package dev.ldlab.zedex;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.app.ActivityOptions;
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
 */
final class Screen {

    private Screen() {
    }

    /**
     * Launch options that put the activity where this test can see it. Pass
     * to {@code startActivity(intent, Screen.here())}.
     */
    static Bundle here() {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        return options.toBundle();
    }

    /**
     * That the app did come up here. Call once the screen is up, since a
     * resumed activity is what this can ask about.
     */
    static void assertHere() {
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

        // Only when it could be established. A resumed activity this could not
        // see is a question about the harness, and failing on it would trade a
        // clear diagnosis for a new flake.
        if (where[0] == Display.INVALID_DISPLAY) return;

        assertEquals("the app came up on display " + where[0] + ", and the "
                     + "screenshots and taps this test makes go to display "
                     + Display.DEFAULT_DISPLAY + " - so its taps would land on "
                     + "the other screen while the accessibility tree still "
                     + "answered about this one. Take the extra display off the "
                     + "bench, or find out why the launch did not pin it",
                     Display.DEFAULT_DISPLAY, where[0]);
    }
}
