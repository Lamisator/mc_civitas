package net.antwire.civitas.network;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import net.antwire.civitas.Civitas;
import net.antwire.civitas.city.Building;
import net.antwire.civitas.city.BuildingType;
import net.antwire.civitas.city.Blueprints;
import net.antwire.civitas.city.CitizenRecord;
import net.antwire.civitas.city.City;
import net.antwire.civitas.city.CityManager;
import net.antwire.civitas.city.Construction;
import net.antwire.civitas.city.DailyCycle;
import net.antwire.civitas.city.Job;
import net.antwire.civitas.city.Townlife;
import net.antwire.civitas.entity.CitizenEntity;
import net.antwire.commerce.api.CommerceApi;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Server side of the screens: town founding, the governor's ledger and the citizen card. */
public final class ServerNet {
	private ServerNet() {
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(Payloads.State.TYPE, Payloads.State.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.Action.TYPE, Payloads.Action.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Action.TYPE, (payload, context) -> {
			try {
				handle(context.player(), payload.kind(), payload.json());
			} catch (RuntimeException e) {
				Civitas.LOGGER.warn("Bad {} action from {}", payload.kind(), context.player().getGameProfile().name(), e);
			}
		});
	}

	static void send(ServerPlayer p, String kind, Object dto) {
		ServerPlayNetworking.send(p, new Payloads.State(kind, Dto.GSON.toJson(dto)));
	}

	static void message(ServerPlayer p, String text, boolean ok) {
		Dto.Message m = new Dto.Message();
		m.text = text;
		m.ok = ok;
		send(p, "message", m);
		p.sendOverlayMessage(Component.literal(text).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
	}

	static boolean mayGovern(ServerPlayer p, City c) {
		return c.isGovernor(p.getUUID()) || p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	public static void openFound(ServerPlayer p, BlockPos pos) {
		Dto.Found f = new Dto.Found();
		f.x = pos.getX();
		f.y = pos.getY();
		f.z = pos.getZ();
		send(p, "found", f);
	}

	public static void openGovern(ServerPlayer p, City c) {
		send(p, "govern", govern(p, c));
	}

	public static void openCitizen(ServerPlayer p, City c, UUID id) {
		Dto.Citizen d = citizen(p, c, id);
		if (d != null) {
			send(p, "citizen", d);
		}
	}

	// ------------------------------------------------------------------ state

	public static Dto.Govern govern(ServerPlayer p, City c) {
		ServerLevel level = (ServerLevel) p.level();
		Dto.Govern d = new Dto.Govern();
		d.id = c.id.toString();
		d.name = c.name;
		d.governor = c.governorName;
		d.editable = mayGovern(p, c);
		d.day = c.day;
		d.population = c.population();
		d.beds = c.beds();
		d.happiness = c.averageHappiness();
		d.treasury = CommerceApi.balance(c.account());
		d.income = c.incomeYesterday;
		d.spending = c.spendingYesterday;
		d.strike = c.strike;
		d.riot = c.riot;
		d.ticker = c.ticker;
		d.sharePrice = c.ticker.isEmpty() ? -1 : CommerceApi.sharePrice(c.ticker);
		d.playerBalance = CommerceApi.balance(CommerceApi.playerAccount(p.getUUID()));
		d.grievance = c.grievance;
		d.fear = c.fear;
		d.festivalDays = c.festivalDays;
		d.policies = c.policies;
		d.baseWage = Math.round(net.antwire.civitas.CivitasConfig.get().baseWage * 100);
		for (CitizenRecord r : c.citizens.values()) {
			if (!r.present()) {
				continue;
			}
			Dto.CitizenRow row = new Dto.CitizenRow();
			row.uuid = r.uuid.toString();
			row.name = r.name;
			row.job = r.job.title;
			long bal = CommerceApi.balance(r.account(c));
			row.tier = r.tier(bal);
			row.happiness = r.happiness;
			row.food = r.food;
			row.balance = bal;
			row.status = r.status.name();
			row.homeless = r.home < 0;
			row.workplace = r.workplace;
			d.citizens.add(row);
			d.tiers[row.tier]++;
			if (r.job != Job.UNEMPLOYED) {
				d.employed++;
			}
		}
		for (Building b : c.buildings) {
			Dto.BuildingRow row = new Dto.BuildingRow();
			row.id = b.id;
			row.type = b.type.id();
			row.title = b.type.title;
			row.complete = b.complete;
			int steps = Math.max(1, b.steps <= 0 ? Construction.steps(b).size() : b.steps);
			row.progress = b.complete ? 100 : (int) Math.min(99, b.progress * 100L / steps);
			row.workers = b.type == BuildingType.TOWN_HALL ? (int) c.citizens.values().stream().filter(r -> r.present() && r.job == Job.BUILDER).count()
				: b.workers.size();
			row.slots = b.workerSlots();
			row.residents = b.residents.size();
			row.beds = b.bedCount();
			row.stalled = b.stalled;
			row.tier = b.tier;
			row.upgrading = b.upgrading() ? b.targetTier : 0;
			if (level != null && b.complete && !b.upgrading() && b.tier < Blueprints.TIERS) {
				String why = Townlife.whyNotUpgrade(level, c, b);
				row.upgradeBlocked = why == null ? "" : why;
				row.upgradeCost = Construction.upgradeCost(b, b.tier + 1, Townlife.upgradeOrigin(b, b.tier + 1));
			} else {
				row.upgradeBlocked = b.tier >= Blueprints.TIERS ? "highest tier" : "";
			}
			row.revenue = b.revenueYesterday;
			row.cost = b.costYesterday;
			row.balance = CommerceApi.balance(b.account(c));
			BlockPos e = b.entrance();
			row.x = e.getX();
			row.y = e.getY();
			row.z = e.getZ();
			d.buildings.add(row);
		}
		net.antwire.civitas.city.Council.Strategy strat = net.antwire.civitas.city.Council.strategy(c);
		d.strategy = strat.name().toLowerCase(java.util.Locale.ROOT);
		d.strategyTitle = strat.title;
		d.strategyText = strat.description;
		d.op = p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		d.keepLoaded = c.keepLoaded == null ? -1 : c.keepLoaded ? 1 : 0;
		d.keepsLoaded = c.keepsLoaded();
		for (int i = 0; i < Math.min(40, c.log.size()); i++) {
			d.log.add(c.log.get(i));
		}
		d.history.addAll(c.history);
		c.stockpile.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(16)
			.forEach(e -> d.stockpile.put(e.getKey(), e.getValue()));
		for (BuildingType t : BuildingType.values()) {
			if (t == BuildingType.TOWN_HALL) {
				continue;
			}
			Dto.Plan plan = new Dto.Plan();
			plan.type = t.id();
			plan.title = t.title;
			plan.cost = Construction.estimate(t, c.tier());
			plan.note = note(t);
			d.plans.add(plan);
		}
		return d;
	}

	static String note(BuildingType t) {
		return switch (t) {
			case HOUSE -> "Beds for 4";
			case FARM -> "Wheat, carrots, potatoes";
			case BAKERY -> "Bread from wheat";
			case BUTCHER -> "Cattle and pigs, roast meat";
			case BLACKSMITH -> "Iron and tools";
			case MINE -> "Digs for stone and ore";
			case LUMBER_MILL -> "Timber for building";
			case BANK -> "Bank terminals for all";
			case SHERIFF -> "Law and order";
			case PRISON -> "Cells for criminals";
			case FACTORY -> net.antwire.civitas.compat.RedButtonCompat.missile() != null ? "Builds missiles" : "Makes explosives";
			case TAVERN -> "Ale and good company";
			case BARRACKS -> "Soldiers defend the town";
			default -> "";
		};
	}

	public static Dto.Citizen citizen(ServerPlayer p, City c, UUID id) {
		CitizenRecord r = c.citizens.get(id);
		if (r == null) {
			return null;
		}
		Dto.Citizen d = new Dto.Citizen();
		d.city = c.id.toString();
		d.uuid = r.uuid.toString();
		d.name = r.name;
		d.job = r.job.title;
		d.age = r.age;
		d.female = r.female;
		long bal = CommerceApi.balance(r.account(c));
		d.tier = r.tier(bal);
		d.happiness = r.happiness;
		d.food = r.food;
		d.rest = r.rest;
		d.clothing = r.clothing;
		d.balance = bal;
		d.wage = r.job == Job.UNEMPLOYED ? 0 : DailyCycle.wage(c, r.job);
		d.status = r.status.name();
		Building home = c.building(r.home);
		Building work = c.building(r.workplace);
		d.home = home == null ? "homeless" : home.type.title + " #" + home.id;
		d.workplace = r.job == Job.BUILDER ? "construction sites" : work == null ? "-" : work.type.title + " #" + work.id;
		d.editable = mayGovern(p, c);
		d.mood.putAll(r.mood);
		d.thoughts.addAll(r.thoughts);
		CitizenEntity e = CityManager.get() == null ? null : CityManager.get().entity(r.uuid);
		d.entityId = e == null ? -1 : e.getId();
		d.jobs.add(new Object[]{-1, "Unemployed", 99});
		Building hall = c.townHall();
		if (hall != null) {
			d.jobs.add(new Object[]{hall.id, "Builder", 99});
		}
		for (Building b : c.buildings) {
			if (b.complete && b.type.job != null && b.type != BuildingType.TOWN_HALL) {
				d.jobs.add(new Object[]{b.id, b.type.job.title + " (" + b.type.title + ")", b.workerSlots() - b.workers.size()});
			}
		}
		return d;
	}

	// ------------------------------------------------------------------ actions

	private static void handle(ServerPlayer p, String kind, String json) {
		CityManager m = CityManager.get();
		if (m == null) {
			return;
		}
		ServerLevel level = (ServerLevel) p.level();
		if (kind.equals("found")) {
			Dto.Found f = Dto.GSON.fromJson(json, Dto.Found.class);
			found(p, m, level, f);
			return;
		}
		Dto.Action a = Dto.GSON.fromJson(json, Dto.Action.class);
		City c;
		try {
			c = m.city(UUID.fromString(a.city));
		} catch (RuntimeException e) {
			return;
		}
		if (c == null) {
			return;
		}
		if (kind.equals("citizen")) {
			citizenAction(p, m, level, c, a);
			return;
		}
		if (!a.op.equals("refresh") && !mayGovern(p, c)) {
			message(p, "Only the governor of " + c.name + " may do that", false);
			return;
		}
		switch (a.op) {
			case "refresh" -> {
			}
			case "policy" -> policy(p, c, level, a.key, a.value);
			case "build" -> {
				BuildingType t = BuildingType.byId(a.text);
				if (t == null || t == BuildingType.TOWN_HALL) {
					break;
				}
				long fee = (long) t.fee * 100;
				if (CommerceApi.balance(c.account()) < fee) {
					message(p, "The treasury can't pay the planning fee (" + CommerceApi.format(fee) + ")", false);
					break;
				}
				Building b = m.plan(level, c, t);
				if (b == null) {
					message(p, "No room for a " + t.title.toLowerCase() + " - the town needs open, fairly flat land", false);
				} else {
					CommerceApi.burn(c.account(), fee, "Planning fee: " + t.title);
					BlockPos e = b.entrance();
					message(p, t.title + " planned at " + e.getX() + " " + e.getY() + " " + e.getZ(), true);
				}
			}
			case "upgrade" -> {
				Building b = c.building(a.building);
				if (b == null) {
					break;
				}
				String why = Townlife.whyNotUpgrade(level, c, b);
				if (why != null) {
					message(p, "The " + b.type.title.toLowerCase() + " can't be upgraded: " + why, false);
				} else if (Townlife.upgrade(level, c, b)) {
					message(p, b.type.title + " to be raised to tier " + b.targetTier + " - the builders start on it next", true);
				}
			}
			case "demolish" -> {
				Building b = c.building(a.building);
				if (b != null && b.type != BuildingType.TOWN_HALL) {
					for (CitizenRecord r : c.citizens.values()) {
						if (r.workplace == b.id) {
							r.workplace = -1;
							r.job = Job.UNEMPLOYED;
						}
						if (r.home == b.id) {
							r.home = -1;
						}
					}
					c.buildings.remove(b);
					c.log("The " + b.type.title.toLowerCase() + " was abandoned by order of the governor");
					message(p, b.type.title + " abandoned (the walls stay standing)", true);
				}
			}
			case "festival" -> {
				long cost = 1000L * Math.max(1, c.population());
				if (CommerceApi.burn(c.account(), cost, "Town festival")) {
					c.festivalDays = 2;
					c.grievance = Math.max(0, c.grievance - 10);
					for (CitizenRecord r : c.citizens.values()) {
						if (r.present()) {
							r.happiness = Math.min(100, r.happiness + 10);
						}
					}
					c.log("A festival! The whole town celebrates");
					fireworks(level, c);
					message(p, "Festival under way (" + CommerceApi.format(cost) + ")", true);
				} else {
					message(p, "A festival costs " + CommerceApi.format(cost), false);
				}
			}
			case "confiscate" -> {
				long total = 0;
				for (CitizenRecord r : c.citizens.values()) {
					if (r.present()) {
						long bal = CommerceApi.balance(r.account(c));
						if (bal > 0 && CommerceApi.transfer(r.account(c), c.account(), bal, "Confiscated by the governor")) {
							total += bal;
						}
						r.happiness = Math.max(0, r.happiness - 25);
						r.think("They took everything we had!");
					}
				}
				c.grievance += 30;
				c.fear += 10;
				c.log("The governor confiscated all savings: " + CommerceApi.format(total));
				message(p, "Confiscated " + CommerceApi.format(total), true);
			}
			case "embezzle" -> {
				long amt = Math.max(0, a.amount);
				if (CommerceApi.transfer(c.account(), CommerceApi.playerAccount(p.getUUID()), amt, "Governor's withdrawal")) {
					if (amt > CommerceApi.balance(c.account()) / 4 + 2000) {
						c.grievance += Math.min(25, amt / 10000.0);
						c.log("Rumours: the governor helped themselves to " + CommerceApi.format(amt) + " of public money");
					}
					message(p, "Took " + CommerceApi.format(amt) + " from the treasury", true);
				} else {
					message(p, "The treasury doesn't have that much", false);
				}
			}
			case "deposit" -> {
				long amt = Math.max(0, a.amount);
				if (CommerceApi.transfer(CommerceApi.playerAccount(p.getUUID()), c.account(), amt, "Governor's donation")) {
					c.grievance = Math.max(0, c.grievance - amt / 20000.0);
					c.log("The governor donated " + CommerceApi.format(amt) + " to the town");
					message(p, "Donated " + CommerceApi.format(amt), true);
				} else {
					message(p, "You don't have that much in the bank", false);
				}
			}
			case "ipo" -> ipo(p, c);
			case "strategy" -> {
				net.antwire.civitas.city.Council.Strategy s = net.antwire.civitas.city.Council.Strategy.byId(a.text);
				c.strategy = s.name().toLowerCase(java.util.Locale.ROOT);
				if (s == net.antwire.civitas.city.Council.Strategy.NONE) {
					c.log(p.getGameProfile().name() + " takes the town's affairs back into their own hands");
					message(p, "You govern " + c.name + " yourself again", true);
				} else {
					c.log("The council now governs the town: " + s.title.toLowerCase(java.util.Locale.ROOT));
					message(p, "The council governs " + c.name + " (" + s.title + ") - it sets the laws each morning", true);
				}
			}
			case "keeploaded" -> {
				if (!p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
					message(p, "Only an operator may change that", false);
					break;
				}
				c.keepLoaded = a.value > 0.5 ? Boolean.TRUE : a.value < -0.5 ? null : Boolean.FALSE;
				message(p, c.name + (c.keepsLoaded() ? " stays loaded while nobody is near" : " sleeps while nobody is near")
					+ (c.keepLoaded == null ? " (server default)" : ""), true);
			}
			case "rename" -> {
				if (a.text != null && !a.text.isBlank() && a.text.length() <= 32) {
					c.log("The town was renamed from " + c.name + " to " + a.text.trim());
					c.name = a.text.trim();
				}
			}
			default -> {
			}
		}
		openGovern(p, c);
	}

	private static void found(ServerPlayer p, CityManager m, ServerLevel level, Dto.Found f) {
		String name = f.name == null ? "" : f.name.trim();
		if (name.isEmpty() || name.length() > 32) {
			message(p, "Give your town a name (up to 32 letters)", false);
			return;
		}
		if (!p.getMainHandItem().is(net.antwire.civitas.registry.ModItems.TOWN_CHARTER) && !p.getOffhandItem().is(net.antwire.civitas.registry.ModItems.TOWN_CHARTER)) {
			message(p, "You need the town charter in hand", false);
			return;
		}
		BlockPos pos = new BlockPos(f.x, f.y, f.z);
		if (p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 64 * 64) {
			return;
		}
		Direction front = Direction.getApproximateNearest(p.getX() - (pos.getX() + 0.5), 0, p.getZ() - (pos.getZ() + 0.5));
		if (front.getAxis().isVertical()) {
			front = Direction.SOUTH;
		}
		StringBuilder why = new StringBuilder();
		City c = m.found(level, p, name, pos, front, why);
		if (c == null) {
			message(p, why.toString(), false);
			return;
		}
		level.playSound(null, pos, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.0F, 1.0F);
		p.sendSystemMessage(Component.literal("You founded " + name + ". The settlers will build the town hall first; open the charter to govern.")
			.withStyle(ChatFormatting.GOLD));
		openGovern(p, c);
	}

	private static void policy(ServerPlayer p, City c, ServerLevel level, String key, double value) {
		try {
			Field f = c.policies.getClass().getField(key);
			Object before = f.get(c.policies);
			if (f.getType() == boolean.class) {
				f.setBoolean(c.policies, value != 0);
			} else if (f.getType() == int.class) {
				f.setInt(c.policies, (int) Math.round(value));
			} else if (f.getType() == double.class) {
				f.setDouble(c.policies, value);
			} else {
				return;
			}
			c.policies.clamp();
			Object after = f.get(c.policies);
			if (!before.equals(after)) {
				c.log("New law: " + label(key) + " " + show(after));
				if (key.endsWith("Price")) {
					Townlife.updatePrices(level, c);
				}
			}
		} catch (ReflectiveOperationException e) {
			message(p, "No such law", false);
		}
	}

	static String label(String key) {
		return switch (key) {
			case "incomeTax" -> "income tax";
			case "rent" -> "rent";
			case "wageLevel" -> "wage level";
			case "workHours" -> "working day";
			case "breadPrice" -> "bread price";
			case "meatPrice" -> "meat price";
			case "alePrice" -> "ale price";
			case "rations" -> "free rations";
			case "basicIncome" -> "basic income";
			case "forcedLabor" -> "forced labour for prisoners";
			case "curfew" -> "curfew";
			case "martialLaw" -> "martial law";
			case "safetyRules" -> "factory safety rules";
			case "sentenceDays" -> "sentence";
			case "autoBuild" -> "town planning by the council";
			case "autoAssign" -> "free choice of work";
			case "subsidies" -> "wage subsidies";
			case "immigration" -> "immigration";
			case "dividendPercent" -> "dividend";
			default -> key;
		};
	}

	static String show(Object v) {
		if (v instanceof Boolean b) {
			return b ? "on" : "off";
		}
		if (v instanceof Double d) {
			return d == Math.rint(d) ? Long.toString(Math.round(d)) : String.format(java.util.Locale.ROOT, "%.2f", d);
		}
		return String.valueOf(v);
	}

	private static void ipo(ServerPlayer p, City c) {
		if (!c.ticker.isEmpty() && CommerceApi.isListed(c.ticker)) {
			message(p, c.name + " is already listed as " + c.ticker, false);
			return;
		}
		String base = c.name.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z]", "");
		if (base.length() < 2) {
			base = "TOWN";
		}
		String ticker = base.substring(0, Math.min(4, base.length()));
		int n = 1;
		while (CommerceApi.isListed(ticker)) {
			ticker = base.substring(0, Math.min(3, base.length())) + n++;
		}
		long shares = 100_000;
		long worth = CommerceApi.balance(c.account()) + c.population() * 50_000L + c.buildings.size() * 80_000L;
		long price = Math.max(500, worth / shares);
		long ownShares = shares * 51 / 100;
		if (CommerceApi.listCompany(ticker, c.name + " Town Corporation", "City", shares, price, c.account(), ownShares, c.policies.dividendPercent)) {
			c.ticker = ticker;
			long raised = (shares - ownShares) * price;
			CommerceApi.mint(c.account(), raised, "Share issue (" + (shares - ownShares) + " shares)");
			c.log(c.name + " went public as " + ticker + " at " + CommerceApi.format(price) + " a share and raised " + CommerceApi.format(raised));
			message(p, "Listed as " + ticker + " - raised " + CommerceApi.format(raised), true);
		}
	}

	private static void citizenAction(ServerPlayer p, CityManager m, ServerLevel level, City c, Dto.Action a) {
		CitizenRecord r;
		try {
			r = c.citizens.get(UUID.fromString(a.citizen));
		} catch (RuntimeException e) {
			return;
		}
		if (r == null) {
			return;
		}
		CitizenEntity e = m.entity(r.uuid);
		boolean gov = mayGovern(p, c);
		switch (a.op) {
			case "gift" -> {
				long amt = Math.max(0, a.amount);
				if (CommerceApi.transfer(CommerceApi.playerAccount(p.getUUID()), r.account(c), amt, "Gift from " + p.getGameProfile().name())) {
					r.happiness = Math.min(100, r.happiness + Math.min(15, amt / 500.0));
					r.mood.merge("Kindness", Math.min(10, amt / 500.0), Double::sum);
					if (e != null) {
						e.say("Thank you, " + p.getGameProfile().name() + "!");
					}
					message(p, "Gave " + CommerceApi.format(amt) + " to " + r.name, true);
				} else {
					message(p, "You don't have that much in the bank", false);
				}
			}
			case "job" -> {
				if (!gov) {
					break;
				}
				Building b = a.building < 0 ? null : c.building(a.building);
				if (Townlife.assign(c, r, b)) {
					c.log(r.name + " was assigned to work as " + r.job.title.toLowerCase());
				} else {
					message(p, "No free place there", false);
				}
			}
			case "arrest" -> {
				if (!gov) {
					break;
				}
				r.crimes++;
				Townlife.arrest(level, c, r, e, "order of the governor");
				if (r.happiness > 30) {
					// arresting someone who did nothing makes everyone afraid
					c.fear += 6;
					c.grievance += 4;
				}
			}
			case "release" -> {
				if (gov && r.status != CitizenRecord.Status.FREE) {
					r.status = CitizenRecord.Status.FREE;
					r.releaseDay = c.day;
					c.log(r.name + " was pardoned by the governor");
					r.happiness = Math.min(100, r.happiness + 10);
				}
			}
			case "exile" -> {
				if (gov) {
					r.leaving = true;
					c.log(r.name + " was banished from " + c.name);
					c.fear += 3;
				}
			}
			case "fine" -> {
				if (gov) {
					long amt = Math.min(CommerceApi.balance(r.account(c)), Math.max(0, a.amount));
					CommerceApi.transfer(r.account(c), c.account(), amt, "Fine");
					r.happiness = Math.max(0, r.happiness - 8);
					c.grievance += 1;
					message(p, r.name + " fined " + CommerceApi.format(amt), true);
				}
			}
			default -> {
			}
		}
		openCitizen(p, c, r.uuid);
	}

	static void fireworks(ServerLevel level, City c) {
		for (int i = 0; i < 8; i++) {
			ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
			int[] palette = {0xFF3B30, 0xFFCC00, 0x34C759, 0x0A84FF, 0xFFFFFF, 0xFF9F0A};
			rocket.set(net.minecraft.core.component.DataComponents.FIREWORKS, new net.minecraft.world.item.component.Fireworks(1 + level.getRandom().nextInt(2),
				java.util.List.of(new net.minecraft.world.item.component.FireworkExplosion(
					net.minecraft.world.item.component.FireworkExplosion.Shape.values()[level.getRandom().nextInt(3)],
					it.unimi.dsi.fastutil.ints.IntList.of(palette[level.getRandom().nextInt(palette.length)], palette[level.getRandom().nextInt(palette.length)]),
					it.unimi.dsi.fastutil.ints.IntList.of(), true, true))));
			BlockPos at = c.center.offset(level.getRandom().nextInt(11) - 5, 1, level.getRandom().nextInt(11) - 5);
			FireworkRocketEntity f = new FireworkRocketEntity(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, rocket);
			level.addFreshEntity(f);
		}
	}
}
