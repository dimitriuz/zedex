package dev.ldlab.zedex.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * What Back does on the panel of a two-screen handheld.
 *
 * <b>Why this is not driven through a real second screen.</b> It cannot be:
 * the only bench with a second display that has its own focus is the hardware
 * itself, and an {@code assumeTrue} on one would print {@code OK} having
 * asserted nothing on every emulator in CI - which is the exact failure this
 * project has already paid for twice. So what is asserted here is the panel's
 * own decision, which needs no window: that Back is consumed rather than
 * handed to the activity, where {@code Activity.onKeyUp}'s own default is to
 * finish, and that it reaches the handler its owner gave it. The other half -
 * that a press on the panel's navigation bar arrives here at all rather than
 * cancelling the dialog underneath - is a framework behaviour, measured by
 * hand on an AYN Thor Lite and written down in {@link SecondScreen}'s own
 * comments.
 */
@RunWith(AndroidJUnit4.class)
public class SecondScreenBackTest {

    /**
     * Built on the main thread and never shown. A {@code Dialog}'s
     * constructor wants a Looper, and showing one would want a display to put
     * a window on - neither of which the decision under test needs.
     *
     * The default display, because every device has one and this window never
     * reaches it. A panel is only ever built on a second one in the app
     * itself; see {@code Panels.free}.
     */
    private SecondScreen panelOnTheBench() {
        Context context = ApplicationProvider.getApplicationContext();
        Display display = context.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);

        SecondScreen[] built = new SecondScreen[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> built[0] = new SecondScreen(context, display, new View[0]));

        return built[0];
    }

    /**
     * Back is the panel's own, whoever else would have taken it.
     *
     * The whole of the fault: {@code dispatchKeyEvent} handed every key to the
     * host activity, and for the library that ends at {@code
     * Activity.onKeyUp}, whose answer to Back is to finish - so one press on
     * the panel's navigation bar took the app off both screens at once.
     * Consuming it here is what makes that impossible, and it is asserted
     * before the forwarding rather than after because returning true at the
     * top is the mechanism.
     */
    @Test
    public void backIsConsumedRatherThanHandedToTheActivity() {
        SecondScreen panel = panelOnTheBench();

        assertTrue("the panel let go of Back, and whatever catches it next"
                   + " has finishing the activity for a default",
                   panel.dispatchKeyEvent(back(KeyEvent.ACTION_DOWN)));
        assertTrue("the panel let go of Back",
                   panel.dispatchKeyEvent(back(KeyEvent.ACTION_UP)));
    }

    /** And it is answered by whatever the owner put there - the machine's own
     *  Back for the emulator's panel, the library's own minus its last step
     *  for the library's. */
    @Test
    public void backReachesTheHandlerTheOwnerGave() {
        SecondScreen panel = panelOnTheBench();

        AtomicInteger asked = new AtomicInteger();
        panel.setOnBack(asked::incrementAndGet);

        panel.backPressed();

        assertEquals("the owner's own answer to Back was not asked for",
                     1, asked.get());
    }

    /**
     * And a panel whose owner gave it none is quiet rather than broken.
     *
     * Both owners do set one, so this is about the order they set it in:
     * {@code show()} succeeds before either {@code setOnBack} runs, and a
     * press landing in that window is a press this has to survive.
     */
    @Test
    public void apanelWithNoHandlerSwallowsBackWithoutFalling() {
        SecondScreen panel = panelOnTheBench();

        panel.backPressed();

        assertTrue("Back stopped being consumed once there was nothing to do"
                   + " with it, which is the way out of the app again",
                   panel.dispatchKeyEvent(back(KeyEvent.ACTION_UP)));
    }

    private static KeyEvent back(int action) {
        return new KeyEvent(action, KeyEvent.KEYCODE_BACK);
    }
}
