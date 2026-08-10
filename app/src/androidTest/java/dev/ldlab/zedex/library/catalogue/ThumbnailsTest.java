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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
     * <b>Two rows wanting the same picture make one request.</b>
     *
     * Not a nicety: the same cover appears in a search result and again in
     * whatever list it was reached from, and a grid scrolled past a row and
     * back asks again before the first answer has landed. One request per url
     * in flight is what keeps that from being two.
     */
    @Test
    public void thesameUrlAskedTwiceAtOnceIsOneRequest() throws Exception {
        OnePixel http = new OnePixel();
        CountDownLatch both = new CountDownLatch(2);

        Thumbnails.load(context, http, URL, (url, picture) -> both.countDown());
        Thumbnails.load(context, http, URL, (url, picture) -> both.countDown());

        assertEquals("both callers were not told", true,
                     both.await(10, TimeUnit.SECONDS));
        assertEquals("the same picture was fetched twice", 1, http.asked.size());
    }

    /** A url of nothing is not a request. Plenty of catalogue entries have no
     *  picture at all, and those rows are text rows. */
    @Test
    public void nourlIsNorequest() {
        OnePixel http = new OnePixel();

        Thumbnails.load(context, http, null, (url, picture) -> { });

        assertEquals(0, http.asked.size());
    }
}
