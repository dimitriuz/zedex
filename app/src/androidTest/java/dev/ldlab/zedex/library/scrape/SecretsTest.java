package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * That the build and the app still agree about how a secret is sealed.
 *
 * The one thing that can go wrong here is silent. {@code Secrets.SEASONING} is
 * a constant in Java that {@code app/build.gradle} reads out of the source
 * with a regular expression; if that ever stops matching - the constant
 * renamed, reformatted onto two lines, moved - the build still succeeds, the
 * APK still ships, and every scrape fails as though the credentials were
 * missing. Nothing logs anything a user would understand.
 *
 * So this asks the only question worth asking: does what the build wrote come
 * back out?
 */
@RunWith(AndroidJUnit4.class)
public class SecretsTest {

    private Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    private String sealedPassword() {
        return context().getString(R.string.screenscraper_password_sealed);
    }

    /**
     * What the build sealed, the app opens.
     *
     * Skipped rather than failed on a build with no credentials: that is the
     * ordinary state of a source clone, and a test that failed there would be
     * failing for everybody who is not the person with the account.
     */
    @Test
    public void whatTheBuildSealedTheAppOpens() {
        assumeTrue("this build has no scraper credentials to seal",
                   !sealedPassword().isEmpty());

        String opened = Secrets.reveal(context(), R.string.screenscraper_password_sealed);

        assertFalse("the seasoning in Secrets.java and the one build.gradle read"
                    + " out of it no longer agree", opened.isEmpty());

        // Proves it decrypted rather than handing the resource straight back,
        // which a reveal() that had quietly become a no-op would also pass the
        // assertion above with.
        assertNotEquals("reveal() returned the ciphertext", sealedPassword(), opened);
    }

    /** And the id beside it, which is sealed the same way with its own nonce. */
    @Test
    public void theidOpensTooAndIsNotTheSameCiphertext() {
        assumeTrue("this build has no scraper credentials to seal",
                   !sealedPassword().isEmpty());

        String id = context().getString(R.string.screenscraper_id_sealed);

        assertFalse(Secrets.reveal(context(), R.string.screenscraper_id_sealed).isEmpty());
        assertNotEquals("both fields sealed to the same bytes, so they share a"
                        + " nonce - see zedexSeal in app/build.gradle",
                        id, sealedPassword());
    }

    /**
     * And the whole point: {@code configured()} still answers yes.
     *
     * The seal sits directly under it - {@code configured} is
     * "are both non-empty", and both now arrive through a decryption that can
     * fail. A broken seal reads exactly like a build with no credentials,
     * which is the failure this pair of tests exists to make loud.
     */
    @Test
    public void theproviderStillKnowsItIsConfigured() {
        assumeTrue("this build has no scraper credentials to seal",
                   !sealedPassword().isEmpty());

        assertTrue(new ScreenScraper(context(), new Http.Real()).configured());
    }

    // --- failing closed ------------------------------------------------------------

    /** A build with nothing to seal has an empty resource, and that has to be
     *  "no credentials" rather than a crash on the first scrape. */
    @Test
    public void nothingSealedIsNotAnError() {
        assertEquals("", Secrets.reveal(""));
        assertEquals("", Secrets.reveal(null));
    }

    /**
     * Nor is rubbish. Every way this can fail - a truncated resource, a
     * changed seasoning, base64 that is not base64 - has to come back as
     * "absent" and never as an exception thrown out of a constructor.
     */
    @Test
    public void rubbishIsAbsentRatherThanAThrow() {
        assertEquals("", Secrets.reveal("not base64 at all !!"));
        assertEquals("", Secrets.reveal("c2hvcnQ="));
        assertEquals("", Secrets.reveal("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }

    /** A sealed value from a different seasoning does not open - which is the
     *  authentication half of GCM doing its job, and the reason a mismatched
     *  build fails closed rather than producing plausible nonsense. */
    @Test
    public void avalueSealedUnderAnotherKeyDoesNotOpen() {
        assumeTrue(!sealedPassword().isEmpty());

        // One character of the ciphertext moved: the tag no longer checks out.
        String sealed = sealedPassword();
        char first = sealed.charAt(0);
        String tampered = (first == 'A' ? 'B' : 'A') + sealed.substring(1);

        assertEquals("", Secrets.reveal(tampered));
    }
}
