package net.antwire.civitas.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.antwire.civitas.city.City;
import net.antwire.civitas.city.Policies;
import net.antwire.civitas.network.Dto;
import net.antwire.civitas.network.Payloads;
import net.antwire.commerce.Money;
import net.antwire.commerce.client.Ui;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** The town ledger: the state of the town, its people and buildings, its laws and its money - and the governor's hand on all of it. */
public class GovernScreen extends Screen {
	static final int W = 400;
	static final int H = 236;
	public static final String[] TABS = {"Overview", "Citizens", "Buildings", "Laws", "Treasury", "Supply", "Works"};
	static final int ROW = 11;
	public int tab;
	private int scroll;
	private int ticks;
	private boolean confirmConfiscate;
	private final List<Button> tabButtons = new ArrayList<>();
	private final List<Button>[] perTab = new List[TABS.length];
	private EditBox amount;
	private String hoverNote;
	private Button councilButton;
	private Button loadButton;
	private Button wallButton;
	private Button paveButton;
	private Button lampButton;
	private Button autoRepairButton;

	/** Number laws: key, label, step, unit. */
	record Law(String key, String label, double step, String unit) {
	}

	static final Law[] NUMBERS = {new Law("incomeTax", "Income tax", 5, "%"), new Law("rent", "Rent per day", 0.5, "$"),
		new Law("wageLevel", "Wages", 10, "%"), new Law("workHours", "Working day", 1, "h"), new Law("basicIncome", "Basic income", 1, "$"),
		new Law("breadPrice", "Bread price", 0.25, "$"), new Law("meatPrice", "Meat price", 0.25, "$"), new Law("alePrice", "Ale price", 0.25, "$"),
		new Law("sentenceDays", "Prison sentence", 1, "days"), new Law("dividendPercent", "Dividend / day", 0.01, "%")};
	static final Law[] TOGGLES = {new Law("rations", "Free rations for the hungry", 1, ""), new Law("subsidies", "Wage subsidies from the treasury", 1, ""),
		new Law("curfew", "Curfew at nightfall", 1, ""), new Law("martialLaw", "Martial law", 1, ""), new Law("forcedLabor", "Prisoners work in the mine", 1, ""),
		new Law("safetyRules", "Factory safety rules", 1, ""), new Law("autoBuild", "Council plans buildings itself", 1, ""),
		new Law("autoAssign", "Free choice of work", 1, ""), new Law("immigration", "Open to settlers", 1, "")};

	public GovernScreen() {
		super(Component.literal("Town ledger"));
		for (int i = 0; i < TABS.length; i++) {
			this.perTab[i] = new ArrayList<>();
		}
	}

	static void act(String op, String key, double value, String text, long amount, int building) {
		Dto.Govern d = ClientState.govern;
		if (d == null) {
			return;
		}
		Dto.Action a = new Dto.Action();
		a.city = d.id;
		a.op = op;
		a.key = key;
		a.value = value;
		a.text = text;
		a.amount = amount;
		a.building = building;
		ClientPlayNetworking.send(new Payloads.Action("govern", Dto.GSON.toJson(a)));
	}

	static double get(Policies p, String key) {
		try {
			Object v = Policies.class.getField(key).get(p);
			if (v instanceof Boolean b) {
				return b ? 1 : 0;
			}
			return ((Number) v).doubleValue();
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	@Override
	protected void init() {
		int l = (this.width - W) / 2;
		int t = (this.height - H) / 2;
		this.tabButtons.clear();
		for (List<Button> list : this.perTab) {
			list.clear();
		}
		for (int i = 0; i < TABS.length; i++) {
			int k = i;
			this.tabButtons.add(this.addRenderableWidget(Button.builder(Component.literal(TABS[i]), b -> this.setTab(k)).bounds(l + 6 + i * 55, t + 20, 53, 16).build()));
		}
		// laws: - and + for the numbers, a switch for each toggle
		for (int i = 0; i < NUMBERS.length; i++) {
			Law law = NUMBERS[i];
			int y = t + 44 + i * 16;
			this.perTab[3].add(this.addRenderableWidget(Button.builder(Component.literal("−"), b -> this.step(law, -1)).bounds(l + 120, y, 16, 14).build()));
			this.perTab[3].add(this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.step(law, 1)).bounds(l + 178, y, 16, 14).build()));
		}
		for (int i = 0; i < TOGGLES.length; i++) {
			Law law = TOGGLES[i];
			int y = t + 46 + i * 17;
			this.perTab[3].add(this.addRenderableWidget(Button.builder(Component.literal("…"), b -> {
				Dto.Govern d = ClientState.govern;
				if (d != null) {
					act("policy", law.key, get(d.policies, law.key) != 0 ? 0 : 1, null, 0, 0);
				}
			}).bounds(l + 352, y, 40, 14).build()));
		}
		// self-government and (for operators) keeping the town loaded
		this.perTab[3].add(this.councilButton = this.addRenderableWidget(Button.builder(Component.literal("Council"), b -> {
			Dto.Govern d = ClientState.govern;
			if (d != null) {
				String[] all = {"none", "growth", "equality", "prosperity", "order", "extortion", "balanced"};
				int i = java.util.Arrays.asList(all).indexOf(d.strategy);
				act("strategy", null, 0, all[(i + 1) % all.length], 0, 0);
			}
		}).bounds(l + 206, t + H - 32, 140, 14).build()));
		this.perTab[3].add(this.loadButton = this.addRenderableWidget(Button.builder(Component.literal("Load"), b -> {
			Dto.Govern d = ClientState.govern;
			if (d != null) {
				// server default -> on -> off -> server default
				act("keeploaded", null, d.keepLoaded == -1 ? 1 : d.keepLoaded == 1 ? 0 : -1, null, 0, 0);
			}
		}).bounds(l + 348, t + H - 32, 44, 14).build()));
		// buildings: one button per kind of building
		String[] types = {"house", "farm", "bakery", "butcher", "blacksmith", "mine", "lumber_mill", "tavern", "sheriff", "prison", "bank", "factory",
			"barracks"};
		for (int i = 0; i < types.length; i++) {
			String type = types[i];
			int col = i % 5;
			int row = i / 5;
			this.perTab[2].add(this.addRenderableWidget(Button.builder(Component.literal(type.replace('_', ' ')), b -> act("build", null, 0, type, 0, 0))
				.bounds(l + 8 + col * 77, t + H - 66 + row * 17, 75, 15).build()));
		}
		// treasury
		this.amount = this.addRenderableWidget(new EditBox(this.font, l + 120, t + 104, 90, 16, Component.literal("Amount")));
		this.amount.setHint(Component.literal("amount").withColor(0xFF6B7682));
		this.perTab[4].add(this.addRenderableWidget(Button.builder(Component.literal("Take from treasury"), b -> act("embezzle", null, 0, null, this.amount(), 0))
			.bounds(l + 220, t + 96, 170, 16).build()));
		this.perTab[4].add(this.addRenderableWidget(Button.builder(Component.literal("Donate to the town"), b -> act("deposit", null, 0, null, this.amount(), 0))
			.bounds(l + 220, t + 114, 170, 16).build()));
		this.perTab[4].add(this.addRenderableWidget(Button.builder(Component.literal("Hold a festival"), b -> act("festival", null, 0, null, 0, 0))
			.bounds(l + 8, t + 140, 190, 18).build()));
		this.perTab[4].add(this.addRenderableWidget(Button.builder(Component.literal("Take the town public"), b -> act("ipo", null, 0, null, 0, 0))
			.bounds(l + 202, t + 140, 190, 18).build()));
		this.perTab[4].add(this.addRenderableWidget(Button.builder(Component.literal("Confiscate all savings").withColor(Ui.RED), b -> {
			if (this.confirmConfiscate) {
				act("confiscate", null, 0, null, 0, 0);
				this.confirmConfiscate = false;
			} else {
				this.confirmConfiscate = true;
			}
		}).bounds(l + 8, t + 162, 190, 18).build()));
		// works: the wall, the streets, repairs
		this.perTab[6].add(this.wallButton = this.addRenderableWidget(Button.builder(Component.literal("Build the town wall"), b -> act("wall", null, 0, null, 0, 0))
			.bounds(l + 10, t + 84, 182, 16).build()));
		this.perTab[6].add(this.paveButton = this.addRenderableWidget(Button.builder(Component.literal("Pave the ways"), b -> act("streets", null, 1, null, 0, 0))
			.bounds(l + 10, t + 136, 89, 16).build()));
		this.perTab[6].add(this.lampButton = this.addRenderableWidget(Button.builder(Component.literal("Street lamps"), b -> act("streets", null, 2, null, 0, 0))
			.bounds(l + 103, t + 136, 89, 16).build()));
		this.perTab[6].add(this.autoRepairButton = this.addRenderableWidget(Button.builder(Component.literal("Auto-repair"), b -> {
			Dto.Govern d = ClientState.govern;
			if (d != null) {
				act("autorepair", null, d.works.autoRepair ? 0 : 1, null, 0, 0);
			}
		}).bounds(l + 10, t + 188, 89, 16).build()));
		this.perTab[6].add(this.addRenderableWidget(Button.builder(Component.literal("Repair all now"), b -> act("repair", null, 0, null, 0, 0))
			.bounds(l + 103, t + 188, 89, 16).build()));
		this.setTab(this.tab);
	}

	private long amount() {
		long v = Money.parse(this.amount.getValue());
		return Math.max(0, v);
	}

	private void step(Law law, int dir) {
		Dto.Govern d = ClientState.govern;
		if (d != null) {
			act("policy", law.key, Math.round((get(d.policies, law.key) + dir * law.step) * 100) / 100.0, null, 0, 0);
		}
	}

	public void setTab(int tab) {
		this.tab = tab;
		this.scroll = 0;
		for (int i = 0; i < TABS.length; i++) {
			this.tabButtons.get(i).active = i != tab;
			boolean editable = ClientState.govern != null && ClientState.govern.editable;
			for (Button b : this.perTab[i]) {
				b.visible = i == tab;
				b.active = editable;
			}
		}
		this.amount.visible = tab == 4;
	}

	@Override
	public void tick() {
		super.tick();
		if (++this.ticks % 40 == 0) {
			act("refresh", null, 0, null, 0, 0);
		}
		Dto.Govern d = ClientState.govern;
		if (d != null) {
			for (int i = 0; i < TOGGLES.length; i++) {
				boolean on = get(d.policies, TOGGLES[i].key) != 0;
				Button b = this.perTab[3].get(NUMBERS.length * 2 + i);
				b.setMessage(Component.literal(on ? "on" : "off").withColor(on ? Ui.GREEN : Ui.MUTED));
			}
			this.perTab[4].get(4).setMessage(Component.literal(this.confirmConfiscate ? "Really? Click again" : "Confiscate all savings").withColor(Ui.RED));
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.getFocused() instanceof EditBox box && !event.isEscape() && (box.keyPressed(event) || box.canConsumeInput())) {
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		this.scroll = Math.max(0, this.scroll - (int) Math.signum(sy) * 2);
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		Dto.Govern d = ClientState.govern;
		int l = (this.width - W) / 2;
		int t = (this.height - H) / 2;
		double mx = event.x() - l;
		double my = event.y() - t;
		if (d != null && this.tab == 1 && mx > 6 && mx < W - 6 && my > 52 && my < H - 16) {
			int i = (int) ((my - 52) / ROW) + this.scroll;
			if (i >= 0 && i < d.citizens.size()) {
				Dto.Action a = new Dto.Action();
				a.city = d.id;
				a.citizen = d.citizens.get(i).uuid;
				a.op = "view";
				ClientPlayNetworking.send(new Payloads.Action("citizen", Dto.GSON.toJson(a)));
				return true;
			}
		}
		if (d != null && d.editable && this.tab == 2 && mx > 112 && mx < 196 && my > 52) {
			int i = (int) ((my - 52) / ROW) + this.scroll;
			if (i >= 0 && i < d.buildings.size() && d.buildings.get(i).missing > 0 && d.buildings.get(i).repair < 0) {
				act("repair", null, 0, null, 0, d.buildings.get(i).id);
				return true;
			}
		}
		if (d != null && d.editable && this.tab == 2 && mx > W - 100 && mx < W - 54 && my > 52) {
			int i = (int) ((my - 52) / ROW) + this.scroll;
			if (i >= 0 && i < d.buildings.size() && d.buildings.get(i).complete && d.buildings.get(i).upgrading == 0 && d.buildings.get(i).tier < 3) {
				act("upgrade", null, 0, null, 0, d.buildings.get(i).id);
				return true;
			}
		}
		if (d != null && d.editable && this.tab == 2 && mx > W - 50 && mx < W - 8 && my > 52) {
			int i = (int) ((my - 52) / ROW) + this.scroll;
			if (i >= 0 && i < d.buildings.size() && !d.buildings.get(i).type.equals("town_hall")) {
				act("demolish", null, 0, null, 0, d.buildings.get(i).id);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
		super.extractBackground(g, mx, my, a);
		int l = (this.width - W) / 2;
		int t = (this.height - H) / 2;
		Ui.frame(g, l, t, W, H);
		Dto.Govern d = ClientState.govern;
		g.fill(l, t, l + W, t + 17, 0xFF2A1C0E);
		if (d == null) {
			return;
		}
		g.text(this.font, "◆ " + d.name.toUpperCase(Locale.ROOT), l + 6, t + 5, Ui.GOLD, false);
		String head = "Day " + d.day + " · governor " + d.governor + (d.strike ? " · STRIKE" : "") + (d.riot ? " · RIOTS" : "");
		Ui.right(g, this.font, head, l + W - 6, t + 5, d.riot || d.strike ? Ui.RED : Ui.TEXT);
		switch (this.tab) {
			case 0 -> this.overview(g, d, l, t);
			case 1 -> this.citizens(g, d, l, t, mx, my);
			case 2 -> this.buildings(g, d, l, t, mx, my);
			case 3 -> this.laws(g, d, l, t);
			case 4 -> this.treasury(g, d, l, t);
			case 5 -> this.supply(g, d, l, t);
			default -> this.works(g, d, l, t);
		}
		g.fill(l, t + H - 13, l + W, t + H, 0xFF2A1C0E);
		if (System.currentTimeMillis() - ClientState.messageTime < 8000) {
			g.text(this.font, ClientState.message, l + 6, t + H - 11, ClientState.messageOk ? Ui.GREEN : Ui.RED, false);
		} else if (!d.log.isEmpty()) {
			g.text(this.font, d.log.getFirst(), l + 6, t + H - 11, Ui.MUTED, false);
		}
	}

	private static int moodColor(double h) {
		return h >= 60 ? Ui.GREEN : h >= 35 ? 0xFFE8C547 : Ui.RED;
	}

	private void overview(GuiGraphicsExtractor g, Dto.Govern d, int l, int t) {
		Ui.panel(g, l + 6, t + 40, l + 196, t + 150, Ui.PANEL2);
		int y = t + 44;
		this.stat(g, l + 10, y, "Citizens", d.population + " (beds " + d.beds + ")", Ui.TEXT);
		this.stat(g, l + 10, y + 11, "Employed", d.employed + " of " + d.population, Ui.TEXT);
		this.stat(g, l + 10, y + 22, "Happiness", Math.round(d.happiness) + "%", moodColor(d.happiness));
		this.stat(g, l + 10, y + 33, "Treasury", Money.format(d.treasury), d.treasury >= 0 ? Ui.TEXT : Ui.RED);
		this.stat(g, l + 10, y + 44, "Yesterday", "+" + Money.number(d.income) + " / −" + Money.number(d.spending), Ui.MUTED);
		if (!d.ticker.isEmpty()) {
			this.stat(g, l + 10, y + 55, "Shares " + d.ticker, Money.format(d.sharePrice), Ui.BLUE);
		}
		if (!d.strategy.equals("none")) {
			this.stat(g, l + 10, y + 77, "Laws by", "the council · " + d.strategyTitle, 0xFFE8C547);
		}
		if (d.grievance > 2 || d.fear > 2) {
			this.stat(g, l + 10, y + 66, "Resentment", String.format(Locale.ROOT, "%.0f   fear %.0f", d.grievance, d.fear), Ui.RED);
		}
		// how people live: a bar of the four classes
		int bx = l + 10;
		int by = t + 128;
		int total = Math.max(1, d.tiers[0] + d.tiers[1] + d.tiers[2] + d.tiers[3]);
		int x = bx;
		for (int i = 0; i < 4; i++) {
			int w = 182 * d.tiers[i] / total;
			g.fill(x, by, x + w, by + 8, CitizenScreen.TIER_COLORS[i]);
			x += w;
		}
		Ui.small(g, this.font, d.tiers[0] + " well-off · " + d.tiers[1] + " comfortable · " + d.tiers[2] + " poor · " + d.tiers[3] + " destitute", bx, by + 11,
			Ui.MUTED);
		// charts
		List<Long> happy = new ArrayList<>();
		List<Long> money = new ArrayList<>();
		for (City.DayStat s : d.history) {
			happy.add(Math.round(s.happiness * 100));
			money.add(s.treasury);
		}
		g.text(this.font, "Happiness", l + 204, t + 41, Ui.MUTED, false);
		Ui.chart(g, this.font, happy, l + 202, t + 50, 192, 46);
		g.text(this.font, "Treasury", l + 204, t + 99, Ui.MUTED, false);
		Ui.chart(g, this.font, money, l + 202, t + 108, 192, 42);
		// the town log
		Ui.panel(g, l + 6, t + 154, l + W - 6, t + H - 16, Ui.PANEL);
		for (int i = 0; i < Math.min(7, d.log.size()); i++) {
			Ui.small(g, this.font, d.log.get(i), l + 10, t + 158 + i * 8, i == 0 ? Ui.TEXT : Ui.MUTED);
		}
	}

	private void stat(GuiGraphicsExtractor g, int x, int y, String label, String value, int color) {
		g.text(this.font, label, x, y, Ui.MUTED, false);
		Ui.right(g, this.font, value, x + 182, y, color);
	}

	private void citizens(GuiGraphicsExtractor g, Dto.Govern d, int l, int t, int mx, int my) {
		Ui.panel(g, l + 6, t + 40, l + W - 6, t + H - 16, Ui.PANEL);
		int[] cols = {10, 120, 210, 268, 306, 340};
		String[] heads = {"NAME", "JOB", "LIVING", "MOOD", "FOOD", "MONEY"};
		for (int i = 0; i < cols.length; i++) {
			Ui.small(g, this.font, heads[i], l + cols[i], t + 43, Ui.MUTED);
		}
		int rows = (H - 70) / ROW;
		this.scroll = Math.min(this.scroll, Math.max(0, d.citizens.size() - rows));
		for (int i = 0; i < rows && i + this.scroll < d.citizens.size(); i++) {
			Dto.CitizenRow r = d.citizens.get(i + this.scroll);
			int y = t + 52 + i * ROW;
			if (Ui.inside(mx, my, l + 7, y, l + W - 7, y + ROW)) {
				g.fill(l + 7, y, l + W - 7, y + ROW, 0xFF222D38);
			}
			int color = r.status.equals("FREE") ? Ui.TEXT : Ui.RED;
			g.text(this.font, r.name, l + cols[0], y + 2, color, false);
			Ui.small(g, this.font, r.status.equals("FREE") ? r.job : r.job + " (" + r.status.toLowerCase(Locale.ROOT) + ")", l + cols[1], y + 3, Ui.MUTED);
			Ui.small(g, this.font, CitizenScreen.TIERS[r.tier] + (r.homeless ? ", no home" : ""), l + cols[2], y + 3, CitizenScreen.TIER_COLORS[r.tier]);
			Ui.small(g, this.font, Math.round(r.happiness) + "%", l + cols[3], y + 3, moodColor(r.happiness));
			Ui.small(g, this.font, Math.round(r.food) + "%", l + cols[4], y + 3, r.food < 25 ? Ui.RED : Ui.MUTED);
			Ui.small(g, this.font, Money.plain(r.balance), l + cols[5], y + 3, Ui.TEXT);
		}
	}

	private void buildings(GuiGraphicsExtractor g, Dto.Govern d, int l, int t, int mx, int my) {
		Ui.panel(g, l + 6, t + 40, l + W - 6, t + H - 70, Ui.PANEL);
		int[] cols = {10, 112, 200, 262, 330};
		String[] heads = {"BUILDING", "STATE", "PEOPLE", "YESTERDAY", ""};
		for (int i = 0; i < cols.length; i++) {
			Ui.small(g, this.font, heads[i], l + cols[i], t + 43, Ui.MUTED);
		}
		int rows = (H - 120) / ROW;
		this.scroll = Math.min(this.scroll, Math.max(0, d.buildings.size() - rows));
		for (int i = 0; i < rows && i + this.scroll < d.buildings.size(); i++) {
			Dto.BuildingRow b = d.buildings.get(i + this.scroll);
			int y = t + 52 + i * ROW;
			g.text(this.font, b.title + " " + "I".repeat(Math.max(1, b.tier)), l + cols[0], y + 2, Ui.TEXT, false);
			String state = b.upgrading > 0 ? (!b.stalled.isEmpty() ? "halted: no money" : "to tier " + b.upgrading + ": " + b.progress + "%")
				: !b.complete ? (!b.stalled.isEmpty() ? "halted: no money" : "building " + b.progress + "%")
				: b.repair >= 0 ? (b.repairStalled ? "repair halted" : "repairing " + b.repair + "%")
				: b.missing > 0 ? "damaged (" + b.condition + "%)" : "in use";
			boolean damaged = b.complete && b.upgrading == 0 && (b.missing > 0 || b.repair >= 0);
			Ui.small(g, this.font, state, l + cols[1], y + 3, damaged ? (b.repair >= 0 && !b.repairStalled ? 0xFFE8C547 : Ui.RED)
				: b.upgrading == 0 && b.complete ? Ui.GREEN : !b.stalled.isEmpty() ? Ui.RED : 0xFFE8C547);
			if (damaged && Ui.inside(mx, my, l + cols[1], y, l + cols[2] - 4, y + ROW)) {
				this.hoverNote = b.missing + " blocks gone" + (b.foreign > 0 ? ", " + b.foreign + " taken by something else" : "")
					+ (b.repair >= 0 ? " - being repaired" : d.editable ? " - click to repair" : "");
			}
			String people = b.beds > 0 ? b.residents + "/" + b.beds + " live here" : b.slots > 0 ? b.workers + "/" + b.slots + " work" : "";
			Ui.small(g, this.font, people, l + cols[2], y + 3, Ui.MUTED);
			if (b.revenue != 0 || b.cost != 0) {
				Ui.small(g, this.font, "+" + Money.plain(b.revenue) + " −" + Money.plain(b.cost), l + cols[3], y + 3, b.revenue >= b.cost ? Ui.GREEN : Ui.RED);
			}
			if (d.editable && !b.type.equals("town_hall")) {
				boolean hover = Ui.inside(mx, my, l + W - 50, y, l + W - 8, y + ROW);
				Ui.small(g, this.font, "abandon", l + W - 46, y + 3, hover ? Ui.RED : 0xFF6B7682);
			}
			if (d.editable && b.complete && b.upgrading == 0 && b.tier < 3) {
				boolean can = b.upgradeBlocked.isEmpty();
				boolean hover = Ui.inside(mx, my, l + W - 100, y, l + W - 54, y + ROW);
				Ui.small(g, this.font, "↑ tier " + (b.tier + 1), l + W - 98, y + 3, can ? (hover ? Ui.GOLD : Ui.GREEN) : 0xFF6B7682);
				if (hover) {
					this.hoverNote = can ? "Upgrade for about " + Money.plain(b.upgradeCost) : "Not yet: " + b.upgradeBlocked;
				}
			}
		}
		g.text(this.font, "Plan a new building (the builders start on it next):", l + 8, t + H - 78, Ui.GOLD, false);
		if (this.hoverNote != null) {
			Ui.small(g, this.font, this.hoverNote, l + 8, t + H - 88, Ui.MUTED);
			this.hoverNote = null;
		}
	}

	private void laws(GuiGraphicsExtractor g, Dto.Govern d, int l, int t) {
		if (this.councilButton != null) {
			this.councilButton.setMessage(Component.literal("Laws by: " + (d.strategy.equals("none") ? "you" : "council · " + d.strategyTitle)));
			this.councilButton.active = d.editable;
		}
		if (this.loadButton != null) {
			this.loadButton.visible = d.op && this.tab == 3;
			this.loadButton.setMessage(Component.literal(d.keepLoaded == -1 ? "Load: -" : d.keepLoaded == 1 ? "Load: on" : "Load: off"));
		}
		Ui.panel(g, l + 6, t + 40, l + 198, t + H - 16, Ui.PANEL);
		Ui.panel(g, l + 202, t + 40, l + W - 6, t + H - 16, Ui.PANEL);
		for (int i = 0; i < NUMBERS.length; i++) {
			Law law = NUMBERS[i];
			int y = t + 44 + i * 16;
			g.text(this.font, law.label, l + 10, y + 3, Ui.TEXT, false);
			double v = get(d.policies, law.key);
			String shown = switch (law.unit) {
				case "$" -> Money.plain(Math.round(v * 100));
				case "%" -> (law.step < 1 ? String.format(Locale.ROOT, "%.2f", v) : String.valueOf(Math.round(v))) + "%";
				default -> Math.round(v) + " " + law.unit;
			};
			g.centeredText(this.font, shown, l + 157, y + 3, Ui.GOLD);
		}
		Ui.small(g, this.font, "Standard wage " + Money.format(d.baseWage) + "/day", l + 10, t + H - 26, Ui.MUTED);
		if (!d.strategy.equals("none")) {
			Ui.small(g, this.font, "The council sets these laws each morning.", l + 206, t + H - 42, 0xFFE8C547);
		}
		if (d.op) {
			Ui.small(g, this.font, d.keepsLoaded ? "kept loaded" : "sleeps when alone", l + 340, t + H - 42, Ui.MUTED);
		}
		for (int i = 0; i < TOGGLES.length; i++) {
			int y = t + 46 + i * 17;
			g.text(this.font, TOGGLES[i].label, l + 206, y + 3, Ui.TEXT, false);
		}
		if (!d.editable) {
			Ui.small(g, this.font, "Only the governor may change the laws.", l + 206, t + H - 26, Ui.RED);
		}
	}

	/** The food chain, link by link, with what to do about each gap. */
	private void supply(GuiGraphicsExtractor g, Dto.Govern d, int l, int t) {
		Ui.panel(g, l + 6, t + 40, l + W - 6, t + H - 16, Ui.PANEL);
		g.text(this.font, "Is everyone fed? The food chain, checked:", l + 10, t + 44, Ui.GOLD, false);
		int y = t + 58;
		for (String line : d.supply) {
			int lvl = line.charAt(0) - '0';
			int colour = lvl == 2 ? Ui.RED : lvl == 1 ? 0xFFE8C547 : Ui.GREEN;
			String text = (lvl == 2 ? "✖ " : lvl == 1 ? "! " : "✔ ") + line.substring(2);
			for (net.minecraft.util.FormattedCharSequence part : this.font.split(Component.literal(text), W - 24)) {
				if (y > t + H - 26) {
					return;
				}
				g.text(this.font, part, l + 12, y, colour, false);
				y += 10;
			}
			y += 2;
		}
	}

	/** The town's works: its wall, its streets, repairs, and the jobs in hand. */
	private void works(GuiGraphicsExtractor g, Dto.Govern d, int l, int t) {
		Dto.Works w = d.works;
		Ui.panel(g, l + 6, t + 40, l + 196, t + H - 16, Ui.PANEL);
		Ui.panel(g, l + 202, t + 40, l + W - 6, t + H - 16, Ui.PANEL);
		String[] roman = {"", "I", "II", "III"};
		// the wall
		g.text(this.font, "Town wall", l + 10, t + 44, Ui.GOLD, false);
		Ui.small(g, this.font, w.wallTier == 0 ? "None yet." : "Tier " + roman[w.wallTier] + ": " + w.wallStyle + ".", l + 10, t + 56, Ui.TEXT);
		if (w.wallRect != null) {
			Ui.small(g, this.font, (w.wallRect[2] - w.wallRect[0] + 1) + " × " + (w.wallRect[3] - w.wallRect[1] + 1) + " blocks; it moves out as the town grows.",
				l + 10, t + 65, Ui.MUTED);
		} else {
			Ui.small(g, this.font, "Wood at tier I, stone and timber at II, stone at III.", l + 10, t + 65, Ui.MUTED);
		}
		boolean wallBusy = w.projects.stream().anyMatch(p -> p.kind.equals("wall"));
		this.wallButton.active = d.editable && !wallBusy && (w.wallTier == 0 || w.wallTier < w.townTier);
		this.wallButton.setMessage(Component.literal(wallBusy ? "Wall: being built" : w.wallTier == 0 ? "Build the wall (~" + Money.plain(w.wallCost) + ")"
			: w.wallTier < w.townTier ? "Rebuild in stone: tier " + roman[w.townTier] : "Wall keeps up by itself"));
		// the streets
		g.text(this.font, "Streets", l + 10, t + 110, Ui.GOLD, false);
		Ui.small(g, this.font, "The ways are " + w.streetsText + (w.lamps > 0 ? " (" + w.lamps + " lamps)" : "") + ".", l + 10, t + 122, Ui.TEXT);
		this.paveButton.active = d.editable && w.streets == 0;
		this.paveButton.setMessage(Component.literal(w.streets >= 1 ? "Paved" : "Pave ~" + Money.plain(w.paveCost)));
		this.lampButton.active = d.editable && w.streets == 1;
		this.lampButton.setMessage(Component.literal(w.streets >= 2 ? "Lit" : w.streets == 1 ? "Lamps ~" + Money.plain(w.lampCost) : "Lamps (pave first)"));
		// repairs
		g.text(this.font, "Repairs", l + 10, t + 162, Ui.GOLD, false);
		Ui.small(g, this.font, w.damaged == 0 ? "Nothing damaged." : w.damaged + " building(s) damaged.", l + 10, t + 174, w.damaged == 0 ? Ui.GREEN : Ui.RED);
		this.autoRepairButton.setMessage(Component.literal(w.autoRepair ? "Auto-repair: on" : "Auto-repair: off").withColor(w.autoRepair ? Ui.GREEN : Ui.MUTED));
		// work in hand
		g.text(this.font, "Work in hand", l + 206, t + 44, Ui.GOLD, false);
		int y = t + 58;
		if (w.projects.isEmpty()) {
			Ui.small(g, this.font, "Nothing besides new buildings.", l + 206, y, Ui.MUTED);
		}
		for (Dto.ProjectRow p : w.projects) {
			if (y > t + H - 34) {
				break;
			}
			g.text(this.font, p.title.length() > 32 ? p.title.substring(0, 31) + "…" : p.title, l + 206, y, Ui.TEXT, false);
			Ui.small(g, this.font, (p.stalled ? "halted: no money · " : p.percent + "% · ") + p.jobs + " blocks · ~" + Money.plain(p.remaining) + " to go",
				l + 210, y + 10, p.stalled ? Ui.RED : Ui.MUTED);
			y += 22;
		}
		Ui.small(g, this.font, "Builders mend damage first, then new buildings, then the rest.", l + 206, t + H - 26, Ui.MUTED);
	}

	private void treasury(GuiGraphicsExtractor g, Dto.Govern d, int l, int t) {
		Ui.panel(g, l + 6, t + 40, l + W - 6, t + 92, Ui.PANEL2);
		g.text(this.font, "Treasury", l + 10, t + 46, Ui.MUTED, false);
		g.pose().pushMatrix();
		g.pose().translate(l + 10, t + 56);
		g.pose().scale(1.6F, 1.6F);
		g.text(this.font, Money.format(d.treasury), 0, 0, d.treasury >= 0 ? Ui.TEXT : Ui.RED, false);
		g.pose().popMatrix();
		Ui.small(g, this.font, "Your bank account: " + Money.format(d.playerBalance), l + 10, t + 80, Ui.MUTED);
		if (!d.ticker.isEmpty()) {
			Ui.right(g, this.font, "Listed as " + d.ticker + " · " + Money.format(d.sharePrice), l + W - 10, t + 46, Ui.BLUE);
		}
		Ui.right(g, this.font, "Yesterday +" + Money.plain(d.income) + " / −" + Money.plain(d.spending), l + W - 10, t + 58, Ui.MUTED);
		g.text(this.font, "Amount", l + 10, t + 108, Ui.MUTED, false);
		Ui.small(g, this.font, "A festival costs 10 per citizen: two days of joy, fireworks, and old grudges forgiven.", l + 8, t + 184, Ui.MUTED);
		Ui.small(g, this.font, "Going public sells 49 % of the town on the stock exchange; profits move the share price.", l + 8, t + 193, Ui.MUTED);
		Ui.small(g, this.font, "Confiscating savings fills the treasury - and turns the town against you.", l + 8, t + 202, Ui.MUTED);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
