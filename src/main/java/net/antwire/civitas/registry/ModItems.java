package net.antwire.civitas.registry;

import java.util.List;
import java.util.function.Function;
import net.antwire.civitas.Civitas;
import net.antwire.civitas.item.TownCharterItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.Consumables;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;

public final class ModItems {
	public static final Item TOWN_CHARTER = register("town_charter", TownCharterItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));
	/** The tavern's ale: a little food and a happy wobble. */
	public static final Item ALE = register("ale", Item::new, new Item.Properties().stacksTo(16).usingConvertsTo(Items.GLASS_BOTTLE)
		.food(new FoodProperties.Builder().nutrition(2).saturationModifier(0.2F).alwaysEdible().build(),
			Consumables.defaultDrink().onConsume(new ApplyStatusEffectsConsumeEffect(List.of(new MobEffectInstance(MobEffects.NAUSEA, 160, 0),
				new MobEffectInstance(MobEffects.REGENERATION, 100, 0)))).build()));

	private ModItems() {
	}

	private static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Civitas.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.setId(key)));
	}

	public static void init() {
	}
}
