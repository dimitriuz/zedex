package dev.ldlab.zedex.work;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where background work goes.
 *
 * There were twenty-eight {@code new Thread(...)} sites and no policy between
 * them: no bound on how many could exist at once, no names to read in a trace
 * or a bug report, and no way to stop one - the idiom throughout was to let
 * the work finish and then compare a token to decide whether to believe the
 * answer. That is fine for one row and wrong for a selection a person is
 * moving through with a pad, where every step started another thread that
 * nobody could call off.
 *
 * Two lanes, because the work is genuinely two kinds:
 *
 * {@link #run} is for the short kind - read a store, resolve a path, stage a
 * file, ask what a folder holds. Bounded, so a fast scroll cannot spawn a
 * hundred of them, and cancellable, so a caller that has moved on can say so
 * rather than paying for an answer it will discard.
 *
 * {@link #alone} is for work whose whole nature is that it takes a while: a
 * fourteen megabyte download, copying a ROM set, encoding a recording. One
 * of those in a bounded pool holds a lane for minutes and starves everything
 * else, so each gets its own thread and its own name.
 *
 * What this deliberately does not touch: the pools built for one job with
 * their own reasons written beside them - {@code Listing}'s eight-wide walk,
 * {@code Gallery}'s decode and prefetch, {@code Scraped}'s two. Folding those
 * into one shared pool would undo the very thing each was sized for.
 */
public final class Work {

    private Work() {
    }

    /**
     * How many short tasks run at once.
     *
     * These are nearly all waiting on a documents provider or a disk rather
     * than computing, so this is not a core count - it is how many round
     * trips are worth having outstanding before the provider itself becomes
     * the queue. Four at minimum so a small device still overlaps them.
     */
    private static final int LANES =
            Math.max(4, Runtime.getRuntime().availableProcessors());

    private static final ExecutorService POOL =
            Executors.newFixedThreadPool(LANES, named("zedex-work"));

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * Runs {@code task} on the shared pool, for work measured in
     * milliseconds.
     *
     * The returned handle can be cancelled; cancelling one that has not
     * started keeps it from ever running, and cancelling one that has does
     * not interrupt it - the work here is I/O against a content provider,
     * which does not answer an interrupt in any useful way, so a caller that
     * cancels late should still check whether it still wants the answer.
     */
    public static Future<?> run(String name, Runnable task) {
        return POOL.submit(() -> {
            Thread self = Thread.currentThread();
            String was = self.getName();

            // The pool's threads are reused, so the name says what is running
            // now rather than what ran first - which is the only form of it
            // that is any use in a trace.
            self.setName("zedex-work:" + name);
            try {
                task.run();
            } finally {
                self.setName(was);
            }
        });
    }

    /**
     * Runs {@code task} on a thread of its own, for work measured in seconds
     * or minutes.
     *
     * Not the pool: one download or one ROM copy would hold a lane for the
     * whole of it and leave the short work queued behind something the user
     * is not waiting on.
     */
    public static Thread alone(String name, Runnable task) {
        Thread thread = new Thread(task, "zedex-" + name);
        thread.start();
        return thread;
    }

    /** Back on the main thread, for handing an answer to a view. */
    public static void onMain(Runnable task) {
        MAIN.post(task);
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger next = new AtomicInteger();

        return task -> {
            Thread thread = new Thread(task, prefix + "-" + next.incrementAndGet());
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
    }
}
