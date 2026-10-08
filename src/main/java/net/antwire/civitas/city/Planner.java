package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Finds building sites: rings around the town hall, the entrance turned to the square, flat enough natural ground,
 * nothing of the player's in the way and room left for a path between buildings.
 */
public final class Planner {
	/** Why the last {@link #fit} failed (for messages and debugging). */
	public static String lastReason = "";
	/** Route searches made while planning, failed ones and their time (for the log). */
	public static int routes;
	public static int routeFails;
	public static long routeNanos;

	private Planner() {
	}

	/** Rotation that turns the blueprint's front (south) towards the given direction. */
	public static int rotationFacing(Direction front) {
		return switch (front) {
			case WEST -> Rotation.CLOCKWISE_90.ordinal();
			case NORTH -> Rotation.CLOCKWISE_180.ordinal();
			case EAST -> Rotation.COUNTERCLOCKWISE_90.ordinal();
			default -> Rotation.NONE.ordinal();
		};
	}

	/** A building of the given type centred on (x, z), front towards {@code front}, floor at the ground; null if it doesn't fit. */
	public static @Nullable Building fit(ServerLevel level, City city, BuildingType type, int x, int z, Direction front, int maxStep) {
		// the plot is the grandest tier's; the first tier needs flat dry land, the rest only nobody's builds in the way
		Blueprint bp = Blueprints.of(type, Blueprints.TIERS);
		Blueprint first = Blueprints.of(type, 1);
		int rot = rotationFacing(front);
		Rotation r = Rotation.values()[rot];
		BlockPos half = new BlockPos(bp.w / 2, 0, bp.d / 2).rotate(r);
		BlockPos origin0 = new BlockPos(x - half.getX(), 0, z - half.getZ());
		Building b = new Building(0, type, origin0, rot);
		b.layout = 1;
		b.tier = Blueprints.TIERS;
		// ground heights over the footprint
		Map<Integer, Integer> counts = new HashMap<>();
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int bz = 0; bz < bp.d; bz++) {
			for (int bx = 0; bx < bp.w; bx++) {
				if (bp.get(bx, 0, bz) == null && bp.get(bx, 1, bz) == null) {
					continue;
				}
				boolean core = first.get(bx, 0, bz) != null || first.get(bx, 1, bz) != null;
				BlockPos col = b.world(bx, 0, bz);
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ()) - 1;
				BlockState ground = level.getBlockState(new BlockPos(col.getX(), top, col.getZ()));
				if (core && !ground.getFluidState().isEmpty() || !Construction.natural(ground)) {
					lastReason = "ground at " + col.getX() + " " + top + " " + col.getZ() + " is " + ground.getBlock().getDescriptionId();
					return null;
				}
				for (int up = 1; up <= 3; up++) {
					BlockState above = level.getBlockState(new BlockPos(col.getX(), top + up, col.getZ()));
					if (!Construction.natural(above) || level.getBlockEntity(new BlockPos(col.getX(), top + up, col.getZ())) != null) {
						lastReason = "something built at " + col.getX() + " " + (top + up) + " " + col.getZ() + ": " + above.getBlock().getDescriptionId();
						return null;
					}
				}
				if (!core) {
					continue;
				}
				min = Math.min(min, top);
				max = Math.max(max, top);
				counts.merge(top, 1, Integer::sum);
			}
		}
		if (max - min > maxStep) {
			lastReason = "too steep (" + (max - min) + " blocks)";
			return null;
		}
		int floor = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(min);
		b.origin = new BlockPos(origin0.getX(), floor, origin0.getZ());
		AABB box = b.bounds();
		b.tier = 1;
		for (Building o : city.buildings) {
			if (o.bounds().inflate(2, 0, 2).intersects(box.inflate(0, 64, 0))) {
				lastReason = "overlaps the " + o.type.title;
				return null;
			}
			// nothing goes up on the way to another building's door
			for (BlockPos p : o.approach) {
				if (p.getX() >= box.minX - 1 && p.getX() < box.maxX + 1 && p.getZ() >= box.minZ - 1 && p.getZ() < box.maxZ + 1) {
					lastReason = "in the way to the " + o.type.title;
					return null;
				}
			}
		}
		// and it must have a way in itself
		long t0 = System.nanoTime();
		Access.Route route = Access.route(level, city, b);
		routeNanos += System.nanoTime() - t0;
		routes++;
		if (route == null) {
			routeFails++;
			lastReason = "no way from the door to the square: " + Access.why;
			return null;
		}
		b.approach = new ArrayList<>(route.path());
		b.approachFill = new ArrayList<>(route.fill());
		return b;
	}

	/** Searches the rings around the centre for a site; null if the town has no room left. */
	public static @Nullable Building find(ServerLevel level, City city, BuildingType type) {
		BlockPos c = city.center;
		Blueprint bp = Blueprints.of(type, Blueprints.TIERS);
		int size = Math.max(bp.w, bp.d);
		List<int[]> ring = new ArrayList<>();
		for (int r = 8 + size / 2; r <= city.radius - size / 2; r += 3) {
			ring.clear();
			for (int i = -r; i <= r; i += 3) {
				ring.add(new int[]{c.getX() + i, c.getZ() - r});
				ring.add(new int[]{c.getX() + i, c.getZ() + r});
				ring.add(new int[]{c.getX() - r, c.getZ() + i});
				ring.add(new int[]{c.getX() + r, c.getZ() + i});
			}
			java.util.Collections.shuffle(ring, new java.util.Random(city.id.getLeastSignificantBits() + r + type.ordinal()));
			for (int[] p : ring) {
				int dx = c.getX() - p[0];
				int dz = c.getZ() - p[1];
				Direction front = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
				Building b = fit(level, city, type, p[0], p[1], front, type == BuildingType.FARM ? 2 : 3);
				if (b != null) {
					return b;
				}
			}
		}
		return null;
	}
}
