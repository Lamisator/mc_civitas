package net.antwire.civitas.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/** The ordnance factory builds RedButton's high-explosive missile when RedButton is installed (looked up by id, no hard dependency). */
public final class RedButtonCompat {
	private static final Identifier MISSILE = Identifier.fromNamespaceAndPath("redbutton", "missile_he");

	private RedButtonCompat() {
	}

	public static @Nullable Item missile() {
		Item item = BuiltInRegistries.ITEM.getValue(MISSILE);
		return item == null || item == Items.AIR ? null : item;
	}
}
