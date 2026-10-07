package net.antwire.civitas.city;

/** The laws of a town, set by its governor. Money in whole currency units. */
public class Policies {
	/** Income tax, percent of wages. */
	public double incomeTax = 10;
	/** Rent each resident pays per day. */
	public double rent = 2;
	/** Wages in percent of the standard wage (0 = they work for nothing). */
	public double wageLevel = 100;
	/** Hours in a working day (6 to 16). */
	public int workHours = 8;
	/** Prices in the town's shops. */
	public double breadPrice = 2.0;
	public double meatPrice = 4.0;
	public double alePrice = 1.5;
	/** Free bread for the hungry, paid by the treasury. */
	public boolean rations = false;
	/** Paid to every citizen each day. */
	public double basicIncome = 0;
	/** Prisoners work in the mine, unpaid. */
	public boolean forcedLabor = false;
	/** Everyone must be home at nightfall. */
	public boolean curfew = false;
	/** The sheriff arrests protesters on sight. */
	public boolean martialLaw = false;
	/** Safety rules in the ordnance factory: slower, but nobody gets blown up. */
	public boolean safetyRules = true;
	/** Days in prison per crime. */
	public int sentenceDays = 2;
	/** The town plans houses and workplaces by itself when it needs them. */
	public boolean autoBuild = true;
	/** The unemployed take open jobs by themselves. */
	public boolean autoAssign = true;
	/** The treasury pays wages a business can't afford. */
	public boolean subsidies = true;
	/** New settlers may move in. */
	public boolean immigration = true;
	/** Share of the business profits paid out as dividend (if the town is listed), percent of share price per day. */
	public double dividendPercent = 0.05;

	public void clamp() {
		this.incomeTax = Math.max(0, Math.min(95, this.incomeTax));
		this.rent = Math.max(0, Math.min(100, this.rent));
		this.wageLevel = Math.max(0, Math.min(300, this.wageLevel));
		this.workHours = Math.max(4, Math.min(18, this.workHours));
		this.breadPrice = Math.max(0, Math.min(100, this.breadPrice));
		this.meatPrice = Math.max(0, Math.min(100, this.meatPrice));
		this.alePrice = Math.max(0, Math.min(100, this.alePrice));
		this.basicIncome = Math.max(0, Math.min(200, this.basicIncome));
		this.sentenceDays = Math.max(1, Math.min(30, this.sentenceDays));
		this.dividendPercent = Math.max(0, Math.min(2, this.dividendPercent));
	}
}
