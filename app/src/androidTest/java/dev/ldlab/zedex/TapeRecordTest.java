package dev.ldlab.zedex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.storage.Storage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

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

    /** The bench's saved tape-format preference, put back by @After. */
    private String savedFormat;

    private final Emulator emulator = new Emulator();

    private SharedPreferences preferences() {
        return emulator.context()
                .getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Pins the <em>Save tape…</em> page's format preference - these tests
     * are about which extension the page picks, so they set it rather
     * than inheriting whatever the bench was left with. @After puts the
     * previous value back.
     */
    private void tapeFormat(String value) {
        preferences().edit()
                     .putString(Prefs.KEY_TAPE_FORMAT, value)
                     .commit();
    }

    @Before
    public void setUp() {
        emulator.useDataFolder();
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());

        savedFormat = preferences().getString(Prefs.KEY_TAPE_FORMAT, null);

        // A run killed mid-recording leaves the bench recording, and every
        // later test would then start a second recording on top of it.
        stopAndForget();
    }

    @After
    public void stopRecordingAndCleanUp() {
        stopAndForget();

        if (!Objects.equals(
                    preferences().getString(Prefs.KEY_TAPE_FORMAT, null), savedFormat)) {
            SharedPreferences.Editor restore = preferences().edit();
            if (savedFormat == null) restore.remove(Prefs.KEY_TAPE_FORMAT);
            else restore.putString(Prefs.KEY_TAPE_FORMAT, savedFormat);
            restore.commit();
        }

        // What the round trip and the plain save write, in the folder
        // <em>Save tape…</em> actually saves to: a killed run never reaches
        // here, and a stale file reads as a fresh one - the next run waits
        // for a file it never wrote, or MediaStore quietly renames it to
        // "recorded (1).tzx" with the same result.
        deleteTapeFiles("recorded", "plain");
        for (String name : new String[]{"saver.tap", "plain.tap"}) {
            File file = new File(emulator.context().getCacheDir(), name);
            if (file.isFile()) file.delete();
        }
    }

    /**
     * Both extensions for each name: <em>which</em> one arrives is exactly
     * what those tests assert, so the other must not be lying around from
     * a previous run.
     */
    private void deleteTapeFiles(String... bases) {
        File directory = Storage.tapesDirectory(emulator.context());
        for (String base : bases) {
            for (String extension : new String[]{".tap", ".tzx"}) {
                File file = new File(directory, base + extension);
                if (file.isFile()) file.delete();
            }
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

    /** Polls the border for {@code timeout}, green being the answer. */
    private boolean awaitGreen(long timeout) {
        for (long waited = 0; waited < timeout; waited += 300) {
            if (emulator.borderColour() == 4) return true;
            emulator.idle(300);
        }
        return emulator.borderColour() == 4;
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
     * <p>The file arrives as a <b>.tzx</b> from the <em>Save tape…</em>
     * page, and that is half the point: libspectrum's .tap writer has
     * nothing to say about an RLE pulse block - every recording is one -
     * and the page has to refuse to write the empty .tap that used to
     * answer in its place.
     *
     * <p>Bound comments measured on the bench: the save itself is the
     * ROM's own pilot tone and two blocks — a little over two seconds of
     * real time — and the load back is the same tape played for real,
     * seconds. The bounds are minutes because a loaded host is slower
     * than the emulator, not because the Spectrum is.
     */
    @Test
    public void aRecordedSaveLoadsBack() throws IOException {
        deleteTapeFiles("recorded");

        // The save step is about the format the page picks, so pin it:
        // unpin, and a bench left on "tzx" would let the round trip pass
        // on the preference's default rather than on the choice.
        tapeFormat("tap");

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

        // Write the deck out through the *Save tape…* page, the way a
        // person does it: type a bare name, the page picks the format -
        // and picking .tap here can only write an empty file. The command
        // is queued with no answer to wait for, so the file itself is the
        // report, as in Media.writeTape.
        File recorded = new File(Storage.tapesDirectory(emulator.context()),
                                 "recorded.tzx");
        emulator.menu("Media", "Save tape");
        emulator.enterText("recorded");
        emulator.tap("Save tape");
        boolean written = false;
        for (int waited = 0; waited < 5 * Emulator.SECOND; waited += 100) {
            if (recorded.isFile() && recorded.length() > 0) {
                written = true;
                break;
            }
            emulator.idle(100);
        }
        assertTrue("the tape holding the recording was never written", written);
        assertFalse("Save tape… wrote a .tap for a tape a .tap cannot hold",
                    new File(recorded.getParentFile(), "recorded.tap").isFile());

        // And back: reset, open the file, and Fuse's autoload types the
        // LOAD "" that reads the recording for real (an RLE pulse block is
        // no ROM loader, so the trap leaves it to the tape) and the 128
        // auto-starts it, and BORDER 4 runs. Green is not a colour the ROM
        // or the port stripes ever paint, so only the loaded program sets
        // it. One attempt is not enough: the phantom typist races the
        // pilot tone and loses sometimes - measured, the white border
        // staying put while a second open() of the same file loads in a
        // second. The file is already on the deck, so retrying just asks
        // for the load again.
        emulator.menu("Machine", "Reset", "Reset");
        emulator.idle(Emulator.BOOT);
        emulator.open(recorded);

        boolean green = awaitGreen(45 * Emulator.SECOND);
        if (!green) {
            emulator.open(recorded);
            green = awaitGreen(45 * Emulator.SECOND);
        }

        assertTrue("the recording did not load back: the border never "
                   + "turned green, so the saved program never ran", green);
    }

    /**
     * The other half of the format decision: a tape made only of blocks
     * a .tap can carry must still come out as a .tap. Without this the fix
     * for a recorded tape could quietly become "everything is .tzx now",
     * and nothing else in this file would notice.
     *
     * <p>Saves through the sheet with the format preference pinned to
     * "tap" - this test is about that preference, so it sets it; @After
     * puts the bench's own value back.
     */
    @Test
    public void aPlainTapeStillSavesAsTap() throws IOException {
        deleteTapeFiles("plain");
        tapeFormat("tap");

        // Empty deck first: opening over a sibling's modified tape
        // would raise the save-confirmation and stall the pump.
        startFromAnEmptyTape();

        File program = new File(emulator.context().getCacheDir(),
                                "plain.tap");
        if (program.isFile()) program.delete();
        new TapeProgram().line(10, "BORDER 4: STOP")
                         .writeTo(program, "plain");
        emulator.open(program);

        File saved = new File(Storage.tapesDirectory(emulator.context()),
                              "plain.tap");
        emulator.menu("Media", "Save tape");
        emulator.enterText("plain");
        emulator.tap("Save tape");

        boolean written = false;
        for (int waited = 0; waited < 5 * Emulator.SECOND; waited += 100) {
            if (saved.isFile() && saved.length() > 0) {
                written = true;
                break;
            }
            emulator.idle(100);
        }
        assertTrue("a tape a .tap can hold stopped saving as .tap", written);
        assertFalse("a plain tape went to .tzx too",
                    new File(saved.getParentFile(), "plain.tzx").isFile());
    }
}
