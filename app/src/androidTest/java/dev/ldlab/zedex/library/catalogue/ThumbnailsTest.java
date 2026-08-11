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

    /**
     * Refuses, the way a host with no such picture does.
     *
     * A {@link Http.Refused} and not a bare {@link IOException}, and the
     * difference is the whole of what {@link Thumbnails} decides by: this is
     * the host <em>answering</em>, which nothing but a new file upstream will
     * change. See {@link Unreachable}, which is the other thing.
     */
    private static final class Refusing implements Http {
        final List<String> asked = new ArrayList<>();
        private final int status;

        Refusing(int status) {
            this.status = status;
        }

        @Override
        public Reply get(String url) {
            throw new UnsupportedOperationException("not this test's business");
        }

        @Override
        public synchronized String save(String url, File into) throws IOException {
            asked.add(url);
            throw new Refused(status);
        }
    }

    /** Never gets there at all - a timeout, a lost tunnel, a name that did not
     *  resolve. Says nothing whatever about the url. */
    private static final class Unreachable implements Http {
        final List<String> asked = new ArrayList<>();

        @Override
        public Reply get(String url) {
            throw new UnsupportedOperationException("not this test's business");
        }

        @Override
        public synchronized String save(String url, File into) throws IOException {
            asked.add(url);
            throw new IOException("failed to connect");
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
     * A url the host <b>answered</b> with something that is not a picture is
     * not asked again until {@link Thumbnails#forget()} - a row scrolling back
     * on screen must not repeat a request already known to fail, the same
     * pattern that got this app's address blocked once.
     */
    @Test
    public void aknownFailureIsNotRetriedUntilForget() throws Exception {
        Refusing http = new Refusing(404);

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

    /**
     * <b>A moment offline is not a url that will never work.</b>
     *
     * Nothing in the app calls {@link Thumbnails#forget()} and {@code
     * CatalogueView.retry()} re-asks for the <em>page</em> rather than for its
     * covers, so a failure remembered here is remembered for the life of the
     * process. That is right for a 404, whose justification - "a request
     * already known to fail" - is exactly true, and wrong for a phone that
     * lost its tunnel for one second while a screenful of rows bound: those
     * covers used to be blacklisted until the app was killed.
     *
     * So the rule is what the failure says about the url, not how it was
     * thrown. Here nothing came back at all, and the next bind asks again.
     * <em>That</em> next bind, and no more than that until a cooldown has
     * passed - see {@link #arebindingRowCannotFloodAhostItCannotReach}.
     */
    @Test
    public void aurlThatCouldNotBeReachedIsAskedAgain() throws Exception {
        Unreachable lost = new Unreachable();

        CountDownLatch missed = new CountDownLatch(1);
        Thumbnails.load(context, lost, URL, (url, picture) -> missed.countDown());
        assertEquals("the miss was never reported", true,
                     missed.await(10, TimeUnit.SECONDS));
        assertNull("nothing should have been cached", Thumbnails.get(URL));

        // The tunnel is back, and nothing has called forget().
        OnePixel back = new OnePixel();
        CountDownLatch arrived = new CountDownLatch(1);
        Thumbnails.load(context, back, URL, (url, picture) -> arrived.countDown());

        assertEquals("the second caller was not told", true,
                     arrived.await(10, TimeUnit.SECONDS));
        assertEquals("a url that was never reached was blacklisted", 1, back.asked.size());
        assertNotNull("the retry did not land", Thumbnails.get(URL));
    }

    /**
     * And nor is a host asking for a moment - but it is asked again when
     * <em>it</em> is ready, not on the next bind.
     *
     * 429 is "not so fast" and 408 is the host's own timeout - both are
     * answers, and neither is one about this url, so blacklisting a cover
     * over either would punish a row for the state of the queue when it
     * happened to scroll past. What it does buy is a cooldown, from the
     * first refusal: an immediate re-ask is the behaviour a 429 complained
     * about. So this waits the cooldown out rather than asserting a retry on
     * the very next call, and asserts the cover then lands by itself - no
     * {@link Thumbnails#forget()}, nobody killing the app.
     */
    @Test
    public void abusyHostIsAskedAgainOnceItsCooldownHasPassed() throws Exception {
        Refusing busy = new Refusing(429);

        CountDownLatch missed = new CountDownLatch(1);
        Thumbnails.load(context, busy, URL, (url, picture) -> missed.countDown());
        assertEquals("the miss was never reported", true,
                     missed.await(10, TimeUnit.SECONDS));

        // Polled rather than slept through: the cooldown is a duration by
        // construction, but how promptly the pool gets to the retry is not,
        // and ten seconds is far past the first wait without pinning the
        // test to it.
        OnePixel later = new OnePixel();
        long deadline = System.currentTimeMillis() + 10_000;
        while (Thumbnails.get(URL) == null && System.currentTimeMillis() < deadline) {
            CountDownLatch bound = new CountDownLatch(1);
            Thumbnails.load(context, later, URL, (url, picture) -> bound.countDown());
            bound.await(10, TimeUnit.SECONDS);

            if (Thumbnails.get(URL) == null) Thread.sleep(100);
        }

        assertNotNull("a 429 was treated as permanent", Thumbnails.get(URL));
        assertEquals("the cooldown let more than one request through",
                     1, later.asked.size());
    }

    /**
     * <b>Retryable is not unbounded, and a 429 is where that matters most.</b>
     *
     * A grid does not bind once: a scroll, a rotation and every {@code
     * setRows} rebind every row on screen. With a transient failure simply
     * unremembered, each of those rebinds starts another fetch - so a host
     * turning requests away gets a fresh request per bind, for ever, on the
     * shared pool {@code CatalogueView.fetch()} and its own <b>Try again</b>
     * button queue behind. Against an address that was once blocked at the
     * network layer for behaviour patterns, answering "not so fast" with
     * more traffic is the worst thing this class could do.
     *
     * Fifty rebinds, each waited out so none of them merely joins the fetch
     * before it: one request. Against a bound of nothing this reads fifty.
     */
    @Test
    public void arebindingRowCannotFloodAhostThatIsRefusing() throws Exception {
        Refusing busy = new Refusing(429);

        for (int bind = 0; bind < 50; bind++) {
            CountDownLatch told = new CountDownLatch(1);
            Thumbnails.load(context, busy, URL, (url, picture) -> told.countDown());
            assertEquals("a bind was never answered", true,
                         told.await(10, TimeUnit.SECONDS));
        }

        assertEquals("a rebinding row asked a refusing host again and again",
                     1, busy.asked.size());
    }

    /**
     * The same bound from the other side: a phone with no network at all.
     *
     * Nothing came back, which says nothing about the url and troubles
     * nobody, so the very next bind is allowed to ask again - that is the
     * moment-offline case this class must not blacklist. What it must not do
     * is keep allowing it: the second consecutive failure starts a cooldown
     * like any other, so fifty rebinds cost two requests rather than fifty.
     */
    @Test
    public void arebindingRowCannotFloodAhostItCannotReach() throws Exception {
        Unreachable lost = new Unreachable();

        for (int bind = 0; bind < 50; bind++) {
            CountDownLatch told = new CountDownLatch(1);
            Thumbnails.load(context, lost, URL, (url, picture) -> told.countDown());
            assertEquals("a bind was never answered", true,
                         told.await(10, TimeUnit.SECONDS));
        }

        assertEquals("an offline phone rebound its way into a flood",
                     2, lost.asked.size());
    }
}
