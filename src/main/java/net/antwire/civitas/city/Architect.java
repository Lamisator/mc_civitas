package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.antwire.civitas.city.Blueprint.Builder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

/**
 * Raises the shell of a building in a plot: a timber-framed body of storeys four blocks high (floor, three of wall), an
 * optional cellar, a gable roof, and inside a switchback staircase along the east wall with railings round the
 * stairwell. Then fills each floor with furniture along the walls, keeping the stairwell and the way from the door free.
 * <p>
 * Plot frame as for every blueprint: x across, z from back (0) to front; the body's front wall stands at {@code frontZ}
 * with the door in the middle of the plot, so it stays in the same place whatever the size of the body.
 */
final class Architect {
	static final int STOREY = 4;
	static final int CELLAR = 4;

	/** The materials of a building. */
	record Style(Block plinth, Block floor, Block wall, Block upper, Block frame, Block door, Block roofStairs, Block roofGable, Block cellarWall,
		Block stairs, Block fence, boolean windows) {
		Style withWall(Block wall) {
			return new Style(this.plinth, this.floor, wall, this.upper, this.frame, this.door, this.roofStairs, this.roofGable, this.cellarWall, this.stairs,
				this.fence, this.windows);
		}

		Style withRoof(Block stairs, Block gable) {
			return new Style(this.plinth, this.floor, this.wall, this.upper, this.frame, this.door, stairs, gable, this.cellarWall, this.stairs, this.fence,
				this.windows);
		}

		Style withUpper(Block upper) {
			return new Style(this.plinth, this.floor, this.wall, upper, this.frame, this.door, this.roofStairs, this.roofGable, this.cellarWall, this.stairs,
				this.fence, this.windows);
		}
	}

	static final Style PLASTER = new Style(Blocks.STONE_BRICKS, Blocks.SPRUCE_PLANKS, Blocks.DYED_TERRACOTTA.white(), Blocks.DYED_TERRACOTTA.white(),
		Blocks.DARK_OAK_LOG, Blocks.SPRUCE_DOOR, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_PLANKS, Blocks.STONE_BRICKS, Blocks.SPRUCE_STAIRS,
		Blocks.SPRUCE_FENCE, true);

	private Architect() {
	}

	/** A plot-sized builder with room for the roof (and a tower or chimney above it) and the cellar below. */
	static Builder plot(int w, int d, int depth, int storeys, boolean cellar, int extraHeight) {
		int roofLevels = (depth + 3) / 2;
		return Blueprint.builder(w, STOREY * storeys + 2 + roofLevels + extraHeight, d, cellar ? CELLAR : 0);
	}

	static final class Body {
		final Builder b;
		final Style st;
		final int x0;
		final int z0;
		final int x1;
		final int z1;
		final int storeys;
		final boolean cellar;
		final int doorX;
		final int roofY;
		/** z of the stairwell's turning point (see {@link #stairs}). */
		final int stairZ;
		/** Cells nobody may furnish, by the feet level of each floor. */
		final Map<Integer, Set<Long>> reserved = new HashMap<>();

		Body(Builder b, Style st, int x0, int z0, int x1, int z1, int storeys, boolean cellar) {
			this.b = b;
			this.st = st;
			this.x0 = x0;
			this.z0 = z0;
			this.x1 = x1;
			this.z1 = z1;
			this.storeys = storeys;
			this.cellar = cellar;
			this.doorX = b.w / 2;
			this.roofY = STOREY * storeys + 1;
			this.stairZ = z0 + 4;
		}

		/** Feet level of floor k (-1 = cellar). */
		int feet(int k) {
			return k < 0 ? -CELLAR + 1 : STOREY * k + 1;
		}

		void reserve(int feet, int x, int z) {
			this.reserved.computeIfAbsent(feet, k -> new HashSet<>()).add(BlockPos.asLong(x, 0, z));
		}

		boolean isReserved(int feet, int x, int z) {
			Set<Long> s = this.reserved.get(feet);
			return s != null && s.contains(BlockPos.asLong(x, 0, z));
		}

		boolean hasStairs() {
			return this.storeys > 1 || this.cellar;
		}

		int midZ() {
			return (this.z0 + this.z1) / 2;
		}
	}

	/** Builds the shell of a body {@code width}×{@code depth} with its front wall at {@code frontZ}, centred across the plot. */
	static Body body(Builder b, Style st, int width, int depth, int frontZ, int storeys, boolean cellar) {
		int x0 = (b.w - width) / 2;
		Body body = new Body(b, st, x0, frontZ - depth + 1, x0 + width - 1, frontZ, storeys, cellar);
		int z0 = body.z0;
		int x1 = body.x1;
		int z1 = body.z1;
		BlockState air = Blocks.AIR.defaultBlockState();
		if (cellar) {
			b.fill(x0, -CELLAR, z0, x1, -CELLAR, z1, st.cellarWall());
			for (int y = -CELLAR + 1; y < 0; y++) {
				for (int x = x0; x <= x1; x++) {
					for (int z = z0; z <= z1; z++) {
						boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
						b.set(x, y, z, edge ? st.cellarWall().defaultBlockState() : air);
					}
				}
			}
			b.set(body.doorX, -2, (z0 + z1) / 2, Builder.lantern());
		}
		b.fill(x0, 0, z0, x1, 0, z1, st.plinth());
		b.fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, st.floor());
		for (int k = 0; k < storeys; k++) {
			int base = STOREY * k;
			for (int y = base + 1; y <= base + STOREY; y++) {
				for (int x = x0; x <= x1; x++) {
					for (int z = z0; z <= z1; z++) {
						boolean ex = x == x0 || x == x1;
						boolean ez = z == z0 || z == z1;
						if (!ex && !ez) {
							// the rooms, and the floor above (the last one is the ceiling under the roof)
							b.set(x, y, z, y == base + STOREY ? st.floor().defaultBlockState() : air);
							continue;
						}
						if (y == base + STOREY) {
							b.set(x, y, z, ex && ez ? Builder.log(st.frame(), Direction.Axis.Y) : Builder.log(st.frame(), ez ? Direction.Axis.X : Direction.Axis.Z));
							continue;
						}
						boolean post = ex && ez || ez && (x - x0) % 4 == 0 || ex && (z - z0) % 4 == 0;
						if (post) {
							b.set(x, y, z, Builder.log(st.frame(), Direction.Axis.Y));
							continue;
						}
						b.set(x, y, z, k == 0 ? st.wall() : st.upper());
						boolean window = st.windows() && y == base + 2 && (ez ? (x - x0) % 4 == 2 : (z - z0) % 4 == 2);
						if (window && !(k == 0 && z == z1 && Math.abs(x - body.doorX) <= 1)) {
							b.set(x, y, z, Blocks.GLASS_PANE);
						}
					}
				}
			}
			b.set(body.doorX, base + 3, (z0 + z1) / 2, Builder.lantern());
		}
		b.door(body.doorX, 1, z1, st.door(), Direction.NORTH);
		b.mark("entrance", body.doorX, 1, z1 + 1);
		// keep the way from the door into the room free
		for (int dx = -1; dx <= 1; dx++) {
			body.reserve(1, body.doorX + dx, z1 - 1);
		}
		body.reserve(1, body.doorX, z1 - 2);
		if (body.hasStairs()) {
			stairs(body);
		}
		b.gableRoof(x0, z0, x1, z1, body.roofY, st.roofStairs(), st.roofGable());
		return body;
	}

	/**
	 * The stairwell, along the inside of the east wall: flights of three steps, up from the cellar and from each floor to
	 * the next, alternating between the two columns next to the wall so that each one starts where the last ended.
	 */
	private static void stairs(Body body) {
		Builder b = body.b;
		int a = body.stairZ;
		int cA = body.x1 - 1;
		int cB = body.x1 - 2;
		List<int[]> holes = new ArrayList<>();
		Set<Long> keep = new HashSet<>();
		List<Integer> runs = new ArrayList<>();
		if (body.cellar) {
			runs.add(-1);
		}
		for (int k = 0; k < body.storeys - 1; k++) {
			runs.add(k);
		}
		for (int k : runs) {
			// a flight from floor level f (the floor block) up to f + 4
			int f = k < 0 ? -CELLAR : STOREY * k;
			boolean even = Math.floorMod(k, 2) == 0;
			int col = even ? cA : cB;
			for (int i = 0; i < 3; i++) {
				int z = even ? a - i : a - 2 + i;
				int y = f + 1 + i;
				b.set(col, y, z, Builder.stairs(body.st.stairs(), even ? Direction.NORTH : Direction.SOUTH, false));
				for (int below = f + 1; below < y; below++) {
					b.set(col, below, z, body.st.floor());
				}
				holes.add(new int[]{col, f + STOREY, z});
			}
			// where one comes on and gets off the flight
			int on = even ? a + 1 : a - 3;
			int off = even ? a - 3 : a + 1;
			keep.add(BlockPos.asLong(col, f, on));
			keep.add(BlockPos.asLong(col, f + STOREY, off));
		}
		for (int[] h : holes) {
			b.set(h[0], h[1], h[2], Blocks.AIR);
		}
		// railings where a floor ends at the stairwell
		Set<Long> holeSet = new HashSet<>();
		for (int[] h : holes) {
			holeSet.add(BlockPos.asLong(h[0], h[1], h[2]));
		}
		for (int[] h : holes) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				int nx = h[0] + d.getStepX();
				int nz = h[2] + d.getStepZ();
				if (nx <= body.x0 || nx >= body.x1 || nz <= body.z0 || nz >= body.z1 || holeSet.contains(BlockPos.asLong(nx, h[1], nz))
					|| keep.contains(BlockPos.asLong(nx, h[1], nz))) {
					continue;
				}
				BlockState under = b.get(nx, h[1], nz);
				BlockState feet = b.get(nx, h[1] + 1, nz);
				if (under != null && !under.isAir() && feet != null && feet.isAir()) {
					b.set(nx, h[1] + 1, nz, body.st.fence());
				}
			}
		}
		// nobody puts furniture in the stairwell
		List<Integer> floors = new ArrayList<>();
		if (body.cellar) {
			floors.add(-1);
		}
		for (int k = 0; k < body.storeys; k++) {
			floors.add(k);
		}
		for (int k : floors) {
			for (int x = body.x1 - 3; x <= body.x1 - 1; x++) {
				for (int z = a - 3; z <= a + 1; z++) {
					body.reserve(body.feet(k), x, z);
				}
			}
		}
	}

	/** Places furniture along the walls of one floor, in order: back wall, west wall, front wall, east wall. */
	static final class Furnisher {
		final Body body;
		final int y;
		private final List<int[]> slots = new ArrayList<>();
		private int next;

		Furnisher(Body body, int floor) {
			this.body = body;
			this.y = body.feet(floor);
			int x0 = body.x0 + 1;
			int x1 = body.x1 - 1;
			int z0 = body.z0 + 1;
			int z1 = body.z1 - 1;
			// x, z, inward dx, inward dz
			for (int x = x0; x <= x1; x++) {
				this.slots.add(new int[]{x, z0, 0, 1});
			}
			for (int z = z0 + 1; z <= z1; z++) {
				this.slots.add(new int[]{x0, z, 1, 0});
			}
			for (int x = x0 + 1; x <= x1; x++) {
				this.slots.add(new int[]{x, z1, 0, -1});
			}
			for (int z = z1 - 1; z > z0; z--) {
				this.slots.add(new int[]{x1, z, -1, 0});
			}
		}

		private boolean free(int x, int z) {
			Builder b = this.body.b;
			BlockState here = b.get(x, this.y, z);
			BlockState under = b.get(x, this.y - 1, z);
			return here != null && here.isAir() && under != null && !under.isAir() && !this.body.isReserved(this.y, x, z);
		}

		/** The next free spot along the wall (with the cell in front of it free too when {@code needsFront}); null when full. */
		private int @Nullable [] take(boolean needsFront) {
			while (this.next < this.slots.size()) {
				int[] s = this.slots.get(this.next++);
				if (this.free(s[0], s[1]) && (!needsFront || this.free(s[0] + s[2], s[1] + s[3]))) {
					return s;
				}
			}
			return null;
		}

		private static Direction dir(int[] s) {
			return Direction.getApproximateNearest(s[2], 0, s[3]);
		}

		/** A block against the wall, facing into the room. */
		@Nullable BlockPos put(Block block) {
			return this.put(block, false);
		}

		@Nullable BlockPos put(Block block, boolean keepFront) {
			int[] s = this.take(keepFront);
			if (s == null) {
				return null;
			}
			this.body.b.set(s[0], this.y, s[1], Builder.facing(block, dir(s)));
			if (keepFront) {
				this.body.reserve(this.y, s[0] + s[2], s[1] + s[3]);
			}
			return new BlockPos(s[0], this.y, s[1]);
		}

		/** Something to stack on top of the last piece (a lantern on a barrel, a second barrel). */
		void top(@Nullable BlockPos at, BlockState s) {
			if (at != null) {
				this.body.b.set(at.getX(), at.getY() + 1, at.getZ(), s);
			}
		}

		/** A chest the business keeps its stock in. */
		@Nullable BlockPos storage() {
			BlockPos p = this.put(Blocks.CHEST);
			if (p != null) {
				this.body.b.mark("storage", p.getX(), p.getY(), p.getZ());
			}
			return p;
		}

		/** A workstation and the place in front of it where the worker stands. */
		@Nullable BlockPos work(Block block) {
			int[] s = this.take(true);
			if (s == null) {
				return null;
			}
			Builder b = this.body.b;
			b.set(s[0], this.y, s[1], Builder.facing(block, dir(s)));
			b.mark("workblock", s[0], this.y, s[1]);
			b.mark("work", s[0] + s[2], this.y, s[1] + s[3]);
			this.body.reserve(this.y, s[0] + s[2], s[1] + s[3]);
			return new BlockPos(s[0], this.y, s[1]);
		}

		/** A bed with its head to the wall. */
		@Nullable BlockPos bed(Block bed) {
			int[] s = this.take(true);
			if (s == null) {
				return null;
			}
			Direction head = dir(s).getOpposite();
			this.body.b.bed(s[0] + s[2], this.y, s[1] + s[3], bed, head);
			this.body.reserve(this.y, s[0] + s[2], s[1] + s[3]);
			return new BlockPos(s[0] + s[2], this.y, s[1] + s[3]);
		}

		/** Skips the next spot (to leave a gap). */
		void gap() {
			this.take(false);
		}

		int left() {
			int n = 0;
			for (int i = this.next; i < this.slots.size(); i++) {
				int[] s = this.slots.get(i);
				if (this.free(s[0], s[1])) {
					n++;
				}
			}
			return n;
		}
	}

	/** A chimney up through the roof, smoking from a campfire on top. */
	static void chimney(Body body, int x, int z, Block block) {
		int top = body.roofY + (body.z1 - body.z0 + 3) / 2 + 1;
		for (int y = 1; y <= top; y++) {
			body.b.set(x, y, z, block);
		}
		body.b.set(x, top + 1, z, Blocks.CAMPFIRE);
	}

	/** A wall lantern (torch) either side of the front door. */
	static void doorLights(Body body) {
		for (int dx : new int[]{-2, 2}) {
			body.b.set(body.doorX + dx, 2, body.z1 + 1, Builder.facing(Blocks.WALL_TORCH, Direction.SOUTH));
		}
	}

	/** Open shutters beside the windows of the back and west walls (grander houses). */
	static void shutters(Body body, Block trapdoor) {
		Builder b = body.b;
		for (int k = 0; k < body.storeys; k++) {
			int y = STOREY * k + 2;
			for (int x = body.x0; x <= body.x1; x++) {
				BlockState s = b.get(x, y, body.z0);
				if (s != null && s.is(Blocks.GLASS_PANE)) {
					for (int dx : new int[]{-1, 1}) {
						b.set(x + dx, y, body.z0 - 1, trapdoor.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
							.setValue(BlockStateProperties.OPEN, true));
					}
				}
			}
			for (int z = body.z0; z <= body.z1; z++) {
				BlockState s = b.get(body.x0, y, z);
				if (s != null && s.is(Blocks.GLASS_PANE)) {
					for (int dz : new int[]{-1, 1}) {
						b.set(body.x0 - 1, y, z + dz, trapdoor.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
							.setValue(BlockStateProperties.OPEN, true));
					}
				}
			}
		}
	}
}
