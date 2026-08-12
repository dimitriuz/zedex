package dev.ldlab.zedex.library.scrape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A sweep's downloads, off the scraping thread.
 *
 * On the JVM: what {@link Backlog} does is queue work, count it, keep the
 * refusals and wait for the queue - none of which is Android, and all of which
 * a device test would only make slower and harder to read.
 *
 * The thing under test is a promise about <em>when</em>, so every assertion
 * here is about that: that {@code take} does not wait, that the work happens
 * on another thread, that {@code drain} does wait, and that a refusal from a
 * download is kept for the sweep to act on rather than thrown at nobody.
 */
public class BacklogTest {

    private static final long PATIENCE_MS = 5_000;

    /**
     * The whole point: handing over a download does not wait for it.
     *
     * A job that will not finish until this test says so, and a {@code take}
     * that has already returned - which is the sweep free to go and make its
     * next paced request while the picture downloads behind it.
     */
    @Test
    public void takingAJobDoesNotWaitForIt() throws Exception {
        Backlog backlog = new Backlog();

        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch letItFinish = new CountDownLatch(1);

        int answered = backlog.take("Somebody", () -> {
            running.countDown();
            waitFor(letItFinish);
            return 3;
        });

        assertEquals("a queued download cannot have landed yet, and saying it "
                     + "has would put a number in the tally that is not on disk",
                     0, answered);

        assertTrue("the job never started", running.await(PATIENCE_MS, TimeUnit.MILLISECONDS));

        letItFinish.countDown();
        assertEquals(3, backlog.drain());
    }

    /** And it happens somewhere else, which is the only reason any of this
     *  exists - a download on the scraping thread is a download the pacer's
     *  sleep is queued behind. */
    @Test
    public void theworkHappensOnAnotherThread() throws Exception {
        Backlog backlog = new Backlog();

        AtomicReference<Thread> ran = new AtomicReference<>();
        backlog.take("Somebody", () -> {
            ran.set(Thread.currentThread());
            return 1;
        });

        backlog.drain();

        assertNotEquals("the download ran on the thread that handed it over",
                        Thread.currentThread(), ran.get());
    }

    /** Two games' downloads never overlap each other: one thread, so the file
     *  hosts see exactly the traffic they saw before this class existed. */
    @Test
    public void jobsRunOneAtATime() throws Exception {
        Backlog backlog = new Backlog();

        AtomicInteger inside = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();

        for (int at = 0; at < 8; at++) {
            backlog.take("Somebody", () -> {
                int now = inside.incrementAndGet();
                mostAtOnce.accumulateAndGet(now, Math::max);
                pause(5);
                inside.decrementAndGet();
                return 1;
            });
        }

        assertEquals(8, backlog.drain());
        assertEquals("two downloads ran at once, which is more than the file "
                     + "hosts were being asked for before",
                     1, mostAtOnce.get());
    }

    /** Draining waits for what is still queued, or the tally reports fewer
     *  pictures than are on disk. */
    @Test
    public void drainingWaitsForEverythingQueued() {
        Backlog backlog = new Backlog();

        for (int at = 0; at < 5; at++) {
            backlog.take("Somebody", () -> {
                pause(10);
                return 2;
            });
        }

        assertEquals("drain answered before the queue had emptied",
                     10, backlog.drain());
    }

    /**
     * A refusal is kept for the sweep, not thrown at a thread that has gone.
     *
     * This is what the overlap costs and how it is paid back: the failure can
     * no longer reach the game it belongs to, so it waits here until the sweep
     * asks at the next game boundary - where it still drops the source that
     * raised it.
     */
    @Test
    public void arefusalIsKeptForWhoeverAsksNext() {
        Backlog backlog = new Backlog();

        backlog.take("ScreenScraper", () -> {
            throw new ScrapeException(ScrapeException.Kind.QUOTA_EXCEEDED,
                                      "no allowance left");
        });

        backlog.drain();

        List<Blend.Failure> refused = backlog.refusals();

        assertEquals(1, refused.size());
        assertEquals("ScreenScraper", refused.get(0).source);
        assertEquals(ScrapeException.Kind.QUOTA_EXCEEDED, refused.get(0).why.kind);
    }

    /** And only once: the sweep drops the source that raised it, and a second
     *  helping would drop a source that has already gone. */
    @Test
    public void arefusalIsHandedOverOnlyOnce() {
        Backlog backlog = new Backlog();

        backlog.take("ScreenScraper", () -> {
            throw new ScrapeException(ScrapeException.Kind.QUOTA_EXCEEDED, "spent");
        });

        backlog.drain();

        assertEquals(1, backlog.refusals().size());
        assertTrue("the same refusal came back a second time",
                   backlog.refusals().isEmpty());
    }

    /** One download failing does not stop the ones behind it - a missing
     *  manual must not cost every later game its cover. */
    @Test
    public void afailedDownloadDoesNotTakeTheQueueWithIt() {
        Backlog backlog = new Backlog();

        backlog.take("Somebody", () -> {
            throw new RuntimeException("something unexpected");
        });
        backlog.take("Somebody", () -> 4);

        assertEquals("the queue died with the first failure",
                     4, backlog.drain());
    }

    /** Cancel means cancel: whatever has not started does not start. */
    @Test
    public void stoppingDropsWhatHasNotRunYet() throws Exception {
        Backlog backlog = new Backlog();

        CountDownLatch holding = new CountDownLatch(1);
        backlog.take("Somebody", () -> {
            waitFor(holding);
            return 1;
        });

        for (int at = 0; at < 5; at++) backlog.take("Somebody", () -> 1);

        backlog.stop();
        holding.countDown();

        assertEquals("a cancelled sweep went on downloading the queue",
                     1, backlog.drain());

        assertEquals("and it took new work after being stopped",
                     0, backlog.take("Somebody", () -> 1));
    }

    /** Nothing queued is not an error - a run where every game was already
     *  complete drains to nothing. */
    @Test
    public void anemptyBacklogDrainsToNothing() {
        assertEquals(0, new Backlog().drain());
    }

    /** {@code Job.run} may only throw a ScrapeException, so the waiting these
     *  tests do to pin down *when* things happen is swallowed here rather
     *  than in six lambdas. */
    private static void waitFor(CountDownLatch latch) {
        try {
            latch.await(PATIENCE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
