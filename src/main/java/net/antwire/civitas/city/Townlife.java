package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.antwire.civitas.CivitasConfig;
import net.antwire.civitas.compat.RedButtonCompat;
import net.antwire.civitas.entity.CitizenEntity;
import net.antwire.civitas.registry.ModItems;
import net.antwire.commerce.api.CommerceApi;
import net.antwire.commerce.block.entity.ShopBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/** The town's running business between the days: jobs, homes, planning, settlers, the law, deliveries. */
public final class Townlife {
	private Townlife() {
	}

	public static void everySecond(ServerLevel level, City city, CityManager m) {
		if (!city.accessChecked && level.getGameTime() % 1200 == 600) {
			// once after loading: towns built before the access rules get their ways now
			city.accessChecked = true;
			for (Building b : List.copyOf(city.buildings)) {
				if (b.complete) {
					checkAccess(level, city, b);
				}
			}
		}
		tidy(city);
		housing(city);
		net.antwire.civitas.entity.ai.Military.tick(level, city);
		if (city.policies.autoAssign) {
			jobs(city);
		}
		long t = level.getGameTime();
		if (t % 1200 == 0) {
			if (city.policies.autoBuild) {
				autoPlan(level, city, m);
			}
			if (city.policies.immigration) {
				immigration(level, city, m);
			}
			bodies(level, city, m);
		}
		// prisoners who served their time walk free
		for (CitizenRecord r : city.citizens.values()) {
			if (r.status == CitizenRecord.Status.JAILED && city.day >= r.releaseDay) {
				r.status = CitizenRecord.Status.FREE;
				city.log(r.name + " was released from prison");
				CitizenEntity e = m.entity(r.uuid);
				Building prison = city.firstComplete(BuildingType.PRISON);
				if (e != null && prison != null) {
					BlockPos out = prison.entrance();
					e.teleportTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
				}
			}
		}
	}

	/** Drops the dead and departed from workplaces and homes. */
	static void tidy(City city) {
		for (Building b : city.buildings) {
			b.workers.removeIf(u -> {
				CitizenRecord r = city.citizens.get(u);
				return r == null || !r.present() || r.workplace != b.id || r.job == Job.BUILDER && b.type != BuildingType.TOWN_HALL;
			});
			b.residents.removeIf(u -> {
				CitizenRecord r = city.citizens.get(u);
				return r == null || !r.present() || r.home != b.id;
			});
		}
	}

	public static void release(City city, CitizenRecord r) {
		for (Building b : city.buildings) {
			b.workers.remove(r.uuid);
			b.residents.remove(r.uuid);
		}
		r.workplace = -1;
		r.home = -1;
	}

	static void housing(City city) {
		for (CitizenRecord r : city.citizens.values()) {
			if (!r.present() || r.home >= 0 && city.building(r.home) != null) {
				continue;
			}
			r.home = -1;
			for (Building b : city.buildings) {
				if (b.complete && b.bedCount() > 0 && b.residents.size() < b.bedCount()) {
					b.residents.add(r.uuid);
					r.home = b.id;
					break;
				}
			}
		}
	}

	public static int builderTarget(City city) {
		int pending = 0;
		for (Building b : city.buildings) {
			if (!b.complete) {
				pending++;
			}
		}
		if (pending == 0) {
			return 1;
		}
		return Math.min(6, 2 + city.population() / 6);
	}

	/** Fills open jobs with the unemployed (and spare builders when nothing is under construction). */
	static void jobs(City city) {
		List<CitizenRecord> builders = new ArrayList<>();
		List<CitizenRecord> idle = new ArrayList<>();
		for (CitizenRecord r : city.citizens.values()) {
			if (!r.free()) {
				continue;
			}
			if (r.job == Job.BUILDER) {
				builders.add(r);
			} else if (r.job == Job.UNEMPLOYED) {
				idle.add(r);
			}
		}
		int target = builderTarget(city);
		Building hall = city.townHall();
		while (builders.size() < target && !idle.isEmpty() && hall != null) {
			CitizenRecord r = idle.removeFirst();
			r.job = Job.BUILDER;
			r.workplace = hall.id;
			builders.add(r);
		}
		while (builders.size() > target) {
			CitizenRecord r = builders.removeLast();
			r.job = Job.UNEMPLOYED;
			r.workplace = -1;
			idle.add(r);
		}
		for (Building b : city.buildings) {
			if (!b.complete || b.type.job == null || b.type == BuildingType.TOWN_HALL && b.type.job == Job.CLERK && city.population() < 6) {
				continue;
			}
			while (b.workers.size() < b.workerSlots() && !idle.isEmpty()) {
				CitizenRecord r = idle.removeFirst();
				r.job = b.type.job;
				r.workplace = b.id;
				r.nightWatch = b.type == BuildingType.BARRACKS && b.workers.size() % 2 == 1;
				b.workers.add(r.uuid);
				city.log(r.name + " now works as a " + r.job.title.toLowerCase());
			}
		}
	}

	/** Moves a citizen to a job (by the governor); the workplace must have room. */
	public static boolean assign(City city, CitizenRecord r, @Nullable Building b) {
		for (Building o : city.buildings) {
			o.workers.remove(r.uuid);
		}
		if (b == null) {
			r.job = Job.UNEMPLOYED;
			r.workplace = -1;
			return true;
		}
		if (b.type == BuildingType.TOWN_HALL && r.job != Job.CLERK) {
			r.job = Job.BUILDER;
			r.workplace = b.id;
			return true;
		}
		if (b.type.job == null || b.workers.size() >= b.workerSlots()) {
			return false;
		}
		r.job = b.type.job;
		r.workplace = b.id;
		r.nightWatch = b.type == BuildingType.BARRACKS && b.workers.size() % 2 == 1;
		b.workers.add(r.uuid);
		return true;
	}

	/** The town plans what it lacks: food first, then homes, then trades. */
	static void autoPlan(ServerLevel level, City city, CityManager m) {
		if (city.nextConstruction() != null) {
			return;
		}
		Building hall = city.townHall();
		if (hall == null || !hall.complete) {
			return;
		}
		int pop = city.population();
		BuildingType want = null;
		BuildingType favoured = Council.priority(city);
		if (favoured != null && !city.of(BuildingType.FARM, false).isEmpty() && !city.of(BuildingType.BAKERY, false).isEmpty()) {
			want = favoured;
		} else if (city.of(BuildingType.FARM, false).isEmpty()) {
			want = BuildingType.FARM;
		} else if (city.of(BuildingType.BAKERY, false).isEmpty()) {
			want = BuildingType.BAKERY;
		} else if (city.beds() < pop + 2) {
			want = BuildingType.HOUSE;
		} else if (city.of(BuildingType.LUMBER_MILL, false).isEmpty()) {
			want = BuildingType.LUMBER_MILL;
		} else if (city.of(BuildingType.MINE, false).isEmpty()) {
			want = BuildingType.MINE;
		} else if (city.of(BuildingType.BUTCHER, false).isEmpty()) {
			want = BuildingType.BUTCHER;
		} else if (pop >= 8 && city.of(BuildingType.TAVERN, false).isEmpty()) {
			want = BuildingType.TAVERN;
		} else if (pop >= 9 && city.of(BuildingType.BLACKSMITH, false).isEmpty()) {
			want = BuildingType.BLACKSMITH;
		} else if (pop >= 10 && city.of(BuildingType.SHERIFF, false).isEmpty()) {
			want = BuildingType.SHERIFF;
		} else if ((pop >= 12 || pop >= 6 && city.day - city.lastAttackDay <= 3) && city.of(BuildingType.BARRACKS, false).isEmpty()) {
			want = BuildingType.BARRACKS;
		} else if (pop >= 14 && city.of(BuildingType.PRISON, false).isEmpty()) {
			want = BuildingType.PRISON;
		} else if (pop >= 16 && city.of(BuildingType.BANK, false).isEmpty()) {
			want = BuildingType.BANK;
		} else if (pop > 12 && city.of(BuildingType.FARM, false).size() < 1 + pop / 12) {
			want = BuildingType.FARM;
		}
		if (want == null) {
			autoUpgrade(level, city);
			return;
		}
		if (CommerceApi.balance(city.account()) < Construction.estimate(want, city.tier()) / 2) {
			return;
		}
		Building b = m.plan(level, city, want);
		if (b != null) {
			CommerceApi.burn(city.account(), (long) want.fee * 100, "Planning fee: " + want.title);
		}
	}

	/** One upgrade at a time: the town hall when the town is big enough, then homes if beds are short, then the rest. */
	static void autoUpgrade(ServerLevel level, City city) {
		for (Building b : city.buildings) {
			if (b.upgrading() || !b.complete && b.type != BuildingType.TOWN_HALL) {
				return;
			}
		}
		List<Building> order = new ArrayList<>();
		Building hall = city.townHall();
		if (hall != null) {
			order.add(hall);
		}
		if (city.beds() < city.population() + 4) {
			order.addAll(city.of(BuildingType.HOUSE, true));
		}
		order.addAll(city.buildings);
		for (Building b : order) {
			if (whyNotUpgrade(level, city, b) == null && upgrade(level, city, b)) {
				return;
			}
		}
	}

	static void immigration(ServerLevel level, City city, CityManager m) {
		int pop = city.population();
		if (pop >= CivitasConfig.get().maxCitizens || city.beds() <= pop || city.averageHappiness() < 45) {
			return;
		}
		double chance = (city.averageHappiness() - 40) / 100.0;
		if (m.random.nextDouble() < chance) {
			m.immigrate(level, city, null, false);
		}
	}

	/** Brings back citizens whose body has vanished (killed by commands, lost in unloaded land). */
	static void bodies(ServerLevel level, City city, CityManager m) {
		for (CitizenRecord r : city.citizens.values()) {
			if (!r.present() || m.entity(r.uuid) != null) {
				continue;
			}
			BlockPos at = r.lastPos.equals(BlockPos.ZERO) ? city.center : r.lastPos;
			if (!level.isLoaded(at)) {
				continue;
			}
			var e = net.antwire.civitas.registry.ModEntities.CITIZEN.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
			if (e != null && level.getEntity(r.uuid) == null) {
				BlockPos spot = net.antwire.civitas.entity.ai.Walker.standable(level, at, 4);
				if (spot == null) {
					spot = at;
				}
				e.setUUID(r.uuid);
				e.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0, 0);
				e.bind(city, r);
				level.addFreshEntity(e);
			}
		}
	}

	// ------------------------------------------------------------------ buildings

	/** A building is finished: shops get their owner and wares, farms their seed, and a path is laid to the square. */
	public static void complete(ServerLevel level, City city, Building b) {
		b.complete = true;
		b.builtDay = city.day;
		b.progress = b.steps = Construction.steps(b).size();
		Construction.forget(b);
		CommerceApi.openAccount(b.account(city), city.name + " " + b.type.title);
		if (b.type.job != null && !b.type.job.publicSector) {
			long capital = Math.min(15000, Math.max(0, CommerceApi.balance(city.account()) / 10));
			if (CommerceApi.transfer(city.account(), b.account(city), capital, "Working capital")) {
				city.spendingToday += capital;
			}
		}
		city.log("The " + b.type.title.toLowerCase() + " was completed");
		setupShop(level, city, b);
		if (b.type == BuildingType.FARM) {
			Storage.add(level, b, new ItemStack(Items.WHEAT_SEEDS, 48));
			Storage.add(level, b, new ItemStack(Items.CARROT, 8));
			Storage.add(level, b, new ItemStack(Items.POTATO, 8));
		}
		fillRacks(level, b);
		Works.placeSign(level, city, b);
		if (b.type == BuildingType.TOWN_HALL) {
			BlockPos plaza = b.mark("plaza");
			if (plaza != null) {
				city.center = plaza;
			}
		}
		// the path to the square (buildings planned before there were proper ways get a straight one)
		for (Construction.Step s : b.approach.isEmpty() ? Construction.road(level, city, b) : List.<Construction.Step>of()) {
			BlockState now = level.getBlockState(s.pos());
			if (now.is(Blocks.GRASS_BLOCK) || now.is(Blocks.DIRT) || now.is(Blocks.COARSE_DIRT) || now.is(Blocks.PODZOL)) {
				level.setBlock(s.pos(), Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
				BlockPos above = s.pos().above();
				if (level.getBlockState(above).canBeReplaced() && !level.getBlockState(above).isAir()) {
					level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
		level.playSound(null, b.entrance(), SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1.0F, 1.2F);
		checkAccess(level, city, b);
	}

	/** Spare weapons on the barracks' racks. */
	static void fillRacks(ServerLevel level, Building b) {
		if (b.type != BuildingType.BARRACKS) {
			return;
		}
		for (BlockPos rack : b.marks("rack")) {
			if (level.getBlockEntity(rack) instanceof net.minecraft.world.Container c) {
				for (int i = 0; i < c.getContainerSize(); i++) {
					if (c.getItem(i).isEmpty()) {
						c.setItem(i, net.antwire.civitas.compat.Arms.stack(net.antwire.civitas.compat.Arms.weapon(i == 1 ? 3 : 0)));
					}
				}
			}
		}
	}

	/** Runs the access check on a finished building and tells the town when people can't get in. */
	public static Access.Audit checkAccess(ServerLevel level, City city, Building b) {
		if (b.upgrading()) {
			return new Access.Audit(0, 0, false, null);
		}
		Access.Audit a = Access.audit(level, city, b);
		String now = a.blocked() == null ? "" : a.blocked();
		if (!now.equals(b.blocked)) {
			if (!now.isEmpty()) {
				city.log("The " + b.type.title.toLowerCase() + " can't be used: " + now);
			} else if (!b.blocked.isEmpty()) {
				city.log("The " + b.type.title.toLowerCase() + " can be entered again");
			}
			b.blocked = now;
		}
		if (a.rerouted()) {
			Construction.forget(b);
		}
		return a;
	}

	// ------------------------------------------------------------------ tiers

	/** Why the building can't go up a tier now, or null if it can. */
	public static @Nullable String whyNotUpgrade(ServerLevel level, City city, Building b) {
		int to = b.tier + 1;
		if (!b.complete || b.upgrading()) {
			return "it is still being built";
		}
		if (to > Blueprints.TIERS) {
			return "it is as grand as it gets";
		}
		CivitasConfig cfg = CivitasConfig.get();
		if (b.type == BuildingType.TOWN_HALL) {
			int need = to == 2 ? cfg.tier2Population : cfg.tier3Population;
			if (city.population() < need) {
				return "the town needs " + need + " citizens (it has " + city.population() + ")";
			}
		} else if (city.tier() < to) {
			return "the town hall must be raised to tier " + to + " first";
		}
		BlockPos origin = upgradeOrigin(b, to);
		String room = roomToGrow(level, city, b, to, origin);
		if (room != null) {
			return room;
		}
		long cost = Construction.upgradeCost(b, to, origin);
		if (CommerceApi.balance(city.account()) < Construction.fee(b.type, to) + cost / 3) {
			return "the treasury can't pay for it (about " + CommerceApi.format(cost) + ")";
		}
		return null;
	}

	/** Where the plot of the next tier starts: the same plot, or for a building from before tiers one laid out around its door. */
	public static BlockPos upgradeOrigin(Building b, int to) {
		if (b.layout != 0) {
			return b.origin;
		}
		BlockPos door = b.entrance();
		BlockPos rel = Blueprints.of(b.type, to).mark("entrance");
		return door.subtract(rel.rotate(b.rot())).atY(b.origin.getY());
	}

	/** Null if the bigger building fits: nothing of anyone else's in the way, no other plot or way to a door. */
	static @Nullable String roomToGrow(ServerLevel level, City city, Building b, int to, BlockPos origin) {
		java.util.Map<BlockPos, BlockState> now = Construction.cells(b.blueprint(), b.origin, b.rot());
		java.util.Map<BlockPos, BlockState> next = Construction.cells(Blueprints.of(b.type, to), origin, b.rot());
		// the ways to other doors may cross the plot, but nothing may be built across them
		for (Building o : city.buildings) {
			if (o == b) {
				continue;
			}
			for (BlockPos w : o.approach) {
				BlockState feet = next.get(w.above());
				BlockState head = next.get(w.above(2));
				BlockState under = next.get(w);
				if (feet != null && !Access.walkThrough(feet) || head != null && !Access.headRoom(head) || under != null && !Access.floor(under)) {
					return "no room to grow: the way to the " + o.type.title.toLowerCase() + " runs there";
				}
			}
		}
		for (BlockPos p : next.keySet()) {
			for (Building o : city.buildings) {
				if (o == b) {
					continue;
				}
				net.minecraft.world.phys.AABB box = o.bounds();
				if (p.getX() >= box.minX && p.getX() < box.maxX && p.getZ() >= box.minZ && p.getZ() < box.maxZ) {
					return "no room to grow: the " + o.type.title.toLowerCase() + " is in the way";
				}
			}
			if (now.containsKey(p)) {
				continue;
			}
			BlockState s = level.getBlockState(p);
			// underground the builders dig out what's there (only a chest, a furnace or bedrock stops them)
			if (p.getY() < b.origin.getY() && (s.getDestroySpeed(level, p) >= 0 && level.getBlockEntity(p) == null)) {
				continue;
			}
			if (!s.isAir() && (!Construction.natural(s) || level.getBlockEntity(p) != null)) {
				return "no room to grow: " + s.getBlock().getName().getString().toLowerCase() + " at " + p.toShortString() + " is in the way";
			}
		}
		return null;
	}

	/** Starts the upgrade: the builders take it from here. */
	public static boolean upgrade(ServerLevel level, City city, Building b) {
		if (whyNotUpgrade(level, city, b) != null) {
			return false;
		}
		int to = b.tier + 1;
		BlockPos origin = upgradeOrigin(b, to);
		CommerceApi.burn(city.account(), Construction.fee(b.type, to), "Planning fee: " + b.type.title + " tier " + to);
		b.targetTier = to;
		b.upgradeOrigin = origin.equals(b.origin) ? null : origin;
		Construction.forget(b);
		b.progress = 0;
		b.steps = Construction.steps(b).size();
		city.log("The " + b.type.title.toLowerCase() + " is to be raised to tier " + to);
		return true;
	}

	/** The builders are done: the building is the new tier, with more room for people and a fresh way to the square. */
	public static void upgraded(ServerLevel level, City city, Building b) {
		int to = b.targetTier;
		b.origin = b.targetOrigin();
		b.tier = to;
		b.layout = 1;
		b.targetTier = 0;
		b.upgradeOrigin = null;
		Construction.forget(b);
		b.progress = b.steps = 0;
		Access.Route r = city.townHall() == null ? null : Access.route(level, city, b);
		if (r != null) {
			b.approach = new ArrayList<>(r.path());
			b.approachFill = new ArrayList<>(r.fill());
			Access.build(level, b);
		}
		setupShop(level, city, b);
		fillRacks(level, b);
		if (b.type == BuildingType.TOWN_HALL && b.mark("plaza") != null) {
			city.center = b.mark("plaza");
		}
		Works.placeSign(level, city, b);
		city.log("The " + b.type.title.toLowerCase() + " was raised to tier " + to);
		level.playSound(null, b.entrance(), SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1.0F, 0.9F);
		checkAccess(level, city, b);
	}

	private record Offer(Item item, int count, long price) {
	}

	static List<Offer> offers(City city, BuildingType type) {
		Policies p = city.policies;
		List<Offer> o = new ArrayList<>();
		switch (type) {
			case BAKERY -> o.add(new Offer(Items.BREAD, 1, Math.round(p.breadPrice * 100)));
			case BUTCHER -> {
				o.add(new Offer(Items.COOKED_BEEF, 1, Math.round(p.meatPrice * 100)));
				o.add(new Offer(Items.COOKED_PORKCHOP, 1, Math.round(p.meatPrice * 100)));
			}
			case TAVERN -> o.add(new Offer(ModItems.ALE, 1, Math.round(p.alePrice * 100)));
			case BLACKSMITH -> {
				o.add(new Offer(Items.IRON_PICKAXE, 1, 4500));
				o.add(new Offer(Items.IRON_SWORD, 1, 3500));
				o.add(new Offer(Items.IRON_HOE, 1, 2500));
			}
			case FACTORY -> {
				Item missile = RedButtonCompat.missile();
				if (missile != null) {
					o.add(new Offer(missile, 1, Math.round(CivitasConfig.get().missileExportPrice * 130)));
				} else {
					o.add(new Offer(Items.TNT, 1, 1500));
				}
			}
			default -> {
			}
		}
		return o;
	}

	public static void setupShop(ServerLevel level, City city, Building b) {
		ShopBlockEntity shop = Storage.shop(level, b);
		if (shop == null) {
			return;
		}
		shop.setOwnerAccount(b.account(city), city.name + " " + b.type.title, city.governor);
		shop.setShopName(city.name + " " + b.type.title);
		List<Offer> list = offers(city, b.type);
		for (int i = 0; i < list.size(); i++) {
			shop.setOffer(i, new ItemStack(list.get(i).item(), list.get(i).count()), list.get(i).price());
		}
	}

	/** Shop prices follow the town's price laws. */
	public static void updatePrices(ServerLevel level, City city) {
		for (Building b : city.buildings) {
			if (!b.complete) {
				continue;
			}
			ShopBlockEntity shop = Storage.shop(level, b);
			if (shop == null) {
				continue;
			}
			List<Offer> list = offers(city, b.type);
			for (int i = 0; i < list.size(); i++) {
				if (shop.template(i).is(list.get(i).item())) {
					shop.setPrice(i, list.get(i).price());
				}
			}
		}
	}

	/**
	 * Puts produce away. Stone and timber go to the town's stockpile (the treasury pays the producer); the rest into the
	 * building's chests, and what doesn't fit is sold on the market. Returns the value produced, in cents.
	 */
	public static long store(ServerLevel level, City city, Building b, ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}
		Item item = stack.getItem();
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		long value = Production.price(item) * stack.getCount();
		boolean material = item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE || id.endsWith("_log") || item == Items.DIRT
			|| item == Items.STONE || item == Items.GRAVEL;
		if (material) {
			city.addStock(item == Items.COBBLED_DEEPSLATE ? "minecraft:cobblestone" : id, stack.getCount());
			if (CommerceApi.transfer(city.account(), b.account(city), value, "Delivered " + stack.getCount() + "× " + stack.getHoverName().getString())) {
				b.revenueToday += value;
				city.spendingToday += value;
			}
			return value;
		}
		ItemStack left = Storage.add(level, b, stack);
		if (!left.isEmpty()) {
			long paid = CommerceApi.sellToMarket(b.account(city), left.getItem(), left.getCount());
			b.revenueToday += paid;
		}
		return value;
	}

	/** Free bread for the hungry: from the bakery's counter if it has some, else imported - paid by the treasury. */
	public static ItemStack ration(ServerLevel level, City city) {
		Building bakery = city.firstComplete(BuildingType.BAKERY);
		if (bakery != null) {
			ShopBlockEntity shop = Storage.shop(level, bakery);
			if (shop != null && shop.stock(0) > 0) {
				List<ItemStack> got = shop.sellToAccount(city.account(), 0, 1);
				if (!got.isEmpty()) {
					city.spendingToday += shop.price(0);
					bakery.revenueToday += shop.price(0);
					return got.getFirst();
				}
			}
		}
		if (CivitasConfig.get().importFood) {
			long cost = CommerceApi.buyFromMarket(city.account(), Items.BREAD, 1);
			if (cost > 0) {
				city.spendingToday += cost;
				return new ItemStack(Items.BREAD);
			}
		}
		return ItemStack.EMPTY;
	}

	// ------------------------------------------------------------------ law and order

	public static @Nullable BlockPos cellOf(City city, CitizenRecord r) {
		Building prison = city.firstComplete(BuildingType.PRISON);
		if (prison == null) {
			return null;
		}
		List<BlockPos> cells = prison.marks("cell");
		if (cells.isEmpty()) {
			return null;
		}
		int i = 0;
		for (CitizenRecord o : city.citizens.values()) {
			if (o == r) {
				break;
			}
			if (o.status == CitizenRecord.Status.JAILED) {
				i++;
			}
		}
		return cells.get(i % cells.size());
	}

	/** Arrest: to prison if the town has one, otherwise a fine. */
	public static void arrest(ServerLevel level, City city, CitizenRecord r, @Nullable CitizenEntity e, String by) {
		Building prison = city.firstComplete(BuildingType.PRISON);
		if (prison != null) {
			r.status = CitizenRecord.Status.JAILED;
			int days = city.policies.sentenceDays * Math.max(1, Math.min(3, r.crimes));
			r.releaseDay = city.day + days;
			BlockPos cell = cellOf(city, r);
			if (e != null && cell != null) {
				e.teleportTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
				e.say("I'm innocent!");
			}
			city.log(r.name + " was arrested by " + by + " and jailed for " + days + " days");
		} else {
			long fine = Math.min(CommerceApi.balance(r.account(city)), 5000);
			CommerceApi.transfer(r.account(city), city.account(), fine, "Fine");
			r.status = CitizenRecord.Status.FREE;
			city.log(r.name + " was caught by " + by + " and fined " + CommerceApi.format(fine));
		}
		if (e != null) {
			e.getNavigation().stop();
			e.brain().walker.reset();
		}
	}

	/** An explosion in the munitions works: nobody's block is harmed, but people are. */
	public static void accident(ServerLevel level, City city, Building b, CitizenEntity npc, CitizenRecord r, BlockPos at) {
		r.cause = "a factory accident";
		level.explode(null, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 2.5F, Level.ExplosionInteraction.NONE);
		npc.hurtServer(level, level.damageSources().explosion(null, null), 40);
		city.log("Explosion at the ordnance factory! " + r.name + " was caught in the blast");
		city.grievance += 8;
	}

	/** Rioters smash windows and, now and then, start a fire. */
	public static void riotDamage(ServerLevel level, City city, CitizenEntity npc) {
		BlockPos c = npc.blockPosition();
		for (int i = 0; i < 40; i++) {
			BlockPos p = c.offset(level.getRandom().nextInt(17) - 8, level.getRandom().nextInt(5) - 1, level.getRandom().nextInt(17) - 8);
			BlockState s = level.getBlockState(p);
			if (s.is(Blocks.GLASS_PANE) || s.is(Blocks.GLASS)) {
				level.destroyBlock(p, false);
				npc.say("Smash it!");
				break;
			}
		}
		if (level.getRandom().nextInt(6) == 0) {
			BlockPos p = c.offset(level.getRandom().nextInt(9) - 4, 0, level.getRandom().nextInt(9) - 4);
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ());
			BlockPos f = new BlockPos(p.getX(), y, p.getZ());
			if (level.getBlockState(f).isAir()) {
				level.setBlock(f, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
				npc.say("Burn it down!");
			}
		}
	}

	public static Map<String, Integer> stockSnapshot(City city) {
		return Map.copyOf(city.stockpile);
	}

	public static UUID governor(City city) {
		return city.governor;
	}
}
