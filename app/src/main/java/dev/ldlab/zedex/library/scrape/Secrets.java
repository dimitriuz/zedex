package dev.ldlab.zedex.library.scrape;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The build-time credentials, not written down in the clear.
 *
 * <b>This is obfuscation and not secrecy, and the difference matters enough to
 * say first.</b> The key is derived from a constant a few lines below, which
 * ships in the same APK as the thing it unlocks, so anyone who reads this file
 * can decrypt what it protects. That is not a flaw in the scheme; it is the
 * property of every client-side secret there has ever been. An app that can
 * decrypt something on its own can be made to.
 *
 * What it does buy is the difference between
 *
 * <pre>    aapt2 dump resources app-release.apk | grep screenscraper</pre>
 *
 * printing the password in five seconds with a stock SDK tool, and somebody
 * having to find this class, understand it and run it. The first is something
 * a person does by accident while looking at something else; the second is a
 * deliberate act. Most of what gets scraped off published APKs is found by the
 * first kind, so raising the floor is worth the forty lines even though the
 * ceiling has not moved at all.
 *
 * The thing actually being defended is not confidentiality. Nobody wants a
 * ScreenScraper password for its own sake — the damage is the developer id
 * being abused and <em>banned</em>, which ends scraping for every install at
 * once. The real mitigation for that is a user's own account, which is why
 * there is one; see {@code Prefs.KEY_SCRAPER_USER}. This is the other, smaller
 * half.
 *
 * @see Scrapers
 */
public final class Secrets {

    private static final String TAG = "Zedex";

    /**
     * What the key is derived from.
     *
     * <b>Read out of this file by {@code app/build.gradle}</b>, with a regular
     * expression, so that the build and the app cannot disagree about it —
     * there is exactly one copy and this is it. Changing the string is
     * allowed; changing its <em>shape</em> means changing that regex too, and
     * the build fails loudly rather than shipping something that will not
     * decrypt.
     *
     * Being in the repository costs nothing. The credentials are not: they
     * come from {@code local.properties} or the environment and are gitignored
     * — so a clone gets the recipe and no ingredients, and only a built APK
     * has both.
     */
    private static final String SEASONING = "Zedex/scrape/v1";

    /** GCM's own preferred nonce length, and what the build writes. */
    private static final int IV_BYTES = 12;

    private static final int TAG_BITS = 128;

    private Secrets() {
    }

    /** The plaintext behind a sealed string resource, or "" when there is
     *  none — which is the ordinary state of a source build. */
    public static String reveal(Context context, int sealed) {
        return reveal(context.getString(sealed));
    }

    /**
     * Package-private twin, so the test can seal and unseal without a
     * resource in the way.
     *
     * Never throws and never logs the value. A build with no credentials has
     * an empty resource here, which has to come back as "" rather than as an
     * exception: {@code ScreenScraper.configured()} asks this question by
     * looking at the answer, and a source clone taking that path is the
     * common case, not an error.
     */
    static String reveal(String sealed) {
        if (sealed == null || sealed.isEmpty()) return "";

        try {
            byte[] all = Base64.decode(sealed, Base64.DEFAULT);
            if (all.length <= IV_BYTES) return "";

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                        new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));

            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES),
                              StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Deliberately without the exception and without the input: the
            // only ways here are an empty build and a build whose seasoning
            // moved, and neither is diagnosed by printing ciphertext into a
            // log every app on the device could once read.
            Log.w(TAG, "a sealed build-time value would not open; treating it as absent");
            return "";
        }
    }

    private static SecretKeySpec key() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        return new SecretKeySpec(
                sha.digest(SEASONING.getBytes(StandardCharsets.UTF_8)), "AES");
    }
}
