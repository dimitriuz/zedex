package dev.ldlab.zedex.library.scrape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * The refusals, against the real service rather than a fixture.
 *
 * {@link ScreenScraperTest} maps HTTP 429 to a spent quota and 430/431 to a
 * thread limit, and every one of those replies was written from the
 * documentation - which proves the client handles what the docs describe and
 * nothing about whether that is what arrives. Those two paths are the whole
 * of how a multi-scrape decides between pausing and stopping, so being wrong
 * about them is being wrong about the part that matters.
 *
 * ScreenScraper's debug mode can force the counters, which makes them
 * reproducible on demand - {@code forcerequestok} for the daily allowance,
 * {@code forcerequestmin} for the per-minute one, {@code forcelevel} for the
 * account's thread allowance.
 *
 * <b>The debug password is not in this repository, the build, or the APK.</b>
 * It arrives as an instrumentation argument and lives on whoever's command
 * line ran it:
 *
 * <pre>
 * adb shell am instrument -w \
 *   -e class dev.ldlab.zedex.library.scrape.LiveRefusalsTest \
 *   -e ss_debug_password '&lt;the password&gt;' \
 *   dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 *
 * Without it every case here skips, so CI and anybody else's checkout are
 * unaffected. Debug mode is limited to a hundred uses a day; this spends four.
 */
@RunWith(AndroidJUnit4.class)
public class LiveRefusalsTest {

    private static final String TAG = "Zedex";

    /** A game the service certainly knows, so a refusal is the only reason a
     *  request can fail. */
    private static final String WELL_KNOWN = "Manic Miner.tzx";

    private static String debugPassword() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String given = arguments == null ? null : arguments.getString("ss_debug_password");
        return given == null ? "" : given.trim();
    }

    private ScreenScraper scraper(String forcing) {
        Context context = ApplicationProvider.getApplicationContext();

        ScreenScraper scraper = new ScreenScraper(
                new Http.Real(),
                // Through Secrets, because the resources hold ciphertext now
                // - see Secrets.java. The five-argument constructor is still
                // the one used here: this test needs debugWith, which the
                // context-taking ones do not expose.
                Secrets.reveal(context, dev.ldlab.zedex.R.string.screenscraper_id_sealed),
                Secrets.reveal(context, dev.ldlab.zedex.R.string.screenscraper_password_sealed),
                "", "");

        scraper.debugWith("&devdebugpassword=" + android.net.Uri.encode(debugPassword())
                          + forcing);
        return scraper;
    }

    private static Provider.Game aGame() {
        return new Provider.Game() {
            @Override public String path() { return "./" + WELL_KNOWN; }
            @Override public String filename() { return WELL_KNOWN; }
            @Override public long size() { return -1; }
            @Override public String md5() { return null; }
        };
    }

    /**
     * That debug mode is actually doing something, before anything is asserted
     * on the strength of it.
     *
     * A wrong password is not refused - ScreenScraper ignores the debug
     * parameters and answers the ordinary way - so without this check every
     * case below fails claiming the client mishandles a quota it was never
     * actually shown. That happened: the value in their documentation's own
     * example is not a working password, and the first run of this file
     * reported two mapping bugs that did not exist.
     *
     * The fact it turns on is observable and immediate: force the day's
     * counter to a number nothing else would produce and see whether the reply
     * says so. No timing, nothing to wait for - see CLAUDE.md on NewDiskTest,
     * and what deciding a skip from a wait once cost.
     */
    private void needWorkingDebugMode() {
        Context context = ApplicationProvider.getApplicationContext();
        assumeTrue("this build has no ScreenScraper developer account",
                   !context.getString(
                           dev.ldlab.zedex.R.string.screenscraper_id_sealed).isEmpty());
        assumeTrue("no -e ss_debug_password given, so the refusals cannot be forced",
                   !debugPassword().isEmpty());

        int marker = 4242;
        ScreenScraper probe = scraper("&forcerequestok=" + marker);

        try {
            probe.search(aGame());
        } catch (ScrapeException e) {
            // A refusal here is debug mode working; carry on and let the real
            // case say what kind it was.
            Log.w(TAG, "LIVE probe refused -> " + e.kind);
            return;
        }

        Log.w(TAG, "LIVE probe forcerequestok=" + marker
                   + " -> used=" + probe.quota().used);

        assumeTrue("the debug password is not being accepted - forcing the day's "
                   + "counter to " + marker + " left it at " + probe.quota().used
                   + ". ScreenScraper ignores debug parameters it does not like "
                   + "rather than refusing them, so nothing below can be checked. "
                   + "The DebugPassword is on your ScreenScraper developer page; "
                   + "the one in their documentation's example is not a live one.",
                   probe.quota().used == marker);
    }

    /**
     * A counter past the allowance is reported, and {@link Quota} reads it
     * that way - which is the thing a multi-scrape must actually pace itself
     * by.
     *
     * <b>ScreenScraper does not refuse.</b> Forcing the day's counter to ten
     * times the allowance and asking again answers perfectly normally, with a
     * candidate and a 200. Measured, not assumed: the first version of this
     * file expected a 429 and reported the client broken when none arrived.
     *
     * So the design that would have followed from the documentation - scrape
     * until refused, then stop - is wrong. What works is reading the numbers
     * that ride along with every reply and stopping before asking, which is
     * what {@link Quota#spent} is for.
     */
    @Test
    public void acounterPastTheAllowanceIsReportedRatherThanRefused() throws Exception {
        needWorkingDebugMode();

        ScreenScraper scraper = scraper("&forcerequestok=100000");
        List<Candidate> found = scraper.search(aGame());

        Quota quota = scraper.quota();
        Log.w(TAG, "LIVE past the allowance -> used=" + quota.used
                   + " allowed=" + quota.allowed + " left=" + quota.left()
                   + " spent=" + quota.spent()
                   + " candidates=" + found.size());

        assertEquals("the forced counter was not what came back", 100000, quota.used);
        assertTrue("the allowance should still be reported", quota.allowed > 0);

        assertEquals("nothing is left, and Quota should say so", 0, quota.left());
        assertTrue("Quota.spent is what a multi-scrape stops on, and it said there "
                   + "was room", quota.spent());
    }

    /**
     * The per-minute counter behaves the same way.
     *
     * Recorded rather than asserted as a refusal, for the same reason: it is
     * not one. Both counters are advisory as far as a forced value goes, and
     * the client reads them rather than waiting to be told.
     */
    @Test
    public void theperMinuteCounterIsAlsoReportedRatherThanRefused() throws Exception {
        needWorkingDebugMode();

        ScreenScraper scraper = scraper("&forcerequestmin=100000");

        try {
            scraper.search(aGame());
            Log.w(TAG, "LIVE per-minute forced -> answered, used=" + scraper.quota().used);
        } catch (ScrapeException e) {
            // If it ever does start refusing, this is the shape to expect -
            // and the client should call it something that clears by itself.
            Log.w(TAG, "LIVE per-minute forced -> " + e.kind);
            assertTrue("a rate refusal should be one of the two that clear by "
                       + "themselves, not " + e.kind, e.worthWaiting());
        }
    }

    /**
     * Forcing the account's level does not change the threads reported.
     *
     * Recorded because a multi-scrape would have sized its queue by this.
     * {@code forcelevel=30} left {@code maxthreads} at one, so either the
     * level does not drive it or this account's ceiling is one regardless -
     * and either way a queue built on the reported number is right to stay
     * serial.
     */
    @Test
    public void theforcedLevelDoesNotRaiseTheThreadsReported() throws Exception {
        needWorkingDebugMode();

        ScreenScraper scraper = scraper("&forcelevel=30");
        scraper.search(aGame());

        Quota quota = scraper.quota();
        Log.w(TAG, "LIVE forcelevel=30 -> threads=" + quota.threads);

        assertTrue("threads should always be at least one", quota.threads >= 1);
    }

    /**
     * A wrong debug password is ignored rather than refused.
     *
     * The reason {@link #needWorkingDebugMode} probes rather than trusting
     * that a password was supplied: an unaccepted one produces an ordinary,
     * successful answer, so every case here would fail claiming the client
     * mishandles a refusal it was never shown. That is exactly what happened
     * on the first run of this file.
     */
    @Test
    public void awrongDebugPasswordIsIgnoredRatherThanRefused() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        assumeTrue("this build has no ScreenScraper developer account",
                   !context.getString(
                           dev.ldlab.zedex.R.string.screenscraper_id_sealed).isEmpty());

        ScreenScraper scraper = new ScreenScraper(
                new Http.Real(),
                // Through Secrets, because the resources hold ciphertext now
                // - see Secrets.java. The five-argument constructor is still
                // the one used here: this test needs debugWith, which the
                // context-taking ones do not expose.
                Secrets.reveal(context, dev.ldlab.zedex.R.string.screenscraper_id_sealed),
                Secrets.reveal(context, dev.ldlab.zedex.R.string.screenscraper_password_sealed),
                "", "");
        scraper.debugWith("&devdebugpassword=definitely-not-it&forcerequestok=4242");

        scraper.search(aGame());

        Log.w(TAG, "LIVE wrong debug password -> used=" + scraper.quota().used);
        assertTrue("a wrong debug password appeared to force the counter, which would "
                   + "make the probe in needWorkingDebugMode meaningless",
                   scraper.quota().used != 4242);
    }
}
