package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Scraping a great many games, with no screen anywhere in it.
 *
 * The same relationship to {@code ScrapeManyActivity} that {@link Scrape} has
 * to {@code ScrapeOneGame}: everything here can be run against a fake {@link
 * Provider} and a fake {@link Http} with no network and no device dialog,
 * which is the whole reason part two built those two as interfaces.
 *
 * <b>Serial, not a pool.</b> An account without a subscription is allowed one
 * request in flight, and asking for more was measured to change nothing. The
 * loop is the pacing.
 *
 * Two entry points rather than one, because a person deserves to know what
 * they are committing to: {@link #select} answers "which games, and so how
 * many requests" without asking the service anything, and {@link #run} is the
 * twenty minutes.
 */
public final class Sweep {

    private static final String TAG = "Zedex";

    private Sweep() {
    }

    // --- what to sweep --------------------------------------------------------------

    /**
     * Which games a run is about.
     *
     * Deliberately not offering "games with no metadata". On a collection
     * that has been linked to ES-DE the store is already mostly full, so that
     * filter would match almost nothing and a run would appear to do nothing
     * at all - and since {@link #NOT_SCRAPED} is also how a stopped run is
     * resumed, getting this question wrong would break resuming rather than
     * merely being a poor default.
     */
    public enum Only {

        /**
         * Anything this app has not itself scraped.
         *
         * The true question, and the true resume: run it again after a
         * cancel, a spent quota or a crash and it is exactly the games that
         * were not reached. ES-DE's own rows count as not scraped, because
         * they are what somebody scraping is trying to improve on.
         */
        NOT_SCRAPED,

        /** Nothing in the media folder to draw - {@link Artwork#picture},
         *  which is the cover falling back to the other folders, and so is
         *  what "games with no image" actually means to somebody looking at
         *  the grid. */
        NO_PICTURE,

        /** The lot, re-fetching what is already there. */
        EVERYTHING,
    }

    /**
     * What to do when the provider answers with more than one game, or with
     * one it is not certain of.
     *
     * Asked once at the start rather than once per game, which is the
     * difference between a run somebody can walk away from and a run that
     * needs them.
     */
    public enum Conflicts {

        /** Counted, nothing written. They keep no row, so {@link
         *  Only#NOT_SCRAPED} finds them again for the one-game chooser. */
        SKIP,

        /**
         * The provider's own first answer, used unasked.
         *
         * <b>Not a judgement this app makes</b> - it is ScreenScraper's
         * relevance order and nothing more. On a Spectrum collection the
         * filename search is wrong often enough that this will put some wrong
         * covers on, which is why it is neither the default nor silent: the
         * screen offering it says so.
         */
        BEST,

        /** {@link Watcher#chooseFrom}, per conflict, on the sweep thread. */
        ASK,
    }

    /**
     * The games in {@code entries} that {@code only} is asking about.
     *
     * Local work: the store and the media folder, no network. Separate from
     * {@link #run} so the screen can put a number and a cost in front of
     * somebody before they commit to twenty minutes of it.
     *
     * Folders, archives and anything without a path of its own are dropped
     * here rather than counted and then skipped - they are not games and
     * including them would make the total a lie. A hand-edited row is
     * <em>not</em> dropped: see {@link Tally#yours}.
     */
    public static List<Entry> select(Context context, List<Entry> entries, Only only) {
        List<Entry> chosen = new ArrayList<>();

        for (Entry entry : entries) {
            if (entry.kind != Entry.Kind.FILE) continue;

            String path = Metadata.relativePath(context, entry.uri);
            if (path == null) continue;

            if (wanted(context, path, only)) chosen.add(entry);
        }

        return chosen;
    }

    private static boolean wanted(Context context, String path, Only only) {
        switch (only) {
            case EVERYTHING:
                return true;

            case NO_PICTURE:
                return Artwork.picture(context, path) == null;

            case NOT_SCRAPED:
            default:
                Meta meta = Metadata.forPath(context, path);
                // isEsde covers a null source too, which is what an
                // unrecognised row reads as.
                return meta == null || meta.isEsde() || meta.isMine();
        }
    }

    // --- what happened --------------------------------------------------------------

    /**
     * What a run came to.
     *
     * Five outcomes rather than a done-and-not-done pair, because five
     * different things happen to a collection and "212 done, 79 not" hides
     * which of them somebody needs to act on: a hundred unknown games is the
     * service's coverage, a hundred ambiguous ones is an afternoon with the
     * chooser, and a hundred failures is something wrong.
     *
     * Mutable, and only by {@link Sweep}. The alternative was a ten-argument
     * constructor, which is the shape {@code Meta.with} carries a warning
     * about for having already gone wrong once in this codebase.
     */
    public static final class Tally {

        /** How many {@link #select} handed over. */
        public int total;

        /** How many were reached, whether or not anything came of them. */
        public int done;

        /** Written: the facts stored, and whatever media arrived. */
        public int scraped;

        /** Several candidates, or one the provider was unsure of, and the
         *  policy was {@link Conflicts#SKIP}. */
        public int ambiguous;

        /** The provider had never heard of it. The ordinary outcome for much
         *  of a Spectrum collection, and not a failure. */
        public int unknown;

        /** Left alone because somebody had typed it by hand. */
        public int yours;

        /** Something went wrong for that one game and the run carried on. */
        public int failed;

        /** Pictures, videos and manuals written across the whole run. */
        public int media;

        public boolean cancelled;

        /** Why the run stopped early, or null if it finished. */
        public ScrapeException stopped;

        /** Whether it got all the way to the end under its own steam. */
        public boolean complete() {
            return !cancelled && stopped == null;
        }
    }

    // --- the screen's side of the conversation --------------------------------------

    /**
     * Whatever is watching a run, which in practice is a screen.
     *
     * Three methods, and it stays at three: CLAUDE.md's rule is that a
     * collaborator interface past about four means the seam is in the wrong
     * place, and this one is close enough to the line to be worth defending.
     *
     * All three are called <b>on the sweep thread</b>, never the UI thread.
     * {@link #chooseFrom} is the awkward one and is awkward on purpose - see
     * its own comment.
     */
    public interface Watcher {

        /** Asked before every game, and while backing off. */
        boolean cancelled();

        /** About to start on this one. {@code done} games are behind it. */
        void at(int done, int total, String game);

        /**
         * Which of several, from a person.
         *
         * Only called under {@link Conflicts#ASK}, and it <b>blocks the sweep
         * thread until somebody answers</b> - the screen posts a dialog and
         * waits on a latch. There is deliberately no timeout: a run that
         * skipped a game because nobody was looking would be worse than one
         * that waits, and Cancel is right there.
         */
        Choice chooseFrom(List<Candidate> found, String game);
    }

    /**
     * An answer to {@link Watcher#chooseFrom}.
     *
     * Three states rather than a nullable {@link Candidate}, because of the
     * third: after thirty of these dialogs a person wants out, and without a
     * way to say "stop asking and finish the rest" the only escape from a long
     * tail of conflicts is cancelling the whole run and losing the games still
     * ahead of it.
     */
    public static final class Choice {

        /** The one to use, or null to leave this game alone. */
        public final Candidate candidate;

        /** Carry on with {@link Conflicts#SKIP} from here. */
        public final boolean stopAsking;

        private Choice(Candidate candidate, boolean stopAsking) {
            this.candidate = candidate;
            this.stopAsking = stopAsking;
        }

        public static Choice of(Candidate candidate) {
            return new Choice(candidate, false);
        }

        public static Choice skip() {
            return new Choice(null, false);
        }

        public static Choice skipTheRest() {
            return new Choice(null, true);
        }
    }

    // --- the run --------------------------------------------------------------------

    /** How many times a game whose failure is worth waiting out is tried. */
    private static final int ATTEMPTS = 3;

    /** Between attempts, multiplied by which attempt this is: two seconds,
     *  then four. A thread limit clears in about that; longer would make a
     *  wobble halfway through a collection cost minutes. */
    private static final long BACKOFF_MS = 2000;

    /** How often the back-off looks up to see whether Cancel was pressed.
     *  Sleeping the whole two seconds would make Cancel appear broken. */
    private static final long CANCEL_POLL_MS = 200;

    /**
     * Scrapes every game in {@code entries}, one at a time.
     *
     * Runs until it finishes, is cancelled, or meets something there is no
     * point carrying on past. Whichever of the three happened is in the
     * {@link Tally}, which is always returned and never null.
     *
     * @param entries what {@link #select} handed back
     */
    public static Tally run(Context context, Provider provider, Http http,
                            List<Entry> entries, Provider.Wanted wanted,
                            Conflicts conflicts, Watcher watcher) {
        Tally tally = new Tally();
        tally.total = entries.size();

        // The cost of one game, and so how much has to be left before there
        // is any point starting another. Only the provider can answer it: a
        // ScreenScraper cover is a mediaJeu.php call and costs one, a ZXInfo
        // cover is a static file and costs nothing.
        int perGame = provider.costPerGame(wanted);

        for (Entry entry : entries) {
            if (watcher.cancelled()) {
                tally.cancelled = true;
                return tally;
            }

            if (spent(provider, perGame)) {
                tally.stopped = new ScrapeException(
                        ScrapeException.Kind.QUOTA_EXCEEDED,
                        "the day's allowance is down to "
                        + provider.quota().left() + ", and a game costs " + perGame);
                return tally;
            }

            watcher.at(tally.done, tally.total, entry.name);

            try {
                conflicts = one(context, provider, http, entry, wanted, conflicts,
                                watcher, tally);
            } catch (ScrapeException fatal) {
                tally.stopped = fatal;
                return tally;
            }

            tally.done++;
        }

        return tally;
    }

    /**
     * Whether there is definitely not enough allowance left for another game.
     *
     * <b>Asked rather than waited for.</b> ScreenScraper does not refuse when
     * an account is over its allowance - forcing the counter to 100000 against
     * an allowance of 10000 still answered 200 with a real candidate - so the
     * counters it puts in every reply are the only warning there is, and a run
     * that waited to be refused would simply never be.
     *
     * Unknown does not stop anything. {@link Quota#left} answers -1 before the
     * first reply and whenever the service did not say, and refusing to try on
     * a guess is worse than one refused request.
     */
    private static boolean spent(Provider provider, int perGame) {
        Quota quota = provider.quota();
        if (quota == null) return false;

        int left = quota.left();
        return left >= 0 && left < perGame;
    }

    /**
     * One game, returning the conflict policy the rest of the run should use -
     * which is the one it was given, unless somebody asked to stop being
     * asked.
     *
     * @throws ScrapeException only for the kinds there is no point carrying on
     *                         past; everything else is counted in the tally
     */
    private static Conflicts one(Context context, Provider provider, Http http,
                                 Entry entry, Provider.Wanted wanted,
                                 Conflicts conflicts, Watcher watcher, Tally tally)
            throws ScrapeException {
        String path = Metadata.relativePath(context, entry.uri);

        if (path == null) {
            // select() drops these, so reaching here means the tree moved
            // under the run. One game's worth of nothing, not a reason to
            // stop.
            tally.failed++;
            return conflicts;
        }

        // Before the search, not after: the check is local, and asking the
        // service about a game whose answer is going to be thrown away would
        // spend the allowance on nothing.
        if (Scrape.wouldOverwriteAHandEdit(context, path)) {
            tally.yours++;
            return conflicts;
        }

        List<Candidate> found;

        try {
            found = attempt(() -> Scrape.candidates(context, provider, entry, path), watcher);
        } catch (ScrapeException e) {
            return carryOnOrStop(e, tally, conflicts);
        }

        if (found.isEmpty()) {
            tally.unknown++;
            return conflicts;
        }

        Candidate chosen;

        if (Scrape.certain(found)) {
            chosen = found.get(0);
        } else {
            switch (conflicts) {
                case BEST:
                    chosen = found.get(0);
                    break;

                case ASK:
                    Choice choice = watcher.chooseFrom(found, entry.name);
                    if (choice.stopAsking) conflicts = Conflicts.SKIP;
                    chosen = choice.candidate;
                    break;

                case SKIP:
                default:
                    chosen = null;
                    break;
            }
        }

        if (chosen == null) {
            tally.ambiguous++;
            return conflicts;
        }

        try {
            Downloads.Result result =
                    attempt(() -> Scrape.apply(context, provider, http, chosen, path, wanted),
                            watcher);
            tally.scraped++;
            tally.media += result.saved;
        } catch (ScrapeException e) {
            // The facts may well have been stored before the pictures were
            // refused - Scrape.apply writes them first on purpose - so this
            // game is not necessarily untouched. It is still not a success.
            return carryOnOrStop(e, tally, conflicts);
        }

        return conflicts;
    }

    /**
     * Whether one game's failure is the whole run's.
     *
     * The same line {@code Downloads} draws for one game's media, for the same
     * reason and from the same enum: a spent quota or a refused password is
     * every remaining game as well as this one, and there is no point
     * discovering it eight hundred more times.
     */
    private static Conflicts carryOnOrStop(ScrapeException e, Tally tally, Conflicts conflicts)
            throws ScrapeException {
        switch (e.kind) {
            case QUOTA_EXCEEDED:
            case BAD_CREDENTIALS:
            case CLOSED:
            case NOT_CONFIGURED:
                throw e;

            default:
                Log.w(TAG, "carrying on past " + e.kind, e);
                tally.failed++;
                return conflicts;
        }
    }

    // --- backing off ------------------------------------------------------------------

    /** One request, so that {@link #attempt} can retry either half of a
     *  scrape without knowing which it is holding. */
    private interface Step<T> {
        T run() throws ScrapeException;
    }

    /**
     * Runs one step, waiting out the failures that are worth waiting out.
     *
     * Only the two {@link ScrapeException#worthWaiting} kinds are retried. A
     * thread limit is the ordinary reason a loop this shape stumbles and
     * clears by itself in a second or two; a network that went away often
     * comes back. Everything else is thrown at once, because trying a refused
     * password three times is three refusals.
     *
     * Retried per step rather than per game deliberately: a fetch that failed
     * after its search succeeded is re-fetched, not re-searched, or the
     * retry would cost an extra request against the day's allowance every
     * time.
     */
    private static <T> T attempt(Step<T> step, Watcher watcher) throws ScrapeException {
        ScrapeException last = null;

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (attempt > 1 && !pause(BACKOFF_MS * (attempt - 1), watcher)) break;

            try {
                return step.run();
            } catch (ScrapeException e) {
                if (!e.worthWaiting()) throw e;

                Log.w(TAG, "attempt " + attempt + " of " + ATTEMPTS + " met " + e.kind);
                last = e;
            }
        }

        throw last;
    }

    /** Waits, looking up often enough that Cancel still means something.
     *  False if it was cancelled or interrupted, in which case the caller
     *  should stop rather than try again. */
    private static boolean pause(long millis, Watcher watcher) {
        long until = SystemClock.uptimeMillis() + millis;

        while (SystemClock.uptimeMillis() < until) {
            if (watcher.cancelled()) return false;

            try {
                Thread.sleep(CANCEL_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return !watcher.cancelled();
    }
}
