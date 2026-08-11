package dev.ldlab.zedex.library.scrape;

import java.util.HashMap;
import java.util.Map;

/**
 * How long ago a host was last asked, and sleeping the difference.
 *
 * <b>Per host and not per object.</b> ZXInfo publishes no rate limit and asks
 * clients to behave; the one time this app misbehaved, the address was blocked
 * at the network layer and it took an email to lift - "you have been jailed
 * because of bad requests... there's no hard limit, it's all based on
 * behaviour patterns". So the thing being spaced is the traffic arriving at
 * the far end, which is not a property of whichever object here happened to
 * send it. A provider scraping and a catalogue browsing are two objects and
 * one address.
 *
 * <b>The lock only reserves a slot; it never sleeps.</b> A first version of
 * this held one lock for the whole of {@link #before}, sleep included -
 * which paced every host through a single queue rather than one each, so a
 * call for one host sat behind another host's sleep and the two blocked each
 * other exactly as badly as the per-instance pacing this class replaced.
 * What has to happen atomically is small: read this host's next free slot,
 * take the later of "now" and "the previous slot plus the minimum interval",
 * and write that back. Arithmetic, not I/O - so the lock is held for that and
 * released before anyone sleeps. Reserving the *slot* rather than recording
 * "the time last asked" is what keeps two callers for the <em>same</em> host
 * correctly queued despite the short lock: a second caller who reads the
 * reservation while the first is still sleeping sees a slot already pushed
 * out far enough, and cannot read "nothing to wait for" and fire alongside
 * it. A caller for a different host never meets that lock for longer than
 * the arithmetic takes, sleep or no sleep.
 *
 * Static state, deliberately. There is one network and one of each host, and
 * an instance per caller is exactly the arrangement this replaces.
 *
 * {@code System.nanoTime} rather than {@code SystemClock.elapsedRealtime}: it
 * is monotonic in the same way, and it keeps this class out of Android
 * entirely so its arithmetic can be tested in milliseconds rather than on a
 * device in minutes.
 */
public final class Pace {

    /** Each host's next free slot, in {@link #nowMs} terms - not "the last
     *  time asked". A slot can be reserved before the wait behind it has
     *  actually elapsed; see the class comment. */
    private static final Map<String, Long> nextSlot = new HashMap<>();

    private Pace() {
    }

    /**
     * Returns when it is this host's turn.
     *
     * Call immediately before the request, never after: the clock is stamped
     * here, so a caller that stamps late spaces from the end of one request
     * to the start of the next and drifts slower than asked. Slower is the
     * safe direction, but it is not the stated one.
     *
     * An interrupt is not swallowed - it is re-raised on the thread, because
     * the one thing that legitimately interrupts a paced request is the
     * screen that started it going away.
     */
    public static void before(String host, long minimumMs) {
        if (minimumMs <= 0) return;

        long wait;

        synchronized (Pace.class) {
            long now = nowMs();
            Long reserved = nextSlot.get(host);
            long earliest = reserved == null ? now : reserved + minimumMs;
            long slot = Math.max(now, earliest);

            wait = slot - now;
            nextSlot.put(host, slot);
        }

        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Tests only: forget every host, so one test's pacing is not the next
     *  test's wait. */
    public static synchronized void forget() {
        nextSlot.clear();
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
