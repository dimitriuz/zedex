package dev.ldlab.zedex;

import dev.ldlab.zedex.storage.Prefs;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The guide: once each, and only once.
 *
 * Every other test in this suite turns the guide off in setUp - a mark over
 * the quick bar swallows the taps every other test is trying to make - so
 * this is the one class that arms it, and it puts the flags back afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class GuideTest {

    /** House style - see {@code WelcomeTest}'s own comment on this constant. */
    private static final long WAIT = 5000;

    private final Emulator emulator = new Emulator();

    private UiDevice device;
    private Context context;
    private SharedPreferences preferences;

    private final boolean[] were = new boolean[Prefs.GUIDE_FLAGS.length];

    @Before
    public void rememberTheFlags() {
        device = emulator.device();
        context = emulator.context();
        preferences = context.getSharedPreferences(
                Prefs.PREFS, Context.MODE_PRIVATE);

        for (int i = 0; i < Prefs.GUIDE_FLAGS.length; i++) {
            were[i] = preferences.getBoolean(Prefs.GUIDE_FLAGS[i], false);
        }
    }

    /** It is the user's device, and the setting they left on is the one they
     *  were using. */
    @After
    public void putTheFlagsBack() {
        SharedPreferences.Editor edit = preferences.edit();
        for (int i = 0; i < Prefs.GUIDE_FLAGS.length; i++) {
            edit.putBoolean(Prefs.GUIDE_FLAGS[i], were[i]);
        }
        edit.commit();
    }

    @Test
    public void theMachinesGuideRunsOnceAndNotTwice() {
        preferences.edit()
                .putBoolean(Prefs.KEY_GUIDE_MACHINE, false).apply();

        // Tour.arm runs from startEmulator, after machine.start() - which
        // never happens without ROMs, and then neither does the guide.
        emulator.useDataFolder();
        emulator.launchShowingGuides();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());

        String next = context.getString(R.string.guide_next);
        String done = context.getString(R.string.guide_done);

        assertNotNull("the guide never appeared",
                device.wait(Until.findObject(By.text(next)), WAIT));

        // Walk it to the end; the flag is set at the end and not the start,
        // so a guide abandoned half way is a guide that has not been given.
        // The machine's guide has four marks, and the last of them reads
        // "Got it" rather than "Next" - see Coach.show's own last parameter -
        // so both texts are looked for, or the walk stops one mark short and
        // never reaches the click that actually sets the flag.
        while (true) {
            UiObject2 button = device.findObject(By.text(next));
            if (button == null) button = device.findObject(By.text(done));
            if (button == null) break;

            button.click();
            device.waitForIdle();
        }

        assertTrue("the guide did not record itself as given",
                preferences.getBoolean(Prefs.KEY_GUIDE_MACHINE, false));

        // Round again. Not force-stop: instrumentation runs inside the app's
        // process, so stopping the app kills this test with it. A fresh launch
        // of the same activity is enough - the flag is what is being tested,
        // not the process.
        emulator.launchShowingGuides();

        assertNull("the guide came back",
                device.wait(Until.findObject(By.text(
                        context.getString(R.string.guide_next))), 2000));
    }
}
