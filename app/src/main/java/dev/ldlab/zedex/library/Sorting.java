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

    /**
     * The order rows go in.
     *
     * @param scrapedNames whether rows are drawing their scraped titles - the
     *                     {@code libraryNames} preference. It decides what
     *                     {@link #NAME} means, because a list has to be
     *                     ordered by the string it is showing: a game whose
     *                     file is {@code zvezdnoenasledie.trd} and whose
     *                     scraped title is "Star Inheritance" drew the title
     *                     and sorted under Z, where nobody looking for it
     *                     would go. Sorting by the title while the row shows
     *                     the filename would be the same fault the other way
     *                     round, so this follows the preference rather than
     *                     always preferring one.
     */
    public static Comparator<Entry> comparator(String field, boolean descending,
                                               boolean scrapedNames, Lookup lookup) {
        String chosen = fieldOrDefault(field);

        // RELEASED and RATING read a Meta for the value itself; NAME reads one
        // only while rows are showing scraped titles, and every sort reads one
        // for the tie-break for the same reason. Resolving one otherwise would
        // be a lookup that never gets used, on the search box's own keystroke
        // path besides - and Shortlist caches per row, so this costs a map hit
        // per comparison rather than a store read.
        boolean needsMeta = RELEASED.equals(chosen) || RATING.equals(chosen) || scrapedNames;

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
            if (!hasLeft) return byShownName(left, right, leftMeta, rightMeta, scrapedNames);

            if (NAME.equals(chosen)) {
                int byName = byShownName(left, right, leftMeta, rightMeta, scrapedNames);
                return descending ? -byName : byName;
            }

            int order = compareValues(chosen, left, right, leftMeta, rightMeta);
            if (order != 0) return descending ? -order : order;

            // A stable, meaningful tie-break, so two games of the same year do
            // not swap places between one listing and the next.
            return byShownName(left, right, leftMeta, rightMeta, scrapedNames);
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
                // NAME does not reach here: it is answered above, by the name
                // the row is showing, which needs the two Metas this method is
                // deliberately not handed. Every other field in FIELDS is a
                // case above, and fieldOrDefault guarantees there is no sixth -
                // so this is unreachable, and equal is the harmless answer if a
                // field is ever added without a case.
                return 0;
        }
    }

    /**
     * By the name the row is actually showing.
     *
     * A scraped title when there is one and rows are drawing them, the file's
     * own name otherwise - which is what {@code EntryAdapter.applyMeta} puts on
     * the row, and the two have to agree or the list is ordered by a string
     * nobody can see.
     *
     * An empty title is not a title: {@code Meta.Builder} already turns "" into
     * null, and guarding it here as well costs nothing and stops every
     * scraped-but-unnamed row sorting to the top if that ever changes.
     */
    private static int byShownName(Entry left, Entry right, Meta leftMeta, Meta rightMeta,
                                   boolean scrapedNames) {
        return shownName(left, leftMeta, scrapedNames)
                .compareTo(shownName(right, rightMeta, scrapedNames));
    }

    private static String shownName(Entry entry, Meta meta, boolean scrapedNames) {
        if (scrapedNames && meta != null && meta.name != null && !meta.name.isEmpty()) {
            return meta.name.toLowerCase(Locale.ROOT);
        }
        return entry.name.toLowerCase(Locale.ROOT);
    }
}
