package net.antwire.civitas.client;

import net.antwire.civitas.network.Dto;
import net.minecraft.client.Minecraft;

/** What the server last told this client, and the screens it opens. */
public final class ClientState {
	public static Dto.Govern govern;
	public static Dto.Citizen citizen;
	public static String message = "";
	public static boolean messageOk;
	public static long messageTime;

	private ClientState() {
	}

	public static void accept(String kind, String json) {
		Minecraft mc = Minecraft.getInstance();
		switch (kind) {
			case "found" -> mc.gui.setScreen(new FoundScreen(Dto.GSON.fromJson(json, Dto.Found.class)));
			case "govern" -> {
				govern = Dto.GSON.fromJson(json, Dto.Govern.class);
				if (!(mc.gui.screen() instanceof GovernScreen)) {
					mc.gui.setScreen(new GovernScreen());
				}
			}
			case "citizen" -> {
				citizen = Dto.GSON.fromJson(json, Dto.Citizen.class);
				if (!(mc.gui.screen() instanceof CitizenScreen)) {
					mc.gui.setScreen(new CitizenScreen());
				}
			}
			case "message" -> {
				Dto.Message m = Dto.GSON.fromJson(json, Dto.Message.class);
				message = m.text;
				messageOk = m.ok;
				messageTime = System.currentTimeMillis();
			}
			default -> {
			}
		}
	}
}
