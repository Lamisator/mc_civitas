package net.antwire.civitas.entity.ai;

import net.antwire.civitas.city.CitizenRecord;
import net.antwire.civitas.city.City;
import net.antwire.civitas.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** One thing a citizen is doing. Ticks until done; may be interrupted when something more pressing comes up. */
public abstract class Task {
	protected final CitizenEntity npc;
	protected final City city;
	protected final CitizenRecord rec;
	protected boolean done;
	protected int ticks;

	protected Task(CitizenEntity npc, City city, CitizenRecord rec) {
		this.npc = npc;
		this.city = city;
		this.rec = rec;
	}

	/** What kind of activity this is, so the brain knows whether a new choice would be the same thing again. */
	public abstract String kind();

	public void start() {
	}

	protected abstract void run();

	public final void tick() {
		if (this.done) {
			return;
		}
		this.ticks++;
		this.run();
	}

	public void stop() {
		this.npc.getNavigation().stop();
		this.npc.setAction(CitizenEntity.ACTION_NONE);
	}

	public boolean done() {
		return this.done;
	}

	protected ServerLevel level() {
		return (ServerLevel) this.npc.level();
	}

	protected boolean walk(BlockPos target, double within) {
		return this.npc.brain().walker.moveTo(target, within, 1.0);
	}

	protected boolean run(BlockPos target, double within) {
		return this.npc.brain().walker.moveTo(target, within, 1.45);
	}

	protected void look(BlockPos pos) {
		this.npc.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	/** Swings the arm every {@code every} ticks (work animation). */
	protected void swing(int every) {
		this.npc.setAction(CitizenEntity.ACTION_WORK);
		if (this.ticks % every == 0) {
			this.npc.swingArm();
		}
	}
}
