package net.antwire.civitas.entity.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.antwire.civitas.CivitasConfig;
import net.antwire.civitas.city.Building;
import net.antwire.civitas.city.BuildingType;
import net.antwire.civitas.city.CitizenRecord;
import net.antwire.civitas.city.City;
import net.antwire.civitas.city.CityManager;
import net.antwire.civitas.city.Job;
import net.antwire.civitas.compat.Arms;
import net.antwire.civitas.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The town's defence. Soldiers from the barracks stand sentry and patrol in two watches (day and night) and turn out
 * for any alarm: monsters inside the town, raiders, and anyone - mob or player - who has hurt a citizen. Townsfolk run
 * from danger. Now and then raiders come for a prosperous town.
 */
public final class Military {
	/** How long someone who hurt a citizen stays an enemy of the town, ticks. */
	private static final int GRUDGE_MOB = 2400;
	private static final int GRUDGE_PLAYER = 6000;

	/** Per-town, not saved: who is hostile, what the soldiers see, an incoming raid. */
	static final class Watch {
		final Map<UUID, Long> hostile = new HashMap<>();
		final List<UUID> raiders = new ArrayList<>();
		final java.util.Set<UUID> warned = new java.util.HashSet<>();
		List<LivingEntity> threats = List.of();
		long seenAt = -1;
		long lastAlarm = -100000;
		long raidAt = -1;
		int raidersKilled;
		int raidSize;
	}

	private static final Map<UUID, Watch> WATCH = new HashMap<>();
	/** Rounds left in each soldier's magazine. */
	private static final Map<UUID, Integer> MAG = new HashMap<>();

	private Military() {
	}

	static Watch watch(City city) {
		return WATCH.computeIfAbsent(city.id, k -> new Watch());
	}

	public static void clear() {
		WATCH.clear();
		MAG.clear();
	}

	// ------------------------------------------------------------------ who is an enemy

	static boolean inTown(City city, Vec3 p) {
		double r = city.radius + 16;
		double dx = p.x - city.center.getX();
		double dz = p.z - city.center.getZ();
		return dx * dx + dz * dz <= r * r && Math.abs(p.y - city.center.getY()) < 48;
	}

	/** Someone hurt a citizen: the town remembers. */
	public static void attacked(ServerLevel level, City city, CitizenEntity victim, @Nullable Entity attacker) {
		if (!(attacker instanceof LivingEntity enemy) || attacker == victim) {
			return;
		}
		// citizens brawling among themselves are the sheriff's business, and a soldier's stray round is an accident
		if (attacker instanceof CitizenEntity c && city.id.equals(c.cityId())) {
			return;
		}
		Watch w = watch(city);
		boolean player = attacker instanceof Player;
		// the town's own rulers are never fired upon by its soldiers
		if (player && (attacker.getUUID().equals(city.governor) || city.deputies.contains(attacker.getUUID()))) {
			if (!w.warned.contains(attacker.getUUID())) {
				w.warned.add(attacker.getUUID());
				city.log(attacker.getName().getString() + " struck " + victim.getName().getString() + " - the guards look away from the governor");
			}
			return;
		}
		Long before = w.hostile.put(attacker.getUUID(), level.getGameTime() + (player ? GRUDGE_PLAYER : GRUDGE_MOB));
		city.lastAttackDay = city.day;
		if (before == null && player) {
			city.log(attacker.getName().getString() + " attacked " + victim.getName().getString() + " - the town treats them as an enemy");
		}
		if (enemy instanceof Mob mob && mob.getTarget() == null) {
			mob.setTarget(victim);
		}
	}

	private static boolean hostile(LivingEntity e, Watch w, long now) {
		if (!e.isAlive() || e.isRemoved()) {
			return false;
		}
		if (e instanceof Player p && (p.isCreative() || p.isSpectator())) {
			return false;
		}
		Long until = w.hostile.get(e.getUUID());
		if (until != null && until > now) {
			return true;
		}
		if (e instanceof CitizenEntity) {
			return false;
		}
		if (e instanceof Enemy && e instanceof Mob m) {
			// an enderman or a piglin minding its own business is left alone
			return !(m instanceof NeutralMob n) || n.getPersistentAngerTarget() != null || m.getTarget() != null;
		}
		return false;
	}

	/** Everything hostile in or near the town right now (worked out once a tick per town). */
	public static List<LivingEntity> threats(ServerLevel level, City city) {
		Watch w = watch(city);
		long now = level.getGameTime();
		if (w.seenAt == now) {
			return w.threats;
		}
		w.seenAt = now;
		w.hostile.values().removeIf(t -> t <= now);
		double r = city.radius + 16;
		AABB area = new AABB(city.center).inflate(r, 48, r);
		List<LivingEntity> out = new ArrayList<>();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area, e -> e.isAlive() && !(e instanceof CitizenEntity) || w.hostile.containsKey(e.getUUID()))) {
			if (hostile(e, w, now) && inTown(city, e.position())) {
				out.add(e);
			}
		}
		w.threats = out;
		return out;
	}

	public static boolean alarm(ServerLevel level, City city) {
		return !threats(level, city).isEmpty();
	}

	static @Nullable LivingEntity nearest(ServerLevel level, City city, Entity from, double within) {
		LivingEntity best = null;
		double bestD = within * within;
		for (LivingEntity e : threats(level, city)) {
			double d = e.distanceToSqr(from);
			if (d < bestD) {
				bestD = d;
				best = e;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ the barracks

	/** Which soldier of their barracks this is (0..3): decides weapon and watch. */
	static int index(City city, CitizenRecord r) {
		Building b = city.building(r.workplace);
		return b == null ? 0 : Math.max(0, b.workers.indexOf(r.uuid));
	}

	/** Puts the soldier's kit on: weapon, helmet, body armour. */
	public static void equip(CitizenEntity npc, City city, CitizenRecord r) {
		String weapon = Arms.weapon(index(city, r));
		ItemStack hand = npc.getItemBySlot(EquipmentSlot.MAINHAND);
		ItemStack want = Arms.stack(weapon);
		if (hand.getItem() != want.getItem()) {
			npc.setItemSlot(EquipmentSlot.MAINHAND, want);
		}
		if (npc.getItemBySlot(EquipmentSlot.HEAD).getItem() != Arms.helmet()) {
			npc.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Arms.helmet()));
		}
		if (npc.getItemBySlot(EquipmentSlot.CHEST).getItem() != Arms.vest()) {
			npc.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Arms.vest()));
		}
	}

	/** Takes the kit off someone who left the army. */
	public static void unequip(CitizenEntity npc) {
		Item head = npc.getItemBySlot(EquipmentSlot.HEAD).getItem();
		Item chest = npc.getItemBySlot(EquipmentSlot.CHEST).getItem();
		if (head == Arms.helmet()) {
			npc.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
		}
		if (chest == Arms.vest()) {
			npc.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
		}
		if (Arms.isWeapon(npc.getItemBySlot(EquipmentSlot.MAINHAND).getItem())) {
			npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
	}

	/** Night watch: on duty from dusk to dawn (12000 to 2000), asleep in the barracks by day. */
	public static boolean onDuty(CitizenRecord r, int t, boolean dayShift) {
		return r.nightWatch ? t >= 12000 || t < 2000 : dayShift;
	}

	public static boolean restsByDay(CitizenRecord r, int t) {
		return r.job == Job.SOLDIER && r.nightWatch && t >= 2000 && t < 10000;
	}

	// ------------------------------------------------------------------ every second

	public static void tick(ServerLevel level, City city) {
		Watch w = watch(city);
		long now = level.getGameTime();
		List<LivingEntity> threats = threats(level, city);
		// the alarm bell, at most once a minute
		boolean serious = threats.size() >= 3 || !w.raiders.isEmpty() || threats.stream().anyMatch(e -> e instanceof Player);
		if (!threats.isEmpty() && serious && now - w.lastAlarm > 1200) {
			w.lastAlarm = now;
			Building barracks = city.firstComplete(BuildingType.BARRACKS);
			if (barracks != null && barracks.mark("bell") != null) {
				Arms.noisyBell(level, barracks.mark("bell"));
			}
		}
		// raiders head for the square
		if (!w.raiders.isEmpty()) {
			Iterator<UUID> it = w.raiders.iterator();
			while (it.hasNext()) {
				Entity e = level.getEntity(it.next());
				if (e == null || !e.isAlive()) {
					if (e != null && !e.isAlive()) {
						w.raidersKilled++;
					}
					it.remove();
					continue;
				}
				if (e instanceof Mob m && m.getTarget() == null && m.getNavigation().isDone()) {
					m.getNavigation().moveTo(city.center.getX() + 0.5, city.center.getY(), city.center.getZ() + 0.5, 1.0);
				}
			}
			if (w.raiders.isEmpty()) {
				city.log("The raid was beaten off (" + w.raidersKilled + " of " + w.raidSize + " raiders killed)");
				tell(level, city, "The raid on " + city.name + " was beaten off.");
			}
		}
		if (w.raidAt > 0 && now >= w.raidAt) {
			w.raidAt = -1;
			raid(level, city);
		}
	}

	/** Each morning: will raiders come tonight? Prosperous, sizeable towns draw them. */
	public static void daily(ServerLevel level, City city) {
		CivitasConfig cfg = CivitasConfig.get();
		if (cfg.raidChance <= 0 || city.population() < 8 || city.day < 4) {
			return;
		}
		Watch w = watch(city);
		if (w.raidAt < 0 && w.raiders.isEmpty() && level.getRandom().nextDouble() < cfg.raidChance) {
			// they come at dusk
			int t = CityManager.timeOfDay(level);
			w.raidAt = level.getGameTime() + Math.floorMod(12500 - t, 24000) + level.getRandom().nextInt(2000);
		}
	}

	private static void tell(ServerLevel level, City city, String text) {
		for (ServerPlayer p : level.players()) {
			if (inTown(city, p.position()) || p.getUUID().equals(city.governor)) {
				p.sendSystemMessage(Component.literal(text));
			}
		}
	}

	/** A band of raiders turns up at the edge of town and marches on the square. */
	public static int raid(ServerLevel level, City city) {
		Watch w = watch(city);
		int n = Math.min(10, 2 + city.population() / 6);
		double a = level.getRandom().nextDouble() * Math.PI * 2;
		double dist = city.radius + 14;
		int x = city.center.getX() + (int) (Math.cos(a) * dist);
		int z = city.center.getZ() + (int) (Math.sin(a) * dist);
		String from = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? (Math.cos(a) > 0 ? "east" : "west") : (Math.sin(a) > 0 ? "south" : "north");
		int spawned = 0;
		for (int i = 0; i < n; i++) {
			EntityType<? extends Mob> type = i % 3 == 2 ? EntityTypes.VINDICATOR : EntityTypes.PILLAGER;
			Mob m = type.create(level, EntitySpawnReason.EVENT);
			if (m == null) {
				continue;
			}
			int px = x + level.getRandom().nextInt(7) - 3;
			int pz = z + level.getRandom().nextInt(7) - 3;
			int py = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
			m.snapTo(px + 0.5, py, pz + 0.5, level.getRandom().nextFloat() * 360, 0);
			m.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(px, py, pz)), EntitySpawnReason.EVENT, null);
			m.setPersistenceRequired();
			level.addFreshEntity(m);
			w.raiders.add(m.getUUID());
			w.hostile.put(m.getUUID(), level.getGameTime() + 24000);
			spawned++;
		}
		w.raidSize = spawned;
		w.raidersKilled = 0;
		city.lastAttackDay = city.day;
		city.log(spawned + " raiders attack from the " + from + "!");
		tell(level, city, spawned + " raiders are attacking " + city.name + " from the " + from + "!");
		return spawned;
	}

	// ------------------------------------------------------------------ soldiers

	/** On duty: deal with the nearest enemy, or stand sentry and walk the rounds. */
	public static class Guard extends Task {
		private final Building barracks;
		private final String weapon;
		private @Nullable LivingEntity target;
		private @Nullable BlockPos post;
		private int atPost;
		private long reloadDone;
		private boolean reloading;
		private double nextShot;
		private int burst;
		private int sidestep;

		Guard(CitizenEntity npc, City city, CitizenRecord rec, Building barracks) {
			super(npc, city, rec);
			this.barracks = barracks;
			this.weapon = Arms.weapon(index(city, rec));
		}

		public static @Nullable Task create(CitizenEntity npc, City city, CitizenRecord rec) {
			Building b = city.building(rec.workplace);
			if (b == null || !b.complete || b.type != BuildingType.BARRACKS) {
				b = city.firstComplete(BuildingType.BARRACKS);
			}
			return b == null ? null : new Guard(npc, city, rec, b);
		}

		@Override
		public String kind() {
			return "guard";
		}

		@Override
		public void start() {
			equip(this.npc, this.city, this.rec);
			if (this.npc.isSleeping()) {
				this.npc.stopSleeping();
			}
			this.post = this.choosePost();
		}

		private BlockPos choosePost() {
			List<BlockPos> posts = new ArrayList<>(this.barracks.marks("post"));
			int i = index(this.city, this.rec);
			// the sentries stand by the barracks door; the others walk the rounds: the square, the edge of town, the workshops
			if (i < 2 && this.npc.getRandom().nextInt(3) > 0 && !posts.isEmpty()) {
				return posts.get(i % posts.size());
			}
			int pick = this.npc.getRandom().nextInt(3);
			if (pick == 0) {
				return this.city.center;
			}
			if (pick == 1) {
				double a = this.npc.getRandom().nextDouble() * Math.PI * 2;
				double r = this.city.radius * 0.8;
				int x = this.city.center.getX() + (int) (Math.cos(a) * r);
				int z = this.city.center.getZ() + (int) (Math.sin(a) * r);
				BlockPos edge = new BlockPos(x, this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
				BlockPos spot = Walker.standable(this.level(), edge, 3);
				return spot == null ? this.city.center : spot;
			}
			List<Building> built = this.city.buildings.stream().filter(b -> b.complete).toList();
			return built.isEmpty() ? this.city.center : built.get(this.npc.getRandom().nextInt(built.size())).entrance();
		}

		@Override
		protected void run() {
			if (this.ticks % 10 == 1 || this.target != null && !this.target.isAlive()) {
				LivingEntity t = nearest(this.level(), this.city, this.npc, Arms.range(this.weapon) + 24);
				if (t != this.target) {
					this.target = t;
					if (t != null && this.npc.getRandom().nextInt(3) == 0) {
						this.npc.say(t instanceof Player ? "Halt! Drop your weapon!" : "Contact!");
					}
				}
			}
			if (this.target != null) {
				this.engage(this.target);
				return;
			}
			this.npc.setAction(CitizenEntity.ACTION_NONE);
			if (this.post == null || this.ticks > 1600) {
				this.done = true;
				return;
			}
			if (this.atPost == 0 && !this.walk(this.post, 1.5)) {
				return;
			}
			// at the post: keep watch
			if (++this.atPost % 60 == 1) {
				double a = this.npc.getRandom().nextDouble() * Math.PI * 2;
				this.npc.getLookControl().setLookAt(this.npc.getX() + Math.cos(a) * 8, this.npc.getEyeY(), this.npc.getZ() + Math.sin(a) * 8);
			}
			if (this.atPost > 300 + this.npc.getRandom().nextInt(200)) {
				this.done = true;
			}
		}

		private void engage(LivingEntity t) {
			ServerLevel level = this.level();
			double d = this.npc.distanceTo(t);
			boolean see = this.npc.getSensing().hasLineOfSight(t);
			this.npc.getLookControl().setLookAt(t, 60, 60);
			if (d < 2.2) {
				// too close to shoot: the rifle butt
				this.npc.getNavigation().stop();
				this.npc.setAction(CitizenEntity.ACTION_NONE);
				if (this.ticks % 14 == 0) {
					this.npc.swingArm();
					t.hurtServer(level, level.damageSources().mobAttack(this.npc), 5);
				}
				return;
			}
			if (!see || d > Arms.range(this.weapon)) {
				this.npc.setAction(CitizenEntity.ACTION_NONE);
				if (this.ticks % 10 == 0 || this.npc.getNavigation().isDone()) {
					this.npc.getNavigation().moveTo(t, 1.3);
				}
				return;
			}
			Vec3 aim = t.getBoundingBox().getCenter();
			if (this.weapon.equals("bow")) {
				aim = aim.add(t.getDeltaMovement().scale(d / 2.4));
			}
			if (!this.clear(aim, t)) {
				// a friend in the line of fire: step aside
				this.npc.setAction(CitizenEntity.ACTION_NONE);
				if (this.sidestep-- <= 0) {
					Vec3 to = aim.subtract(this.npc.position()).normalize();
					Vec3 side = new Vec3(-to.z, 0, to.x).scale(this.npc.getRandom().nextBoolean() ? 3 : -3);
					this.npc.getNavigation().moveTo(this.npc.getX() + side.x, this.npc.getY(), this.npc.getZ() + side.z, 1.2);
					this.sidestep = 20;
				}
				return;
			}
			this.npc.getNavigation().stop();
			this.npc.setAction(CitizenEntity.ACTION_AIM);
			Vec3 to = aim.subtract(this.npc.getEyePosition());
			float yaw = (float) (Math.atan2(to.z, to.x) * 180.0 / Math.PI) - 90.0F;
			this.npc.setYRot(yaw);
			this.npc.setYHeadRot(yaw);
			this.npc.setYBodyRot(yaw);
			this.shoot(level, aim, t);
		}

		/** Nobody but the enemy on the line of fire. */
		private boolean clear(Vec3 aim, LivingEntity t) {
			Vec3 eye = this.npc.getEyePosition();
			for (Entity e : this.level().getEntities(this.npc, new AABB(eye, aim).inflate(1.0))) {
				if (e == t || !(e instanceof LivingEntity) || threats(this.level(), this.city).contains(e)) {
					continue;
				}
				if ((e instanceof CitizenEntity || e instanceof Player) && e.getBoundingBox().inflate(0.4).clip(eye, aim).isPresent()) {
					return false;
				}
			}
			return true;
		}

		private void shoot(ServerLevel level, Vec3 aim, LivingEntity t) {
			long now = level.getGameTime();
			boolean bow = this.weapon.equals("bow");
			if (this.reloading) {
				if (now < this.reloadDone) {
					return;
				}
				this.reloading = false;
				MAG.put(this.rec.uuid, Arms.magazine(this.weapon));
				Arms.reloadSound(this.npc, true);
			}
			int mag = bow ? 1 : MAG.getOrDefault(this.rec.uuid, Arms.magazine(this.weapon));
			if (mag <= 0) {
				this.reloading = true;
				this.reloadDone = now + Arms.reloadTicks(this.weapon);
				Arms.reloadSound(this.npc, false);
				if (this.npc.getRandom().nextInt(3) == 0) {
					this.npc.say("Reloading!");
				}
				return;
			}
			if (now < this.nextShot) {
				return;
			}
			// steady when the target holds still, less so when it runs; the marksman takes his time
			double moving = t.getDeltaMovement().horizontalDistance();
			float inaccuracy = (float) Math.min(1.0, (this.weapon.equals("awm") ? 0.05 : 0.2) + moving * 2.0);
			// rounds that miss or go through the target never hurt the town's own people or a peaceful visitor
			List<LivingEntity> enemies = threats(level, this.city);
			Arms.fire(level, this.npc, this.weapon, aim, inaccuracy,
				e -> e instanceof CitizenEntity c && this.city.id.equals(c.cityId()) || e instanceof Player && !enemies.contains(e));
			this.npc.setAction(CitizenEntity.ACTION_AIM);
			if (!bow) {
				MAG.put(this.rec.uuid, mag - 1);
			}
			double interval = Arms.interval(this.weapon);
			this.nextShot = now + interval;
			// short bursts from the carbines
			if (this.weapon.equals("m4a1") && ++this.burst >= 3) {
				this.burst = 0;
				this.nextShot = now + 10 + this.npc.getRandom().nextInt(10);
			}
		}

		@Override
		public void stop() {
			super.stop();
			this.npc.setAction(CitizenEntity.ACTION_NONE);
		}
	}

	// ------------------------------------------------------------------ everyone else

	/** Danger close: run (townsfolk; soldiers and the sheriff stand their ground). */
	public static @Nullable Task danger(CitizenEntity npc, City city, CitizenRecord r) {
		if (r.job == Job.SOLDIER || r.job == Job.SHERIFF || !(npc.level() instanceof ServerLevel level)) {
			return null;
		}
		LivingEntity e = nearest(level, city, npc, 10);
		return e == null ? null : new Flee(npc, city, r, e);
	}

	public static class Flee extends Task {
		private final LivingEntity from;
		private @Nullable BlockPos to;

		Flee(CitizenEntity npc, City city, CitizenRecord rec, LivingEntity from) {
			super(npc, city, rec);
			this.from = from;
		}

		@Override
		public String kind() {
			return "flee";
		}

		@Override
		public void start() {
			this.npc.say(this.from instanceof Player ? "Help! Guards!" : "Run!");
		}

		@Override
		protected void run() {
			if (!this.from.isAlive() || this.npc.distanceTo(this.from) > 18 || this.ticks > 300) {
				this.done = true;
				return;
			}
			if (this.to == null || this.ticks % 30 == 0) {
				Vec3 away = this.npc.position().subtract(this.from.position()).multiply(1, 0, 1);
				if (away.lengthSqr() < 1.0E-4) {
					away = new Vec3(1, 0, 0);
				}
				Vec3 goal = this.npc.position().add(away.normalize().scale(12));
				BlockPos g = BlockPos.containing(goal);
				g = new BlockPos(g.getX(), this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g.getX(), g.getZ()), g.getZ());
				BlockPos spot = Walker.standable(this.level(), g, 3);
				this.to = spot == null ? g : spot;
			}
			this.run(this.to, 1.5);
		}
	}
}
