package net.antwire.civitas.city;

import java.util.HashMap;
import java.util.Map;
import net.antwire.commerce.api.CommerceApi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Building materials: taken from the town's stockpile (what lumberjacks, miners and demolition brought in), otherwise
 * bought from outside with treasury money.
 */
public final class Materials {
	private static final Map<Item, Long> PRICE = new HashMap<>();

	static {
		p(Items.COBBLESTONE, 5);
		p(Items.STONE, 8);
		p(Items.STONE_BRICKS, 10);
		p(Items.CHISELED_STONE_BRICKS, 15);
		p(Items.STONE_BRICK_STAIRS, 15);
		p(Items.STONE_BRICK_WALL, 10);
		p(Items.SMOOTH_STONE, 12);
		p(Items.SMOOTH_STONE_SLAB, 6);
		p(Items.POLISHED_ANDESITE, 10);
		p(Items.POLISHED_ANDESITE_SLAB, 5);
		p(Items.POLISHED_BASALT, 15);
		p(Items.BRICKS, 40);
		p(Items.BRICK_STAIRS, 45);
		p(Items.DYED_TERRACOTTA.white(), 50);
		p(Items.CONCRETE.red(), 30);
		p(Items.QUARTZ_PILLAR, 150);
		p(Items.GLASS_PANE, 12);
		p(Items.IRON_BARS, 60);
		p(Items.OAK_FENCE, 20);
		p(Items.OAK_FENCE_GATE, 40);
		p(Items.OAK_SLAB, 10);
		p(Items.SPRUCE_SLAB, 10);
		p(Items.OAK_PRESSURE_PLATE, 20);
		p(Items.CHEST, 160);
		p(Items.BARREL, 140);
		p(Items.CRAFTING_TABLE, 80);
		p(Items.FURNACE, 60);
		p(Items.SMOKER, 300);
		p(Items.BLAST_FURNACE, 900);
		p(Items.ANVIL, 6000);
		p(Items.SMITHING_TABLE, 1400);
		p(Items.GRINDSTONE, 200);
		p(Items.STONECUTTER, 700);
		p(Items.CARTOGRAPHY_TABLE, 300);
		p(Items.LECTERN, 500);
		p(Items.COMPOSTER, 100);
		p(Items.CAULDRON, 1500);
		p(Items.BELL, 5000);
		p(Items.LANTERN, 250);
		p(Items.CAMPFIRE, 120);
		p(Items.HAY_BLOCK, 150);
		p(Items.CARVED_PUMPKIN, 60);
		p(Items.TARGET, 200);
		p(Items.IRON_BLOCK, 5800);
		p(Items.GOLD_BLOCK, 21000);
		p(Items.COAL_BLOCK, 900);
		p(Items.FLOWER_POT, 30);
		p(Items.POPPY, 5);
		p(Items.DANDELION, 5);
		p(Items.GRASS_BLOCK, 5);
		p(Items.DIRT, 2);
		p(Items.BANNER.red(), 300);
		p(Items.BANNER.blue(), 300);
		p(Items.BANNER.green(), 300);
		p(Items.BED.red(), 400);
		p(Items.BED.blue(), 400);
		p(Items.BED.green(), 400);
		p(Items.BED.yellow(), 400);
		p(Items.BED.gray(), 400);
		p(Items.SPRUCE_DOOR, 120);
		p(Items.OAK_DOOR, 120);
		p(Items.DARK_OAK_DOOR, 120);
	}

	private static void p(Item item, long cents) {
		PRICE.put(item, cents);
	}

	private Materials() {
	}

	/** Price of one item in cents. */
	public static long price(Item item) {
		Long fixed = PRICE.get(item);
		if (fixed != null) {
			return fixed;
		}
		long market = CommerceApi.available() ? CommerceApi.commodityPrice(item) : -1;
		if (market > 0) {
			return market;
		}
		String id = BuiltInRegistries.ITEM.getKey(item).getPath();
		if (id.endsWith("_planks")) {
			return 20;
		}
		if (id.endsWith("_log") || id.endsWith("_wood")) {
			return 80;
		}
		if (id.endsWith("_stairs")) {
			return 25;
		}
		if (id.endsWith("_slab")) {
			return 10;
		}
		if (id.startsWith("commerce:") || BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("commerce")) {
			return id.contains("terminal") ? 5000 : 500;
		}
		return 25;
	}

	/** Takes n items for the town: from the stockpile, by sawing logs into planks, or bought with treasury money. */
	public static boolean take(City city, Item item, int n) {
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		int have = city.stock(id);
		if (have >= n) {
			city.addStock(id, -n);
			return true;
		}
		if (id.endsWith("_planks")) {
			String log = id.replace("_planks", "_log");
			if (city.stock(log) > 0) {
				city.addStock(log, -1);
				city.addStock(id, 4);
				return take(city, item, n);
			}
			if (city.stock("minecraft:oak_log") > 0) {
				city.addStock("minecraft:oak_log", -1);
				city.addStock(id, 4);
				return take(city, item, n);
			}
		}
		if (id.equals("minecraft:cobblestone") && city.stock("minecraft:stone") > 0) {
			city.addStock("minecraft:stone", -1);
			city.addStock(id, 1);
			return take(city, item, n);
		}
		int buy = n - have;
		long cost = price(item) * buy;
		if (!CommerceApi.burn(city.account(), cost, "Building materials")) {
			return false;
		}
		city.spendingToday += cost;
		city.addStock(id, -have);
		return true;
	}
}
