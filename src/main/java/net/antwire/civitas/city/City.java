package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** A town: its land, buildings, citizens, laws, treasury and memory. */
public class City {
	public UUID id;
	public String name;
	public UUID governor;
	public String governorName = "";
	public List<UUID> deputies = new ArrayList<>();
	public String dimension = "minecraft:overworld";
	public BlockPos center;
	public int radius = 40;
	public int day;
	public long foundedDay;
	public Policies policies = new Policies();
	public List<Building> buildings = new ArrayList<>();
	public int nextBuildingId = 1;
	public Map<UUID, CitizenRecord> citizens = new LinkedHashMap<>();
	/** Building materials and goods held by the town (item id -> count). */
	public Map<String, Integer> stockpile = new LinkedHashMap<>();
	public List<String> log = new ArrayList<>();
	public List<DayStat> history = new ArrayList<>();
	public String ticker = "";
	public boolean strike;
	public boolean riot;
	public int unrestDays;
	/** Lingering resentment from the governor's acts (decays daily). */
	public double grievance;
	public double fear;
	public int festivalDays;
	public long treasuryYesterday;
	public long incomeToday;
	public long spendingToday;
	public long incomeYesterday;
	public long spendingYesterday;
	/** Every building's access checked since the world was loaded. */
	public transient boolean accessChecked;
	/** Self-government: "none" (the governor rules) or a {@link Council.Strategy}. */
	public String strategy = "none";
	/** Keep the town's land loaded while nobody is near: null = as the server config says (operators decide). */
	public @Nullable Boolean keepLoaded;

	public boolean keepsLoaded() {
		return this.keepLoaded != null ? this.keepLoaded : net.antwire.civitas.CivitasConfig.get().keepTownsLoaded;
	}

	/** Repairs, the wall, paving and street lamps: builders take repairs first, then new buildings, then the rest. */
	public List<Project> projects = new ArrayList<>();
	/** Damage is repaired as soon as the daily rounds find it (otherwise the governor orders each repair). */
	public boolean autoRepair = true;
	/** The town wall: its tier (0 = none), every block of it ("x y z state") and its outline {minX, minZ, maxX, maxZ}. */
	public int wallTier;
	public List<String> wall = new ArrayList<>();
	public int @Nullable [] wallRect;
	/** 0 = trodden paths, 1 = paved, 2 = paved and lit by street lamps. */
	public int streets;
	/** Street lamps: the ground under each post. */
	public List<BlockPos> lamps = new ArrayList<>();
	/** Which part of the town the inspectors look at next (buildings, then the wall, then the lamps). */
	public transient int inspectCursor;
	/** Cost estimates for the ledger, worked out at most once a day. */
	public transient long wallEstimate;
	public transient long wallEstimateKey = -1;
	public transient long[] streetEstimate = {0, 0};
	public transient long streetEstimateKey = -1;

	public @Nullable Project project(String kind, String target) {
		for (Project p : this.projects) {
			if (p.kind.equals(kind) && p.target.equals(target)) {
				return p;
			}
		}
		return null;
	}

	/** The last day anyone attacked the town or its people. */
	public int lastAttackDay = -100;

	public static class DayStat {
		public int day;
		public int population;
		public double happiness;
		public long treasury;
		public long output;
		public int employed;
		public int[] tiers = new int[4];
		public int crimes;
	}

	public String account() {
		return "civitas:city:" + this.id;
	}

	public void log(String line) {
		this.log.add(0, "Day " + this.day + ": " + line);
		while (this.log.size() > 80) {
			this.log.remove(this.log.size() - 1);
		}
	}

	public @Nullable Building building(int id) {
		for (Building b : this.buildings) {
			if (b.id == id) {
				return b;
			}
		}
		return null;
	}

	public @Nullable Building townHall() {
		for (Building b : this.buildings) {
			if (b.type == BuildingType.TOWN_HALL) {
				return b;
			}
		}
		return null;
	}

	public List<Building> of(BuildingType type, boolean completeOnly) {
		List<Building> out = new ArrayList<>();
		for (Building b : this.buildings) {
			if (b.type == type && (!completeOnly || b.complete)) {
				out.add(b);
			}
		}
		return out;
	}

	public @Nullable Building firstComplete(BuildingType type) {
		for (Building b : this.buildings) {
			if (b.type == type && b.complete) {
				return b;
			}
		}
		return null;
	}

	public @Nullable Building nextConstruction() {
		for (Building b : this.buildings) {
			if (!b.complete || b.upgrading()) {
				return b;
			}
		}
		return null;
	}

	/** The town's tier: how far its town hall has been upgraded. */
	public int tier() {
		Building hall = this.townHall();
		return hall == null || !hall.complete ? 1 : hall.tier;
	}

	public List<CitizenRecord> living() {
		List<CitizenRecord> out = new ArrayList<>();
		for (CitizenRecord c : this.citizens.values()) {
			if (c.present()) {
				out.add(c);
			}
		}
		return out;
	}

	public int population() {
		int n = 0;
		for (CitizenRecord c : this.citizens.values()) {
			if (c.present()) {
				n++;
			}
		}
		return n;
	}

	public int beds() {
		int n = 0;
		for (Building b : this.buildings) {
			if (b.complete) {
				n += b.bedCount();
			}
		}
		return n;
	}

	public double averageHappiness() {
		double s = 0;
		int n = 0;
		for (CitizenRecord c : this.citizens.values()) {
			if (c.present()) {
				s += c.happiness;
				n++;
			}
		}
		return n == 0 ? 50 : s / n;
	}

	public boolean isGovernor(UUID player) {
		return player.equals(this.governor) || this.deputies.contains(player);
	}

	public boolean contains(BlockPos pos) {
		return pos.distSqr(this.center) <= (double) this.radius * this.radius;
	}

	public AABB area() {
		return new AABB(this.center).inflate(this.radius, 64, this.radius);
	}

	public int stock(String item) {
		return this.stockpile.getOrDefault(item, 0);
	}

	public void addStock(String item, int n) {
		if (n != 0) {
			this.stockpile.merge(item, n, Integer::sum);
			if (this.stockpile.get(item) <= 0) {
				this.stockpile.remove(item);
			}
		}
	}
}
