package net.antwire.civitas.entity;

import java.util.UUID;
import net.antwire.civitas.city.CitizenRecord;
import net.antwire.civitas.city.City;
import net.antwire.civitas.city.CityManager;
import net.antwire.civitas.city.Job;
import net.antwire.civitas.entity.ai.Brain;
import net.antwire.civitas.network.ServerNet;
import net.antwire.commerce.api.CommerceApi;
import net.antwire.commerce.item.CoinItem;
import net.antwire.commerce.Money;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** A citizen: walks, works, shops, sleeps. What they are lives in the town's {@link CitizenRecord}. */
public class CitizenEntity extends PathfinderMob {
	private static final EntityDataAccessor<Integer> JOB = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> TIER = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> FEMALE = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<String> THOUGHT = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> ACTION = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

	public static final int ACTION_NONE = 0;
	public static final int ACTION_WORK = 1;
	public static final int ACTION_PROTEST = 2;
	public static final int ACTION_SIT = 3;
	public static final int ACTION_BEG = 4;

	private @Nullable UUID cityId;
	private final Brain brain = new Brain(this);
	private int thoughtTicks;

	public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
		this.getNavigation().setCanOpenDoors(true);
		this.getNavigation().setCanFloat(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.32).add(Attributes.FOLLOW_RANGE, 64.0)
			.add(Attributes.ATTACK_DAMAGE, 2.0);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder data) {
		super.defineSynchedData(data);
		data.define(JOB, 0);
		data.define(TIER, 1);
		data.define(SKIN, 0);
		data.define(FEMALE, false);
		data.define(THOUGHT, "");
		data.define(ACTION, 0);
	}

	public void bind(City city, CitizenRecord r) {
		this.cityId = city.id;
		this.setCustomName(Component.literal(r.name));
		this.setCustomNameVisible(true);
		this.entityData.set(SKIN, r.skin);
		this.entityData.set(FEMALE, r.female);
		this.refresh(city, r);
	}

	/** Brings the synced looks in line with the record (job outfit, how worn the clothes are). */
	public void refresh(City city, CitizenRecord r) {
		Job shown = r.status == CitizenRecord.Status.JAILED ? Job.PRISONER : r.job;
		this.entityData.set(JOB, shown.ordinal());
		this.entityData.set(TIER, r.tier(CommerceApi.balance(r.account(city))));
		Tools.equip(this, shown);
	}

	public @Nullable UUID cityId() {
		return this.cityId;
	}

	public @Nullable City city() {
		CityManager m = CityManager.get();
		return m == null ? null : m.city(this.cityId);
	}

	public @Nullable CitizenRecord record() {
		City c = this.city();
		return c == null ? null : c.citizens.get(this.getUUID());
	}

	public Job job() {
		return Job.byOrdinal(this.entityData.get(JOB));
	}

	public int tier() {
		return this.entityData.get(TIER);
	}

	public int skin() {
		return this.entityData.get(SKIN);
	}

	public boolean female() {
		return this.entityData.get(FEMALE);
	}

	public String thought() {
		return this.entityData.get(THOUGHT);
	}

	public int action() {
		return this.entityData.get(ACTION);
	}

	public void setAction(int a) {
		if (this.entityData.get(ACTION) != a) {
			this.entityData.set(ACTION, a);
		}
	}

	/** Says something (shown over their head for a few seconds) and remembers it. */
	public void say(String text) {
		this.entityData.set(THOUGHT, text);
		this.thoughtTicks = 100;
		CitizenRecord r = this.record();
		if (r != null) {
			r.think(text);
		}
	}

	public Brain brain() {
		return this.brain;
	}

	/** Citizens tread carefully on farmland (no trampled crops). */
	@Override
	protected void checkFallDamage(double ya, boolean onGround, net.minecraft.world.level.block.state.BlockState onState, net.minecraft.core.BlockPos pos) {
		if (onState.is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
			this.resetFallDistance();
		}
		super.checkFallDamage(ya, onGround, onState, pos);
	}

	public void swingArm() {
		this.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
	}

	@Override
	public void tick() {
		super.tick();
		if (!(this.level() instanceof ServerLevel)) {
			return;
		}
		CityManager m = CityManager.get();
		City city = this.city();
		CitizenRecord r = this.record();
		if (m == null) {
			return;
		}
		if (city == null || r == null || !r.present()) {
			// a body without a town (the town was dissolved, or they left): fade away
			this.discard();
			return;
		}
		m.entities.put(this.getUUID(), this);
		if (this.thoughtTicks > 0 && --this.thoughtTicks == 0) {
			this.entityData.set(THOUGHT, "");
		}
		if (this.tickCount % 40 == 0) {
			this.refresh(city, r);
			r.lastPos = this.blockPosition();
		}
		this.brain.tick(city, r);
	}

	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (!(player instanceof ServerPlayer sp) || this.city() == null) {
			return InteractionResult.SUCCESS;
		}
		City city = this.city();
		CitizenRecord r = this.record();
		ItemStack held = player.getItemInHand(hand);
		if (held.getItem() instanceof CoinItem coin && r != null) {
			// alms
			long cents = (long) coin.value() * Money.UNIT;
			held.shrink(1);
			CommerceApi.mint(r.account(city), cents, "Gift from " + sp.getGameProfile().name());
			r.happiness = Math.min(100, r.happiness + 4);
			r.mood.merge("Kindness", 4.0, Double::sum);
			this.say(r.clothing < 40 ? "Bless you, " + sp.getGameProfile().name() + "!" : "Thank you!");
			this.playSound(SoundEvents.VILLAGER_YES, 1.0F, 1.0F);
			return InteractionResult.SUCCESS;
		}
		ServerNet.openCitizen(sp, city, this.getUUID());
		this.getLookControl().setLookAt(player);
		return InteractionResult.SUCCESS;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (this.level() instanceof ServerLevel) {
			City city = this.city();
			CitizenRecord r = this.record();
			if (city != null && r != null && r.present()) {
				r.status = CitizenRecord.Status.DEAD;
				String cause = r.cause.isEmpty() ? source.getLocalizedDeathMessage(this).getString().replace(r.name + " ", "") : r.cause;
				city.log(r.name + " died (" + cause + ")");
				for (CitizenRecord o : city.citizens.values()) {
					if (o.present()) {
						o.happiness = Math.max(0, o.happiness - 3);
						o.mood.merge("Grief", -3.0, Double::sum);
					}
				}
				net.antwire.civitas.city.Townlife.release(city, r);
			}
		}
	}

	@Override
	protected @Nullable SoundEvent getAmbientSound() {
		return null;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.PLAYER_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.PLAYER_DEATH;
	}

	@Override
	public boolean removeWhenFarAway(double distSqr) {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.storeNullable("city", UUIDUtil.CODEC, this.cityId);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.cityId = input.read("city", UUIDUtil.CODEC).orElse(null);
		this.setCustomNameVisible(true);
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
	}

	@Override
	public boolean canPickUpLoot() {
		return false;
	}

	public void hold(ItemStack stack) {
		this.setItemSlot(EquipmentSlot.MAINHAND, stack);
	}
}
