package net.antwire.civitas.entity.ai;

import net.antwire.civitas.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Walking with patience: re-paths now and then and, when a citizen makes no headway for a while (a wall went up around
 * the builder, a door is jammed, the path is too long), steps them to the nearest free spot by the target.
 */
public final class Walker {
	private final CitizenEntity npc;
	private @Nullable BlockPos target;
	private double best;
	private int stuck;
	private int age;

	Walker(CitizenEntity npc) {
		this.npc = npc;
	}

	public boolean moveTo(BlockPos target, double within, double speed) {
		Vec3 goal = Vec3.atBottomCenterOf(target);
		double d = this.npc.position().distanceTo(goal);
		if (d <= within) {
			this.npc.getNavigation().stop();
			this.target = null;
			return true;
		}
		if (!target.equals(this.target)) {
			this.target = target;
			this.best = d;
			this.stuck = 0;
			this.age = 0;
			this.npc.getNavigation().moveTo(goal.x, goal.y, goal.z, speed);
		}
		this.age++;
		if (this.npc.getNavigation().isDone() || this.age % 50 == 0) {
			this.npc.getNavigation().moveTo(goal.x, goal.y, goal.z, speed);
		}
		if (d < this.best - 0.4) {
			this.best = d;
			this.stuck = 0;
		} else if (++this.stuck > 140) {
			BlockPos spot = standable(this.npc.level(), target, 3);
			if (spot != null) {
				this.npc.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
				this.npc.getNavigation().stop();
			}
			this.stuck = 0;
			this.best = this.npc.position().distanceTo(goal);
		}
		return false;
	}

	public void reset() {
		this.target = null;
	}

	/** A spot where a person fits (two air blocks over something solid), nearest to pos within r. */
	public static @Nullable BlockPos standable(Level level, BlockPos pos, int r) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dy = -2; dy <= 3; dy++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos p = pos.offset(dx, dy, dz);
					if (fits(level, p)) {
						double dd = p.distSqr(pos) + Math.abs(dy) * 0.5;
						if (dd < bestD) {
							bestD = dd;
							best = p;
						}
					}
				}
			}
		}
		return best;
	}

	public static boolean fits(Level level, BlockPos p) {
		BlockState feet = level.getBlockState(p);
		BlockState head = level.getBlockState(p.above());
		BlockState floor = level.getBlockState(p.below());
		return feet.getCollisionShape(level, p).isEmpty() && head.getCollisionShape(level, p.above()).isEmpty() && feet.getFluidState().isEmpty()
			&& !floor.getCollisionShape(level, p.below()).isEmpty();
	}
}
