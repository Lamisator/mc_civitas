package net.antwire.civitas.registry;

import net.antwire.civitas.Civitas;
import net.antwire.civitas.entity.CitizenEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	public static final EntityType<CitizenEntity> CITIZEN;

	static {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Civitas.id("citizen"));
		CITIZEN = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
			EntityType.Builder.<CitizenEntity>of(CitizenEntity::new, MobCategory.MISC).sized(0.6F, 1.95F).eyeHeight(1.62F).clientTrackingRange(10).build(key));
	}

	private ModEntities() {
	}

	public static void init() {
		FabricDefaultAttributeRegistry.register(CITIZEN, CitizenEntity.createAttributes());
	}
}
