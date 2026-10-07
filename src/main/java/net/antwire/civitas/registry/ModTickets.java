package net.antwire.civitas.registry;

import net.antwire.civitas.Civitas;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.TicketType;

public final class ModTickets {
	/** Keeps a town's chunks loaded and ticking: its people go on living while nobody watches. */
	public static final TicketType TOWN = Registry.register(BuiltInRegistries.TICKET_TYPE, Civitas.id("town"),
		new TicketType(300L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

	private ModTickets() {
	}

	public static void init() {
	}
}
