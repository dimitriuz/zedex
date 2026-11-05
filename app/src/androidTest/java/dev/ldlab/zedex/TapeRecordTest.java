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
    public void stopRecording() {
        stopAndForget();
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
}
