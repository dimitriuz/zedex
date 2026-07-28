package dev.ldlab.zedex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

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

    private final Emulator emulator = new Emulator();

    @Before
    public void setUp() {
        emulator.useDataFolder();
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());

        // The border is read at a fixed corner of the window, and portrait is
        // the arrangement whose picture starts there.
        emulator.portrait();
        show(true);
    }

    @After
    public void tearDown() {
        emulator.releaseOrientation();
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
        emulator.menu("Machine", "Reset", "Reset");
        emulator.idle(Emulator.BOOT);

        File tape = new File(emulator.context().getCacheDir(), "reporter.tap");
        try {
            REPORTER.writeTo(tape, "reporter");
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
