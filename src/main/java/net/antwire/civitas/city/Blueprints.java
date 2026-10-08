package net.antwire.civitas.city;

import java.util.EnumMap;
import java.util.Map;
import net.antwire.civitas.city.Blueprint.Builder;
import net.antwire.civitas.registry.ModBlocks;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The town's architecture. Every blueprint faces south (+z): the door is in the front wall, the entrance mark just
 * outside it. Timber framing, white plaster and steep roofs - a German small town.
 */
public final class Blueprints {
	private static final Map<BuildingType, Blueprint> CACHE = new EnumMap<>(BuildingType.class);

	private Blueprints() {
	}

	public static Blueprint of(BuildingType type) {
		return CACHE.computeIfAbsent(type, Blueprints::create);
	}

	private static Blueprint create(BuildingType type) {
		return switch (type) {
			case TOWN_HALL -> townHall();
			case HOUSE -> house();
			case FARM -> farm();
			case BAKERY -> bakery();
			case BUTCHER -> butcher();
			case BLACKSMITH -> blacksmith();
			case MINE -> mine();
			case LUMBER_MILL -> lumberMill();
			case BANK -> bank();
			case SHERIFF -> sheriff();
			case PRISON -> prison();
			case FACTORY -> factory();
			case TAVERN -> tavern();
			case BARRACKS -> barracks();
		};
	}

	private static BlockState shop() {
		return Builder.facing(net.antwire.commerce.registry.ModBlocks.SHOP_COUNTER, Direction.SOUTH);
	}

	private static void ceiling(Builder b, int x0, int z0, int x1, int z1, int y, Block block) {
		b.fill(x0, y, z0, x1, y, z1, block);
	}

	private static Blueprint house() {
		Builder b = Blueprint.builder(11, 10, 9);
		b.frameHouse(1, 1, 9, 7, 4, Blocks.SPRUCE_PLANKS, Blocks.DYED_TERRACOTTA.white(), Blocks.DARK_OAK_LOG, Blocks.SPRUCE_DOOR, true);
		ceiling(b, 2, 1, 8, 7, 5, Blocks.SPRUCE_PLANKS);
		b.gableRoof(1, 1, 9, 7, 5, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_PLANKS);
		ceiling(b, 2, 2, 8, 6, 5, Blocks.SPRUCE_PLANKS);
		b.bed(2, 1, 3, Blocks.BED.red(), Direction.NORTH);
		b.bed(3, 1, 3, Blocks.BED.blue(), Direction.NORTH);
		b.bed(7, 1, 3, Blocks.BED.green(), Direction.NORTH);
		b.bed(8, 1, 3, Blocks.BED.yellow(), Direction.NORTH);
		b.set(5, 1, 2, Blocks.CRAFTING_TABLE);
		b.set(5, 1, 3, Builder.facing(Blocks.BARREL, Direction.UP));
		b.set(2, 1, 6, Blocks.FLOWER_POT);
		b.set(5, 4, 4, Builder.lantern());
		b.set(3, 2, 8, Blocks.AIR);
		b.set(3, 1, 8, Blocks.POPPY);
		b.set(7, 1, 8, Blocks.DANDELION);
		b.mark("home", 5, 1, 5);
		return b.build();
	}

	private static Blueprint townHall() {
		Builder b = Blueprint.builder(15, 13, 13);
		b.frameHouse(1, 1, 13, 9, 5, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_DOOR, true);
		// tall arched windows
		for (int x : new int[]{3, 5, 9, 11}) {
			b.set(x, 3, 1, Blocks.GLASS_PANE);
			b.set(x, 3, 9, Blocks.GLASS_PANE);
		}
		b.gableRoof(1, 1, 13, 9, 6, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_PLANKS);
		// the clerk's desk with the town ledger, chests of the stockpile, benches for the council
		b.set(6, 1, 2, Builder.stairs(Blocks.DARK_OAK_STAIRS, Direction.NORTH, true));
		b.set(8, 1, 2, Builder.stairs(Blocks.DARK_OAK_STAIRS, Direction.NORTH, true));
		b.set(7, 1, 2, Builder.facing(ModBlocks.TOWN_LEDGER, Direction.SOUTH));
		b.mark("ledger", 7, 1, 2);
		b.mark("workblock", 7, 1, 2);
		b.mark("work", 7, 1, 3);
		b.set(2, 1, 2, Builder.facing(Blocks.CHEST, Direction.SOUTH));
		b.set(3, 1, 2, Builder.facing(Blocks.CHEST, Direction.SOUTH));
		b.mark("storage", 2, 1, 2);
		b.mark("storage", 3, 1, 2);
		b.set(12, 1, 2, Builder.facing(Blocks.BARREL, Direction.UP));
		b.set(11, 1, 2, Builder.facing(Blocks.BARREL, Direction.UP));
		for (int x : new int[]{3, 4, 10, 11}) {
			b.set(x, 1, 5, Builder.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
			b.set(x, 1, 7, Builder.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
		}
		b.set(6, 2, 2, Builder.facing(Blocks.LANTERN, Direction.UP).setValue(BlockStateProperties.HANGING, false));
		b.set(8, 2, 2, Builder.facing(Blocks.LANTERN, Direction.UP).setValue(BlockStateProperties.HANGING, false));
		b.set(4, 3, 10, Builder.facing(Blocks.WALL_BANNER.red(), Direction.SOUTH));
		b.set(10, 3, 10, Builder.facing(Blocks.WALL_BANNER.red(), Direction.SOUTH));
		// a bell by the door and the square in front
		b.set(11, 1, 11, Blocks.STONE_BRICK_WALL);
		b.set(11, 2, 11, Builder.facing(Blocks.BELL, Direction.SOUTH));
		b.fill(4, 0, 10, 10, 0, 12, Blocks.STONE_BRICKS);
		b.fill(5, 0, 11, 9, 0, 12, Blocks.CHISELED_STONE_BRICKS);
		b.mark("plaza", 7, 1, 12);
		return b.build();
	}

	private static Blueprint farm() {
		Builder b = Blueprint.builder(11, 3, 11);
		for (int x = 0; x <= 10; x++) {
			for (int z = 0; z <= 10; z++) {
				boolean edge = x == 0 || x == 10 || z == 0 || z == 10;
				b.set(x, 2, z, Blocks.AIR);
				if (edge) {
					b.set(x, 0, z, Blocks.GRASS_BLOCK);
					b.set(x, 1, z, Blocks.OAK_FENCE);
				} else if (z == 9) {
					b.set(x, 0, z, Blocks.DIRT_PATH);
					b.set(x, 1, z, Blocks.AIR);
				} else if (x == 5) {
					b.set(x, 0, z, Blocks.WATER);
					b.set(x, 1, z, Blocks.OAK_SLAB);
				} else {
					b.set(x, 0, z, Blocks.FARMLAND);
					b.set(x, 1, z, Blocks.AIR);
					b.mark("field", x, 1, z);
				}
			}
		}
		b.set(5, 1, 10, Builder.facing(Blocks.OAK_FENCE_GATE, Direction.SOUTH));
		b.mark("entrance", 5, 1, 11);
		b.set(1, 1, 9, Blocks.COMPOSTER);
		b.mark("workblock", 1, 1, 9);
		b.mark("work", 2, 1, 9);
		b.set(9, 1, 9, Builder.facing(Blocks.CHEST, Direction.WEST));
		b.mark("storage", 9, 1, 9);
		b.set(8, 1, 9, Blocks.HAY_BLOCK);
		b.set(3, 1, 9, Blocks.HAY_BLOCK);
		b.set(3, 2, 9, Builder.facing(Blocks.CARVED_PUMPKIN, Direction.SOUTH));
		for (int[] c : new int[][]{{0, 0}, {10, 0}, {0, 10}, {10, 10}}) {
			b.set(c[0], 2, c[1], Blocks.LANTERN);
		}
		return b.build();
	}

	private static Blueprint bakery() {
		Builder b = Blueprint.builder(9, 11, 9);
		b.frameHouse(1, 1, 7, 7, 4, Blocks.SPRUCE_PLANKS, Blocks.DYED_TERRACOTTA.white(), Blocks.DARK_OAK_LOG, Blocks.SPRUCE_DOOR, true);
		ceiling(b, 2, 1, 6, 7, 5, Blocks.SPRUCE_PLANKS);
		b.gableRoof(1, 1, 7, 7, 5, Blocks.BRICK_STAIRS, Blocks.BRICKS);
		ceiling(b, 2, 2, 6, 6, 5, Blocks.SPRUCE_PLANKS);
		b.set(2, 1, 2, Builder.facing(Blocks.SMOKER, Direction.SOUTH));
		b.set(3, 1, 2, Builder.facing(Blocks.FURNACE, Direction.SOUTH));
		b.mark("workblock", 2, 1, 2);
		b.mark("work", 2, 1, 3);
		b.set(6, 1, 2, Builder.facing(Blocks.CHEST, Direction.SOUTH));
		b.mark("storage", 6, 1, 2);
		b.set(6, 1, 3, Blocks.HAY_BLOCK);
		b.set(4, 1, 2, Builder.facing(Blocks.BARREL, Direction.UP));
		b.set(4, 1, 5, shop());
		b.mark("shop", 4, 1, 5);
		b.set(4, 4, 4, Builder.lantern());
		// chimney with a smoking campfire
		for (int y = 5; y <= 8; y++) {
			b.set(2, y, 2, Blocks.BRICKS);
		}
		b.set(2, 9, 2, Blocks.CAMPFIRE);
		return b.build();
	}

	private static Blueprint butcher() {
		Builder b = Blueprint.builder(9, 11, 15);
		// the pen behind the shop
		for (int x = 1; x <= 7; x++) {
			for (int z = 0; z <= 6; z++) {
				boolean ring = x == 1 || x == 7 || z == 0;
				b.set(x, 0, z, Blocks.GRASS_BLOCK);
				b.set(x, 1, z, ring ? Blocks.OAK_FENCE.defaultBlockState() : Blocks.AIR.defaultBlockState());
				b.set(x, 2, z, Blocks.AIR);
			}
		}
		b.set(2, 1, 1, Blocks.HAY_BLOCK);
		b.set(6, 1, 1, Blocks.WATER_CAULDRON.defaultBlockState().setValue(BlockStateProperties.LEVEL_CAULDRON, 3));
		b.mark("pen", 2, 1, 1);
		b.mark("pen", 6, 1, 6);
		b.frameHouse(1, 7, 7, 13, 4, Blocks.SPRUCE_PLANKS, Blocks.DYED_TERRACOTTA.white(), Blocks.DARK_OAK_LOG, Blocks.SPRUCE_DOOR, true);
		b.door(4, 1, 7, Blocks.SPRUCE_DOOR, Direction.SOUTH);
		b.marks.get("door").removeLast();
		b.mark("backdoor", 4, 1, 7);
		ceiling(b, 2, 7, 6, 13, 5, Blocks.SPRUCE_PLANKS);
		b.gableRoof(1, 7, 7, 13, 5, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_PLANKS);
		ceiling(b, 2, 8, 6, 12, 5, Blocks.SPRUCE_PLANKS);
		b.set(2, 1, 9, Builder.facing(Blocks.SMOKER, Direction.SOUTH));
		b.mark("workblock", 2, 1, 9);
		b.mark("work", 2, 1, 10);
		b.set(6, 1, 9, Builder.facing(Blocks.CHEST, Direction.SOUTH));
		b.mark("storage", 6, 1, 9);
		b.set(6, 1, 10, Blocks.SMOOTH_STONE_SLAB);
		b.set(4, 1, 11, shop());
		b.mark("shop", 4, 1, 11);
		b.set(4, 4, 10, Builder.lantern());
		return b.build();
	}

	private static Blueprint blacksmith() {
		Builder b = Blueprint.builder(9, 11, 9);
		b.frameHouse(1, 1, 7, 7, 4, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.SPRUCE_LOG, Blocks.SPRUCE_DOOR, true);
		// open front: a wide doorway
		b.set(3, 1, 7, Blocks.AIR);
		b.set(3, 2, 7, Blocks.AIR);
		b.set(5, 1, 7, Blocks.AIR);
		b.set(5, 2, 7, Blocks.AIR);
		b.gableRoof(1, 1, 7, 7, 5, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_PLANKS);
		b.set(2, 1, 2, Builder.facing(Blocks.BLAST_FURNACE, Direction.SOUTH));
		b.set(4, 1, 2, Builder.facing(Blocks.ANVIL, Direction.EAST));
		b.mark("workblock", 4, 1, 2);
		b.mark("work", 4, 1, 3);
		b.set(6, 1, 2, Blocks.SMITHING_TABLE);
		b.set(2, 1, 4, Builder.facing(Blocks.CHEST, Direction.EAST));
		b.mark("storage", 2, 1, 4);
		b.set(6, 1, 4, Blocks.WATER_CAULDRON.defaultBlockState().setValue(BlockStateProperties.LEVEL_CAULDRON, 3));
		b.set(4, 1, 5, shop());
		b.mark("shop", 4, 1, 5);
		b.set(6, 1, 6, Blocks.GRINDSTONE);
		for (int y = 5; y <= 8; y++) {
			b.set(2, y, 2, Blocks.COBBLESTONE);
		}
		b.set(2, 9, 2, Blocks.CAMPFIRE);
		b.set(4, 4, 4, Builder.lantern());
		ceiling(b, 3, 3, 5, 5, 5, Blocks.SPRUCE_PLANKS);
		return b.build();
	}

	private static Blueprint mine() {
		Builder b = Blueprint.builder(9, 11, 9);
		b.frameHouse(1, 1, 7, 7, 4, Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.SPRUCE_LOG, Blocks.SPRUCE_DOOR, true);
		b.gableRoof(1, 1, 7, 7, 5, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICKS);
		// shaft head: the staircase starts in the floor and descends to the north
		b.set(4, 0, 4, Blocks.AIR);
		b.set(3, 0, 4, Blocks.OAK_PLANKS);
		b.set(5, 0, 4, Blocks.OAK_PLANKS);
		b.mark("shaft", 4, 0, 4);
		b.set(2, 1, 6, Builder.facing(Blocks.CHEST, Direction.EAST));
		b.mark("storage", 2, 1, 6);
		b.set(6, 1, 6, Builder.facing(Blocks.BARREL, Direction.UP));
		b.set(6, 1, 2, Blocks.IRON_BLOCK);
		b.set(2, 1, 2, Blocks.COAL_BLOCK);
		b.mark("work", 4, 1, 5);
		b.set(3, 3, 3, Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, false));
		b.set(3, 2, 3, Blocks.OAK_FENCE);
		b.set(3, 1, 3, Blocks.OAK_FENCE);
		b.set(5, 3, 3, Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, false));
		b.set(5, 2, 3, Blocks.OAK_FENCE);
		b.set(5, 1, 3, Blocks.OAK_FENCE);
		return b.build();
	}

	private static Blueprint lumberMill() {
		Builder b = Blueprint.builder(9, 10, 9);
		b.fill(1, 0, 1, 7, 0, 7, Blocks.SPRUCE_PLANKS);
		b.fill(1, 1, 1, 7, 4, 7, Blocks.AIR);
		for (int[] c : new int[][]{{1, 1}, {7, 1}, {1, 7}, {7, 7}}) {
			b.fill(c[0], 1, c[1], c[0], 4, c[1], Builder.log(Blocks.OAK_LOG, Direction.Axis.Y));
		}
		for (int x = 1; x <= 7; x++) {
			b.set(x, 4, 1, Builder.log(Blocks.OAK_LOG, Direction.Axis.X));
			b.set(x, 4, 7, Builder.log(Blocks.OAK_LOG, Direction.Axis.X));
		}
		for (int z = 2; z <= 6; z++) {
			b.set(1, 4, z, Builder.log(Blocks.OAK_LOG, Direction.Axis.Z));
			b.set(7, 4, z, Builder.log(Blocks.OAK_LOG, Direction.Axis.Z));
		}
		b.fill(1, 1, 1, 7, 1, 1, Blocks.OAK_FENCE);
		b.set(1, 1, 1, Builder.log(Blocks.OAK_LOG, Direction.Axis.Y));
		b.set(7, 1, 1, Builder.log(Blocks.OAK_LOG, Direction.Axis.Y));
		b.gableRoof(1, 1, 7, 7, 5, Blocks.OAK_STAIRS, Blocks.OAK_PLANKS);
		b.set(4, 1, 3, Builder.facing(Blocks.STONECUTTER, Direction.SOUTH));
		b.mark("workblock", 4, 1, 3);
		b.mark("work", 4, 1, 4);
		b.set(2, 1, 2, Builder.facing(Blocks.CHEST, Direction.SOUTH));
		b.mark("storage", 2, 1, 2);
		b.fill(5, 1, 2, 6, 1, 3, Builder.log(Blocks.OAK_LOG, Direction.Axis.Z));
		b.set(5, 2, 2, Builder.log(Blocks.OAK_LOG, Direction.Axis.Z));
		b.set(2, 1, 5, Builder.log(Blocks.OAK_LOG, Direction.Axis.Y));
		b.mark("entrance", 4, 1, 8);
		return b.build();
	}

	private static Blueprint bank() {
		Builder b = Blueprint.builder(9, 11, 9);
		b.frameHouse(1, 1, 7, 7, 4, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.QUARTZ_PILLAR, Blocks.DARK_OAK_DOOR, true);
		for (int x = 1; x <= 7; x++) {
			for (int z = 1; z <= 7; z++) {
				if (b.get(x, 2, z) != null && b.get(x, 2, z).is(Blocks.GLASS_PANE)) {
					b.set(x, 2, z, Blocks.IRON_BARS);
				}
			}
		}
		ceiling(b, 2, 1, 6, 7, 5, Blocks.SMOOTH_STONE);
		b.gableRoof(1, 1, 7, 7, 5, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICKS);
		ceiling(b, 2, 2, 6, 6, 5, Blocks.SMOOTH_STONE);
		b.set(2, 1, 4, Builder.facing(net.antwire.commerce.registry.ModBlocks.BANK_TERMINAL, Direction.EAST));
		b.set(6, 1, 4, Builder.facing(net.antwire.commerce.registry.ModBlocks.BANK_TERMINAL, Direction.WEST));
		b.fill(3, 1, 3, 5, 1, 3, Blocks.POLISHED_ANDESITE_SLAB);
		b.set(4, 1, 3, Blocks.LECTERN);
		b.mark("workblock", 4, 1, 3);
		b.mark("work", 4, 1, 2);
		b.set(2, 1, 2, Builder.facing(Blocks.CHEST, Direction.SOUTH));
		b.mark("storage", 2, 1, 2);
		b.set(6, 1, 2, Blocks.GOLD_BLOCK);
		b.set(5, 1, 2, Blocks.GOLD_BLOCK);
		b.set(4, 4, 5, Builder.lantern());
		return b.build();
	}

	private static Blueprint sheriff() {
		Builder b = Blueprint.builder(9, 11, 9);
		b.frameHouse(1, 1, 7, 7, 4, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_DOOR, true);
		ceiling(b, 2, 1, 6, 7, 5, Blocks.DARK_OAK_PLANKS);
		b.gableRoof(1, 1, 7, 7, 5, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_PLANKS);
		ceiling(b, 2, 2, 6, 6, 5, Blocks.DARK_OAK_PLANKS);
		b.set(4, 1, 2, Blocks.CARTOGRAPHY_TABLE);
		b.mark("workblock", 4, 1, 2);
		b.mark("work", 4, 1, 3);
		b.set(2, 1, 2, Builder.facing(Blocks.CHEST, Direction.SOUTH));
		b.mark("storage", 2, 1, 2);
		b.set(6, 1, 2, Builder.facing(Blocks.BARREL, Direction.UP));
		b.set(2, 3, 4, Builder.facing(Blocks.WALL_BANNER.blue(), Direction.EAST));
		b.set(4, 4, 4, Builder.lantern());
		b.set(6, 2, 5, Blocks.TARGET);
		b.mark("entrance", 4, 1, 8);
		return b.build();
	}

	private static Blueprint prison() {
		Builder b = Blueprint.builder(11, 11, 9);
		b.frameHouse(1, 1, 9, 7, 4, Blocks.STONE, Blocks.STONE_BRICKS, Blocks.POLISHED_BASALT, Blocks.DARK_OAK_DOOR, false);
		ceiling(b, 2, 1, 8, 7, 5, Blocks.STONE_BRICKS);
		b.gableRoof(1, 1, 9, 7, 5, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICKS);
		ceiling(b, 2, 2, 8, 6, 5, Blocks.STONE_BRICKS);
		// two cells along the back, barred to the corridor
		b.fill(5, 1, 2, 5, 3, 4, Blocks.STONE_BRICKS);
		b.fill(2, 1, 4, 4, 3, 4, Blocks.IRON_BARS);
		b.fill(6, 1, 4, 8, 3, 4, Blocks.IRON_BARS);
		b.bed(2, 1, 3, Blocks.BED.gray(), Direction.NORTH);
		b.bed(8, 1, 3, Blocks.BED.gray(), Direction.NORTH);
		b.set(4, 1, 2, Blocks.CAULDRON);
		b.set(6, 1, 2, Blocks.CAULDRON);
		b.mark("cell", 3, 1, 3);
		b.mark("cell", 7, 1, 3);
		b.mark("cellbed", 2, 1, 3);
		b.mark("cellbed", 8, 1, 3);
		// the cell beds are nobody's home
		b.marks.remove("bed");
		b.set(5, 4, 5, Builder.lantern());
		b.set(2, 2, 7, Blocks.IRON_BARS);
		b.set(8, 2, 7, Blocks.IRON_BARS);
		b.set(2, 1, 6, Builder.facing(Blocks.CHEST, Direction.EAST));
		b.mark("storage", 2, 1, 6);
		b.mark("work", 7, 1, 6);
		return b.build();
	}

	private static Blueprint factory() {
		Builder b = Blueprint.builder(15, 13, 11);
		b.frameHouse(1, 1, 13, 9, 5, Blocks.SMOOTH_STONE, Blocks.BRICKS, Blocks.POLISHED_BASALT, Blocks.DARK_OAK_DOOR, true);
		b.gableRoof(1, 1, 13, 9, 6, Blocks.STONE_BRICK_STAIRS, Blocks.BRICKS);
		b.set(3, 1, 2, Builder.facing(Blocks.ANVIL, Direction.EAST));
		b.set(5, 1, 2, Blocks.SMITHING_TABLE);
		b.set(7, 1, 2, Builder.facing(Blocks.BLAST_FURNACE, Direction.SOUTH));
		b.set(9, 1, 2, Blocks.CRAFTING_TABLE);
		b.mark("workblock", 3, 1, 2);
		b.mark("workblock", 7, 1, 2);
		b.mark("workblock", 9, 1, 2);
		b.mark("work", 3, 1, 3);
		b.mark("work", 7, 1, 3);
		b.mark("work", 9, 1, 3);
		b.set(11, 1, 2, Builder.facing(Blocks.CHEST, Direction.SOUTH));
		b.set(12, 1, 2, Builder.facing(Blocks.CHEST, Direction.SOUTH));
		b.mark("storage", 11, 1, 2);
		b.mark("storage", 12, 1, 2);
		b.fill(2, 1, 7, 3, 2, 8, Blocks.CONCRETE.red());
		b.set(2, 3, 8, Blocks.CONCRETE.red());
		b.set(10, 1, 7, shop());
		b.mark("shop", 10, 1, 7);
		b.fill(11, 1, 7, 12, 1, 7, Blocks.SMOOTH_STONE_SLAB);
		for (int x : new int[]{4, 10}) {
			b.set(x, 4, 5, Builder.lantern());
		}
		ceiling(b, 2, 4, 12, 6, 6, Blocks.SMOOTH_STONE);
		for (int y = 6; y <= 9; y++) {
			b.set(2, y, 2, Blocks.BRICKS);
			b.set(12, y, 2, Blocks.BRICKS);
		}
		b.set(2, 10, 2, Blocks.CAMPFIRE);
		b.set(12, 10, 2, Blocks.CAMPFIRE);
		return b.build();
	}

	private static Blueprint tavern() {
		Builder b = Blueprint.builder(11, 10, 9);
		b.frameHouse(1, 1, 9, 7, 4, Blocks.SPRUCE_PLANKS, Blocks.DYED_TERRACOTTA.white(), Blocks.DARK_OAK_LOG, Blocks.OAK_DOOR, true);
		ceiling(b, 2, 1, 8, 7, 5, Blocks.SPRUCE_PLANKS);
		b.gableRoof(1, 1, 9, 7, 5, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_PLANKS);
		ceiling(b, 2, 2, 8, 6, 5, Blocks.SPRUCE_PLANKS);
		b.set(2, 1, 3, Builder.log(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.X));
		b.set(3, 1, 3, shop());
		b.mark("shop", 3, 1, 3);
		b.set(4, 1, 3, Builder.log(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.X));
		b.set(2, 1, 2, Builder.facing(Blocks.BARREL, Direction.SOUTH));
		b.set(4, 1, 2, Builder.facing(Blocks.BARREL, Direction.SOUTH));
		b.set(2, 2, 2, Builder.facing(Blocks.BARREL, Direction.SOUTH));
		b.mark("work", 3, 1, 2);
		b.mark("workblock", 2, 1, 2);
		b.set(8, 1, 2, Builder.facing(Blocks.CHEST, Direction.SOUTH));
		b.mark("storage", 8, 1, 2);
		// tables and chairs
		for (int[] t : new int[][]{{6, 3}, {6, 5}, {3, 5}}) {
			b.set(t[0], 1, t[1], Blocks.OAK_FENCE);
			b.set(t[0], 2, t[1], Blocks.OAK_PRESSURE_PLATE);
			b.set(t[0] - 1, 1, t[1], Builder.stairs(Blocks.OAK_STAIRS, Direction.WEST, false));
			b.set(t[0] + 1, 1, t[1], Builder.stairs(Blocks.OAK_STAIRS, Direction.EAST, false));
			b.mark("seat", t[0] - 1, 1, t[1]);
			b.mark("seat", t[0] + 1, 1, t[1]);
		}
		b.set(5, 4, 4, Builder.lantern());
		b.set(3, 4, 4, Builder.lantern());
		b.set(3, 3, 8, Builder.facing(Blocks.WALL_BANNER.green(), Direction.SOUTH));
		return b.build();
	}

	/** An Arsenal block by id, or the fallback when Arsenal isn't installed. */
	private static BlockState arsenal(String id, Direction facing, BlockState fallback) {
		Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("arsenal", id));
		return block == null || block == Blocks.AIR ? fallback : Builder.facing(block, facing);
	}

	/**
	 * Stone barracks: four bunks with footlockers, the armoury along the east wall (Arsenal's weapon racks and ammunition
	 * crates when it is installed), the duty officer's map table, and a parade ground in front with targets and the
	 * alarm bell - all at the sides, the way to the door stays clear.
	 */
	private static Blueprint barracks() {
		Builder b = Blueprint.builder(15, 12, 16);
		b.frameHouse(1, 1, 13, 9, 4, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.SPRUCE_LOG, Blocks.DARK_OAK_DOOR, true);
		ceiling(b, 2, 1, 12, 9, 5, Blocks.SPRUCE_PLANKS);
		b.gableRoof(1, 1, 13, 9, 5, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILES);
		ceiling(b, 2, 2, 12, 8, 5, Blocks.SPRUCE_PLANKS);
		// the dormitory: bunks along the west wall, a footlocker between each
		for (int z : new int[]{2, 4, 6, 8}) {
			b.bed(3, 1, z, Blocks.BED.green(), Direction.WEST);
		}
		for (int z : new int[]{3, 5, 7}) {
			b.set(2, 1, z, Builder.facing(Blocks.BARREL, Direction.UP));
		}
		// the armoury along the east wall
		BlockState barrel = Builder.facing(Blocks.BARREL, Direction.UP);
		b.set(12, 2, 3, arsenal("weapon_rack", Direction.WEST, Blocks.AIR.defaultBlockState()));
		b.set(12, 2, 5, arsenal("weapon_rack", Direction.WEST, Blocks.AIR.defaultBlockState()));
		b.mark("rack", 12, 2, 3);
		b.mark("rack", 12, 2, 5);
		b.set(12, 1, 7, arsenal("gun_rack", Direction.WEST, barrel));
		b.mark("rack", 12, 1, 7);
		b.set(12, 1, 2, arsenal("ammo_crate", Direction.WEST, barrel));
		b.set(11, 1, 2, arsenal("ammo_crate", Direction.SOUTH, barrel));
		b.set(12, 1, 4, Builder.facing(Blocks.CHEST, Direction.WEST));
		b.mark("storage", 12, 1, 4);
		b.set(12, 1, 3, Blocks.SMITHING_TABLE);
		// the duty officer's map table under the colours
		b.set(7, 1, 3, Blocks.CARTOGRAPHY_TABLE);
		b.set(6, 1, 3, Builder.facing(Blocks.LECTERN, Direction.SOUTH));
		b.mark("workblock", 7, 1, 3);
		b.mark("work", 7, 1, 4);
		b.set(7, 3, 2, Builder.facing(Blocks.WALL_BANNER.green(), Direction.SOUTH));
		b.set(5, 4, 5, Builder.lantern());
		b.set(9, 4, 5, Builder.lantern());
		// the parade ground
		b.fill(1, 0, 10, 13, 0, 14, Blocks.COARSE_DIRT);
		b.fill(1, 1, 10, 13, 3, 14, Blocks.AIR);
		for (int x = 1; x <= 13; x++) {
			b.set(x, 0, 14, Blocks.STONE_BRICKS);
		}
		for (int x : new int[]{1, 13}) {
			b.set(x, 1, 11, Blocks.HAY_BLOCK);
			b.set(x, 2, 11, Blocks.TARGET);
			b.set(x, 1, 12, Blocks.HAY_BLOCK);
		}
		b.set(1, 1, 14, Blocks.STONE_BRICK_WALL);
		b.set(1, 2, 14, Builder.facing(Blocks.BELL, Direction.EAST));
		b.mark("bell", 1, 2, 14);
		for (int y = 1; y <= 4; y++) {
			b.set(13, y, 14, Blocks.SPRUCE_FENCE);
		}
		b.set(13, 5, 14, Blocks.BANNER.green());
		// sentries by the door, the parade ground for drill
		b.mark("post", 5, 1, 10);
		b.mark("post", 9, 1, 10);
		b.mark("post", 7, 1, 12);
		b.mark("parade", 4, 1, 12);
		b.mark("parade", 10, 1, 12);
		return b.build();
	}
}
