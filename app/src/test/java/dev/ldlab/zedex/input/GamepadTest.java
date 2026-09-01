package dev.ldlab.zedex.input;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.InputDevice;

import org.junit.Test;

/**
 * What of {@link Gamepad} can actually be asserted on the JVM tier, and an
 * honest account of what cannot.
 *
 * <b>{@link Gamepad#key(android.view.KeyEvent)} and {@link
 * Gamepad#motion(android.view.MotionEvent)} cannot be exercised here at
 * all</b>, which is the answer to why this class did not exist before now
 * despite {@link Gamepad.Maps} being built, in the design's own words, "so a
 * test supplies a fake". The seam is real and would work; what defeats it is
 * one layer further out, and it was measured rather than assumed:
 *
 * <ul>
 * <li>The unit-test tier substitutes a different {@code android.jar} than
 * the one this app compiles against - AGP's own "mockable" jar - and every
 * method of every framework class in it is replaced with one that returns a
 * hardcoded default (0, false, null), unconditionally, no matter what a
 * constructor was given. Measured directly: a {@code KeyEvent} built with a
 * real source, keycode and device id reports {@code getSource() == 0},
 * {@code getKeyCode() == 0} and {@code getDeviceId() == 0} - none of them 0
 * in the constructor call. Without those three answering honestly, {@code
 * Gamepad.key()} cannot even tell whether an event came from a pad, let
 * alone which button it names.
 * <li>The obvious workaround - subclass {@code KeyEvent} and override just
 * the handful of getters {@code Gamepad} reads - does not compile: those
 * methods are {@code final} on the real SDK jar this app is compiled
 * against (confirmed with {@code javap}), and only lose {@code final} on
 * the mockable jar substituted at test <i>runtime</i>, specifically so a
 * bytecode-based mocking framework can override them there. javac sees the
 * real jar and refuses the override before the mockable one ever enters the
 * picture.
 * <li>This project has no such framework - no Mockito, no Robolectric - in
 * {@code testImplementation}. Adding one would fix this properly, but is a
 * dependency and build-configuration decision bigger than this test, so it
 * is flagged here rather than made unilaterally.
 * </ul>
 *
 * A worker method or two more of {@code Gamepad} still hits the same wall,
 * even if the event problem above were solved: {@link Hotkeys.Bindings}
 * keeps its button-to-action table in a {@code SparseArray}, and that
 * class's methods are answered with the same kind of unconditional default -
 * measured the same way, a value {@code put()} into one comes back {@code
 * null} from {@code get()} every time. So {@code Hotkeys.Bindings.forButton}
 * cannot be made to return a bound action on this tier, and {@link
 * Gamepad.Actions#run} can never be exercised as "a bound action ran".
 *
 * <b>What is left, and is real:</b> {@link Gamepad#isPad}, which needs no
 * event at all - it is a pure function over the {@code int} sources mask
 * every {@code InputDevice} reports, and it is what {@code key()}, {@code
 * motion()}, {@code connected()} and {@code isFrom()} all defer to, and what
 * {@code GamepadActivity} and {@code Diagnostics} call directly (see its own
 * javadoc). It is measured here against the actual mixed source masks from
 * the design's own device survey - a pad that is also a keyboard, and a
 * keyboard that is not a pad - rather than against invented ones.
 */
public class GamepadTest {

    /**
     * The GameSir-Cyclone Pro's own pad endpoint, measured in the design doc
     * as {@code KEYBOARD | GAMEPAD | JOYSTICK | BATTERY | EXTERNAL} - a pad
     * that is also, at the same time, a keyboard source. isPad has to say
     * yes anyway: the two other bits do not cancel the two that matter.
     */
    @Test
    public void aDeviceThatIsAlsoAKeyboardIsStillAPad() {
        int sources = InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_GAMEPAD
                | InputDevice.SOURCE_JOYSTICK;

        assertTrue(Gamepad.isPad(sources));
    }

    /** Either bit alone is enough - a pad that reports only one of the two. */
    @Test
    public void eitherGamepadOrJoystickAloneIsAPad() {
        assertTrue(Gamepad.isPad(InputDevice.SOURCE_GAMEPAD));
        assertTrue(Gamepad.isPad(InputDevice.SOURCE_JOYSTICK));
    }

    /**
     * The same physical pad's <i>other</i> two endpoints, measured in the
     * design doc as {@code KEYBOARD | BATTERY | EXTERNAL} and {@code
     * KEYBOARD | ALPHAKEY | BATTERY | EXTERNAL} - a keyboard source with
     * neither a gamepad nor a joystick bit. {@code BATTERY}, {@code
     * ALPHAKEY} and {@code EXTERNAL} are device capability flags dumpsys
     * shows beside the source mask, not source bits themselves, so {@code
     * SOURCE_KEYBOARD} alone is what the mask actually was. Reading this as
     * a pad is exactly the bug the design's own device key measurement
     * exists to avoid: three input devices from one physical pad sharing a
     * vendor, product and address, only one of which should ever be treated
     * as the pad.
     */
    @Test
    public void aKeyboardEndpointWithNeitherBitIsNotAPad() {
        assertFalse(Gamepad.isPad(InputDevice.SOURCE_KEYBOARD));
    }

    /** Nothing reported at all is not a pad either. */
    @Test
    public void noSourcesAtAllIsNotAPad() {
        assertFalse(Gamepad.isPad(0));
    }
}
