package net.antwire.civitas.city;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.antwire.civitas.Civitas;
import net.antwire.civitas.CivitasConfig;
import net.antwire.civitas.entity.CitizenEntity;
import net.antwire.civitas.registry.ModEntities;
import net.antwire.civitas.registry.ModTickets;
import net.antwire.commerce.api.CommerceApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/** All towns of the running server: stored in &lt;world&gt;/civitas_data.json, ticked every server tick. */
public final class CityManager {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().registerTypeAdapter(BlockPos.class, new PosAdapter()).create();
	private static @Nullable CityManager current;

	public final Map<UUID, City> cities = new LinkedHashMap<>();
	/** Living citizen entities by UUID (registered while they tick). */
	public final Map<UUID, CitizenEntity> entities = new HashMap<>();
	private final Path path;
	private final MinecraftServer server;
	public final Random random = new Random();
	private long lastDay = -1;

	private CityManager(MinecraftServer server, Path path) {
		this.server = server;
		this.path = path;
	}

	public static @Nullable CityManager get() {
		return current;
	}

	public MinecraftServer server() {
		return this.server;
	}

	private static class Stored {
		List<City> cities = new ArrayList<>();
		long lastDay = -1;
	}

	public static void load(MinecraftServer server) {
		Path p = server.getWorldPath(LevelResource.ROOT).resolve("civitas_data.json");
		CityManager m = new CityManager(server, p);
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p)) {
				Stored s = GSON.fromJson(r, Stored.class);
				if (s != null && s.cities != null) {
					for (City c : s.cities) {
						if (c.policies == null) {
							c.policies = new Policies();
						}
						m.cities.put(c.id, c);
					}
					m.lastDay = s.lastDay;
				}
			} catch (Exception e) {
				Civitas.LOGGER.error("Could not read {}", p, e);
			}
		}
		current = m;
		for (City c : m.cities.values()) {
			CommerceApi.openAccount(c.account(), c.name + " treasury");
		}
	}

	public void save() {
		Stored s = new Stored();
		s.cities.addAll(this.cities.values());
		s.lastDay = this.lastDay;
		try {
			Path tmp = this.path.resolveSibling("civitas_data.json.tmp");
			try (Writer w = Files.newBufferedWriter(tmp)) {
				GSON.toJson(s, w);
			}
			Files.move(tmp, this.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Civitas.LOGGER.error("Could not write {}", this.path, e);
		}
	}

	public static void unload() {
		if (current != null) {
			current.save();
		}
		current = null;
	}

	// ------------------------------------------------------------------ lookups

	public @Nullable City city(UUID id) {
		return id == null ? null : this.cities.get(id);
	}

	public @Nullable City cityAt(Level level, BlockPos pos) {
		String dim = level.dimension().identifier().toString();
		for (City c : this.cities.values()) {
			if (c.dimension.equals(dim) && c.contains(pos)) {
				return c;
			}
		}
		return null;
	}

	public @Nullable City cityOf(UUID citizen) {
		for (City c : this.cities.values()) {
			if (c.citizens.containsKey(citizen)) {
				return c;
			}
		}
		return null;
	}

	public @Nullable City governedBy(UUID player) {
		for (City c : this.cities.values()) {
			if (c.isGovernor(player)) {
				return c;
			}
		}
		return null;
	}

	public @Nullable City byName(String name) {
		for (City c : this.cities.values()) {
			if (c.name.equalsIgnoreCase(name)) {
				return c;
			}
		}
		return null;
	}

	public @Nullable ServerLevel level(City c) {
		return this.server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(c.dimension)));
	}

	public @Nullable CitizenEntity entity(UUID id) {
		CitizenEntity e = this.entities.get(id);
		return e != null && e.isAlive() ? e : null;
	}

	// ------------------------------------------------------------------ founding

	/** Founds a town around pos; the town hall is laid out facing {@code front}. Null (and why) if it can't be done. */
	public @Nullable City found(ServerLevel level, ServerPlayer founder, String name, BlockPos pos, Direction front, StringBuilder why) {
		if (this.byName(name) != null) {
			why.append("There is already a town called ").append(name);
			return null;
		}
		for (City c : this.cities.values()) {
			if (c.dimension.equals(level.dimension().identifier().toString()) && c.center.distSqr(pos) < Math.pow(c.radius * 2 + 20, 2)) {
				why.append("Too close to ").append(c.name);
				return null;
			}
		}
		City city = new City();
		city.id = UUID.randomUUID();
		city.name = name;
		city.governor = founder.getUUID();
		city.governorName = founder.getGameProfile().name();
		city.dimension = level.dimension().identifier().toString();
		city.center = pos;
		city.foundedDay = currentDay(level);
		Building hall = Planner.fit(level, city, BuildingType.TOWN_HALL, pos.getX(), pos.getZ(), front, 6);
		if (hall == null) {
			net.antwire.civitas.Civitas.LOGGER.info("No town hall at {}: {}", pos.toShortString(), Planner.lastReason);
			why.setLength(0);
			why.append("The ground here won't do (").append(Planner.lastReason).append(") - pick an open, fairly flat spot");
			return null;
		}
		hall.id = city.nextBuildingId++;
		city.buildings.add(hall);
		city.center = hall.mark("plaza") != null ? hall.mark("plaza") : pos;
		CommerceApi.openAccount(city.account(), name + " treasury");
		CommerceApi.mint(city.account(), Math.round(CivitasConfig.get().foundingGrant * 100), "Founding grant");
		city.log(founder.getGameProfile().name() + " founded " + name);
		this.cities.put(city.id, city);
		CivitasConfig cfg = CivitasConfig.get();
		for (int i = 0; i < cfg.founders; i++) {
			this.immigrate(level, city, i < 2 ? Job.BUILDER : null, true);
		}
		return city;
	}

	/** A new settler: a record, a bank account with a little savings, and a body walking in from the edge of town. */
	public @Nullable CitizenRecord immigrate(ServerLevel level, City city, @Nullable Job job, boolean nearCenter) {
		CitizenRecord r = new CitizenRecord();
		r.uuid = UUID.randomUUID();
		r.female = this.random.nextBoolean();
		r.name = Names.random(this.random, r.female);
		r.skin = this.random.nextInt(Names.SKINS);
		r.age = 18 + this.random.nextInt(45);
		r.arrivedDay = city.day;
		r.food = 70;
		r.happiness = 60;
		r.clothing = 55 + this.random.nextInt(35);
		if (job != null) {
			r.job = job;
			if (job == Job.BUILDER && city.townHall() != null) {
				r.workplace = city.townHall().id;
			}
		}
		double a = this.random.nextDouble() * Math.PI * 2;
		double dist = nearCenter ? 4 + this.random.nextDouble() * 4 : city.radius - 4;
		int x = (int) Math.round(city.center.getX() + Math.cos(a) * dist);
		int z = (int) Math.round(city.center.getZ() + Math.sin(a) * dist);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		CitizenEntity e = ModEntities.CITIZEN.create(level, EntitySpawnReason.EVENT);
		if (e == null) {
			return null;
		}
		e.setUUID(r.uuid);
		e.moveOrInterpolateTo(new net.minecraft.world.phys.Vec3(x + 0.5, y, z + 0.5), this.random.nextFloat() * 360, 0);
		e.bind(city, r);
		city.citizens.put(r.uuid, r);
		CommerceApi.openAccount(r.account(city), r.name);
		CommerceApi.mint(r.account(city), 2000 + this.random.nextInt(4000), "Savings brought along");
		level.addFreshEntity(e);
		city.log(r.name + " moved to " + city.name);
		return r;
	}

	public @Nullable Building plan(ServerLevel level, City city, BuildingType type) {
		Building b = Planner.find(level, city, type);
		// no room left: the town grows outwards
		while (b == null && city.radius < 112) {
			city.radius += 12;
			b = Planner.find(level, city, type);
		}
		if (b == null) {
			return null;
		}
		b.id = city.nextBuildingId++;
		city.buildings.add(b);
		b.steps = Construction.steps(b).size();
		city.log("Construction of a " + type.title.toLowerCase(java.util.Locale.ROOT) + " planned");
		return b;
	}

	/**
	 * The towns' clock. Normally the world's; when the world's clock stands still (gamerule advance_time off), the towns
	 * keep their own from the game's tick counter, so days still pass: wages, rent, meals, sleep.
	 */
	static long clock(Level level) {
		long world = level.getOverworldClockTime();
		if (CivitasConfig.get().ownClockWhenFrozen && level instanceof net.minecraft.server.level.ServerLevel sl
			&& !sl.getServer().overworld().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME)) {
			long ticks = sl.getServer().overworld().getGameTime();
			// runs on from where the world's clock stopped; /time set moves it there again
			if (frozenWorld != world || frozenSince < 0) {
				frozenWorld = world;
				frozenSince = ticks;
			}
			return world + (ticks - frozenSince);
		}
		frozenSince = -1;
		return world;
	}

	private static long frozenWorld;
	private static long frozenSince = -1;

	public static long currentDay(Level level) {
		return Math.floorDiv(clock(level), 24000L);
	}

	public static int timeOfDay(Level level) {
		return (int) Math.floorMod(clock(level), 24000L);
	}

	// ------------------------------------------------------------------ ticking

	public void tick() {
		ServerLevel overworld = this.server.overworld();
		long day = currentDay(overworld);
		boolean newDay = this.lastDay >= 0 && day > this.lastDay;
		if (this.lastDay < 0 || day < this.lastDay || newDay) {
			this.lastDay = day;
		}
		long t = overworld.getGameTime();
		for (City city : this.cities.values()) {
			ServerLevel level = this.level(city);
			if (level == null) {
				continue;
			}
			if (t % 100 == 0 && CivitasConfig.get().keepTownsLoaded) {
				int r = (city.radius >> 4) + 2;
				level.getChunkSource().addTicketWithRadius(ModTickets.TOWN, ChunkPos.containing(city.center), Math.min(r, 8));
			}
			if (t % 20 == 0) {
				Townlife.everySecond(level, city, this);
			}
			if (newDay) {
				city.day++;
				DailyCycle.run(level, city, this);
			}
		}
	}

	/** Gson: BlockPos as [x, y, z]. */
	static final class PosAdapter extends TypeAdapter<BlockPos> {
		@Override
		public void write(JsonWriter out, BlockPos p) throws IOException {
			if (p == null) {
				out.nullValue();
				return;
			}
			out.beginArray().value(p.getX()).value(p.getY()).value(p.getZ()).endArray();
		}

		@Override
		public BlockPos read(JsonReader in) throws IOException {
			if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
				in.nextNull();
				return null;
			}
			in.beginArray();
			BlockPos p = new BlockPos(in.nextInt(), in.nextInt(), in.nextInt());
			in.endArray();
			return p;
		}
	}
}
