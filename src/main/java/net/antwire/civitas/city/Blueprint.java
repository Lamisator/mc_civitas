package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import org.jspecify.annotations.Nullable;

/**
 * A building's blocks in its own frame: x across, y up, z from back (0) to front (d-1); the entrance faces +z (south).
 * Null cells are left alone; air cells are cleared. Marks name special spots (beds, workstations, storage, ...).
 */
public final class Blueprint {
	public final int w;
	public final int h;
	public final int d;
	/** How far it reaches below the ground floor (a cellar): y runs from -base to h-1. */
	public final int base;
	private final BlockState[] states;
	public final Map<String, List<BlockPos>> marks;

	private Blueprint(int w, int h, int d, int base, BlockState[] states, Map<String, List<BlockPos>> marks) {
		this.w = w;
		this.h = h;
		this.d = d;
		this.base = base;
		this.states = states;
		this.marks = marks;
	}

	public @Nullable BlockState get(int x, int y, int z) {
		if (x < 0 || y < -this.base || z < 0 || x >= this.w || y >= this.h || z >= this.d) {
			return null;
		}
		return this.states[((y + this.base) * this.d + z) * this.w + x];
	}

	/** Lowest y with cells. */
	public int minY() {
		return -this.base;
	}

	public List<BlockPos> marks(String name) {
		return this.marks.getOrDefault(name, List.of());
	}

	public @Nullable BlockPos mark(String name) {
		List<BlockPos> l = this.marks(name);
		return l.isEmpty() ? null : l.getFirst();
	}

	public static Builder builder(int w, int h, int d) {
		return new Builder(w, h, d, 0);
	}

	public static Builder builder(int w, int h, int d, int base) {
		return new Builder(w, h, d, base);
	}

	public static final class Builder {
		final int w;
		final int h;
		final int d;
		final int base;
		final BlockState[] states;
		final Map<String, List<BlockPos>> marks = new LinkedHashMap<>();

		Builder(int w, int h, int d, int base) {
			this.w = w;
			this.h = h;
			this.d = d;
			this.base = base;
			this.states = new BlockState[w * (h + base) * d];
		}

		public Blueprint build() {
			Access.clearDoorways(this, "blueprint");
			this.lightPockets();
			return new Blueprint(this.w, this.h, this.d, this.base, this.states, this.marks);
		}

		/** Light reaches this far from a light block (level 15) before it is too dim to keep monsters away. */
		static final int LIGHT_REACH = 12;

		/** Can air (and so light and monsters) get through this cell? */
		private static boolean open(@Nullable BlockState s) {
			if (s == null || s.isAir() || s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
				|| s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock || s.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock) {
				return true;
			}
			return s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
		}

		/**
		 * Sealed air under roofs, behind gables and in wall cavities is never walked into and never lit, so monsters would
		 * spawn there and scare the town. Every pocket of air that can't be reached from outside or through a door gets
		 * hidden light blocks, close enough together that no spot in it stays dark.
		 */
		void lightPockets() {
			int n = this.states.length;
			boolean[] outside = new boolean[n];
			java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
			int layers = this.h + this.base;
			for (int y = 0; y < layers; y++) {
				for (int z = 0; z < this.d; z++) {
					for (int x = 0; x < this.w; x++) {
						boolean edge = x == 0 || z == 0 || x == this.w - 1 || z == this.d - 1 || y == layers - 1 || y == 0;
						int i = (y * this.d + z) * this.w + x;
						if (edge && open(this.states[i])) {
							outside[i] = true;
							queue.add(i);
						}
					}
				}
			}
			flood(queue, outside, -1);
			// what is left over and open is a sealed pocket; light it, one light per patch
			boolean[] lit = new boolean[n];
			for (int i = 0; i < n; i++) {
				BlockState s = this.states[i];
				if (outside[i] || lit[i] || s == null || !s.isAir()) {
					continue;
				}
				// only where something could stand: a solid block below
				int below = i - this.w * this.d;
				if (below < 0 || this.states[below] == null || open(this.states[below])) {
					continue;
				}
				this.states[i] = Blocks.LIGHT.defaultBlockState();
				lit[i] = true;
				java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
				q.add(i);
				this.reach(q, lit, outside);
			}
		}

		/** Marks every open cell reachable from the queue (all of them, or within LIGHT_REACH steps when lighting). */
		private void flood(java.util.ArrayDeque<Integer> queue, boolean[] seen, int limit) {
			while (!queue.isEmpty()) {
				int i = queue.poll();
				for (int nb : this.neighbours(i)) {
					if (nb >= 0 && !seen[nb] && open(this.states[nb])) {
						seen[nb] = true;
						queue.add(nb);
					}
				}
			}
		}

		private void reach(java.util.ArrayDeque<Integer> queue, boolean[] lit, boolean[] outside) {
			java.util.Map<Integer, Integer> dist = new java.util.HashMap<>();
			dist.put(queue.peek(), 0);
			while (!queue.isEmpty()) {
				int i = queue.poll();
				int dd = dist.get(i);
				if (dd >= LIGHT_REACH) {
					continue;
				}
				for (int nb : this.neighbours(i)) {
					if (nb >= 0 && !outside[nb] && !dist.containsKey(nb) && open(this.states[nb])) {
						dist.put(nb, dd + 1);
						lit[nb] = true;
						queue.add(nb);
					}
				}
			}
		}

		/** The six neighbours of a cell (-1 where the plan ends). */
		private int[] neighbours(int i) {
			int x = i % this.w;
			int z = (i / this.w) % this.d;
			int y = i / (this.w * this.d);
			int layers = this.h + this.base;
			int s = this.w * this.d;
			return new int[]{x > 0 ? i - 1 : -1, x < this.w - 1 ? i + 1 : -1, z > 0 ? i - this.w : -1, z < this.d - 1 ? i + this.w : -1, y > 0 ? i - s : -1,
				y < layers - 1 ? i + s : -1};
		}

		private boolean inside(int x, int y, int z) {
			return x >= 0 && y >= -this.base && z >= 0 && x < this.w && y < this.h && z < this.d;
		}

		public Builder set(int x, int y, int z, @Nullable BlockState s) {
			if (this.inside(x, y, z)) {
				this.states[((y + this.base) * this.d + z) * this.w + x] = s;
			}
			return this;
		}

		public Builder set(int x, int y, int z, Block b) {
			return this.set(x, y, z, b.defaultBlockState());
		}

		public @Nullable BlockState get(int x, int y, int z) {
			if (this.inside(x, y, z)) {
				return this.states[((y + this.base) * this.d + z) * this.w + x];
			}
			return null;
		}

		public Builder fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
					for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
						this.set(x, y, z, s);
					}
				}
			}
			return this;
		}

		public Builder fill(int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
			return this.fill(x0, y0, z0, x1, y1, z1, b.defaultBlockState());
		}

		/** Air everywhere not set yet (so the site gets cleared). */
		public Builder clearRest() {
			for (int i = 0; i < this.states.length; i++) {
				if (this.states[i] == null) {
					this.states[i] = Blocks.AIR.defaultBlockState();
				}
			}
			return this;
		}

		public Builder mark(String name, int x, int y, int z) {
			this.marks.computeIfAbsent(name, k -> new ArrayList<>()).add(new BlockPos(x, y, z));
			return this;
		}

		public Builder door(int x, int y, int z, Block door, Direction facing) {
			BlockState s = door.defaultBlockState().setValue(DoorBlock.FACING, facing);
			this.set(x, y, z, s.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
			this.set(x, y + 1, z, s.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
			return this.mark("door", x, y, z);
		}

		/** A bed with its foot at (x, y, z) and its head towards {@code head}. */
		public Builder bed(int x, int y, int z, Block bed, Direction head) {
			BlockState s = bed.defaultBlockState().setValue(BedBlock.FACING, head);
			this.set(x, y, z, s.setValue(BedBlock.PART, BedPart.FOOT));
			this.set(x + head.getStepX(), y, z + head.getStepZ(), s.setValue(BedBlock.PART, BedPart.HEAD));
			return this.mark("bed", x, y, z);
		}

		public static BlockState stairs(Block b, Direction facing, boolean top) {
			return b.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM);
		}

		public static BlockState log(Block b, Direction.Axis axis) {
			return b.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
		}

		public static BlockState facing(Block b, Direction d) {
			BlockState s = b.defaultBlockState();
			if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
				return s.setValue(BlockStateProperties.HORIZONTAL_FACING, d);
			}
			if (s.hasProperty(BlockStateProperties.FACING)) {
				return s.setValue(BlockStateProperties.FACING, d);
			}
			return s;
		}

		public static BlockState lantern() {
			return Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true);
		}

		/**
		 * A timber-framed box: floor at y=0 from (x0,z0) to (x1,z1), walls up to {@code top}, corner posts and a beam
		 * of {@code frame}, windows of panes, a door in the middle of the front wall.
		 */
		public Builder frameHouse(int x0, int z0, int x1, int z1, int top, Block floor, Block wall, Block frame, Block door, boolean windows) {
			this.fill(x0, 0, z0, x1, 0, z1, Blocks.STONE_BRICKS);
			this.fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, floor);
			for (int y = 1; y <= top; y++) {
				for (int x = x0; x <= x1; x++) {
					for (int z = z0; z <= z1; z++) {
						boolean edgeX = x == x0 || x == x1;
						boolean edgeZ = z == z0 || z == z1;
						if (!edgeX && !edgeZ) {
							this.set(x, y, z, Blocks.AIR);
							continue;
						}
						if (edgeX && edgeZ) {
							this.set(x, y, z, log(frame, Direction.Axis.Y));
						} else if (y == top) {
							this.set(x, y, z, log(frame, edgeZ ? Direction.Axis.X : Direction.Axis.Z));
						} else {
							this.set(x, y, z, wall);
						}
					}
				}
			}
			if (windows) {
				for (int x = x0 + 2; x <= x1 - 2; x += 2) {
					for (int z : new int[]{z0, z1}) {
						if (top >= 3 && Math.abs(x - (x0 + x1) / 2) > 0) {
							this.set(x, 2, z, Blocks.GLASS_PANE);
						}
					}
				}
				for (int z = z0 + 2; z <= z1 - 2; z += 2) {
					this.set(x0, 2, z, Blocks.GLASS_PANE);
					this.set(x1, 2, z, Blocks.GLASS_PANE);
				}
			}
			int dx = (x0 + x1) / 2;
			this.door(dx, 1, z1, door, Direction.NORTH);
			this.mark("entrance", dx, 1, z1 + 1);
			return this;
		}

		/** A gable roof over (x0..x1, z0..z1) - the ridge runs along x - starting at height y, with one block of overhang. */
		public Builder gableRoof(int x0, int z0, int x1, int z1, int y, Block stairs, Block gable) {
			int za = z0 - 1;
			int zb = z1 + 1;
			int level = y;
			while (za <= zb) {
				for (int x = x0 - 1; x <= x1 + 1; x++) {
					if (za == zb) {
						this.set(x, level, za, Blocks.SPRUCE_SLAB);
					} else {
						this.set(x, level, za, stairs(stairs, Direction.SOUTH, false));
						this.set(x, level, zb, stairs(stairs, Direction.NORTH, false));
					}
				}
				// gable ends below this slope row
				for (int z = za + 1; z < zb; z++) {
					this.set(x0, level, z, gable);
					this.set(x1, level, z, gable);
					for (int x = x0 + 1; x < x1; x++) {
						if (this.get(x, level, z) == null) {
							this.set(x, level, z, Blocks.AIR);
						}
					}
				}
				za++;
				zb--;
				level++;
			}
			return this;
		}

		/** A rail-less flat roof slab layer with a rim. */
		public Builder flatRoof(int x0, int z0, int x1, int z1, int y, Block slab, Block rim) {
			this.fill(x0, y, z0, x1, y, z1, slab);
			for (int x = x0; x <= x1; x++) {
				this.set(x, y + 1, z0, rim);
				this.set(x, y + 1, z1, rim);
			}
			for (int z = z0; z <= z1; z++) {
				this.set(x0, y + 1, z, rim);
				this.set(x1, y + 1, z, rim);
			}
			return this;
		}
	}
}
