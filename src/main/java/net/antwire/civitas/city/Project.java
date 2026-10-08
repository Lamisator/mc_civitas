package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Building work that isn't a building of its own: repairing damage, raising or moving the town wall, paving the ways and
 * putting up street lamps. Builders work through the jobs in order, like the steps of a construction site.
 */
public class Project {
	public static final String REPAIR = "repair";
	public static final String WALL = "wall";
	public static final String STREETS = "streets";
	public static final String LAMPS = "lamps";

	public String kind;
	/** What is being repaired: "b:&lt;building id&gt;", "wall" or "lamps" (repairs only). */
	public String target = "";
	public String title = "";
	public List<Job> jobs = new ArrayList<>();
	public int progress;
	public String stalled = "";
	/** Wall projects: the tier being built. */
	public int tier;
	/** Wall projects: the wall as it will stand, "x y z state" per block, and its outline {minX, minZ, maxX, maxZ}. */
	public List<String> plan = new ArrayList<>();
	public int[] rect;

	public Project() {
	}

	public Project(String kind, String target, String title) {
		this.kind = kind;
		this.target = target;
		this.title = title;
	}

	public boolean repair() {
		return REPAIR.equals(this.kind);
	}

	public int percent() {
		return this.jobs.isEmpty() ? 100 : this.progress * 100 / this.jobs.size();
	}

	/**
	 * One block of work: {@code k} is a {@link Construction.Kind} name or "SIGN" (a building's name sign), {@code s} the
	 * block state as in commands.
	 */
	public static class Job {
		public String k;
		public BlockPos p;
		public String s;

		public Job() {
		}

		public Job(String k, BlockPos p, String s) {
			this.k = k;
			this.p = p;
			this.s = s;
		}
	}
}
