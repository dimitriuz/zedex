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
 * Static state, deliberately. There is one network and one of each host, and
 * an instance per caller is exactly the arrangement this replaces.
 *
 * {@code System.nanoTime} rather than {@code SystemClock.elapsedRealtime}: it
 * is monotonic in the same way, and it keeps this class out of Android
 * entirely so its arithmetic can be tested in milliseconds rather than on a
 * device in minutes.
 */
public final class Pace {

    private static final Map<String, Long> lastAsked = new HashMap<>();

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
    public static synchronized void before(String host, long minimumMs) {
        if (minimumMs <= 0) return;

        Long previous = lastAsked.get(host);
        long now = System.nanoTime() / 1_000_000L;

        if (previous != null) {
            long since = now - previous;

            if (since < minimumMs) {
                try {
                    Thread.sleep(minimumMs - since);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                now = System.nanoTime() / 1_000_000L;
            }
        }

        lastAsked.put(host, now);
    }

    /** Tests only: forget every host, so one test's pacing is not the next
     *  test's wait. */
    public static synchronized void forget() {
        lastAsked.clear();
    }
}
