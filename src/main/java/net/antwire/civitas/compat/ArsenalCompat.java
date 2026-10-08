package net.antwire.civitas.compat;

import net.antwire.arsenal.api.ArsenalApi;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** The calls into Arsenal. Only touched through {@link Arms} when Arsenal is installed. */
final class ArsenalCompat {
	private ArsenalCompat() {
	}

	static ItemStack stack(String gun) {
		return ArsenalApi.stack(gun);
	}

	static int magazine(String gun) {
		return ArsenalApi.magazine(gun);
	}

	static double interval(String gun) {
		return ArsenalApi.interval(gun);
	}

	static int reloadTicks(String gun) {
		return ArsenalApi.reloadTicks(gun);
	}

	static boolean fire(LivingEntity shooter, String gun, float yaw, float pitch, float inaccuracy) {
		return ArsenalApi.fire(shooter, gun, yaw, pitch, inaccuracy);
	}

	static void reloadSound(LivingEntity shooter, boolean in) {
		ArsenalApi.reloadSound(shooter, in);
	}
}
