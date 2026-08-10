package dev.ldlab.zedex.library.scrape;

import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * How long ago this host was last asked.
 *
 * On the JVM because the whole of it is a clock and a map, and because the
 * failure it exists to stop is invisible from the outside: two objects each
 * waiting half a second and neither waiting for the other looks exactly like
 * one object waiting half a second, right up until an address is blocked for
 * "behaviour patterns".
 */
public class PaceTest {

    @Before
    public void setUp() {
        Pace.forget();
    }

    /** The first ask of a host waits for nothing - there is nothing to wait
     *  behind, and a spinner half a second long for one request is a cost
     *  paid for no reason. */
    @Test
    public void thefirstAskIsNotDelayed() {
        long began = System.nanoTime();

        Pace.before("api.example", 500);

        assertTrue("the first call waited", elapsedMs(began) < 100);
    }

    /**
     * <b>The whole point: the second ask waits, whoever makes it.</b>
     *
     * Not "the same object's second ask" - Pace is asked by host, so a
     * catalogue and a scraper hitting the same API queue behind each other
     * rather than interleaving into twice the rate.
     */
    @Test
    public void asecondAskOfTheSameHostWaits() {
        Pace.before("api.example", 200);
        long began = System.nanoTime();

        Pace.before("api.example", 200);

        assertTrue("the second call did not wait", elapsedMs(began) >= 190);
    }

    /** A different host is a different queue. Waiting on ZXInfo's account
     *  before asking spectrumcomputing for a picture would make a grid of
     *  thumbnails take a minute. */
    @Test
    public void adifferentHostIsNotWaitedOn() {
        Pace.before("api.example", 200);
        long began = System.nanoTime();

        Pace.before("files.example", 200);

        assertTrue("an unrelated host was waited on", elapsedMs(began) < 100);
    }

    /** Zero and below mean no pacing at all, rather than a busy loop. */
    @Test
    public void nointervalIsNoWait() {
        Pace.before("api.example", 0);
        long began = System.nanoTime();

        Pace.before("api.example", 0);

        assertTrue(elapsedMs(began) < 100);
    }

    private static long elapsedMs(long began) {
        return (System.nanoTime() - began) / 1_000_000L;
    }
}
