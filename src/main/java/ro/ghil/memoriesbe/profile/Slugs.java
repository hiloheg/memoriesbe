package ro.ghil.memoriesbe.profile;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds and checks the readable URL names (slugs) used for photographer pages and events. */
public final class Slugs {
    private Slugs() {}

    /** Names that would collide with app routes or look official. */
    static final Set<String> RESERVED = Set.of("e", "p", "event", "events", "profile", "api", "admin", "login",
            "logout", "settings", "assets", "static", "www", "app", "help", "about", "terms", "privacy",
            "favicon", "index", "imemories");

    private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$");

    /** "Ștefan Ionescu" becomes "stefan-ionescu"; always 3 to 30 characters. */
    public static String slugify(String input) {
        String s = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > 30) s = s.substring(0, 30).replaceAll("-+$", "");
        if (s.length() < 3) s = (s + "-photo").replaceAll("^-+", "");
        return s;
    }

    /** True if the value can be used as a photographer address. */
    public static boolean valid(String slug) {
        return slug != null && VALID.matcher(slug).matches() && !RESERVED.contains(slug) && !slug.contains("--");
    }

    public static boolean reserved(String slug) {
        return RESERVED.contains(slug);
    }
}
