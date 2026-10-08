package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The town wall: a ring around every plot and way of the town, following the ground, with a gate in every side (and more
 * along long sides). Tier I is a wooden palisade, tier II a cobblestone base under a timber parapet, tier III stone brick
 * with battlements and corner towers. The wall follows the town: when the town hall goes up a tier the wall is rebuilt
 * in the new style, and when the town outgrows it the wall is moved outwards (the old stretch comes down).
 */
public final class Walls {
	/** Free ground between the wall and the nearest plot or way. */
	public static final int MARGIN = 5;
	/** No stretch of wall longer than this without a gate. */
	public static final int GATE_SPACING = 56;

	private Walls() {
	}

	public static String style(int tier) {
		return switch (tier) {
			case 3 -> "stone wall with battlements";
			case 2 -> "stone and timber wall";
			case 1 -> "wooden palisade";
			default -> "no wall";
		};
	}

	private static int height(int tier) {
		return tier == 1 ? 4 : 5;
	}

	/** The outline the town needs: every plot, way and lamp, the square, and room to spare. */
	public static int[] needed(City city) {
		int minX = city.center.getX() - 8;
		int minZ = city.center.getZ() - 8;
		int maxX = city.center.getX() + 8;
		int maxZ = city.center.getZ() + 8;
		for (Building b : city.buildings) {
			AABB box = b.plot();
			minX = Math.min(minX, (int) Math.floor(box.minX));
			minZ = Math.min(minZ, (int) Math.floor(box.minZ));
			maxX = Math.max(maxX, (int) Math.ceil(box.maxX) - 1);
			maxZ = Math.max(maxZ, (int) Math.ceil(box.maxZ) - 1);
			for (BlockPos p : b.approach) {
				minX = Math.min(minX, p.getX());
				minZ = Math.min(minZ, p.getZ());
				maxX = Math.max(maxX, p.getX());
				maxZ = Math.max(maxZ, p.getZ());
			}
		}
		for (BlockPos p : city.lamps) {
			minX = Math.min(minX, p.getX());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxZ = Math.max(maxZ, p.getZ());
		}
		return new int[]{minX - MARGIN, minZ - MARGIN, maxX + MARGIN, maxZ + MARGIN};
	}

	public static boolean inside(int[] outer, int[] inner, int slack) {
		return inner[0] >= outer[0] + slack && inner[1] >= outer[1] + slack && inner[2] <= outer[2] - slack && inner[3] <= outer[3] - slack;
	}

	public static int[] union(int[] a, int[] b) {
		return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
	}

	/** The wall's blocks and the openings to keep clear. */
	record Plan(Map<BlockPos, BlockState> blocks, List<BlockPos> openings, int gates, int gaps) {
	}

	/** One step along the outline: where, how far round, and what stands there. */
	private record Spot(int x, int z, int index, boolean corner, int gate) {
	}

	/** Gate offsets along a side running from a to b (inclusive), the first aimed at the town centre's line. */
	private static List<Integer> gates(int a, int b, int centre) {
		List<Integer> out = new ArrayList<>();
		int len = b - a;
		int n = Math.max(1, Math.round(len / (float) GATE_SPACING));
		if (n == 1) {
			out.add(Math.max(a + 4, Math.min(b - 4, centre)));
		} else {
			for (int k = 1; k <= n; k++) {
				out.add(a + k * len / (n + 1));
			}
		}
		return out;
	}

	/** -1 no gate, 0 opening, 1 gate post. */
	private static int gateRole(List<Integer> gates, int v) {
		for (int g : gates) {
			int d = Math.abs(v - g);
			if (d <= 1) {
				return 0;
			}
			if (d == 2) {
				return 1;
			}
		}
		return -1;
	}

	static Plan plan(ServerLevel level, City city, int tier, int[] r) {
		Map<BlockPos, BlockState> old = Works.cells(city.wall);
		Set<Long> plots = new HashSet<>();
		for (Building b : city.buildings) {
			AABB box = b.plot();
			for (int x = (int) box.minX; x < (int) box.maxX; x++) {
				for (int z = (int) box.minZ; z < (int) box.maxZ; z++) {
					plots.add(BlockPos.asLong(x, 0, z));
				}
			}
		}
		List<Spot> ring = new ArrayList<>();
		List<Integer> north = gates(r[0], r[2], city.center.getX());
		List<Integer> south = gates(r[0], r[2], city.center.getX());
		List<Integer> west = gates(r[1], r[3], city.center.getZ());
		List<Integer> east = gates(r[1], r[3], city.center.getZ());
		int i = 0;
		for (int x = r[0]; x < r[2]; x++) {
			ring.add(new Spot(x, r[1], i++, x == r[0], gateRole(north, x)));
		}
		for (int z = r[1]; z < r[3]; z++) {
			ring.add(new Spot(r[2], z, i++, z == r[1], gateRole(east, z)));
		}
		for (int x = r[2]; x > r[0]; x--) {
			ring.add(new Spot(x, r[3], i++, x == r[2], gateRole(south, x)));
		}
		for (int z = r[3]; z > r[1]; z--) {
			ring.add(new Spot(r[0], z, i++, z == r[3], gateRole(west, z)));
		}
		Map<BlockPos, BlockState> out = new LinkedHashMap<>();
		List<BlockPos> openings = new ArrayList<>();
		int gaps = 0;
		int h = height(tier);
		for (Spot s : ring) {
			if (plots.contains(BlockPos.asLong(s.x, 0, s.z)) || Access.onAWay(city, s.x, s.z)) {
				gaps++;
				continue;
			}
			int ground = ground(level, old, s.x, s.z);
			BlockState floor = level.getBlockState(new BlockPos(s.x, ground, s.z));
			if (floor.is(Blocks.LAVA)) {
				gaps++;
				continue;
			}
			int base = ground + 1;
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, s.x, s.z) - 1;
			int top = level.getBlockState(new BlockPos(s.x, surface, s.z)).getFluidState().isEmpty() ? ground + h : Math.max(ground + h, surface + 2);
			// someone's build in the way: leave a gap rather than wall it in
			boolean blocked = false;
			for (int y = base; y <= top + 4 && !blocked; y++) {
				BlockPos p = new BlockPos(s.x, y, s.z);
				BlockState now = level.getBlockState(p);
				if (!old.containsKey(p) && !Works.repairable(level, p, now)) {
					blocked = true;
				}
			}
			if (blocked) {
				gaps++;
				continue;
			}
			column(out, openings, tier, s, base, top);
		}
		return new Plan(out, openings, Math.max(0, countGates(ring)), gaps);
	}

	private static int countGates(List<Spot> ring) {
		int n = 0;
		int prev = -1;
		for (Spot s : ring) {
			if (s.gate == 0 && prev != 0) {
				n++;
			}
			prev = s.gate;
		}
		return n;
	}

	/** One column of the wall in the tier's style. */
	private static void column(Map<BlockPos, BlockState> out, List<BlockPos> openings, int tier, Spot s, int base, int top) {
		BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
		BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		BlockState bricks = Blocks.STONE_BRICKS.defaultBlockState();
		BlockState lantern = Blocks.LANTERN.defaultBlockState();
		boolean merlon = s.index % 2 == 0;
		boolean post = s.gate == 1 || s.corner || tier == 1 && s.index % 4 == 0;
		if (s.gate == 0) {
			// the gateway: three high and clear; from tier II a lintel over it
			for (int y = base; y < base + 3; y++) {
				openings.add(new BlockPos(s.x, y, s.z));
			}
			if (tier == 1) {
				return;
			}
			for (int y = base + 3; y < top; y++) {
				out.put(new BlockPos(s.x, y, s.z), tier == 2 ? planks : bricks);
			}
			if (merlon) {
				out.put(new BlockPos(s.x, top, s.z), tier == 2 ? cobble : bricks);
			}
			return;
		}
		switch (tier) {
			case 1 -> {
				for (int y = base; y < top; y++) {
					out.put(new BlockPos(s.x, y, s.z), planks);
				}
				if (post) {
					out.put(new BlockPos(s.x, top, s.z), planks);
					out.put(new BlockPos(s.x, top + 1, s.z), fence);
				} else {
					out.put(new BlockPos(s.x, top, s.z), fence);
				}
			}
			case 2 -> {
				for (int y = base; y < top; y++) {
					out.put(new BlockPos(s.x, y, s.z), y < base + 2 || post ? cobble : planks);
				}
				if (post) {
					out.put(new BlockPos(s.x, top, s.z), cobble);
					out.put(new BlockPos(s.x, top + 1, s.z), s.gate == 1 ? lantern : cobble);
				} else if (merlon) {
					out.put(new BlockPos(s.x, top, s.z), cobble);
				}
			}
			default -> {
				int t = s.corner ? top + 3 : top;
				for (int y = base; y < t; y++) {
					out.put(new BlockPos(s.x, y, s.z), bricks);
				}
				if (s.corner) {
					out.put(new BlockPos(s.x, t, s.z), bricks);
					out.put(new BlockPos(s.x, t + 1, s.z), Blocks.STONE_BRICK_WALL.defaultBlockState());
				} else if (s.gate == 1) {
					out.put(new BlockPos(s.x, t, s.z), bricks);
					out.put(new BlockPos(s.x, t + 1, s.z), lantern);
				} else if (merlon) {
					out.put(new BlockPos(s.x, t, s.z), bricks);
				}
			}
		}
	}

	/** The ground under the wall: the highest floor, not counting the wall that stands there now (its lintels and parapets). */
	private static int ground(ServerLevel level, Map<BlockPos, BlockState> wall, int x, int z) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
		while (y > level.getMinY()) {
			p.setY(y);
			BlockState st = level.getBlockState(p);
			if (!wall.containsKey(p) && Access.floor(st) && !st.is(net.minecraft.tags.BlockTags.LOGS)) {
				return y;
			}
			y--;
		}
		return y;
	}

		/** Starts building (or rebuilding) the wall; returns the project, or null if there is nothing to do. */
	public static @Nullable Project start(ServerLevel level, City city, int tier, int[] rect) {
		Plan plan = plan(level, city, tier, rect);
		Map<BlockPos, BlockState> old = Works.cells(city.wall);
		List<Project.Job> down = new ArrayList<>();
		List<Project.Job> clear = new ArrayList<>();
		List<Project.Job> up = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> e : old.entrySet()) {
			BlockState t = plan.blocks().get(e.getKey());
			if (t == null || t.getBlock() != e.getValue().getBlock()) {
				down.add(new Project.Job("DEMOLISH", e.getKey(), Works.text(e.getValue())));
			}
		}
		for (Map.Entry<BlockPos, BlockState> e : plan.blocks().entrySet()) {
			BlockState o = old.get(e.getKey());
			if (o == null) {
				clear.add(new Project.Job("CLEAR", e.getKey(), "minecraft:air"));
			}
			if (o == null || o.getBlock() != e.getValue().getBlock()) {
				up.add(new Project.Job("BLOCK", e.getKey(), Works.text(e.getValue())));
			}
		}
		for (BlockPos p : plan.openings()) {
			clear.add(new Project.Job("CLEAR", p, "minecraft:air"));
		}
		down.sort((a, b) -> Integer.compare(b.p.getY(), a.p.getY()));
		clear.sort((a, b) -> Integer.compare(b.p.getY(), a.p.getY()));
		Works.sortBottomUp(up);
		Project p = new Project(Project.WALL, "wall", city.wallTier == 0 ? "Town wall (" + style(tier) + ")"
			: tier > city.wallTier ? "Town wall to tier " + tier + " (" + style(tier) + ")" : "Town wall moved outwards");
		p.jobs.addAll(down);
		p.jobs.addAll(clear);
		p.jobs.addAll(up);
		p.tier = tier;
		p.rect = rect;
		for (Map.Entry<BlockPos, BlockState> e : plan.blocks().entrySet()) {
			p.plan.add(Works.cell(e.getKey(), e.getValue()));
		}
		if (p.jobs.isEmpty()) {
			return null;
		}
		Project running = city.project(Project.WALL, "wall");
		if (running != null) {
			city.projects.remove(running);
		}
		city.projects.add(p);
		city.log(p.title + " planned: " + plan.gates() + " gates, about " + net.antwire.commerce.api.CommerceApi.format(Works.cost(p.jobs, 0)) + " in materials");
		return p;
	}

	/** Materials for a first wall of the town's tier, in cents (for the ledger; worked out at most once a day). */
	public static long estimate(ServerLevel level, City city) {
		long key = city.day * 1000L + city.buildings.size() * 10L + city.tier();
		if (city.wallEstimateKey != key) {
			Plan plan = plan(level, city, city.tier(), needed(city));
			long sum = 0;
			for (BlockState s : plan.blocks().values()) {
				var item = s.getBlock().asItem();
				if (item != net.minecraft.world.item.Items.AIR) {
					sum += Materials.price(item);
				}
			}
			city.wallEstimate = sum;
			city.wallEstimateKey = key;
		}
		return city.wallEstimate;
	}

	/** The gateways of the wall as it stands (for checks): every block that must stay clear. */
	public static List<BlockPos> openings(ServerLevel level, City city) {
		return city.wallRect == null ? List.of() : plan(level, city, city.wallTier, city.wallRect).openings();
	}

		/** The builders are done: this is the wall now. */
	static void built(ServerLevel level, City city, Project p) {
		boolean first = city.wallTier == 0;
		city.wall = new ArrayList<>(p.plan);
		city.wallRect = p.rect;
		city.wallTier = p.tier;
		city.log(first ? "The town wall stands" : "The town wall was rebuilt (" + style(p.tier) + ")");
	}

	/** Once a day: does the wall still go round the whole town, and is it in the town's style? */
	public static void daily(ServerLevel level, City city) {
		if (city.wallTier == 0 || city.project(Project.WALL, "wall") != null) {
			return;
		}
		int tier = Math.max(city.wallTier, city.tier());
		int[] want = needed(city);
		int[] cur = city.wallRect;
		if (cur == null) {
			start(level, city, tier, want);
		} else if (!inside(cur, want, 0) || tier > city.wallTier) {
			start(level, city, tier, union(cur, want));
		}
	}

	/** The inspectors walk the wall: what is broken becomes a repair. */
	public static void inspect(ServerLevel level, City city, boolean order) {
		if (city.wallTier == 0 || city.project(Project.WALL, "wall") != null) {
			return;
		}
		List<Project.Job> jobs = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> e : Works.cells(city.wall).entrySet()) {
			BlockPos p = e.getKey();
			if (!level.isLoaded(p)) {
				continue;
			}
			BlockState now = level.getBlockState(p);
			if (!Works.same(e.getValue(), now) && Works.repairable(level, p, now)) {
				jobs.add(new Project.Job("BLOCK", p, Works.text(e.getValue())));
			}
		}
		// the gateways stay open: what grew or fell into them is cleared
		if (city.wallRect != null) {
			for (BlockPos g : plan(level, city, city.wallTier, city.wallRect).openings()) {
				BlockState now = level.getBlockState(g);
				if (level.isLoaded(g) && !now.isAir() && Access.clearable(level, g, now)) {
					jobs.add(new Project.Job("CLEAR", g, "minecraft:air"));
				}
			}
		}
		Works.queueRepair(city, "wall", "Repairs: town wall", jobs, order);
	}
}
