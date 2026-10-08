package net.antwire.civitas.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.antwire.civitas.city.City;
import net.antwire.civitas.city.Policies;

/** Plain data sent as JSON between server and client. */
public final class Dto {
	public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private Dto() {
	}

	public static class Found {
		public int x;
		public int y;
		public int z;
		public String name;
	}

	public static class CitizenRow {
		public String uuid;
		public String name;
		public String job;
		public int tier;
		public double happiness;
		public double food;
		public long balance;
		public String status;
		public boolean homeless;
		public int workplace;
	}

	public static class BuildingRow {
		public int id;
		public String type;
		public String title;
		public boolean complete;
		public int progress;
		public int workers;
		public int slots;
		public int residents;
		public int beds;
		public String stalled;
		public int tier;
		/** Being raised to this tier (0 = no). */
		public int upgrading;
		/** Why it can't be upgraded now (empty = it can). */
		public String upgradeBlocked = "";
		public long upgradeCost;
		public long revenue;
		public long cost;
		public long balance;
		public int x;
		public int y;
		public int z;
	}

	public static class Plan {
		public String type;
		public String title;
		public long cost;
		public String note;
	}

	public static class Govern {
		public String id;
		public String name;
		public String governor;
		public boolean editable;
		public int day;
		public int population;
		public int beds;
		public int employed;
		public double happiness;
		public long treasury;
		public long income;
		public long spending;
		public boolean strike;
		public boolean riot;
		public String ticker;
		public long sharePrice;
		public long playerBalance;
		public double grievance;
		public double fear;
		public int festivalDays;
		public int[] tiers = new int[4];
		public Policies policies;
		public List<CitizenRow> citizens = new ArrayList<>();
		public List<BuildingRow> buildings = new ArrayList<>();
		public List<String> log = new ArrayList<>();
		public List<City.DayStat> history = new ArrayList<>();
		public Map<String, Integer> stockpile = new LinkedHashMap<>();
		public List<Plan> plans = new ArrayList<>();
		public long baseWage;
		/** Self-government. */
		public String strategy = "none";
		public String strategyTitle = "";
		public String strategyText = "";
		/** The viewer is an operator (may switch keep-loaded). */
		public boolean op;
		/** -1 = server default, 0 = off, 1 = on. */
		public int keepLoaded = -1;
		public boolean keepsLoaded;
		/** The supply check: "level|text" lines (0 fine, 1 warning, 2 trouble). */
		public List<String> supply = new ArrayList<>();
	}

	public static class Citizen {
		public String city;
		public String uuid;
		public String name;
		public String job;
		public int age;
		public boolean female;
		public int tier;
		public double happiness;
		public double food;
		public double rest;
		public double clothing;
		public long balance;
		public long wage;
		public String status;
		public String home;
		public String workplace;
		public boolean editable;
		public Map<String, Double> mood = new LinkedHashMap<>();
		public List<String> thoughts = new ArrayList<>();
		/** {building id, title, free slots} the governor may move them to. */
		public List<Object[]> jobs = new ArrayList<>();
		public int entityId;
	}

	public static class Action {
		public String city;
		public String op;
		public String key;
		public double value;
		public String text;
		public String citizen;
		public int building;
		public long amount;
	}

	public static class Message {
		public String text;
		public boolean ok;
	}
}
