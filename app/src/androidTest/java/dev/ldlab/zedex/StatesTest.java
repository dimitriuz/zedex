package dev.ldlab.zedex;

import dev.ldlab.zedex.storage.States;
import dev.ldlab.zedex.storage.Storage;

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

/**
 * Save states: that one is really a snapshot of the machine, and that the
 * screen's own rename and delete reach the files.
 *
 * <b>The state has to be proved, not just counted.</b> A file of the right name
 * and a plausible size would pass a test that only looked at the folder, and
 * would pass just as well if the snapshot were of the wrong moment or of
 * nothing at all. So the machine is put into a state that can be seen from
 * outside — a border colour, which is the one thing a test can read back off
 * the emulated screen — the state is saved, the border is changed, and the
 * state is loaded. If the border comes back, the snapshot was real.
 *
 * The two hotkeys are covered the same way, through their rows in the menu:
 * they are what the quick pair does with no name asked for, and the naming rule
 * around them is the fiddly part.
 */
@RunWith(AndroidJUnit4.class)
public class StatesTest {

    private static final String NAME = "uitest";
    private static final String RENAMED = "uitest2";

    /** Fuse's own numbering: 1 is blue, 6 is yellow, and both are unmistakable. */
    private static final int BLUE = 1;
    private static final int YELLOW = 6;

    /** A BORDER takes effect on the next frame; this leaves room for several. */
    private static final long DRAWN = 2 * Emulator.SECOND;

    private final Emulator emulator = new Emulator();

    @Before
    public void setUp() {
        emulator.useDataFolder();
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());

        clear(NAME);
        clear(RENAMED);
    }

    @After
    public void tearDown() {
        clear(NAME);
        clear(RENAMED);
    }

    /**
     * Saving, loading, renaming and deleting, in the order a person does them.
     *
     * One test rather than four, because each step needs what the one before it
     * produced and saving a state costs a boot: four tests would boot the
     * machine four times to arrive at the same place.
     */
    @Test
    public void savesTheMachineAndLoadsItBack() {
        atBasic();

        border(BLUE);
        assertEquals("the border did not take", BLUE, emulator.borderColour());

        save(NAME);
        assertTrue("no snapshot written for " + NAME, snapshot(NAME).isFile());
        assertTrue("no thumbnail written for " + NAME,
                   States.thumbnailFor(emulator.context(), NAME).isFile());

        // Somewhere else entirely, so that coming back is visible.
        border(YELLOW);
        assertEquals("the border did not change", YELLOW, emulator.borderColour());

        emulator.menu("States", "Load state");
        emulator.tap(NAME);
        emulator.idle(DRAWN);

        assertEquals("loading the state did not restore the machine",
                     BLUE, emulator.borderColour());

        rename();
        delete();
    }

    /** The rename button on the card, which is named after the state it edits. */
    private void rename() {
        emulator.menu("States", "Load state");
        // The button is described as Rename “uitest”, quotes and all.
        emulator.tap("Rename");
        emulator.enterText(RENAMED);
        emulator.tap("OK");

        assertTrue("the renamed snapshot is missing", snapshot(RENAMED).isFile());
        assertFalse("the old snapshot is still there", snapshot(NAME).isFile());
        assertTrue("the thumbnail did not follow the name",
                   States.thumbnailFor(emulator.context(), RENAMED).isFile());
    }

    private void delete() {
        emulator.tap("Delete");
        emulator.tap("Delete");

        assertFalse("the snapshot was not deleted", snapshot(RENAMED).isFile());
        assertFalse("the thumbnail was left behind",
                    States.thumbnailFor(emulator.context(), RENAMED).isFile());
    }

    /**
     * The quick pair, which write and read one state without asking anything.
     *
     * Its name comes from whatever media is loaded, and nothing is loaded here,
     * so it is plain <i>Quick</i> — which is also the case the naming rule is
     * least likely to get right, since it is the one with no name to borrow.
     */
    @Test
    public void theQuickPairSavesAndLoadsWithoutAsking() {
        atBasic();
        clear("Quick");

        border(BLUE);
        emulator.menu("States", "Quick save");
        emulator.idle(DRAWN);

        assertTrue("quick save wrote no snapshot", snapshot("Quick").isFile());

        border(YELLOW);
        assertEquals(YELLOW, emulator.borderColour());

        emulator.menu("States", "Quick load");
        emulator.idle(DRAWN);

        assertEquals("quick load did not restore the machine",
                     BLUE, emulator.borderColour());

        clear("Quick");
    }

    // --- getting the machine somewhere a border can be set --------------------

    /**
     * A reset leaves a 128K at its menu, which has no BASIC prompt to type at.
     * Three down and Enter is 48 BASIC, which has.
     */
    private void atBasic() {
        emulator.menu("Machine", "Reset", "Reset");
        emulator.idle(3 * Emulator.SECOND);

        for (int i = 0; i < 3; i++) emulator.capsShift("6");
        emulator.key("ENTER");
        emulator.idle(2 * Emulator.SECOND);
    }

    /**
     * At the K cursor the first key of a line is a keyword, so B <em>is</em>
     * BORDER - typing the six letters gives "BORDER ORDER" and a syntax error.
     * After ENTER the cursor is back at K, so this works again unchanged.
     */
    private void border(int colour) {
        emulator.key("B");
        emulator.key(String.valueOf(colour));
        emulator.key("ENTER");
        emulator.idle(DRAWN);
    }

    private void save(String name) {
        emulator.menu("States", "Save state");
        emulator.tap("Add new snapshot");
        emulator.enterText(name);
        emulator.tap("OK");
        emulator.idle(DRAWN);
    }

    private File snapshot(String name) {
        return new File(States.directory(emulator.context()), name + ".szx");
    }

    /** Leaves nothing of a previous run behind, whatever format it was in. */
    private void clear(String name) {
        for (String format : States.FORMATS) {
            new File(States.directory(emulator.context()),
                     name + "." + format).delete();
        }
        States.thumbnailFor(emulator.context(), name).delete();
    }
}
