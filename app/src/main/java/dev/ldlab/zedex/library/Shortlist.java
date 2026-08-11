package dev.ldlab.zedex.library;

import dev.ldlab.zedex.library.meta.Meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the library actually shows, out of everything it loaded: the search
 * box's own narrowing, the filter, and the sort, in that order.
 *
 * The one piece of the library with real branching that has nothing Android in
 * it. It used to be the first half of {@code LibraryActivity.applyFilterSort},
 * inseparable from the second half - the adapter, the empty label, the
 * selection, the restored scroll - which is view work and stays there. Nothing
 * here touches a view, a {@code Context} or a preference, so a JVM test can
 * ask it what it shows for a given collection and get an answer, which is what
 * it exists apart for; the emulator was previously the only way to find out.
 *
 * The metadata cache is the reason this is a class rather than two loose
 * methods. The filter asks for a row's {@link Meta} once, and the comparator
 * asks again on every comparison it takes part in - {@code Collections.sort}
 * calls it O(n log n) times over the whole list - so one map shared across
 * both passes is what keeps a search-box keystroke from costing a metadata
 * lookup per row per comparison. See {@code Sorting.comparator} on why it only
 * asks at all for the two fields that need it.
 */
public final class Shortlist {

    private Shortlist() {
    }

    /**
     * @param loaded     everything the last listing produced, untouched.
     * @param query      the search box, matched against a row's shown name,
     *                   case-insensitively; empty narrows nothing.
     * @param filters    what is narrowed to. Only consulted while {@code
     *                   flat}, since a filter belongs to Browse alone.
     * @param flat       whether a filter is on <em>and</em> applies here -
     *                   which is also whether the list has folders to hold
     *                   apart. Passed rather than derived from {@code
     *                   filters.isEmpty()}: which tab this is decides whether
     *                   a filter applies at all, and that is the caller's
     *                   business, not this class's. One predicate answering
     *                   both questions is a mistake this codebase has already
     *                   made once - see CLAUDE.md on {@code startsInLibrary}.
     * @param sort       one of {@link Sorting#FIELDS}; anything else falls
     *                   back to the default, per {@code fieldOrDefault}.
     * @param descending reverses whichever field {@code sort} names.
     * @param lookup     how a row's metadata is reached. Asked at most once
     *                   per row for this whole call, whatever either pass
     *                   does - so it need not be cheap, and need not be
     *                   idempotent.
     */
    public static List<Entry> of(List<Entry> loaded, String query, Filters filters,
                                 boolean flat, String sort, boolean descending,
                                 boolean scrapedNames, Sorting.Lookup lookup) {
        // Keyed by the row itself, not by Entry#key(): Entry declares no
        // equals, so this is identity, which is exactly the question being
        // asked - one listing hands out one object per row, and what the
        // comparator asks about over and over is that same object. Building
        // the key string instead meant a uri.toString() on every probe, which
        // is the very per-comparison cost this map is here to avoid.
        Map<Entry, Meta> cache = new HashMap<>();

        Sorting.Lookup cached = entry -> {
            // containsKey, not a null check: most rows have no metadata at
            // all, and a null answer costs exactly as much to ask for again
            // as a real one. Caching only the hits would have left the
            // greater part of the cost this exists to remove.
            if (!cache.containsKey(entry)) cache.put(entry, lookup.of(entry));
            return cache.get(entry);
        };

        List<Entry> shown = narrow(loaded, query, filters, flat, cached);
        sort(shown, flat, sort, descending, scrapedNames, cached);

        return shown;
    }

    private static List<Entry> narrow(List<Entry> loaded, String query, Filters filters,
                                      boolean flat, Sorting.Lookup lookup) {
        List<Entry> shown = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);

        for (Entry entry : loaded) {
            if (!needle.isEmpty()
                    && !entry.name.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }

            // flat, not "the tab is Browse": that plain tab check is true on
            // every keystroke in the search box even with nothing set, which
            // ran the lookup - a content-folder read plus a store lookup - for
            // every non-folder row on every call for no reason at all. flat
            // already implies Browse and is false the moment nothing is set,
            // which removes the whole cost from the unfiltered path without
            // changing what is shown: Favourites and Recent are already an
            // answer to a question of their own, and a filter set in Browse
            // and left in place must not narrow them too. See the design
            // spec, "Filtering applies to Browse only". Folders are never
            // filtered either way - they are how you move, not what you are
            // looking for.
            if (flat && entry.kind != Entry.Kind.FOLDER
                    && !filters.matches(entry, lookup.of(entry))) {
                continue;
            }

            shown.add(entry);
        }
        return shown;
    }

    /**
     * Folders stay first and alphabetical whatever the sort says - the same
     * rule {@link Listing#folder} itself sorts by - since they are what Browse
     * is walked through rather than a game to weigh by size or rating. A no-op
     * split for Favourites and Recents, which are never folders, and skipped
     * entirely while {@code flat}: a flattened list has no folders in it to
     * hold apart from the rest.
     */
    private static void sort(List<Entry> list, boolean flat, String field,
                             boolean descending, boolean scrapedNames,
                             Sorting.Lookup lookup) {
        if (flat) {
            Collections.sort(list, Sorting.comparator(field, descending, scrapedNames,
                                                      lookup));
            return;
        }

        List<Entry> folders = new ArrayList<>();
        List<Entry> rest = new ArrayList<>();

        for (Entry entry : list) {
            (entry.kind == Entry.Kind.FOLDER ? folders : rest).add(entry);
        }

        folders.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        Collections.sort(rest, Sorting.comparator(field, descending, scrapedNames,
                                                 lookup));

        list.clear();
        list.addAll(folders);
        list.addAll(rest);
    }
}
