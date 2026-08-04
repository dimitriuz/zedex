package dev.ldlab.zedex.feedback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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
