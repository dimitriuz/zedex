package dev.ldlab.zedex.library.scrape;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A sweep's downloads, on one thread of their own, so the scraping thread can
 * be waiting for the next game while they run.
 *
 * <b>What this is for.</b> The facts come from an API this app deliberately
 * spaces its calls to - see {@code ZxInfo.MINIMUM_INTERVAL_MS} - and the
 * pictures come from hosts nobody spaces. Fetched on the same thread, the two
 * queue up behind each other: the sweep downloads a game's cover, then sleeps
 * before the next game's first request, having had nothing to do during the
 * sleep and something to do the whole time. Measured on eight hundred games,
 * the two halves are about the same size, so overlapping them takes something
 * close to a third off a full sweep.
 *
 * <b>One thread, deliberately.</b> Two games' downloads never overlap each
 * other, which means this asks no more of anybody's file host than fetching
 * them in line did - and the file hosts are the one part of this that has no
 * pacing to protect it. The only thing that changes is what the scraping
 * thread does while it waits.
 *
 * <b>What is given up.</b> A refusal from a media host used to reach {@code
 * Blend.run}'s own per-source catch and land in that game's failures, where
 * {@code Sweep} reads it and may drop the source - a spent ScreenScraper
 * allowance being the case that matters. Fetched behind the scraping thread,
 * that refusal arrives after the game has been counted, so {@link #refusals}
 * hands it to the sweep at the next game boundary instead: one game later at
 * worst, and never lost. Quota is mostly seen without this anyway, since
 * {@code Sweep} paces on the counters in every reply rather than waiting to
 * be refused.
 */
final class Backlog implements Blend.Installs {

    private static final String TAG = "Zedex";

    /** How long {@link #drain} will wait for the last downloads. Long because
     *  a manual over a slow connection is a real file and the alternative is
     *  a tally that undercounts what is actually on disk; not unbounded,
     *  because a sweep that will not finish is worse than a number that is
     *  short. */
    private static final long DRAIN_TIMEOUT_MINUTES = 5;

    private final ExecutorService one =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "zedex-media");
                thread.setDaemon(true);
                return thread;
            });

    private final AtomicInteger installed = new AtomicInteger();

    private final ConcurrentLinkedQueue<Blend.Failure> failures =
            new ConcurrentLinkedQueue<>();

    /** Set by {@link #stop}; read by every job that has not started yet, so a
     *  cancelled sweep does not go on downloading for another minute. */
    private volatile boolean stopped;

    /**
     * Queues the download and answers straight away.
     *
     * Zero, always: nothing has landed yet by definition, and the caller's own
     * per-game count would be a lie either way. The real number comes from
     * {@link #drain} at the end of the run, which is the only moment anybody
     * asks for it.
     */
    @Override
    public int take(String source, Job job) {
        if (stopped) return 0;

        one.execute(() -> {
            if (stopped) return;

            try {
                installed.addAndGet(job.run());
            } catch (ScrapeException e) {
                // Kept rather than thrown: the thread that could have acted on
                // it has moved on to the next game. See the class comment.
                failures.add(new Blend.Failure(source, e));
            } catch (RuntimeException e) {
                // A download must not be able to kill this thread and leave
                // every later game silently un-illustrated.
                Log.w(TAG, "media for " + source + " failed", e);
            }
        });

        return 0;
    }

    /**
     * Whatever has refused since this was last asked, and not again.
     *
     * Drained rather than read, because the sweep acts on each one once -
     * dropping the source that raised it - and a list that kept handing back
     * the same refusal would drop a source that had already gone.
     */
    List<Blend.Failure> refusals() {
        List<Blend.Failure> taken = new ArrayList<>();

        for (Blend.Failure failure; (failure = failures.poll()) != null; ) {
            taken.add(failure);
        }

        return taken;
    }

    /** Nothing more, and nothing still queued - a cancelled sweep. Whatever is
     *  already downloading is left to finish, since a half-written file is
     *  worse than a spare one and {@code Downloads} deletes those itself. */
    void stop() {
        stopped = true;
    }

    /**
     * Waits for the queue to empty and answers how many files landed in all.
     *
     * Called once, at the end of a run, from the thread that started it.
     */
    int drain() {
        one.shutdown();

        try {
            if (!one.awaitTermination(DRAIN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                Log.w(TAG, "media downloads did not finish in "
                           + DRAIN_TIMEOUT_MINUTES + " minutes");
            }
        } catch (InterruptedException e) {
            // The screen that started the sweep has gone; the count is not
            // worth holding the thread for.
            Thread.currentThread().interrupt();
        }

        return installed.get();
    }
}
