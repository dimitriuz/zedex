package dev.ldlab.zedex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.ldlab.zedex.input.ControlProfiles;
import dev.ldlab.zedex.storage.Prefs;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The on-screen joystick, from a finger to the emulated machine and back.
 *
 * The pad is two circles drawn on a canvas, so — like the keyboard — each of
 * its five controls is published as a virtual accessibility node, and that is
 * how a test finds one. Nothing here knows a coordinate.
 *
 * What the machine received cannot be read directly: the emulated screen is a
 * GL surface with no view structure. So the machine is asked to say it in the
 * one thing a screenshot can be trusted to show — the colour of the border. A
 * tape built by {@link TapeProgram} carries a loop that turns each Kempston
 * bit into a different colour and then leaves it there, so the press and the
 * screenshot do not have to happen at the same moment.
 */
@RunWith(AndroidJUnit4.class)
public class JoystickTest {

    /** What the fixture paints for each control; see {@link #REPORTER}. */
    private static final int NOTHING = 0;        // black
    private static final int RIGHT = 1;          // blue
    private static final int LEFT = 2;           // red
    private static final int DOWN = 3;           // magenta
    private static final int UP = 4;             // green
    private static final int FIRE = 5;           // cyan

    /**
     * Reads the Kempston port and paints the border to say what it saw.
     *
     * It only paints when it saw something, so the answer is latched: a press
     * that has already been let go of is still on the screen a second later.
     */
    private static final TapeProgram REPORTER = new TapeProgram()
            .line(10, "BORDER 0")
            .line(20, "LET k=IN 31")
            .line(30, "IF k>0 THEN BORDER (k=1)+2*(k=2)+3*(k=4)+4*(k=8)+5*(k=16)")
            .line(40, "GO TO 20")
            .startingAt(10);

    /**
     * The same trick for keys rather than a port: which of the profile's keys
     * the machine is seeing, in the border.
     *
     * A Keyboard joystick sends keys, so the Kempston port has nothing to say
     * about it and {@code INKEY$} is what does. Latched the same way, and the
     * three it watches are three different colours - Q is the profile's up, M
     * its fire, and a space is what Button 2 sends.
     *
     * It compares codes with the case folded rather than strings, because the
     * 128's editor leaves CAPS LOCK on and {@code INKEY$} says so: the first
     * version looked for "q" and never saw one.
     */
    private static final TapeProgram KEY_REPORTER = new TapeProgram()
            .line(10, "BORDER 0")
            .line(20, "LET c=CODE INKEY$")
            .line(30, "IF c>96 THEN LET c=c-32")
            .line(40, "IF c=81 THEN BORDER 4")
            .line(50, "IF c=77 THEN BORDER 5")
            .line(60, "IF c=32 THEN BORDER 1")
            .line(70, "GO TO 20")
            .startingAt(10);

    private final Emulator emulator = new Emulator();

    /** The profile that was in use before this test picked its own. */
    private int theirProfile;

    @Before
    public void setUp() {
        emulator.useDataFolder();
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());

        // The border is read at a fixed corner of the window, and portrait is
        // the arrangement whose picture starts there.
        emulator.portrait();
        show(true);
        useQaopm();
    }

    @After
    public void tearDown() {
        emulator.releaseOrientation();
        preferences().edit()
                .putInt(ControlProfiles.KEY_CURRENT, theirProfile).commit();
    }

    /**
     * QAOPM, because that is the profile this class asserts against.
     *
     * It used to read whichever profile the bench happened to be on, and got
     * away with it while every profile was one of the built-in ones and QAOPM
     * was first. The setup dialog changed that: applying a scraped keyboard
     * layout adds a profile named after the game and <b>selects it</b>, so a
     * bench where anybody has ever done that answers "up is the profile's Q"
     * with whatever that game used - here, a K. A test sets the world it needs.
     */
    private void useQaopm() {
        SharedPreferences preferences = preferences();
        theirProfile = preferences.getInt(ControlProfiles.KEY_CURRENT, 0);

        List<ControlProfiles.Profile> profiles = ControlProfiles.all(preferences);

        for (int at = 0; at < profiles.size(); at++) {
            if (!ControlProfiles.QAOPM_NAME.equals(profiles.get(at).name)) continue;

            preferences.edit().putInt(ControlProfiles.KEY_CURRENT, at).commit();
            return;
        }

        // Nothing called QAOPM: put one back rather than testing against a
        // list somebody has renamed out from under this.
        profiles.add(new ControlProfiles.Profile(ControlProfiles.QAOPM_NAME,
                                                 ControlProfiles.QAOPM));
        ControlProfiles.store(preferences, profiles, profiles.size() - 1);
    }

    private SharedPreferences preferences() {
        return emulator.context().getSharedPreferences(
                Prefs.PREFS, android.content.Context.MODE_PRIVATE);
    }

    @Test
    public void everyControlReachesTheMachineAsItsOwnKempstonBit() {
        chooseType("Kempston");
        runTheReporter();

        assertEquals("an untouched joystick should read as nothing",
                     NOTHING, emulator.borderColour());

        assertEquals("right", RIGHT, whatTheMachineSaw("JOYSTICK RIGHT"));
        assertEquals("left", LEFT, whatTheMachineSaw("JOYSTICK LEFT"));
        assertEquals("up", UP, whatTheMachineSaw("JOYSTICK UP"));
        assertEquals("down", DOWN, whatTheMachineSaw("JOYSTICK DOWN"));
        assertEquals("fire", FIRE, whatTheMachineSaw("JOYSTICK FIRE"));
    }

    /**
     * Choosing the interface is the whole of what the type menu does, and the
     * proof is that another one stops reaching the Kempston port: Cursor is
     * key presses inside Fuse, so port 31 never hears about it.
     */
    @Test
    public void anotherInterfaceDoesNotReachTheKempstonPort() {
        chooseType("Cursor");
        runTheReporter();

        emulator.hold("JOYSTICK RIGHT");
        assertEquals(NOTHING, emulator.borderColour());

        chooseType("Kempston");
        assertEquals("and switching back reaches it again",
                     RIGHT, whatTheMachineSaw("JOYSTICK RIGHT"));
    }

    @Test
    public void hidingTakesTheControlsAwayAndShowingBringsThemBack() {
        assertTrue("the pad should be on screen", emulator.isShowing("JOYSTICK UP"));

        show(false);
        assertFalse("a hidden joystick must leave no nodes behind",
                    emulator.isShowing("JOYSTICK UP"));
        assertFalse(emulator.isShowing("JOYSTICK FIRE"));

        show(true);
        assertTrue("the pad should be back", emulator.isShowing("JOYSTICK UP"));
        assertTrue(emulator.isShowing("JOYSTICK FIRE"));
    }

    @Test
    public void theTypesAreFusesOwnAndTheChoiceSticks() {
        chooseType("Sinclair 2");

        emulator.menu("Controls", "Joystick");
        assertTrue("the menu should say which interface is in use",
                   emulator.isShowing("Sinclair 2"));
        emulator.closeMenu();
    }

    /**
     * Keyboard is not one of Fuse's interfaces: the pad sends the current
     * profile's keys, and the three buttons beside fire send theirs whatever the
     * type is. Both are checked through a program that watches the keyboard,
     * since a key press is invisible at the Kempston port by definition.
     */
    @Test
    public void theKeyboardTypeSendsKeysAndSoDoTheButtons() {
        chooseType("Keyboard");
        runTheProgram(KEY_REPORTER, "keys.tap", "keys");

        assertEquals("an untouched joystick should type nothing",
                     NOTHING, emulator.borderColour());

        assertEquals("up is the profile's Q", UP, whatTheMachineSaw("JOYSTICK UP"));
        assertEquals("fire is its M", FIRE, whatTheMachineSaw("JOYSTICK FIRE"));
        assertEquals("and a button sends its own key",
                     RIGHT, whatTheMachineSaw("BUTTON SPACE"));
    }

    /** Presses a control, then reads what the machine made of it. */
    private int whatTheMachineSaw(String control) {
        emulator.hold(control);
        return emulator.borderColour();
    }

    /**
     * A fresh machine with the reporter loaded and running. The tape carries
     * an autostart line, so there is nothing to type.
     */
    private void runTheReporter() {
        runTheProgram(REPORTER, "reporter.tap", "reporter");
    }

    private void runTheProgram(TapeProgram program, String file, String name) {
        emulator.menu("Machine", "Reset", "Reset");
        emulator.idle(Emulator.BOOT);

        File tape = new File(emulator.context().getCacheDir(), file);
        try {
            program.writeTo(tape, name);
        } catch (IOException e) {
            throw new AssertionError("could not write " + tape, e);
        }

        emulator.open(tape);
    }

    /** Idempotent: the menu offers whichever of the two is not already so. */
    private void show(boolean shown) {
        String wanted = shown ? "Show on screen" : "Hide from screen";

        emulator.menu("Controls", "Joystick");
        if (emulator.isShowing(wanted)) emulator.tap(wanted);
        else emulator.closeMenu();
        emulator.idle(Emulator.SECOND);
    }

    private void chooseType(String name) {
        emulator.menu("Controls", "Joystick", "Type:", name);
        emulator.idle(Emulator.SECOND);
    }
}
