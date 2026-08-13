package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;

import java.util.List;

/**
 * Two answers about the same game, combined.
 *
 * <b>One rule for every field: a gap may be filled, a value may never be
 * replaced.</b> That is the whole of it, and it is what makes scraping from
 * several services safe to do at all - {@code Scrapers} used to record that
 * merging was rejected because "two sources disagreeing about a name or a
 * year needs a rule per field", and there is no rule per field here because
 * the priority order has already decided who gets asked first. Whoever
 * answers first keeps the field.
 *
 * Its own class rather than a method on {@link Blend} for one reason: it is
 * the only part of the loop with no {@code Context} in it, so it can be
 * tested on the JVM - including {@code MergeTest.everyFieldIsMerged}, which
 * walks {@link Meta} by reflection so that a field added there and forgotten
 * here cannot go unnoticed.
 */
public final class Merge {

    private Merge() {
    }

    /**
     * {@code base}, with anything it is missing taken from {@code addition}.
     *
     * {@code path} and {@code source} are the base's own and are never taken
     * from the addition: the path is the key this row is stored under, and
     * the contributor list is {@link Blend}'s to write once it knows who
     * actually contributed something.
     *
     * A null addition answers the base unchanged, which is what a source that
     * knew nothing amounts to.
     */
    public static Meta of(Meta base, Meta addition) {
        if (addition == null) return base;
        if (base == null) return addition;

        return base.but()
                .name(first(base.name, addition.name))
                .desc(first(base.desc, addition.desc))
                .developer(first(base.developer, addition.developer))
                .publisher(first(base.publisher, addition.publisher))
                .genre(first(base.genre, addition.genre))
                .subgenre(first(base.subgenre, addition.subgenre))
                .released(first(base.released, addition.released))
                .players(first(base.players, addition.players))
                .rating(first(base.rating, addition.rating))
                .keymap(first(base.keymap, addition.keymap))
                .machine(first(base.machine, addition.machine))
                .price(first(base.price, addition.price))
                .series(first(base.series, addition.series))

                // Gaps, like everything else. A play count is the one field
                // here the app writes for itself, and it still merges this
                // way: an ES-DE link filling an empty count is right, and
                // overwriting a count this app has been keeping would throw
                // away the only record of it.
                .playCount(first(base.playCount, addition.playCount))
                .completed(first(base.completed, addition.completed))
                .inputs(firstList(base.inputs, addition.inputs))
                .authors(firstList(base.authors, addition.authors))
                .seriesGames(firstList(base.seriesGames, addition.seriesGames))
                .compilations(firstList(base.compilations, addition.compilations))
                .contents(firstList(base.contents, addition.contents))
                .build();
    }

    private static String first(String base, String addition) {
        return base != null && !base.isEmpty() ? base : addition;
    }

    /** Empty is absent for a list - {@link Meta} keeps lists empty rather
     *  than null, so there is no other way for one to say it has nothing. */
    private static <T> List<T> firstList(List<T> base, List<T> addition) {
        return base != null && !base.isEmpty() ? base : addition;
    }
}
