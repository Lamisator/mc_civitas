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
	private final BlockState[] states;
	public final Map<String, List<BlockPos>> marks;

	private Blueprint(int w, int h, int d, BlockState[] states, Map<String, List<BlockPos>> marks) {
		this.w = w;
		this.h = h;
		this.d = d;
		this.states = states;
		this.marks = marks;
	}

	public @Nullable BlockState get(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= this.w || y >= this.h || z >= this.d) {
			return null;
		}
		return this.states[(y * this.d + z) * this.w + x];
	}

	public List<BlockPos> marks(String name) {
		return this.marks.getOrDefault(name, List.of());
	}

	public @Nullable BlockPos mark(String name) {
		List<BlockPos> l = this.marks(name);
		return l.isEmpty() ? null : l.getFirst();
	}

	public static Builder builder(int w, int h, int d) {
		return new Builder(w, h, d);
	}

	public static final class Builder {
		final int w;
		final int h;
		final int d;
		final BlockState[] states;
		final Map<String, List<BlockPos>> marks = new LinkedHashMap<>();

		Builder(int w, int h, int d) {
			this.w = w;
			this.h = h;
			this.d = d;
			this.states = new BlockState[w * h * d];
		}

		public Blueprint build() {
			return new Blueprint(this.w, this.h, this.d, this.states, this.marks);
		}

		public Builder set(int x, int y, int z, @Nullable BlockState s) {
			if (x >= 0 && y >= 0 && z >= 0 && x < this.w && y < this.h && z < this.d) {
				this.states[(y * this.d + z) * this.w + x] = s;
			}
			return this;
		}

		public Builder set(int x, int y, int z, Block b) {
			return this.set(x, y, z, b.defaultBlockState());
		}

		public @Nullable BlockState get(int x, int y, int z) {
			if (x >= 0 && y >= 0 && z >= 0 && x < this.w && y < this.h && z < this.d) {
				return this.states[(y * this.d + z) * this.w + x];
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
