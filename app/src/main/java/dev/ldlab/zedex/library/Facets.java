package dev.ldlab.zedex.library;

import dev.ldlab.zedex.library.meta.Meta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which values a filter can actually offer, and how many games each would
 * bring back.
 *
 * Built from the collection rather than from a fixed list, so nothing is ever
 * offered that would match nothing - and so a collection of Spanish budget
 * titles offers its own publishers rather than somebody else's.
 *
 * Commonest first. On the collection this was designed against that puts Ocean
 * and Platform at the top, which is what somebody scrolling a list of 277
 * developers needs.
 */
public final class Facets {

    /** One offerable value, and how many games have it. */
    public static final class Value {
        public final String name;
        public final int count;

        public Value(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    private Facets() {
    }

    /**
     * Genre, developer and publisher, from one walk of the store.
     *
     * {@link Filters.Field#FORMAT} comes back empty: a format is a property of
     * the filename, and plenty of games have one without the store knowing
     * anything about them. See {@link #formatsOf}.
     */
    public static Map<Filters.Field, List<Value>> of(Collection<Meta> games) {
        Map<String, Integer> genres = new LinkedHashMap<>();
        Map<String, Integer> subgenres = new LinkedHashMap<>();
        Map<String, Integer> developers = new LinkedHashMap<>();
        Map<String, Integer> publishers = new LinkedHashMap<>();

        for (Meta game : games) {
            if (game == null) continue;

            for (String genre : Filters.genresOf(game.genre)) add(genres, genre);
            add(subgenres, game.subgenre);
            add(developers, game.developer);
            add(publishers, game.publisher);
        }

        Map<Filters.Field, List<Value>> all = new EnumMap<>(Filters.Field.class);
        all.put(Filters.Field.FORMAT, Collections.emptyList());
        all.put(Filters.Field.GENRE, ranked(genres));
        all.put(Filters.Field.SUBGENRE, ranked(subgenres));
        all.put(Filters.Field.DEVELOPER, ranked(developers));
        all.put(Filters.Field.PUBLISHER, ranked(publishers));

        // The three statuses, in their own order rather than by count: they are
        // fixed questions rather than values this collection happens to hold,
        // so ranking them would make the sheet's rows move about as somebody
        // finishes games.
        //
        // Offered only where there is something to find, which is the rule
        // every other field here already follows - a genre nobody has is a
        // genre not worth a row, and an empty store offers nothing at all.
        // "Not completed" is the one that is nearly always there, and it is
        // still counted rather than assumed: a collection where everything is
        // finished should not be offered a filter that selects none of it.
        List<Value> statuses = new ArrayList<>();
        int completed = 0;
        int played = 0;
        int rows = 0;

        for (Meta game : games) {
            if (game == null) continue;

            rows++;
            if (game.isCompleted()) completed++;
            if (game.plays() > 0) played++;
        }

        if (completed > 0) statuses.add(new Value(Filters.COMPLETED, completed));
        if (rows - completed > 0) {
            statuses.add(new Value(Filters.NOT_COMPLETED, rows - completed));
        }
        if (played > 0) statuses.add(new Value(Filters.PLAYED, played));

        all.put(Filters.Field.STATUS, statuses);

        return all;
    }

    /** The formats present among these entries, commonest first. */
    public static List<Value> formatsOf(Collection<Entry> entries) {
        Map<String, Integer> formats = new LinkedHashMap<>();

        for (Entry entry : entries) {
            if (entry == null || entry.kind != Entry.Kind.FILE) continue;
            add(formats, Filters.formatOf(entry));
        }

        return ranked(formats);
    }

    private static void add(Map<String, Integer> counts, String value) {
        if (value == null) return;

        String trimmed = value.trim();
        if (trimmed.isEmpty()) return;

        Integer had = counts.get(trimmed);
        counts.put(trimmed, had == null ? 1 : had + 1);
    }

    /** Commonest first, and alphabetically within a tie so the order does not
     *  wander between one build of the list and the next. */
    private static List<Value> ranked(Map<String, Integer> counts) {
        List<Value> values = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            values.add(new Value(entry.getKey(), entry.getValue()));
        }

        Collections.sort(values, (left, right) -> {
            if (left.count != right.count) return right.count - left.count;
            return left.name.toLowerCase(Locale.ROOT)
                            .compareTo(right.name.toLowerCase(Locale.ROOT));
        });

        return values;
    }
}
