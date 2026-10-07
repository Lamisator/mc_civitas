package net.antwire.civitas.city;

import java.util.Locale;

/** What a citizen does for a living. The ordinal is synced to clients (outfit). */
public enum Job {
	UNEMPLOYED("Unemployed", 0.0, false),
	BUILDER("Builder", 1.2, true),
	FARMER("Farmer", 1.0, false),
	BAKER("Baker", 1.0, false),
	BUTCHER("Butcher", 1.0, false),
	BLACKSMITH("Blacksmith", 1.3, false),
	MINER("Miner", 1.3, false),
	LUMBERJACK("Lumberjack", 1.1, false),
	BANKER("Banker", 1.5, true),
	SHERIFF("Sheriff", 1.3, true),
	FACTORY_WORKER("Munitions Worker", 1.4, false),
	TAVERN_KEEPER("Innkeeper", 1.0, false),
	CLERK("Town Clerk", 1.1, true),
	PRISONER("Prisoner", 0.0, false);

	public final String title;
	/** Wage relative to the base wage. */
	public final double wageFactor;
	/** Paid from the treasury rather than by a business. */
	public final boolean publicSector;

	Job(String title, double wageFactor, boolean publicSector) {
		this.title = title;
		this.wageFactor = wageFactor;
		this.publicSector = publicSector;
	}

	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public static Job byOrdinal(int i) {
		Job[] v = values();
		return i >= 0 && i < v.length ? v[i] : UNEMPLOYED;
	}

	public static Job byId(String id) {
		for (Job j : values()) {
			if (j.id().equalsIgnoreCase(id) || j.name().equalsIgnoreCase(id)) {
				return j;
			}
		}
		return UNEMPLOYED;
	}
}
