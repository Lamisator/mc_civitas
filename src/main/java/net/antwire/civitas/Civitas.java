package net.antwire.civitas;

import net.antwire.civitas.city.CityManager;
import net.antwire.civitas.command.CivitasCommands;
import net.antwire.civitas.network.ServerNet;
import net.antwire.civitas.registry.ModBlocks;
import net.antwire.civitas.registry.ModEntities;
import net.antwire.civitas.registry.ModItems;
import net.antwire.civitas.registry.ModTab;
import net.antwire.civitas.registry.ModTickets;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Civitas: found a town and its people build it, work, trade in the town's currency, eat, sleep, protest and leave -
 * governed by you, for better or worse.
 */
public class Civitas implements ModInitializer {
	public static final String MOD_ID = "civitas";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		CivitasConfig.get();
		ModTickets.init();
		ModBlocks.init();
		ModItems.init();
		ModEntities.init();
		ModTab.init();
		ServerNet.init();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CivitasCommands.register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			CityManager.load(server);
			LOGGER.info("Civitas: {} town(s)", CityManager.get().cities.size());
			net.antwire.civitas.city.Access.lintAll();
		});
		ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> {
			if (CityManager.get() != null) {
				CityManager.get().save();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			CityManager.unload();
			net.antwire.civitas.entity.ai.Military.clear();
		});
		// the town's enemies: what hunts villagers hunts citizens too
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (CivitasConfig.get().monstersAttackCitizens && entity instanceof net.minecraft.world.entity.monster.Monster m
				&& (m instanceof net.minecraft.world.entity.monster.zombie.Zombie || m instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton
					|| m instanceof net.minecraft.world.entity.raid.Raider && !(m instanceof net.minecraft.world.entity.monster.Witch))) {
				((net.antwire.civitas.mixin.MobAccessor) m).civitas$targetSelector().addGoal(3,
					new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(m, net.antwire.civitas.entity.CitizenEntity.class, true));
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (CityManager.get() != null) {
				CityManager.get().tick();
			}
		});
		LOGGER.info("Civitas loaded");
	}
}
