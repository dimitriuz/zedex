package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.R;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * One real request to ScreenScraper, to check the shape everything else is
 * built on.
 *
 * Everything in {@link ScreenScraperTest} is offline against replies written
 * by hand from the documentation, which proves the client handles that shape -
 * and proves nothing at all about whether it is the shape the service sends.
 * This is the only thing that can, and it is deliberately one request: their
 * daily allowance is the scarce thing, and a suite that spent it would be
 * worse than no suite.
 *
 * Skipped when the build has no developer account, which is every source
 * download and every CI run - see {@code app/build.gradle}. It is not part of
 * what a change has to pass; it is a thing to run by hand when the client
 * changes or when ScreenScraper does.
 */
@RunWith(AndroidJUnit4.class)
public class LiveScreenScraperTest {

    private static final String TAG = "Zedex";

    /** A game ScreenScraper certainly knows, searched by name rather than by
     *  hash - a hash would depend on which dump this bench happens to have. */
    private static final String WELL_KNOWN = "Manic Miner.tzx";

    @Test
    public void screenScraperAnswersInTheShapeTheClientExpects() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();

        ScreenScraper scraper = new ScreenScraper(context, new Http.Real());
        assumeTrue("this build has no ScreenScraper developer account",
                   scraper.configured());

        List<Candidate> found = scraper.search(new Provider.Game() {
            @Override public String path() { return "./" + WELL_KNOWN; }
            @Override public String filename() { return WELL_KNOWN; }
            @Override public long size() { return -1; }
            @Override public String md5() { return null; }
        });

        Quota quota = scraper.quota();
        Log.w(TAG, "LIVE quota used=" + quota.used + " allowed=" + quota.allowed
                   + " threads=" + quota.threads);

        assertTrue("ScreenScraper answered but said nothing about the quota, so a "
                   + "multi-scrape would have nothing to pace itself by",
                   quota.allowed > 0 || quota.used >= 0);

        assertTrue("no candidate for " + WELL_KNOWN, !found.isEmpty());

        Candidate one = found.get(0);
        Log.w(TAG, "LIVE candidate " + one.describe());
        assertNotNull(one.name);

        Provider.Scraped got = scraper.fetch(one, new Provider.Wanted(true, true, true));
        Log.w(TAG, "LIVE meta name=" + got.meta.name
                   + " dev=" + got.meta.developer
                   + " pub=" + got.meta.publisher
                   + " genre=" + got.meta.genre
                   + " released=" + got.meta.released
                   + " rating=" + got.meta.rating
                   + " players=" + got.meta.players);

        for (Medium medium : got.media) {
            Log.w(TAG, "LIVE medium " + medium.folder + " ." + medium.extension
                       + (medium.md5 == null ? " (no hash)" : " hashed"));
        }

        assertNotNull("the name did not survive the parse", got.meta.name);
    }
}
