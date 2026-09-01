package dev.ldlab.zedex.feedback;

import dev.ldlab.zedex.storage.Prefs;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import dev.ldlab.zedex.input.PadMap;
import dev.ldlab.zedex.input.PadMaps;
import dev.ldlab.zedex.screen.SettingsActivity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * That a report still says everything it is supposed to.
 *
 * A bug report is only worth the questions it saves, and every line of it was
 * added because somebody had to ask for that fact by hand. A refactor that
 * quietly drops one costs nothing at build time and one round trip per report
 * afterwards, so the keys are pinned here.
 *
 * It also checks the report holds nothing it should not, which is the half a
 * reviewer of the privacy policy would care about.
 */
@RunWith(AndroidJUnit4.class)
public class DiagnosticsTest {

    private String report() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return Diagnostics.report(context);
    }

    /**
     * A report must survive whatever type a preference is actually stored as.
     *
     * This is the bug that shipped in 1.1.0. Settings are not all strings -
     * joystickType is written with putInt in three places - and the report read
     * every one of them with getString, which throws ClassCastException. It went
     * unnoticed because a preference nobody has changed is absent, and getString
     * on an absent key returns the default rather than throwing: it crashed on
     * the first phone that had ever changed its joystick interface, and only
     * when asking for a report, which is the worst moment for the app to die.
     */
    @Test
    public void survivesAPreferenceThatIsNotAString() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences(
                Prefs.PREFS, Context.MODE_PRIVATE);

        preferences.edit()
                .putInt(Prefs.KEY_JOYSTICK_TYPE, 1)
                .putBoolean(Prefs.KEY_FULLSCREEN, true)
                .putString(Prefs.KEY_MACHINE, "128")
                .commit();

        try {
            String report = Diagnostics.report(context);

            assertTrue("the int did not reach the report:\n" + report,
                       report.contains("joystick=1"));
            assertTrue("the boolean did not:\n" + report,
                       report.contains("fullscreen=true"));
            assertTrue("the string did not:\n" + report,
                       report.contains("machine=128"));
        } finally {
            preferences.edit()
                    .remove(Prefs.KEY_JOYSTICK_TYPE)
                    .remove(Prefs.KEY_FULLSCREEN)
                    .remove(Prefs.KEY_MACHINE)
                    .commit();
        }
    }

    @Test
    public void saysEverythingWorthAsking() {
        String report = report();

        for (String key : new String[] {
                "app=", "build=", "installer=",
                "android=", "device=", "abi=", "screen=",
                "data=", "roms=", "allFiles=",
                "machine=", "filter=", "scale=", "keyboard=", "joystick=",
                "fullscreen=", "secondScreen=", "controllers=" }) {
            assertTrue("a report with no " + key + ":\n" + report,
                       report.contains(key));
        }
    }

    /** One fact a line, so two reports diff and a person can read it. */
    @Test
    public void isOneFactALine() {
        for (String line : report().split("\n")) {
            if (line.isEmpty()) continue;
            assertTrue("not key=value: " + line, line.indexOf('=') > 0);
        }
    }

    /**
     * Nothing in here identifies a person or a device beyond its model.
     *
     * Not a proof - it cannot be - but it does catch the obvious mistake of
     * someone adding an id to make grouping easier one day.
     */
    @Test
    public void carriesNoIdentifier() {
        String report = report().toLowerCase();

        for (String forbidden : new String[] {
                "android_id", "imei", "serial", "advertising", "mac=" }) {
            assertFalse("a report containing " + forbidden + ":\n" + report,
                        report.contains(forbidden));
        }

        // An address, and not merely an at sign: the screen line says "@420dpi",
        // which is what the first version of this test tripped over.
        assertFalse("a report containing an email address:\n" + report,
                    java.util.regex.Pattern
                        .compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}")
                        .matcher(report).find());
    }

    /**
     * The one identifier this feature could plausibly leak, and the test
     * above cannot catch it.
     *
     * {@code carriesNoIdentifier()} greps for {@code mac=}, {@code serial},
     * {@code imei} and the like - none of which would match a raw
     * {@code InputDevice} descriptor, which is exactly what a pad's mapping
     * is stored keyed by (see {@link PadMaps#keyFor} and CLAUDE.md: "the
     * descriptor is not proven harmless"). {@link Diagnostics#report} is
     * meant to use the key only to look a mapping up, and print the pad's
     * name and its mapping - never the key itself. This builds a store with
     * a known, descriptor-shaped key and asserts the actual thing: the key
     * is absent from the report while the pad's own name is present.
     */
    @Test
    public void carriesNoDeviceKey() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences =
                context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        String previous = preferences.getString(Prefs.KEY_PAD_MAPPINGS, null);

        // Shaped like the real thing - Android's own getDescriptor() is a
        // 40-character hex string - and distinct enough that it could only
        // have reached the report from here.
        String deviceKey = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
        String padName = "Zedex Test Pad";

        try {
            PadMaps.save(preferences, deviceKey, padName, PadMap.defaults());

            String report = Diagnostics.report(context);

            assertFalse("a report containing the device key:\n" + report,
                        report.contains(deviceKey));
            assertTrue("a report with no name for the pad it does carry:\n" + report,
                       report.contains(padName));
        } finally {
            if (previous == null) {
                preferences.edit().remove(Prefs.KEY_PAD_MAPPINGS).commit();
            } else {
                preferences.edit().putString(Prefs.KEY_PAD_MAPPINGS, previous).commit();
            }
        }
    }

    /**
     * And no scraper login, which is the one secret this app now stores.
     *
     * A bug report is written to be read by the user and sent by hand to a
     * stranger, so a password in it is a password given away - and the whole
     * design is that they can read it first, which only helps if the thing
     * they would object to is visible rather than buried among forty settings.
     *
     * Safe today by construction: {@code Diagnostics.settings} names the keys
     * it prints one at a time rather than walking {@code getAll}. That is
     * exactly the kind of safety that lasts until somebody makes it a loop,
     * which is what this test is for. It sets a value that could not occur by
     * accident and asks whether it came out the other end.
     */
    @Test
    public void carriesNoScraperLogin() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences =
                context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        String user = preferences.getString(Prefs.KEY_SCRAPER_USER, null);
        String password = preferences.getString(Prefs.KEY_SCRAPER_PASSWORD, null);

        try {
            preferences.edit()
                    .putString(Prefs.KEY_SCRAPER_USER, "zedex-test-username")
                    .putString(Prefs.KEY_SCRAPER_PASSWORD, "zedex-test-passphrase")
                    .commit();

            String report = report();

            assertFalse("a report containing the scraper username:\n" + report,
                        report.contains("zedex-test-username"));
            assertFalse("a report containing the scraper password:\n" + report,
                        report.contains("zedex-test-passphrase"));
        } finally {
            // Somebody's real login, if they have one - putting it back
            // matters more here than in most of these.
            preferences.edit()
                    .putString(Prefs.KEY_SCRAPER_USER, user)
                    .putString(Prefs.KEY_SCRAPER_PASSWORD, password)
                    .commit();
        }
    }
}
