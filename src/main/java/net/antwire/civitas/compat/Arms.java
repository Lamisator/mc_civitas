package net.antwire.civitas.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * What the town's soldiers are armed with. With Arsenal installed: M4A1 carbines (one designated marksman with an AWM),
 * plate carriers and combat helmets - real bullets that body armour, cover and walls act on. Without it: longbows, iron
 * helmets and breastplates.
 */
public final class Arms {
	private static final boolean ARSENAL = FabricLoader.getInstance().isModLoaded("arsenal");

	private Arms() {
	}

	public static boolean arsenal() {
		return ARSENAL;
	}

	/** The weapon of the n-th soldier of a barracks. */
	public static String weapon(int index) {
		if (!ARSENAL) {
			return "bow";
		}
		return index % 4 == 3 ? "awm" : "m4a1";
	}

	public static ItemStack stack(String weapon) {
		if (ARSENAL && !weapon.equals("bow")) {
			ItemStack s = ArsenalCompat.stack(weapon);
			if (!s.isEmpty()) {
				return s;
			}
		}
		return new ItemStack(Items.BOW);
	}

	private static Item arsenalItem(String id, Item fallback) {
		if (!ARSENAL) {
			return fallback;
		}
		Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("arsenal", id));
		return item == null || item == Items.AIR ? fallback : item;
	}

	public static Item helmet() {
		return arsenalItem("combat_helmet", Items.IRON_HELMET);
	}

	public static Item vest() {
		return arsenalItem("plate_carrier", Items.IRON_CHESTPLATE);
	}

	public static boolean isWeapon(Item item) {
		return item == Items.BOW || ARSENAL && BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("arsenal");
	}

	public static int magazine(String weapon) {
		return ARSENAL && !weapon.equals("bow") ? ArsenalCompat.magazine(weapon) : 1;
	}

	/** Ticks from one shot to the next: the gun's rate of fire, or drawing the bow. */
	public static double interval(String weapon) {
		return ARSENAL && !weapon.equals("bow") ? ArsenalCompat.interval(weapon) : 28;
	}

	public static int reloadTicks(String weapon) {
		return ARSENAL && !weapon.equals("bow") ? ArsenalCompat.reloadTicks(weapon) : 0;
	}

	/** Effective range in blocks. */
	public static double range(String weapon) {
		return switch (weapon) {
			case "awm" -> 64;
			case "m4a1" -> 40;
			default -> 22;
		};
	}

	public static void reloadSound(LivingEntity shooter, boolean in) {
		if (ARSENAL) {
			ArsenalCompat.reloadSound(shooter, in);
		}
	}

	/** One shot at the aim point. */
	public static void fire(ServerLevel level, LivingEntity shooter, String weapon, Vec3 aim, float inaccuracy,
		java.util.function.Predicate<net.minecraft.world.entity.Entity> spare) {
		Vec3 eye = shooter.getEyePosition();
		Vec3 d = aim.subtract(eye);
		if (ARSENAL && !weapon.equals("bow")) {
			double h = Math.sqrt(d.x * d.x + d.z * d.z);
			float yaw = (float) (Math.atan2(d.z, d.x) * 180.0 / Math.PI) - 90.0F;
			float pitch = (float) -(Math.atan2(d.y, h) * 180.0 / Math.PI);
			ArsenalCompat.fire(shooter, weapon, yaw, pitch, inaccuracy, spare);
			return;
		}
		ItemStack bow = new ItemStack(Items.BOW);
		ItemStack arrowStack = new ItemStack(Items.ARROW);
		AbstractArrow arrow = ProjectileUtil.getMobArrow(shooter, arrowStack, 1.0F, bow);
		arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
		double h = Math.sqrt(d.x * d.x + d.z * d.z);
		Projectile.spawnProjectileUsingShoot(arrow, level, arrowStack, d.x, d.y + h * 0.12, d.z, 2.4F, 2.0F + inaccuracy * 6.0F);
		shooter.playSound(SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (shooter.getRandom().nextFloat() * 0.4F + 0.8F));
	}

	public static void noisyBell(ServerLevel level, net.minecraft.core.BlockPos at) {
		level.playSound(null, at, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.5F, 1.0F);
	}
}
