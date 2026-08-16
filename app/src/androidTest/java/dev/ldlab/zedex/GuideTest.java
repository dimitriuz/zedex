package dev.ldlab.zedex;

import dev.ldlab.zedex.library.catalogue.Catalogues;
import dev.ldlab.zedex.screen.LibraryActivity;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
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
        //
        // Bounded at the mark count plus one: an uncapped loop turns a Coach
        // that fails to advance into a hang rather than a failure, and a hang
        // gives no clue which mark it stuck on.
        final int marks = 4; // buildMachineTour's own four .mark() calls.
        for (int tap = 0; tap <= marks; tap++) {
            UiObject2 button = device.findObject(By.text(next));
            if (button == null) button = device.findObject(By.text(done));
            if (button == null) break;

            button.click();
            device.waitForIdle();
        }

        if (device.findObject(By.text(next)) != null
                || device.findObject(By.text(done)) != null) {
            fail("the guide is still showing \"" + next + "\" or \"" + done
                    + "\" after " + (marks + 1) + " taps - it never reached the end");
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

    /**
     * Arms one guide, opens the library on the tab it belongs to, walks it to
     * the end, and answers whether the flag was set.
     *
     * EXTRA_FROM_MENU on both: it asks for the library whatever the start-in-
     * library setting says, which is exactly why every other library test uses
     * it too. Without it a bench with the setting off hands straight over to
     * the machine and the test measures the wrong screen.
     *
     * @param marks how many marks this tour has - {@code buildBrowseTour} and
     *              {@code buildCatalogueTour} both name their own count, the
     *              same way {@code theMachinesGuideRunsOnceAndNotTwice} does
     *              above. Bounds the walk, exactly as that one does and for
     *              the same reason: an unbounded loop turns a guide that
     *              fails to advance into a hang, and a bare "Next" search
     *              stops one mark short of the last, which reads "Got it".
     */
    private void walkTheGuide(String flag, String extra, int marks) {
        // Same reason as Screen.suppressGuides: a fresh connectedDebugAndroidTest
        // install has firstInstallTime == lastUpdateTime, so Prefs.welcomeNeeded
        // would otherwise send this launch to WelcomeActivity instead of
        // LibraryActivity - EXTRA_FROM_MENU only skips the startsInLibrary
        // gate below it, not this one.
        preferences.edit()
                .putBoolean(flag, false)
                .putBoolean(Storage.KEY_SETUP_DONE, true)
                .commit();

        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        if (extra != null) intent.putExtra(extra, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent, Screen.here());

        String next = context.getString(R.string.guide_next);
        String done = context.getString(R.string.guide_done);

        assertNotNull("the guide never appeared",
                device.wait(Until.findObject(By.text(next)), WAIT));

        // The accessibility tree spans every display, so a launch that
        // landed on the wrong one still finds "Next" through it - and every
        // click below would then land on display 0 instead, with nothing in
        // the eventual failure to say a display was ever the problem. Every
        // other library launcher in this suite checks this; this one hadn't.
        Screen.assertHere();

        for (int tap = 0; tap <= marks; tap++) {
            UiObject2 button = device.findObject(By.text(next));
            if (button == null) button = device.findObject(By.text(done));
            if (button == null) break;

            button.click();
            device.waitForIdle();
        }

        if (device.findObject(By.text(next)) != null
                || device.findObject(By.text(done)) != null) {
            fail("the guide is still showing \"" + next + "\" or \"" + done
                    + "\" after " + (marks + 1) + " taps - it never reached the end");
        }
    }

    @Test
    public void theLibrarysGuideIsGivenOnBrowse() {
        // No content-folder assumeTrue here: neither of browseTour's four
        // targets - the rail, the toolbar, the list's own frame, the pane -
        // needs one. All four are built unconditionally in onCreate; the
        // "choose a folder" view is shown *inside* the list's frame rather
        // than replacing it, and the pane shows a placeholder with nothing
        // selected. A guard copied from FilterTest/DetailPaneTest, where it
        // is load-bearing because those tests go looking for real games,
        // would only have skipped this test silently on the standard Gradle
        // route - connectedDebugAndroidTest wipes the SAF grant, and only a
        // human with a picker can restore it.
        walkTheGuide(Prefs.KEY_GUIDE_LIBRARY, null, 4); // buildBrowseTour's own four .mark() calls.

        assertTrue("the library guide did not record itself as given",
                   preferences.getBoolean(Prefs.KEY_GUIDE_LIBRARY, false));
    }

    @Test
    public void theArchivesGuideIsGivenOnTheCatalogueTab() {
        // Kept, unlike the content-folder guard above: catalogueTour's own
        // targets are only built when Catalogues.any() is - see
        // LibraryActivity.buildPage - so a build with no catalogue truly has
        // no shelf to land on, and wantsCatalogue() refuses the extra outright.
        assumeTrue("no catalogue in this build", Catalogues.any(context));

        walkTheGuide(Prefs.KEY_GUIDE_CATALOGUE,
                     LibraryActivity.EXTRA_OPEN_CATALOGUE,
                     2); // buildCatalogueTour's own two .mark() calls.

        assertTrue("the archive guide did not record itself as given",
                   preferences.getBoolean(Prefs.KEY_GUIDE_CATALOGUE, false));
    }
}
