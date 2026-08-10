package dev.ldlab.zedex.input;

import dev.ldlab.zedex.FuseNative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

/**
 * Reading a scraped key layout into a profile this app can play with.
 *
 * ScreenScraper's {@code sp2kcfg} is Recalbox's {@code p2k.cfg}: lines of
 * {@code 0:a = v ;; jump} saying which keyboard key each pad button should
 * send, hand-authored per game. It is the only scraped field that changes how
 * a game <em>plays</em>, and the reason the setup dialog can offer the
 * keyboard at all - "the profile's keys" is a useless answer when nothing
 * knows which keys.
 *
 * On the JVM because none of it is Android but the keycodes, which are
 * constants. The sample below is a real one.
 */
public class KeymapTest {

    /** As recorded from ScreenScraper - see {@code ScreenScraperTest}. */
    private static final String BATMAN =
            "# Batman\n0:left = q ;; left\n0:a = v ;; jump";

    private static ControlProfiles.Profile read(String name, String keymap) {
        return Keymap.profile(name, keymap);
    }

    /** The directions and the fire button, which is what a layout is for. */
    @Test
    public void thefourDirectionsAndFireAreRead() {
        ControlProfiles.Profile profile = read("Chuckie Egg",
                "0:left = o\n0:right = p\n0:up = q\n0:down = a\n0:a = space");

        assertEquals(KeyEvent.KEYCODE_O, profile.keys[FuseNative.JOYSTICK_LEFT]);
        assertEquals(KeyEvent.KEYCODE_P, profile.keys[FuseNative.JOYSTICK_RIGHT]);
        assertEquals(KeyEvent.KEYCODE_Q, profile.keys[FuseNative.JOYSTICK_UP]);
        assertEquals(KeyEvent.KEYCODE_A, profile.keys[FuseNative.JOYSTICK_DOWN]);
        assertEquals(KeyEvent.KEYCODE_SPACE, profile.keys[FuseNative.JOYSTICK_FIRE]);
    }

    /**
     * What a line is not: a comment, a heading, or the whitespace round it.
     *
     * The file is hand-authored, so every line of it has been typed by
     * somebody with their own habits about spacing.
     */
    @Test
    public void commentsAndSpacingAreNotPartOfIt() {
        ControlProfiles.Profile profile = read("Batman", BATMAN);

        assertEquals("the ;; comment was read as part of the key",
                     KeyEvent.KEYCODE_Q, profile.keys[FuseNative.JOYSTICK_LEFT]);
        assertEquals(KeyEvent.KEYCODE_V, profile.keys[FuseNative.JOYSTICK_FIRE]);

        assertEquals(read("x", "   0:left=q   ").keys[FuseNative.JOYSTICK_LEFT],
                     read("x", "0:left = q").keys[FuseNative.JOYSTICK_LEFT]);
    }

    /**
     * A slot the layout says nothing about keeps what it had.
     *
     * The same rule the built-in layouts follow - "the five the layout names;
     * the buttons stay as they are" - because a game that wants keys for the
     * stick still wants Enter to start.
     */
    @Test
    public void whatThelayoutDoesNotNameIsLeftAlone() {
        ControlProfiles.Profile profile = read("Batman", BATMAN);

        assertEquals(ControlProfiles.QAOPM[FuseNative.JOYSTICK_RIGHT],
                     profile.keys[FuseNative.JOYSTICK_RIGHT]);
        assertEquals(ControlProfiles.QAOPM[ControlProfiles.BUTTON_1],
                     profile.keys[ControlProfiles.BUTTON_1]);
        assertEquals(ControlProfiles.QAOPM[ControlProfiles.BUTTON_3],
                     profile.keys[ControlProfiles.BUTTON_3]);
    }

    /** The named keys a Spectrum has, by the names Recalbox writes them
     *  under. CAPS SHIFT and SYMBOL SHIFT are the two shifts. */
    @Test
    public void thenamedKeysAreUnderstood() {
        assertEquals(KeyEvent.KEYCODE_ENTER,
                     read("x", "0:a = enter").keys[FuseNative.JOYSTICK_FIRE]);
        assertEquals(KeyEvent.KEYCODE_SPACE,
                     read("x", "0:a = space").keys[FuseNative.JOYSTICK_FIRE]);
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT,
                     read("x", "0:a = leftshift").keys[FuseNative.JOYSTICK_FIRE]);
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT,
                     read("x", "0:a = rightctrl").keys[FuseNative.JOYSTICK_FIRE]);
        assertEquals(KeyEvent.KEYCODE_7,
                     read("x", "0:a = 7").keys[FuseNative.JOYSTICK_FIRE]);
    }

    /**
     * Player two is somebody else's pad, and there is only one here.
     *
     * A layout can carry both, and reading them into one profile would let
     * player two's keys overwrite player one's - silently, and only for the
     * games whose author bothered to write both.
     */
    @Test
    public void onlyThefirstPlayersHalfIsRead() {
        ControlProfiles.Profile profile = read("Two Up",
                "0:left = o\n1:left = z\n1:a = m");

        assertEquals(KeyEvent.KEYCODE_O, profile.keys[FuseNative.JOYSTICK_LEFT]);
        assertEquals("player two's fire became player one's",
                     ControlProfiles.QAOPM[FuseNative.JOYSTICK_FIRE],
                     profile.keys[FuseNative.JOYSTICK_FIRE]);
    }

    /** Fire is A where there is an A, and the next button along where there
     *  is not - a layout naming only B still has a fire button. */
    @Test
    public void fireIsAorTheNextButtonAlong() {
        assertEquals(KeyEvent.KEYCODE_V,
                     read("x", "0:b = v").keys[FuseNative.JOYSTICK_FIRE]);
        assertEquals("A beats B when both are named",
                     KeyEvent.KEYCODE_M,
                     read("x", "0:b = v\n0:a = m").keys[FuseNative.JOYSTICK_FIRE]);
    }

    /** Start and Select are the two buttons beside fire; the third is left
     *  alone, because nothing in a p2k file corresponds to it. */
    @Test
    public void startAndSelectBecomeTheTwoSpareButtons() {
        ControlProfiles.Profile profile = read("x", "0:start = 1\n0:select = 0");

        assertEquals(KeyEvent.KEYCODE_1, profile.keys[ControlProfiles.BUTTON_1]);
        assertEquals(KeyEvent.KEYCODE_0, profile.keys[ControlProfiles.BUTTON_2]);
        assertEquals(ControlProfiles.QAOPM[ControlProfiles.BUTTON_3],
                     profile.keys[ControlProfiles.BUTTON_3]);
    }

    /**
     * A layout with nothing in it this can use is no layout.
     *
     * Null rather than a profile of defaults: a profile named after the game
     * whose keys are simply QAOPM would claim the game had been set up when
     * nothing had been read at all, and the dialog decides whether to offer
     * the keyboard by asking exactly this.
     */
    @Test
    public void alayoutThatSaysNothingUsableIsNoLayout() {
        assertNull(read("x", null));
        assertNull(read("x", ""));
        assertNull("only comments", read("x", "# Batman\n;; nothing here"));
        assertNull("a pad button this app does not have",
                   read("x", "0:l2 = k"));
        assertNull("a key this app cannot name",
                   read("x", "0:a = kp4"));
    }

    @Test
    public void readableAnswersTheSameQuestionWithoutBuildingOne() {
        assertTrue(Keymap.readable(BATMAN));
        assertFalse(Keymap.readable("# nothing"));
        assertFalse(Keymap.readable(null));
    }

    /** Named after the game, since that is what the profile list will show. */
    @Test
    public void theprofileIsNamedAfterTheGame() {
        assertEquals("Batman", read("Batman", BATMAN).name);
    }
}
