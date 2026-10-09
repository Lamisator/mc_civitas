package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.List;
import net.antwire.civitas.CivitasConfig;
import net.antwire.commerce.api.CommerceApi;
import net.antwire.commerce.block.entity.ShopBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

/**
 * The town's food, checked link by link - fields, farmers, wheat, the baker, the butcher's herd, what is on the
 * counters and whether people can pay for it - with advice for the governor on what to do about each gap.
 */
public final class Supply {
	public static final int OK = 0;
	public static final int WARN = 1;
	public static final int BAD = 2;

	public record Line(int level, String text) {
	}

	private Supply() {
	}

	/** Bread equivalents a citizen eats in a day (a full stomach lasts about a day; bread fills 35 of 100). */
	static final double MEALS_PER_DAY = 2.5;

	public static List<Line> report(ServerLevel level, City city) {
		List<Line> out = new ArrayList<>();
		Policies p = city.policies;
		List<CitizenRecord> people = city.living();
		int pop = people.size();
		if (!level.getServer().overworld().getGameRules().get(GameRules.ADVANCE_TIME)) {
			out.add(new Line(WARN, CivitasConfig.get().ownClockWhenFrozen
				? "The world's clock stands still; the town keeps its own days (day " + city.day + ")."
				: "The world's clock stands still, so no day ever ends: no wages, no meals counted. Turn on ownClockWhenFrozen."));
		}
		// how hungry are they?
		int hungry = 0;
		int starving = 0;
		long cheapest = Math.round(Math.min(p.breadPrice, p.meatPrice) * 100);
		int broke = 0;
		for (CitizenRecord r : people) {
			if (r.food < 20) {
				hungry++;
			}
			if (r.hungryDays > 0) {
				starving++;
			}
			if (CommerceApi.balance(r.account(city)) < cheapest) {
				broke++;
			}
		}
		if (starving > 0) {
			out.add(new Line(BAD, starving + " of " + pop + " citizens went hungry yesterday, " + hungry + " are hungry now."));
		} else if (hungry > 0) {
			out.add(new Line(WARN, hungry + " of " + pop + " citizens are hungry now; nobody went without yesterday."));
		} else {
			out.add(new Line(OK, "All " + pop + " citizens are fed."));
		}
		// what's on the counters against what they need
		int bread = 0;
		int meat = 0;
		for (Building b : city.buildings) {
			if (!b.complete || b.type != BuildingType.BAKERY && b.type != BuildingType.BUTCHER) {
				continue;
			}
			ShopBlockEntity shop = Storage.shop(level, b);
			if (shop == null) {
				continue;
			}
			for (int i = 0; i < ShopBlockEntity.OFFERS; i++) {
				ItemStack t = shop.template(i);
				if (t.is(Items.BREAD)) {
					bread += shop.stock(i);
				} else if (t.is(Items.COOKED_BEEF) || t.is(Items.COOKED_PORKCHOP)) {
					meat += shop.stock(i);
				}
			}
		}
		double need = pop * MEALS_PER_DAY;
		double have = bread + meat * 55.0 / 35.0;
		out.add(new Line(have >= need ? OK : have >= need / 3 ? WARN : BAD, String.format(java.util.Locale.ROOT,
			"On the counters: %d bread, %d roast meat - %.0f%% of a day's meals (%d people eat about %.0f loaves' worth a day).", bread, meat,
			need <= 0 ? 100 : Math.min(999, have * 100 / need), pop, need)));
		// can they pay?
		if (broke > 0) {
			String fix = p.rations ? "free rations are on; keep the treasury and the bakery stocked"
				: "raise wages, lower food prices, pay a basic income or turn on free rations (Laws)";
			out.add(new Line(broke * 4 >= pop ? BAD : WARN, broke + " citizens can't afford even the cheapest food (" + CommerceApi.format(cheapest) + "): " + fix
				+ "."));
		}
		if (p.wageLevel < 60) {
			out.add(new Line(WARN, "Wages are at " + Math.round(p.wageLevel) + "% - workers can barely pay for food."));
		}
		// the bread chain: fields, farmers, wheat, bakers
		List<Building> farms = city.of(BuildingType.FARM, true);
		List<Building> bakeries = city.of(BuildingType.BAKERY, true);
		int farmers = 0;
		int farmSlots = 0;
		int fields = 0;
		int wheatAtFarms = 0;
		for (Building f : farms) {
			farmers += f.workers.size();
			farmSlots += f.type.workers;
			fields += f.marks("field").size();
			wheatAtFarms += Storage.count(level, f, Items.WHEAT);
		}
		if (farms.isEmpty()) {
			out.add(new Line(BAD, "There is no farm: without wheat there is no bread. Plan a farm (Buildings)."));
		} else {
			// a field gives about one wheat a day once grown; a loaf takes three
			int loavesPerDay = fields / 3;
			out.add(new Line(farmers == 0 ? BAD : loavesPerDay < need * 0.6 ? WARN : OK, farms.size() + " farm(s), " + fields + " fields, " + farmers + "/"
				+ farmSlots + " farmers - wheat for roughly " + loavesPerDay + " loaves a day" + (wheatAtFarms > 0 ? ", " + wheatAtFarms + " wheat waiting at the farms" : "")
				+ (loavesPerDay < need * 0.6 ? ". Build or upgrade farms." : ".")));
			if (farmers < farmSlots) {
				out.add(new Line(WARN, (farmSlots - farmers) + " farm job(s) open: assign the unemployed (Citizens) or allow free choice of work."));
			}
		}
		if (bakeries.isEmpty()) {
			out.add(new Line(BAD, "There is no bakery: wheat isn't baked into bread. Plan a bakery."));
		}
		for (Building b : bakeries) {
			int wheat = Storage.count(level, b, Items.WHEAT);
			if (b.workers.isEmpty()) {
				out.add(new Line(BAD, "The bakery has no baker. Assign someone (Citizens) - bread only comes from a baker."));
			} else if (wheat < 3) {
				long till = CommerceApi.balance(b.account(city));
				String pay = city.policies.subsidies ? "; with subsidies on, the treasury pays for it when the till is short"
					: till <= 0 ? ", but its till is empty and subsidies are off - turn subsidies on (Laws) or raise the bread price" : "";
				out.add(new Line(WARN, "The bakery is out of wheat" + (wheatAtFarms > 0 ? " - the baker fetches it from the farm (" + CommerceApi.format(till)
					+ " in its till" + pay + ")." : " and the farms have none to sell; the baker orders it from the market" + pay + ".")));
			}
		}
		// the meat chain
		for (Building b : city.of(BuildingType.BUTCHER, true)) {
			int raw = Storage.count(level, b, Items.BEEF) + Storage.count(level, b, Items.PORKCHOP);
			int herd = 0;
			AABB pen = pen(b);
			if (pen != null) {
				herd = level.getEntitiesOfClass(Animal.class, pen.inflate(1)).size();
			}
			if (b.workers.isEmpty()) {
				out.add(new Line(BAD, "The butcher's has no butcher. Assign someone (Citizens)."));
			} else {
				long till = CommerceApi.balance(b.account(city));
				String state = herd + " animals in the pen, " + raw + " raw meat in store";
				out.add(new Line(herd < 2 && raw == 0 ? WARN : OK, "Butcher's: " + state + (herd < 2 && till < 3000 ? " - it can't afford new livestock ("
					+ CommerceApi.format(till) + " in its till; wage subsidies or higher meat prices help)." : ".")));
			}
		}
		// too many mouths?
		if (have < need && p.immigration && starving > 0) {
			out.add(new Line(WARN, "Settlers keep coming while people go hungry: stop immigration (Laws) until food catches up."));
		}
		if (starving > 0 && !p.rations) {
			out.add(new Line(WARN, "Free rations (Laws) feed the hungry from the treasury when they can't buy food."));
		}
		return out;
	}

	private static AABB pen(Building b) {
		List<BlockPos> m = b.marks("pen");
		if (m.size() < 2) {
			return null;
		}
		return new AABB(Vec(m.get(0)), Vec(m.get(1))).inflate(0.5);
	}

	private static net.minecraft.world.phys.Vec3 Vec(BlockPos p) {
		return net.minecraft.world.phys.Vec3.atCenterOf(p);
	}

	/** Tells the governor once a day when people go hungry. */
	public static void daily(ServerLevel level, City city) {
		int starving = 0;
		for (CitizenRecord r : city.living()) {
			if (r.hungryDays > 0) {
				starving++;
			}
		}
		if (starving == 0) {
			return;
		}
		net.minecraft.server.level.ServerPlayer gov = level.getServer().getPlayerList().getPlayer(city.governor);
		if (gov != null) {
			gov.sendSystemMessage(net.minecraft.network.chat.Component.literal(starving + " citizens of " + city.name
				+ " went hungry yesterday - the Supply page of the town ledger shows what's missing.").withStyle(net.minecraft.ChatFormatting.GOLD));
		}
	}

	static Item[] foods() {
		return new Item[]{Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP};
	}
}
