package net.antwire.civitas.entity.ai;

import java.util.List;
import net.antwire.civitas.city.Building;
import net.antwire.civitas.city.BuildingType;
import net.antwire.civitas.city.CitizenRecord;
import net.antwire.civitas.city.City;
import net.antwire.civitas.city.CityManager;
import net.antwire.civitas.city.Storage;
import net.antwire.civitas.city.Townlife;
import net.antwire.civitas.entity.CitizenEntity;
import net.antwire.civitas.registry.ModItems;
import net.antwire.commerce.api.CommerceApi;
import net.antwire.commerce.block.entity.ShopBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** The everyday tasks every citizen has: sleeping, eating, a drink, idling - and protesting, stealing, begging, leaving. */
public final class Tasks {
	private Tasks() {
	}

	static int time(CitizenEntity npc) {
		return CityManager.timeOfDay(npc.level());
	}

	/** The spot in front of a building's shop counter. */
	static @Nullable BlockPos customerSpot(Building b) {
		BlockPos shop = b.mark("shop");
		if (shop == null) {
			return null;
		}
		Direction front = b.rot().rotate(Direction.SOUTH);
		return shop.relative(front);
	}

	// ------------------------------------------------------------------ wander

	public static class Wander extends Task {
		private final int duration;
		private @Nullable BlockPos spot;

		public Wander(CitizenEntity npc, City city, CitizenRecord rec, int duration) {
			super(npc, city, rec);
			this.duration = duration;
		}

		@Override
		public String kind() {
			return "wander";
		}

		@Override
		public void start() {
			BlockPos anchor = this.city.center;
			Building home = this.city.building(this.rec.home);
			if (home != null && home.complete && time(this.npc) > 9000 && this.npc.getRandom().nextBoolean()) {
				anchor = home.entrance();
			}
			BlockPos target = anchor.offset(this.npc.getRandom().nextInt(13) - 6, 0, this.npc.getRandom().nextInt(13) - 6);
			this.spot = Walker.standable(this.npc.level(), target, 3);
		}

		@Override
		protected void run() {
			if (this.spot != null && this.walk(this.spot, 1.2)) {
				this.spot = null;
			}
			if (this.spot == null && this.ticks % 60 == 0) {
				this.npc.getLookControl().setLookAt(this.npc.getX() + this.npc.getRandom().nextGaussian() * 4, this.npc.getEyeY(),
					this.npc.getZ() + this.npc.getRandom().nextGaussian() * 4);
			}
			if (this.ticks > this.duration) {
				this.done = true;
			}
		}
	}

	// ------------------------------------------------------------------ sleep

	public static class Sleep extends Task {
		private @Nullable BlockPos bed;
		private boolean asleep;

		public Sleep(CitizenEntity npc, City city, CitizenRecord rec) {
			super(npc, city, rec);
		}

		@Override
		public String kind() {
			return "sleep";
		}

		@Override
		public void start() {
			Building home = this.city.building(this.rec.home);
			int i = home == null ? 0 : Math.max(0, home.residents.indexOf(this.rec.uuid));
			if (this.rec.job == net.antwire.civitas.city.Job.SOLDIER && this.city.building(this.rec.workplace) instanceof Building barracks && barracks.complete
				&& !barracks.marks("bed").isEmpty()) {
				// soldiers sleep in their bunks
				home = barracks;
				i = Math.max(0, barracks.workers.indexOf(this.rec.uuid));
			}
			if (home != null && home.complete) {
				List<BlockPos> beds = home.marks("bed");
				for (int k = 0; k < beds.size(); k++) {
					BlockPos foot = beds.get((i + k) % beds.size());
					BlockState s = this.npc.level().getBlockState(foot);
					if (s.getBlock() instanceof BedBlock && !s.getValue(BedBlock.OCCUPIED)) {
						this.bed = foot;
						break;
					}
				}
			}
			this.rec.sleptInBed = false;
		}

		@Override
		protected void run() {
			if (this.bed != null && !this.asleep) {
				if (this.walk(this.bed, 1.6)) {
					BlockState s = this.npc.level().getBlockState(this.bed);
					if (s.getBlock() instanceof BedBlock) {
						BlockPos head = this.bed.relative(s.getValue(BedBlock.FACING));
						if (this.npc.startSleeping(head)) {
							this.asleep = true;
							this.rec.sleptInBed = true;
						}
					}
					if (!this.asleep) {
						this.bed = null;
					}
				}
			} else if (this.bed == null && this.ticks % 100 == 1) {
				// no bed: sleep rough by the town hall
				BlockPos spot = Walker.standable(this.npc.level(), this.city.center.offset(3, 0, -3), 4);
				if (spot != null) {
					this.walk(spot, 2);
				}
			}
			if (!this.asleep && this.bed == null) {
				this.npc.getNavigation().moveTo(this.city.center.getX() + 3, this.city.center.getY(), this.city.center.getZ() - 3, 0.8);
			}
			int t = time(this.npc);
			if (this.rec.job == net.antwire.civitas.city.Job.SOLDIER && this.rec.nightWatch) {
				if (!Military.restsByDay(this.rec, t)) {
					this.done = true;
				}
			} else if (t < Brain.sleepStart(this.city.policies) && !(this.city.policies.curfew && t >= 12500)) {
				this.done = true;
			}
		}

		@Override
		public void stop() {
			super.stop();
			if (this.npc.isSleeping()) {
				this.npc.stopSleeping();
			}
		}
	}

	// ------------------------------------------------------------------ food

	public static class BuyFood extends Task {
		private @Nullable Building shopBuilding;
		private int offer = -1;
		private int eating;
		private boolean rations;

		public BuyFood(CitizenEntity npc, City city, CitizenRecord rec) {
			super(npc, city, rec);
		}

		@Override
		public String kind() {
			return "food";
		}

		@Override
		public void start() {
			long money = CommerceApi.balance(this.rec.account(this.city));
			long best = Long.MAX_VALUE;
			for (Building b : this.city.buildings) {
				if (!b.complete || (b.type != BuildingType.BAKERY && b.type != BuildingType.BUTCHER)) {
					continue;
				}
				ShopBlockEntity shop = Storage.shop(this.level(), b);
				if (shop == null) {
					continue;
				}
				for (int i = 0; i < ShopBlockEntity.OFFERS; i++) {
					ItemStack t = shop.template(i);
					if (!t.isEmpty() && food(t.getItem()) > 0 && shop.stock(i) > 0 && shop.price(i) <= money && shop.price(i) < best) {
						best = shop.price(i);
						this.shopBuilding = b;
						this.offer = i;
					}
				}
			}
			if (this.shopBuilding == null && this.city.policies.rations && !this.rec.rationed) {
				this.rations = true;
			}
			if (this.shopBuilding == null && !this.rations) {
				this.npc.say(money < 150 ? "I can't afford bread..." : "There's no food anywhere!");
				this.rec.mood.merge("No food", -1.0, Double::sum);
				this.done = true;
			}
		}

		@Override
		protected void run() {
			if (this.eating > 0) {
				if (--this.eating % 4 == 0) {
					ItemStack food = this.npc.getMainHandItem();
					this.npc.playSound(SoundEvents.GENERIC_EAT.value(), 0.6F, 0.9F + this.npc.getRandom().nextFloat() * 0.2F);
					this.level().sendParticles(new ItemParticleOption(ParticleTypes.ITEM, food.getItem()), this.npc.getX(), this.npc.getEyeY() - 0.2, this.npc.getZ(), 4,
						0.15, 0.1, 0.15, 0.05);
				}
				if (this.eating == 0) {
					this.npc.hold(ItemStack.EMPTY);
					this.done = true;
				}
				return;
			}
			if (this.rations) {
				Building hall = this.city.townHall();
				BlockPos target = hall != null && hall.complete ? hall.entrance() : this.city.center;
				if (this.walk(target, 2.0)) {
					ItemStack bread = Townlife.ration(this.level(), this.city);
					if (bread.isEmpty()) {
						this.npc.say("Even the rations ran out...");
						this.done = true;
					} else {
						this.rec.rationed = true;
						this.eat(bread);
						this.npc.say("Thank you for the bread.");
					}
				}
				return;
			}
			BlockPos spot = this.shopBuilding == null ? null : customerSpot(this.shopBuilding);
			if (spot == null) {
				this.done = true;
				return;
			}
			if (this.walk(spot, 1.6)) {
				ShopBlockEntity shop = Storage.shop(this.level(), this.shopBuilding);
				if (shop == null) {
					this.done = true;
					return;
				}
				this.look(shop.getBlockPos());
				List<ItemStack> got = shop.sellToAccount(this.rec.account(this.city), this.offer, 1);
				if (got.isEmpty()) {
					this.npc.say("Sold out?!");
					this.done = true;
					return;
				}
				this.shopBuilding.revenueToday += shop.price(this.offer);
				this.eat(got.getFirst());
			}
		}

		private void eat(ItemStack food) {
			this.npc.hold(food.copyWithCount(1));
			this.rec.food = Math.min(100, this.rec.food + food(food.getItem()));
			this.rec.ateToday = true;
			this.eating = 32;
		}

		static int food(Item item) {
			if (item == Items.BREAD) {
				return 35;
			}
			if (item == Items.COOKED_BEEF || item == Items.COOKED_PORKCHOP) {
				return 55;
			}
			if (item == Items.BAKED_POTATO || item == Items.CARROT || item == Items.POTATO) {
				return 15;
			}
			if (item == Items.COOKIE || item == Items.PUMPKIN_PIE) {
				return 20;
			}
			return 0;
		}
	}

	// ------------------------------------------------------------------ tavern

	public static class Tavern extends Task {
		private final Building tavern;
		private boolean served;
		private @Nullable BlockPos seat;
		private int sitting;

		public Tavern(CitizenEntity npc, City city, CitizenRecord rec, Building tavern) {
			super(npc, city, rec);
			this.tavern = tavern;
		}

		@Override
		public String kind() {
			return "tavern";
		}

		@Override
		protected void run() {
			if (!this.served) {
				BlockPos spot = customerSpot(this.tavern);
				if (spot == null || this.walk(spot, 1.6)) {
					ShopBlockEntity shop = Storage.shop(this.level(), this.tavern);
					long price = Math.round(this.city.policies.alePrice * 100);
					boolean paid = false;
					if (shop != null) {
						for (int i = 0; i < ShopBlockEntity.OFFERS && !paid; i++) {
							if (shop.template(i).is(ModItems.ALE) && shop.stock(i) > 0) {
								paid = !shop.sellToAccount(this.rec.account(this.city), i, 1).isEmpty();
								if (paid) {
									this.tavern.revenueToday += shop.price(i);
								}
							}
						}
					}
					if (!paid && CommerceApi.transfer(this.rec.account(this.city), this.tavern.account(this.city), price, "A drink")) {
						paid = true;
						this.tavern.revenueToday += price;
					}
					if (!paid) {
						this.done = true;
						return;
					}
					this.served = true;
					this.npc.hold(new ItemStack(ModItems.ALE));
					List<BlockPos> seats = this.tavern.marks("seat");
					this.seat = seats.isEmpty() ? null : seats.get(this.npc.getRandom().nextInt(seats.size()));
					this.sitting = 300 + this.npc.getRandom().nextInt(400);
				}
				return;
			}
			if (this.seat != null && this.npc.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(this.seat)) > 0.9) {
				if (this.walk(this.seat, 0.6)) {
					this.npc.teleportTo(this.seat.getX() + 0.5, this.seat.getY() + 0.1, this.seat.getZ() + 0.5);
				}
				return;
			}
			this.npc.setAction(CitizenEntity.ACTION_SIT);
			this.npc.getNavigation().stop();
			if (this.ticks % 160 == 0) {
				String[] talk = {"Prost!", "Another round!", "Did you hear about the mayor?", "Good ale tonight.", "Cheers!"};
				this.npc.say(talk[this.npc.getRandom().nextInt(talk.length)]);
				this.npc.playSound(SoundEvents.GENERIC_DRINK.value(), 0.5F, 1.0F);
			}
			if (--this.sitting <= 0) {
				this.rec.visitedTavern = true;
				this.rec.happiness = Math.min(100, this.rec.happiness + 2);
				this.npc.hold(ItemStack.EMPTY);
				this.done = true;
			}
		}

		@Override
		public void stop() {
			super.stop();
			this.npc.hold(ItemStack.EMPTY);
		}
	}

	// ------------------------------------------------------------------ protest

	public static class Protest extends Task {
		private @Nullable BlockPos spot;
		private static final String[] SLOGANS = {"Fair wages now!", "Bread for all!", "Down with the governor!", "We won't work for nothing!",
			"Lower the taxes!", "Shorter hours!", "Enough is enough!"};

		public Protest(CitizenEntity npc, City city, CitizenRecord rec) {
			super(npc, city, rec);
		}

		@Override
		public String kind() {
			return "protest";
		}

		@Override
		public void start() {
			BlockPos square = this.city.center;
			this.spot = Walker.standable(this.npc.level(), square.offset(this.npc.getRandom().nextInt(9) - 4, 0, this.npc.getRandom().nextInt(9) - 4), 3);
		}

		@Override
		protected void run() {
			if (this.spot != null && !this.walk(this.spot, 1.5)) {
				return;
			}
			this.npc.setAction(CitizenEntity.ACTION_PROTEST);
			if (this.ticks % 25 == 0) {
				this.npc.swingArm();
			}
			if (this.ticks % 140 == 0) {
				this.npc.say(SLOGANS[this.npc.getRandom().nextInt(SLOGANS.length)]);
			}
			if (this.city.riot && net.antwire.civitas.CivitasConfig.get().riotDamage && this.ticks % 400 == 0) {
				Townlife.riotDamage(this.level(), this.city, this.npc);
			}
			boolean on = this.city.riot || (this.city.strike && Brain.workTime(this.city.policies, time(this.npc)));
			if (!on || this.ticks > 1200) {
				this.done = true;
			}
		}
	}

	// ------------------------------------------------------------------ prison

	public static class Jail extends Task {
		public Jail(CitizenEntity npc, City city, CitizenRecord rec) {
			super(npc, city, rec);
		}

		@Override
		public String kind() {
			return "jail";
		}

		@Override
		protected void run() {
			BlockPos cell = Townlife.cellOf(this.city, this.rec);
			if (cell == null) {
				this.done = true;
				return;
			}
			if (this.npc.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(cell)) > 2.5) {
				this.npc.teleportTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
			}
			this.npc.getNavigation().stop();
			if (this.ticks % 300 == 0) {
				String[] talk = {"Let me out!", "I only took a loaf of bread...", "How long now?", "This isn't justice."};
				this.npc.say(talk[this.npc.getRandom().nextInt(talk.length)]);
			}
			if (this.rec.status != CitizenRecord.Status.JAILED) {
				this.done = true;
			}
		}
	}

	// ------------------------------------------------------------------ crime

	public static class Steal extends Task {
		private final Building target;
		private boolean took;

		Steal(CitizenEntity npc, City city, CitizenRecord rec, Building target) {
			super(npc, city, rec);
			this.target = target;
		}

		static @Nullable Steal create(CitizenEntity npc, City city, CitizenRecord rec) {
			for (Building b : city.buildings) {
				if (b.complete && (b.type == BuildingType.BAKERY || b.type == BuildingType.BUTCHER || b.type == BuildingType.TAVERN)) {
					ShopBlockEntity shop = Storage.shop((net.minecraft.server.level.ServerLevel) npc.level(), b);
					if (shop != null && !shop.isEmpty()) {
						return new Steal(npc, city, rec, b);
					}
				}
			}
			return null;
		}

		@Override
		public String kind() {
			return "steal";
		}

		@Override
		protected void run() {
			if (!this.took) {
				BlockPos spot = customerSpot(this.target);
				if (spot == null || this.walk(spot, 1.6)) {
					ShopBlockEntity shop = Storage.shop(this.level(), this.target);
					ItemStack loot = ItemStack.EMPTY;
					if (shop != null) {
						for (int i = 0; i < shop.getContainerSize() && loot.isEmpty(); i++) {
							ItemStack s = shop.getItem(i);
							if (!s.isEmpty()) {
								loot = s.split(Math.min(2, s.getCount()));
								shop.setChanged();
							}
						}
					}
					this.took = true;
					if (loot.isEmpty()) {
						this.done = true;
						return;
					}
					this.rec.food = Math.min(100, this.rec.food + BuyFood.food(loot.getItem()));
					this.rec.status = CitizenRecord.Status.WANTED;
					this.rec.crimes++;
					this.npc.hold(loot);
					this.npc.say("Nobody saw that...");
					this.city.log(this.rec.name + " stole " + loot.getCount() + "× " + loot.getHoverName().getString() + " from the " + this.target.type.title.toLowerCase());
				}
				return;
			}
			// slip away from the scene
			BlockPos away = this.target.entrance().offset(this.npc.getRandom().nextInt(17) - 8, 0, this.npc.getRandom().nextInt(17) - 8);
			if (this.run(away, 3) || this.ticks > 300) {
				this.npc.hold(ItemStack.EMPTY);
				this.done = true;
			}
		}
	}

	public static class Beg extends Task {
		private final Player player;

		public Beg(CitizenEntity npc, City city, CitizenRecord rec, Player player) {
			super(npc, city, rec);
			this.player = player;
		}

		@Override
		public String kind() {
			return "beg";
		}

		@Override
		protected void run() {
			if (!this.player.isAlive() || this.player.distanceTo(this.npc) > 24 || this.ticks > 400) {
				this.done = true;
				return;
			}
			if (this.npc.distanceTo(this.player) > 2.5) {
				this.npc.getNavigation().moveTo(this.player, 1.0);
			} else {
				this.npc.getNavigation().stop();
				this.npc.setAction(CitizenEntity.ACTION_BEG);
			}
			this.npc.getLookControl().setLookAt(this.player);
			if (this.ticks % 120 == 20) {
				String[] talk = {"Spare a coin, please?", "I haven't eaten in days...", "Anything helps, " + this.player.getGameProfile().name() + "...",
					"Please, for my children."};
				this.npc.say(talk[this.npc.getRandom().nextInt(talk.length)]);
			}
		}
	}

	public static class Leave extends Task {
		private @Nullable BlockPos exit;

		public Leave(CitizenEntity npc, City city, CitizenRecord rec) {
			super(npc, city, rec);
		}

		@Override
		public String kind() {
			return "leave";
		}

		@Override
		public void start() {
			double dx = this.npc.getX() - this.city.center.getX();
			double dz = this.npc.getZ() - this.city.center.getZ();
			double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
			int x = (int) (this.city.center.getX() + dx / len * (this.city.radius + 12));
			int z = (int) (this.city.center.getZ() + dz / len * (this.city.radius + 12));
			int y = this.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			this.exit = new BlockPos(x, y, z);
			this.npc.say("I'm leaving this town for good.");
		}

		@Override
		protected void run() {
			if (this.exit == null || this.walk(this.exit, 3) || this.ticks > 1600) {
				this.rec.status = CitizenRecord.Status.LEFT;
				this.city.log(this.rec.name + " left " + this.city.name);
				Townlife.release(this.city, this.rec);
				this.npc.discard();
				this.done = true;
			}
		}
	}
}
