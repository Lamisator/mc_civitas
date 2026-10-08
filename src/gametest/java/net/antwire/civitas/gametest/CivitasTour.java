package net.antwire.civitas.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.antwire.civitas.city.Building;
import net.antwire.civitas.city.BuildingType;
import net.antwire.civitas.city.CitizenRecord;
import net.antwire.civitas.city.City;
import net.antwire.civitas.city.CityManager;
import net.antwire.civitas.city.Storage;
import net.antwire.civitas.client.CitizenScreen;
import net.antwire.civitas.client.GovernScreen;
import net.antwire.civitas.entity.CitizenEntity;
import net.antwire.civitas.network.ServerNet;
import net.antwire.commerce.api.CommerceApi;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/** Founds a town, watches it being built and run, then governs it well - and badly - and photographs everything. */
public class CivitasTour implements FabricClientGameTest {
	private static final String SCENES = System.getProperty("civitas.scenes", "all");
	private static final List<String> FAILS = new ArrayList<>();

	private static void check(String what, boolean ok, String detail) {
		System.out.println("[civitas-test] " + (ok ? "PASS " : "FAIL ") + what + " - " + detail);
		if (!ok) {
			FAILS.add(what);
		}
	}

	private static boolean run(String name) {
		return SCENES.equals("all") || SCENES.contains(name);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		try (TestSingleplayerContext sp = context.worldBuilder()
			.setUseConsistentSettings(true)
			.adjustSettings(s -> {
				s.getNormalPresetList().stream().filter(e -> e.preset() != null && e.preset().is(WorldPresets.NORMAL)).findFirst().ifPresent(s::setWorldType);
				s.setSeed("civitas-tour-1");
			})
			.create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("time set 1500");
			sp.getServer().runCommand("weather clear");
			sp.getServer().runCommand("gamerule advance_weather false");
			sp.getServer().runCommand("gamerule spawn_monsters false");
			sp.getServer().runCommand("gamemode creative @a");
			context.runOnClient(mc -> mc.options.renderDistance().set(12));

			BlockPos site = sp.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				BlockPos spawn = level.getRespawnData().globalPos().pos();
				var plains = level.findClosestBiome3d(b -> b.is(Biomes.PLAINS), spawn, 4000, 32, 64);
				BlockPos base = plains == null ? spawn : plains.getFirst();
				return new BlockPos(base.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX(), base.getZ()), base.getZ());
			});
			this.tp(context, sp, site.getX() + 0.5, site.getY() + 2, site.getZ() + 16.5, 180, 15);
			context.waitTicks(40);

			// ---- found
			String found = sp.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
				StringBuilder why = new StringBuilder();
				City c = CityManager.get().found(level, p, "Neu-Funkenberg", site, Direction.SOUTH, why);
				if (c == null) {
					// try a little further away until the ground is good
					for (int i = 1; i < 12 && c == null; i++) {
						BlockPos alt = site.offset(i * 9, 0, i * 5);
						alt = new BlockPos(alt.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, alt.getX(), alt.getZ()), alt.getZ());
						c = CityManager.get().found(level, p, "Neu-Funkenberg", alt, Direction.SOUTH, why);
					}
				}
				return c == null ? "FAIL " + why : c.id + " " + c.center.getX() + " " + c.center.getY() + " " + c.center.getZ();
			});
			check("found a town", !found.startsWith("FAIL"), found);
			if (found.startsWith("FAIL")) {
				return;
			}
			String[] f = found.split(" ");
			BlockPos center = new BlockPos(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]));
			java.util.UUID cityId = java.util.UUID.fromString(f[0]);

			// ---- builders at work on the town hall
			this.look(context, sp, center.getX() + 9.5, center.getY() + 5, center.getZ() + 14.5, Vec3.atCenterOf(center.offset(0, 2, -6)));
			context.waitTicks(900);
			shot(context, "civitas_building_townhall");
			int hallProgress = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).townHall().progress);
			check("builders lay blocks", hallProgress > 30, hallProgress + " steps done");
			context.waitTicks(1200);
			shot(context, "civitas_building_townhall_2");

			// ---- the rest of the town (completed at once to keep the test short)
			sp.getServer().runCommand("execute positioned " + center.getX() + " " + center.getY() + " " + center.getZ() + " run civitas complete");
			String[] kinds = {"farm", "bakery", "house", "house", "house", "lumber_mill", "mine", "butcher", "tavern", "blacksmith", "sheriff", "barracks", "prison", "bank",
				"factory", "farm", "house"};
			String planned = sp.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				City c = CityManager.get().city(cityId);
				StringBuilder s = new StringBuilder();
				for (String k : kinds) {
					Building b = CityManager.get().plan(level, c, BuildingType.byId(k));
					s.append(k).append(b == null ? "!" : "").append(' ');
				}
				return s.toString();
			});
			check("sites for every building", !planned.contains("!"), planned);
			sp.getServer().runCommand("execute positioned " + center.getX() + " " + center.getY() + " " + center.getZ() + " run civitas complete");
			sp.getServer().runCommand("execute positioned " + center.getX() + " " + center.getY() + " " + center.getZ() + " run civitas immigrate 16");
			context.waitTicks(200);
			int jobs = sp.getServer().computeOnServer(server -> (int) CityManager.get().city(cityId).citizens.values().stream()
				.filter(r -> r.present() && r.job != net.antwire.civitas.city.Job.UNEMPLOYED).count());
			check("jobs filled", jobs >= 14, jobs + " citizens at work");

			// ---- an aerial view of the town
			int radius = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).radius);
			this.look(context, sp, center.getX() - radius * 0.9, center.getY() + radius * 0.8, center.getZ() + radius * 0.9, Vec3.atCenterOf(center));
			context.waitTicks(120);
			shot(context, "civitas_town_aerial");

			// ---- every building can be entered
			java.util.List<String> lint = net.antwire.civitas.city.Access.lintAll();
			check("blueprints are walkable from the door", lint.isEmpty(), lint.isEmpty() ? "all " + BuildingType.values().length + " blueprints" : lint.toString());
			String access = sp.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				City c = CityManager.get().city(cityId);
				StringBuilder bad = new StringBuilder();
				int ways = 0;
				for (Building b : c.buildings) {
					// what the town has only just planned isn't built yet
					String why = b.complete ? net.antwire.civitas.city.Access.rooms(level, b) : null;
					if (why != null) {
						bad.append(b.type.id()).append(": ").append(why).append("; ");
						BlockPos e0 = b.entrance();
						StringBuilder col = new StringBuilder();
						for (int dy = -2; dy <= 6; dy++) {
							col.append(dy).append('=').append(level.getBlockState(e0.above(dy)).getBlock().getDescriptionId().replace("block.minecraft.", "")).append(' ');
						}
						System.out.println("[civitas-test] DIAG " + b.type.id() + " origin " + b.origin.toShortString() + " rot " + b.rotation + " entrance column " + col
							+ " way " + b.approach.subList(0, Math.min(4, b.approach.size())) + " fill " + b.approachFill + " complete " + b.complete + " blocked " + b.blocked);
					}
					if (!b.approach.isEmpty()) {
						ways++;
						for (int i = 1; i < b.approach.size(); i++) {
							if (Math.abs(b.approach.get(i).getY() - b.approach.get(i - 1).getY()) > 1) {
								bad.append(b.type.id()).append(": a step too high at ").append(b.approach.get(i).toShortString()).append("; ");
								break;
							}
						}
					}
				}
				return (bad.isEmpty() ? "OK" : bad.toString()) + " | " + ways + " of " + c.buildings.size() + " with a way to the square";
			});
			check("every building can be entered", access.startsWith("OK"), access);
			if (run("access")) {
				this.accessScenes(context, sp, cityId);
			}
			if (run("barracks")) {
				this.barracksScenes(context, sp, cityId, center);
			}
			if (run("tiers")) {
				this.tierScenes(context, sp, cityId, center);
			}
			if (run("hotfix")) {
				this.hotfixScenes(context, sp, cityId, center);
			}
			if (run("council")) {
				this.councilScenes(context, sp, cityId, center);
			}
			if (run("works")) {
				this.worksScenes(context, sp, cityId, center);
			}

			// ---- a working day
			if (run("work")) {
				context.waitTicks(1600);
				for (BuildingType t : new BuildingType[]{BuildingType.FARM, BuildingType.BAKERY, BuildingType.MINE, BuildingType.BLACKSMITH, BuildingType.LUMBER_MILL,
					BuildingType.TOWN_HALL}) {
					Vec3[] cam = sp.getServer().computeOnServer(server -> {
						City c = CityManager.get().city(cityId);
						Building b = c.firstComplete(t);
						if (b == null) {
							return null;
						}
						CitizenEntity worker = null;
						for (java.util.UUID u : b.workers) {
							worker = CityManager.get().entity(u);
							if (worker != null) {
								break;
							}
						}
						Direction front = b.rot().rotate(Direction.SOUTH);
						boolean outdoor = t == BuildingType.FARM || t == BuildingType.LUMBER_MILL;
						boolean underground = worker != null && worker.getY() < b.entrance().getY() - 2;
						Vec3 target = worker != null && !underground ? worker.position().add(0, 1.2, 0)
							: Vec3.atCenterOf(b.mark("workblock") != null ? b.mark("workblock") : b.center());
						if (t == BuildingType.MINE && b.mark("shaft") != null) {
							target = Vec3.atCenterOf(b.mark("shaft")).add(0, 0.5, 0);
						}
						Vec3 from;
						BlockPos door = b.mark("door");
						if (!outdoor && door != null) {
							// just inside the door, looking in
							from = Vec3.atBottomCenterOf(door.relative(front.getOpposite())).add(Vec3.atLowerCornerOf(front.getClockWise().getUnitVec3i()).scale(0.6));
						} else {
							from = target.add(Vec3.atLowerCornerOf(front.getUnitVec3i()).scale(8)).add(0, 1.5, 0);
						}
						return new Vec3[]{from, target};
					});
					if (cam != null) {
						this.look(context, sp, cam[0].x, cam[0].y, cam[0].z, cam[1]);
						context.waitTicks(30);
						shot(context, "civitas_work_" + t.id());
					}
				}
				String economy = sp.getServer().computeOnServer(server -> {
					ServerLevel level = server.overworld();
					City c = CityManager.get().city(cityId);
					Building farm = c.firstComplete(BuildingType.FARM);
					Building mine = c.firstComplete(BuildingType.MINE);
					Building bakery = c.firstComplete(BuildingType.BAKERY);
					int bread = bakery == null || Storage.shop(level, bakery) == null ? -1 : Storage.shop(level, bakery).stock(0);
					return (farm == null ? -1 : farm.producedToday) + " " + (mine == null ? -1 : mine.digIndex) + " " + bread + " "
						+ c.stockpile.getOrDefault("minecraft:cobblestone", 0);
				});
				String[] e = economy.split(" ");
				check("mine digs", Integer.parseInt(e[1]) > 3, "dig step " + e[1] + ", cobblestone in stock " + e[3]);
				System.out.println("[civitas-test] farm output " + e[0] + " cents, bread on the counter " + e[2]);
			}

			// ---- good government: fair wages, low taxes, a festival
			sp.getServer().runOnServer(server -> {
				City c = CityManager.get().city(cityId);
				c.policies.wageLevel = 130;
				c.policies.incomeTax = 5;
				c.policies.rent = 1;
				c.policies.rations = true;
				CommerceApi.mint(c.account(), 500000, "test grant");
			});
			for (int d = 0; d < 3; d++) {
				this.feedEveryone(sp, cityId);
				sp.getServer().runCommand("execute positioned " + center.getX() + " " + center.getY() + " " + center.getZ() + " run civitas day");
			}
			double good = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).averageHappiness());
			check("good government makes people happy", good > 55, String.format(Locale.ROOT, "%.0f%%", good));
			this.showCrowd(context, sp, cityId, center, "civitas_prosperous");

			// ---- exploitation: no pay, crushing taxes, sixteen hours, savings confiscated
			sp.getServer().runOnServer(server -> {
				City c = CityManager.get().city(cityId);
				c.policies.wageLevel = 0;
				c.policies.incomeTax = 90;
				c.policies.rent = 15;
				c.policies.workHours = 16;
				c.policies.rations = false;
				c.policies.immigration = false;
				for (CitizenRecord r : c.citizens.values()) {
					if (r.present()) {
						long bal = CommerceApi.balance(r.account(c));
						CommerceApi.transfer(r.account(c), c.account(), bal, "Confiscated");
						r.leaving = false;
					}
				}
				c.grievance += 30;
			});
			int[] tiersBefore = this.tiers(sp, cityId);
			for (int d = 0; d < 7; d++) {
				sp.getServer().runCommand("execute positioned " + center.getX() + " " + center.getY() + " " + center.getZ() + " run civitas day");
				sp.getServer().runOnServer(server -> {
					for (CitizenRecord r : CityManager.get().city(cityId).citizens.values()) {
						r.leaving = false;
						r.hungryDays = Math.min(r.hungryDays, 1);
						if (r.status == CitizenRecord.Status.LEFT) {
							r.status = CitizenRecord.Status.FREE;
						}
					}
				});
			}
			int[] tiersAfter = this.tiers(sp, cityId);
			double bad = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).averageHappiness());
			boolean strike = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).strike);
			check("exploitation makes them miserable", bad < 25, String.format(Locale.ROOT, "%.0f%%", bad));
			check("exploitation makes them poor", tiersAfter[3] > tiersBefore[3] && tiersAfter[3] >= tiersAfter[0] + tiersAfter[1],
				"destitute " + tiersBefore[3] + " -> " + tiersAfter[3]);
			check("they go on strike", strike, String.valueOf(strike));
			// back to noon: the strikers gather in the square
			sp.getServer().runCommand("time set 4000");
			this.showCrowd(context, sp, cityId, center, "civitas_ragged");
			context.waitTicks(300);
			this.look(context, sp, center.getX() + 6.5, center.getY() + 3, center.getZ() + 7.5, Vec3.atCenterOf(center).add(0, 1, 0));
			context.waitTicks(20);
			shot(context, "civitas_protest");

			// ---- the ledger, after good days and bad
			sp.getServer().runOnServer(server -> ServerNet.openGovern(server.getPlayerList().getPlayers().getFirst(), CityManager.get().city(cityId)));
			context.waitFor(mc -> mc.gui.screen() instanceof GovernScreen, 200);
			context.waitTicks(10);
			shot(context, "civitas_ledger_overview");
			for (int tab = 1; tab <= 4; tab++) {
				int k = tab;
				context.runOnClient(mc -> ((GovernScreen) mc.gui.screen()).setTab(k));
				context.waitTicks(10);
				shot(context, "civitas_ledger_" + GovernScreen.TABS[k].toLowerCase(Locale.ROOT));
			}
			context.runOnClient(mc -> mc.gui.setScreen(null));

			// a destitute citizen's card
			sp.getServer().runOnServer(server -> {
				City c = CityManager.get().city(cityId);
				CitizenRecord poor = c.citizens.values().stream().filter(CitizenRecord::present).findFirst().orElse(null);
				if (poor != null) {
					ServerNet.openCitizen(server.getPlayerList().getPlayers().getFirst(), c, poor.uuid);
				}
			});
			context.waitFor(mc -> mc.gui.screen() instanceof CitizenScreen, 200);
			context.waitTicks(10);
			shot(context, "civitas_citizen_card");
			context.runOnClient(mc -> mc.gui.setScreen(null));
			// the town and the bank survive a save and reload
			String reload = sp.getServer().computeOnServer(server -> {
				City c = CityManager.get().city(cityId);
				String before = c.citizens.size() + "/" + c.buildings.size() + "/" + CommerceApi.balance(c.account()) + "/" + c.policies.workHours;
				CityManager.get().save();
				net.antwire.commerce.CommerceData.get().save();
				CityManager.load(server);
				net.antwire.commerce.CommerceData.load(server);
				City d = CityManager.get().city(cityId);
				String after = d == null ? "gone" : d.citizens.size() + "/" + d.buildings.size() + "/" + CommerceApi.balance(d.account()) + "/" + d.policies.workHours;
				return before + " " + after;
			});
			String[] rl = reload.split(" ");
			check("saved and reloaded", rl[0].equals(rl[1]), reload);
			System.out.println("[civitas-test] RESULT " + (FAILS.isEmpty() ? "PASS" : "FAIL " + FAILS));
		}
	}

	/** A house facing a bank of earth, and a way that a wall goes up across. */
	private void accessScenes(ClientGameTestContext context, TestSingleplayerContext sp, java.util.UUID cityId) {
		// a site, then a three-high bank of earth piled up two steps in front of its door
		String hill = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			c.radius = Math.max(c.radius, 70);
			Building probe = net.antwire.civitas.city.Planner.find(level, c, BuildingType.HOUSE);
			if (probe == null) {
				return "FAIL no site: " + net.antwire.civitas.city.Planner.lastReason;
			}
			Direction front = probe.rot().rotate(Direction.SOUTH);
			Direction side = front.getClockWise();
			BlockPos e = probe.entrance();
			for (int f = 2; f <= 5; f++) {
				for (int l = -4; l <= 4; l++) {
					BlockPos col = e.relative(front, f).relative(side, l);
					for (int h = 0; h < 3; h++) {
						level.setBlock(col.above(h), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 3);
					}
				}
			}
			BlockPos centre = probe.center();
			Building b = net.antwire.civitas.city.Planner.fit(level, c, BuildingType.HOUSE, centre.getX(), centre.getZ(), front, 3);
			if (b == null) {
				return "FAIL refit: " + net.antwire.civitas.city.Planner.lastReason;
			}
			b.id = c.nextBuildingId++;
			c.buildings.add(b);
			b.steps = net.antwire.civitas.city.Construction.steps(b).size();
			int climb = 0;
			for (BlockPos p : b.approach) {
				climb = Math.max(climb, p.getY() - b.origin.getY());
			}
			return b.id + " " + b.approach.size() + " " + b.approachFill.size() + " " + climb + " " + e.getX() + " " + e.getY() + " " + e.getZ() + " " + front.get2DDataValue();
		});
		check("a site facing a bank of earth gets a way", !hill.startsWith("FAIL"), hill);
		if (hill.startsWith("FAIL")) {
			return;
		}
		String[] h = hill.split(" ");
		int houseId = Integer.parseInt(h[0]);
		BlockPos e = new BlockPos(Integer.parseInt(h[4]), Integer.parseInt(h[5]), Integer.parseInt(h[6]));
		Direction front = Direction.from2DDataValue(Integer.parseInt(h[7]));
		sp.getServer().runCommand("execute positioned " + e.getX() + " " + e.getY() + " " + e.getZ() + " run civitas complete");
		String walk = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			Building b = CityManager.get().city(cityId).building(houseId);
			String rooms = net.antwire.civitas.city.Access.rooms(level, b);
			// walk the way: every step room to stand, none higher than one block
			for (int i = 0; i < b.approach.size(); i++) {
				BlockPos g = b.approach.get(i);
				if (!net.antwire.civitas.entity.ai.Walker.fits(level, g.above())) {
					return "can't stand at " + g.above().toShortString() + " (" + level.getBlockState(g.above()).getBlock().getDescriptionId() + ")";
				}
				if (i > 0 && Math.abs(g.getY() - b.approach.get(i - 1).getY()) > 1) {
					return "step too high at " + g.toShortString();
				}
			}
			return rooms == null ? "OK" : rooms;
		});
		check("the house behind the bank can be walked into", walk.equals("OK"), walk + ", way " + h[1] + " steps, " + h[2] + " banked up, climbs " + h[3]);
		Vec3 eye = Vec3.atCenterOf(e.relative(front, 12)).add(Vec3.atLowerCornerOf(front.getClockWise().getUnitVec3i()).scale(7)).add(0, 10, 0);
		this.look(context, sp, eye.x, eye.y, eye.z, Vec3.atCenterOf(e.relative(front, 2)));
		context.waitTicks(30);
		shot(context, "civitas_access_bank");

		// a wall goes up across the bakery's way (a player's stone bricks), and earth is dumped in its doorway
		String reroute = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			Building b = c.firstComplete(BuildingType.BAKERY);
			if (b == null || b.approach.size() < 8) {
				return "FAIL no bakery way";
			}
			BlockPos door = b.entrance();
			level.setBlock(door, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(door.above(), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 3);
			BlockPos cut = b.approach.get(b.approach.size() / 2);
			java.util.Set<Long> walled = new java.util.HashSet<>();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos col = cut.offset(dx, 0, dz);
					walled.add(BlockPos.asLong(col.getX(), 0, col.getZ()));
					for (int y = 1; y <= 3; y++) {
						level.setBlock(col.above(y), net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState(), 3);
					}
				}
			}
			var a = net.antwire.civitas.city.Townlife.checkAccess(level, c, b);
			boolean around = b.approach.stream().noneMatch(p -> walled.contains(BlockPos.asLong(p.getX(), 0, p.getZ())));
			return a.cleared() + " " + a.rerouted() + " " + around + " " + (a.blocked() == null ? "-" : a.blocked().replace(' ', '_'));
		});
		String[] r = reroute.split(" ");
		check("earth in the doorway is cleared", !reroute.startsWith("FAIL") && Integer.parseInt(r[0]) >= 2, reroute);
		check("a wall across the way is walked around", !reroute.startsWith("FAIL") && r[1].equals("true") && r[2].equals("true") && r[3].equals("-"), reroute);
	}

	/** Name signs, damage found and repaired, the town wall at every tier and as the town grows, paved and lit streets. */
	private void worksScenes(ClientGameTestContext context, TestSingleplayerContext sp, java.util.UUID cityId, BlockPos center) {
		String at = "execute positioned " + center.getX() + " " + center.getY() + " " + center.getZ() + " run ";
		sp.getServer().runOnServer(server -> CommerceApi.mint(CityManager.get().city(cityId).account(), 10_000_000, "test grant"));
		// ---- every building has its name sign by the door
		String signs = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			int n = 0;
			int with = 0;
			StringBuilder none = new StringBuilder();
			for (Building b : c.buildings) {
				if (!b.complete) {
					continue;
				}
				n++;
				if (b.sign == null) {
					net.antwire.civitas.city.Works.placeSign(level, c, b);
				}
				if (b.sign != null && level.getBlockState(b.sign).getBlock() instanceof net.minecraft.world.level.block.SignBlock) {
					with++;
				} else {
					none.append(b.type.id()).append(b.sign == null ? "(no spot)" : "(" + level.getBlockState(b.sign).getBlock().getDescriptionId() + ")").append(" ");
				}
			}
			return with + " of " + n + (none.isEmpty() ? "" : " - none on " + none);
		});
		check("every building has a name sign", signs.startsWith(signs.split(" ")[2] + " "), signs);
		int houseId = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).firstComplete(BuildingType.HOUSE).id);
		// ---- no dark corners: sealed attics and cavities carry hidden lights, also in buildings from before they existed
		String retro = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			Building b = c.building(houseId);
			int removed = 0;
			var box = b.bounds();
			for (BlockPos q : BlockPos.betweenClosed((int) box.minX, (int) box.minY, (int) box.minZ, (int) box.maxX - 1, (int) box.maxY - 1, (int) box.maxZ - 1)) {
				if (level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.LIGHT)) {
					level.setBlock(q, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
					removed++;
				}
			}
			net.antwire.civitas.city.Works.inspect(level, c, b, false);
			int back = 0;
			for (BlockPos q : BlockPos.betweenClosed((int) box.minX, (int) box.minY, (int) box.minZ, (int) box.maxX - 1, (int) box.maxY - 1, (int) box.maxZ - 1)) {
				if (level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.LIGHT)) {
					back++;
				}
			}
			return removed + " " + back;
		});
		String[] rt = retro.split(" ");
		check("hidden lights are put back into older buildings", Integer.parseInt(rt[0]) > 0 && rt[0].equals(rt[1]), retro + " (taken out, back after inspection)");
		context.waitTicks(40);
		String dark = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			StringBuilder out = new StringBuilder();
			int spots = 0;
			for (Building b : c.buildings) {
				if (!b.complete) {
					continue;
				}
				var box = b.bounds();
				int here = 0;
				BlockPos first = null;
				for (BlockPos q : BlockPos.betweenClosed((int) box.minX, (int) box.minY + 1, (int) box.minZ, (int) box.maxX - 1, (int) box.maxY - 1, (int) box.maxZ - 1)) {
					if (!level.getBlockState(q).isAir() || !level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)) {
						continue;
					}
					if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, q) == 0 && level.getBrightness(net.minecraft.world.level.LightLayer.SKY, q) == 0) {
						here++;
						if (first == null) {
							first = q.immutable();
						}
					}
				}
				if (here > 0) {
					spots += here;
					out.append(b.type.id()).append(" ").append(b.tier).append(": ").append(here).append(" at ").append(first.toShortString()).append("; ");
				}
			}
			return spots == 0 ? "OK" : spots + " dark spots: " + out;
		});
		check("no dark enclosed spot in any building", dark.equals("OK"), dark);
		Vec3[] signCam = sp.getServer().computeOnServer(server -> {
			Building b = CityManager.get().city(cityId).building(houseId);
			if (b.sign == null) {
				return null;
			}
			Direction out = b.rot().rotate(Direction.SOUTH);
			Vec3 s = Vec3.atCenterOf(b.sign);
			return new Vec3[]{s.add(Vec3.atLowerCornerOf(out.getUnitVec3i()).scale(3.5)).add(0, -1.2, 0), s};
		});
		if (signCam != null) {
			this.look(context, sp, signCam[0].x, signCam[0].y, signCam[0].z, signCam[1]);
			context.waitTicks(30);
			shot(context, "civitas_works_sign");
		}
		// ---- damage: a blast against the house is found and repaired
		String blast = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			Building b = c.building(houseId);
			Vec3 e = Vec3.atCenterOf(b.entrance());
			level.explode(null, e.x, e.y + 1, e.z, 3.5F, net.minecraft.world.level.Level.ExplosionInteraction.TNT);
			int n = net.antwire.civitas.city.Works.inspect(level, c, b, false);
			var p = c.project(net.antwire.civitas.city.Project.REPAIR, "b:" + b.id);
			return n + " " + b.condition + " " + (p != null);
		});
		String[] bl = blast.split(" ");
		check("a blast against the house is found", Integer.parseInt(bl[0]) > 5 && bl[2].equals("true"), blast + " (blocks to put back, condition %, repair queued)");
		this.front(context, sp, cityId, houseId, 10, 4, "civitas_works_damaged");
		// builders mend it (before anything else); the rest is finished at once to keep the test short
		for (int k = 0; k < 6; k++) {
			context.waitTicks(400);
			boolean done = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).project(net.antwire.civitas.city.Project.REPAIR, "b:" + houseId) == null);
			if (done) {
				break;
			}
		}
		String mending = sp.getServer().computeOnServer(server -> {
			City c = CityManager.get().city(cityId);
			var p = c.project(net.antwire.civitas.city.Project.REPAIR, "b:" + houseId);
			long builders = c.citizens.values().stream().filter(r -> r.present() && r.job == net.antwire.civitas.city.Job.BUILDER).count();
			return (p == null ? "done" : p.progress + "/" + p.jobs.size()) + " with " + builders + " builders";
		});
		int jobsLeft = mending.startsWith("done") ? 0 : Integer.parseInt(mending.substring(mending.indexOf('/') + 1, mending.indexOf(' ')));
		check("builders repair the damage", jobsLeft < Integer.parseInt(bl[0]) / 2, mending + " (jobs left of " + bl[0] + ")");
		sp.getServer().runCommand(at + "civitas complete");
		String after = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			Building b = c.building(houseId);
			net.antwire.civitas.city.Works.inspect(level, c, b, false);
			var left = c.project(net.antwire.civitas.city.Project.REPAIR, "b:" + b.id);
			StringBuilder what = new StringBuilder();
			if (left != null) {
				for (var j : left.jobs) {
					what.append(" ").append(j.k).append(" ").append(j.p.toShortString()).append(" ").append(j.s).append(" now ")
						.append(level.getBlockState(j.p).getBlock().getDescriptionId()).append(" below ").append(level.getBlockState(j.p.below()).getBlock().getDescriptionId());
				}
			}
			return b.missing + " " + b.condition + "%" + what;
		});
		check("the house stands as planned again", after.startsWith("0 "), after);
		this.front(context, sp, cityId, houseId, 10, 4, "civitas_works_repaired");
		// ---- the wall at each tier
		for (int tier = 1; tier <= 3; tier++) {
			int to = tier;
			String wall = sp.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				City c = CityManager.get().city(cityId);
				int[] rect = c.wallRect == null ? net.antwire.civitas.city.Walls.needed(c) : c.wallRect;
				var p = net.antwire.civitas.city.Walls.start(level, c, to, rect);
				if (p == null) {
					return "FAIL nothing to build";
				}
				net.antwire.civitas.city.Works.completeAll(level, c);
				// one round of the inspectors, as in the running town
				net.antwire.civitas.city.Walls.inspect(level, c, true);
				net.antwire.civitas.city.Works.completeAll(level, c);
				List<BlockPos> gates = net.antwire.civitas.city.Walls.openings(level, c);
				int blocked = 0;
				StringBuilder in = new StringBuilder();
				for (BlockPos g : gates) {
					if (!level.getBlockState(g).isAir()) {
						blocked++;
						if (blocked <= 4) {
							in.append(g.toShortString()).append("=").append(level.getBlockState(g).getBlock().getDescriptionId().replace("block.minecraft.", "")).append(";");
						}
					}
				}
				int missing = 0;
				for (String cell : c.wall) {
					String[] f = cell.split(" ", 4);
					BlockPos q = new BlockPos(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]));
					if (level.getBlockState(q).isAir()) {
						missing++;
					}
				}
				return c.wallTier + " " + c.wall.size() + " " + gates.size() / 3 + " " + blocked + " " + missing + " " + in;
			});
			String[] w = wall.split(" ");
			check("wall tier " + to + " built with gates", !wall.startsWith("FAIL") && w[0].equals(String.valueOf(to)) && Integer.parseInt(w[2]) >= 12
				&& w[3].equals("0") && w[4].equals("0"), wall + " (tier, blocks, gate columns, gate blocks not clear, wall blocks missing)");
			int[] r = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).wallRect);
			double cx = (r[0] + r[2]) / 2.0;
			double cz = (r[1] + r[3]) / 2.0;
			double span = Math.max(r[2] - r[0], r[3] - r[1]);
			this.look(context, sp, r[0] - span * 0.25, center.getY() + span * 0.45, r[3] + span * 0.25, new Vec3(cx, center.getY(), cz));
			context.waitTicks(100);
			shot(context, "civitas_works_wall_tier" + to);
			// a gate up close
			Vec3[] gate = sp.getServer().computeOnServer(server -> {
				City c = CityManager.get().city(cityId);
				List<BlockPos> gs = net.antwire.civitas.city.Walls.openings(server.overworld(), c);
				BlockPos best = null;
				for (BlockPos g : gs) {
					if (g.getZ() == c.wallRect[3] && (best == null || Math.abs(g.getX() - c.center.getX()) < Math.abs(best.getX() - c.center.getX()))) {
						best = g;
					}
				}
				return best == null ? null : new Vec3[]{Vec3.atCenterOf(best).add(4, 2, 9), Vec3.atCenterOf(best).add(0, 1, 0)};
			});
			if (gate != null) {
				this.look(context, sp, gate[0].x, gate[0].y, gate[0].z, gate[1]);
				context.waitTicks(40);
				shot(context, "civitas_works_gate_tier" + to);
			}
		}
		// every building can still be entered, and every way still reaches the square
		String inside = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			StringBuilder bad = new StringBuilder();
			for (Building b : c.buildings) {
				var a = b.complete ? net.antwire.civitas.city.Access.audit(level, c, b) : null;
				if (a != null && a.blocked() != null) {
					bad.append(b.type.id()).append(": ").append(a.blocked()).append("; ");
				}
			}
			return bad.isEmpty() ? "OK" : bad.toString();
		});
		check("the wall keeps every way open", inside.equals("OK"), inside);
		// ---- the town outgrows its wall: the wall moves out
		String grow = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			int[] before = c.wallRect.clone();
			c.radius = Math.max(c.radius, (Math.max(before[2] - before[0], before[3] - before[1]) / 2) + 40);
			Building b = null;
			for (int k = 0; k < 6 && b == null; k++) {
				Building cand = CityManager.get().plan(level, c, BuildingType.HOUSE);
				if (cand != null && !net.antwire.civitas.city.Walls.inside(before, net.antwire.civitas.city.Walls.needed(c), 0)) {
					b = cand;
				}
			}
			if (b == null) {
				return "FAIL no house outside the wall";
			}
			net.antwire.civitas.city.Walls.daily(level, c);
			var p = c.project(net.antwire.civitas.city.Project.WALL, "wall");
			if (p == null) {
				return "FAIL the wall did not move";
			}
			return "OK";
		});
		check("the wall moves out when the town grows", grow.equals("OK"), grow);
		sp.getServer().runCommand(at + "civitas complete");
		String grown = sp.getServer().computeOnServer(server -> {
			City c = CityManager.get().city(cityId);
			return net.antwire.civitas.city.Walls.inside(c.wallRect, net.antwire.civitas.city.Walls.needed(c), 0) ? "OK" : "FAIL plots outside";
		});
		check("the moved wall goes round every plot", grown.equals("OK"), grown);
		// ---- wall damage is repaired
		String wallFix = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			int broken = 0;
			for (int i = 0; i < c.wall.size(); i += 37) {
				String[] f = c.wall.get(i).split(" ", 4);
				level.setBlock(new BlockPos(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2])), net.minecraft.world.level.block.Blocks.AIR
					.defaultBlockState(), 3);
				broken++;
			}
			net.antwire.civitas.city.Walls.inspect(level, c, false);
			var p = c.project(net.antwire.civitas.city.Project.REPAIR, "wall");
			int jobs = p == null ? 0 : p.jobs.size();
			net.antwire.civitas.city.Works.completeAll(level, c);
			net.antwire.civitas.city.Walls.inspect(level, c, false);
			return broken + " " + jobs + " " + (c.project(net.antwire.civitas.city.Project.REPAIR, "wall") == null);
		});
		String[] wf = wallFix.split(" ");
		check("broken wall is found and repaired", Integer.parseInt(wf[1]) >= Integer.parseInt(wf[0]) && wf[2].equals("true"), wallFix + " (broken, jobs, fixed)");
		// ---- streets: paved, then lit
		String streets = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			String a = net.antwire.civitas.city.Streets.order(level, c, 1);
			net.antwire.civitas.city.Works.completeAll(level, c);
			int earth = 0;
			int paved = 0;
			for (Building b : c.buildings) {
				if (!b.complete) {
					continue;
				}
				for (BlockPos g : b.approach) {
					if (net.antwire.civitas.city.Access.owned(c, g)) {
						continue; // a building's own ground: its business, not the street's
					}
					var s = level.getBlockState(g);
					if (s.is(net.minecraft.world.level.block.Blocks.STONE_BRICKS)) {
						paved++;
					} else if (s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || s.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)) {
						earth++;
					}
				}
			}
			String l = net.antwire.civitas.city.Streets.order(level, c, 2);
			net.antwire.civitas.city.Works.completeAll(level, c);
			int lit = 0;
			for (BlockPos g : c.lamps) {
				if (level.getBlockState(g.above(4)).is(net.minecraft.world.level.block.Blocks.LANTERN)) {
					lit++;
				}
			}
			return paved + " " + earth + " " + c.lamps.size() + " " + lit + " | " + a + " | " + l;
		});
		String[] st = streets.split(" ");
		check("the ways are paved", Integer.parseInt(st[0]) > 50 && Integer.parseInt(st[1]) == 0, streets + " (paved, still earth)");
		check("street lamps stand along the ways", Integer.parseInt(st[2]) >= 5 && st[2].equals(st[3]), streets + " (lamps, lit)");
		String still = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			StringBuilder bad = new StringBuilder();
			for (Building b : c.buildings) {
				String why = b.complete ? net.antwire.civitas.city.Access.rooms(level, b) : null;
				var a = b.complete ? net.antwire.civitas.city.Access.audit(level, c, b) : null;
				if (why != null || a != null && a.blocked() != null) {
					bad.append(b.type.id()).append("; ");
				}
			}
			return bad.isEmpty() ? "OK" : bad.toString();
		});
		check("paving and lamps keep every way walkable", still.equals("OK"), still);
		String falseAlarms = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			StringBuilder out = new StringBuilder();
			for (Building b : c.buildings) {
				if (b.complete && !b.upgrading() && net.antwire.civitas.city.Works.inspect(level, c, b, false) > 0) {
					var p = c.project(net.antwire.civitas.city.Project.REPAIR, "b:" + b.id);
					out.append(b.type.id()).append("#").append(b.id).append(":");
					for (var j : p.jobs) {
						out.append(" ").append(j.s).append(" now ").append(level.getBlockState(j.p).getBlock().getDescriptionId().replace("block.minecraft.", ""))
							.append(" below ").append(level.getBlockState(j.p.below()).getBlock().getDescriptionId().replace("block.minecraft.", ""));
					}
					out.append("; ");
				}
			}
			return out.isEmpty() ? "OK" : out.toString();
		});
		check("no damage reported where nothing was damaged", falseAlarms.equals("OK"), falseAlarms);
		sp.getServer().runCommand("time set 13500");
		Vec3[] lampCam = sp.getServer().computeOnServer(server -> {
			City c = CityManager.get().city(cityId);
			if (c.lamps.isEmpty()) {
				return null;
			}
			BlockPos l = c.lamps.get(c.lamps.size() / 2);
			return new Vec3[]{Vec3.atCenterOf(l).add(9, 6, 9), Vec3.atCenterOf(l).add(0, 1, 0)};
		});
		if (lampCam != null) {
			this.look(context, sp, lampCam[0].x, lampCam[0].y, lampCam[0].z, lampCam[1]);
			context.waitTicks(60);
			shot(context, "civitas_works_streets_dusk");
		}
		int[] r = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).wallRect);
		double span = Math.max(r[2] - r[0], r[3] - r[1]);
		this.look(context, sp, r[0] - span * 0.2, center.getY() + span * 0.5, r[3] + span * 0.2, new Vec3((r[0] + r[2]) / 2.0, center.getY(), (r[1] + r[3]) / 2.0));
		context.waitTicks(120);
		shot(context, "civitas_works_town_night");
		sp.getServer().runCommand("time set 6000");
		// the ledger's Works tab
		sp.getServer().runOnServer(server -> {
			var p = server.getPlayerList().getPlayers().getFirst();
			net.antwire.civitas.network.ServerNet.openGovern(p, CityManager.get().city(cityId));
		});
		context.waitFor(mc -> mc.gui.screen() instanceof GovernScreen, 200);
		context.runOnClient(mc -> ((GovernScreen) mc.gui.screen()).setTab(6));
		context.waitTicks(60);
		shot(context, "civitas_works_ledger");
		context.runOnClient(mc -> mc.gui.setScreen(null));
	}

	/** Photographs a building from the front, a little above. */
	private void front(ClientGameTestContext context, TestSingleplayerContext sp, java.util.UUID cityId, int id, double dist, double up, String name) {
		Vec3[] cam = sp.getServer().computeOnServer(server -> {
			Building b = CityManager.get().city(cityId).building(id);
			Direction front = b.rot().rotate(Direction.SOUTH);
			Vec3 c = Vec3.atCenterOf(b.center());
			Vec3 e = Vec3.atCenterOf(b.entrance());
			Vec3 from = e.add(Vec3.atLowerCornerOf(front.getUnitVec3i()).scale(dist)).add(Vec3.atLowerCornerOf(front.getClockWise().getUnitVec3i()).scale(dist * 0.45))
				.add(0, up, 0);
			return new Vec3[]{from, c.add(0, 2, 0)};
		});
		this.look(context, sp, cam[0].x, cam[0].y, cam[0].z, cam[1]);
		context.waitTicks(40);
		shot(context, name);
	}

	/** An old house raised to the new plans, then the town hall and every building to tier 2 and tier 3. */
	private void tierScenes(ClientGameTestContext context, TestSingleplayerContext sp, java.util.UUID cityId, BlockPos center) {
		String at = "execute positioned " + center.getX() + " " + center.getY() + " " + center.getZ() + " run ";
		// a house built to the plans from before tiers
		String legacy = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			c.radius = Math.max(c.radius, 90);
			Building b = net.antwire.civitas.city.Planner.find(level, c, BuildingType.HOUSE);
			if (b == null) {
				return "FAIL no site: " + net.antwire.civitas.city.Planner.lastReason;
			}
			BlockPos e = b.entrance();
			BlockPos rel = net.antwire.civitas.city.LegacyBlueprints.of(BuildingType.HOUSE).mark("entrance");
			b.origin = e.subtract(rel.rotate(b.rot())).atY(b.origin.getY());
			b.layout = 0;
			b.tier = 1;
			b.id = c.nextBuildingId++;
			c.buildings.add(b);
			b.steps = net.antwire.civitas.city.Construction.steps(b).size();
			CommerceApi.mint(c.account(), 5_000_000, "test grant");
			return String.valueOf(b.id);
		});
		check("an old-style house to upgrade", !legacy.startsWith("FAIL"), legacy);
		sp.getServer().runCommand(at + "civitas complete");
		sp.getServer().runCommand(at + "civitas immigrate 6");
		int houseId = legacy.startsWith("FAIL") ? -1 : Integer.parseInt(legacy);
		if (houseId > 0) {
			this.front(context, sp, cityId, houseId, 14, 7, "civitas_tier1_house");
		}
		int hallId = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).townHall().id);
		this.front(context, sp, cityId, hallId, 22, 10, "civitas_tier1_hall");
		for (int tier = 2; tier <= 3; tier++) {
			int to = tier;
			String hall = sp.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				City c = CityManager.get().city(cityId);
				Building h = c.townHall();
				String why = net.antwire.civitas.city.Townlife.whyNotUpgrade(level, c, h);
				return why == null ? (net.antwire.civitas.city.Townlife.upgrade(level, c, h) ? "OK" : "refused") : why;
			});
			check("town hall to tier " + to, hall.equals("OK"), hall);
			sp.getServer().runCommand(at + "civitas complete");
			String all = sp.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				City c = CityManager.get().city(cityId);
				int n = 0;
				StringBuilder no = new StringBuilder();
				for (Building b : java.util.List.copyOf(c.buildings)) {
					if (b.type == BuildingType.TOWN_HALL || b.tier >= to) {
						continue;
					}
					String why = net.antwire.civitas.city.Townlife.whyNotUpgrade(level, c, b);
					if (why == null && net.antwire.civitas.city.Townlife.upgrade(level, c, b)) {
						n++;
					} else {
						no.append(b.type.id()).append(": ").append(why).append("; ");
					}
				}
				return n + " " + no;
			});
			sp.getServer().runCommand(at + "civitas complete");
			String result = sp.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				City c = CityManager.get().city(cityId);
				int at2 = 0;
				StringBuilder bad = new StringBuilder();
				for (Building b : c.buildings) {
					if (b.tier == to) {
						at2++;
					}
					String why = b.complete ? net.antwire.civitas.city.Access.rooms(level, b) : null;
					if (why != null) {
						bad.append(b.type.id()).append(" ").append(b.tier).append(": ").append(why).append("; ");
					}
				}
				return at2 + " of " + c.buildings.size() + " at tier " + to + (bad.isEmpty() ? " | all enterable" : " | " + bad);
			});
			check("buildings raised to tier " + to, !all.startsWith("0 "), all);
			check("tier " + to + " buildings can be entered", result.endsWith("all enterable"), result);
			if (houseId > 0) {
				this.front(context, sp, cityId, houseId, 16, 8, "civitas_tier" + to + "_house");
			}
			this.front(context, sp, cityId, hallId, 24, 12, "civitas_tier" + to + "_hall");
		}
		// inside a grand house: the bedrooms upstairs, and the cellar
		if (houseId > 0) {
			Vec3[] up = sp.getServer().computeOnServer(server -> {
				Building b = CityManager.get().city(cityId).building(houseId);
				BlockPos bed = b.marks("bed").getLast();
				Direction front = b.rot().rotate(Direction.SOUTH);
				BlockPos door = b.mark("door");
				Vec3 eye = Vec3.atBottomCenterOf(door.relative(front.getOpposite(), 2)).add(0, 8, 0);
				return new Vec3[]{eye, Vec3.atCenterOf(bed)};
			});
			this.look(context, sp, up[0].x, up[0].y, up[0].z, up[1]);
			context.waitTicks(30);
			shot(context, "civitas_tier3_house_upstairs");
		}
		Vec3 aerial = Vec3.atCenterOf(center);
		int radius = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).radius);
		this.look(context, sp, aerial.x - radius * 0.8, aerial.y + radius * 0.7, aerial.z + radius * 0.8, aerial);
		context.waitTicks(120);
		shot(context, "civitas_tier3_town");
	}

	/** Self-government: the extortionist squeezes and pays the governor, the growth council loosens; keep-loaded switch. */
	private void councilScenes(ClientGameTestContext context, TestSingleplayerContext sp, java.util.UUID cityId, BlockPos center) {
		String at = "execute positioned " + center.getX() + " " + center.getY() + " " + center.getZ() + " run ";
		String before = sp.getServer().computeOnServer(server -> {
			City c = CityManager.get().city(cityId);
			CommerceApi.mint(c.account(), 2_000_000, "test grant");
			return c.policies.incomeTax + " " + c.policies.wageLevel + " " + CommerceApi.balance(CommerceApi.playerAccount(c.governor));
		});
		sp.getServer().runCommand(at + "civitas council extortion");
		for (int d = 0; d < 4; d++) {
			this.feedEveryone(sp, cityId);
			sp.getServer().runCommand(at + "civitas day");
		}
		String squeezed = sp.getServer().computeOnServer(server -> {
			City c = CityManager.get().city(cityId);
			return c.policies.incomeTax + " " + c.policies.wageLevel + " " + CommerceApi.balance(CommerceApi.playerAccount(c.governor)) + " " + c.policies.forcedLabor
				+ " | " + String.join(" / ", c.log.subList(0, 4));
		});
		String[] b0 = before.split(" ");
		String[] s1 = squeezed.split(" ");
		check("the extortion council squeezes the town", Double.parseDouble(s1[0]) > Double.parseDouble(b0[0]) && Double.parseDouble(s1[1]) < Double.parseDouble(b0[1]),
			"tax " + b0[0] + " -> " + s1[0] + ", wages " + b0[1] + " -> " + s1[1]);
		check("... and pays the governor a cut", Long.parseLong(s1[2]) > Long.parseLong(b0[2]), squeezed);
		sp.getServer().runCommand(at + "civitas council growth");
		for (int d = 0; d < 4; d++) {
			this.feedEveryone(sp, cityId);
			sp.getServer().runCommand(at + "civitas day");
		}
		String grown = sp.getServer().computeOnServer(server -> {
			City c = CityManager.get().city(cityId);
			return c.policies.incomeTax + " " + c.policies.wageLevel + " " + c.policies.forcedLabor + " | " + String.join(" / ", c.log.subList(0, 3));
		});
		String[] g1 = grown.split(" ");
		check("the growth council loosens the reins", Double.parseDouble(g1[0]) < Double.parseDouble(s1[0]) && Double.parseDouble(g1[1]) > Double.parseDouble(s1[1])
			&& g1[2].equals("false"), grown);
		sp.getServer().runCommand(at + "civitas council none");
		sp.getServer().runCommand(at + "civitas keeploaded off");
		boolean off = sp.getServer().computeOnServer(server -> !CityManager.get().city(cityId).keepsLoaded());
		sp.getServer().runCommand(at + "civitas keeploaded default");
		boolean def = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).keepsLoaded() && CityManager.get().city(cityId).keepLoaded == null);
		check("operators can keep a town loaded or let it sleep", off && def, off + " " + def);
		sp.getServer().runOnServer(server -> {
			City c = CityManager.get().city(cityId);
			c.strategy = "balanced";
			ServerNet.openGovern(server.getPlayerList().getPlayers().getFirst(), c);
		});
		context.waitFor(mc -> mc.gui.screen() instanceof GovernScreen, 200);
		context.runOnClient(mc -> ((GovernScreen) mc.gui.screen()).setTab(3));
		context.waitTicks(10);
		shot(context, "civitas_ledger_council");
		context.runOnClient(mc -> mc.gui.setScreen(null));
		sp.getServer().runOnServer(server -> CityManager.get().city(cityId).strategy = "none");
	}

	/** The 1.1.1 fixes: no friendly fire, the governor isn't an enemy, the town clock, the supply check. */
	private void hotfixScenes(ClientGameTestContext context, TestSingleplayerContext sp, java.util.UUID cityId, BlockPos center) {
		// zombies right in front of a crowd of townsfolk: the soldiers must kill them without shooting anyone of their own
		sp.getServer().runCommand("time set 14500");
		String crowd = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			Building b = c.firstComplete(BuildingType.BARRACKS);
			Direction front = b.rot().rotate(Direction.SOUTH);
			BlockPos at = b.entrance().relative(front, 14);
			at = new BlockPos(at.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
			int n = 0;
			for (CitizenRecord r : c.citizens.values()) {
				CitizenEntity e = CityManager.get().entity(r.uuid);
				if (e == null || r.job == net.antwire.civitas.city.Job.SOLDIER || n >= 6) {
					continue;
				}
				// behind the zombies, as seen from the barracks
				BlockPos spot = at.relative(front, 3).relative(front.getClockWise(), n - 3);
				e.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
				n++;
			}
			for (int i = 0; i < 4; i++) {
				var z = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				z.snapTo(at.getX() + 0.5 + i - 2, at.getY(), at.getZ() + 0.5, 0, 0);
				z.setPersistenceRequired();
				z.addTag("civitas_hotfix_zombie");
				level.addFreshEntity(z);
			}
			return n + "";
		});
		int deadBefore = sp.getServer().computeOnServer(server -> (int) CityManager.get().city(cityId).citizens.values().stream()
			.filter(r -> r.status == CitizenRecord.Status.DEAD).count());
		int left = 4;
		for (int t = 0; t < 40 && left > 0; t++) {
			context.waitTicks(20);
			left = sp.getServer().computeOnServer(server -> server.overworld().getEntities(net.minecraft.world.entity.EntityTypes.ZOMBIE,
				z -> z.isAlive() && z.entityTags().contains("civitas_hotfix_zombie")).size());
		}
		String hurt = sp.getServer().computeOnServer(server -> {
			City c = CityManager.get().city(cityId);
			long dead = c.citizens.values().stream().filter(r -> r.status == CitizenRecord.Status.DEAD).count();
			return dead + "";
		});
		check("soldiers' rounds spare the townsfolk", left <= 1 && Integer.parseInt(hurt) <= deadBefore,
			(4 - left) + " of 4 zombies killed, " + crowd + " citizens in the line of fire, deaths " + deadBefore + " -> " + hurt);
		// the governor strikes a citizen: no soldier turns on them
		String governor = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
			p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
			CitizenEntity victim = null;
			for (CitizenRecord r : c.citizens.values()) {
				victim = CityManager.get().entity(r.uuid);
				if (victim != null && r.job != net.antwire.civitas.city.Job.SOLDIER) {
					break;
				}
			}
			victim.hurtServer(level, level.damageSources().playerAttack(p), 1.0F);
			boolean enemy = net.antwire.civitas.entity.ai.Military.threats(level, c).contains(p);
			p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
			return enemy ? "the guards turned on the governor" : "OK";
		});
		check("the governor is never the town's enemy", governor.equals("OK"), governor);
		// the world clock stops: the town's day goes on
		sp.getServer().runCommand("gamerule advance_time false");
		int[] clock = sp.getServer().computeOnServer(server -> new int[]{CityManager.timeOfDay(server.overworld())});
		context.waitTicks(100);
		int[] later = sp.getServer().computeOnServer(server -> new int[]{CityManager.timeOfDay(server.overworld())});
		sp.getServer().runCommand("gamerule advance_time true");
		check("the town keeps its own time when the world clock stands still", later[0] != clock[0], clock[0] + " -> " + later[0]);
		// the supply check
		String supply = sp.getServer().computeOnServer(server -> {
			StringBuilder sb = new StringBuilder();
			for (var l : net.antwire.civitas.city.Supply.report(server.overworld(), CityManager.get().city(cityId))) {
				sb.append(l.level()).append(' ').append(l.text()).append(" || ");
			}
			return sb.toString();
		});
		System.out.println("[civitas-test] SUPPLY " + supply);
		check("the supply check reports on the food chain", supply.contains("On the counters") && supply.contains("farm"), supply.length() + " chars");
		sp.getServer().runOnServer(server -> ServerNet.openGovern(server.getPlayerList().getPlayers().getFirst(), CityManager.get().city(cityId)));
		context.waitFor(mc -> mc.gui.screen() instanceof GovernScreen, 200);
		context.runOnClient(mc -> ((GovernScreen) mc.gui.screen()).setTab(5));
		context.waitTicks(10);
		shot(context, "civitas_ledger_supply");
		context.runOnClient(mc -> mc.gui.setScreen(null));
		sp.getServer().runCommand("time set 1500");
	}

	/** Soldiers against zombies at night, then a raid. */
	private void barracksScenes(ClientGameTestContext context, TestSingleplayerContext sp, java.util.UUID cityId, BlockPos center) {
		String kit = sp.getServer().computeOnServer(server -> {
			City c = CityManager.get().city(cityId);
			Building b = c.firstComplete(BuildingType.BARRACKS);
			if (b == null) {
				return "FAIL no barracks";
			}
			StringBuilder s = new StringBuilder();
			for (java.util.UUID u : b.workers) {
				CitizenEntity e = CityManager.get().entity(u);
				if (e != null) {
					e.refresh(c, c.citizens.get(u));
					s.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getMainHandItem().getItem()).getPath()).append('/')
						.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).getItem()).getPath())
						.append(c.citizens.get(u).nightWatch ? "/night " : "/day ");
				}
			}
			return b.workers.size() + " " + s;
		});
		check("the barracks has its soldiers", kit.startsWith("4 "), kit);
		check("soldiers carry their kit", kit.contains(net.antwire.civitas.compat.Arms.arsenal() ? "m4a1/combat_helmet" : "bow/iron_helmet"), kit);
		// the barracks
		Vec3[] cam = sp.getServer().computeOnServer(server -> {
			Building b = CityManager.get().city(cityId).firstComplete(BuildingType.BARRACKS);
			Direction front = b.rot().rotate(Direction.SOUTH);
			Vec3 door = Vec3.atCenterOf(b.entrance());
			return new Vec3[]{door.add(Vec3.atLowerCornerOf(front.getUnitVec3i()).scale(13)).add(Vec3.atLowerCornerOf(front.getClockWise().getUnitVec3i()).scale(5)).add(0, 11, 0),
				door.add(Vec3.atLowerCornerOf(front.getOpposite().getUnitVec3i()).scale(3)).add(0, 1, 0)};
		});
		sp.getServer().runCommand("time set 3000");
		context.waitTicks(200);
		this.look(context, sp, cam[0].x, cam[0].y, cam[0].z, cam[1]);
		context.waitTicks(40);
		shot(context, "civitas_barracks");
		// inside
		Vec3[] in = sp.getServer().computeOnServer(server -> {
			Building b = CityManager.get().city(cityId).firstComplete(BuildingType.BARRACKS);
			Direction front = b.rot().rotate(Direction.SOUTH);
			BlockPos door = b.mark("door");
			return new Vec3[]{Vec3.atBottomCenterOf(door.relative(front.getOpposite())).add(Vec3.atLowerCornerOf(front.getClockWise().getUnitVec3i()).scale(0.6)),
				Vec3.atCenterOf(b.mark("workblock"))};
		});
		this.look(context, sp, in[0].x, in[0].y, in[0].z, in[1]);
		context.waitTicks(30);
		shot(context, "civitas_barracks_inside");

		// night: zombies walk into the square
		sp.getServer().runCommand("time set 14500");
		sp.getServer().runCommand("effect give @a minecraft:night_vision 600 0 true");
		BlockPos spot = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			City c = CityManager.get().city(cityId);
			Building b = c.firstComplete(BuildingType.BARRACKS);
			BlockPos e = b.entrance();
			Direction front = b.rot().rotate(Direction.SOUTH);
			BlockPos at = e.relative(front, 18);
			at = new BlockPos(at.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
			for (int i = 0; i < 4; i++) {
				var z = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				z.snapTo(at.getX() + 0.5 + i, at.getY(), at.getZ() + 0.5, 0, 0);
				z.setPersistenceRequired();
				z.addTag("civitas_test_zombie");
				level.addFreshEntity(z);
			}
			return at;
		});
		Vec3[] view = sp.getServer().computeOnServer(server -> {
			Building b = CityManager.get().city(cityId).firstComplete(BuildingType.BARRACKS);
			Direction front = b.rot().rotate(Direction.SOUTH);
			Vec3 door = Vec3.atCenterOf(b.entrance());
			return new Vec3[]{door.add(Vec3.atLowerCornerOf(front.getUnitVec3i()).scale(6)).add(Vec3.atLowerCornerOf(front.getClockWise().getUnitVec3i()).scale(7)).add(0, 4, 0),
				door.add(Vec3.atLowerCornerOf(front.getUnitVec3i()).scale(11))};
		});
		this.look(context, sp, view[0].x, view[0].y, view[0].z, view[1]);
		context.waitTicks(25);
		shot(context, "civitas_soldiers_night");
		int left = 4;
		for (int t = 0; t < 40 && left > 0; t++) {
			context.waitTicks(20);
			left = sp.getServer().computeOnServer(server -> server.overworld().getEntities(net.minecraft.world.entity.EntityTypes.ZOMBIE,
				z -> z.isAlive() && z.entityTags().contains("civitas_test_zombie")).size());
			if (t == 0) {
				shot(context, "civitas_soldiers_fight");
			}
		}
		check("soldiers kill the monsters in town", left == 0, (4 - left) + " of 4 zombies killed");
		sp.getServer().runCommand("effect clear @a minecraft:night_vision");

		// a raid
		sp.getServer().runCommand("execute positioned " + center.getX() + " " + center.getY() + " " + center.getZ() + " run civitas raid");
		boolean beaten = false;
		for (int t = 0; t < 90 && !beaten; t++) {
			context.waitTicks(20);
			beaten = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).log.stream().limit(8).anyMatch(l -> l.contains("raid was beaten off")));
		}
		String log = sp.getServer().computeOnServer(server -> String.join(" | ", CityManager.get().city(cityId).log.subList(0, 6)));
		check("a raid is beaten off", beaten, log);
		sp.getServer().runCommand("kill @e[type=minecraft:pillager]");
		sp.getServer().runCommand("kill @e[type=minecraft:vindicator]");
		sp.getServer().runCommand("time set 1500");
	}

	private void feedEveryone(TestSingleplayerContext sp, java.util.UUID cityId) {
		sp.getServer().runOnServer(server -> {
			for (CitizenRecord r : CityManager.get().city(cityId).citizens.values()) {
				r.ateToday = true;
				r.sleptInBed = true;
				r.food = 80;
			}
		});
	}

	private int[] tiers(TestSingleplayerContext sp, java.util.UUID cityId) {
		return sp.getServer().computeOnServer(server -> {
			City c = CityManager.get().city(cityId);
			int[] t = new int[4];
			for (CitizenRecord r : c.citizens.values()) {
				if (r.present()) {
					t[r.tier(CommerceApi.balance(r.account(c)))]++;
				}
			}
			return t;
		});
	}

	/** Gathers a few citizens in front of the camera and refreshes their looks. */
	private void showCrowd(ClientGameTestContext context, TestSingleplayerContext sp, java.util.UUID cityId, BlockPos center, String shot) {
		sp.getServer().runOnServer(server -> {
			City c = CityManager.get().city(cityId);
			int i = 0;
			for (CitizenRecord r : c.citizens.values()) {
				CitizenEntity e = CityManager.get().entity(r.uuid);
				if (e == null || i >= 7) {
					continue;
				}
				e.refresh(c, r);
				e.teleportTo(center.getX() + 0.5 + (i - 3) * 1.3, center.getY(), center.getZ() + 2.5 + (i % 2));
				e.getNavigation().stop();
				i++;
			}
		});
		// mow the square so the grass doesn't hide anyone
		sp.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			for (int dx = -6; dx <= 6; dx++) {
				for (int dz = -2; dz <= 10; dz++) {
					for (int dy = -1; dy <= 2; dy++) {
						BlockPos p = center.offset(dx, dy, dz);
						var st = level.getBlockState(p);
						if (st.canBeReplaced() && !st.isAir() && st.getFluidState().isEmpty()) {
							level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
						}
					}
				}
			}
		});
		this.look(context, sp, center.getX() + 0.5, center.getY() + 2.2, center.getZ() + 8.5, Vec3.atCenterOf(center).add(0, 0.8, 2.5));
		context.waitTicks(4);
		shot(context, shot);
	}

	private void tp(ClientGameTestContext context, TestSingleplayerContext sp, double x, double y, double z, float yaw, float pitch) {
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
		sp.getServer().runOnServer(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				player.setDeltaMovement(Vec3.ZERO);
			}
		});
		context.runOnClient(mc -> {
			if (mc.player != null) {
				mc.player.getAbilities().flying = true;
				mc.player.setDeltaMovement(Vec3.ZERO);
			}
		});
		context.waitTicks(5);
		try {
			sp.getConnection().waitForChunksRender(false, 600);
		} catch (AssertionError e) {
			System.out.println("[civitas-test] chunks still rendering");
		}
	}

	private void look(ClientGameTestContext context, TestSingleplayerContext sp, double x, double y, double z, Vec3 target) {
		double dx = target.x - x;
		double dy = target.y - (y + 1.62);
		double dz = target.z - z;
		float yaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
		this.tp(context, sp, x, y, z, yaw, pitch);
	}

	/** A screenshot without the chat in the way. */
	private static void shot(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
		context.waitTicks(1);
		context.takeScreenshot(name);
	}
}
