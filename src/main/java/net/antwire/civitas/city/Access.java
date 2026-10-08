package net.antwire.civitas.city;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.antwire.civitas.Civitas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Makes sure people can get into what the town builds.
 * <ul>
 * <li>Blueprints: nothing may stand in a doorway, and every place someone works, sleeps, shops or sits must be reachable
 * from the entrance ({@link #lint}).</li>
 * <li>Sites: every building gets a walkable way from its door to the town square, found over the real terrain - it may
 * cut through a bank or bank up a dip a little, never through someone's build ({@link #route}). The way is built with the
 * building and no later building may be put on it.</li>
 * <li>Built towns: once a day each building is checked - doorway, rooms and the way to the square - and what nature put
 * in the way (snow, saplings, sand, a fallen tree) is cleared; a way blocked by a player's build is routed around, or
 * reported if there is none ({@link #audit}).</li>
 * </ul>
 */
public final class Access {
	/** Marks that are not places to walk to: doors themselves, the mine shaft, the barred prison cells. */
	private static final Set<String> NOT_DESTINATIONS = Set.of("door", "backdoor", "entrance", "shaft", "cell", "cellbed", "bell", "rack");
	private static final int MAX_FILL = 3;
	private static final int MAX_NODES = 25000;

	private Access() {
	}

	// ------------------------------------------------------------------ what a body can walk through and stand on

	/** Feet room: empty, a door or gate, or something low enough to step onto (slab, bed, carpet, snow layer). */
	static boolean walkThrough(BlockState s) {
		if (s.isAir() || s.getBlock() instanceof DoorBlock || s.getBlock() instanceof FenceGateBlock) {
			return true;
		}
		if (!s.getFluidState().isEmpty()) {
			return false;
		}
		var shape = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.6 && !(s.getBlock() instanceof FenceBlock || s.getBlock() instanceof WallBlock);
	}

	/** Head room: nothing solid at all (an open door counts as open). */
	static boolean headRoom(BlockState s) {
		if (s.isAir() || s.getBlock() instanceof DoorBlock || s.getBlock() instanceof FenceGateBlock) {
			return true;
		}
		return s.getFluidState().isEmpty() && s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	/** Something to stand on (not a fence or wall, which nobody climbs, and not leaves). */
	static boolean floor(BlockState s) {
		if (s.isAir() || !s.getFluidState().isEmpty() || s.is(BlockTags.LEAVES) || s.getBlock() instanceof FenceBlock || s.getBlock() instanceof WallBlock
			|| s.getBlock() instanceof FenceGateBlock || s.is(Blocks.CACTUS) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.POWDER_SNOW)) {
			return false;
		}
		return !s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	/** In the way, but nature's: a builder may dig it out. */
	static boolean clearable(ServerLevel level, BlockPos p, BlockState s) {
		return s.getFluidState().isEmpty() && Construction.natural(s) && s.getDestroySpeed(level, p) >= 0 && level.getBlockEntity(p) == null;
	}

	/**
	 * Digs out one block, and the sand or gravel resting on it with it (or it would slide into the hole later and
	 * block the doorway again). Returns the number of blocks removed.
	 */
	public static int dig(ServerLevel level, BlockPos p, int flags) {
		level.setBlock(p, Blocks.AIR.defaultBlockState(), flags);
		int n = 1;
		BlockPos.MutableBlockPos up = p.mutable().move(Direction.UP);
		for (int k = 0; k < 12; k++, up.move(Direction.UP)) {
			BlockState s = level.getBlockState(up);
			if (!(s.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) || !clearable(level, up, s)) {
				break;
			}
			level.setBlock(up, Blocks.AIR.defaultBlockState(), flags);
			n++;
		}
		return n;
	}

	// ------------------------------------------------------------------ blueprints

	/** Doors must open onto free space on both sides; anything the blueprint put there is taken out. */
	static void clearDoorways(Blueprint.Builder b, String what) {
		for (String mark : new String[]{"door", "backdoor"}) {
			for (BlockPos d : b.marks.getOrDefault(mark, List.of())) {
				BlockState door = b.get(d.getX(), d.getY(), d.getZ());
				if (door == null || !(door.getBlock() instanceof DoorBlock)) {
					continue;
				}
				Direction f = door.getValue(DoorBlock.FACING);
				for (Direction side : new Direction[]{f, f.getOpposite()}) {
					for (int up = 0; up <= 1; up++) {
						int x = d.getX() + side.getStepX();
						int y = d.getY() + up;
						int z = d.getZ() + side.getStepZ();
						BlockState s = b.get(x, y, z);
						if (s != null && !(up == 0 ? walkThrough(s) : headRoom(s))) {
							Civitas.LOGGER.warn("{}: {} in the doorway at {} {} {} - removed", what, s.getBlock().getDescriptionId(), x, y, z);
							b.set(x, y, z, Blocks.AIR.defaultBlockState());
						}
					}
				}
			}
		}
	}

	private static @Nullable BlockState cell(Blueprint bp, int x, int y, int z) {
		return bp.get(x, y, z);
	}

	/** Blueprint cell as feet room; outside the blueprint and in cells it leaves alone, open ground is assumed. */
	private static boolean bpFeet(Blueprint bp, int x, int y, int z) {
		BlockState s = cell(bp, x, y, z);
		return s == null ? y >= 1 : walkThrough(s);
	}

	private static boolean bpHead(Blueprint bp, int x, int y, int z) {
		BlockState s = cell(bp, x, y, z);
		return s == null ? y >= 1 : headRoom(s);
	}

	private static boolean bpFloor(Blueprint bp, int x, int y, int z) {
		BlockState s = cell(bp, x, y, z);
		if (s == null) {
			return y <= 0;
		}
		return floor(s);
	}

	private static boolean bpStand(Blueprint bp, int x, int y, int z) {
		if (!bpFeet(bp, x, y, z) || !bpHead(bp, x, y + 1, z)) {
			return false;
		}
		// standing on a slab or bed in the feet cell, or on the floor below
		BlockState feet = cell(bp, x, y, z);
		return feet != null && !feet.isAir() && !headRoom(feet) || bpFloor(bp, x, y - 1, z);
	}

	/**
	 * Walks the blueprint from its entrance and lists every mark nobody could get to (empty when the building works).
	 * Outside the blueprint is taken to be level open ground.
	 */
	public static List<String> lint(BuildingType type, int tier) {
		Blueprint bp = Blueprints.of(type, tier);
		BlockPos start = bp.mark("entrance");
		if (start == null) {
			start = new BlockPos(bp.w / 2, 1, bp.d);
		}
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.add(start);
		queue.add(start);
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				for (int dy = 1; dy >= -2; dy--) {
					BlockPos n = p.offset(dir.getStepX(), dy, dir.getStepZ());
					if (n.getX() < -2 || n.getZ() < -2 || n.getX() > bp.w + 1 || n.getZ() > bp.d + 1 || n.getY() < bp.minY() + 1 || n.getY() >= bp.h) {
						continue;
					}
					// up a step needs head room over the start, down needs room to drop through
					if (dy == 1 && !bpHead(bp, p.getX(), p.getY() + 2, p.getZ())) {
						continue;
					}
					boolean drop = true;
					for (int k = 0; k > dy; k--) {
						drop &= bpFeet(bp, n.getX(), p.getY() + k, n.getZ()) && bpHead(bp, n.getX(), p.getY() + k + 1, n.getZ());
					}
					if (dy < 0 && !drop) {
						continue;
					}
					if (bpStand(bp, n.getX(), n.getY(), n.getZ()) && seen.add(n)) {
						queue.add(n);
						break;
					}
				}
			}
		}
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, List<BlockPos>> e : bp.marks.entrySet()) {
			if (NOT_DESTINATIONS.contains(e.getKey())) {
				continue;
			}
			for (BlockPos m : e.getValue()) {
				if (!near(seen, m)) {
					out.add(type.id() + " tier " + tier + ": " + e.getKey() + " at " + m.getX() + " " + m.getY() + " " + m.getZ() + " can't be reached");
				}
			}
		}
		return out;
	}

	private static boolean near(Set<BlockPos> seen, BlockPos m) {
		for (int dy = -1; dy <= 1; dy++) {
			if (seen.contains(m.above(dy))) {
				return true;
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				if (seen.contains(m.relative(d).above(dy))) {
					return true;
				}
			}
		}
		return false;
	}

	/** Checks every blueprint once at start-up and logs what's wrong (the test tour fails on it). */
	public static List<String> lintAll() {
		List<String> all = new ArrayList<>();
		for (BuildingType t : BuildingType.values()) {
			for (int tier = 1; tier <= Blueprints.TIERS; tier++) {
				all.addAll(lint(t, tier));
			}
		}
		for (String s : all) {
			Civitas.LOGGER.warn("Blueprint: {}", s);
		}
		return all;
	}

	// ------------------------------------------------------------------ the way to the square

	/** A way from a door: the ground block of each step, and the blocks to bank up under it (bottom first). */
	public record Route(List<BlockPos> path, List<BlockPos> fill) {
	}

	/** Why the last {@link #route} found nothing. */
	public static String why = "";

	private static Rotation inverse(Rotation r) {
		return switch (r) {
			case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
			case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
			default -> r;
		};
	}

	/** World position in a building's own blueprint frame. */
	static BlockPos local(Building b, BlockPos world) {
		return world.subtract(b.origin).rotate(inverse(b.rot()));
	}

	private static boolean inside(Building b, int x, int z) {
		var box = b.bounds();
		return x >= box.minX && x < box.maxX && z >= box.minZ && z < box.maxZ;
	}

	/** Natural ground of a column: the top solid block under trees, plants and water. */
	static int ground(ServerLevel level, int x, int z) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
		while (y > level.getMinY()) {
			BlockState s = level.getBlockState(p.setY(y));
			if (floor(s) && !s.is(BlockTags.LOGS)) {
				break;
			}
			y--;
		}
		return y;
	}

	/** What walking a column at ground height y takes: -1 impossible, else the extra effort; fills and cuts are collected. */
	/** Which building's plot each column belongs to (worked out once per search). */
	private static Map<Long, Building> plots(List<Building> buildings) {
		Map<Long, Building> out = new HashMap<>();
		for (Building b : buildings) {
			var box = b.bounds();
			for (int x = (int) box.minX; x < (int) box.maxX; x++) {
				for (int z = (int) box.minZ; z < (int) box.maxZ; z++) {
					out.putIfAbsent(BlockPos.asLong(x, 0, z), b);
				}
			}
		}
		return out;
	}

	private static double effort(ServerLevel level, Map<Long, Building> plots, int x, int y, int z, @Nullable List<BlockPos> fills,
		@Nullable List<BlockPos> cuts) {
		double cost = 0;
		BlockState bpFloor = null;
		BlockState bpFeet = null;
		BlockState bpHeadS = null;
		boolean dictated = false;
		Building owner = plots.get(BlockPos.asLong(x, 0, z));
		for (Building b : owner == null ? List.<Building>of() : List.of(owner)) {
			// on a building's land only at its floor level, and only where its blueprint leaves room
			if (y != b.origin.getY()) {
				return -1;
			}
			BlockPos rel = local(b, new BlockPos(x, y, z));
			// plan the way round what the building will become, not just what stands
			Blueprint bp = b.layout == 0 ? b.blueprint() : Blueprints.of(b.type, Blueprints.TIERS);
			bpFloor = bp.get(rel.getX(), 0, rel.getZ());
			bpFeet = bp.get(rel.getX(), 1, rel.getZ());
			bpHeadS = bp.get(rel.getX(), 2, rel.getZ());
			if (bpFloor != null && !floor(bpFloor) || bpFeet != null && !walkThrough(bpFeet) || bpHeadS != null && !headRoom(bpHeadS)) {
				return -1;
			}
			dictated = true;
			break;
		}
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		// feet and head
		for (int up = 1; up <= 2; up++) {
			if (dictated && (up == 1 ? bpFeet : bpHeadS) != null) {
				continue;
			}
			p.set(x, y + up, z);
			BlockState s = level.getBlockState(p);
			if (up == 1 ? walkThrough(s) : headRoom(s)) {
				if (s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)) {
					return -1;
				}
				continue;
			}
			if (!clearable(level, p, s)) {
				return -1;
			}
			cost += 2;
			if (cuts != null) {
				cuts.add(p.immutable());
			}
		}
		// the ground underfoot
		if (!(dictated && bpFloor != null)) {
			p.set(x, y, z);
			BlockState s = level.getBlockState(p);
			if (!floor(s)) {
				if (s.is(Blocks.LAVA) || !s.canBeReplaced() && s.getFluidState().isEmpty() && !clearable(level, p, s)) {
					return -1;
				}
				// bank it up, down to something solid
				List<BlockPos> col = new ArrayList<>();
				int yy = y;
				while (true) {
					BlockState t = level.getBlockState(p.setY(yy));
					if (floor(t)) {
						break;
					}
					if (y - yy >= MAX_FILL || !(t.canBeReplaced() || !t.getFluidState().isEmpty()) || t.is(Blocks.LAVA)) {
						return -1;
					}
					col.add(0, p.immutable());
					yy--;
				}
				cost += 3 * col.size();
				if (fills != null) {
					fills.addAll(col);
				}
			}
		}
		return cost;
	}

	private record Open(double f, Node node) {
	}

	private record Node(int x, int y, int z) {
		long key() {
			return BlockPos.asLong(this.x, this.y, this.z);
		}
	}

	/**
	 * The cheapest walkable way from the building's door to the town square (or, for the town hall, out onto open
	 * ground), over the terrain as it is and the other buildings as they will be. Null when there is none.
	 */
	public static @Nullable Route route(ServerLevel level, City city, Building b) {
		List<Building> list = new ArrayList<>(city.buildings);
		if (!list.contains(b)) {
			list.add(b);
		}
		Map<Long, Building> buildings = plots(list);
		// the town's roads: reaching one of them is as good as reaching the square
		java.util.Set<Long> roads = new HashSet<>();
		for (Building o : city.buildings) {
			if (o != b && o.complete || o != b && !o.approach.isEmpty()) {
				for (BlockPos p : o.approach) {
					roads.add(p.asLong());
				}
			}
		}
		BlockPos entrance = b.entrance();
		Node start = new Node(entrance.getX(), entrance.getY() - 1, entrance.getZ());
		Building hall = city.townHall();
		BlockPos goal;
		boolean outward = hall == null || hall == b;
		if (outward) {
			// out of the building's front by a few steps
			BlockPos front = b.world(b.blueprint().w / 2, 0, b.blueprint().d + 3);
			goal = front;
		} else {
			BlockPos plaza = hall.mark("plaza");
			goal = plaza == null ? hall.entrance() : plaza;
		}
		var own = b.bounds().inflate(2, 0, 2);
		int limit = Math.max(48, Math.abs(goal.getX() - start.x) + Math.abs(goal.getZ() - start.z) + 32);
		PriorityQueue<Open> open = new PriorityQueue<>((p, q) -> Double.compare(p.f(), q.f()));
		Map<Long, Double> best = new HashMap<>();
		Map<Long, Node> from = new HashMap<>();
		if (effort(level, buildings, start.x, start.y, start.z, null, null) < 0) {
			why = "the doorstep at " + start.x + " " + start.y + " " + start.z + " can't be walked";
			return null;
		}
		best.put(start.key(), 0.0);
		open.add(new Open(h(start, goal), start));
		Node end = null;
		int expanded = 0;
		while (!open.isEmpty() && expanded++ < MAX_NODES) {
			Open top = open.poll();
			Node n = top.node();
			double g = best.get(n.key());
			if (top.f() > g + h(n, goal) + 1.0E-6) {
				continue;
			}
			boolean arrived = outward
				? !(n.x >= own.minX && n.x < own.maxX && n.z >= own.minZ && n.z < own.maxZ) && Math.abs(n.x - goal.getX()) + Math.abs(n.z - goal.getZ()) <= 4
				: Math.abs(n.x - goal.getX()) + Math.abs(n.z - goal.getZ()) <= 1 && Math.abs(n.y + 1 - goal.getY()) <= 1
					|| n != start && roads.contains(n.key());
			if (arrived) {
				end = n;
				break;
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				int nx = n.x + d.getStepX();
				int nz = n.z + d.getStepZ();
				if (Math.abs(nx - start.x) > limit || Math.abs(nz - start.z) > limit) {
					continue;
				}
				for (int dy = -1; dy <= 1; dy++) {
					int ny = n.y + dy;
					// stepping up needs head room above where you stand, stepping down head room above where you land
					if (dy == 1 && !headRoom(level.getBlockState(new BlockPos(n.x, n.y + 3, n.z)))
						&& !clearable(level, new BlockPos(n.x, n.y + 3, n.z), level.getBlockState(new BlockPos(n.x, n.y + 3, n.z)))) {
						continue;
					}
					double e = effort(level, buildings, nx, ny, nz, null, null);
					if (e < 0) {
						continue;
					}
					double cost = g + 1 + e + (dy != 0 ? 0.6 : 0);
					Node m = new Node(nx, ny, nz);
					Double old = best.get(m.key());
					if (old == null || cost < old - 1.0E-6) {
						best.put(m.key(), cost);
						from.put(m.key(), n);
						open.add(new Open(cost + h(m, goal), m));
					}
				}
			}
		}
		if (end == null) {
			why = "no way found after " + expanded + " steps from " + start.x + " " + start.y + " " + start.z + " to " + goal.toShortString();
			return null;
		}
		List<Node> chain = new ArrayList<>();
		for (Node n = end; n != null; n = from.get(n.key())) {
			chain.add(0, n);
		}
		List<BlockPos> path = new ArrayList<>();
		List<BlockPos> fill = new ArrayList<>();
		for (Node n : chain) {
			path.add(new BlockPos(n.x, n.y, n.z));
			effort(level, buildings, n.x, n.y, n.z, fill, null);
		}
		return new Route(path, fill);
	}

	private static double h(Node n, BlockPos goal) {
		return Math.abs(n.x - goal.getX()) + Math.abs(n.z - goal.getZ());
	}

	/** Is the column a step of any building's way (so nothing may be built on it)? */
	public static boolean onAWay(City city, int x, int z) {
		for (Building o : city.buildings) {
			for (BlockPos p : o.approach) {
				if (p.getX() == x && p.getZ() == z) {
					return true;
				}
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ keeping it so

	/** Who can stand at p in the world right now. */
	static boolean standable(ServerLevel level, BlockPos feet) {
		BlockState f = level.getBlockState(feet);
		BlockState h = level.getBlockState(feet.above());
		BlockState below = level.getBlockState(feet.below());
		return walkThrough(f) && headRoom(h) && (floor(below) || !f.isAir() && !headRoom(f));
	}

	/** The result of checking a building. */
	public record Audit(int cleared, int filled, boolean rerouted, @Nullable String blocked) {
	}

	/**
	 * Checks that the building can be entered and its rooms used, and puts right what it can: clears nature out of the
	 * doorways, rooms and the way, banks up holes in the way, and finds a new way if a player built over the old one.
	 */
	public static Audit audit(ServerLevel level, City city, Building b) {
		int cleared = 0;
		int filled = 0;
		boolean rerouted = false;
		String blocked = null;
		Blueprint bp = b.blueprint();
		// rooms and doorways: whatever the blueprint keeps free must be free
		for (int y = bp.minY() + 1; y < bp.h; y++) {
			for (int z = 0; z < bp.d; z++) {
				for (int x = 0; x < bp.w; x++) {
					BlockState want = bp.get(x, y, z);
					if (want == null || !want.isAir()) {
						continue;
					}
					BlockPos p = b.world(x, y, z);
					BlockState now = level.getBlockState(p);
					if (now.isAir() || headRoom(now) && !now.is(BlockTags.SAPLINGS) && !now.is(Blocks.SNOW)) {
						continue;
					}
					if (clearable(level, p, now)) {
						cleared += dig(level, p, Block.UPDATE_ALL);
					}
				}
			}
		}
		// the doorways themselves
		for (BlockPos d : b.marks("door")) {
			for (int up = 0; up <= 1; up++) {
				BlockState s = level.getBlockState(d.above(up));
				if (!(s.getBlock() instanceof DoorBlock) && !headRoom(s)) {
					if (clearable(level, d.above(up), s)) {
						cleared += dig(level, d.above(up), Block.UPDATE_ALL);
					} else {
						blocked = "door at " + d.toShortString() + " walled up with " + s.getBlock().getName().getString();
					}
				}
			}
		}
		// the way: each step walkable and no step too high
		if (b.approach.isEmpty() && city.townHall() != null) {
			Route r = route(level, city, b);
			if (r != null) {
				b.approach = new ArrayList<>(r.path());
				b.approachFill = new ArrayList<>(r.fill());
				rerouted = true;
			}
		}
		boolean broken = false;
		BlockPos prev = null;
		for (BlockPos g : b.approach) {
			for (int up = 1; up <= 2; up++) {
				BlockPos p = g.above(up);
				BlockState s = level.getBlockState(p);
				if (!(up == 1 ? walkThrough(s) : headRoom(s))) {
					if (clearable(level, p, s)) {
						cleared += dig(level, p, Block.UPDATE_ALL);
					} else if (!owned(city, p)) {
						broken = true;
					}
				}
			}
			BlockState under = level.getBlockState(g);
			if (!floor(under) && !owned(city, g)) {
				if ((under.canBeReplaced() || !under.getFluidState().isEmpty()) && !under.is(Blocks.LAVA)) {
					// a hole in the way: fill it if it's shallow
					int depth = 0;
					while (depth <= MAX_FILL && !floor(level.getBlockState(g.below(depth + 1)))) {
						depth++;
					}
					if (depth < MAX_FILL) {
						for (int k = depth; k >= 0; k--) {
							level.setBlock(g.below(k), (k == 0 ? Blocks.DIRT_PATH : Blocks.COBBLESTONE).defaultBlockState(), Block.UPDATE_ALL);
							filled++;
						}
					} else {
						broken = true;
					}
				} else {
					broken = true;
				}
			}
			if (prev != null && Math.abs(prev.getY() - g.getY()) > 1) {
				broken = true;
			}
			prev = g;
		}
		if (broken) {
			Route r = route(level, city, b);
			if (r == null) {
				blocked = "no way from the door to the square";
			} else {
				b.approach = new ArrayList<>(r.path());
				b.approachFill = new ArrayList<>(r.fill());
				rerouted = true;
			}
		}
		if (rerouted) {
			int[] done = build(level, b);
			cleared += done[0];
			filled += done[1];
		}
		// finally: can one actually walk from outside the door to every room?
		if (blocked == null) {
			blocked = rooms(level, b);
		}
		return new Audit(cleared, filled, rerouted, blocked);
	}

	/** Part of some building's blueprint (those blocks are the building's business, not the way's). */
	public static boolean owned(City city, BlockPos p) {
		for (Building o : city.buildings) {
			if (inside(o, p.getX(), p.getZ())) {
				BlockPos rel = local(o, p);
				if (o.blueprint().get(rel.getX(), rel.getY(), rel.getZ()) != null) {
					return true;
				}
			}
		}
		return false;
	}

	/** Lays the building's way at once (for repairs and the complete command): banks up, digs out, paves. */
	public static int[] build(ServerLevel level, Building b) {
		int cleared = 0;
		int filled = 0;
		for (BlockPos f : b.approachFill) {
			BlockState s = level.getBlockState(f);
			if (s.canBeReplaced() || !s.getFluidState().isEmpty()) {
				level.setBlock(f, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
				filled++;
			}
		}
		for (BlockPos g : b.approach) {
			for (int up = 2; up >= 1; up--) {
				BlockPos p = g.above(up);
				BlockState s = level.getBlockState(p);
				if (!s.isAir() && !(up == 1 ? walkThrough(s) : headRoom(s)) && clearable(level, p, s)) {
					cleared += dig(level, p, Block.UPDATE_ALL);
				} else if (s.canBeReplaced() && !s.isAir() && s.getFluidState().isEmpty()) {
					level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
			BlockState under = level.getBlockState(g);
			if (under.is(Blocks.GRASS_BLOCK) || under.is(Blocks.DIRT) || under.is(Blocks.COARSE_DIRT) || under.is(Blocks.PODZOL)) {
				level.setBlock(g, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		return new int[]{cleared, filled};
	}

	/**
	 * Walks the real building from just outside its door and returns what keeps people from a room, or null when every
	 * place is reachable.
	 */
	public static @Nullable String rooms(ServerLevel level, Building b) {
		var box = b.bounds().inflate(2, 0, 2);
		BlockPos start = b.entrance();
		if (!standable(level, start)) {
			BlockState s = level.getBlockState(start);
			return "the entrance at " + start.toShortString() + " is blocked by " + s.getBlock().getName().getString();
		}
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.add(start);
		queue.add(start);
		while (!queue.isEmpty() && seen.size() < 6000) {
			BlockPos p = queue.poll();
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				for (int dy = 1; dy >= -2; dy--) {
					BlockPos n = p.offset(dir.getStepX(), dy, dir.getStepZ());
					if (n.getX() < box.minX || n.getX() >= box.maxX || n.getZ() < box.minZ || n.getZ() >= box.maxZ || n.getY() < box.minY - 1 || n.getY() > box.maxY) {
						continue;
					}
					if (dy == 1 && !headRoom(level.getBlockState(p.above(2)))) {
						continue;
					}
					if (dy < 0 && !(walkThrough(level.getBlockState(n.above(-dy))) && headRoom(level.getBlockState(n.above(-dy + 1))))) {
						continue;
					}
					if (standable(level, n) && seen.add(n)) {
						queue.add(n);
						break;
					}
				}
			}
		}
		for (Map.Entry<String, List<BlockPos>> e : b.blueprint().marks.entrySet()) {
			if (NOT_DESTINATIONS.contains(e.getKey()) || e.getKey().equals("field") || e.getKey().equals("pen") || e.getKey().equals("plaza")) {
				continue;
			}
			for (BlockPos rel : e.getValue()) {
				BlockPos m = b.world(rel);
				if (!near(seen, m)) {
					return "the " + e.getKey() + " at " + m.toShortString() + " can't be reached";
				}
			}
		}
		return null;
	}
}
