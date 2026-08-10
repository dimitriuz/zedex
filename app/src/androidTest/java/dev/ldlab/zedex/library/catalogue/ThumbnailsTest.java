package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.ldlab.zedex.library.scrape.Http;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/**
 * Covers for the rows that are on screen.
 *
 * On a device because a Bitmap is Android's and because the bound that matters
 * is a fraction of this process's own heap. What is pinned here is the
 * request count rather than the picture: a grid flung past a row and back is
 * the ordinary case, and a cache that misses on the way back doubles every
 * fetch - which against an address that blocks on behaviour patterns is not a
 * performance question.
 */
@RunWith(AndroidJUnit4.class)
public class ThumbnailsTest {

    private static final String URL = "https://example/covers/HeadOverHeels.jpg";

    private Context context;

    /** Writes a 1x1 png, and counts. */
    private static final class OnePixel implements Http {
        final List<String> asked = new ArrayList<>();

        @Override
        public Reply get(String url) {
            throw new UnsupportedOperationException("not this test's business");
        }

        @Override
        public synchronized String save(String url, File into) throws java.io.IOException {
            asked.add(url);

            Bitmap dot = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            try (FileOutputStream out = new FileOutputStream(into)) {
                dot.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return "00000000000000000000000000000000";
        }
    }

    /**
     * Writes a 1x1 png like {@link OnePixel}, but only after the test
     * releases it - so a caller landing while this is blocked is
     * genuinely concurrent with the fetch, not merely re-entrant on the
     * same thread before the pool has picked the task up.
     */
    private static final class Blocking implements Http {
        final List<String> asked = new ArrayList<>();

        /** Counted down the moment save() is actually entered - proof the
         *  fetch is really running, not merely submitted. */
        final CountDownLatch entered = new CountDownLatch(1);

        /** Held closed by the test until it wants save() to finish. */
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Reply get(String url) {
            throw new UnsupportedOperationException("not this test's business");
        }

        @Override
        public String save(String url, File into) throws IOException {
            synchronized (asked) {
                asked.add(url);
            }
            entered.countDown();

            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Bitmap dot = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            try (FileOutputStream out = new FileOutputStream(into)) {
                dot.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return "00000000000000000000000000000000";
        }

        int askedCount() {
            synchronized (asked) {
                return asked.size();
            }
        }
    }

    /** Throws, the way a 404 or a timeout would. */
    private static final class Failing implements Http {
        final List<String> asked = new ArrayList<>();

        @Override
        public Reply get(String url) {
            throw new UnsupportedOperationException("not this test's business");
        }

        @Override
        public synchronized String save(String url, File into) throws IOException {
            asked.add(url);
            throw new IOException("404");
        }
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        Thumbnails.forget();
    }

    /** A miss answers null immediately - the adapter draws a placeholder and
     *  gets on with binding the next row, rather than blocking layout on the
     *  network. */
    @Test
    public void amissIsNullAndDoesNotBlock() {
        assertNull(Thumbnails.get(URL));
    }

    /** What arrives is cached, and asking again costs nothing. */
    @Test
    public void whatArrivesIsCachedAndNotFetchedTwice() throws Exception {
        OnePixel http = new OnePixel();
        CountDownLatch arrived = new CountDownLatch(1);

        Thumbnails.load(context, http, URL, (url, picture) -> arrived.countDown());
        assertEquals("the picture never arrived", true,
                     arrived.await(10, TimeUnit.SECONDS));

        assertNotNull("it was not cached", Thumbnails.get(URL));

        Thumbnails.load(context, http, URL, (url, picture) -> { });
        assertEquals("it was fetched a second time", 1, http.asked.size());
    }

    /**
     * <b>A second caller before the first finishes joins rather than
     * fetches.</b>
     *
     * Both calls are made from this one thread, back to back, so this
     * proves re-entrancy before completion - a naive check-then-start
     * would already pass it, since only one thread is ever inside the
     * decision. {@link #twoThreadsAskingAtOnceStillMakeOneRequest} is what
     * exercises the same guarantee under real concurrency.
     */
    @Test
    public void joiningAFetchAlreadyUnderwayMakesOneRequest() throws Exception {
        OnePixel http = new OnePixel();
        CountDownLatch both = new CountDownLatch(2);

        Thumbnails.load(context, http, URL, (url, picture) -> both.countDown());
        Thumbnails.load(context, http, URL, (url, picture) -> both.countDown());

        assertEquals("both callers were not told", true,
                     both.await(10, TimeUnit.SECONDS));
        assertEquals("the same picture was fetched twice", 1, http.asked.size());
    }

    /**
     * <b>Two rows wanting the same picture make one request - even asked
     * from two real threads at once.</b>
     *
     * The same cover appears in a search result and again in whatever list
     * it was reached from, and a grid scrolled past a row and back asks
     * again before the first answer has landed - both are genuinely
     * concurrent callers, not one thread calling twice. A {@link
     * CyclicBarrier} releases two threads together, and {@link Blocking}
     * holds {@code save()} open until both calls have returned and the
     * fetch one of them started has actually entered {@code save()} - so
     * the second call is guaranteed to land while the first is really in
     * flight, not merely submitted, whichever of the two threads happens
     * to be the one that starts it.
     */
    @Test
    public void twoThreadsAskingAtOnceStillMakeOneRequest() throws Exception {
        Blocking http = new Blocking();
        CyclicBarrier together = new CyclicBarrier(2);
        CountDownLatch told = new CountDownLatch(2);

        Runnable ask = () -> {
            try {
                together.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            Thumbnails.load(context, http, URL, (url, picture) -> told.countDown());
        };

        Thread first = new Thread(ask);
        Thread second = new Thread(ask);
        first.start();
        second.start();
        first.join(10_000);
        second.join(10_000);

        assertEquals("the fetch never really started", true,
                     http.entered.await(10, TimeUnit.SECONDS));

        http.release.countDown();

        assertEquals("both callers were not told", true,
                     told.await(10, TimeUnit.SECONDS));
        assertEquals("the same picture was fetched twice", 1, http.askedCount());
    }

    /** A url of nothing is not a request. Plenty of catalogue entries have no
     *  picture at all, and those rows are text rows. */
    @Test
    public void nourlIsNorequest() {
        OnePixel http = new OnePixel();

        Thumbnails.load(context, http, null, (url, picture) -> { });

        assertEquals(0, http.asked.size());
    }

    /**
     * A url known to answer with nothing is not asked again until {@link
     * Thumbnails#forget()} - a row scrolling back on screen must not repeat
     * a request already known to fail, the same pattern that got this
     * app's address blocked once.
     */
    @Test
    public void aknownFailureIsNotRetriedUntilForget() throws Exception {
        Failing http = new Failing();

        CountDownLatch missed = new CountDownLatch(1);
        Thumbnails.load(context, http, URL, (url, picture) -> missed.countDown());
        assertEquals("the miss was never reported", true,
                     missed.await(10, TimeUnit.SECONDS));

        CountDownLatch again = new CountDownLatch(1);
        Thumbnails.load(context, http, URL, (url, picture) -> again.countDown());
        assertEquals("the second caller was not told", true,
                     again.await(10, TimeUnit.SECONDS));
        assertEquals("a known failure was retried", 1, http.asked.size());

        Thumbnails.forget();

        CountDownLatch retried = new CountDownLatch(1);
        Thumbnails.load(context, http, URL, (url, picture) -> retried.countDown());
        assertEquals("forget() did not clear the remembered failure", true,
                     retried.await(10, TimeUnit.SECONDS));
        assertEquals("forget() should allow exactly one retry", 2, http.asked.size());
    }
}
