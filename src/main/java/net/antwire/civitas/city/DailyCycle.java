package net.antwire.civitas.city;

import java.util.List;

import java.util.Iterator;
import java.util.Map;
import net.antwire.civitas.CivitasConfig;
import net.antwire.civitas.compat.RedButtonCompat;
import net.antwire.civitas.entity.CitizenEntity;
import net.antwire.commerce.api.CommerceApi;
import net.antwire.commerce.block.entity.ShopBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Dawn in town: wages, taxes, rent and welfare change hands; clothes wear out and are replaced by those who can afford
 * it; the hungry get hungrier; everybody takes stock of their life (happiness); the desperate turn to theft or leave;
 * the businesses hand their profits to the treasury and the books are closed.
 */
public final class DailyCycle {
	private DailyCycle() {
	}

	static long cents(double units) {
		return Math.round(units * 100);
	}

	public static long wage(City city, Job job) {
		return Math.round(CivitasConfig.get().baseWage * 100 * job.wageFactor * city.policies.wageLevel / 100.0);
	}

	public static void run(ServerLevel level, City city, CityManager m) {
		CivitasConfig cfg = CivitasConfig.get();
		Policies p = city.policies;
		String treasury = city.account();
		long treasuryStart = CommerceApi.balance(treasury);
		int crimes = 0;
		for (CitizenRecord r : city.citizens.values()) {
			if (r.status == CitizenRecord.Status.WANTED) {
				crimes++;
			}
		}
		Building hall = city.townHall();
		for (CitizenRecord r : city.citizens.values()) {
			if (!r.present()) {
				continue;
			}
			String acct = r.account(city);
			Map<String, Double> mood = r.mood;
			// carried-over feelings fade
			for (Iterator<Map.Entry<String, Double>> it = mood.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<String, Double> e = it.next();
				if (e.getKey().equals("Kindness") || e.getKey().equals("Grief") || e.getKey().equals("No food")) {
					e.setValue(e.getValue() * 0.5);
					if (Math.abs(e.getValue()) < 0.5) {
						it.remove();
					}
				} else {
					it.remove();
				}
			}
			boolean jailed = r.status == CitizenRecord.Status.JAILED;
			// ---- wages
			long wage = 0;
			boolean struck = city.strike && r.job != Job.UNEMPLOYED;
			if (r.job != Job.UNEMPLOYED && !jailed && !struck) {
				wage = wage(city, r.job);
				if (wage > 0) {
					Building w = city.building(r.workplace);
					String payer = r.job.publicSector || w == null ? treasury : w.account(city);
					if (!payer.equals(treasury) && CommerceApi.balance(payer) < wage && p.subsidies) {
						long gap = wage - CommerceApi.balance(payer);
						if (CommerceApi.transfer(treasury, payer, gap, "Subsidy")) {
							city.spendingToday += gap;
						}
					}
					if (CommerceApi.transfer(payer, acct, wage, "Wages")) {
						if (payer.equals(treasury)) {
							city.spendingToday += wage;
						} else if (w != null) {
							w.costToday += wage;
						}
						r.lastWage = wage;
					} else {
						r.lastWage = 0;
						wage = 0;
						mood.put("Unpaid wages", -12.0);
						r.think("They didn't pay me!");
					}
				}
			}
			// ---- taxes, rent, welfare
			long tax = Math.round(wage * p.incomeTax / 100.0);
			if (tax > 0 && CommerceApi.transfer(acct, treasury, tax, "Income tax")) {
				city.incomeToday += tax;
			}
			long rent = r.home >= 0 && !jailed ? cents(p.rent) : 0;
			if (rent > 0) {
				if (CommerceApi.transfer(acct, treasury, rent, "Rent")) {
					city.incomeToday += rent;
				} else {
					mood.put("Behind on rent", -8.0);
					r.think("I can't pay the rent...");
				}
			}
			long welfare = cents(p.basicIncome);
			if (welfare > 0 && CommerceApi.transfer(treasury, acct, welfare, "Basic income")) {
				city.spendingToday += welfare;
			}
			long balance = CommerceApi.balance(acct);
			// ---- clothes wear out; new ones if there's money to spare
			double wear = switch (r.job) {
				case MINER, FARMER, BUTCHER, LUMBERJACK, BUILDER, FACTORY_WORKER, BLACKSMITH -> 6;
				case PRISONER -> 9;
				default -> 4;
			};
			if (jailed) {
				wear = 9;
			}
			if (p.workHours > 10 && r.job != Job.UNEMPLOYED) {
				wear += (p.workHours - 10) * 0.8;
			}
			if (!r.ateToday && !r.rationed) {
				wear += 5;
			}
			r.clothing = Math.max(0, r.clothing - wear);
			long clothes = 1500;
			if (r.clothing < 70 && balance > clothes + 3000 && !jailed) {
				CommerceApi.burn(acct, clothes, "New clothes");
				r.clothing = 100;
				r.think("New clothes!");
				balance -= clothes;
			}
			// ---- hunger
			if (!r.ateToday && !r.rationed && r.food < 30) {
				r.hungryDays++;
			} else {
				r.hungryDays = 0;
			}
			if (cfg.starvationDays > 0 && r.hungryDays >= cfg.starvationDays) {
				r.cause = "starvation";
				CitizenEntity e = m.entity(r.uuid);
				if (e != null) {
					e.hurtServer(level, level.damageSources().starve(), 1000);
				} else {
					r.status = CitizenRecord.Status.DEAD;
					city.log(r.name + " starved to death");
					Townlife.release(city, r);
				}
				continue;
			}
			// ---- how life feels
			if (r.ateToday || r.rationed) {
				mood.put("Fed", r.rationed && !r.ateToday ? 2.0 : 8.0);
			} else {
				mood.put(r.hungryDays >= 2 ? "Starving" : "Hungry", r.hungryDays >= 2 ? -30.0 : -14.0);
			}
			mood.put(r.sleptInBed ? "Slept in a bed" : "Slept rough", r.sleptInBed ? 5.0 : -9.0);
			if (p.workHours > 8 && r.job != Job.UNEMPLOYED) {
				mood.put("Long hours", -(p.workHours - 8) * 3.5);
			} else if (p.workHours < 8 && r.job != Job.UNEMPLOYED) {
				mood.put("Short hours", (8 - p.workHours) * 1.5);
			}
			if (r.job != Job.UNEMPLOYED && !jailed) {
				long net = wage - tax - rent + welfare;
				double disposable = (net - 450) / 100.0;
				mood.put("Income", Math.max(-20, Math.min(12, disposable * 1.6)));
			} else if (!jailed) {
				mood.put("Unemployed", -8.0);
			}
			if (p.incomeTax > 0) {
				mood.put("Taxes", -p.incomeTax * 0.22);
			}
			mood.put(r.home >= 0 ? "Home" : "Homeless", r.home >= 0 ? 6.0 : -12.0);
			if (balance > 10000) {
				mood.put("Savings", 5.0);
			} else if (balance < 500) {
				mood.put("Broke", -6.0);
			}
			if (r.clothing < 30) {
				mood.put("Rags", -6.0);
			}
			if (r.visitedTavern) {
				mood.put("Evening at the tavern", 5.0);
			}
			if (city.festivalDays > 0) {
				mood.put("Festival", 15.0);
			}
			if (crimes > 0) {
				mood.put("Crime", -Math.min(8.0, crimes * 1.5));
			}
			if (city.fear > 1) {
				mood.put("Fear", -city.fear);
			}
			if (city.grievance > 1) {
				mood.put("Grievances", -city.grievance);
			}
			if (p.curfew) {
				mood.put("Curfew", -6.0);
			}
			if (p.martialLaw) {
				mood.put("Martial law", -10.0);
			}
			if (jailed) {
				mood.put("Imprisoned", -25.0);
			}
			double target = 50;
			for (double v : mood.values()) {
				target += v;
			}
			target = Math.max(0, Math.min(100, target));
			r.happiness = Math.max(0, Math.min(100, r.happiness * 0.55 + target * 0.45));
			r.miserableDays = r.happiness < 20 ? r.miserableDays + 1 : 0;
			// ---- the desperate
			if (cfg.crime && !jailed && ((balance < 300 && r.food < 40) || r.happiness < 15) && m.random.nextDouble() < 0.35) {
				r.thief = true;
			}
			if (r.miserableDays >= 3 && !jailed && m.random.nextDouble() < 0.35) {
				r.leaving = true;
				city.log(r.name + " has had enough and is leaving");
			}
			if (r.status == CitizenRecord.Status.WANTED && city.firstComplete(BuildingType.SHERIFF) == null && m.random.nextBoolean()) {
				r.status = CitizenRecord.Status.FREE;
			}
			r.ateToday = false;
			r.rationed = false;
			r.visitedTavern = false;
			r.sleptInBed = false;
		}

		// ---- unrest
		double avg = city.averageHappiness();
		boolean wasStrike = city.strike;
		boolean wasRiot = city.riot;
		city.unrestDays = avg < 30 ? city.unrestDays + 1 : 0;
		city.strike = city.unrestDays >= 1 && avg < 30;
		city.riot = city.unrestDays >= 2 && avg < 15;
		if (city.strike && !wasStrike) {
			city.log("The workers are on strike!");
		} else if (!city.strike && wasStrike) {
			city.log("The strike is over");
		}
		if (city.riot && !wasRiot) {
			city.log("Riots in the streets!");
		}
		if (p.martialLaw && (city.strike || city.riot)) {
			// the sheriff rounds up the ringleaders
			int n = 0;
			for (CitizenRecord r : city.citizens.values()) {
				if (r.free() && r.job != Job.SHERIFF && r.happiness < 25 && n < 3 && m.random.nextBoolean()) {
					r.status = CitizenRecord.Status.WANTED;
					r.crimes++;
					n++;
				}
			}
			city.fear += n * 3;
		}

		// ---- businesses: books, profits to the treasury, exports
		long profits = 0;
		for (Building b : city.buildings) {
			b.revenueYesterday = b.revenueToday;
			b.costYesterday = b.costToday;
			b.revenueToday = 0;
			b.costToday = 0;
			b.producedToday = 0;
			if (!b.complete || b.type.job == null || b.type.job.publicSector) {
				continue;
			}
			String acct = b.account(city);
			long reserve = 20000;
			long bal = CommerceApi.balance(acct);
			if (bal > reserve) {
				long sweep = bal - reserve;
				if (CommerceApi.transfer(acct, treasury, sweep, "Profits")) {
					profits += sweep;
					city.incomeToday += sweep;
				}
			}
			if (b.type == BuildingType.FACTORY) {
				exportMissiles(level, city, b);
			}
		}
		Townlife.updatePrices(level, city);
		net.antwire.civitas.entity.ai.Military.daily(level, city);
		// can everyone still get in everywhere?
		for (Building b : List.copyOf(city.buildings)) {
			if (b.complete) {
				Townlife.checkAccess(level, city, b);
			}
		}

		// ---- feelings fade, the books close
		city.grievance *= 0.7;
		city.fear *= 0.7;
		if (city.festivalDays > 0) {
			city.festivalDays--;
		}
		long treasuryEnd = CommerceApi.balance(treasury);
		City.DayStat s = new City.DayStat();
		s.day = city.day;
		s.population = city.population();
		s.happiness = avg;
		s.treasury = treasuryEnd;
		s.crimes = crimes;
		for (CitizenRecord r : city.citizens.values()) {
			if (r.present()) {
				if (r.job != Job.UNEMPLOYED) {
					s.employed++;
				}
				s.tiers[r.tier(CommerceApi.balance(r.account(city)))]++;
			}
		}
		for (Building b : city.buildings) {
			s.output += b.revenueYesterday;
		}
		city.history.add(s);
		while (city.history.size() > 60) {
			city.history.removeFirst();
		}
		city.incomeYesterday = city.incomeToday;
		city.spendingYesterday = city.spendingToday;
		city.incomeToday = 0;
		city.spendingToday = 0;
		city.treasuryYesterday = treasuryStart;
		if (!city.ticker.isEmpty() && CommerceApi.isListed(city.ticker)) {
			CommerceApi.reportEarnings(city.ticker, treasuryEnd - treasuryStart + profits / 2);
			CommerceApi.setDividend(city.ticker, p.dividendPercent);
			if (city.strike) {
				CommerceApi.news(city.ticker, "Strike paralyses " + city.name, -6);
			}
			if (city.riot) {
				CommerceApi.news(city.ticker, "Riots in " + city.name, -12);
			}
		}
	}

	/** The arms buyer takes all but a few missiles off the factory's hands. */
	static void exportMissiles(ServerLevel level, City city, Building b) {
		ShopBlockEntity shop = Storage.shop(level, b);
		Item missile = RedButtonCompat.missile();
		if (shop == null) {
			return;
		}
		Item product = missile != null ? missile : net.minecraft.world.item.Items.TNT;
		int keep = missile != null ? 2 : 8;
		int have = 0;
		for (int i = 0; i < shop.getContainerSize(); i++) {
			if (shop.getItem(i).is(product)) {
				have += shop.getItem(i).getCount();
			}
		}
		int sell = have - keep;
		for (int i = 0; i < shop.getContainerSize() && sell > 0; i++) {
			ItemStack st = shop.getItem(i);
			if (st.is(product)) {
				int k = Math.min(sell, st.getCount());
				st.shrink(k);
				sell -= k;
				long pay = missile != null ? Math.round(CivitasConfig.get().missileExportPrice * 100) * k : 1200L * k;
				CommerceApi.mint(b.account(city), pay, "Sold " + k + "× " + new ItemStack(product).getHoverName().getString() + " to the state");
				b.revenueToday += pay;
				city.log("The factory delivered " + k + "× " + new ItemStack(product).getHoverName().getString() + " to the state arms buyer");
			}
		}
		shop.setChanged();
	}
}
