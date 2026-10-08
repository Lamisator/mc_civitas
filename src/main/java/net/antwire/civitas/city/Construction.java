package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.antwire.commerce.api.CommerceApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Turns a blueprint into an ordered list of construction steps: clear the site (top down), lay a foundation where the
 * ground dips, raise the solid blocks layer by layer, then fit doors, beds, panes, lanterns and the like, and finally pave
 * the path to the town square. Builders work through the list one block at a time and pay for what they place.
 */
public final class Construction {
	public enum Kind {
		CLEAR, FOUNDATION, BLOCK, ROAD
	}

	public record Step(Kind kind, BlockPos pos, BlockState state) {
	}

	public enum Result {
		PLACED, SKIPPED, NO_MONEY
	}

	private static final Map<Building, List<Step>> CACHE = new WeakHashMap<>();

	private Construction() {
	}

	public static List<Step> steps(Building b) {
		return CACHE.computeIfAbsent(b, Construction::plan);
	}

	public static void forget(Building b) {
		CACHE.remove(b);
	}

	private static boolean attachment(BlockState s) {
		Block b = s.getBlock();
		return !s.canOcclude() || b instanceof DoorBlock || b instanceof BedBlock;
	}

	private static List<Step> plan(Building b) {
		Blueprint bp = b.blueprint();
		List<Step> out = new ArrayList<>();
		BlockState air = Blocks.AIR.defaultBlockState();
		// clear, from above the roof down
		for (int y = bp.h + 5; y >= 0; y--) {
			for (int z = 0; z < bp.d; z++) {
				for (int x = 0; x < bp.w; x++) {
					// everything the blueprint defines gets cleared first, and whatever grows above the roof
					if (y >= bp.h || bp.get(x, y, z) != null) {
						out.add(new Step(Kind.CLEAR, b.world(x, y, z), air));
					}
				}
			}
		}
		// foundation under the floor
		for (int y = -4; y <= -1; y++) {
			for (int z = 0; z < bp.d; z++) {
				for (int x = 0; x < bp.w; x++) {
					BlockState s = bp.get(x, 0, z);
					if (s != null && !s.isAir()) {
						out.add(new Step(Kind.FOUNDATION, b.world(x, y, z), Blocks.COBBLESTONE.defaultBlockState()));
					}
				}
			}
		}
		// solid blocks, then the fittings
		for (int pass = 0; pass < 2; pass++) {
			for (int y = 0; y < bp.h; y++) {
				for (int z = 0; z < bp.d; z++) {
					for (int x = 0; x < bp.w; x++) {
						BlockState s = bp.get(x, y, z);
						if (s == null || s.isAir() || attachment(s) != (pass == 1)) {
							continue;
						}
						out.add(new Step(Kind.BLOCK, b.world(x, y, z), s.rotate(b.rot())));
					}
				}
			}
		}
		// last, the way to the square: bank up the dips, dig through the banks, tread the path
		java.util.Set<BlockPos> tops = new java.util.HashSet<>(b.approach);
		for (BlockPos f : b.approachFill) {
			out.add(new Step(Kind.FOUNDATION, f, (tops.contains(f) ? Blocks.DIRT : Blocks.COBBLESTONE).defaultBlockState()));
		}
		for (BlockPos g : b.approach) {
			out.add(new Step(Kind.CLEAR, g.above(2), air));
			out.add(new Step(Kind.CLEAR, g.above(), air));
			out.add(new Step(Kind.ROAD, g, Blocks.DIRT_PATH.defaultBlockState()));
		}
		return out;
	}

	/** Earth plants grow in: dirt, grass, podzol, moss, mud... */
	public static boolean soil(BlockState s) {
		return s.is(BlockTags.DIRT) || s.is(BlockTags.GRASS_BLOCKS) || s.is(BlockTags.SUBSTRATE_OVERWORLD);
	}

	/** Natural ground cover and vegetation a builder may tear down. */
	public static boolean natural(BlockState s) {
		return s.isAir() || s.canBeReplaced() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || soil(s) || s.is(BlockTags.SAND)
			|| s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.CLAY) || s.is(BlockTags.FLOWERS)
			|| s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH) || s.is(BlockTags.SAPLINGS) || s.is(Blocks.BAMBOO) || s.is(Blocks.SUGAR_CANE)
			|| s.is(Blocks.CACTUS) || s.is(BlockTags.ICE) || s.is(Blocks.MOSS_BLOCK) || s.is(BlockTags.ORES)
			|| s.is(BlockTags.COPPER_ORES) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON) || s.is(Blocks.BROWN_MUSHROOM_BLOCK)
			|| s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.TERRACOTTA) || s.is(BlockTags.TERRACOTTA);
	}

	/** Does this step need doing at all (cheap: no materials, no changes)? */
	public static boolean needed(ServerLevel level, Step step) {
		BlockState now = level.getBlockState(step.pos());
		return switch (step.kind()) {
			case CLEAR -> !now.isAir() && now.getDestroySpeed(level, step.pos()) >= 0 && level.getBlockEntity(step.pos()) == null && natural(now);
			case FOUNDATION -> now.canBeReplaced() || !now.getFluidState().isEmpty();
			case BLOCK, ROAD -> !now.is(step.state().getBlock());
		};
	}

	/** Carries out one step. Steps that need nothing done return SKIPPED (and cost no time). */
	public static Result apply(ServerLevel level, City city, Building b, Step step) {
		BlockPos pos = step.pos();
		BlockState now = level.getBlockState(pos);
		switch (step.kind()) {
			case CLEAR -> {
				if (now.isAir()) {
					return Result.SKIPPED;
				}
				// only nature goes: never bedrock, a player's build or anything with contents
				if (now.getDestroySpeed(level, pos) < 0 || level.getBlockEntity(pos) != null || !natural(now)) {
					return Result.SKIPPED;
				}
				BlockState target = step.state();
				for (ItemStack drop : Block.getDrops(now, level, pos, level.getBlockEntity(pos))) {
					city.addStock(BuiltInRegistries.ITEM.getKey(drop.getItem()).toString(), drop.getCount());
				}
				Access.dig(level, pos, Block.UPDATE_ALL);
				level.playSound(null, pos, now.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.7F, 1.0F);
				return Result.PLACED;
			}
			case FOUNDATION -> {
				if (!now.canBeReplaced() && now.getFluidState().isEmpty()) {
					return Result.SKIPPED;
				}
				Item fill = step.state().getBlock().asItem();
				if (!Materials.take(city, fill == Items.AIR ? Items.COBBLESTONE : fill, 1)) {
					return Result.NO_MONEY;
				}
				level.setBlock(pos, step.state(), Block.UPDATE_ALL);
				return Result.PLACED;
			}
			case BLOCK, ROAD -> {
				BlockState target = step.state();
				if (now.is(target.getBlock())) {
					return Result.SKIPPED;
				}
				if (target.getBlock() instanceof DoorBlock && target.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
					return Result.SKIPPED;
				}
				if (target.getBlock() instanceof BedBlock && target.getValue(BedBlock.PART) == BedPart.HEAD) {
					return Result.SKIPPED;
				}
				// a player's chest or furnace that turned up on the site stays where it is
				if (step.kind() == Kind.BLOCK && !now.isAir() && !natural(now) && level.getBlockEntity(pos) != null) {
					return Result.SKIPPED;
				}
				if (step.kind() == Kind.ROAD && !(now.is(Blocks.GRASS_BLOCK) || now.is(Blocks.DIRT) || now.is(Blocks.COARSE_DIRT) || now.is(Blocks.PODZOL))) {
					return Result.SKIPPED;
				}
				// treading a path costs nothing
				Item item = step.kind() == Kind.ROAD ? Items.AIR : target.getBlock().asItem();
				if (item != Items.AIR && !Materials.take(city, item, 1)) {
					return Result.NO_MONEY;
				}
				placeFull(level, pos, target);
				level.playSound(null, pos, target.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
				return Result.PLACED;
			}
		}
		return Result.SKIPPED;
	}

	/** Carries out a step at once and for free (the complete command): the same rules as builders, no materials. */
	public static void applyFree(ServerLevel level, Step step) {
		BlockPos pos = step.pos();
		BlockState now = level.getBlockState(pos);
		switch (step.kind()) {
			case CLEAR -> {
				if (!now.isAir() && natural(now) && level.getBlockEntity(pos) == null && now.getDestroySpeed(level, pos) >= 0) {
					Access.dig(level, pos, Block.UPDATE_CLIENTS);
				}
			}
			case FOUNDATION -> {
				if (now.canBeReplaced() || !now.getFluidState().isEmpty()) {
					level.setBlock(pos, step.state(), Block.UPDATE_CLIENTS);
				}
			}
			case ROAD -> {
				if (now.is(Blocks.GRASS_BLOCK) || now.is(Blocks.DIRT) || now.is(Blocks.COARSE_DIRT) || now.is(Blocks.PODZOL)) {
					level.setBlock(pos, step.state(), Block.UPDATE_CLIENTS);
				}
			}
			case BLOCK -> placeFull(level, pos, step.state());
		}
	}

	/** Sets a block, with the second half of doors and beds. */
	public static void placeFull(ServerLevel level, BlockPos pos, BlockState s) {
		// the second halves come with the first
		if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
			|| s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) {
			return;
		}
		if (s.getBlock() instanceof DoorBlock) {
			level.setBlock(pos, s.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_CLIENTS);
			level.setBlock(pos.above(), s.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
			return;
		}
		if (s.getBlock() instanceof BedBlock) {
			BlockPos head = pos.relative(s.getValue(BedBlock.FACING));
			level.setBlock(pos, s.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_CLIENTS);
			level.setBlock(head, s.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
			return;
		}
		level.setBlock(pos, s, Block.UPDATE_ALL);
		BlockState shaped = Block.updateFromNeighbourShapes(s, level, pos);
		if (shaped != s) {
			level.setBlock(pos, shaped, Block.UPDATE_ALL);
		}
	}

	/** Paves a path of dirt from the building's entrance to the town square, following the ground. */
	public static List<Step> road(ServerLevel level, City city, Building b) {
		List<Step> out = new ArrayList<>();
		Building hall = city.townHall();
		if (hall == null || hall == b) {
			return out;
		}
		BlockPos from = b.entrance();
		BlockPos to = hall.mark("plaza");
		if (to == null) {
			to = hall.entrance();
		}
		int x = from.getX();
		int z = from.getZ();
		BlockState path = Blocks.DIRT_PATH.defaultBlockState();
		int guard = 0;
		while ((x != to.getX() || z != to.getZ()) && guard++ < 400) {
			if (x != to.getX()) {
				x += Integer.signum(to.getX() - x);
			} else {
				z += Integer.signum(to.getZ() - z);
			}
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
			BlockPos p = new BlockPos(x, y, z);
			boolean inside = false;
			for (Building o : city.buildings) {
				if (o.bounds().inflate(0.5).contains(net.minecraft.world.phys.Vec3.atCenterOf(p))) {
					inside = true;
					break;
				}
			}
			if (!inside) {
				out.add(new Step(Kind.ROAD, p, path));
			}
		}
		return out;
	}

	/** The cost of the materials of a blueprint, in cents (for the planning screen). */
	public static long estimate(BuildingType type) {
		Blueprint bp = Blueprints.of(type);
		long sum = 0;
		for (int y = 0; y < bp.h; y++) {
			for (int z = 0; z < bp.d; z++) {
				for (int x = 0; x < bp.w; x++) {
					BlockState s = bp.get(x, y, z);
					if (s != null && !s.isAir()) {
						Item item = s.getBlock().asItem();
						if (item != Items.AIR) {
							sum += Materials.price(item);
						}
					}
				}
			}
		}
		return sum + (long) type.fee * 100 + bp.w * bp.d * 4L * Materials.price(Items.COBBLESTONE) / 4;
	}

	static String treasury(City city) {
		return city.account();
	}

	static boolean pay(City city, long cents, String memo) {
		return CommerceApi.burn(city.account(), cents, memo);
	}
}
