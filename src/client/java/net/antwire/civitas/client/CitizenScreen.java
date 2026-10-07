package net.antwire.civitas.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.antwire.civitas.network.Dto;
import net.antwire.civitas.network.Payloads;
import net.antwire.commerce.Money;
import net.antwire.commerce.client.Ui;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** A citizen's card: who they are, how they're doing and why - and what the governor can do about them. */
public class CitizenScreen extends Screen {
	static final int W = 330;
	static final int H = 210;
	static final String[] TIERS = {"Well-off", "Comfortable", "Poor", "Destitute"};
	static final int[] TIER_COLORS = {0xFFE8C547, 0xFF4CD787, 0xFFE89A47, 0xFFFF5A5A};
	private final List<Button> govButtons = new ArrayList<>();
	private int jobIndex;

	public CitizenScreen() {
		super(Component.literal("Citizen"));
	}

	static void send(String op, Dto.Citizen d, int building, long amount) {
		Dto.Action a = new Dto.Action();
		a.city = d.city;
		a.citizen = d.uuid;
		a.op = op;
		a.building = building;
		a.amount = amount;
		ClientPlayNetworking.send(new Payloads.Action("citizen", Dto.GSON.toJson(a)));
	}

	@Override
	protected void init() {
		Dto.Citizen d = ClientState.citizen;
		int l = (this.width - W) / 2;
		int t = (this.height - H) / 2;
		this.govButtons.clear();
		this.addRenderableWidget(Button.builder(Component.literal("Give 10"), b -> send("gift", ClientState.citizen, 0, 1000)).bounds(l + 8, t + H - 24, 60, 16)
			.build());
		this.addRenderableWidget(Button.builder(Component.literal("Give 100"), b -> send("gift", ClientState.citizen, 0, 10000)).bounds(l + 70, t + H - 24, 60, 16)
			.build());
		if (d != null && d.editable) {
			this.govButtons.add(this.addRenderableWidget(Button.builder(Component.literal("Job ▸"), b -> this.nextJob()).bounds(l + 136, t + H - 24, 44, 16).build()));
			this.govButtons.add(this.addRenderableWidget(Button.builder(Component.literal("Arrest"), b -> send("arrest", ClientState.citizen, 0, 0))
				.bounds(l + 182, t + H - 24, 44, 16).build()));
			this.govButtons.add(this.addRenderableWidget(Button.builder(Component.literal("Pardon"), b -> send("release", ClientState.citizen, 0, 0))
				.bounds(l + 228, t + H - 24, 44, 16).build()));
			this.govButtons.add(this.addRenderableWidget(Button.builder(Component.literal("Banish"), b -> send("exile", ClientState.citizen, 0, 0))
				.bounds(l + 274, t + H - 24, 48, 16).build()));
		}
	}

	private void nextJob() {
		Dto.Citizen d = ClientState.citizen;
		if (d == null || d.jobs.isEmpty()) {
			return;
		}
		this.jobIndex = (this.jobIndex + 1) % d.jobs.size();
		Object[] j = d.jobs.get(this.jobIndex);
		send("job", d, ((Number) j[0]).intValue(), 0);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
		super.extractBackground(g, mx, my, a);
		Dto.Citizen d = ClientState.citizen;
		int l = (this.width - W) / 2;
		int t = (this.height - H) / 2;
		Ui.frame(g, l, t, W, H);
		if (d == null) {
			return;
		}
		g.fill(l, t, l + W, t + 17, 0xFF2A1C0E);
		g.text(this.font, d.name, l + 6, t + 5, Ui.GOLD, false);
		Ui.right(g, this.font, d.job + " · " + d.age + " years", l + W - 6, t + 5, Ui.TEXT);
		// portrait
		Ui.panel(g, l + 6, t + 22, l + 86, t + 132, 0xFF0B0F13);
		Entity e = this.minecraft.level == null || d.entityId < 0 ? null : this.minecraft.level.getEntity(d.entityId);
		if (e instanceof LivingEntity le) {
			InventoryScreen.extractEntityInInventoryFollowsMouse(g, l + 8, t + 24, l + 84, t + 130, 38, 0.0625F, mx, my, le);
		}
		g.centeredText(this.font, TIERS[d.tier], l + 46, t + 136, TIER_COLORS[d.tier]);
		Ui.small(g, this.font, d.status.equals("FREE") ? "" : d.status.toLowerCase(Locale.ROOT), l + 8, t + 147, Ui.RED);
		// needs
		int x = l + 94;
		int y = t + 24;
		bar(g, x, y, "Happiness", d.happiness, d.happiness >= 50 ? Ui.GREEN : d.happiness >= 25 ? 0xFFE8C547 : Ui.RED);
		bar(g, x, y + 12, "Food", d.food, Ui.GOLD);
		bar(g, x, y + 24, "Rest", d.rest, Ui.BLUE);
		bar(g, x, y + 36, "Clothes", d.clothing, 0xFFB08BE0);
		Ui.small(g, this.font, "Money " + Money.format(d.balance) + "   wage " + Money.format(d.wage) + "/day", x, y + 50, Ui.TEXT);
		Ui.small(g, this.font, "Home: " + d.home, x, y + 59, Ui.MUTED);
		Ui.small(g, this.font, "Works at: " + d.workplace, x, y + 68, Ui.MUTED);
		// why they feel this way
		Ui.small(g, this.font, "WHY", x, y + 82, Ui.MUTED);
		int my2 = y + 91;
		for (Map.Entry<String, Double> m : d.mood.entrySet()) {
			if (my2 > t + H - 34) {
				break;
			}
			Ui.small(g, this.font, m.getKey(), x, my2, Ui.TEXT);
			Ui.smallRight(g, this.font, String.format(Locale.ROOT, "%+.0f", m.getValue()), x + 110, my2, m.getValue() >= 0 ? Ui.GREEN : Ui.RED);
			my2 += 8;
		}
		// thoughts
		int tx = l + 214;
		Ui.small(g, this.font, "ON THEIR MIND", tx, y + 82, Ui.MUTED);
		int ty = y + 91;
		for (String s : d.thoughts) {
			for (var line : this.font.split(Component.literal("“" + s + "”"), 140)) {
				if (ty > t + H - 34) {
					break;
				}
				g.pose().pushMatrix();
				g.pose().translate(tx, ty);
				g.pose().scale(0.75F, 0.75F);
				g.text(this.font, line, 0, 0, 0xFFE8E2A0, false);
				g.pose().popMatrix();
				ty += 8;
			}
		}
		if (System.currentTimeMillis() - ClientState.messageTime < 6000) {
			g.text(this.font, ClientState.message, l + 6, t + H + 4, ClientState.messageOk ? Ui.GREEN : Ui.RED, true);
		}
	}

	private void bar(GuiGraphicsExtractor g, int x, int y, String label, double v, int color) {
		Ui.small(g, this.font, label, x, y + 1, Ui.MUTED);
		g.fill(x + 50, y, x + 230, y + 7, 0xFF0B0F13);
		g.fill(x + 50, y, x + 50 + (int) (180 * Math.max(0, Math.min(100, v)) / 100), y + 7, color);
		Ui.smallRight(g, this.font, Math.round(v) + "%", x + 228, y + 1, 0xFF000000);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
