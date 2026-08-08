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

        return (left, right) -> {
            // Whether a value is known is decided before direction is applied,
            // and always the same way round: descending by rating opens on the
            // best games, and ascending does not open on the ones that have no
            // rating at all. Reversing the whole comparison would do exactly
            // that.
            boolean hasLeft = has(chosen, left, lookup);
            boolean hasRight = has(chosen, right, lookup);

            if (hasLeft != hasRight) return hasLeft ? -1 : 1;
            if (!hasLeft) return byName(left, right);

            int order = compareValues(chosen, left, right, lookup);
            if (order != 0) return descending ? -order : order;

            // A stable, meaningful tie-break, so two games of the same year do
            // not swap places between one listing and the next.
            return byName(left, right);
        };
    }

    private static boolean has(String field, Entry entry, Lookup lookup) {
        switch (field) {
            case RELEASED: {
                Meta meta = lookup.of(entry);
                return meta != null && meta.year() != null;
            }
            case RATING: {
                Meta meta = lookup.of(entry);
                return meta != null && meta.ratingOutOfFive() >= 0f;
            }
            case FORMAT:
                return !Filters.formatOf(entry).isEmpty();
            default:
                return true;   // a name and a size are always known
        }
    }

    private static int compareValues(String field, Entry left, Entry right,
                                     Lookup lookup) {
        switch (field) {
            case SIZE:
                return Long.compare(left.size, right.size);

            case FORMAT:
                return Filters.formatOf(left).compareTo(Filters.formatOf(right));

            case RELEASED:
                return lookup.of(left).year().compareTo(lookup.of(right).year());

            case RATING:
                return Float.compare(lookup.of(left).ratingOutOfFive(),
                                     lookup.of(right).ratingOutOfFive());

            default:
                return byName(left, right);
        }
    }

    private static int byName(Entry left, Entry right) {
        return left.name.toLowerCase(Locale.ROOT)
                        .compareTo(right.name.toLowerCase(Locale.ROOT));
    }
}
