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
		return Blueprints.of(this.type);
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

	public AABB bounds() {
		Blueprint bp = this.blueprint();
		BlockPos a = this.world(0, 0, 0);
		BlockPos b = this.world(bp.w - 1, bp.h - 1, bp.d - 1);
		return new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()) + 1,
			Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
	}

	public String account(City city) {
		return "civitas:biz:" + city.id + ":" + this.id;
	}

	public String title() {
		return this.type.title + (this.complete ? "" : " (construction " + (this.steps <= 0 ? 0 : this.progress * 100 / this.steps) + "%)");
	}
}
