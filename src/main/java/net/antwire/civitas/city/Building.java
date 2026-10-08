package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** A building of a town: where it stands, how far its construction got, who works and lives there. */
public class Building {
	public int id;
	public BuildingType type;
	public BlockPos origin;
	/** 0 = entrance south, 1 = west, 2 = north, 3 = east (Rotation ordinal). */
	public int rotation;
	/** Next construction step; equals the step count once built. */
	public int progress;
	public int steps;
	public boolean complete;
	public String stalled = "";
	public List<UUID> workers = new ArrayList<>();
	public List<UUID> residents = new ArrayList<>();
	/** Mine: next dig step. */
	public int digIndex;
	/** Revenue and costs of the current day (cents), for the books. */
	public long revenueToday;
	public long costToday;
	public long producedToday;
	public long revenueYesterday;
	public long costYesterday;
	public int builtDay = -1;
	/** How grand it is (1 to 3); the town hall's tier is the town's. */
	public int tier = 1;
	/** Being rebuilt to this tier (0 = not being upgraded). */
	public int targetTier;
	/** Where the plot of the upgraded building starts (old buildings are re-planned around their door). */
	public @Nullable BlockPos upgradeOrigin;
	/** 0 = built to the plans before tiers ({@link LegacyBlueprints}), 1 = a plot with room for every tier. */
	public int layout;
	/** The way from the door to the town square: the ground block of every step (see {@link Access}). */
	public List<BlockPos> approach = new ArrayList<>();
	/** Blocks banked up under the way where the ground dips. */
	public List<BlockPos> approachFill = new ArrayList<>();
	/** What keeps people out, as last found by the daily check (empty when all is well). */
	public String blocked = "";

	public Building() {
	}

	public Building(int id, BuildingType type, BlockPos origin, int rotation) {
		this.id = id;
		this.type = type;
		this.origin = origin;
		this.rotation = rotation;
	}

	public Rotation rot() {
		return Rotation.values()[Math.floorMod(this.rotation, 4)];
	}

	public Blueprint blueprint() {
		return this.layout == 0 ? LegacyBlueprints.of(this.type) : Blueprints.of(this.type, this.tier);
	}

	public Blueprint targetBlueprint() {
		return Blueprints.of(this.type, this.targetTier);
	}

	public BlockPos targetOrigin() {
		return this.upgradeOrigin == null ? this.origin : this.upgradeOrigin;
	}

	public boolean upgrading() {
		return this.targetTier > this.tier;
	}

	public int workerSlots() {
		return this.type.workers(this.tier);
	}

	public int bedCount() {
		return this.type.beds(this.tier);
	}

	/** Blueprint coordinates to world coordinates. */
	public BlockPos world(BlockPos rel) {
		return this.origin.offset(rel.rotate(this.rot()));
	}

	public BlockPos world(int x, int y, int z) {
		return this.world(new BlockPos(x, y, z));
	}

	public @Nullable BlockPos mark(String name) {
		BlockPos p = this.blueprint().mark(name);
		return p == null ? null : this.world(p);
	}

	public List<BlockPos> marks(String name) {
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos p : this.blueprint().marks(name)) {
			out.add(this.world(p));
		}
		return out;
	}

	/** Where people walk to: just outside the door (or the first work spot). */
	public BlockPos entrance() {
		BlockPos e = this.mark("entrance");
		if (e == null) {
			Blueprint bp = this.blueprint();
			e = this.world(bp.w / 2, 1, bp.d);
		}
		return e;
	}

	public BlockPos center() {
		Blueprint bp = this.blueprint();
		return this.world(bp.w / 2, 1, bp.d / 2);
	}

	/** The plot (while being upgraded: the old and the new plot together). */
	public AABB bounds() {
		AABB box = box(this.blueprint(), this.origin);
		return this.upgrading() ? box.minmax(box(this.targetBlueprint(), this.targetOrigin())) : box;
	}

	private AABB box(Blueprint bp, BlockPos origin) {
		BlockPos a = origin.offset(new BlockPos(0, bp.minY(), 0).rotate(this.rot()));
		BlockPos b = origin.offset(new BlockPos(bp.w - 1, bp.h - 1, bp.d - 1).rotate(this.rot()));
		return new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()) + 1,
			Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
	}

	public String account(City city) {
		return "civitas:biz:" + city.id + ":" + this.id;
	}

	public String title() {
		String t = this.type.title + (this.tier > 1 ? " " + "I".repeat(this.tier) : "");
		if (this.upgrading()) {
			return t + " (upgrading to tier " + this.targetTier + ", " + (this.steps <= 0 ? 0 : this.progress * 100 / this.steps) + "%)";
		}
		return t + (this.complete ? "" : " (construction " + (this.steps <= 0 ? 0 : this.progress * 100 / this.steps) + "%)");
	}
}
