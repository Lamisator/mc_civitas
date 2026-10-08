package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.antwire.commerce.api.CommerceApi;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Self-government: the town council sets the laws itself each morning, after a strategy, and plans what that strategy
 * favours. It moves step by step (a few points of tax a day, not all at once) and reacts to how the town is doing -
 * hunger, unrest, an emptying treasury - in its own way. The governor can hand over or take back the reins at any time.
 */
public final class Council {
	public enum Strategy {
		NONE("You", "The governor makes every law."),
		GROWTH("Growth", "Low taxes, good wages, cheap bread, open doors: as many people and buildings as the treasury can carry."),
		EQUALITY("Equality", "High taxes paid back as a basic income, free rations, short days, low prices: nobody falls behind."),
		PROSPERITY("Prosperity", "A busy, well-paid town that earns money: trade, the bank and the factory first, upgrades, dividends."),
		ORDER("Order", "Curfew, long sentences, sheriffs and soldiers first; martial law when the streets stir."),
		EXTORTION("Extortion", "Squeeze them as hard as they will bear: low pay, long days, high rents and prices, forced labour - and a cut of the treasury for the governor every day."),
		BALANCED("Balanced", "Steers for a content town with a steady treasury, adjusting taxes and wages to how people feel.");

		public final String title;
		public final String description;

		Strategy(String title, String description) {
			this.title = title;
			this.description = description;
		}

		public static Strategy byId(@Nullable String id) {
			for (Strategy s : values()) {
				if (s.name().equalsIgnoreCase(id) || s.title.equalsIgnoreCase(id)) {
					return s;
				}
			}
			return NONE;
		}
	}

	private Council() {
	}

	public static Strategy strategy(City city) {
		return Strategy.byId(city.strategy);
	}

	/** One law the council wants, and how far it may move it in a day. */
	private record Aim(String key, String label, double target, double step) {
	}

	/** Each morning, before wages and rent: the council sets the laws for the day. */
	public static void govern(ServerLevel level, City city) {
		Strategy s = strategy(city);
		if (s == Strategy.NONE) {
			return;
		}
		Policies p = city.policies;
		p.autoBuild = true;
		p.autoAssign = true;
		int pop = Math.max(1, city.population());
		double mood = city.averageHappiness();
		int hungry = 0;
		for (CitizenRecord r : city.living()) {
			if (r.hungryDays > 0 || r.food < 25) {
				hungry++;
			}
		}
		long treasury = CommerceApi.balance(city.account());
		long reserve = 1500L * pop;
		boolean unrest = city.strike || city.riot || city.grievance > 25;
		List<Aim> aims = new ArrayList<>();
		boolean rations = hungry > 0;
		switch (s) {
			case GROWTH -> {
				aims.add(new Aim("incomeTax", "income tax", 8, 3));
				aims.add(new Aim("rent", "rent", 1, 1));
				aims.add(new Aim("wageLevel", "wages", 110, 10));
				aims.add(new Aim("workHours", "working day", 8, 1));
				aims.add(new Aim("breadPrice", "bread price", 1.75, 0.25));
				aims.add(new Aim("meatPrice", "meat price", 3.5, 0.25));
				aims.add(new Aim("basicIncome", "basic income", 0, 1));
				aims.add(new Aim("sentenceDays", "sentence", 2, 1));
				p.curfew = false;
				p.martialLaw = false;
				p.forcedLabor = false;
				p.safetyRules = true;
				p.subsidies = true;
				p.immigration = hungry * 5 < pop;
			}
			case EQUALITY -> {
				aims.add(new Aim("incomeTax", "income tax", 28, 3));
				aims.add(new Aim("rent", "rent", 0, 1));
				aims.add(new Aim("wageLevel", "wages", 100, 10));
				aims.add(new Aim("workHours", "working day", 7, 1));
				aims.add(new Aim("breadPrice", "bread price", 1.25, 0.25));
				aims.add(new Aim("meatPrice", "meat price", 2.5, 0.25));
				// what the treasury has to spare goes back to everyone
				double spare = Math.max(0, treasury - reserve) / 100.0 * 0.08 / pop;
				aims.add(new Aim("basicIncome", "basic income", Math.min(40, Math.round(spare)), 2));
				aims.add(new Aim("sentenceDays", "sentence", 1, 1));
				rations = true;
				p.curfew = false;
				p.martialLaw = false;
				p.forcedLabor = false;
				p.safetyRules = true;
				p.subsidies = true;
				p.immigration = hungry * 5 < pop;
			}
			case PROSPERITY -> {
				aims.add(new Aim("incomeTax", "income tax", 12, 3));
				aims.add(new Aim("rent", "rent", 3, 1));
				aims.add(new Aim("wageLevel", "wages", 105, 10));
				aims.add(new Aim("workHours", "working day", 9, 1));
				aims.add(new Aim("breadPrice", "bread price", 2.5, 0.25));
				aims.add(new Aim("meatPrice", "meat price", 5, 0.25));
				aims.add(new Aim("basicIncome", "basic income", 0, 1));
				aims.add(new Aim("dividendPercent", "dividend", 0.12, 0.02));
				aims.add(new Aim("sentenceDays", "sentence", 2, 1));
				rations = hungry * 4 > pop;
				p.curfew = false;
				p.martialLaw = false;
				p.forcedLabor = false;
				p.safetyRules = true;
				p.subsidies = true;
				p.immigration = true;
			}
			case ORDER -> {
				aims.add(new Aim("incomeTax", "income tax", 15, 3));
				aims.add(new Aim("rent", "rent", 2, 1));
				aims.add(new Aim("wageLevel", "wages", 100, 10));
				aims.add(new Aim("workHours", "working day", 9, 1));
				aims.add(new Aim("breadPrice", "bread price", 2, 0.25));
				aims.add(new Aim("meatPrice", "meat price", 4, 0.25));
				aims.add(new Aim("basicIncome", "basic income", 0, 1));
				aims.add(new Aim("sentenceDays", "sentence", 5, 1));
				p.curfew = true;
				p.martialLaw = unrest;
				p.forcedLabor = false;
				p.safetyRules = true;
				p.subsidies = true;
				p.immigration = true;
			}
			case EXTORTION -> {
				// as hard as they will bear: back off a little when they riot or collapse, squeeze harder when they don't
				boolean breaking = city.riot || mood < 12;
				boolean meek = !unrest && mood > 30;
				aims.add(new Aim("incomeTax", "income tax", breaking ? 40 : meek ? 70 : 55, 5));
				aims.add(new Aim("rent", "rent", breaking ? 8 : meek ? 15 : 12, 1));
				aims.add(new Aim("wageLevel", "wages", breaking ? 60 : meek ? 35 : 45, 10));
				aims.add(new Aim("workHours", "working day", breaking ? 11 : meek ? 15 : 14, 1));
				aims.add(new Aim("breadPrice", "bread price", 3.5, 0.25));
				aims.add(new Aim("meatPrice", "meat price", 7, 0.5));
				aims.add(new Aim("alePrice", "ale price", 3, 0.25));
				aims.add(new Aim("basicIncome", "basic income", 0, 2));
				aims.add(new Aim("sentenceDays", "sentence", 6, 1));
				aims.add(new Aim("dividendPercent", "dividend", 0.3, 0.05));
				// starving workers don't work: keep them alive, barely
				rations = city.living().stream().anyMatch(r -> r.hungryDays >= 2);
				p.curfew = true;
				p.martialLaw = unrest;
				p.forcedLabor = true;
				p.safetyRules = false;
				p.subsidies = false;
				p.immigration = true;
				skim(level, city, treasury, reserve);
			}
			case BALANCED -> {
				double tax = p.incomeTax;
				double wage = p.wageLevel;
				double rent = p.rent;
				if (mood < 60 || hungry > 0) {
					tax -= 3;
					wage += 10;
					rent -= 1;
				} else if (mood > 72 && treasury < city.treasuryYesterday) {
					tax += 2;
					rent += 0.5;
				}
				if (treasury < reserve / 3) {
					tax += 3;
					wage -= 5;
				}
				aims.add(new Aim("incomeTax", "income tax", Math.max(5, Math.min(30, tax)), 3));
				aims.add(new Aim("wageLevel", "wages", Math.max(80, Math.min(130, wage)), 10));
				aims.add(new Aim("rent", "rent", Math.max(0, Math.min(5, rent)), 1));
				aims.add(new Aim("workHours", "working day", 8, 1));
				aims.add(new Aim("breadPrice", "bread price", 2, 0.25));
				aims.add(new Aim("meatPrice", "meat price", 4, 0.25));
				aims.add(new Aim("basicIncome", "basic income", 0, 1));
				aims.add(new Aim("sentenceDays", "sentence", 2, 1));
				p.curfew = false;
				p.martialLaw = false;
				p.forcedLabor = false;
				p.safetyRules = true;
				p.subsidies = true;
				p.immigration = hungry * 5 < pop;
			}
			default -> {
			}
		}
		p.rations = rations;
		// a festival when spirits are low and the treasury can afford it (not for the extortionist)
		if (s != Strategy.EXTORTION && mood < 50 && treasury > 1000L * pop * 4 && city.festivalDays == 0) {
			long cost = 1000L * pop;
			if (CommerceApi.burn(city.account(), cost, "Town festival")) {
				city.festivalDays = 2;
				city.grievance = Math.max(0, city.grievance - 10);
				for (CitizenRecord r : city.living()) {
					r.happiness = Math.min(100, r.happiness + 10);
				}
				city.log("The council holds a festival");
			}
		}
		List<String> changed = new ArrayList<>();
		for (Aim a : aims) {
			double now = get(p, a.key);
			double next = now + Math.max(-a.step, Math.min(a.step, a.target - now));
			if (Math.abs(next - now) > 1e-6) {
				set(p, a.key, next);
				changed.add(a.label + " " + shown(a.key, get(p, a.key)));
			}
		}
		p.clamp();
		if (!changed.isEmpty()) {
			city.log("The council (" + s.title.toLowerCase(Locale.ROOT) + "): " + String.join(", ", changed));
		}
	}

	/** The extortionist's cut: a quarter of whatever the treasury holds above its reserve goes to the governor. */
	private static void skim(ServerLevel level, City city, long treasury, long reserve) {
		long cut = Math.max(0, (treasury - reserve) / 4);
		if (cut > 0 && city.governor != null && CommerceApi.transfer(city.account(), CommerceApi.playerAccount(city.governor), cut, "The governor's share")) {
			city.grievance += Math.min(5, cut / 50000.0);
			city.log("The governor's share: " + CommerceApi.format(cut) + " from the treasury");
		}
	}

	/** What the strategy wants built first (before the ordinary order of things), or null. */
	public static @Nullable BuildingType priority(City city) {
		Strategy s = strategy(city);
		int pop = city.population();
		return switch (s) {
			case ORDER -> first(city, pop >= 5, BuildingType.SHERIFF, pop >= 7, BuildingType.BARRACKS, pop >= 9, BuildingType.PRISON);
			case PROSPERITY -> first(city, pop >= 6, BuildingType.BLACKSMITH, pop >= 8, BuildingType.BANK, pop >= 12, BuildingType.FACTORY);
			case EXTORTION -> first(city, pop >= 4, BuildingType.MINE, pop >= 8, BuildingType.PRISON, pop >= 10, BuildingType.FACTORY);
			case EQUALITY -> first(city, pop >= 6, BuildingType.TAVERN, false, null, false, null);
			case GROWTH -> city.beds() < pop + 6 ? BuildingType.HOUSE
				: city.of(BuildingType.FARM, false).size() < 1 + pop / 8 ? BuildingType.FARM : null;
			default -> null;
		};
	}

	private static @Nullable BuildingType first(City city, boolean a, @Nullable BuildingType ta, boolean b, @Nullable BuildingType tb, boolean c,
		@Nullable BuildingType tc) {
		if (a && ta != null && city.of(ta, false).isEmpty()) {
			return ta;
		}
		if (b && tb != null && city.of(tb, false).isEmpty()) {
			return tb;
		}
		if (c && tc != null && city.of(tc, false).isEmpty()) {
			return tc;
		}
		return null;
	}

	private static double get(Policies p, String key) {
		try {
			return ((Number) Policies.class.getField(key).get(p)).doubleValue();
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	private static void set(Policies p, String key, double v) {
		try {
			var f = Policies.class.getField(key);
			if (f.getType() == int.class) {
				f.setInt(p, (int) Math.round(v));
			} else {
				f.setDouble(p, Math.round(v * 100) / 100.0);
			}
		} catch (ReflectiveOperationException ignored) {
		}
	}

	private static String shown(String key, double v) {
		return switch (key) {
			case "incomeTax", "wageLevel" -> Math.round(v) + "%";
			case "workHours" -> Math.round(v) + " h";
			case "sentenceDays" -> Math.round(v) + " days";
			case "dividendPercent" -> String.format(Locale.ROOT, "%.2f%%", v);
			default -> CommerceApi.format(Math.round(v * 100));
		};
	}
}
