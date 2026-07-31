package dev.ldlab.zedex;

import dev.ldlab.zedex.storage.Storage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A disk made from nothing, formatted and written to by the machine itself,
 * and saved as a file that is really a TR-DOS image.
 *
 * This is the whole disk story end to end, and it is the one that broke: a
 * new disk from {@code disk_new()} has geometry but no filesystem, so saving
 * it before the machine has formatted it wrote a silent zero byte file.
 *
 * Needs a Scorpion — its ROMs are not redistributable, so the test skips
 * rather than fails when they are not in the ROM folder.
 */
@RunWith(AndroidJUnit4.class)
public class NewDiskTest {

    /** A formatted double-sided 80 track TR-DOS disk is exactly this big. */
    private static final long TRD_SIZE = 655360;

    /** Where TR-DOS keeps the disk's own description. */
    private static final int INFO_SECTOR = 0x800;
    private static final int FILE_COUNT = INFO_SECTOR + 0xe4;
    private static final int TRDOS_ID = INFO_SECTOR + 0xe7;
    private static final int LABEL = INFO_SECTOR + 0xf5;

    private static final String DISK_LABEL = "test";
    private static final String FILE_NAME = "hi";
    private static final String SAVED_AS = "uitest";

    /** Formatting eighty tracks takes the machine a while. */
    private static final long FORMATTING = 120 * Emulator.SECOND;

    private final Emulator emulator = new Emulator();

    private File written;

    @Before
    public void setUp() {
        emulator.useDataFolder();
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());

        written = new File(Storage.disksDirectory(emulator.context()),
                           SAVED_AS + ".trd");
        assertTrue("cannot clear " + written, !written.exists() || written.delete());
    }

    @Test
    public void formatsANewDiskAndSavesWhatTheMachineWroteOnIt() throws IOException {
        selectScorpion();

        // A drive that was empty, given a disk that does not exist anywhere.
        emulator.menu("Media", "Beta Disk A:", "New disk");
        emulator.tapIfPresent("New disk");        // only asked when replacing

        emulator.idle(3 * Emulator.SECOND);   // let the toast go, it says the same

        emulator.menu("Media");
        assertTrue("the drive should hold a blank disk",
                   emulator.isShowing("Blank disk"));
        emulator.closeMenu();

        bootTo48Basic();
        enterAProgram();
        enterTrDos();
        format(DISK_LABEL);
        save(FILE_NAME);

        saveTheDiskAs(SAVED_AS);

        byte[] image = Files.readAllBytes(written.toPath());

        assertEquals("not a whole TR-DOS image", TRD_SIZE, image.length);
        assertEquals("TR-DOS signature", 0x10, image[TRDOS_ID] & 0xff);
        assertEquals("one file on the disk", 1, image[FILE_COUNT] & 0xff);
        assertEquals("the disk's label", DISK_LABEL, text(image, LABEL, 8));
        assertEquals("the file the machine saved", FILE_NAME, text(image, 0, 8));
        assertEquals("saved as a BASIC program", 'B', (char) image[8]);
    }

    /**
     * The Beta 128 interface, and so TR-DOS, comes with the machine rather
     * than being a setting; a Scorpion has it.
     */
    private void selectScorpion() {
        emulator.menu("Machine", "Change machine", "Scorpion");
        emulator.idle(3 * Emulator.SECOND);

        emulator.menu("Media");
        boolean beta = emulator.isShowing("Beta Disk A:");
        emulator.closeMenu();

        assumeTrue("the Scorpion ROMs are missing, so it fell back to 48K", beta);
    }

    /**
     * The Scorpion's boot menu, fourth entry down. 48 BASIC because the
     * tokens the disk commands need are only reachable there.
     */
    private void bootTo48Basic() {
        emulator.menu("Machine", "Reset", "Reset");
        emulator.idle(Emulator.BOOT);

        emulator.capsShift("6");
        emulator.capsShift("6");
        emulator.capsShift("6");
        emulator.key("ENTER");
        emulator.idle(3 * Emulator.SECOND);
    }

    /**
     * Something for TR-DOS to save, since SAVE writes what is in memory.
     *
     * Typed rather than handed over on a tape, which is how {@link
     * JoystickTest} gets its program: opening a tape autoloads it, and
     * autoloading is Fuse typing LOAD "" through its phantom typist at a
     * moment of its own choosing. That is fine on a machine sitting at a
     * BASIC prompt and not worth the risk on one that has just been walked
     * through a Scorpion's boot menu. Eight keys is not the slow part of this
     * test; formatting eighty tracks is.
     */
    private void enterAProgram() {
        emulator.type("10 ");
        emulator.key("E");            // REM, at the keyword cursor
        emulator.type("hello");
        emulator.key("ENTER");
    }

    /** RANDOMIZE USR 15616, the way in to TR-DOS. */
    private void enterTrDos() {
        emulator.key("T");            // RANDOMIZE
        emulator.extendedMode();
        emulator.key("L");            // USR
        emulator.type("15616");
        emulator.key("ENTER");
        emulator.idle(5 * Emulator.SECOND);
    }

    /** FORMAT is a token on the 0 key, not a word that can be spelled. */
    private void format(String label) {
        emulator.extendedMode();
        emulator.symbolShift("0");
        emulator.type(" \"" + label + "\"");
        emulator.key("ENTER");
        emulator.idle(FORMATTING);
    }

    private void save(String name) {
        emulator.key("S");            // SAVE
        emulator.type("\"" + name + "\"");
        emulator.key("ENTER");
        emulator.idle(5 * Emulator.SECOND);
    }

    /**
     * Saving is a sheet page and not a dialog, so the button that commits it is
     * the page's own "Save as…" and not an OK. This tapped OK for a while after
     * the dialogs became pages, and said "nothing on screen says OK".
     */
    private void saveTheDiskAs(String name) {
        emulator.menu("Media", "Beta Disk A:", "Save as");
        emulator.enterText(name);
        emulator.tap("Save as");
        emulator.idle(3 * Emulator.SECOND);
    }

    /** TR-DOS pads its names with spaces. */
    private static String text(byte[] image, int offset, int length) {
        return new String(image, offset, length, StandardCharsets.US_ASCII).trim();
    }
}
