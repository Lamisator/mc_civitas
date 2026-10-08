package net.antwire.civitas.entity.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.antwire.civitas.CivitasConfig;
import net.antwire.civitas.city.Building;
import net.antwire.civitas.city.BuildingType;
import net.antwire.civitas.city.CitizenRecord;
import net.antwire.civitas.city.City;
import net.antwire.civitas.city.CityManager;
import net.antwire.civitas.city.Construction;
import net.antwire.civitas.city.Job;
import net.antwire.civitas.city.Production;
import net.antwire.civitas.city.Storage;
import net.antwire.civitas.city.Townlife;
import net.antwire.civitas.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Every job's work: one unit of it per task, so the brain can re-plan between them. */
public final class Work {
	private Work() {
	}

	/** How briskly they work: unhappy, tired people dawdle. */
	static double pace(City city, CitizenRecord r) {
		double p = 0.5 + r.happiness / 100.0;
		// better tools and room in a grander workplace
		Building w = city.building(r.workplace);
		if (w != null && r.job != Job.BUILDER) {
			p *= 1.0 + 0.2 * (w.tier - 1);
		}
		if (r.rest < 20) {
			p *= 0.7;
		}
		return Math.max(0.35, p);
	}

	public static @Nullable Task next(CitizenEntity npc, City city, CitizenRecord r) {
		if (r.job == Job.BUILDER) {
			// damage first, then new buildings, then the wall and the streets
			net.antwire.civitas.city.Project repair = net.antwire.civitas.city.Works.next(city, true);
			if (repair != null) {
				return new BuildProject(npc, city, r, repair);
			}
			Building site = city.nextConstruction();
			if (site != null) {
				return new Build(npc, city, r, site);
			}
			net.antwire.civitas.city.Project works = net.antwire.civitas.city.Works.next(city, false);
			if (works != null) {
				return new BuildProject(npc, city, r, works);
			}
			Building hall = city.townHall();
			return hall == null ? null : new Station(npc, city, r, hall);
		}
		Building w = city.building(r.workplace);
		if (w == null || !w.complete) {
			return null;
		}
		return switch (r.job) {
			case FARMER -> new Farm(npc, city, r, w);
			case MINER -> new Mine(npc, city, r, w, false);
			case LUMBERJACK -> Chop.create(npc, city, r, w);
			case BUTCHER -> Butcher.create(npc, city, r, w);
			case BAKER, TAVERN_KEEPER, BLACKSMITH, FACTORY_WORKER -> Production.task(npc, city, r, w);
			case SHERIFF -> Sheriff.create(npc, city, r, w);
			case SOLDIER -> Military.Guard.create(npc, city, r);
			default -> new Station(npc, city, r, w);
		};
	}

	public static Task forcedLabor(CitizenEntity npc, City city, CitizenRecord r) {
		Building mine = city.firstComplete(BuildingType.MINE);
		return mine == null ? new Tasks.Jail(npc, city, r) : new Mine(npc, city, r, mine, true);
	}

	// ------------------------------------------------------------------ desk jobs

	/** Stands at the workplace and does the paperwork (clerk, banker, idle builders). */
	public static class Station extends Task {
		private final Building b;

		public Station(CitizenEntity npc, City city, CitizenRecord rec, Building b) {
			super(npc, city, rec);
			this.b = b;
		}

		@Override
		public String kind() {
			return "station";
		}

		@Override
		protected void run() {
			BlockPos work = this.b.mark("work");
			if (work == null) {
				work = this.b.entrance();
			}
			if (!this.walk(work, 1.2)) {
				return;
			}
			BlockPos wb = this.b.mark("workblock");
			if (wb != null) {
				this.look(wb);
			}
			if (this.ticks % 90 == 0) {
				this.npc.swingArm();
			}
			if (this.ticks > 400) {
				this.done = true;
			}
		}
	}

	// ------------------------------------------------------------------ builders

	public static class Build extends Task {
		private static final double REACH = 16;
		private final Building site;
		private int cooldown;

		public Build(CitizenEntity npc, City city, CitizenRecord rec, Building site) {
			super(npc, city, rec);
			this.site = site;
		}

		@Override
		public String kind() {
			return "build";
		}

		@Override
		public void start() {
			this.npc.hold(new ItemStack(Items.IRON_SHOVEL));
		}

		@Override
		protected void run() {
			if (this.site.complete && !this.site.upgrading() || !this.city.buildings.contains(this.site)) {
				this.done = true;
				return;
			}
			if (--this.cooldown > 0) {
				return;
			}
			List<Construction.Step> steps = Construction.steps(this.site);
			int skips = 0;
			while (this.site.progress < steps.size() && skips < 200) {
				Construction.Step step = steps.get(this.site.progress);
				if (!Construction.needed(this.level(), step)) {
					this.site.progress++;
					skips++;
					continue;
				}
				Vec3 eye = this.npc.getEyePosition();
				if (eye.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(step.pos())) > REACH) {
					this.approach(step.pos());
					return;
				}
				Construction.Result r = Construction.apply(this.level(), this.city, this.site, step);
				if (r == Construction.Result.NO_MONEY) {
					if (this.site.stalled.isEmpty()) {
						this.city.log("Construction of the " + this.site.type.title.toLowerCase() + " halted: the treasury can't pay for materials");
					}
					this.site.stalled = "Treasury can't pay for materials";
					this.npc.say("We need money for materials!");
					this.cooldown = 200;
					this.done = true;
					return;
				}
				this.site.stalled = "";
				this.site.progress++;
				if (r == Construction.Result.PLACED) {
					this.look(step.pos());
					this.npc.swingArm();
					this.npc.setAction(CitizenEntity.ACTION_WORK);
					this.cooldown = (int) Math.max(2, CivitasConfig.get().buildTicksPerBlock / pace(this.city, this.rec));
					break;
				}
				skips++;
			}
			if (this.site.progress >= steps.size()) {
				if (this.site.upgrading()) {
					Townlife.upgraded(this.level(), this.city, this.site);
				} else {
					Townlife.complete(this.level(), this.city, this.site);
				}
				this.npc.say("The " + this.site.type.title.toLowerCase() + " is finished!");
				this.done = true;
			}
			if (this.ticks > 1200) {
				this.done = true;
			}
		}

		/** Walks to a spot just outside the site, near the block to place (never into the walls going up). */
		private void approach(BlockPos pos) {
			AABB box = this.site.bounds();
			if (!box.inflate(1).contains(Vec3.atCenterOf(pos))) {
				// out on the way to the square: stand next to it
				BlockPos spot = Walker.standable(this.level(), pos.above(), 3);
				this.walk(spot == null ? pos.above() : spot, 1.5);
				return;
			}
			double cx = Math.max(box.minX, Math.min(box.maxX, pos.getX() + 0.5));
			double cz = Math.max(box.minZ, Math.min(box.maxZ, pos.getZ() + 0.5));
			double dW = cx - box.minX;
			double dE = box.maxX - cx;
			double dN = cz - box.minZ;
			double dS = box.maxZ - cz;
			double m = Math.min(Math.min(dW, dE), Math.min(dN, dS));
			if (m == dW) {
				cx = box.minX - 2;
			} else if (m == dE) {
				cx = box.maxX + 1;
			} else if (m == dN) {
				cz = box.minZ - 2;
			} else {
				cz = box.maxZ + 1;
			}
			int y = this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(cx), (int) Math.floor(cz));
			BlockPos spot = Walker.standable(this.level(), new BlockPos((int) Math.floor(cx), y, (int) Math.floor(cz)), 2);
			if (spot == null) {
				spot = new BlockPos((int) Math.floor(cx), y, (int) Math.floor(cz));
			}
			this.walk(spot, 1.5);
		}

		@Override
		public void stop() {
			super.stop();
		}
	}

	/** Repairs, the wall, paving, street lamps: a list of jobs like a construction site's steps. */
	public static class BuildProject extends Task {
		private static final double REACH = 12;
		private final net.antwire.civitas.city.Project project;
		private int cooldown;

		public BuildProject(CitizenEntity npc, City city, CitizenRecord rec, net.antwire.civitas.city.Project project) {
			super(npc, city, rec);
			this.project = project;
		}

		@Override
		public String kind() {
			return "build";
		}

		@Override
		public void start() {
			this.npc.hold(new ItemStack(this.project.repair() ? Items.IRON_AXE : Items.IRON_SHOVEL));
		}

		@Override
		protected void run() {
			if (!this.city.projects.contains(this.project)) {
				this.done = true;
				return;
			}
			if (--this.cooldown > 0) {
				return;
			}
			var jobs = this.project.jobs;
			int skips = 0;
			while (this.project.progress < jobs.size() && skips < 200) {
				net.antwire.civitas.city.Project.Job job = jobs.get(this.project.progress);
				if (!net.antwire.civitas.city.Works.needed(this.level(), job)) {
					this.project.progress++;
					skips++;
					continue;
				}
				// walk up to it; if there is no getting closer (a crater, a wall in the way), reach over from where they stand
				if (this.npc.getEyePosition().distanceTo(Vec3.atCenterOf(job.p)) > REACH && this.ticks < 400) {
					BlockPos spot = Walker.standable(this.level(), job.p.above(), 4);
					this.walk(spot == null ? job.p.above() : spot, 1.5);
					return;
				}
				Construction.Result r = net.antwire.civitas.city.Works.apply(this.level(), this.city, job, false);
				if (r == Construction.Result.NO_MONEY) {
					if (this.project.stalled.isEmpty()) {
						this.city.log(this.project.title + " halted: the treasury can't pay for materials");
					}
					this.project.stalled = "Treasury can't pay for materials";
					this.npc.say("We need money for materials!");
					this.cooldown = 200;
					this.done = true;
					return;
				}
				this.project.stalled = "";
				this.project.progress++;
				if (r == Construction.Result.PLACED) {
					this.look(job.p);
					this.npc.swingArm();
					this.npc.setAction(CitizenEntity.ACTION_WORK);
					this.cooldown = (int) Math.max(2, CivitasConfig.get().buildTicksPerBlock / pace(this.city, this.rec));
					break;
				}
				skips++;
			}
			if (this.project.progress >= jobs.size()) {
				net.antwire.civitas.city.Works.finished(this.level(), this.city, this.project);
				this.npc.say(this.project.repair() ? "All mended!" : "Done: " + this.project.title.toLowerCase(java.util.Locale.ROOT) + "!");
				this.done = true;
			}
			if (this.ticks > 1200) {
				this.done = true;
			}
		}
	}

	// ------------------------------------------------------------------ farmers

	public static class Farm extends Task {
		private final Building farm;
		private @Nullable BlockPos cell;
		private int cellIndex;
		private int working;

		public Farm(CitizenEntity npc, City city, CitizenRecord rec, Building farm) {
			super(npc, city, rec);
			this.farm = farm;
		}

		@Override
		public String kind() {
			return "farm";
		}

		@Override
		public void start() {
			List<BlockPos> cells = this.farm.marks("field");
			ServerLevel level = this.level();
			int n = cells.size();
			int offset = this.npc.getRandom().nextInt(Math.max(1, n));
			// ripe crops first, then empty ground, then anything to tend
			for (int pass = 0; pass < 3 && this.cell == null; pass++) {
				for (int k = 0; k < n; k++) {
					int i = (k + offset) % n;
					BlockPos p = cells.get(i);
					BlockState s = level.getBlockState(p);
					boolean hit = switch (pass) {
						case 0 -> s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s);
						case 1 -> s.isAir() || !level.getBlockState(p.below()).is(Blocks.FARMLAND);
						default -> s.getBlock() instanceof CropBlock;
					};
					if (hit) {
						this.cell = p;
						this.cellIndex = i;
						break;
					}
				}
			}
			this.npc.hold(new ItemStack(Items.IRON_HOE));
			if (this.cell == null) {
				this.done = true;
			}
		}

		private Block crop() {
			int m = this.cellIndex % 8;
			return m == 6 ? Blocks.CARROTS : m == 7 ? Blocks.POTATOES : Blocks.WHEAT;
		}

		@Override
		protected void run() {
			if (this.cell == null) {
				this.done = true;
				return;
			}
			if (this.working == 0) {
				if (!this.walk(this.cell, 1.9)) {
					if (this.ticks > 400) {
						this.done = true;
					}
					return;
				}
				this.working = (int) (24 / pace(this.city, this.rec));
			}
			this.look(this.cell);
			this.swing(8);
			if (--this.working > 0) {
				return;
			}
			ServerLevel level = this.level();
			BlockState s = level.getBlockState(this.cell);
			BlockPos soil = this.cell.below();
			if (!level.getBlockState(soil).is(Blocks.FARMLAND)) {
				if (Construction.soil(level.getBlockState(soil))) {
					level.setBlock(soil, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
					level.playSound(null, soil, SoundEvents.HOE_TILL.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
				}
			} else if (s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s)) {
				List<ItemStack> drops = Block.getDrops(s, level, this.cell, null, this.npc, new ItemStack(Items.IRON_HOE));
				boolean replanted = false;
				for (ItemStack d : drops) {
					if (!replanted && (d.is(Items.WHEAT_SEEDS) || d.is(Items.CARROT) || d.is(Items.POTATO))) {
						d.shrink(1);
						replanted = true;
					}
					if (!d.isEmpty()) {
						this.farm.producedToday += Townlife.store(level, this.city, this.farm, d);
					}
				}
				level.setBlock(this.cell, replanted ? crop.getStateForAge(0) : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				level.playSound(null, this.cell, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
			} else if (s.isAir()) {
				Block crop = this.crop();
				net.minecraft.world.item.Item seed = crop == Blocks.CARROTS ? Items.CARROT : crop == Blocks.POTATOES ? Items.POTATO : Items.WHEAT_SEEDS;
				if (Storage.take(level, this.farm, seed, 1) > 0 || this.city.day < 2) {
					level.setBlock(this.cell, crop.defaultBlockState(), Block.UPDATE_ALL);
					level.playSound(null, this.cell, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
				}
			} else if (s.getBlock() instanceof CropBlock crop && level.getRandom().nextDouble() < CivitasConfig.get().farmTendChance * 10) {
				level.setBlock(this.cell, crop.getStateForAge(Math.min(crop.getMaxAge(), crop.getAge(s) + 1)), Block.UPDATE_ALL);
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, this.cell.getX() + 0.5, this.cell.getY() + 0.5, this.cell.getZ() + 0.5, 4, 0.3, 0.2, 0.3, 0);
			}
			this.done = true;
		}
	}

	// ------------------------------------------------------------------ miners

	/** A mine's tunnels: a staircase down to the mining depth, a main gallery and side galleries every four blocks. */
	public static final class MinePlan {
		public record Step(List<BlockPos> blocks, BlockPos stand, boolean torch, Direction wall) {
		}

		private static final int GALLERY = 40;
		private static final int BRANCH = 12;

		public static int size(ServerLevel level, Building mine) {
			BlockPos start = mine.mark("shaft");
			if (start == null) {
				return 0;
			}
			int depth = depth(level, start);
			return depth + GALLERY + (GALLERY / 4) * BRANCH * 2;
		}

		static int depth(ServerLevel level, BlockPos start) {
			int target = Math.max(CivitasConfig.get().mineDepthY, level.getMinY() + 6);
			return Math.max(4, start.getY() - target);
		}

		public static @Nullable Step step(ServerLevel level, Building mine, int index) {
			BlockPos start = mine.mark("shaft");
			if (start == null) {
				return null;
			}
			Direction dir = mine.rot().rotate(Direction.NORTH);
			Direction side = dir.getClockWise();
			int depth = depth(level, start);
			if (index < depth) {
				BlockPos f = start.relative(dir, index).below(index);
				BlockPos prev = index == 0 ? start.relative(dir.getOpposite()).above() : start.relative(dir, index - 1).below(index - 1);
				return new Step(List.of(f.above(2), f.above(), f), prev, index % 6 == 5, side);
			}
			BlockPos bottom = start.relative(dir, depth - 1).below(depth - 1);
			int k = index - depth;
			// the gallery, with a pair of side galleries after every fourth block
			int seg = BRANCH * 2 + 4;
			int block = k / seg;
			int within = k % seg;
			if (block * 4 >= GALLERY) {
				return null;
			}
			if (within < 4) {
				int j = block * 4 + within + 1;
				BlockPos t = bottom.relative(dir, j);
				return new Step(List.of(t.above(), t), bottom.relative(dir, j - 1), j % 6 == 0, side);
			}
			int b = within - 4;
			Direction way = b < BRANCH ? side : side.getOpposite();
			int len = b % BRANCH + 1;
			BlockPos root = bottom.relative(dir, block * 4 + 4);
			BlockPos t = root.relative(way, len);
			return new Step(List.of(t.above(), t), root.relative(way, len - 1), len % 6 == 0, dir);
		}
	}

	public static class Mine extends Task {
		private final Building mine;
		private final boolean forced;
		private @Nullable BlockPos target;
		private int progress;
		private int needed;

		public Mine(CitizenEntity npc, City city, CitizenRecord rec, Building mine, boolean forced) {
			super(npc, city, rec);
			this.mine = mine;
			this.forced = forced;
		}

		@Override
		public String kind() {
			return "mine";
		}

		@Override
		public void start() {
			this.npc.hold(new ItemStack(this.forced ? Items.STONE_PICKAXE : Items.IRON_PICKAXE));
			if (this.forced) {
				this.npc.say("Forced to dig for nothing...");
			}
		}

		@Override
		protected void run() {
			ServerLevel level = this.level();
			if (this.forced && !Brain.workTime(this.city.policies, CityManager.timeOfDay(level))) {
				this.done = true;
				return;
			}
			MinePlan.Step step = MinePlan.step(level, this.mine, this.mine.digIndex);
			if (step == null) {
				if (this.ticks % 600 == 1) {
					this.npc.say("The mine is worked out.");
				}
				this.done = this.ticks > 600;
				return;
			}
			if (this.target == null) {
				for (BlockPos p : step.blocks()) {
					BlockState s = level.getBlockState(p);
					if (!s.isAir() && !s.is(Blocks.TORCH) && !s.is(Blocks.WALL_TORCH)) {
						this.target = p;
						this.progress = 0;
						float hardness = s.getDestroySpeed(level, p);
						if (hardness < 0) {
							// bedrock: this way is closed, skip the step
							this.mine.digIndex++;
							this.target = null;
							return;
						}
						this.needed = (int) Math.max(6, hardness * 9 / pace(this.city, this.rec));
						break;
					}
				}
				if (this.target == null) {
					if (step.torch()) {
						BlockPos torch = step.blocks().get(0).below(step.blocks().size() == 3 ? 1 : 0);
						if (level.getBlockState(torch).isAir() && level.getBlockState(torch.relative(step.wall())).isSolid()) {
							level.setBlock(torch, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, step.wall().getOpposite()), Block.UPDATE_ALL);
						}
					}
					this.mine.digIndex++;
					if (this.ticks > 1200) {
						this.done = true;
					}
					return;
				}
			}
			if (this.npc.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(this.target)) > 4.5) {
				if (!this.walk(step.stand(), 1.2) && this.ticks > 900) {
					this.done = true;
				}
				return;
			}
			this.look(this.target);
			this.swing(6);
			this.progress++;
			level.destroyBlockProgress(this.npc.getId(), this.target, Math.min(9, this.progress * 10 / Math.max(1, this.needed)));
			if (this.progress < this.needed) {
				return;
			}
			BlockState s = level.getBlockState(this.target);
			level.destroyBlockProgress(this.npc.getId(), this.target, -1);
			if (!s.getFluidState().isEmpty()) {
				level.setBlock(this.target, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
			} else {
				for (ItemStack d : Block.getDrops(s, level, this.target, level.getBlockEntity(this.target), this.npc, new ItemStack(Items.IRON_PICKAXE))) {
					if (this.forced) {
						Townlife.store(level, this.city, this.mine, d);
					} else {
						this.mine.producedToday += Townlife.store(level, this.city, this.mine, d);
					}
				}
				level.setBlock(this.target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				level.playSound(null, this.target, s.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, 0.9F);
				if (s.is(net.minecraft.tags.BlockItemTags.DIAMOND_ORES.block())) {
					this.npc.say("Diamonds!");
					this.city.log(this.rec.name + " struck diamonds in the mine");
				}
			}
			// seal water and lava that would flood the tunnel
			for (Direction d : Direction.values()) {
				BlockPos n = this.target.relative(d);
				if (!level.getFluidState(n).isEmpty() && d != Direction.DOWN) {
					level.setBlock(n, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
			this.target = null;
			if (this.ticks > 2400) {
				this.done = true;
			}
		}

		@Override
		public void stop() {
			super.stop();
			if (this.target != null) {
				this.level().destroyBlockProgress(this.npc.getId(), this.target, -1);
			}
		}
	}

	// ------------------------------------------------------------------ lumberjacks

	public static class Chop extends Task {
		private final Building mill;
		private final List<BlockPos> logs;
		private final BlockPos base;
		private int index;
		private int cooldown;
		private final Block sapling;

		Chop(CitizenEntity npc, City city, CitizenRecord rec, Building mill, BlockPos base, List<BlockPos> logs, Block sapling) {
			super(npc, city, rec);
			this.mill = mill;
			this.base = base;
			this.logs = logs;
			this.sapling = sapling;
		}

		static @Nullable Task create(CitizenEntity npc, City city, CitizenRecord rec, Building mill) {
			ServerLevel level = (ServerLevel) npc.level();
			BlockPos c = mill.center();
			for (int tries = 0; tries < 60; tries++) {
				int x = c.getX() + npc.getRandom().nextInt(57) - 28;
				int z = c.getZ() + npc.getRandom().nextInt(57) - 28;
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
				BlockPos p = new BlockPos(x, top, z);
				BlockState s = level.getBlockState(p);
				if (!s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS)) {
					continue;
				}
				// down to the trunk
				BlockPos trunk = null;
				for (int dy = 0; dy < 24; dy++) {
					BlockPos q = p.below(dy);
					BlockState qs = level.getBlockState(q);
					if (qs.is(BlockTags.LOGS)) {
						trunk = q;
					} else if (trunk != null || (!qs.is(BlockTags.LEAVES) && !qs.isAir())) {
						break;
					}
				}
				if (trunk == null || !Construction.soil(level.getBlockState(trunk.below()))) {
					continue;
				}
				boolean inTown = false;
				for (Building b : city.buildings) {
					if (b.bounds().inflate(1).contains(net.minecraft.world.phys.Vec3.atCenterOf(trunk))) {
						inTown = true;
						break;
					}
				}
				if (inTown) {
					continue;
				}
				List<BlockPos> logs = connected(level, trunk);
				if (logs.size() < 3) {
					continue;
				}
				return new Chop(npc, city, rec, mill, trunk, logs, saplingFor(level.getBlockState(trunk).getBlock()));
			}
			return new Station(npc, city, rec, mill);
		}

		static List<BlockPos> connected(ServerLevel level, BlockPos start) {
			List<BlockPos> out = new ArrayList<>();
			Set<BlockPos> seen = new HashSet<>();
			ArrayDeque<BlockPos> q = new ArrayDeque<>();
			q.add(start);
			seen.add(start);
			while (!q.isEmpty() && out.size() < 64) {
				BlockPos p = q.poll();
				out.add(p);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dy = 0; dy <= 1; dy++) {
						for (int dz = -1; dz <= 1; dz++) {
							BlockPos n = p.offset(dx, dy, dz);
							if (seen.add(n) && level.getBlockState(n).is(BlockTags.LOGS)) {
								q.add(n);
							}
						}
					}
				}
			}
			out.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
			return out;
		}

		static Block saplingFor(Block log) {
			String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(log).getPath();
			if (id.startsWith("spruce")) {
				return Blocks.SPRUCE_SAPLING;
			}
			if (id.startsWith("birch")) {
				return Blocks.BIRCH_SAPLING;
			}
			if (id.startsWith("dark_oak")) {
				return Blocks.DARK_OAK_SAPLING;
			}
			if (id.startsWith("jungle")) {
				return Blocks.JUNGLE_SAPLING;
			}
			if (id.startsWith("acacia")) {
				return Blocks.ACACIA_SAPLING;
			}
			if (id.startsWith("cherry")) {
				return Blocks.CHERRY_SAPLING;
			}
			return Blocks.OAK_SAPLING;
		}

		@Override
		public String kind() {
			return "chop";
		}

		@Override
		public void start() {
			this.npc.hold(new ItemStack(Items.IRON_AXE));
		}

		@Override
		protected void run() {
			ServerLevel level = this.level();
			if (this.npc.position().distanceTo(Vec3.atBottomCenterOf(this.base)) > 3.5) {
				BlockPos spot = Walker.standable(level, this.base, 2);
				if (spot == null || (!this.walk(spot, 1.5) && this.ticks > 700)) {
					this.done = this.ticks > 700;
				}
				return;
			}
			if (--this.cooldown > 0) {
				this.swing(5);
				return;
			}
			while (this.index < this.logs.size() && !level.getBlockState(this.logs.get(this.index)).is(BlockTags.LOGS)) {
				this.index++;
			}
			if (this.index >= this.logs.size()) {
				if (level.getBlockState(this.base).isAir() && Construction.soil(level.getBlockState(this.base.below()))) {
					level.setBlock(this.base, this.sapling.defaultBlockState(), Block.UPDATE_ALL);
				}
				this.done = true;
				return;
			}
			BlockPos log = this.logs.get(this.index++);
			BlockState s = level.getBlockState(log);
			this.look(log);
			for (ItemStack d : Block.getDrops(s, level, log, null, this.npc, new ItemStack(Items.IRON_AXE))) {
				this.mill.producedToday += Townlife.store(level, this.city, this.mill, d);
			}
			level.setBlock(log, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			level.playSound(null, log, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.0F, 0.9F);
			this.cooldown = (int) (14 / pace(this.city, this.rec));
		}
	}

	// ------------------------------------------------------------------ butchers

	public static class Butcher extends Task {
		private final Building shop;
		private @Nullable Animal victim;

		Butcher(CitizenEntity npc, City city, CitizenRecord rec, Building shop, @Nullable Animal victim) {
			super(npc, city, rec);
			this.shop = shop;
			this.victim = victim;
		}

		static Task create(CitizenEntity npc, City city, CitizenRecord rec, Building shop) {
			ServerLevel level = (ServerLevel) npc.level();
			// raw meat in store: cook it for the counter
			Task cook = Production.task(npc, city, rec, shop);
			if (cook instanceof Production.Craft) {
				return cook;
			}
			AABB pen = pen(shop);
			if (pen == null) {
				return new Station(npc, city, rec, shop);
			}
			List<Animal> herd = level.getEntitiesOfClass(Animal.class, pen, a -> a.isAlive());
			long adults = herd.stream().filter(a -> !a.isBaby()).count();
			if (herd.size() < 4) {
				// buy a calf or a piglet for the pen
				long price = 1200;
				if (net.antwire.commerce.api.CommerceApi.burn(shop.account(city), price, "Livestock")) {
					shop.costToday += price;
					EntityType<? extends Animal> kind = npc.getRandom().nextBoolean() ? net.minecraft.world.entity.EntityTypes.COW : net.minecraft.world.entity.EntityTypes.PIG;
					Animal a = kind.create(level, EntitySpawnReason.BREEDING);
					if (a != null) {
						Vec3 c = pen.getCenter();
						a.snapTo(c.x, pen.minY, c.z, npc.getRandom().nextFloat() * 360, 0);
						a.setPersistenceRequired();
						level.addFreshEntity(a);
					}
				}
			}
			if (adults >= 3) {
				Animal v = herd.stream().filter(a -> !a.isBaby()).findFirst().orElse(null);
				return new Butcher(npc, city, rec, shop, v);
			}
			return new Station(npc, city, rec, shop);
		}

		static @Nullable AABB pen(Building b) {
			List<BlockPos> m = b.marks("pen");
			if (m.size() < 2) {
				return null;
			}
			return new AABB(Vec3.atLowerCornerOf(m.get(0)), Vec3.atLowerCornerOf(m.get(1))).expandTowards(1, 2, 1).inflate(0.4, 0, 0.4)
				.move(m.get(0).getX() <= m.get(1).getX() ? 0 : 1, 0, m.get(0).getZ() <= m.get(1).getZ() ? 0 : 1);
		}

		@Override
		public String kind() {
			return "butcher";
		}

		@Override
		public void start() {
			this.npc.hold(new ItemStack(Items.IRON_AXE));
		}

		@Override
		protected void run() {
			if (this.victim == null || !this.victim.isAlive()) {
				// pick up what fell
				AABB pen = pen(this.shop);
				if (pen != null) {
					for (ItemEntity item : this.level().getEntitiesOfClass(ItemEntity.class, pen.inflate(1))) {
						this.shop.producedToday += Townlife.store(this.level(), this.city, this.shop, item.getItem());
						item.discard();
					}
				}
				this.done = this.ticks > 20 || this.victim == null;
				return;
			}
			if (this.npc.distanceTo(this.victim) > 2.2) {
				this.npc.getNavigation().moveTo(this.victim, 1.0);
				if (this.ticks > 600) {
					this.done = true;
				}
				return;
			}
			this.npc.getLookControl().setLookAt(this.victim);
			this.npc.swingArm();
			this.victim.hurtServer(this.level(), this.level().damageSources().mobAttack(this.npc), 100);
		}
	}

	// ------------------------------------------------------------------ sheriffs

	public static class Sheriff extends Task {
		private final Building office;
		private @Nullable LivingEntity target;
		private final boolean arrest;
		private @Nullable BlockPos beat;

		Sheriff(CitizenEntity npc, City city, CitizenRecord rec, Building office, @Nullable LivingEntity target, boolean arrest) {
			super(npc, city, rec);
			this.office = office;
			this.target = target;
			this.arrest = arrest;
		}

		static Task create(CitizenEntity npc, City city, CitizenRecord rec, Building office) {
			ServerLevel level = (ServerLevel) npc.level();
			// monsters near the town come first
			List<net.minecraft.world.entity.Mob> monsters = level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, npc.getBoundingBox().inflate(24),
				m -> m instanceof Enemy && m.isAlive() && city.contains(m.blockPosition()));
			if (!monsters.isEmpty()) {
				return new Sheriff(npc, city, rec, office, monsters.getFirst(), false);
			}
			CityManager m = CityManager.get();
			for (CitizenRecord c : city.citizens.values()) {
				if (c.status == CitizenRecord.Status.WANTED && m != null) {
					CitizenEntity e = m.entity(c.uuid);
					if (e != null && e.distanceTo(npc) < 64) {
						return new Sheriff(npc, city, rec, office, e, true);
					}
				}
			}
			return new Sheriff(npc, city, rec, office, null, false);
		}

		@Override
		public String kind() {
			return "sheriff";
		}

		@Override
		public void start() {
			this.npc.hold(new ItemStack(Items.IRON_SWORD));
			if (this.target == null) {
				List<Building> list = this.city.buildings.stream().filter(b -> b.complete).toList();
				this.beat = list.isEmpty() ? this.city.center : list.get(this.npc.getRandom().nextInt(list.size())).entrance();
			} else if (this.arrest) {
				this.npc.say("Stop right there, " + this.target.getName().getString() + "!");
			}
		}

		@Override
		protected void run() {
			if (this.target == null) {
				if (this.beat == null || this.walk(this.beat, 2) || this.ticks > 600) {
					this.done = true;
				}
				return;
			}
			if (!this.target.isAlive() || this.ticks > 900) {
				this.done = true;
				return;
			}
			this.npc.getLookControl().setLookAt(this.target);
			double d = this.npc.distanceTo(this.target);
			if (d > 1.8) {
				this.npc.getNavigation().moveTo(this.target, 1.35);
				return;
			}
			this.npc.getNavigation().stop();
			if (this.arrest && this.target instanceof CitizenEntity suspect) {
				CitizenRecord r = suspect.record();
				if (r != null) {
					Townlife.arrest(this.level(), this.city, r, suspect, this.rec.name);
				}
				this.done = true;
			} else if (this.ticks % 12 == 0) {
				this.npc.swingArm();
				this.target.hurtServer(this.level(), this.level().damageSources().mobAttack(this.npc), 7);
			}
		}
	}
}
