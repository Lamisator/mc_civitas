package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The town's ways, improved: first paved with stone (where they run over earth), then lit with street lamps - a post with
 * a lantern every few steps beside the way. Once ordered, new ways are paved and lit as they are made.
 */
public final class Streets {
	public static final BlockState PAVING = Blocks.STONE_BRICKS.defaultBlockState();
	/** Steps along a way between two lamps. */
	public static final int LAMP_SPACING = 10;
	private static final int POST = 3;

	private Streets() {
	}

	public static String level(int streets) {
		return switch (streets) {
			case 2 -> "paved and lit";
			case 1 -> "paved";
			default -> "trodden paths";
		};
	}

	/** Earth a way may run over (and that paving replaces). */
	static boolean earth(BlockState s) {
		return s.is(Blocks.DIRT_PATH) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL)
			|| s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.MUD) || s.is(Blocks.GRAVEL) || s.is(Blocks.MYCELIUM);
	}

	/** Every step of every built way, once. */
	static Set<BlockPos> ways(City city) {
		Set<BlockPos> out = new LinkedHashSet<>();
		for (Building b : city.buildings) {
			if (b.complete) {
				out.addAll(b.approach);
			}
		}
		return out;
	}

	/** Paving for every step of the ways that is still earth (not where a building's own floor is the way). */
	static List<Project.Job> paving(ServerLevel level, City city) {
		List<Project.Job> out = new ArrayList<>();
		for (BlockPos g : ways(city)) {
			if (!level.isLoaded(g) || Access.owned(city, g)) {
				continue;
			}
			if (earth(level.getBlockState(g))) {
				out.add(new Project.Job("BLOCK", g, Works.text(PAVING)));
			}
		}
		return out;
	}

	/** The blocks of a lamp on the ground at g: a post of fence and a lantern on top. */
	static Map<BlockPos, BlockState> lamp(BlockPos g) {
		Map<BlockPos, BlockState> out = new LinkedHashMap<>();
		for (int y = 1; y <= POST; y++) {
			out.put(g.above(y), Blocks.SPRUCE_FENCE.defaultBlockState());
		}
		out.put(g.above(POST + 1), Blocks.LANTERN.defaultBlockState());
		return out;
	}

	/** Where new lamps go: beside the ways, every so many steps, clear of plots, the wall and the other lamps. */
	static List<BlockPos> newLamps(ServerLevel level, City city) {
		List<BlockPos> lamps = new ArrayList<>(city.lamps);
		List<BlockPos> added = new ArrayList<>();
		List<AABB> plots = new ArrayList<>();
		for (Building b : city.buildings) {
			plots.add(b.plot().inflate(1, 0, 1));
		}
		for (Building b : city.buildings) {
			if (!b.complete) {
				continue;
			}
			List<BlockPos> way = b.approach;
			int since = LAMP_SPACING / 2;
			for (int i = 1; i + 1 < way.size(); i++) {
				if (++since < LAMP_SPACING) {
					continue;
				}
				BlockPos g = way.get(i);
				BlockPos next = way.get(i + 1);
				Direction along = Direction.getApproximateNearest(next.getX() - g.getX(), 0, next.getZ() - g.getZ());
				for (Direction side : new Direction[]{along.getClockWise(), along.getCounterClockWise()}) {
					BlockPos spot = spot(level, city, plots, lamps, g, side);
					if (spot != null) {
						lamps.add(spot);
						added.add(spot);
						since = 0;
						break;
					}
				}
			}
		}
		return added;
	}

	private static @Nullable BlockPos spot(ServerLevel level, City city, List<AABB> plots, List<BlockPos> lamps, BlockPos g, Direction side) {
		int x = g.getX() + side.getStepX();
		int z = g.getZ() + side.getStepZ();
		if (!level.isLoaded(new BlockPos(x, g.getY(), z)) || Access.onAWay(city, x, z)) {
			return null;
		}
		int gy = Access.ground(level, x, z);
		if (Math.abs(gy - g.getY()) > 1) {
			return null;
		}
		BlockPos ground = new BlockPos(x, gy, z);
		if (!Access.floor(level.getBlockState(ground)) || !level.getBlockState(ground).getFluidState().isEmpty()) {
			return null;
		}
		Vec3 c = Vec3.atCenterOf(ground);
		for (AABB box : plots) {
			if (box.contains(c.x, box.minY + 0.5, c.z)) {
				return null;
			}
		}
		int[] w = city.wallRect;
		if (w != null && x >= w[0] - 3 && x <= w[2] + 3 && z >= w[1] - 3 && z <= w[3] + 3 && (x <= w[0] + 3 || x >= w[2] - 3 || z <= w[1] + 3 || z >= w[3] - 3)) {
			return null;
		}
		for (BlockPos l : lamps) {
			if (l.distSqr(ground) < (LAMP_SPACING - 2) * (LAMP_SPACING - 2)) {
				return null;
			}
		}
		for (BlockPos p : lamp(ground).keySet()) {
			if (!Works.repairable(level, p, level.getBlockState(p))) {
				return null;
			}
		}
		return ground;
	}

	static List<Project.Job> lampJobs(ServerLevel level, List<BlockPos> spots) {
		List<Project.Job> out = new ArrayList<>();
		for (BlockPos g : spots) {
			for (Map.Entry<BlockPos, BlockState> e : lamp(g).entrySet()) {
				if (!level.getBlockState(e.getKey()).isAir()) {
					out.add(new Project.Job("CLEAR", e.getKey(), "minecraft:air"));
				}
				out.add(new Project.Job("BLOCK", e.getKey(), Works.text(e.getValue())));
			}
		}
		return out;
	}

	/** The governor orders the next improvement (1 = paving, 2 = lamps). Returns a note for the ledger. */
	public static String order(ServerLevel level, City city, int to) {
		if (to == 2 && city.streets < 1) {
			return "Pave the ways first";
		}
		if (to <= city.streets) {
			return "The ways are already " + level(city.streets);
		}
		city.streets = to;
		daily(level, city);
		Project p = city.project(to == 1 ? Project.STREETS : Project.LAMPS, "town");
		return p == null ? "Nothing to do right now; new ways will be " + level(to) : p.title + ": " + p.jobs.size() + " blocks, about "
			+ net.antwire.commerce.api.CommerceApi.format(Works.cost(p.jobs, 0));
	}

	/** Once a day (and when ordered): pave new ways, light new ways. */
	public static void daily(ServerLevel level, City city) {
		if (city.streets >= 1 && city.project(Project.STREETS, "town") == null) {
			List<Project.Job> jobs = paving(level, city);
			if (!jobs.isEmpty()) {
				Project p = new Project(Project.STREETS, "town", "Paving the ways (" + jobs.size() + " steps)");
				p.jobs = jobs;
				city.projects.add(p);
			}
		}
		if (city.streets >= 2 && city.project(Project.LAMPS, "town") == null) {
			List<BlockPos> spots = newLamps(level, city);
			if (!spots.isEmpty()) {
				Project p = new Project(Project.LAMPS, "town", "Street lamps (" + spots.size() + ")");
				p.jobs = lampJobs(level, spots);
				city.lamps.addAll(spots);
				city.projects.add(p);
			}
		}
	}

	/** What paving the ways and lighting them would cost now, in cents: {paving, lamps}. */
	public static long[] estimate(ServerLevel level, City city) {
		long key = city.day * 1000L + city.buildings.size() * 10L + city.streets;
		if (city.streetEstimateKey != key) {
			long pave = paving(level, city).size() * Materials.price(PAVING.getBlock().asItem());
			long lamps = city.streets >= 1 ? newLamps(level, city).size() * (POST * Materials.price(Items.SPRUCE_FENCE) + Materials.price(Items.LANTERN)) : 0;
			city.streetEstimate = new long[]{pave, lamps};
			city.streetEstimateKey = key;
		}
		return city.streetEstimate;
	}

	/** The inspectors check the lamps: what is broken becomes a repair. */
	public static void inspectLamps(ServerLevel level, City city, boolean order) {
		if (city.lamps.isEmpty() || city.project(Project.LAMPS, "town") != null) {
			return;
		}
		List<Project.Job> jobs = new ArrayList<>();
		for (BlockPos g : city.lamps) {
			if (!level.isLoaded(g)) {
				continue;
			}
			for (Map.Entry<BlockPos, BlockState> e : lamp(g).entrySet()) {
				BlockState now = level.getBlockState(e.getKey());
				if (!Works.same(e.getValue(), now) && Works.repairable(level, e.getKey(), now)) {
					jobs.add(new Project.Job("BLOCK", e.getKey(), Works.text(e.getValue())));
				}
			}
		}
		Works.queueRepair(city, "lamps", "Repairs: street lamps", jobs, order);
	}
}
