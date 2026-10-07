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
			String[] kinds = {"farm", "bakery", "house", "house", "house", "lumber_mill", "mine", "butcher", "tavern", "blacksmith", "sheriff", "prison", "bank",
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
			sp.getServer().runCommand("execute positioned " + center.getX() + " " + center.getY() + " " + center.getZ() + " run civitas immigrate 13");
			context.waitTicks(200);
			int jobs = sp.getServer().computeOnServer(server -> (int) CityManager.get().city(cityId).citizens.values().stream()
				.filter(r -> r.present() && r.job != net.antwire.civitas.city.Job.UNEMPLOYED).count());
			check("jobs filled", jobs >= 14, jobs + " citizens at work");

			// ---- an aerial view of the town
			int radius = sp.getServer().computeOnServer(server -> CityManager.get().city(cityId).radius);
			this.look(context, sp, center.getX() - radius * 0.9, center.getY() + radius * 0.8, center.getZ() + radius * 0.9, Vec3.atCenterOf(center));
			context.waitTicks(120);
			shot(context, "civitas_town_aerial");

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
