package dev.ldlab.zedex.library;

import dev.ldlab.zedex.library.meta.Meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the library is currently narrowed to, and whether a game is in it.
 *
 * Plain Java on purpose: no Context, no views, nothing from Android. That is
 * what lets the awkward parts - compound genres, an inclusive threshold, the
 * difference between unrated and rated zero - be checked on the JVM in seconds
 * rather than on a device, and it keeps the rules in one place instead of
 * spread through the listing code.
 *
 * Held for the session and never written to preferences. Sort is a preference,
 * because it is how somebody likes their library; a filter is a question they
 * are asking now, and the person most likely to meet a forgotten one is the
 * person who set it weeks ago and has since decided the app is broken.
 *
 * Values combine as OR within a field and AND across fields: tap or tzx, and
 * Platform. That is the combination people actually want.
 */
public final class Filters {

    /** The four list-shaped fields. The rating is a threshold, not a list, so
     *  it is not one of these - see {@link #minStars}. */
    public enum Field { FORMAT, GENRE, SUBGENRE, DEVELOPER, PUBLISHER }

    private final Map<Field, Set<String>> chosen = new EnumMap<>(Field.class);

    /** Out of five, so it reads the way the pane shows it. Zero is off. */
    private float minStars;

    public Filters() {
        for (Field field : Field.values()) chosen.put(field, new LinkedHashSet<>());
    }

    /** Nothing is narrowed, so the library is showing everything. */
    public boolean isEmpty() {
        return activeFieldCount() == 0;
    }

    /** How many fields are set, for the row that says so without listing them. */
    public int activeFieldCount() {
        int active = minStars > 0f ? 1 : 0;
        for (Field field : Field.values()) {
            if (!chosen.get(field).isEmpty()) active++;
        }
        return active;
    }

    /** What is picked for one field; never null, and not to be written to. */
    public Set<String> chosen(Field field) {
        return Collections.unmodifiableSet(chosen.get(field));
    }

    /** On if it was off, off if it was on. */
    public void toggle(Field field, String value) {
        Set<String> set = chosen.get(field);
        if (!set.remove(value)) set.add(value);
    }

    public void clear(Field field) {
        chosen.get(field).clear();
    }

    public void clearAll() {
        for (Field field : Field.values()) chosen.get(field).clear();
        minStars = 0f;
    }

    public float minStars() {
        return minStars;
    }

    /** Out of five. Zero turns the threshold off. */
    public void setMinStars(float stars) {
        minStars = stars;
    }

    /**
     * Whether this game is in what is currently being shown.
     *
     * {@code meta} is null for a game the store knows nothing about, and every
     * metadata filter excludes it: a game with no genre is not a game of every
     * genre. The format filter is the exception, since the filename always
     * answers it.
     */
    public boolean matches(Entry entry, Meta meta) {
        if (!matchesField(Field.FORMAT, formatOf(entry))) return false;

        if (!chosen.get(Field.GENRE).isEmpty()) {
            boolean any = false;
            for (String genre : genresOf(meta == null ? null : meta.genre)) {
                if (chosen.get(Field.GENRE).contains(genre)) { any = true; break; }
            }
            if (!any) return false;
        }

        // A plain match, unlike genre above: a subgenre is one value, never a
        // comma-separated list, because only ZXInfo writes one and it writes
        // exactly one.
        if (!matchesField(Field.SUBGENRE, meta == null ? null : meta.subgenre)) return false;
        if (!matchesField(Field.DEVELOPER, meta == null ? null : meta.developer)) return false;
        if (!matchesField(Field.PUBLISHER, meta == null ? null : meta.publisher)) return false;

        if (minStars > 0f) {
            float stars = meta == null ? -1f : meta.ratingOutOfFive();

            // Not >=  with a tolerance of nothing: ES-DE's 0.9 is exactly 4.5,
            // and a strict comparison here would drop every best-rated game in
            // the collection.
            if (stars < 0f || stars + 0.0001f < minStars) return false;
        }

        return true;
    }

    private boolean matchesField(Field field, String value) {
        Set<String> wanted = chosen.get(field);
        if (wanted.isEmpty()) return true;

        return value != null && wanted.contains(value);
    }

    /**
     * One genre string as the genres it actually names.
     *
     * ES-DE writes compound values - {@code "Racing, Driving"} - and offering
     * those whole would make a person looking for racing games guess what it
     * was filed beside. Order is kept so a caller can show the first as the
     * principal one.
     */
    public static List<String> genresOf(String genre) {
        List<String> names = new ArrayList<>();
        if (genre == null) return names;

        for (String part : genre.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }

        return names;
    }

    /** The file's extension; empty when it has none. {@code Types.extension}
     *  already lowercases and already answers "" rather than null. */
    public static String formatOf(Entry entry) {
        return Types.extension(entry.name);
    }

    /**
     * "3+" or "4.5+" - {@link Locale#getDefault()}, not a plain {@code
     * String.valueOf}, so the decimal point reads the way {@code Meta.stars()}
     * already renders one, in German among others. Three copies of this used
     * to exist, one in each of {@code OptionsDialog}, {@code LibraryActivity}
     * and {@code FilterTest} - each for its own stated reason, and each still
     * using the locale-blind {@code String.valueOf} because three copies is
     * three places to remember the fix. Here instead, since both app callers
     * already look at this class for the threshold itself.
     */
    public static String ratingLabel(float stars) {
        String number = stars == Math.rint(stars)
                ? String.valueOf((int) stars)
                : String.format(Locale.getDefault(), "%.1f", stars);
        return number + "+";
    }
}
