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
     * What the last reply said about how much asking is left.
     *
     * Null before anything has been asked. ScreenScraper reports the day's
     * count, the day's allowance and how many requests may be in flight in
     * every single reply, which is the only reason a multi-scrape can pace
     * itself rather than discovering the limit by hitting it.
     */
    Quota quota();

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

    /** Which media a fetch should resolve. */
    final class Wanted {
        public final boolean pictures;
        public final boolean video;
        public final boolean manual;

        public Wanted(boolean pictures, boolean video, boolean manual) {
            this.pictures = pictures;
            this.video = video;
            this.manual = manual;
        }

        /** Metadata only. */
        public static Wanted nothing() {
            return new Wanted(false, false, false);
        }

        public boolean any() {
            return pictures || video || manual;
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
