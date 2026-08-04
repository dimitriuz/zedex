package dev.ldlab.zedex.feedback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

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
                SettingsActivity.PREFS, Context.MODE_PRIVATE);

        preferences.edit()
                .putInt(SettingsActivity.KEY_JOYSTICK_TYPE, 1)
                .putBoolean(SettingsActivity.KEY_FULLSCREEN, true)
                .putString(SettingsActivity.KEY_MACHINE, "128")
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
                    .remove(SettingsActivity.KEY_JOYSTICK_TYPE)
                    .remove(SettingsActivity.KEY_FULLSCREEN)
                    .remove(SettingsActivity.KEY_MACHINE)
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
}
