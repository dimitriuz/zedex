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
 * Fuse's own tape recorder - {@code tape_record_start()} / {@code tape_record_stop()} -
 * reached through the bridge's command queue.
 *
 * <p>Recorder state lives on the emulation thread and Java reads a copy of it
 * published once a frame, so every expectation here is a condition waited for
 * and not a duration slept: {@code tapeRecording()} can lag the command by a
 * frame, and the block a stop appends reaches the browser's list through
 * {@code androidstate_publish()} as well.
 *
 * <p>Fuse's two record functions do not guard themselves; the bridge does. Of
 * the two guards, this class can pin only the stop one: with nothing recording,
 * {@code tape_record_stop()} writes a length byte into a NULL sampling buffer,
 * which is a SIGSEGV - measured, by removing the guard and watching the run die
 * in that function. A double start is not observable through this API - it
 * leaks the sampling buffer and roughly doubles each pulse count inside the
 * block, and {@code tapeBlocks()} shows neither - so {@link
 * #startTwiceThenStopAppendsOneBlock} pins only that a double start appends no
 * second block and does not crash. The start guard is kept for the part that
 * cannot be seen from here.
 *
 * <p>Instrumentation rather than JVM: the questions are about what the real
 * Fuse core does, and it has to be running to answer them.
 */
@RunWith(AndroidJUnit4.class)
public class TapeRecordTest {

    /** How long to wait for the published flag or the block list to catch up. */
    private static final long TIMEOUT = 2 * Emulator.SECOND;

    private static final long POLL = 50;

    private final Emulator emulator = new Emulator();

    @Before
    public void setUp() {
        emulator.useDataFolder();
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());

        // A run killed mid-recording leaves the bench recording, and every
        // later test would then start a second recording on top of it.
        stopAndForget();
    }

    @After
    public void stopRecordingAndCleanUp() {
        stopAndForget();

        // The round trip leaves its tape and its recording in the cache; a
        // killed run never reaches here, and MediaStore-style leftovers make
        // the next one read a stale file as a fresh one, so delete on the
        // way out what both directions of the run write.
        for (String name : new String[]{"saver.tap", "recorded.tzx"}) {
            File file = new File(emulator.context().getCacheDir(), name);
            if (file.isFile()) file.delete();
        }
    }

    /** Asks for a stop and waits for the published flag to say so. */
    private void stopAndForget() {
        FuseNative.tapeRecord(false);
        awaitRecording(false);
    }

    /**
     * Every test starts from a tape with nothing on it: {@code newTape()} is
     * queued like everything else, so the empty list is waited for rather than
     * assumed - and if it never empties, an appended block could not be counted.
     */
    private void startFromAnEmptyTape() {
        FuseNative.newTape();
        awaitBlockCount(0);
        assertEquals("newTape() left blocks on the tape", 0, blockCount());
    }

    /** {@code tapeBlocks()} may answer null for no tape; that is zero blocks. */
    private int blockCount() {
        String[] blocks = FuseNative.tapeBlocks();
        return blocks == null ? 0 : blocks.length;
    }

    /** Polls until the deck says it is recording, or not, however asked. */
    private boolean awaitRecording(boolean expected) {
        for (long waited = 0; waited < TIMEOUT; waited += POLL) {
            if (FuseNative.tapeRecording() == expected) return true;
            emulator.idle(POLL);
        }
        return FuseNative.tapeRecording() == expected;
    }

    /** Polls until the browser's list holds {@code expected} blocks. */
    private void awaitBlockCount(int expected) {
        for (long waited = 0; waited < TIMEOUT && blockCount() != expected;
             waited += POLL) {
            emulator.idle(POLL);
        }
    }

    /**
     * The round trip: start, sample for a second, stop - and the tape has one
     * block on it, which is what *Save tape…* then writes out.
     */
    @Test
    public void recordingAppendsOneBlock() {
        startFromAnEmptyTape();

        FuseNative.tapeRecord(true);
        assertTrue("tapeRecording() never said true after a start",
                   awaitRecording(true));

        emulator.idle(1000);

        FuseNative.tapeRecord(false);
        assertTrue("tapeRecording() never said false after a stop",
                   awaitRecording(false));

        awaitBlockCount(1);
        assertEquals("a recorded second did not append exactly one block",
                     1, blockCount());
        assertTrue("hasTape() says there is nothing on a tape with a block "
                   + "recorded onto it", FuseNative.hasTape());
    }

    /**
     * What a double start can be made to answer from Java: no second block, and
     * no crash. Only {@code tape_record_stop()} ever appends a block, and it
     * removes every sampling event, so the second start's damage - the leaked
     * buffer, the pulse counts counted twice over - never reaches {@code
     * tapeBlocks()}. The guard in the bridge is what stops that damage
     * happening; this only proves it is not fatal.
     */
    @Test
    public void startTwiceThenStopAppendsOneBlock() {
        startFromAnEmptyTape();

        FuseNative.tapeRecord(true);
        assertTrue("the first start never took", awaitRecording(true));
        emulator.idle(200);

        FuseNative.tapeRecord(true);
        emulator.idle(200);

        FuseNative.tapeRecord(false);
        assertTrue("tapeRecording() never said false after a stop",
                   awaitRecording(false));

        awaitBlockCount(1);
        assertEquals("a second start while already recording appended a block",
                     1, blockCount());

        emulator.idle(500);
        assertEquals("a second sampling event appended a block late",
                     1, blockCount());
    }

    /**
     * And the other way round: Fuse's {@code tape_record_stop()} writes the
     * last length byte into the sampling buffer and appends whatever is in it,
     * whether or not anything was ever recorded. With no buffer that is a write
     * to NULL - so the crash is the bug and the extra block only the milder
     * symptom of the same missing check.
     */
    @Test
    public void stopWithoutStartAppendsNothing() {
        startFromAnEmptyTape();

        FuseNative.tapeRecord(false);
        emulator.idle(500);

        assertEquals("a stop while not recording appended a block",
                     0, blockCount());
        assertFalse("tapeRecording() says true and nothing started it",
                    FuseNative.tapeRecording());
    }

    /**
     * What the sheet does while a recording runs: the Tape section is
     * <em>only</em> <em>Stop recording</em>. Load, New tape, Play and
     * <em>Save tape…</em> during a recording would mutate or write out the
     * very tape the recording is about to be appended to, so they are not
     * merely hidden — there is nothing there to tap.
     *
     * <p>The block to hang the ordinary rows on is recorded natively, the
     * way {@link #recordingAppendsOneBlock} does it: the point of this test
     * is the sheet, and the recorder is already pinned elsewhere.
     */
    @Test
    public void recordingHidesTheOtherTapeRows() {
        // A sibling that died with the sheet open would leave its rows for
        // find() to trip over here; start from a closed drawer either way.
        emulator.closeMenu();

        startFromAnEmptyTape();

        FuseNative.tapeRecord(true);
        assertTrue("the block to hang the rows on never started recording",
                   awaitRecording(true));
        emulator.idle(1000);
        FuseNative.tapeRecord(false);
        assertTrue("the block to hang the rows on never stopped recording",
                   awaitRecording(false));
        awaitBlockCount(1);
        assertEquals("the block to hang the rows on never appeared",
                     1, blockCount());

        emulator.menu("Media");
        assertTrue("a tape with a block on it does not offer Save tape…",
                   emulator.isInMenu("Save tape"));
        assertTrue("a tape with a block on it does not offer Rewind",
                   emulator.isInMenu("Rewind"));

        emulator.tap("Record");
        assertTrue("tapping Record never started a recording",
                   awaitRecording(true));

        emulator.menu("Media");
        assertTrue("while recording the sheet does not offer Stop recording",
                   emulator.isInMenu("Stop recording"));
        for (String row : new String[]{"Load a tape", "New tape", "Rewind",
                                       "Blocks", "Save tape"}) {
            assertFalse("while recording the sheet still offers " + row,
                        emulator.isInMenu(row));
        }

        emulator.tap("Stop recording");
        assertTrue("tapping Stop recording never stopped the recording",
                   awaitRecording(false));

        emulator.menu("Media");
        assertTrue("Record is not offered again once the stop is done",
                   emulator.isInMenu("Record"));
        assertTrue("Save tape… is not offered again once the stop is done",
                   emulator.isInMenu("Save tape"));
        emulator.closeMenu();
    }

    /**
     * The whole reason for the feature, as a round trip: a real ROM save,
     * recorded off the ULA's tape-out pin while the save trap stands aside
     * (tape.c refuses to trap a save while {@code tape_recording}), written
     * out as a file, and loaded back. The program the machine saves sets
     * the border green when it runs, so green after a reset and a load of
     * the written file can only mean the recording carried a program that
     * {@code LOAD ""} could read and 128 BASIC could auto-start.
     *
     * <p>The file is a <b>.tzx</b>, not a .tap: libspectrum's .tap writer
     * has nothing to say about an RLE pulse block, and a recorded block
     * is exactly that.
     *
     * <p>Bound comments measured on the bench: the save itself is the
     * ROM's own pilot tone and two blocks — a little over two seconds of
     * real time — and the load back is the same tape played for real,
     * seconds. The bounds are minutes because a loaded host is slower
     * than the emulator, not because the Spectrum is.
     */
    @Test
    public void aRecordedSaveLoadsBack() throws IOException {
        emulator.menu("Machine", "Reset", "Reset");
        emulator.idle(Emulator.BOOT);

        // The deck goes empty before the saver tape goes in, and that is
        // load-bearing rather than tidy: a sibling's recording is a
        // *modified* tape, and opening a file over one makes Fuse ask
        // whether to save it - which blocks the emulation thread until
        // somebody answers (see FuseNative.onConfirmSave). startFromAnEmptyTape
        // is the only way in that cannot stall, because the bridge clears
        // tape_modified before it closes.
        startFromAnEmptyTape();

        File saver = new File(emulator.context().getCacheDir(), "saver.tap");
        new TapeProgram()
                .line(10, "BORDER 4: STOP")
                // LINE 10 is what makes the load back run: LOAD ""
                // auto-starts a program whose header names a line.
                .line(20, "SAVE \"rec\" LINE 10")
                .startingAt(20)
                .writeTo(saver, "saver");

        // Autoloads, runs line 20, and waits at the ROM's own
        // “Start tape, then press any key”.
        emulator.open(saver);

        // The deck goes empty now, so the one block counted after the stop
        // can only be the recording.
        startFromAnEmptyTape();

        emulator.menu("Media", "Record");
        assertTrue("Record from the sheet never started the recorder",
                   awaitRecording(true));
        emulator.closeMenu();
        emulator.key("ENTER");

        // The save: the ROM stripes the border red/cyan through its pilot
        // tone and blue/yellow through the data, and white means the
        // routine has finished. Started is a colour; finished is that
        // colour held over three polls, so a stripe caught mid-pulse is
        // not read as a done save.
        boolean started = false;
        for (int waited = 0; waited < 15 * Emulator.SECOND; waited += 300) {
            if (emulator.borderColour() != 7) {
                started = true;
                break;
            }
            emulator.idle(300);
        }
        assertTrue("the border never left white, so the save never started",
                   started);

        boolean finished = false;
        for (int waited = 0, white = 0; waited < 60 * Emulator.SECOND;
             waited += 300) {
            white = emulator.borderColour() == 7 ? white + 1 : 0;
            if (white >= 3) {
                finished = true;
                break;
            }
            emulator.idle(300);
        }
        assertTrue("the save never finished within a minute of starting",
                   finished);

        emulator.menu("Media", "Stop recording");
        assertTrue("Stop recording from the sheet never stopped the recorder",
                   awaitRecording(false));
        awaitBlockCount(1);
        assertEquals("the recorded save is not the one block on the tape",
                     1, blockCount());

        // Write the deck out. The command is queued and there is no answer
        // to wait for, so the file itself is the report, as in Media.java.
        File recorded = new File(emulator.context().getCacheDir(),
                                 "recorded.tzx");
        if (recorded.isFile()) recorded.delete();
        FuseNative.writeTape(recorded.getAbsolutePath());
        boolean written = false;
        for (int waited = 0; waited < 5 * Emulator.SECOND; waited += 100) {
            if (recorded.isFile() && recorded.length() > 0) {
                written = true;
                break;
            }
            emulator.idle(100);
        }
        assertTrue("the tape holding the recording was never written", written);

        // And back: reset, open the file, and the autoloaded LOAD "" reads
        // the recording for real (an RLE pulse block is no ROM loader, so
        // the trap leaves it to the tape), the 128 auto-starts it, and
        // BORDER 4 runs. Green is not a colour the ROM or the port stripes
        // ever paint, so only the loaded program sets it.
        emulator.menu("Machine", "Reset", "Reset");
        emulator.idle(Emulator.BOOT);
        emulator.open(recorded);

        boolean green = false;
        for (int waited = 0; waited < 90 * Emulator.SECOND; waited += 300) {
            if (emulator.borderColour() == 4) {
                green = true;
                break;
            }
            emulator.idle(300);
        }
        assertTrue("the recording did not load back: the border never "
                   + "turned green, so the saved program never ran", green);
    }
}
