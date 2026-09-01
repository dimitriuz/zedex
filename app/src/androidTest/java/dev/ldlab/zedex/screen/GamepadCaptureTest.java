package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.Screen;
import dev.ldlab.zedex.input.Hotkeys;
import dev.ldlab.zedex.input.PadMap;
import dev.ldlab.zedex.input.PadMaps;
import dev.ldlab.zedex.storage.Prefs;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiObjectNotFoundException;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Capturing a binding on {@link GamepadActivity} with the "press a button"
 * dialog actually up - the case every capture exists for, and the one the
 * activity's own {@code dispatchKeyEvent} and {@code onGenericMotionEvent}
 * never saw a press during, because a showing {@code AlertDialog} owns the
 * focused window. Android delivers key and motion events to the focused
 * window's callback - the dialog - not to the activity, so {@code B}
 * dismissed the dialog (its default {@code onKeyUp} cancels on back, which a
 * pad's B button falls back to) and {@code A} pressed the Cancel button
 * (consuming {@code DPAD_CENTER}, which A falls back to) before the
 * activity's own handler was ever asked. This class is the failing case made
 * reproducible: the fix moves the same handling onto the dialog itself
 * ({@code setOnKeyListener}, and {@code setOnGenericMotionListener} on its
 * decor view), and every test here fails against the code before that move.
 *
 * <b>Why a synthetic {@link KeyEvent} proves this without a controller.</b>
 * No bench this runs on has one plugged in - see {@code HotkeyTest}'s own
 * note - but {@link dev.ldlab.zedex.input.Gamepad#isFrom} only asks the
 * event's <i>source</i>, not which device sent it. A {@code KeyEvent} built
 * with the constructor that takes a source and injected with
 * {@code Instrumentation.sendKeySync} carries {@link InputDevice#SOURCE_GAMEPAD}
 * and is delivered exactly where a real press would be: to whichever window
 * has focus, dialog included. An event built any other way (or typed with
 * {@code adb shell input}) would arrive with the wrong source and prove
 * nothing about this bug.
 *
 * <b>Why a stored pad, not a connected one.</b> With nothing connected and
 * nothing stored, {@code GamepadActivity} opens with a null {@code
 * deviceKey}, and a capture would try to save a mapping under it. A real
 * press never hits that: it names its own device
 * ({@code GamepadActivity.adoptDevice}). The synthetic events here carry no
 * real device (device id -1, which resolves to no {@link InputDevice} at
 * all), so a pad already known to the screen - exactly the case a stored
 * mapping with nothing plugged in is for - is put there first.
 */
@RunWith(AndroidJUnit4.class)
public class GamepadCaptureTest {

    private static final long FIND = 10_000;

    private static final String DEVICE_KEY = "gamepad-capture-test-pad";
    private static final String DEVICE_NAME = "Test pad";

    private Context context;
    private UiDevice device;
    private SharedPreferences preferences;
    private Activity activity;

    private String bindingsBefore;
    private int modifierBefore;
    private String padMappingsBefore;
    private String languageBefore;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        preferences = context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        bindingsBefore = preferences.getString(Hotkeys.KEY_BINDINGS, null);
        modifierBefore = preferences.getInt(Hotkeys.KEY_MODIFIER, Integer.MIN_VALUE);
        padMappingsBefore = preferences.getString(Prefs.KEY_PAD_MAPPINGS, null);
        languageBefore = preferences.getString(Language.KEY_LANGUAGE, "");

        // No modifier, so a captured hotkey action reads back as the bare
        // button name rather than "Select + ..." - one fewer thing every
        // assertion below has to spell out. And the system's own language,
        // not whatever a bench was left on - android.R.string.cancel is read
        // through this test's own (unwrapped) application context, and it
        // has to name the same string GamepadActivity's dialog is showing.
        preferences.edit()
                .remove(Hotkeys.KEY_BINDINGS)
                .putInt(Hotkeys.KEY_MODIFIER, 0)
                .putString(Language.KEY_LANGUAGE, "")
                .apply();

        // A pad this screen already knows about - see the class comment on
        // why the synthetic events here need one waiting rather than a
        // connected InputDevice to adopt.
        PadMaps.save(preferences, DEVICE_KEY, DEVICE_NAME, PadMap.defaults());

        activity = launch();
    }

    @After
    public void tearDown() {
        if (activity != null) activity.finish();

        SharedPreferences.Editor edit = preferences.edit();

        if (bindingsBefore == null) edit.remove(Hotkeys.KEY_BINDINGS);
        else edit.putString(Hotkeys.KEY_BINDINGS, bindingsBefore);

        if (modifierBefore == Integer.MIN_VALUE) edit.remove(Hotkeys.KEY_MODIFIER);
        else edit.putInt(Hotkeys.KEY_MODIFIER, modifierBefore);

        if (padMappingsBefore == null) edit.remove(Prefs.KEY_PAD_MAPPINGS);
        else edit.putString(Prefs.KEY_PAD_MAPPINGS, padMappingsBefore);

        edit.putString(Language.KEY_LANGUAGE, languageBefore);

        edit.commit();

        // row() below can fling a UiScrollable, whose scrollIntoView begins
        // by scrolling to the beginning - a downward swipe near the top of
        // this screen's scrolling view, which Android can read as the
        // gesture that opens the notification shade (see WelcomeTest's own
        // tearDown for where this was first measured). The shade is
        // SystemUI's and outlives this class if left up, so put it back down
        // regardless of whether this run actually pulled it.
        try {
            device.executeShellCommand("cmd statusbar collapse");
        } catch (java.io.IOException e) {
            // Nothing this test can do about it, and nothing it should fail
            // for: the assertions have already run by here.
        }
    }

    private Activity launch() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                GamepadActivity.class.getName(), null, false);

        Screen.suppressFirstRun(context);
        context.startActivity(new Intent(context, GamepadActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), Screen.here());

        Activity launched = instrumentation.waitForMonitorWithTimeout(monitor, FIND);
        assertNotNull("the gamepad screen never came up", launched);

        assertNotNull("the gamepad screen never drew its rows",
                device.wait(Until.findObject(
                        By.textStartsWith(context.getString(R.string.gamepad_reset_pad))),
                        FIND));

        Screen.assertHere();
        return launched;
    }

    /**
     * The machine's section (a picker row, eight control rows, "Reset this
     * pad") plus "The app" heading and its explanatory paragraph sit ahead of
     * the hotkey rows, and on a tall enough screen that is more than one
     * screenful - the hotkey rows are below the fold and {@code
     * By.textStartsWith} alone, with no scrolling, cannot see them (measured
     * on a Realme RMX5061). So a first, unscrolled look is tried, and only if
     * that fails is a {@link UiScrollable} asked to bring the row into view
     * before looking again - this must find a genuine fact, not paper over a
     * missing row, so nothing here treats "still not found" as anything but
     * a failure.
     */
    private UiObject2 row(String startsWith) {
        UiObject2 found = device.wait(Until.findObject(By.textStartsWith(startsWith)), FIND);
        if (found == null) {
            try {
                UiScrollable scrollable = new UiScrollable(new UiSelector().scrollable(true));
                if (scrollable.exists()) {
                    scrollable.scrollIntoView(new UiSelector().textStartsWith(startsWith));
                }
            } catch (UiObjectNotFoundException e) {
                // Not there, or not scrollable; the assertion below reports it.
            }
            found = device.wait(Until.findObject(By.textStartsWith(startsWith)), FIND);
        }
        assertNotNull("no row starting with \"" + startsWith + "\" - scrolled and still did not find it",
                found);
        return found;
    }

    /** Taps a row and waits for the capture dialog's Cancel button - present
     *  whether or not the bench has a real controller. */
    private void openCapture(UiObject2 target) {
        target.click();

        assertNotNull("the capture dialog never opened",
                device.wait(Until.findObject(cancelButton()), FIND));
    }

    private void waitForCaptureToClose() {
        assertTrue("the capture dialog is still up - the press was not taken",
                device.wait(Until.gone(cancelButton()), FIND));
    }

    private BySelector cancelButton() {
        return By.text(context.getString(android.R.string.cancel));
    }

    private static KeyEvent padKey(int action, int keycode) {
        long now = SystemClock.uptimeMillis();
        return new KeyEvent(now, now, action, keycode, 0, 0, -1, 0, 0,
                            InputDevice.SOURCE_GAMEPAD);
    }

    /** A full press: a real one is down and then up, and the dialog closing
     *  on the down must not choke on the up that follows it. */
    private void press(int keycode) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.sendKeySync(padKey(KeyEvent.ACTION_DOWN, keycode));
        instrumentation.sendKeySync(padKey(KeyEvent.ACTION_UP, keycode));
    }

    @Test
    public void capturingAControlSlotBindsAnOrdinaryButton() {
        openCapture(row(context.getString(R.string.slot_fire)));

        press(KeyEvent.KEYCODE_BUTTON_X);
        waitForCaptureToClose();

        UiObject2 bound = row(context.getString(R.string.slot_fire));
        assertTrue("Fire was not rebound to X; its row reads \"" + bound.getText() + "\"",
                bound.getText().contains(KeyEvent.keyCodeToString(KeyEvent.KEYCODE_BUTTON_X)));
    }

    /**
     * The bug by name: B used to dismiss the dialog (Dialog.onKeyUp's own
     * default cancels on back, which B falls back to) rather than ever
     * reaching GamepadActivity.dispatchKeyEvent.
     */
    @Test
    public void capturingWithButtonBBindsBRatherThanDismissingTheDialog() {
        openCapture(row(context.getString(R.string.slot_fire)));

        press(KeyEvent.KEYCODE_BUTTON_B);
        waitForCaptureToClose();

        UiObject2 bound = row(context.getString(R.string.slot_fire));
        assertTrue("Fire was not rebound to B - the dialog was dismissed "
                      + "instead of capturing the press, which is the bug "
                      + "this test is for; row reads \"" + bound.getText() + "\"",
                bound.getText().contains(KeyEvent.keyCodeToString(KeyEvent.KEYCODE_BUTTON_B)));
    }

    /**
     * The other half: A used to activate whichever button the dialog's view
     * hierarchy had focused (Cancel, here) before GamepadActivity's own
     * dispatchKeyEvent was ever asked.
     */
    @Test
    public void capturingWithButtonABindsARatherThanPressingCancel() {
        openCapture(row(context.getString(R.string.slot_left)));

        press(KeyEvent.KEYCODE_BUTTON_A);
        waitForCaptureToClose();

        UiObject2 bound = row(context.getString(R.string.slot_left));
        assertTrue("Left was not rebound to A - Cancel took the press instead; "
                      + "row reads \"" + bound.getText() + "\"",
                bound.getText().contains(KeyEvent.keyCodeToString(KeyEvent.KEYCODE_BUTTON_A)));
    }

    /**
     * The hotkey rows go through the same dialog and were exactly as broken -
     * predating this branch, per {@code GamepadActivity}'s own history, and
     * never covered by a test either. This is that test.
     */
    @Test
    public void capturingAHotkeyActionBindsIt() {
        String title = context.getString(Hotkeys.Action.SCREENSHOT.title);
        openCapture(row(title));

        press(KeyEvent.KEYCODE_BUTTON_C);
        waitForCaptureToClose();

        UiObject2 bound = row(title);
        assertTrue("the Screenshot hotkey was not bound to C; row reads \""
                      + bound.getText() + "\"",
                bound.getText().contains(Hotkeys.buttonName(KeyEvent.KEYCODE_BUTTON_C)));
    }

    /**
     * The third shape this one dialog serves: the hotkey button itself, not
     * an action bound behind it.
     */
    @Test
    public void capturingTheHotkeyModifierBindsIt() {
        openCapture(row(context.getString(R.string.gamepad_hotkey)));

        press(KeyEvent.KEYCODE_BUTTON_C);
        waitForCaptureToClose();

        UiObject2 bound = row(context.getString(R.string.gamepad_hotkey));
        assertTrue("the hotkey modifier was not bound to C; row reads \""
                      + bound.getText() + "\"",
                bound.getText().contains(Hotkeys.buttonName(KeyEvent.KEYCODE_BUTTON_C)));
    }
}
