package net.antwire.civitas.city;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.antwire.civitas.compat.RedButtonCompat;
import net.antwire.civitas.entity.CitizenEntity;
import net.antwire.civitas.entity.ai.Task;
import net.antwire.civitas.entity.ai.Work;
import net.antwire.civitas.registry.ModItems;
import net.antwire.commerce.api.CommerceApi;
import net.antwire.commerce.block.entity.ShopBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * What the workshops make and where their raw materials come from: wheat from the farms, ore from the mine, iron from the
 * smithy - carried over by the workers and paid for between the businesses - or, failing that, bought on the market.
 */
public final class Production {
	public record Recipe(Map<Item, Integer> in, Item out, int count, int ticks, boolean forShop, int shopTarget) {
	}

	private Production() {
	}

	private static Map<Item, Integer> in(Object... kv) {
		Map<Item, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((Item) kv[i], (Integer) kv[i + 1]);
		}
		return m;
	}

	public static List<Recipe> recipes(BuildingType type, City city) {
		return switch (type) {
			case BAKERY -> List.of(new Recipe(in(Items.WHEAT, 3), Items.BREAD, 1, 60, true, 32));
			case TAVERN -> List.of(new Recipe(in(Items.WHEAT, 2), ModItems.ALE, 1, 80, true, 24));
			case BUTCHER -> List.of(new Recipe(in(Items.BEEF, 1), Items.COOKED_BEEF, 1, 50, true, 24),
				new Recipe(in(Items.PORKCHOP, 1), Items.COOKED_PORKCHOP, 1, 50, true, 24));
			case BLACKSMITH -> List.of(new Recipe(in(Items.RAW_IRON, 1), Items.IRON_INGOT, 1, 60, false, 0),
				new Recipe(in(Items.IRON_INGOT, 3), Items.IRON_PICKAXE, 1, 160, true, 2),
				new Recipe(in(Items.IRON_INGOT, 2), Items.IRON_SWORD, 1, 140, true, 2),
				new Recipe(in(Items.IRON_INGOT, 2), Items.IRON_HOE, 1, 120, true, 2));
			case FACTORY -> {
				Item missile = RedButtonCompat.missile();
				yield List.of(new Recipe(in(Items.IRON_INGOT, 6, Items.GUNPOWDER, 4, Items.REDSTONE, 2), missile != null ? missile : Items.TNT,
					missile != null ? 1 : 3, city.policies.safetyRules ? 500 : 300, true, missile != null ? 4 : 16));
			}
			default -> List.of();
		};
	}

	/** Who supplies an input in town. */
	static @Nullable BuildingType supplier(Item item) {
		if (item == Items.WHEAT) {
			return BuildingType.FARM;
		}
		if (item == Items.RAW_IRON || item == Items.REDSTONE) {
			return BuildingType.MINE;
		}
		if (item == Items.IRON_INGOT) {
			return BuildingType.BLACKSMITH;
		}
		return null;
	}

	/** Price one business pays another per item (cents): the market price, else a fair guess. */
	public static long price(Item item) {
		long p = CommerceApi.commodityPrice(item);
		return p > 0 ? p : Materials.price(item);
	}

	/**
	 * Makes sure a business can pay {@code cost} for its supplies. With subsidies on (Laws), the treasury tops the till up
	 * when it runs short - as it does for wages - so a bakery whose takings went on wages can still buy wheat. Without
	 * subsidies, or with an empty treasury, the business has to make do with what it has.
	 */
	static boolean fund(City city, Building b, long cost) {
		String acct = b.account(city);
		long bal = CommerceApi.balance(acct);
		if (bal >= cost) {
			return true;
		}
		if (!city.policies.subsidies) {
			return false;
		}
		long gap = cost - bal;
		if (CommerceApi.transfer(city.account(), acct, gap, "Subsidy (supplies)")) {
			city.spendingToday += gap;
			return true;
		}
		return false;
	}

	static boolean wanted(ServerLevel level, Building b, Recipe r) {
		ShopBlockEntity shop = Storage.shop(level, b);
		if (r.forShop && shop != null) {
			int have = 0;
			for (int i = 0; i < shop.getContainerSize(); i++) {
				if (shop.getItem(i).is(r.out)) {
					have += shop.getItem(i).getCount();
				}
			}
			return have < r.shopTarget;
		}
		return Storage.freeSlots(level, b) > 2;
	}

	static boolean hasInputs(ServerLevel level, Building b, Recipe r) {
		for (Map.Entry<Item, Integer> e : r.in.entrySet()) {
			if (Storage.count(level, b, e.getKey()) < e.getValue()) {
				return false;
			}
		}
		return true;
	}

	/** The next job at a workshop: make something, fetch what's missing, or mind the shop. */
	public static Task task(CitizenEntity npc, City city, CitizenRecord rec, Building b) {
		ServerLevel level = (ServerLevel) npc.level();
		List<Recipe> list = recipes(b.type, city);
		Recipe missing = null;
		for (Recipe r : list) {
			if (!wanted(level, b, r)) {
				continue;
			}
			if (hasInputs(level, b, r)) {
				return new Craft(npc, city, rec, b, r);
			}
			if (missing == null) {
				missing = r;
			}
		}
		if (missing != null) {
			for (Map.Entry<Item, Integer> e : missing.in.entrySet()) {
				int need = e.getValue() * 6 - Storage.count(level, b, e.getKey());
				if (need <= 0) {
					continue;
				}
				BuildingType from = supplier(e.getKey());
				if (from != null) {
					for (Building s : city.of(from, true)) {
						if (s != b && Storage.count(level, s, e.getKey()) > 0) {
							return new Fetch(npc, city, rec, b, s, e.getKey(), need);
						}
					}
				}
				// nobody in town has it: order from outside (the quote is above the plain price: allow for the spread)
				fund(city, b, price(e.getKey()) * need * 3 / 2);
				long cost = CommerceApi.buyFromMarket(b.account(city), e.getKey(), need);
				if (cost > 0) {
					b.costToday += cost;
					Storage.add(level, b, new ItemStack(e.getKey(), need));
				}
			}
		}
		return new Work.Station(npc, city, rec, b);
	}

	/** Makes one batch at the workstation. */
	public static class Craft extends Task {
		private final Building b;
		private final Recipe recipe;
		private int working = -1;

		Craft(CitizenEntity npc, City city, CitizenRecord rec, Building b, Recipe recipe) {
			super(npc, city, rec);
			this.b = b;
			this.recipe = recipe;
		}

		@Override
		public String kind() {
			return "craft";
		}

		private BlockPos spot() {
			List<BlockPos> spots = this.b.marks("work");
			if (spots.isEmpty()) {
				return this.b.entrance();
			}
			int i = Math.max(0, this.b.workers.indexOf(this.rec.uuid));
			return spots.get(i % spots.size());
		}

		@Override
		protected void run() {
			BlockPos spot = this.spot();
			if (this.working < 0) {
				if (!this.walk(spot, 1.2)) {
					if (this.ticks > 600) {
						this.done = true;
					}
					return;
				}
				double pace = 0.5 + this.rec.happiness / 100.0;
				this.working = (int) (this.recipe.ticks / Math.max(0.35, pace));
				this.npc.hold(new ItemStack(this.tool()));
			}
			List<BlockPos> blocks = this.b.marks("workblock");
			BlockPos wb = blocks.isEmpty() ? spot : blocks.get(Math.max(0, this.b.workers.indexOf(this.rec.uuid)) % blocks.size());
			this.look(wb);
			this.swing(10);
			ServerLevel level = this.level();
			if (this.ticks % 20 == 0) {
				level.playSound(null, wb, this.sound(), SoundSource.BLOCKS, 0.5F, 0.9F + level.getRandom().nextFloat() * 0.2F);
				level.sendParticles(this.b.type == BuildingType.BLACKSMITH || this.b.type == BuildingType.FACTORY ? ParticleTypes.LAVA : ParticleTypes.SMOKE,
					wb.getX() + 0.5, wb.getY() + 1.0, wb.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0.01);
			}
			if (--this.working > 0) {
				return;
			}
			if (!hasInputs(level, this.b, this.recipe)) {
				this.done = true;
				return;
			}
			long inputValue = 0;
			for (Map.Entry<Item, Integer> e : this.recipe.in.entrySet()) {
				Storage.take(level, this.b, e.getKey(), e.getValue());
				inputValue += price(e.getKey()) * e.getValue();
			}
			ItemStack out = new ItemStack(this.recipe.out, this.recipe.count);
			long value = Math.max(inputValue, price(this.recipe.out) * this.recipe.count);
			this.b.producedToday += value;
			ShopBlockEntity shop = Storage.shop(level, this.b);
			ItemStack left = this.recipe.forShop && shop != null ? shop.restock(out) : out;
			if (!left.isEmpty()) {
				Townlife.store(level, this.city, this.b, left);
			}
			if (this.b.type == BuildingType.FACTORY && !this.city.policies.safetyRules && level.getRandom().nextFloat() < 0.04F) {
				Townlife.accident(level, this.city, this.b, this.npc, this.rec, wb);
			}
			this.npc.hold(ItemStack.EMPTY);
			this.done = true;
		}

		private Item tool() {
			return switch (this.b.type) {
				case BLACKSMITH, FACTORY -> Items.IRON_AXE;
				case BAKERY -> Items.WHEAT;
				case TAVERN -> ModItems.ALE;
				case BUTCHER -> Items.BEEF;
				default -> Items.AIR;
			};
		}

		private SoundEvent sound() {
			return switch (this.b.type) {
				case BLACKSMITH, FACTORY -> SoundEvents.ANVIL_USE;
				case TAVERN -> SoundEvents.BREWING_STAND_BREW;
				default -> SoundEvents.FURNACE_FIRE_CRACKLE;
			};
		}
	}

	/** Walks to another business, buys a batch of something from its store and carries it home. */
	public static class Fetch extends Task {
		private final Building home;
		private final Building from;
		private final Item item;
		private final int amount;
		private int carried = -1;

		Fetch(CitizenEntity npc, City city, CitizenRecord rec, Building home, Building from, Item item, int amount) {
			super(npc, city, rec);
			this.home = home;
			this.from = from;
			this.item = item;
			this.amount = Math.min(64, amount);
		}

		@Override
		public String kind() {
			return "fetch";
		}

		@Override
		protected void run() {
			ServerLevel level = this.level();
			if (this.carried < 0) {
				BlockPos at = this.from.mark("storage") != null ? this.from.mark("storage") : this.from.entrance();
				if (!this.walk(at, 2.6)) {
					if (this.ticks > 900) {
						this.done = true;
					}
					return;
				}
				long each = price(this.item);
				int there = Math.min(this.amount, Storage.count(level, this.from, this.item));
				if (each > 0 && there > 0) {
					fund(this.city, this.home, each * there);
				}
				long afford = each <= 0 ? this.amount : CommerceApi.balance(this.home.account(this.city)) / each;
				int want = (int) Math.min(this.amount, afford);
				int got = want <= 0 ? 0 : Storage.take(level, this.from, this.item, want);
				if (got > 0) {
					long pay = each * got;
					CommerceApi.transfer(this.home.account(this.city), this.from.account(this.city), pay,
						got + "× " + new ItemStack(this.item).getHoverName().getString());
					this.home.costToday += pay;
					this.from.revenueToday += pay;
					this.npc.hold(new ItemStack(this.item, Math.min(got, 64)));
				} else if (want <= 0) {
					this.npc.say("We can't pay for the " + new ItemStack(this.item).getHoverName().getString().toLowerCase() + "...");
				}
				this.carried = got;
				if (got == 0) {
					this.done = true;
				}
				return;
			}
			BlockPos back = this.home.mark("storage") != null ? this.home.mark("storage") : this.home.entrance();
			if (this.walk(back, 2.6) || this.ticks > 1800) {
				Storage.add(level, this.home, new ItemStack(this.item, this.carried));
				this.npc.hold(ItemStack.EMPTY);
				this.done = true;
			}
		}

		@Override
		public void stop() {
			super.stop();
			if (this.carried > 0 && !this.done) {
				// don't lose the goods if interrupted: they arrive anyway
				Storage.add(this.level(), this.home, new ItemStack(this.item, this.carried));
				this.npc.hold(ItemStack.EMPTY);
			}
		}
	}

	static boolean isNight(Level level) {
		return CityManager.timeOfDay(level) > 13000;
	}
}
