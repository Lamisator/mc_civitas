package net.antwire.civitas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** config/civitas.json. Amounts in whole currency units unless noted. */
public class CivitasConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static CivitasConfig instance;

	/** Money the town starts with (granted, not taken from the founder). */
	public double foundingGrant = 1500;
	/** Settlers who arrive with the founding. */
	public int founders = 5;
	/** Largest population a town can reach. */
	public int maxCitizens = 40;
	/** Base wage per day before job factors and the town's wage policy. */
	public double baseWage = 12;
	/** Ticks a builder needs per block. */
	public int buildTicksPerBlock = 6;
	/** Keep the town's chunks loaded and ticking while nobody is near (towns live on). */
	public boolean keepTownsLoaded = true;
	/** Citizens can starve to death after this many days without food (0 = never). */
	public int starvationDays = 4;
	/** Desperate citizens may steal. */
	public boolean crime = true;
	/** Unhappy towns riot: protesters smash windows and set fires (off = they only protest). */
	public boolean riotDamage = false;
	/** Extra crop growth when farmers tend their fields (chance per farmer work tick). */
	public double farmTendChance = 0.02;
	/** Depth (Y) the mines dig down to. */
	public int mineDepthY = 0;
	/** What the state arms buyer pays for one missile from the ordnance factory. */
	public double missileExportPrice = 450;
	/** Days a town needs no food production before it imports bread for its citizens at market price. */
	public boolean importFood = true;
	/** Zombies, skeletons and illagers go for citizens as they do for villagers (what the barracks is for). */
	public boolean monstersAttackCitizens = true;
	/** Chance per day that raiders attack a town of 8 or more (0 = never). */
	public double raidChance = 0.08;

	public static CivitasConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("civitas.json");
	}

	private static CivitasConfig load() {
		CivitasConfig c = null;
		Path p = path();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p)) {
				c = GSON.fromJson(r, CivitasConfig.class);
			} catch (Exception e) {
				Civitas.LOGGER.error("Could not read {}, using defaults", p, e);
			}
		}
		if (c == null) {
			c = new CivitasConfig();
		}
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.createDirectories(path().getParent());
			try (Writer w = Files.newBufferedWriter(path())) {
				GSON.toJson(this, w);
			}
		} catch (Exception e) {
			Civitas.LOGGER.error("Could not write {}", path(), e);
		}
	}
}
