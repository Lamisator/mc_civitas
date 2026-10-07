package net.antwire.civitas.city;

import java.util.List;
import java.util.Random;

/** Who the settlers are. Funkstadt is a German town, so mostly German names - and a few from further afield. */
public final class Names {
	/** Number of base skins (skin tone and hair) - must match the generated textures. */
	public static final int SKINS = 12;

	private static final List<String> MALE = List.of("Hans", "Karl", "Friedrich", "Wilhelm", "Otto", "Heinrich", "Ernst", "Paul", "Walter", "Kurt",
		"Fritz", "Hermann", "Georg", "Ludwig", "Josef", "Franz", "Rudolf", "Bernd", "Klaus", "Dieter", "Jürgen", "Uwe", "Lars", "Jonas", "Lukas",
		"Felix", "Moritz", "Emil", "Anton", "Theo", "Matthias", "Stefan", "Mehmet", "Luca", "Piotr", "Dmitri", "Ole", "Jan", "Niklas", "Ben");
	private static final List<String> FEMALE = List.of("Anna", "Maria", "Elisabeth", "Gertrud", "Hildegard", "Margarete", "Martha", "Frieda", "Erna",
		"Ilse", "Ingrid", "Ursula", "Helga", "Renate", "Monika", "Sabine", "Petra", "Claudia", "Katrin", "Julia", "Lena", "Sophie", "Marie", "Emma",
		"Hanna", "Lea", "Clara", "Greta", "Frida", "Mila", "Charlotte", "Paula", "Ayşe", "Giulia", "Agnieszka", "Olga", "Ingeborg", "Wiebke", "Anke",
		"Lotte");
	private static final List<String> FAMILY = List.of("Müller", "Schmidt", "Schneider", "Fischer", "Weber", "Meyer", "Wagner", "Becker", "Schulz",
		"Hoffmann", "Schäfer", "Koch", "Bauer", "Richter", "Klein", "Wolf", "Schröder", "Neumann", "Schwarz", "Zimmermann", "Braun", "Krüger",
		"Hofmann", "Hartmann", "Lange", "Schmitt", "Werner", "Schmitz", "Krause", "Meier", "Lehmann", "Schmid", "Schulze", "Maier", "Köhler",
		"Herrmann", "König", "Walter", "Mayer", "Huber", "Kaiser", "Fuchs", "Peters", "Lang", "Scholz", "Möller", "Weiß", "Jung", "Hahn",
		"Schubert", "Vogel", "Friedrich", "Keller", "Günther", "Frank", "Berger", "Winkler", "Roth", "Beck", "Lorenz", "Baumann", "Franke",
		"Albrecht", "Schuster", "Simon", "Ludwig", "Böhm", "Winter", "Kraus", "Martin", "Schumacher", "Krämer", "Vogt", "Stein", "Jäger",
		"Otto", "Sommer", "Groß", "Seidel", "Heinrich", "Brandt", "Haas", "Schreiber", "Graf", "Schulte", "Dietrich", "Ziegler", "Kuhn",
		"Kühn", "Pohl", "Engel", "Horn", "Busch", "Bergmann", "Thomas", "Voigt", "Sauer", "Arnold", "Wolff", "Pfeiffer", "Funke", "Antenne");

	private Names() {
	}

	public static String random(Random r, boolean female) {
		List<String> first = female ? FEMALE : MALE;
		return first.get(r.nextInt(first.size())) + " " + FAMILY.get(r.nextInt(FAMILY.size()));
	}
}
