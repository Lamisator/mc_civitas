package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** Everything a town knows about one citizen (the entity only carries its body). */
public class CitizenRecord {
	public enum Status {
		FREE, WANTED, JAILED, LEFT, DEAD
	}

	public UUID uuid;
	public String name;
	public boolean female;
	public int skin;
	public int age;
	public Job job = Job.UNEMPLOYED;
	public int workplace = -1;
	public int home = -1;
	/** 0..100 */
	public double food = 80;
	public double rest = 100;
	public double happiness = 60;
	/** Condition of their clothes, 0 = rags, 100 = new. */
	public double clothing = 80;
	public Status status = Status.FREE;
	public int releaseDay;
	public int crimes;
	public int hungryDays;
	public int miserableDays;
	public boolean sleptInBed;
	public boolean ateToday;
	public boolean visitedTavern;
	public long lastWage;
	public long lastPaidDay = -1;
	public int arrivedDay;
	public BlockPos lastPos = BlockPos.ZERO;
	/** Why they feel as they do: factor name -> contribution. */
	public Map<String, Double> mood = new LinkedHashMap<>();
	public List<String> thoughts = new ArrayList<>();
	public String cause = "";
	/** Desperate enough to steal (set by the town each day). */
	public boolean thief;
	/** Packing up to leave town. */
	public boolean leaving;
	/** Fed by the rations this day. */
	public boolean rationed;

	public String account(City city) {
		return "civitas:cit:" + this.uuid;
	}

	/** 0 = well-off, 1 = comfortable, 2 = poor, 3 = destitute. */
	public int tier(long balance) {
		if (this.clothing < 25 || this.hungryDays >= 3 || (this.food < 15 && balance < 300)) {
			return 3;
		}
		if (this.clothing < 55 || (balance < 300 && this.hungryDays >= 1)) {
			return 2;
		}
		if (this.clothing > 80 && balance > 20000 && this.happiness > 60) {
			return 0;
		}
		return 1;
	}

	public void think(String thought) {
		this.thoughts.remove(thought);
		this.thoughts.add(0, thought);
		while (this.thoughts.size() > 6) {
			this.thoughts.remove(this.thoughts.size() - 1);
		}
	}

	public boolean free() {
		return this.status == Status.FREE || this.status == Status.WANTED;
	}

	public boolean present() {
		return this.status != Status.LEFT && this.status != Status.DEAD;
	}
}
