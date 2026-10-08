package net.antwire.civitas.city;

import java.util.Locale;

/** Every kind of building a town can have, with its job, worker slots and build cost (in whole currency units). */
public enum BuildingType {
	TOWN_HALL("Town Hall", Job.CLERK, 1, 0, 0),
	HOUSE("House", null, 0, 4, 120),
	FARM("Farm", Job.FARMER, 2, 0, 60),
	BAKERY("Bakery", Job.BAKER, 1, 0, 140),
	BUTCHER("Butcher", Job.BUTCHER, 1, 0, 150),
	BLACKSMITH("Blacksmith", Job.BLACKSMITH, 1, 0, 220),
	MINE("Mine", Job.MINER, 3, 0, 160),
	LUMBER_MILL("Lumber Mill", Job.LUMBERJACK, 2, 0, 90),
	BANK("Bank", Job.BANKER, 1, 0, 400),
	SHERIFF("Sheriff's Office", Job.SHERIFF, 2, 0, 180),
	PRISON("Prison", null, 0, 0, 260),
	FACTORY("Ordnance Factory", Job.FACTORY_WORKER, 3, 0, 600),
	TAVERN("Tavern", Job.TAVERN_KEEPER, 1, 0, 200),
	BARRACKS("Barracks", Job.SOLDIER, 4, 0, 350);

	public final String title;
	public final Job job;
	public final int workers;
	public final int beds;
	/** Planning fee on top of materials, whole units. */
	public final int fee;

	BuildingType(String title, Job job, int workers, int beds, int fee) {
		this.title = title;
		this.job = job;
		this.workers = workers;
		this.beds = beds;
		this.fee = fee;
	}

	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public static BuildingType byId(String id) {
		for (BuildingType t : values()) {
			if (t.id().equalsIgnoreCase(id) || t.name().equalsIgnoreCase(id)) {
				return t;
			}
		}
		return null;
	}
}
