package net.antwire.civitas.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Arrays;
import net.antwire.civitas.city.Building;
import net.antwire.civitas.city.BuildingType;
import net.antwire.civitas.city.City;
import net.antwire.civitas.city.CityManager;
import net.antwire.civitas.city.Construction;
import net.antwire.civitas.city.DailyCycle;
import net.antwire.civitas.city.Townlife;
import net.antwire.civitas.network.ServerNet;
import net.antwire.commerce.api.CommerceApi;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * /civitas list | info [town] | govern [town] | found &lt;name&gt; - and for operators: build, complete, day, immigrate.
 */
public final class CivitasCommands {
	private CivitasCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("civitas")
			.then(Commands.literal("list").executes(CivitasCommands::list))
			.then(Commands.literal("info").executes(c -> info(c, null))
				.then(Commands.argument("town", StringArgumentType.greedyString()).suggests(CivitasCommands::towns)
					.executes(c -> info(c, StringArgumentType.getString(c, "town")))))
			.then(Commands.literal("govern").executes(c -> govern(c, null))
				.then(Commands.argument("town", StringArgumentType.greedyString()).suggests(CivitasCommands::towns)
					.executes(c -> govern(c, StringArgumentType.getString(c, "town")))))
			.then(Commands.literal("found").then(Commands.argument("name", StringArgumentType.greedyString()).executes(CivitasCommands::found)))
			.then(Commands.literal("build").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("type", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(BuildingType.values()).map(BuildingType::id), b))
					.executes(CivitasCommands::build)))
			.then(Commands.literal("complete").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(CivitasCommands::complete))
			.then(Commands.literal("inspect").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
				City city = here(c, null);
				if (city == null) {
					return 0;
				}
				ServerLevel level = c.getSource().getLevel();
				StringBuilder out = new StringBuilder(city.name + ":");
				int damaged = 0;
				for (Building b : city.buildings) {
					if (b.complete && !b.upgrading()) {
						int n = net.antwire.civitas.city.Works.inspect(level, city, b, false);
						if (b.missing > 0 || b.foreign > 0) {
							damaged++;
							out.append("\n  ").append(b.type.title).append(" #").append(b.id).append(": ").append(b.condition).append("% (")
								.append(b.missing).append(" gone, ").append(b.foreign).append(" taken)").append(n > 0 && city.autoRepair ? ", repair queued" : "");
						}
					}
				}
				net.antwire.civitas.city.Walls.inspect(level, city, false);
				net.antwire.civitas.city.Streets.inspectLamps(level, city, false);
				if (damaged == 0) {
					out.append(" every building as planned");
				}
				for (net.antwire.civitas.city.Project p : city.projects) {
					out.append("\n  work: ").append(p.title).append(" ").append(p.percent()).append("% of ").append(p.jobs.size());
				}
				c.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
				return damaged;
			}))
			.then(Commands.literal("wall").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
				City city = here(c, null);
				if (city == null) {
					return 0;
				}
				int tier = Math.max(city.wallTier, city.tier());
				int[] rect = city.wallRect == null ? net.antwire.civitas.city.Walls.needed(city)
					: net.antwire.civitas.city.Walls.union(city.wallRect, net.antwire.civitas.city.Walls.needed(city));
				var p = net.antwire.civitas.city.Walls.start(c.getSource().getLevel(), city, tier, rect);
				c.getSource().sendSuccess(() -> Component.literal(p == null ? "The wall is as it should be" : p.title + ": " + p.jobs.size() + " blocks"), true);
				return 1;
			}))
			.then(Commands.literal("streets").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 2)).executes(c -> {
					City city = here(c, null);
					if (city == null) {
						return 0;
					}
					String msg = net.antwire.civitas.city.Streets.order(c.getSource().getLevel(), city,
						com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "level"));
					c.getSource().sendSuccess(() -> Component.literal(msg), true);
					return 1;
				})))
			.then(Commands.literal("council")
				.then(Commands.argument("strategy", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.Arrays.stream(net.antwire.civitas.city.Council.Strategy.values())
						.map(x -> x.name().toLowerCase(java.util.Locale.ROOT)), b))
					.executes(c -> {
						City city = here(c, null);
						if (city == null) {
							return 0;
						}
						boolean allowed = c.getSource().getEntity() instanceof ServerPlayer p && city.isGovernor(p.getUUID())
							|| c.getSource().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
						if (!allowed) {
							c.getSource().sendFailure(Component.literal("Only the governor (or an operator) may hand the town to its council"));
							return 0;
						}
						var s = net.antwire.civitas.city.Council.Strategy.byId(StringArgumentType.getString(c, "strategy"));
						city.strategy = s.name().toLowerCase(java.util.Locale.ROOT);
						city.log(s == net.antwire.civitas.city.Council.Strategy.NONE ? "The governor takes the town's affairs back"
							: "The council now governs the town: " + s.title.toLowerCase(java.util.Locale.ROOT));
						c.getSource().sendSuccess(() -> Component.literal(city.name + ": " + (s == net.antwire.civitas.city.Council.Strategy.NONE
							? "the governor makes the laws" : "the council governs (" + s.title + ") - " + s.description)), true);
						return 1;
					})))
			.then(Commands.literal("keeploaded").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("mode", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.List.of("on", "off", "default"), b))
					.executes(c -> {
						City city = here(c, null);
						if (city == null) {
							return 0;
						}
						String mode = StringArgumentType.getString(c, "mode");
						city.keepLoaded = mode.equals("on") ? Boolean.TRUE : mode.equals("off") ? Boolean.FALSE : null;
						c.getSource().sendSuccess(() -> Component.literal(city.name + (city.keepsLoaded() ? " stays loaded and lives on while nobody is near"
							: " sleeps while nobody is near") + (city.keepLoaded == null ? " (server default)" : "")), true);
						return 1;
					})))
			.then(Commands.literal("upgrade").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("type", StringArgumentType.word()).executes(c -> {
					City city = here(c, null);
					BuildingType t = BuildingType.byId(StringArgumentType.getString(c, "type"));
					if (city == null || t == null) {
						return 0;
					}
					ServerLevel level = c.getSource().getLevel();
					// the lowest-tier building of the type that can go up
					Building best = null;
					String why = "there is no " + t.title.toLowerCase();
					for (Building b : city.of(t, true)) {
						String no = Townlife.whyNotUpgrade(level, city, b);
						if (no == null && (best == null || b.tier < best.tier)) {
							best = b;
						} else if (no != null) {
							why = no;
						}
					}
					if (best == null) {
						String w = why;
						c.getSource().sendFailure(Component.literal("Can't upgrade: " + w));
						return 0;
					}
					Townlife.upgrade(level, city, best);
					Building up = best;
					c.getSource().sendSuccess(() -> Component.literal(t.title + " to be raised to tier " + up.targetTier), true);
					return 1;
				})))
			.then(Commands.literal("raid").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
				City city = here(c, null);
				if (city == null) {
					return 0;
				}
				int n = net.antwire.civitas.entity.ai.Military.raid(c.getSource().getLevel(), city);
				c.getSource().sendSuccess(() -> Component.literal(n + " raiders sent against " + city.name), true);
				return n;
			}))
			.then(Commands.literal("access").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
				City city = here(c, null);
				if (city == null) {
					return 0;
				}
				int bad = 0;
				for (Building b : java.util.List.copyOf(city.buildings)) {
					if (!b.complete) {
						continue;
					}
					var a = net.antwire.civitas.city.Townlife.checkAccess(c.getSource().getLevel(), city, b);
					String line = b.type.title + ": " + (a.blocked() == null ? "OK" : a.blocked()) + (a.cleared() + a.filled() > 0 ? " (" + a.cleared()
						+ " cleared, " + a.filled() + " filled)" : "") + (a.rerouted() ? ", new way" : "");
					if (a.blocked() != null) {
						bad++;
					}
					c.getSource().sendSuccess(() -> Component.literal(line).withStyle(a.blocked() == null ? ChatFormatting.GREEN : ChatFormatting.RED), false);
				}
				return bad;
			}))
			.then(Commands.literal("day").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(CivitasCommands::day))
			.then(Commands.literal("immigrate").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("count", IntegerArgumentType.integer(1, 40)).executes(CivitasCommands::immigrate))));
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> towns(CommandContext<CommandSourceStack> c,
		com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
		CityManager m = CityManager.get();
		return SharedSuggestionProvider.suggest(m == null ? java.util.List.of() : m.cities.values().stream().map(x -> x.name).toList(), b);
	}

	private static City here(CommandContext<CommandSourceStack> c, String name) {
		CityManager m = CityManager.get();
		if (m == null) {
			return null;
		}
		if (name != null) {
			return m.byName(name);
		}
		City at = m.cityAt(c.getSource().getLevel(), net.minecraft.core.BlockPos.containing(c.getSource().getPosition()));
		if (at == null && c.getSource().getEntity() instanceof ServerPlayer p) {
			at = m.governedBy(p.getUUID());
		}
		return at;
	}

	private static int list(CommandContext<CommandSourceStack> c) {
		CityManager m = CityManager.get();
		if (m == null || m.cities.isEmpty()) {
			c.getSource().sendSuccess(() -> Component.literal("No towns yet - found one with a town charter"), false);
			return 0;
		}
		for (City city : m.cities.values()) {
			c.getSource().sendSuccess(() -> Component.literal(city.name).withStyle(ChatFormatting.GOLD)
				.append(Component.literal(String.format(java.util.Locale.ROOT, "  pop %d, happiness %.0f%%, governor %s, at %d %d %d", city.population(),
					city.averageHappiness(), city.governorName, city.center.getX(), city.center.getY(), city.center.getZ())).withStyle(ChatFormatting.GRAY)), false);
		}
		return m.cities.size();
	}

	private static int info(CommandContext<CommandSourceStack> c, String name) {
		City city = here(c, name);
		if (city == null) {
			c.getSource().sendFailure(Component.literal("No such town here"));
			return 0;
		}
		c.getSource().sendSuccess(() -> Component.literal(city.name + " - day " + city.day).withStyle(ChatFormatting.GOLD), false);
		c.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT, "Population %d (beds %d), happiness %.0f%%, treasury %s",
			city.population(), city.beds(), city.averageHappiness(), CommerceApi.format(CommerceApi.balance(city.account())))), false);
		for (Building b : city.buildings) {
			c.getSource().sendSuccess(() -> Component.literal(" - " + b.title() + (b.stalled.isEmpty() ? "" : " [" + b.stalled + "]")).withStyle(ChatFormatting.GRAY),
				false);
		}
		for (int i = 0; i < Math.min(5, city.log.size()); i++) {
			String line = city.log.get(i);
			c.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.DARK_GRAY), false);
		}
		return 1;
	}

	private static int govern(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		City city = here(c, name);
		if (city == null) {
			c.getSource().sendFailure(Component.literal("No such town"));
			return 0;
		}
		ServerNet.openGovern(p, city);
		return 1;
	}

	private static int found(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		CityManager m = CityManager.get();
		if (m == null) {
			return 0;
		}
		if (!p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
			&& !p.getMainHandItem().is(net.antwire.civitas.registry.ModItems.TOWN_CHARTER)) {
			c.getSource().sendFailure(Component.literal("You need a town charter in hand"));
			return 0;
		}
		Direction front = p.getDirection().getOpposite();
		var pos = p.blockPosition().relative(p.getDirection(), 10);
		StringBuilder why = new StringBuilder();
		City city = m.found(c.getSource().getLevel(), p, StringArgumentType.getString(c, "name"), pos, front, why);
		if (city == null) {
			c.getSource().sendFailure(Component.literal(why.toString()));
			return 0;
		}
		c.getSource().sendSuccess(() -> Component.literal("Founded " + city.name).withStyle(ChatFormatting.GOLD), true);
		return 1;
	}

	private static int build(CommandContext<CommandSourceStack> c) {
		City city = here(c, null);
		BuildingType t = BuildingType.byId(StringArgumentType.getString(c, "type"));
		if (city == null || t == null) {
			c.getSource().sendFailure(Component.literal("Stand in a town and name a building type"));
			return 0;
		}
		Building b = CityManager.get().plan(c.getSource().getLevel(), city, t);
		if (b == null) {
			c.getSource().sendFailure(Component.literal("No room for it"));
			return 0;
		}
		c.getSource().sendSuccess(() -> Component.literal(t.title + " planned at " + b.entrance().toShortString()), true);
		return 1;
	}

	/** Builds every pending building at once (for testing and impatient operators). */
	private static int complete(CommandContext<CommandSourceStack> c) {
		City city = here(c, null);
		if (city == null) {
			return 0;
		}
		ServerLevel level = c.getSource().getLevel();
		int n = 0;
		for (Building b : java.util.List.copyOf(city.buildings)) {
			if (b.complete && !b.upgrading()) {
				continue;
			}
			for (Construction.Step s : Construction.steps(b)) {
				Construction.applyFree(level, city, s);
			}
			if (b.upgrading()) {
				Townlife.upgraded(level, city, b);
			} else {
				Townlife.complete(level, city, b);
			}
			n++;
		}
		int works = city.projects.size();
		net.antwire.civitas.city.Works.completeAll(level, city);
		int done = n;
		c.getSource().sendSuccess(() -> Component.literal("Completed " + done + " building(s) and " + works + " other work(s)"), true);
		return n + works;
	}

	private static int day(CommandContext<CommandSourceStack> c) {
		City city = here(c, null);
		if (city == null) {
			return 0;
		}
		city.day++;
		DailyCycle.run(c.getSource().getLevel(), city, CityManager.get());
		c.getSource().sendSuccess(() -> Component.literal("A day passed in " + city.name), true);
		return 1;
	}

	private static int immigrate(CommandContext<CommandSourceStack> c) {
		City city = here(c, null);
		if (city == null) {
			return 0;
		}
		int n = IntegerArgumentType.getInteger(c, "count");
		for (int i = 0; i < n; i++) {
			CityManager.get().immigrate(c.getSource().getLevel(), city, null, true);
		}
		c.getSource().sendSuccess(() -> Component.literal(n + " settlers arrived in " + city.name), true);
		return n;
	}
}
