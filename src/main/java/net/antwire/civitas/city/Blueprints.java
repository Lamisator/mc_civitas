package net.antwire.civitas.city;

import java.util.HashMap;
import java.util.Map;
import net.antwire.civitas.city.Architect.Body;
import net.antwire.civitas.city.Architect.Furnisher;
import net.antwire.civitas.city.Architect.Style;
import net.antwire.civitas.city.Blueprint.Builder;
import net.antwire.civitas.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The town's architecture, in three tiers. Every building has a plot the size of its grandest version, reserved when it
 * is planned; the first tier stands at the front of it, and each upgrade grows the building backwards and sideways
 * around the same front door: more storeys (four blocks each), sometimes a cellar, more beds and workplaces, finer
 * materials. Timber framing, white plaster and steep roofs - a German small town.
 */
public final class Blueprints {
	public static final int TIERS = 3;
	private static final Map<Integer, Blueprint> CACHE = new HashMap<>();

	private Blueprints() {
	}

	/** The first tier. */
	public static Blueprint of(BuildingType type) {
		return of(type, 1);
	}

	public static Blueprint of(BuildingType type, int tier) {
		int t = Math.max(1, Math.min(TIERS, tier));
		return CACHE.computeIfAbsent(type.ordinal() * 8 + t, k -> create(type, t));
	}

	private static Blueprint create(BuildingType type, int t) {
		return switch (type) {
			case TOWN_HALL -> townHall(t);
			case HOUSE -> house(t);
			case FARM -> farm(t);
			case BAKERY -> bakery(t);
			case BUTCHER -> butcher(t);
			case BLACKSMITH -> blacksmith(t);
			case MINE -> mine(t);
			case LUMBER_MILL -> lumberMill(t);
			case BANK -> bank(t);
			case SHERIFF -> sheriff(t);
			case PRISON -> prison(t);
			case FACTORY -> factory(t);
			case TAVERN -> tavern(t);
			case BARRACKS -> barracks(t);
		};
	}

	private static int pick(int t, int a, int b, int c) {
		return t == 1 ? a : t == 2 ? b : c;
	}

	private static BlockState shopCounter() {
		return Builder.facing(net.antwire.commerce.registry.ModBlocks.SHOP_COUNTER, Direction.SOUTH);
	}

	/** The shop counter in the middle of the room, two steps in from the door. */
	private static void shop(Body body) {
		int z = body.z1 - 3;
		body.b.set(body.doorX, 1, z, shopCounter());
		body.b.mark("shop", body.doorX, 1, z);
		for (int dx = -1; dx <= 1; dx++) {
			body.reserve(1, body.doorX + dx, z);
			body.reserve(1, body.doorX + dx, z + 1);
		}
	}

	/** A plot and a body in one go: plot w×d, body width×depth with its front at frontZ. */
	private static Body body(int w, int d, int frontZ, int width, int depth, int storeys, boolean cellar, Style st, int extra) {
		Builder b = Architect.plot(w, d, depth, storeys, cellar, extra);
		return Architect.body(b, st, width, depth, frontZ, storeys, cellar);
	}

	/** Grander houses: stone ground floor, slate roof, shutters, lights by the door. */
	private static Style grand(Style st, int t) {
		if (t < 3) {
			return st;
		}
		return st.withRoof(Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILES);
	}

	private static void dress(Body body, int t) {
		if (t >= 2) {
			Architect.doorLights(body);
		}
		if (t >= 3) {
			Architect.shutters(body, Blocks.SPRUCE_TRAPDOOR);
		}
	}

	private static void decorate(Furnisher f, Block... blocks) {
		for (Block b : blocks) {
			f.put(b);
		}
	}

	// ------------------------------------------------------------------ homes

	/** 4, 6 or 8 beds; the grandest has three floors and a cellar for provisions. */
	private static Blueprint house(int t) {
		Style st = grand(Architect.PLASTER, t);
		if (t >= 3) {
			st = st.withWall(Blocks.STONE_BRICKS);
		}
		Body body = body(13, 13, 11, pick(t, 9, 9, 11), pick(t, 7, 9, 11), pick(t, 1, 2, 3), t >= 3, st, 0);
		Block[] colours = {Blocks.BED.red(), Blocks.BED.blue(), Blocks.BED.green(), Blocks.BED.yellow(), Blocks.BED.orange(), Blocks.BED.cyan(),
			Blocks.BED.purple(), Blocks.BED.lime()};
		int beds = BuildingType.HOUSE.beds(t);
		int placed = 0;
		Furnisher ground = new Furnisher(body, 0);
		if (t == 1) {
			for (int i = 0; i < 4; i++) {
				ground.bed(colours[placed++]);
			}
			decorate(ground, Blocks.CRAFTING_TABLE, Blocks.BARREL, Blocks.FLOWER_POT);
		} else {
			// downstairs the kitchen and parlour, upstairs the bedrooms
			decorate(ground, Blocks.CRAFTING_TABLE, Blocks.SMOKER, Blocks.BARREL, Blocks.BOOKSHELF, Blocks.FLOWER_POT);
			if (t == 2) {
				for (int i = 0; i < 2; i++) {
					ground.bed(colours[placed++]);
				}
			}
			for (int k = 1; k < body.storeys && placed < beds; k++) {
				Furnisher up = new Furnisher(body, k);
				for (int i = 0; i < 4 && placed < beds; i++) {
					if (up.bed(colours[placed]) != null) {
						placed++;
					}
				}
				decorate(up, Blocks.BARREL, Blocks.FLOWER_POT);
			}
		}
		if (body.cellar) {
			Furnisher cellar = new Furnisher(body, -1);
			for (int i = 0; i < 4; i++) {
				cellar.top(cellar.put(Blocks.BARREL), Builder.facing(Blocks.BARREL, Direction.UP));
			}
			decorate(cellar, Blocks.COMPOSTER, Blocks.HAY_BLOCK);
		}
		body.b.mark("home", body.doorX, 1, body.z1 - 2);
		body.b.set(body.doorX - 2, 1, body.z1 + 1, Blocks.POPPY);
		body.b.set(body.doorX + 2, 1, body.z1 + 1, Blocks.DANDELION);
		dress(body, t);
		return body.b.build();
	}

	// ------------------------------------------------------------------ the town hall

	/** The clerk's desk and the ledger, the council chamber upstairs, the archive and a clock tower at the third tier. */
	private static Blueprint townHall(int t) {
		Style st = new Style(Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.DYED_TERRACOTTA.white(), Blocks.DARK_OAK_LOG,
			Blocks.DARK_OAK_DOOR, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_PLANKS, Blocks.STONE_BRICKS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_FENCE, true);
		st = grand(st, t);
		int depth = pick(t, 9, 11, 13);
		Body body = body(19, 17, 13, pick(t, 13, 15, 17), depth, pick(t, 1, 2, 3), t >= 3, st, t >= 3 ? 9 : 0);
		Builder b = body.b;
		// the clerk's desk at the back, facing the door
		int dz = body.z0 + 1;
		b.set(body.doorX, 1, dz, Builder.facing(ModBlocks.TOWN_LEDGER, Direction.SOUTH));
		b.set(body.doorX - 1, 1, dz, Builder.stairs(Blocks.DARK_OAK_STAIRS, Direction.NORTH, true));
		b.set(body.doorX + 1, 1, dz, Builder.stairs(Blocks.DARK_OAK_STAIRS, Direction.NORTH, true));
		b.set(body.doorX - 1, 2, dz, Builder.facing(Blocks.LANTERN, Direction.UP).setValue(BlockStateProperties.HANGING, false));
		b.mark("ledger", body.doorX, 1, dz);
		b.mark("workblock", body.doorX, 1, dz);
		b.mark("work", body.doorX, 1, dz + 1);
		for (int dx = -1; dx <= 1; dx++) {
			body.reserve(1, body.doorX + dx, dz + 1);
		}
		// benches for the citizens
		for (int z = dz + 3; z < body.z1 - 2; z += 2) {
			for (int x = body.doorX - 4; x <= body.doorX + 4; x++) {
				if (Math.abs(x - body.doorX) < 2 || body.isReserved(1, x, z) || x <= body.x0 || x >= body.x1) {
					continue;
				}
				b.set(x, 1, z, Builder.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
				body.reserve(1, x, z);
			}
		}
		Furnisher ground = new Furnisher(body, 0);
		ground.storage();
		ground.storage();
		decorate(ground, Blocks.BARREL, Blocks.BARREL, Blocks.BOOKSHELF);
		for (int k = 1; k < body.storeys; k++) {
			Furnisher up = new Furnisher(body, k);
			if (k == 1) {
				// the council chamber: a long table and chairs
				int z = body.midZ();
				for (int x = body.doorX - 2; x <= body.doorX + 2; x++) {
					b.set(x, up.y, z, Blocks.DARK_OAK_FENCE);
					b.set(x, up.y + 1, z, Blocks.DARK_OAK_PRESSURE_PLATE);
					b.set(x, up.y, z - 1, Builder.stairs(Blocks.DARK_OAK_STAIRS, Direction.SOUTH, false));
					b.set(x, up.y, z + 1, Builder.stairs(Blocks.DARK_OAK_STAIRS, Direction.NORTH, false));
				}
				decorate(up, Blocks.BOOKSHELF, Blocks.BOOKSHELF, Blocks.LECTERN, Blocks.BOOKSHELF);
			} else {
				// the archive
				while (up.left() > 6) {
					up.put(Blocks.BOOKSHELF);
				}
			}
		}
		if (body.cellar) {
			Furnisher cellar = new Furnisher(body, -1);
			decorate(cellar, Blocks.GOLD_BLOCK, Blocks.IRON_BLOCK, Blocks.BARREL, Blocks.BARREL, Blocks.CHEST, Blocks.BARREL);
		}
		// the square in front
		int pz = body.z1;
		b.fill(body.doorX - 3, 0, pz + 1, body.doorX + 3, 0, pz + 3, Blocks.STONE_BRICKS);
		b.fill(body.doorX - 2, 0, pz + 2, body.doorX + 2, 0, pz + 3, Blocks.CHISELED_STONE_BRICKS);
		b.mark("plaza", body.doorX, 1, pz + 3);
		b.set(body.doorX + 4, 1, pz + 2, Blocks.STONE_BRICK_WALL);
		b.set(body.doorX + 4, 2, pz + 2, Builder.facing(Blocks.BELL, Direction.SOUTH));
		b.set(body.doorX - 3, 3, pz + 1, Builder.facing(Blocks.WALL_BANNER.red(), Direction.SOUTH));
		b.set(body.doorX + 3, 3, pz + 1, Builder.facing(Blocks.WALL_BANNER.red(), Direction.SOUTH));
		if (t >= 2) {
			b.set(body.doorX - 5, 1, pz + 2, Blocks.STONE_BRICK_WALL);
			b.set(body.doorX - 5, 2, pz + 2, Builder.facing(Blocks.LANTERN, Direction.UP).setValue(BlockStateProperties.HANGING, false));
		}
		if (t >= 3) {
			clockTower(body);
		}
		dress(body, t);
		return b.build();
	}

	/** A square stone tower rising out of the ridge, with the town bell under a pointed roof. */
	private static void clockTower(Body body) {
		Builder b = body.b;
		int cx = body.doorX;
		int cz = body.midZ();
		int ridge = body.roofY + (body.z1 - body.z0 + 2) / 2;
		int top = ridge + 5;
		for (int y = body.roofY; y <= top; y++) {
			for (int x = cx - 1; x <= cx + 1; x++) {
				for (int z = cz - 1; z <= cz + 1; z++) {
					boolean corner = Math.abs(x - cx) == 1 && Math.abs(z - cz) == 1;
					boolean centre = x == cx && z == cz;
					BlockState s;
					if (corner) {
						s = Builder.log(Blocks.DARK_OAK_LOG, Direction.Axis.Y);
					} else if (centre) {
						s = Blocks.AIR.defaultBlockState();
					} else if (y >= top - 2 && y < top) {
						s = Blocks.AIR.defaultBlockState(); // the belfry's openings
					} else if (y == ridge + 2) {
						s = Blocks.TARGET.defaultBlockState(); // the clock faces
					} else {
						s = Blocks.STONE_BRICKS.defaultBlockState();
					}
					b.set(x, y, z, s);
				}
			}
		}
		b.set(cx, top - 1, cz, Builder.facing(Blocks.BELL, Direction.SOUTH).setValue(BlockStateProperties.BELL_ATTACHMENT,
			net.minecraft.world.level.block.state.properties.BellAttachType.CEILING));
		b.set(cx, top, cz, Blocks.STONE_BRICKS);
		// pointed roof
		for (int x = cx - 1; x <= cx + 1; x++) {
			for (int z = cz - 1; z <= cz + 1; z++) {
				if (x == cx && z == cz) {
					continue;
				}
				Direction face = Math.abs(x - cx) >= Math.abs(z - cz) ? (x < cx ? Direction.EAST : Direction.WEST) : (z < cz ? Direction.SOUTH : Direction.NORTH);
				b.set(x, top + 1, z, Builder.stairs(Blocks.DEEPSLATE_TILE_STAIRS, face, false));
			}
		}
		b.set(cx, top + 1, cz, Blocks.DEEPSLATE_TILES);
		b.set(cx, top + 2, cz, Blocks.IRON_BARS);
	}

	// ------------------------------------------------------------------ the land

	/** Fields around a water channel, fenced; bigger with each tier, with a barn at the back at the third. */
	private static Blueprint farm(int t) {
		int w = 15;
		int d = 15;
		int n = pick(t, 11, 13, 15);
		Builder b = Blueprint.builder(w, 6, d);
		int x0 = (w - n) / 2;
		int x1 = x0 + n - 1;
		int z1 = d - 1;
		int z0 = z1 - n + 1;
		int mid = w / 2;
		Block fence = t >= 2 ? Blocks.SPRUCE_FENCE : Blocks.OAK_FENCE;
		int barnZ = t >= 3 ? z0 + 3 : z0;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
				for (int y = 1; y <= 4; y++) {
					b.set(x, y, z, Blocks.AIR);
				}
				if (edge) {
					b.set(x, 0, z, Blocks.GRASS_BLOCK);
					b.set(x, 1, z, fence);
				} else if (z == z1 - 1) {
					b.set(x, 0, z, Blocks.DIRT_PATH);
				} else if (z < barnZ) {
					b.set(x, 0, z, Blocks.GRASS_BLOCK);
				} else if (x == mid || t >= 3 && (x == x0 + 3 || x == x1 - 3)) {
					b.set(x, 0, z, Blocks.WATER);
					b.set(x, 1, z, Blocks.OAK_SLAB);
				} else {
					b.set(x, 0, z, Blocks.FARMLAND);
					b.mark("field", x, 1, z);
				}
			}
		}
		b.set(mid, 1, z1, Builder.facing(Blocks.OAK_FENCE_GATE, Direction.SOUTH));
		b.mark("entrance", mid, 1, z1 + 1);
		b.set(x0 + 1, 1, z1 - 1, Blocks.COMPOSTER);
		b.mark("workblock", x0 + 1, 1, z1 - 1);
		b.mark("work", x0 + 2, 1, z1 - 1);
		b.set(x1 - 1, 1, z1 - 1, Builder.facing(Blocks.CHEST, Direction.WEST));
		b.mark("storage", x1 - 1, 1, z1 - 1);
		b.set(x1 - 2, 1, z1 - 1, Blocks.HAY_BLOCK);
		b.set(x0 + 3, 1, z1 - 1, Blocks.HAY_BLOCK);
		b.set(x0 + 3, 2, z1 - 1, Builder.facing(Blocks.CARVED_PUMPKIN, Direction.SOUTH));
		for (int[] c : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
			b.set(c[0], 2, c[1], Blocks.LANTERN);
		}
		if (t >= 2) {
			// lanterns along the fence and a scarecrow
			for (int x = x0 + 4; x < x1; x += 4) {
				b.set(x, 2, z0, Blocks.LANTERN);
			}
			b.set(mid + 2, 1, z1 - 4, Blocks.OAK_FENCE);
			b.set(mid + 2, 2, z1 - 4, Blocks.HAY_BLOCK);
			b.set(mid + 2, 3, z1 - 4, Builder.facing(Blocks.CARVED_PUMPKIN, Direction.SOUTH));
			b.marks.get("field").removeIf(p -> p.getX() == mid + 2 && p.getZ() == z1 - 4);
			b.set(mid + 2, 0, z1 - 4, Blocks.DIRT);
		}
		if (t >= 3) {
			// the barn along the back fence: hay, a cart, a second composter
			for (int x = x0 + 1; x < x1; x++) {
				for (int z = z0 + 1; z < barnZ; z++) {
					b.set(x, 0, z, Blocks.SPRUCE_PLANKS);
					boolean side = x == x0 + 1 || x == x1 - 1;
					b.set(x, 1, z, side ? Builder.log(Blocks.SPRUCE_LOG, Direction.Axis.Y) : Blocks.AIR.defaultBlockState());
					b.set(x, 2, z, side ? Builder.log(Blocks.SPRUCE_LOG, Direction.Axis.Y) : Blocks.AIR.defaultBlockState());
					b.set(x, 3, z, Blocks.SPRUCE_SLAB);
				}
			}
			for (int x = x0 + 2; x < x1 - 1; x += 2) {
				b.set(x, 1, z0 + 1, Blocks.HAY_BLOCK);
			}
			b.set(x0 + 3, 1, z0 + 2, Blocks.COMPOSTER);
		}
		return b.build();
	}

	// ------------------------------------------------------------------ trades

	private static Blueprint bakery(int t) {
		Style st = grand(Architect.PLASTER.withRoof(Blocks.BRICK_STAIRS, Blocks.BRICKS), t);
		Body body = body(13, 11, 9, pick(t, 7, 9, 11), pick(t, 7, 9, 9), pick(t, 1, 2, 2), t >= 3, st, 2);
		Architect.chimney(body, body.x0 + 1, body.z0 + 1, Blocks.BRICKS);
		shop(body);
		Furnisher f = new Furnisher(body, 0);
		f.work(Blocks.SMOKER);
		f.put(Blocks.FURNACE);
		f.storage();
		decorate(f, Blocks.BARREL, Blocks.HAY_BLOCK);
		if (t >= 2) {
			f.put(Blocks.FURNACE);
			Furnisher up = new Furnisher(body, 1);
			up.bed(Blocks.BED.white());
			decorate(up, Blocks.HAY_BLOCK, Blocks.HAY_BLOCK, Blocks.BARREL, Blocks.BARREL, Blocks.CRAFTING_TABLE);
		}
		if (body.cellar) {
			Furnisher c = new Furnisher(body, -1);
			for (int i = 0; i < 6; i++) {
				c.top(c.put(Blocks.HAY_BLOCK), Blocks.HAY_BLOCK.defaultBlockState());
			}
		}
		dress(body, t);
		return body.b.build();
	}

	private static Blueprint butcher(int t) {
		Style st = grand(Architect.PLASTER, t);
		int depth = pick(t, 7, 9, 9);
		Body body = body(13, 17, 15, pick(t, 7, 9, 11), depth, pick(t, 1, 2, 2), t >= 3, st, 0);
		Builder b = body.b;
		// the pen behind the shop
		int px0 = body.x0;
		int px1 = body.x1;
		int pz0 = 1;
		int pz1 = body.z0 - 1;
		for (int x = px0; x <= px1; x++) {
			for (int z = pz0; z <= pz1; z++) {
				boolean ring = x == px0 || x == px1 || z == pz0;
				b.set(x, 0, z, Blocks.GRASS_BLOCK);
				b.set(x, 1, z, ring ? Blocks.OAK_FENCE.defaultBlockState() : Blocks.AIR.defaultBlockState());
				b.set(x, 2, z, Blocks.AIR);
			}
		}
		b.set(px0 + 1, 1, pz0 + 1, Blocks.HAY_BLOCK);
		b.set(px1 - 1, 1, pz0 + 1, Blocks.WATER_CAULDRON.defaultBlockState().setValue(BlockStateProperties.LEVEL_CAULDRON, 3));
		b.mark("pen", px0 + 1, 1, pz0 + 1);
		b.mark("pen", px1 - 1, 1, pz1);
		b.door(body.doorX, 1, body.z0, Blocks.SPRUCE_DOOR, Direction.SOUTH);
		b.marks.get("door").removeLast();
		b.mark("backdoor", body.doorX, 1, body.z0);
		body.reserve(1, body.doorX, body.z0 + 1);
		shop(body);
		Furnisher f = new Furnisher(body, 0);
		f.work(Blocks.SMOKER);
		f.storage();
		decorate(f, Blocks.SMOOTH_STONE_SLAB, Blocks.BARREL);
		if (t >= 2) {
			Furnisher up = new Furnisher(body, 1);
			up.bed(Blocks.BED.red());
			decorate(up, Blocks.BARREL, Blocks.BARREL, Blocks.SMOKER, Blocks.CRAFTING_TABLE);
		}
		if (body.cellar) {
			// the cold store
			Furnisher c = new Furnisher(body, -1);
			decorate(c, Blocks.PACKED_ICE, Blocks.PACKED_ICE, Blocks.BARREL, Blocks.PACKED_ICE, Blocks.BARREL, Blocks.PACKED_ICE);
		}
		dress(body, t);
		return b.build();
	}

	private static Blueprint blacksmith(int t) {
		Style st = new Style(Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.DYED_TERRACOTTA.white(), Blocks.SPRUCE_LOG, Blocks.SPRUCE_DOOR,
			Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_PLANKS, Blocks.STONE_BRICKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_FENCE, true);
		st = grand(st, t);
		Body body = body(13, 11, 9, pick(t, 7, 9, 11), pick(t, 7, 9, 9), pick(t, 1, 2, 2), false, st, 2);
		Architect.chimney(body, body.x0 + 1, body.z0 + 1, Blocks.COBBLESTONE);
		// a wide doorway: the forge opens to the street
		for (int dx : new int[]{-1, 1}) {
			body.b.set(body.doorX + dx, 1, body.z1, Blocks.AIR);
			body.b.set(body.doorX + dx, 2, body.z1, Blocks.AIR);
		}
		shop(body);
		Furnisher f = new Furnisher(body, 0);
		f.put(Blocks.BLAST_FURNACE);
		f.work(Blocks.ANVIL);
		f.put(Blocks.SMITHING_TABLE);
		f.storage();
		f.put(Blocks.GRINDSTONE);
		f.top(f.put(Blocks.CAULDRON), Blocks.AIR.defaultBlockState());
		if (t >= 2) {
			Furnisher up = new Furnisher(body, 1);
			up.bed(Blocks.BED.gray());
			decorate(up, Blocks.IRON_BLOCK, Blocks.BARREL, Blocks.BARREL, Blocks.CHEST, Blocks.ANVIL);
		}
		if (t >= 3) {
			f.put(Blocks.BLAST_FURNACE);
			f.put(Blocks.ANVIL);
		}
		dress(body, t);
		return body.b.build();
	}

	/** The shaft head; the miners' staircase goes down through its floor (always at the same place behind the door). */
	private static Blueprint mine(int t) {
		Style st = new Style(Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG, Blocks.SPRUCE_DOOR,
			Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_FENCE, true);
		Body body = body(13, 11, 9, pick(t, 7, 9, 11), pick(t, 7, 9, 9), pick(t, 1, 2, 3), false, st, 0);
		Builder b = body.b;
		int sz = body.z1 - 3;
		b.set(body.doorX, 0, sz, Blocks.AIR);
		b.set(body.doorX - 1, 0, sz, Blocks.OAK_PLANKS);
		b.set(body.doorX + 1, 0, sz, Blocks.OAK_PLANKS);
		b.mark("shaft", body.doorX, 0, sz);
		b.mark("work", body.doorX, 1, body.z1 - 2);
		for (int dx = -1; dx <= 1; dx++) {
			for (int z = sz - 1; z <= sz + 1; z++) {
				body.reserve(1, body.doorX + dx, z);
			}
		}
		// pit props either side of the shaft
		for (int dx : new int[]{-1, 1}) {
			b.set(body.doorX + dx, 1, sz - 1, Blocks.OAK_FENCE);
			b.set(body.doorX + dx, 2, sz - 1, Blocks.OAK_FENCE);
			b.set(body.doorX + dx, 3, sz - 1, Builder.facing(Blocks.LANTERN, Direction.UP).setValue(BlockStateProperties.HANGING, false));
		}
		Furnisher f = new Furnisher(body, 0);
		f.storage();
		decorate(f, Blocks.COAL_BLOCK, Blocks.IRON_BLOCK, Blocks.BARREL);
		for (int k = 1; k < body.storeys; k++) {
			Furnisher up = new Furnisher(body, k);
			if (k == 1) {
				up.bed(Blocks.BED.brown());
				up.bed(Blocks.BED.brown());
			}
			decorate(up, Blocks.BARREL, Blocks.CHEST, Blocks.BARREL, Blocks.CRAFTING_TABLE);
		}
		if (t >= 3) {
			// the winding wheel on top of the headframe
			int y = body.roofY + (body.z1 - body.z0 + 2) / 2 + 1;
			for (int dz = -1; dz <= 1; dz++) {
				b.set(body.doorX, y, body.midZ() + dz, Builder.log(Blocks.SPRUCE_LOG, Direction.Axis.Z));
				b.set(body.doorX, y + 2, body.midZ() + dz, Builder.log(Blocks.SPRUCE_LOG, Direction.Axis.Z));
			}
			b.set(body.doorX, y + 1, body.midZ() - 1, Builder.log(Blocks.SPRUCE_LOG, Direction.Axis.Y));
			b.set(body.doorX, y + 1, body.midZ() + 1, Builder.log(Blocks.SPRUCE_LOG, Direction.Axis.Y));
			b.set(body.doorX, y + 1, body.midZ(), Blocks.IRON_BLOCK);
		}
		dress(body, t);
		return b.build();
	}

	/** An open-sided sawmill under a roof; a loft above from the second tier. */
	private static Blueprint lumberMill(int t) {
		Style st = new Style(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.OAK_DOOR,
			Blocks.OAK_STAIRS, Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.OAK_STAIRS, Blocks.OAK_FENCE, false);
		Body body = body(13, 11, 9, pick(t, 7, 9, 11), pick(t, 7, 9, 9), pick(t, 1, 2, 2), false, st, 0);
		Builder b = body.b;
		// open sides: only the posts and the back wall stand on the ground floor
		for (int y = 1; y <= 3; y++) {
			for (int x = body.x0; x <= body.x1; x++) {
				for (int z = body.z0 + 1; z <= body.z1; z++) {
					boolean edge = x == body.x0 || x == body.x1 || z == body.z1;
					BlockState s = b.get(x, y, z);
					if (edge && s != null && !s.is(BlockTagsHolder.LOGS)) {
						b.set(x, y, z, y == 1 && z != body.z1 ? Blocks.OAK_FENCE.defaultBlockState() : Blocks.AIR.defaultBlockState());
					}
				}
			}
		}
		Furnisher f = new Furnisher(body, 0);
		f.storage();
		f.work(Blocks.STONECUTTER);
		f.put(Blocks.OAK_LOG);
		f.top(f.put(Blocks.OAK_LOG), Builder.log(Blocks.OAK_LOG, Direction.Axis.Y));
		if (t >= 2) {
			Furnisher up = new Furnisher(body, 1);
			for (int i = 0; i < 6; i++) {
				up.top(up.put(Blocks.OAK_LOG), Builder.log(Blocks.OAK_LOG, Direction.Axis.Y));
			}
		}
		if (t >= 3) {
			f.put(Blocks.GRINDSTONE);
			f.put(Blocks.STONECUTTER);
		}
		return b.build();
	}

	/** Bank terminals for all; a vault in the cellar at the third tier. */
	private static Blueprint bank(int t) {
		Style st = new Style(Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.QUARTZ_PILLAR,
			Blocks.DARK_OAK_DOOR, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICKS, Blocks.DEEPSLATE_BRICKS, Blocks.QUARTZ_STAIRS, Blocks.IRON_BARS, true);
		Body body = body(13, 13, 11, pick(t, 7, 9, 11), pick(t, 7, 9, 11), pick(t, 1, 2, 2), t >= 3, st, 0);
		Builder b = body.b;
		barWindows(body);
		Furnisher f = new Furnisher(body, 0);
		f.put(net.antwire.commerce.registry.ModBlocks.BANK_TERMINAL);
		f.work(Blocks.LECTERN);
		f.storage();
		f.put(Blocks.GOLD_BLOCK);
		f.put(net.antwire.commerce.registry.ModBlocks.BANK_TERMINAL);
		if (t >= 2) {
			f.put(net.antwire.commerce.registry.ModBlocks.BANK_TERMINAL);
			Furnisher up = new Furnisher(body, 1);
			decorate(up, Blocks.LECTERN, Blocks.BOOKSHELF, Blocks.BOOKSHELF, Blocks.CHEST, Blocks.BOOKSHELF);
		}
		if (body.cellar) {
			Furnisher c = new Furnisher(body, -1);
			while (c.left() > 4) {
				c.put(Blocks.GOLD_BLOCK);
			}
		}
		dress(body, t);
		return b.build();
	}

	private static void barWindows(Body body) {
		Builder b = body.b;
		for (int y = 1; y <= 3; y++) {
			for (int x = body.x0; x <= body.x1; x++) {
				for (int z = body.z0; z <= body.z1; z++) {
					BlockState s = b.get(x, y, z);
					if (s != null && s.is(Blocks.GLASS_PANE)) {
						b.set(x, y, z, Blocks.IRON_BARS);
					}
				}
			}
		}
	}

	private static Blueprint sheriff(int t) {
		Style st = new Style(Blocks.STONE_BRICKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.DYED_TERRACOTTA.white(), Blocks.DARK_OAK_LOG,
			Blocks.DARK_OAK_DOOR, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_PLANKS, Blocks.STONE_BRICKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_FENCE, true);
		st = grand(st, t);
		Body body = body(13, 11, 9, pick(t, 7, 9, 11), pick(t, 7, 9, 9), pick(t, 1, 2, 2), t >= 3, st, 0);
		Furnisher f = new Furnisher(body, 0);
		f.work(Blocks.CARTOGRAPHY_TABLE);
		f.storage();
		decorate(f, Blocks.BARREL, Blocks.TARGET);
		if (t >= 2) {
			Furnisher up = new Furnisher(body, 1);
			up.bed(Blocks.BED.blue());
			up.bed(Blocks.BED.blue());
			decorate(up, Blocks.CHEST, Blocks.BARREL);
		}
		if (body.cellar) {
			Furnisher c = new Furnisher(body, -1);
			decorate(c, Blocks.BARREL, Blocks.CHEST, Blocks.BARREL, Blocks.TARGET);
		}
		dress(body, t);
		return body.b.build();
	}

	/** Barred cells along the back wall of each floor (and in the cellar at the third tier). */
	private static Blueprint prison(int t) {
		Style st = new Style(Blocks.STONE_BRICKS, Blocks.STONE, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.POLISHED_BASALT, Blocks.DARK_OAK_DOOR,
			Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.IRON_BARS, false);
		Body body = body(15, 13, 11, pick(t, 9, 11, 13), pick(t, 7, 9, 11), pick(t, 1, 2, 2), t >= 3, st, 0);
		Builder b = body.b;
		if (body.cellar) {
			cells(body, -1);
		}
		for (int k = 0; k < body.storeys; k++) {
			cells(body, k);
		}
		b.marks.remove("bed");
		// small barred windows high up
		for (int k = 0; k < body.storeys; k++) {
			int y = Architect.STOREY * k + 3;
			b.set(body.x0 + 2, y, body.z1, Blocks.IRON_BARS);
			b.set(body.x1 - 2, y, body.z1, Blocks.IRON_BARS);
		}
		Furnisher f = new Furnisher(body, 0);
		f.storage();
		f.put(Blocks.BARREL);
		b.mark("work", body.doorX + 1, 1, body.z1 - 2);
		return b.build();
	}

	private static void cells(Body body, int floor) {
		Builder b = body.b;
		int y = body.feet(floor);
		int limit = body.hasStairs() ? body.x1 - 4 : body.x1 - 1;
		int zb = body.z0 + 3;
		for (int cx = body.x0 + 1; cx + 2 <= limit; cx += 4) {
			for (int dy = 0; dy < 3; dy++) {
				for (int x = cx; x <= cx + 2; x++) {
					b.set(x, y + dy, zb, Blocks.IRON_BARS);
				}
				if (cx + 3 < body.x1) {
					for (int z = body.z0 + 1; z <= zb; z++) {
						b.set(cx + 3, y + dy, z, Blocks.STONE_BRICKS);
					}
				}
			}
			b.bed(cx, y, body.z0 + 2, Blocks.BED.gray(), Direction.NORTH);
			b.set(cx + 2, y, body.z0 + 1, Blocks.CAULDRON);
			b.mark("cell", cx + 1, y, body.z0 + 1);
			b.mark("cellbed", cx, y, body.z0 + 2);
			for (int x = cx; x <= cx + 3; x++) {
				for (int z = body.z0 + 1; z <= zb + 1; z++) {
					body.reserve(y, x, z);
				}
			}
		}
	}

	/** Workshops on the ground floor - one more bench with every tier - and the powder magazine in the cellar. */
	private static Blueprint factory(int t) {
		Style st = new Style(Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, Blocks.BRICKS, Blocks.BRICKS, Blocks.POLISHED_BASALT, Blocks.DARK_OAK_DOOR,
			Blocks.STONE_BRICK_STAIRS, Blocks.BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.IRON_BARS, true);
		Body body = body(19, 15, 13, pick(t, 13, 15, 17), pick(t, 9, 11, 13), pick(t, 1, 2, 2), t >= 3, st, 2);
		Architect.chimney(body, body.x0 + 1, body.z0 + 1, Blocks.BRICKS);
		Architect.chimney(body, body.x0 + 1, body.z1 - 1, Blocks.BRICKS);
		shop(body);
		Furnisher f = new Furnisher(body, 0);
		Block[] benches = {Blocks.ANVIL, Blocks.BLAST_FURNACE, Blocks.CRAFTING_TABLE, Blocks.SMITHING_TABLE, Blocks.ANVIL};
		for (int i = 0; i < BuildingType.FACTORY.workers(t); i++) {
			f.work(benches[i % benches.length]);
		}
		f.storage();
		f.storage();
		f.top(f.put(Blocks.CONCRETE.red()), Blocks.CONCRETE.red().defaultBlockState());
		f.put(Blocks.SMOOTH_STONE_SLAB);
		if (t >= 2) {
			Furnisher up = new Furnisher(body, 1);
			decorate(up, Blocks.CHEST, Blocks.BARREL, Blocks.BARREL, Blocks.CHEST, Blocks.LECTERN, Blocks.CARTOGRAPHY_TABLE);
		}
		if (body.cellar) {
			Furnisher c = new Furnisher(body, -1);
			while (c.left() > 6) {
				c.put(Blocks.CONCRETE.red());
			}
		}
		dress(body, t);
		return body.b.build();
	}

	/** The taproom with its tables; guest rooms upstairs and the beer cellar from the third tier. */
	private static Blueprint tavern(int t) {
		Style st = grand(Architect.PLASTER.withRoof(Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_PLANKS), t);
		Body body = body(15, 13, 11, pick(t, 9, 11, 13), pick(t, 7, 9, 11), pick(t, 1, 2, 3), t >= 3, st, 0);
		Builder b = body.b;
		// the bar along the back wall, west of the middle
		int bz = body.z0 + 2;
		int bx = body.x0 + 2;
		b.set(bx - 1, 1, bz, Builder.log(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.X));
		b.set(bx, 1, bz, shopCounter());
		b.mark("shop", bx, 1, bz);
		b.set(bx + 1, 1, bz, Builder.log(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.X));
		b.set(bx - 1, 1, body.z0 + 1, Builder.facing(Blocks.BARREL, Direction.SOUTH));
		b.set(bx + 1, 1, body.z0 + 1, Builder.facing(Blocks.BARREL, Direction.SOUTH));
		b.set(bx - 1, 2, body.z0 + 1, Builder.facing(Blocks.BARREL, Direction.SOUTH));
		b.mark("workblock", bx - 1, 1, body.z0 + 1);
		b.mark("work", bx, 1, body.z0 + 1);
		for (int x = bx - 1; x <= bx + 1; x++) {
			body.reserve(1, x, body.z0 + 1);
			body.reserve(1, x, bz);
			body.reserve(1, x, bz + 1);
		}
		// tables and chairs, in the free middle of the room
		for (int z = bz + 2; z <= body.z1 - 2; z += 2) {
			for (int x = body.x0 + 2; x <= body.x1 - 2; x += 4) {
				if (Math.abs(x - body.doorX) < 2) {
					continue;
				}
				boolean ok = true;
				for (int dx = -1; dx <= 1; dx++) {
					ok &= !body.isReserved(1, x + dx, z) && b.get(x + dx, 1, z) != null && b.get(x + dx, 1, z).isAir();
				}
				if (!ok) {
					continue;
				}
				b.set(x, 1, z, Blocks.OAK_FENCE);
				b.set(x, 2, z, Blocks.OAK_PRESSURE_PLATE);
				b.set(x - 1, 1, z, Builder.stairs(Blocks.OAK_STAIRS, Direction.WEST, false));
				b.set(x + 1, 1, z, Builder.stairs(Blocks.OAK_STAIRS, Direction.EAST, false));
				b.mark("seat", x - 1, 1, z);
				b.mark("seat", x + 1, 1, z);
				for (int dx = -1; dx <= 1; dx++) {
					body.reserve(1, x + dx, z);
				}
			}
		}
		Furnisher f = new Furnisher(body, 0);
		f.storage();
		b.set(body.doorX, 3, body.z1 + 1, Builder.facing(Blocks.WALL_BANNER.green(), Direction.SOUTH));
		for (int k = 1; k < body.storeys; k++) {
			Furnisher up = new Furnisher(body, k);
			for (int i = 0; i < 3; i++) {
				up.bed(Blocks.BED.green());
			}
			up.put(Blocks.FLOWER_POT);
		}
		if (body.cellar) {
			Furnisher c = new Furnisher(body, -1);
			while (c.left() > 4) {
				c.top(c.put(Blocks.BARREL), Builder.facing(Blocks.BARREL, Direction.UP));
			}
		}
		dress(body, t);
		return b.build();
	}

	/** An Arsenal block by id, or the fallback when Arsenal isn't installed. */
	private static BlockState arsenal(String id, Direction facing, BlockState fallback) {
		Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("arsenal", id));
		return block == null || block == Blocks.AIR ? fallback : Builder.facing(block, facing);
	}

	/**
	 * Stone barracks: bunks for every soldier, the armoury (Arsenal's racks and ammunition crates when it is installed),
	 * the duty officer's map table, the armoury cellar at the third tier, and the parade ground in front with targets and
	 * the alarm bell at the sides - the way to the door stays clear.
	 */
	private static Blueprint barracks(int t) {
		Style st = new Style(Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.SPRUCE_LOG, Blocks.DARK_OAK_DOOR,
			Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_BRICKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_FENCE, true);
		Body body = body(19, 18, 13, pick(t, 13, 15, 17), pick(t, 9, 11, 13), pick(t, 1, 2, 3), t >= 3, st, 0);
		Builder b = body.b;
		Furnisher f = new Furnisher(body, 0);
		f.work(Blocks.CARTOGRAPHY_TABLE);
		f.put(Blocks.LECTERN);
		f.storage();
		BlockState barrel = Builder.facing(Blocks.BARREL, Direction.UP);
		// the armoury: racks on the wall, a gun stand, ammunition
		for (int i = 0; i < 2; i++) {
			BlockPos p = f.put(Blocks.SMITHING_TABLE);
			if (p != null) {
				b.set(p.getX(), p.getY(), p.getZ(), arsenal("ammo_crate", Direction.SOUTH, barrel));
				BlockState rack = arsenal("weapon_rack", Direction.SOUTH, Blocks.AIR.defaultBlockState());
				BlockState placed = b.get(p.getX(), p.getY(), p.getZ());
				if (!rack.isAir()) {
					// the rack hangs on the wall above the crate, facing the room
					Direction into = placed.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? placed.getValue(BlockStateProperties.HORIZONTAL_FACING)
						: Direction.SOUTH;
					b.set(p.getX(), p.getY() + 1, p.getZ(), rack.setValue(BlockStateProperties.HORIZONTAL_FACING, into));
					b.mark("rack", p.getX(), p.getY() + 1, p.getZ());
				}
			}
		}
		BlockPos stand = f.put(Blocks.BARREL);
		if (stand != null) {
			b.set(stand.getX(), stand.getY(), stand.getZ(), arsenal("gun_rack", Direction.SOUTH, barrel));
			b.mark("rack", stand.getX(), stand.getY(), stand.getZ());
		}
		// bunks for every soldier, as many on each floor as fit
		int bunks = BuildingType.BARRACKS.workers(t);
		int placed = 0;
		for (int k = 0; k < body.storeys && placed < bunks; k++) {
			Furnisher fl = k == 0 ? f : new Furnisher(body, k);
			while (placed < bunks && fl.bed(Blocks.BED.green()) != null) {
				placed++;
			}
			if (k > 0) {
				fl.put(Blocks.BARREL);
			}
		}
		if (body.cellar) {
			Furnisher c = new Furnisher(body, -1);
			for (int i = 0; i < 4; i++) {
				BlockPos p = c.put(Blocks.BARREL);
				if (p != null) {
					b.set(p.getX(), p.getY(), p.getZ(), arsenal("ammo_crate", Direction.SOUTH, barrel));
				}
			}
			BlockPos r = c.put(Blocks.BARREL);
			if (r != null) {
				b.set(r.getX(), r.getY(), r.getZ(), arsenal("gun_rack", Direction.SOUTH, barrel));
				b.mark("rack", r.getX(), r.getY(), r.getZ());
			}
		}
		b.set(body.doorX, 3, body.z0 + 1, Builder.facing(Blocks.WALL_BANNER.green(), Direction.SOUTH));
		// the parade ground
		int pz0 = body.z1 + 1;
		int pz1 = body.z1 + 4;
		b.fill(body.x0, 0, pz0, body.x1, 0, pz1, Blocks.COARSE_DIRT);
		b.fill(body.x0, 1, pz0, body.x1, 3, pz1, Blocks.AIR);
		for (int x = body.x0; x <= body.x1; x++) {
			b.set(x, 0, pz1, Blocks.STONE_BRICKS);
		}
		for (int x : new int[]{body.x0, body.x1}) {
			b.set(x, 1, pz0 + 1, Blocks.HAY_BLOCK);
			b.set(x, 2, pz0 + 1, Blocks.TARGET);
			b.set(x, 1, pz0 + 2, Blocks.HAY_BLOCK);
		}
		b.set(body.x0, 1, pz1, Blocks.STONE_BRICK_WALL);
		b.set(body.x0, 2, pz1, Builder.facing(Blocks.BELL, Direction.EAST));
		b.mark("bell", body.x0, 2, pz1);
		for (int y = 1; y <= 4; y++) {
			b.set(body.x1, y, pz1, Blocks.SPRUCE_FENCE);
		}
		b.set(body.x1, 5, pz1, Blocks.BANNER.green());
		b.mark("post", body.doorX - 2, 1, pz0);
		b.mark("post", body.doorX + 2, 1, pz0);
		b.mark("post", body.doorX, 1, pz0 + 2);
		b.mark("parade", body.doorX - 3, 1, pz0 + 2);
		b.mark("parade", body.doorX + 3, 1, pz0 + 2);
		dress(body, t);
		return b.build();
	}

	/** Block tags in a place blueprints can see them without an import clash. */
	private static final class BlockTagsHolder {
		static final net.minecraft.tags.TagKey<Block> LOGS = net.minecraft.tags.BlockTags.LOGS;
	}
}
