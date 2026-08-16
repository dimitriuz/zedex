package dev.ldlab.zedex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.storage.Storage;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Display;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

/**
 * The display these tests can actually look at, said out loud.
 *
 * A bench with a second display - the emulator's own extra window, a real
 * panel, a desktop-mode screen - hands a launch to whichever display last had
 * the focus, and the task then stays there across launches. Everything a test
 * does with pixels or with a finger means the default display and only that
 * one: {@code UiDevice} screenshots come from it, and {@code UiDevice.click}
 * injects into it.
 *
 * The two halves are both needed. Asking is not enough on its own, because a
 * task that already exists somewhere else can be brought forward rather than
 * moved; and checking is not enough, because a bench nobody has touched is the
 * normal case and a test that only complains is a test that does not run.
 *
 * Nothing else notices. The accessibility tree spans every display, so a wait
 * on {@code By.desc(...)} finds its object whichever screen it is on and the
 * launch reads as a success - and then the taps land on another display's
 * launcher while the tree still answers about ours. That is what makes this
 * worth a class: it failed as a wrong border colour forty seconds later in one
 * suite, and as a filter that changed no rows in another, neither of them
 * saying anything about a display.
 *
 * Public because the suite has tests in sub-packages now - {@code
 * library.catalogue.CatalogueScreenTest} is the first - and package-private
 * stops at that boundary. The same rule the app's own layers already follow.
 */
public final class Screen {

    private Screen() {
    }

    /**
     * Launch options that put the activity where this test can see it. Pass
     * to {@code startActivity(intent, Screen.here())}.
     */
    public static Bundle here() {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        return options.toBundle();
    }

    /**
     * Turns every guide off before {@code LibraryActivity} starts, and marks
     * first run done.
     *
     * The same treatment {@code Emulator.launch()} gives the machine's own
     * guide, and for the same reason: a mark over the rail or the toolbar
     * swallows the very tap most of this suite's tests are about to make,
     * since {@code Coach} consumes every touch reaching it whatever it is
     * drawn over. Call before {@code startActivity}, not after - the
     * activity reads these on the way up.
     *
     * {@code Storage.KEY_SETUP_DONE} has to go with the guide flags now that
     * {@code LibraryActivity.onCreate} hands over to {@code WelcomeActivity}
     * whenever {@code Prefs#welcomeNeeded} says so - a fresh
     * {@code connectedDebugAndroidTest} install has
     * {@code firstInstallTime == lastUpdateTime}, which is exactly the case
     * that method answers true for, so every one of this class's callers
     * would otherwise land on the wizard instead of the library. Symmetric
     * with {@code Emulator.launch()}, which sets the same flag for the same
     * reason on the machine's side.
     *
     * {@code GuideTest} is the one class that wants a guide and turns the
     * guide flags back off for itself.
     */
    public static void suppressGuides(Context context) {
        SharedPreferences.Editor edit = context.getSharedPreferences(
                Prefs.PREFS, Context.MODE_PRIVATE).edit();
        edit.putBoolean(Storage.KEY_SETUP_DONE, true);
        for (String flag : Prefs.GUIDE_FLAGS) edit.putBoolean(flag, true);
        edit.commit();
    }

    /**
     * That the app did come up here. Call once the screen is up, since a
     * resumed activity is what this can ask about.
     */
    public static void assertHere() {
        int[] where = { Display.INVALID_DISPLAY };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                Display display = activity.getDisplay();
                if (display != null) {
                    where[0] = display.getDisplayId();
                }
            }
        });

        // Nothing of ours is resumed, which the registry can only mean one
        // way: something else is in front. A file picker left behind by
        // driving the device by hand is the usual one - it survives a
        // force-stop of the app, because it belongs to another package, and
        // the next launch comes up behind it.
        //
        // Worth failing here and saying so. Left to itself it surfaces much
        // later as a key that is not on the keyboard, and CLAUDE.md's own
        // advice - check what is in front before believing a measurement -
        // is only useful to somebody who already suspects it.
        assertNotEquals("nothing of this app is resumed, so something else is"
                        + " in front of it - a file picker left over from"
                        + " driving the device by hand survives am force-stop,"
                        + " since it belongs to another package. Clear it with"
                        + ": adb shell am force-stop"
                        + " com.google.android.documentsui",
                        Display.INVALID_DISPLAY, where[0]);

        assertEquals("the app came up on display " + where[0] + ", and the "
                     + "screenshots and taps this test makes go to display "
                     + Display.DEFAULT_DISPLAY + " - so its taps would land on "
                     + "the other screen while the accessibility tree still "
                     + "answered about this one. Take the extra display off the "
                     + "bench, or find out why the launch did not pin it",
                     Display.DEFAULT_DISPLAY, where[0]);
    }
}
