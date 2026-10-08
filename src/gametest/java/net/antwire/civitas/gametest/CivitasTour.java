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
			if (run("hotfix")) {
				this.hotfixScenes(context, sp, cityId, center);
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
		check("soldiers' rounds spare the townsfolk", left == 0 && Integer.parseInt(hurt) <= deadBefore,
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
