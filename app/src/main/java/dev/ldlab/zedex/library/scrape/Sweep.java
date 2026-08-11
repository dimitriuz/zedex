package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
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

        /**
         * The lot, including games this app has already scraped.
         *
         * <b>It no longer re-fetches what is there.</b> Nothing does: a scrape
         * fills gaps and never overwrites, so this differs from {@link
         * #NOT_SCRAPED} only in also revisiting games that already carry a
         * provider's name - which is worth having when a source has been added
         * to the order since the last run, and is worth nothing otherwise.
         *
         * Kept rather than removed because people have used it, and because
         * "ask the new source about everything" is exactly what somebody wants
         * the first time they turn a second source on.
         */
        EVERYTHING,
    }

    /**
     * What to do when a source answers with more than one game, or with
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
         * The source's own first answer, used unasked.
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
     * including them would make the total a lie. A hand-edited row is not
     * dropped either: a scrape can only fill in what nobody has typed, so
     * there is nothing left to protect by skipping it.
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
     * Four outcomes rather than a done-and-not-done pair, because four
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

        /** Several candidates, or one a source was unsure of, and the
         *  policy was {@link Conflicts#SKIP}. */
        public int ambiguous;

        /** No source had ever heard of it. The ordinary outcome for much of a
         *  Spectrum collection, and not a failure. */
        public int unknown;

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
         * {@code sourceName} because more than one service is asked about each
         * game now, and "choose one of these five" without saying who is
         * asking is a question with a fact missing - the same file matches
         * differently at each service, and which one is offering these five is
         * part of judging them.
         *
         * Called under {@link Conflicts#ASK} only, and it <b>blocks the sweep
         * thread until somebody answers</b>. There is deliberately no timeout:
         * a run that skipped a game because nobody was looking would be worse
         * than one that waits, and Cancel is right there.
         */
        Choice chooseFrom(String sourceName, List<Candidate> found, String game);
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

    /**
     * Scrapes every game in {@code entries}, one at a time, against every
     * source in turn.
     *
     * Runs until it finishes, is cancelled, or every source has run out.
     * Whichever of the three happened is in the {@link Tally}, which is
     * always returned and never null.
     *
     * @param entries what {@link #select} handed back
     */
    public static Tally run(Context context, List<Provider> sources, Http http,
                            List<Entry> entries, Provider.Wanted wanted,
                            Conflicts conflicts, Watcher watcher) {
        Tally tally = new Tally();
        tally.total = entries.size();

        // Sources are dropped as they run out, so this shrinks. A quota or a
        // refused password is every remaining game for *that* service and
        // nothing at all for the others - ZXInfo has no allowance to spend.
        List<Provider> live = new ArrayList<>(sources);
        ScrapeException last = null;

        for (Entry entry : entries) {
            if (watcher.cancelled()) {
                tally.cancelled = true;
                return tally;
            }

            dropTheSpent(live, wanted);

            if (live.isEmpty()) {
                tally.stopped = last != null ? last : new ScrapeException(
                        ScrapeException.Kind.QUOTA_EXCEEDED,
                        "every source is out of allowance: " + allowanceSummary(sources, wanted));
                return tally;
            }

            watcher.at(tally.done, tally.total, entry.name);

            Outcome outcome = one(context, live, http, entry, wanted, conflicts, watcher, tally);
            conflicts = outcome.conflicts;

            for (Blend.Failure failure : outcome.failures) {
                if (isHopeless(failure.why)) {
                    last = failure.why;
                    drop(live, failure.source);
                }
            }

            tally.done++;
        }

        return tally;
    }

    /**
     * What one game came to, beyond the tally: which sources refused, so the
     * run can drop them.
     *
     * A holder rather than a field on {@code Sweep} itself - a static mutable
     * is exactly the trap {@code Pace} documents, and {@link #run} is called
     * from one thread at a time only by convention, not by anything that
     * enforces it.
     */
    private static final class Outcome {
        Conflicts conflicts;
        List<Blend.Failure> failures = Collections.emptyList();
    }

    /**
     * One game, against every source still live.
     *
     * @return what happened, so {@link #run} can carry the conflict policy
     *         forward and drop whichever sources just refused
     */
    private static Outcome one(Context context, List<Provider> live, Http http,
                               Entry entry, Provider.Wanted wanted, Conflicts conflicts,
                               Watcher watcher, Tally tally) {
        Outcome outcome = new Outcome();
        outcome.conflicts = conflicts;

        String path = Metadata.relativePath(context, entry.uri);

        if (path == null) {
            // select() drops these, so reaching here means the tree moved
            // under the run. One game's worth of nothing, not a reason to
            // stop.
            tally.failed++;
            return outcome;
        }

        Blend.Chooser chooser = (sourceName, found, game) -> {
            switch (outcome.conflicts) {
                case BEST:
                    return found.get(0);

                case ASK:
                    Choice choice = watcher.chooseFrom(sourceName, found, game);
                    if (choice.stopAsking) outcome.conflicts = Conflicts.SKIP;
                    return choice.candidate;

                case SKIP:
                default:
                    return null;
            }
        };

        Blend.Result result = Blend.run(context, live, http, entry, path, wanted,
                                        Blend.Media.FILL_GAPS, chooser,
                                        watcher::cancelled);

        if (watcher.cancelled()) {
            // Cancellation reaching Blend mid-back-off surfaces as a
            // ScrapeException in result.failures for whichever source it
            // interrupted - the wait was cut short, not refused, and that
            // source may well have answered on a fourth try that never
            // happened. Acting on it here would report a failure that never
            // really occurred; only a genuine success - already written by
            // Blend.run itself - is trusted once cancel has been seen.
            //
            // In practice this cannot also drop a source for nothing: the
            // only kind Blend can manufacture this way is one of the two
            // Blend.attempt retries (THREAD_LIMIT, NETWORK), and neither is
            // among isHopeless's four. Skipping outcome.failures here anyway
            // is a hedge against that no longer being true - a kind added to
            // one list and forgotten in the other - not a present risk.
            if (!result.consulted.isEmpty()) {
                tally.scraped++;
                tally.media += result.installed;
            }
            return outcome;
        }

        outcome.failures = result.failures;

        if (!result.consulted.isEmpty()) {
            tally.scraped++;
            tally.media += result.installed;
        } else if (!result.failures.isEmpty()) {
            tally.failed++;
        } else if (result.ambiguous) {
            // Found, and nobody would say which. An afternoon with the
            // chooser, which is a different thing to act on than a service
            // that has never heard of the game.
            tally.ambiguous++;
        } else {
            // Nobody answered and nobody refused either - the ordinary
            // "never heard of it", but also what a fully-described row under
            // Only.EVERYTHING looks like: Blend.nothingLeftToGain breaks its
            // loop before asking anyone, so consulted, failures and ambiguous
            // are all empty here too. Counted as unknown rather than a fifth
            // tally field for "already had everything" - re-scraping a
            // complete row is the rare case EVERYTHING exists for, not the
            // ordinary one, and the row itself is unharmed either way.
            tally.unknown++;
        }

        return outcome;
    }

    /**
     * Drops any source that cannot afford another game.
     *
     * <b>Asked rather than waited for.</b> ScreenScraper does not refuse when
     * an account is over its allowance - forcing the counter to 100000 against
     * an allowance of 10000 still answered 200 with a real candidate - so the
     * counters it puts in every reply are the only warning there is.
     *
     * Unknown does not drop anything: {@link Quota#left} answers -1 before the
     * first reply and whenever the service did not say, and refusing to try on
     * a guess is worse than one refused request.
     */
    private static void dropTheSpent(List<Provider> live, Provider.Wanted wanted) {
        for (Iterator<Provider> each = live.iterator(); each.hasNext(); ) {
            Provider provider = each.next();

            Quota quota = provider.quota();
            if (quota == null) continue;

            int left = quota.left();
            if (left >= 0 && left < provider.costPerGame(wanted)) {
                Log.w(TAG, provider.name() + " has " + left
                           + " left and a game costs more; dropping it for this run");
                each.remove();
            }
        }
    }

    /**
     * Every source's name and quota, for the message logcat shows when a run
     * stops early with nobody left standing.
     *
     * Named and counted rather than "every source is out of allowance": that
     * line is read after the fact, by somebody who was not watching the run,
     * and "ScreenScraper 0 left, ZXInfo no stated allowance" says which
     * source did what where the old wording said nothing at all.
     */
    private static String allowanceSummary(List<Provider> sources, Provider.Wanted wanted) {
        StringBuilder text = new StringBuilder();

        for (Provider source : sources) {
            if (text.length() > 0) text.append(", ");

            Quota quota = source.quota();
            int left = quota == null ? -1 : quota.left();

            text.append(source.name()).append(' ');
            if (left < 0) {
                text.append("no stated allowance");
            } else {
                text.append(left).append(" left, a game costs ")
                    .append(source.costPerGame(wanted));
            }
        }

        return text.toString();
    }

    /** Drops one source by name, for the rest of the run. */
    private static void drop(List<Provider> live, String name) {
        for (Iterator<Provider> each = live.iterator(); each.hasNext(); ) {
            if (each.next().name().equals(name)) {
                Log.w(TAG, "dropping " + name + " for the rest of the run");
                each.remove();
                return;
            }
        }
    }

    /**
     * Whether a refusal is one that source will keep making.
     *
     * The same line {@code Downloads} draws for one game's media, from the
     * same enum and for the same reason - and now drawn per source rather than
     * per run, because one service running out says nothing about another.
     */
    private static boolean isHopeless(ScrapeException e) {
        switch (e.kind) {
            case QUOTA_EXCEEDED:
            case BAD_CREDENTIALS:
            case CLOSED:
            case NOT_CONFIGURED:
                return true;
            default:
                return false;
        }
    }
}
