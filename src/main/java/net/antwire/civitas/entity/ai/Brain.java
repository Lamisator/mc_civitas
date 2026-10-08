package net.antwire.civitas.entity.ai;

import net.antwire.civitas.city.Building;
import net.antwire.civitas.city.BuildingType;
import net.antwire.civitas.city.CitizenRecord;
import net.antwire.civitas.city.City;
import net.antwire.civitas.city.CityManager;
import net.antwire.civitas.city.Job;
import net.antwire.civitas.city.Policies;
import net.antwire.civitas.entity.CitizenEntity;
import net.antwire.commerce.api.CommerceApi;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * A citizen's day: up at six, work for as many hours as the law says, buy food, maybe a drink at the tavern, home and
 * to bed. Hunger, prison, strikes, crime and leaving town override the routine.
 */
public final class Brain {
	public static final int WORK_START = 1000;
	private final CitizenEntity npc;
	public final Walker walker;
	private @Nullable Task task;

	public Brain(CitizenEntity npc) {
		this.npc = npc;
		this.walker = new Walker(npc);
	}

	/** Drops whatever they were doing (after an error); they pick something new next tick. */
	public void reset() {
		this.task = null;
		this.walker.reset();
	}

	public @Nullable Task task() {
		return this.task;
	}

	public static int workEnd(Policies p) {
		return Math.min(23000, WORK_START + p.workHours * 1000);
	}

	public static int sleepStart(Policies p) {
		return Math.min(23500, Math.max(13000, workEnd(p) + 1500));
	}

	public static boolean workTime(Policies p, int t) {
		return t >= WORK_START && t < workEnd(p);
	}

	/** Working hours - or the watch, for a soldier. */
	public static boolean onDuty(CitizenRecord r, Policies p, int t) {
		return r.job == Job.SOLDIER ? Military.onDuty(r, t, workTime(p, t)) : workTime(p, t);
	}

	public void tick(City city, CitizenRecord r) {
		if (this.npc.tickCount % 20 == 0) {
			// needs: a full stomach lasts about a day, longer hours make you hungrier
			double hunger = 100.0 / 1200.0 * (city.policies.workHours > 10 ? 1.25 : 1.0);
			r.food = Math.max(0, r.food - hunger);
			if (this.npc.isSleeping()) {
				r.rest = Math.min(100, r.rest + 100.0 / 500.0);
			} else {
				r.rest = Math.max(0, r.rest - 100.0 / 900.0);
			}
			Task urgent = this.urgent(city, r);
			if (urgent != null && (this.task == null || !this.task.kind().equals(urgent.kind()))) {
				this.switchTo(urgent);
			}
		}
		if (this.task == null || this.task.done()) {
			this.switchTo(this.choose(city, r));
		}
		if (this.task != null) {
			this.task.tick();
		}
	}

	private void switchTo(@Nullable Task next) {
		if (this.task != null) {
			this.task.stop();
		}
		this.walker.reset();
		this.task = next;
		if (next != null) {
			next.start();
		}
	}

	/** Interrupts the current task when something more important comes up. */
	private @Nullable Task urgent(City city, CitizenRecord r) {
		int t = CityManager.timeOfDay(this.npc.level());
		Policies p = city.policies;
		if (r.status == CitizenRecord.Status.JAILED) {
			if (p.forcedLabor && workTime(p, t) && city.firstComplete(BuildingType.MINE) != null) {
				return this.task != null && this.task.kind().equals("mine") ? null : Work.forcedLabor(this.npc, city, r);
			}
			return new Tasks.Jail(this.npc, city, r);
		}
		if (r.leaving) {
			return new Tasks.Leave(this.npc, city, r);
		}
		net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) this.npc.level();
		// the alarm: soldiers turn out at any hour
		if (r.job == Job.SOLDIER && r.free() && Military.alarm(level, city)) {
			return this.task != null && this.task.kind().equals("guard") ? null : Military.Guard.create(this.npc, city, r);
		}
		if (r.job == Job.SOLDIER && r.nightWatch) {
			if (Military.restsByDay(r, t)) {
				return new Tasks.Sleep(this.npc, city, r);
			}
		} else if (t >= sleepStart(p) || (p.curfew && t >= 12500 && !workTime(p, t))) {
			return new Tasks.Sleep(this.npc, city, r);
		}
		if (this.npc.isSleeping()) {
			this.npc.stopSleeping();
		}
		Task flee = Military.danger(this.npc, city, r);
		if (flee != null) {
			return this.task != null && this.task.kind().equals("flee") ? null : flee;
		}
		boolean working = r.job != Job.UNEMPLOYED && onDuty(r, p, t);
		if ((city.strike && working) || city.riot) {
			return new Tasks.Protest(this.npc, city, r);
		}
		if (r.food < 20 && this.canBuyFood(city, r)) {
			return this.task != null && this.task.kind().equals("food") ? null : new Tasks.BuyFood(this.npc, city, r);
		}
		return null;
	}

	private boolean canBuyFood(City city, CitizenRecord r) {
		return CommerceApi.balance(r.account(city)) >= Math.round(Math.min(city.policies.breadPrice, city.policies.meatPrice) * 100)
			&& (city.firstComplete(BuildingType.BAKERY) != null || city.firstComplete(BuildingType.BUTCHER) != null);
	}

	private Task choose(City city, CitizenRecord r) {
		Task u = this.urgent(city, r);
		if (u != null) {
			return u;
		}
		int t = CityManager.timeOfDay(this.npc.level());
		Policies p = city.policies;
		if (r.thief) {
			r.thief = false;
			Task steal = Tasks.Steal.create(this.npc, city, r);
			if (steal != null) {
				return steal;
			}
		}
		if (onDuty(r, p, t) && r.job != Job.UNEMPLOYED) {
			Task w = Work.next(this.npc, city, r);
			if (w != null) {
				return w;
			}
		}
		if (r.food < 60 && this.canBuyFood(city, r)) {
			return new Tasks.BuyFood(this.npc, city, r);
		}
		long balance = CommerceApi.balance(r.account(city));
		if (this.npc.tier() >= 3 && this.npc.getRandom().nextInt(3) == 0) {
			Player near = this.npc.level().getNearestPlayer(this.npc, 16);
			if (near != null) {
				return new Tasks.Beg(this.npc, city, r, near);
			}
		}
		Building tavern = city.firstComplete(BuildingType.TAVERN);
		if (tavern != null && !r.visitedTavern && t >= workEnd(p) && balance >= Math.round(p.alePrice * 100) && this.npc.getRandom().nextInt(3) == 0) {
			return new Tasks.Tavern(this.npc, city, r, tavern);
		}
		return new Tasks.Wander(this.npc, city, r, 200 + this.npc.getRandom().nextInt(300));
	}
}
