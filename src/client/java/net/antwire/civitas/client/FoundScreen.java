package net.antwire.civitas.client;

import net.antwire.civitas.network.Dto;
import net.antwire.civitas.network.Payloads;
import net.antwire.commerce.client.Ui;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Name your town. */
public class FoundScreen extends Screen {
	private final Dto.Found where;
	private EditBox name;

	public FoundScreen(Dto.Found where) {
		super(Component.literal("Found a town"));
		this.where = where;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int cy = this.height / 2;
		this.name = this.addRenderableWidget(new EditBox(this.font, cx - 90, cy - 6, 180, 18, Component.literal("Name")));
		this.name.setMaxLength(32);
		this.name.setHint(Component.literal("Name of your town").withColor(0xFF6B7682));
		this.setInitialFocus(this.name);
		this.addRenderableWidget(Button.builder(Component.literal("Found the town"), b -> this.found()).bounds(cx - 90, cy + 20, 180, 20).build());
	}

	private void found() {
		this.where.name = this.name.getValue();
		ClientPlayNetworking.send(new Payloads.Action("found", Dto.GSON.toJson(this.where)));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
		super.extractBackground(g, mx, my, a);
		int cx = this.width / 2;
		int cy = this.height / 2;
		Ui.frame(g, cx - 110, cy - 60, 220, 112);
		g.centeredText(this.font, "◆ TOWN CHARTER", cx, cy - 52, Ui.GOLD);
		g.textWithWordWrap(this.font, Component.literal("The town hall will rise here, its door facing you. Settlers arrive with you and start building."),
			cx - 100, cy - 38, 200, Ui.MUTED, false);
		if (System.currentTimeMillis() - ClientState.messageTime < 8000 && !ClientState.messageOk) {
			g.centeredText(this.font, ClientState.message, cx, cy + 46, Ui.RED);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
