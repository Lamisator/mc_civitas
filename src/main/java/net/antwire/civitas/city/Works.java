package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.antwire.civitas.Civitas;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jspecify.annotations.Nullable;

/**
 * Keeping the town in shape: inspectors walk the town in rounds and compare every building (and the wall and the street
 * lamps) with its plan; what is broken, burnt or blown away becomes a repair job for the builders. Also the name signs by
 * the doors, and running the jobs of every {@link Project}.
 */
public final class Works {
	/** Ticks between two inspections (each looks at one building, the wall or the lamps). */
	public static final int INSPECT_TICKS = 100;
	private static final Map<String, BlockState> STATES = new HashMap<>();

	private Works() {
	}

	// ------------------------------------------------------------------ block states as text

	public static String text(BlockState s) {
		return BlockStateParser.serialize(s);
	}

	public static BlockState state(String s) {
		return STATES.computeIfAbsent(s, k -> {
			try {
				return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, k, false).blockState();
			} catch (Exception e) {
				Civitas.LOGGER.warn("Unknown block state {}", k);
				return Blocks.AIR.defaultBlockState();
			}
		});
	}

	/** "x y z state" (how the wall is stored). */
	static String cell(BlockPos p, BlockState s) {
		return p.getX() + " " + p.getY() + " " + p.getZ() + " " + text(s);
	}

	static Map<BlockPos, BlockState> cells(List<String> stored) {
		Map<BlockPos, BlockState> out = new LinkedHashMap<>();
		for (String line : stored) {
			String[] f = line.split(" ", 4);
			out.put(new BlockPos(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2])), state(f[3]));
		}
		return out;
	}

	// ------------------------------------------------------------------ which work comes next

	/** The next project for a builder: repairs (or only the rest, after the new buildings). */
	public static @Nullable Project next(City city, boolean repairs) {
		for (Project p : city.projects) {
			if (p.repair() == repairs && p.progress < p.jobs.size()) {
				return p;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ inspection

	/** Every few seconds: the inspectors look at the next part of the town. */
	public static void tick(ServerLevel level, City city, long gameTime) {
		if (gameTime % INSPECT_TICKS != 0 || city.buildings.isEmpty()) {
			return;
		}
		int parts = city.buildings.size() + 2;
		int i = Math.floorMod(city.inspectCursor++, parts);
		if (i < city.buildings.size()) {
			Building b = city.buildings.get(i);
			var box = b.plot();
			if (b.complete && !b.upgrading() && level.hasChunksAt((int) box.minX, (int) box.minZ, (int) box.maxX, (int) box.maxZ)) {
				inspect(level, city, b, false);
			}
		} else if (i == city.buildings.size()) {
			Walls.inspect(level, city, false);
		} else {
			Streets.inspectLamps(level, city, false);
		}
	}

	/** The plan says one thing, the world another: is that damage (or just the same thing in another state)? */
	static boolean same(BlockState want, BlockState now) {
		if (now.getBlock() == want.getBlock()) {
			return true;
		}
		// a cauldron with or without water, copper that weathered, soil that got trodden or overgrown
		if (want.getBlock() instanceof AbstractCauldronBlock && now.getBlock() instanceof AbstractCauldronBlock) {
			return true;
		}
		if (want.getBlock() instanceof WeatheringCopper && now.getBlock() instanceof WeatheringCopper) {
			return true;
		}
		return earth(want) && earth(now);
	}

	/** Dirt, grass, trodden paths and tilled fields turn into each other all the time: none of that is damage. */
	private static boolean earth(BlockState s) {
		return Construction.soil(s) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND);
	}

	/** Parts of a plan nobody repairs: what grows, what flows, and the mine's own staircase. */
	private static boolean ignored(Building b, BlockPos p, BlockState want) {
		if (want.isAir() || want.getBlock() instanceof LiquidBlock || want.is(BlockTags.CROPS) || want.is(BlockTags.SAPLINGS) || want.is(BlockTags.LEAVES)
			|| want.is(BlockTags.SMALL_FLOWERS) || want.is(BlockTags.FLOWERS) || want.is(Blocks.LIGHT)) {
			return true;
		}
		if (b.type == BuildingType.MINE) {
			BlockPos shaft = b.mark("shaft");
			if (shaft != null && p.getY() <= shaft.getY() + 2) {
				Direction dig = b.rot().rotate(Direction.NORTH);
				int dx = p.getX() - shaft.getX();
				int dz = p.getZ() - shaft.getZ();
				int ahead = dx * dig.getStepX() + dz * dig.getStepZ();
				int aside = Math.abs(dx * dig.getStepZ() - dz * dig.getStepX());
				return ahead >= -1 && aside <= 1;
			}
		}
		return false;
	}

	/** Can a builder put the planned block back here (it's gone, burnt, or nature's in the way), or is it someone's? */
	static boolean repairable(ServerLevel level, BlockPos p, BlockState now) {
		return now.isAir() || now.canBeReplaced() || !now.getFluidState().isEmpty() || Construction.natural(now) && level.getBlockEntity(p) == null;
	}

	/**
	 * Compares a building with its plan. Records how much of it stands; with damage it queues (or refreshes) a repair,
	 * when the town repairs by itself or when {@code order} says so. Returns the number of blocks to put back.
	 */
	public static int inspect(ServerLevel level, City city, Building b, boolean order) {
		Map<BlockPos, BlockState> plan = Construction.cells(b.blueprint(), b.origin, b.rot());
		List<Project.Job> jobs = new ArrayList<>();
		int total = 0;
		int missing = 0;
		int foreign = 0;
		java.util.Set<BlockPos> queued = new java.util.HashSet<>();
		for (Map.Entry<BlockPos, BlockState> e : plan.entrySet()) {
			BlockPos p = e.getKey();
			BlockState want = e.getValue();
			BlockState now = level.getBlockState(p);
			if (want.is(Blocks.LIGHT)) {
				// the hidden lights in sealed attics and cavities: put in at once, also into buildings from before they existed
				if (now.isAir()) {
					level.setBlock(p, want, Block.UPDATE_ALL);
				}
				continue;
			}
			// what can't stay where the plan puts it (a bell on a post, flowers on a path) is not repaired over and over
			if (ignored(b, p, want) || !want.canSurvive(level, p)) {
				continue;
			}
			total++;
			if (same(want, now)) {
				continue;
			}
			if (!repairable(level, p, now)) {
				foreign++;
				continue;
			}
			missing++;
			// doors and beds come back whole, from their lower half or foot
			BlockPos at = p;
			BlockState put = want;
			if (want.getBlock() instanceof DoorBlock && want.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
				at = p.below();
				put = plan.getOrDefault(at, want.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
			} else if (want.getBlock() instanceof BedBlock && want.getValue(BedBlock.PART) == BedPart.HEAD) {
				at = p.relative(want.getValue(BedBlock.FACING).getOpposite());
				put = plan.getOrDefault(at, want.setValue(BedBlock.PART, BedPart.FOOT));
			}
			if (queued.add(at)) {
				jobs.add(new Project.Job("BLOCK", at, text(put)));
			}
		}
		// the name sign by the door
		if (b.sign != null && !(level.getBlockState(b.sign).getBlock() instanceof net.minecraft.world.level.block.SignBlock)) {
			jobs.add(new Project.Job("SIGN", b.sign, ""));
			missing++;
		} else if (b.sign == null) {
			placeSign(level, city, b);
		}
		b.missing = missing;
		b.foreign = foreign;
		b.condition = total == 0 ? 100 : (int) Math.round(100.0 * (total - missing - foreign) / total);
		return queueRepair(city, "b:" + b.id, "Repairs: " + b.type.title.toLowerCase(Locale.ROOT) + " #" + b.id, jobs, order);
	}

	/** Puts a repair project in the queue (or brings an existing one up to date); returns the number of jobs. */
	static int queueRepair(City city, String target, String title, List<Project.Job> jobs, boolean order) {
		Project p = city.project(Project.REPAIR, target);
		if (jobs.isEmpty()) {
			if (p != null) {
				city.projects.remove(p);
			}
			return 0;
		}
		sortBottomUp(jobs);
		if (p == null) {
			if (!city.autoRepair && !order) {
				return jobs.size();
			}
			p = new Project(Project.REPAIR, target, title);
			city.projects.add(p);
			city.log(title.replace("Repairs: ", "Damage found: the ") + " (" + jobs.size() + " blocks) - the builders will repair it");
		}
		p.jobs = jobs;
		p.progress = 0;
		return jobs.size();
	}

	/** Bottom up, walls before what hangs on them (lanterns, signs, doors, panes). */
	static void sortBottomUp(List<Project.Job> jobs) {
		jobs.sort((a, b) -> {
			boolean aa = attachment(a);
			boolean bb = attachment(b);
			if (aa != bb) {
				return aa ? 1 : -1;
			}
			return Integer.compare(a.p.getY(), b.p.getY());
		});
	}

	private static boolean attachment(Project.Job j) {
		if (!j.k.equals("BLOCK")) {
			return j.k.equals("SIGN");
		}
		BlockState s = state(j.s);
		return !s.canOcclude() || s.getBlock() instanceof DoorBlock || s.getBlock() instanceof BedBlock;
	}

	/** What the materials of a list of jobs cost, in cents. */
	public static long cost(List<Project.Job> jobs, int from) {
		long sum = 0;
		for (int i = from; i < jobs.size(); i++) {
			Project.Job j = jobs.get(i);
			if (j.k.equals("BLOCK")) {
				Item item = state(j.s).getBlock().asItem();
				if (item != Items.AIR) {
					sum += Materials.price(item);
				}
			}
		}
		return sum;
	}

	// ------------------------------------------------------------------ doing the work

	/** Does the job still need doing? */
	public static boolean needed(ServerLevel level, Project.Job j) {
		BlockState now = level.getBlockState(j.p);
		if (j.k.equals("SIGN")) {
			return !(now.getBlock() instanceof net.minecraft.world.level.block.SignBlock);
		}
		if (!j.k.equals("BLOCK")) {
			return Construction.needed(level, step(j));
		}
		BlockState want = state(j.s);
		if (!same(want, now)) {
			return repairable(level, j.p, now);
		}
		// the other half of a door or bed
		if (want.getBlock() instanceof DoorBlock) {
			BlockState up = level.getBlockState(j.p.above());
			return !(up.getBlock() == want.getBlock()) && repairable(level, j.p.above(), up);
		}
		if (want.getBlock() instanceof BedBlock) {
			BlockPos head = j.p.relative(want.getValue(BedBlock.FACING));
			BlockState h = level.getBlockState(head);
			return !(h.getBlock() == want.getBlock()) && repairable(level, head, h);
		}
		return false;
	}

	static Construction.Step step(Project.Job j) {
		return new Construction.Step(Construction.Kind.valueOf(j.k), j.p, j.k.equals("SIGN") ? Blocks.AIR.defaultBlockState() : state(j.s));
	}

	/** Carries out one job, paying for the materials (or, with {@code free}, at once and for nothing). */
	public static Construction.Result apply(ServerLevel level, City city, Project.Job j, boolean free) {
		if (!needed(level, j)) {
			return Construction.Result.SKIPPED;
		}
		if (j.k.equals("SIGN")) {
			Building b = null;
			for (Building o : city.buildings) {
				if (j.p.equals(o.sign)) {
					b = o;
				}
			}
			if (b == null) {
				return Construction.Result.SKIPPED;
			}
			Item item = signBlock(b).asItem();
			if (!free && !Materials.take(city, item, 1)) {
				return Construction.Result.NO_MONEY;
			}
			b.sign = null;
			placeSign(level, city, b);
			return Construction.Result.PLACED;
		}
		if (!j.k.equals("BLOCK")) {
			if (free) {
				Construction.applyFree(level, city, step(j));
				return Construction.Result.PLACED;
			}
			return Construction.apply(level, city, null, step(j));
		}
		BlockState want = state(j.s);
		Item item = want.getBlock().asItem();
		if (!free && item != Items.AIR && !Materials.take(city, item, 1)) {
			return Construction.Result.NO_MONEY;
		}
		Construction.placeFull(level, j.p, want);
		if (!free) {
			level.playSound(null, j.p, want.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
		}
		return Construction.Result.PLACED;
	}

	/** A project's last job is done. */
	public static void finished(ServerLevel level, City city, Project p) {
		city.projects.remove(p);
		switch (p.kind) {
			case Project.WALL -> Walls.built(level, city, p);
			case Project.STREETS -> city.log("The ways are paved");
			case Project.LAMPS -> city.log("Street lamps light the ways");
			default -> {
				city.log(p.title.replace("Repairs: ", "Repaired: the "));
				if (p.target.startsWith("b:")) {
					Building b = city.building(Integer.parseInt(p.target.substring(2)));
					if (b != null) {
						inspect(level, city, b, false);
					}
				}
			}
		}
	}

	/** The complete command: every job of every project done at once, for free. */
	public static void completeAll(ServerLevel level, City city) {
		for (Project p : List.copyOf(city.projects)) {
			for (Project.Job j : p.jobs) {
				apply(level, city, j, true);
			}
			p.progress = p.jobs.size();
			finished(level, city, p);
		}
	}

	// ------------------------------------------------------------------ name signs

	private static Block signBlock(Building b) {
		return switch (b.tier) {
			case 3 -> Blocks.DARK_OAK_WALL_SIGN;
			case 2 -> Blocks.SPRUCE_WALL_SIGN;
			default -> Blocks.OAK_WALL_SIGN;
		};
	}

	/**
	 * Hangs the name sign beside the door, at eye height, facing out: what the building is, its tier, the town and its
	 * number. An old sign of the building is taken down first.
	 */
	public static void placeSign(ServerLevel level, City city, Building b) {
		BlockPos entrance = b.entrance();
		BlockPos door = b.mark("door");
		if (door == null) {
			door = entrance.relative(b.rot().rotate(Direction.NORTH));
		}
		Direction out = b.rot().rotate(Direction.SOUTH);
		int dx = entrance.getX() - door.getX();
		int dz = entrance.getZ() - door.getZ();
		if (dx != 0 || dz != 0) {
			out = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
		}
		if (b.sign != null && level.getBlockState(b.sign).getBlock() instanceof net.minecraft.world.level.block.SignBlock) {
			level.setBlock(b.sign, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		b.sign = null;
		for (int up = 1; up <= 2 && b.sign == null; up++) {
			for (Direction side : new Direction[]{out.getCounterClockWise(), out.getClockWise()}) {
				BlockPos wall = door.relative(side).above(up);
				BlockPos at = wall.relative(out);
				if (!level.getBlockState(wall).isFaceSturdy(level, wall, out) || !level.getBlockState(at).isAir() || Access.onAWay(city, at.getX(), at.getZ())
					&& at.getY() <= door.getY() + 1) {
					continue;
				}
				level.setBlock(at, signBlock(b).defaultBlockState().setValue(WallSignBlock.FACING, out), Block.UPDATE_ALL);
				if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
					String[] lines = signLines(city, b);
					Component[] msgs = new Component[4];
					for (int i = 0; i < 4; i++) {
						msgs[i] = i == 0 ? Component.literal(lines[i]).withStyle(ChatFormatting.BOLD) : Component.literal(lines[i]);
					}
					sign.setText(new SignText(List.of(msgs), List.of(msgs), b.tier == 3 ? DyeColor.WHITE : DyeColor.BLACK, b.tier == 3), net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
					sign.setWaxed(true);
				}
				b.sign = at;
				break;
			}
		}
		// open buildings (a farm, a forge): a sign on its own post beside the entrance
		int rot = switch (out) {
			case WEST -> 4;
			case NORTH -> 8;
			case EAST -> 12;
			default -> 0;
		};
		BlockState post = standingSign(b).defaultBlockState().setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION, rot);
		for (int ahead = 0; ahead <= 2 && b.sign == null; ahead++) {
			for (int k = 1; k <= 4 && b.sign == null; k++) {
				for (Direction side : new Direction[]{out.getCounterClockWise(), out.getClockWise()}) {
					for (int dy = -1; dy <= 1; dy++) {
						BlockPos at = entrance.relative(out, ahead).relative(side, k).above(dy);
						BlockState here = level.getBlockState(at);
						if (!(here.isAir() || here.canBeReplaced() && here.getFluidState().isEmpty()) || !post.canSurvive(level, at)
							|| Access.onAWay(city, at.getX(), at.getZ()) || !level.getBlockState(at.above()).isAir()) {
							continue;
						}
						level.setBlock(at, post, Block.UPDATE_ALL);
						write(level, city, b, at);
						b.sign = at;
						break;
					}
					if (b.sign != null) {
						break;
					}
				}
			}
		}
	}

	private static Block standingSign(Building b) {
		return switch (b.tier) {
			case 3 -> Blocks.DARK_OAK_SIGN;
			case 2 -> Blocks.SPRUCE_SIGN;
			default -> Blocks.OAK_SIGN;
		};
	}

	private static void write(ServerLevel level, City city, Building b, BlockPos at) {
		if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
			String[] lines = signLines(city, b);
			Component[] msgs = new Component[4];
			for (int i = 0; i < 4; i++) {
				msgs[i] = i == 0 ? Component.literal(lines[i]).withStyle(ChatFormatting.BOLD) : Component.literal(lines[i]);
			}
			sign.setText(new SignText(List.of(msgs), List.of(msgs), b.tier == 3 ? DyeColor.WHITE : DyeColor.BLACK, b.tier == 3),
				net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
			sign.setWaxed(true);
		}
	}

	static String[] signLines(City city, Building b) {
		String what = b.type == BuildingType.TOWN_HALL ? "Town Hall" : b.type.title;
		String tier = "Tier " + "I".repeat(Math.max(1, b.tier));
		String name = city.name.length() > 15 ? city.name.substring(0, 15) : city.name;
		return new String[]{what, b.type == BuildingType.TOWN_HALL ? name : "No. " + b.id, tier, b.type == BuildingType.TOWN_HALL ? "" : name};
	}
}
