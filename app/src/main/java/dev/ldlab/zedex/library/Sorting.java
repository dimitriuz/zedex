package dev.ldlab.zedex.library;

import dev.ldlab.zedex.library.meta.Meta;

import java.util.Comparator;
import java.util.Locale;

/**
 * The order the library is in.
 *
 * Plain Java, like {@link Filters} and for the same reason: the rule that
 * matters here - a game with no rating is not a game rated nought - is easy to
 * write as a comparator that simply reverses, and easy to check here that it
 * does not.
 */
public final class Sorting {

    public static final String NAME = "name";
    public static final String SIZE = "size";
    public static final String RELEASED = "released";
    public static final String FORMAT = "format";
    public static final String RATING = "rating";

    /** In the order the pickers show them. */
    public static final String[] FIELDS = { NAME, SIZE, RELEASED, FORMAT, RATING };

    /** How a caller reaches the metadata for an entry, without this class
     *  needing a Context to do it. */
    public interface Lookup {
        Meta of(Entry entry);
    }

    private Sorting() {
    }

    /**
     * The stored field, or {@link #NAME} when it is one this build does not
     * have.
     *
     * "date" was a field once. A stored value with no matching entry is how
     * this app has drawn a blank row before, so an unrecognised one resolves
     * to the default rather than to nothing.
     */
    public static String fieldOrDefault(String stored) {
        for (String field : FIELDS) {
            if (field.equals(stored)) return field;
        }
        return NAME;
    }

    public static Comparator<Entry> comparator(String field, boolean descending,
                                               Lookup lookup) {
        String chosen = fieldOrDefault(field);

        // Only RELEASED and RATING ever look at a Meta at all - resolving one
        // for every pair on every other field would be a lookup that never
        // gets used, on the search box's own keystroke path besides.
        boolean needsMeta = RELEASED.equals(chosen) || RATING.equals(chosen);

        return (left, right) -> {
            // Resolved once per entry per comparison, here, and handed to
            // both has() and compareValues() below - not resolved again by
            // either. A caller's Lookup is only ever asked to answer the same
            // question twice this way if it is idempotent, which lookup.of
            // itself never promised; asking once removes the requirement
            // rather than documenting it.
            Meta leftMeta = needsMeta ? lookup.of(left) : null;
            Meta rightMeta = needsMeta ? lookup.of(right) : null;

            // Whether a value is known is decided before direction is applied,
            // and always the same way round: descending by rating opens on the
            // best games, and ascending does not open on the ones that have no
            // rating at all. Reversing the whole comparison would do exactly
            // that.
            boolean hasLeft = has(chosen, left, leftMeta);
            boolean hasRight = has(chosen, right, rightMeta);

            if (hasLeft != hasRight) return hasLeft ? -1 : 1;
            if (!hasLeft) return byName(left, right);

            int order = compareValues(chosen, left, right, leftMeta, rightMeta);
            if (order != 0) return descending ? -order : order;

            // A stable, meaningful tie-break, so two games of the same year do
            // not swap places between one listing and the next.
            return byName(left, right);
        };
    }

    private static boolean has(String field, Entry entry, Meta meta) {
        switch (field) {
            case RELEASED:
                return meta != null && meta.year() != null;
            case RATING:
                return meta != null && meta.ratingOutOfFive() >= 0f;
            case FORMAT:
                return !Filters.formatOf(entry).isEmpty();
            case SIZE:
                return entry.size >= 0;
            default:
                return true;   // a name is always known
        }
    }

    private static int compareValues(String field, Entry left, Entry right,
                                     Meta leftMeta, Meta rightMeta) {
        switch (field) {
            case SIZE:
                return Long.compare(left.size, right.size);

            case FORMAT:
                return Filters.formatOf(left).compareTo(Filters.formatOf(right));

            case RELEASED:
                return leftMeta.year().compareTo(rightMeta.year());

            case RATING:
                return Float.compare(leftMeta.ratingOutOfFive(), rightMeta.ratingOutOfFive());

            default:
                return byName(left, right);
        }
    }

    private static int byName(Entry left, Entry right) {
        return left.name.toLowerCase(Locale.ROOT)
                        .compareTo(right.name.toLowerCase(Locale.ROOT));
    }
}
