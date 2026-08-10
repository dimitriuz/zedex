package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;

import java.util.List;

/**
 * Somewhere the app can ask about a game.
 *
 * Three of these are planned and one is written; the interface exists so the
 * other two slot in rather than being bolted alongside. What it deliberately
 * does <em>not</em> abstract is what each service is good at - the caller asks
 * for a game and gets candidates, and which of a hash, a filename or a
 * hand-typed name got there is the provider's own business.
 *
 * Two steps, not one, and that is the whole shape of the feature: a search
 * that may answer with several games, and a fetch for the one that was chosen.
 * A single {@code scrape(game)} would have to decide on the user's behalf what
 * to do about ambiguity, and deciding wrongly means one game's cover on
 * another game for ever.
 *
 * Nothing here touches the network directly - everything goes through {@link
 * Http}, which the tests replace.
 */
public interface Provider {

    /** What to show a person choosing between providers. Not translated: it
     *  is the service's own name. */
    String name();

    /**
     * Whether this provider can be asked anything at all.
     *
     * False when its credentials are missing, which for ScreenScraper is the
     * ordinary state of a source build - see {@code app/build.gradle}. Every
     * entry point checks this and hides itself rather than offering something
     * that can only fail.
     */
    boolean configured();

    /**
     * Games this provider thinks {@code game} might be.
     *
     * Empty when it is confident there is nothing, which is an answer and not
     * an error - most of a collection is obscure. One entry means the provider
     * is certain, which is what lets a scrape fill itself in without asking.
     *
     * @throws ScrapeException for anything that is not an answer: credentials
     *         refused, quota spent, the service closed, a body that will not
     *         parse. Those are told apart because a multi-scrape has to pause
     *         for some and stop for others.
     */
    List<Candidate> search(Game game) throws ScrapeException;

    /**
     * Everything this provider has for one candidate.
     *
     * {@code wanted} says which media to bother resolving; asking for none is
     * legitimate and means metadata only.
     */
    Scraped fetch(Candidate candidate, Wanted wanted) throws ScrapeException;

    /**
     * What a bare HTTP status from this service means.
     *
     * Media are fetched from the same API as everything else - for
     * ScreenScraper a cover is a {@code mediaJeu.php} call, credentials and
     * all - so a download can be refused for exactly the reasons a search can,
     * and a spent quota arrives as a status on a picture rather than as a
     * parsed reply. Only the provider knows what its own codes mean, and
     * {@code Downloads} has to ask before deciding whether one missing cover
     * is a reason to stop everything.
     */
    ScrapeException refusalFor(int status);

    /**
     * What the last reply said about how much asking is left.
     *
     * Null before anything has been asked. ScreenScraper reports the day's
     * count, the day's allowance and how many requests may be in flight in
     * every single reply, which is the only reason a multi-scrape can pace
     * itself rather than discovering the limit by hitting it.
     */
    Quota quota();

    /**
     * How many requests one game costs, so a screen can say so before
     * somebody commits to a collection.
     *
     * On the provider and not on {@link Wanted} because the answer is not a
     * property of what was asked for: a ScreenScraper cover is a
     * {@code mediaJeu.php} call and costs one, a ZXInfo cover is a static
     * file and costs nothing. The same two hundred games are eight hundred
     * requests against one service and two hundred against the other, and a
     * sweep that assumed either would be wrong by a factor of four.
     */
    int costPerGame(Wanted wanted);

    /**
     * The game being asked about, as much as is known cheaply.
     *
     * The hash is a supplier rather than a value because taking it means
     * reading the whole file through the documents provider, and a provider
     * that matched on the filename alone should not pay for one. Called at
     * most once.
     */
    interface Game {
        /** ES-DE's own key, {@code ./folder/Game.tap} - what the store and
         *  the media folder are both addressed by. */
        String path();

        /** The file's own name, extension and all. */
        String filename();

        /** Bytes, or -1 when it is not known. */
        long size();

        /** Lower-case hex MD5 of the file, or null when it cannot be read. */
        String md5();
    }

    /**
     * Which media a fetch should resolve, by folder.
     *
     * Folder names rather than a handful of booleans, because that is the
     * question actually being asked and because the arithmetic matters: every
     * medium is a separate request against the day's allowance - a cover is a
     * {@code mediaJeu.php} call exactly like a search is - so a game with
     * everything selected costs nine or ten, and eight hundred of those is
     * more than a day. Naming folders lets the default be the three this app
     * actually draws and lets part five hand a person's own choice straight
     * through.
     *
     * The names are ES-DE's, which is what this app's media folder mirrors -
     * see {@code Artwork}. Translating a provider's own vocabulary into them
     * is the provider's job.
     */
    final class Wanted {

        /**
         * What a scrape takes unless told otherwise: the cover every row and
         * tile draws, and the two the pane's gallery is worth opening for.
         *
         * Four requests a game including the search, which leaves room to
         * scrape a whole collection twice over. Video is twenty megabytes and
         * a manual is rarely looked at; both are opt-in.
         */
        public static Wanted usual() {
            return of("covers", "screenshots", "titlescreens");
        }

        private final java.util.Set<String> folders;

        private Wanted(java.util.Set<String> folders) {
            this.folders = folders;
        }

        public static Wanted of(String... folders) {
            return new Wanted(new java.util.LinkedHashSet<>(java.util.Arrays.asList(folders)));
        }

        /**
         * From a stored set, which is where a person's own choice arrives -
         * see {@code Prefs.KEY_SCRAPE_MEDIA}.
         *
         * Copied rather than held: a {@code Set} handed back by {@code
         * getStringSet} belongs to the preferences and must not be kept, let
         * alone mutated, which is a documented way to corrupt them.
         *
         * An empty set stays empty. That is metadata only, and it is the one
         * case where "nothing chosen" must not quietly become "the default" -
         * the caller has already decided whether absent means the default,
         * because only the caller can tell absent from empty.
         */
        public static Wanted of(java.util.Set<String> folders) {
            return new Wanted(new java.util.LinkedHashSet<>(folders));
        }

        /** The folders themselves, for a screen that has to name them. */
        public java.util.Set<String> folders() {
            return java.util.Collections.unmodifiableSet(folders);
        }

        /** Metadata only, which is a real thing to want and the cheapest
         *  possible scrape. */
        public static Wanted nothing() {
            return new Wanted(java.util.Collections.emptySet());
        }

        public boolean wants(String folder) {
            return folders.contains(folder);
        }

        public boolean any() {
            return !folders.isEmpty();
        }

        /** How many requests this will cost per game, media only. */
        public int requests() {
            return folders.size();
        }
    }

    /**
     * What a fetch answered: the facts, and where the media can be got.
     *
     * The media are URLs rather than files. Downloading them is {@link
     * Downloads}' job and is the same work whichever provider named them,
     * where deciding what a game is called differs completely between the
     * three.
     */
    final class Scraped {
        public final Meta meta;
        public final List<Medium> media;

        public Scraped(Meta meta, List<Medium> media) {
            this.meta = meta;
            this.media = media;
        }
    }
}
