package dev.ldlab.zedex;

import dev.ldlab.zedex.machine.Border;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * The promises {@link FuseNative}'s comments make about what the C side hands
 * back, checked against what it actually hands back.
 *
 * 11.4's number nine. Every one of these is a shape Java code indexes by
 * without asking - two arrays walked as though they were the same length,
 * three strings taken per drive, a buffer read to a length worked out from a
 * different call. None of them is checked anywhere, and none would fail
 * gracefully: the arrays throw {@code ArrayIndexOutOfBounds} somewhere far
 * from the cause, and the buffer throws {@code BufferUnderflow} on the
 * emulation thread while recording.
 *
 * They are cheap to assert and they guard a seam that is easy to break from
 * the far side. Fuse's version is pinned, but {@code native/patches} is a
 * living series and {@code android_bridge.c} is edited whenever the Java side
 * wants something new - and a change there compiles perfectly whatever it does
 * to these.
 *
 * Instrumentation rather than JVM, necessarily: these are questions only the
 * real library can answer, and it has to have started.
 */
@RunWith(AndroidJUnit4.class)
public class NativeContractTest {

    /** Fuse's own id for the machine, from {@code machineIds}. */
    private static final String PENTAGON_ID = "pentagon";

    /** What Fuse loads for one - see NewDiskTest, which names the same two.
     *  trdos.rom is the Beta interface, and so the drives. */
    private static final String[] PENTAGON_ROMS = { "128p-0.rom", "trdos.rom" };

    private final Emulator emulator = new Emulator();

    /** Put back in {@link #restoreTheMachine}: it is the user's bench, and the
     *  machine they left running is the one they were using. */
    private int machineBefore = -1;

    @Before
    public void setUp() {
        emulator.useDataFolder();
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());

        machineBefore = FuseNative.currentMachine();
    }

    @After
    public void restoreTheMachine() {
        if (machineBefore < 0 || FuseNative.currentMachine() == machineBefore) return;

        FuseNative.selectMachine(machineBefore);
        for (int waited = 0; waited < 20 * Emulator.SECOND; waited += 250) {
            emulator.idle(250);
            if (FuseNative.currentMachine() == machineBefore) return;
        }
    }

    /**
     * {@code machineIds} says "in the same order as machineNames", and every
     * caller walks one and indexes the other.
     *
     * {@code Emulator.useSpectrum48} does it in this very suite: it searches
     * {@code machineIds} for "128" and hands the index straight to {@code
     * selectMachine}, which the menu then labels from {@code machineNames}. A
     * mismatch does not fail - it silently starts a different machine and
     * calls it by the wrong name.
     */
    @Test
    public void theMachineNamesAndIdsAreTheSameListTwice() {
        String[] names = FuseNative.machineNames();
        String[] ids = FuseNative.machineIds();

        assertNotNull("machineNames() answered null", names);
        assertNotNull("machineIds() answered null", ids);
        assertTrue("Fuse offers no machines at all", names.length > 0);

        assertEquals("machineNames() has " + names.length + " and machineIds() has "
                     + ids.length + ", and every caller indexes one by the other",
                     names.length, ids.length);

        for (int at = 0; at < names.length; at++) {
            assertTrue("machine " + at + " has no name", names[at] != null && !names[at].isEmpty());
            assertTrue("machine " + at + " (" + names[at] + ") has no id",
                       ids[at] != null && !ids[at].isEmpty());
        }
    }

    /** The current machine indexes that list, which is what the menu ticks and
     *  what {@code Emulator.useSpectrum48} compares against. */
    @Test
    public void theCurrentMachineIsOneOfThem() {
        int current = FuseNative.currentMachine();
        int count = FuseNative.machineIds().length;

        assertTrue("currentMachine() is " + current + " against " + count + " machines",
                   current >= 0 && current < count);
    }

    /**
     * "Three strings per drive: its name, the disk in it, and \"1\" if
     * modified" - so the details array is exactly three times the id array,
     * and anything else means the reader is off by a drive from some row on.
     *
     * On a Pentagon, deliberately. The machine this suite normally leaves
     * running is a 128K, which has no disk interface and therefore no drives -
     * and against zero drives this assertion is {@code 0 == 3 * 0}, which is
     * true of any implementation including a broken one. Measured before it
     * was written: {@code drives=0 details=0} on the bench as found. A test
     * that can only pass is not one.
     *
     * The Beta interface a Pentagon comes up with is what puts real drives in
     * the list; {@code NewDiskTest} uses the same machine for the same reason.
     * Skipped on the ROM files being absent, which is a fact no timing
     * changes - see that test's own note on what deciding this from the screen
     * once cost.
     */
    @Test
    public void thereAreExactlyThreeDetailsPerDrive() {
        for (String rom : PENTAGON_ROMS) {
            assumeTrue("no " + rom + " in " + emulator.romFolder(),
                       new File(emulator.romFolder(), rom).exists());
        }

        usePentagon();

        int[] ids = FuseNative.driveIds();
        String[] details = FuseNative.driveDetails();

        assertNotNull("driveIds() answered null", ids);
        assertNotNull("driveDetails() answered null", details);

        assertTrue("a Pentagon came up with no drives at all, so this would have "
                   + "asserted nothing", ids.length > 0);

        assertEquals("driveDetails() has " + details.length + " for " + ids.length
                     + " drives, and it is read three at a time",
                     3 * ids.length, details.length);

        // The first of each three is the drive's own name, which the Media
        // menu shows as a row - an empty one is a row nobody can identify.
        for (int drive = 0; drive < ids.length; drive++) {
            String name = details[3 * drive];
            assertTrue("drive " + drive + " has no name", name != null && !name.isEmpty());
        }
    }

    /**
     * Switches to a Pentagon and waits for its drives, by asking Fuse rather
     * than by waiting a while - the command is queued and drained on the
     * emulation thread, so the answer arrives when it arrives. The same shape
     * as {@code Emulator.useSpectrum48}.
     */
    private void usePentagon() {
        String[] ids = FuseNative.machineIds();

        int pentagon = -1;
        for (int at = 0; at < ids.length; at++) {
            if (PENTAGON_ID.equals(ids[at])) pentagon = at;
        }
        assertTrue("Fuse offers no machine called " + PENTAGON_ID, pentagon >= 0);

        FuseNative.selectMachine(pentagon);

        for (int waited = 0; waited < 20 * Emulator.SECOND; waited += 250) {
            emulator.idle(250);
            if (FuseNative.currentMachine() == pentagon
                    && FuseNative.driveIds().length > 0) {
                return;
            }
        }

        fail("the machine never became a Pentagon with drives");
    }

    /**
     * The frame buffer holds a whole frame at the stride it reports.
     *
     * {@code Recorder.read} works the length out as {@code height * stride}
     * and calls {@code get} for exactly that many bytes - so a buffer smaller
     * than the tallest frame Fuse draws throws {@code BufferUnderflow} on the
     * emulation thread, mid-recording, with the recording already started.
     *
     * 240 is {@link Border#FULL}'s own height, which is the frame Fuse draws
     * whole; the other two borders are windows onto the same buffer. Checking
     * the largest covers all three.
     */
    @Test
    public void theFrameBufferHoldsAWholeFrame() {
        ByteBuffer pixels = FuseNative.frameBuffer();
        int stride = FuseNative.frameStride();

        assertNotNull("frameBuffer() answered null", pixels);
        assertTrue("frameStride() is " + stride, stride > 0);
        assertTrue("the stride is " + stride + ", narrower than the " + Border.FULL.width
                   + " pixel frame it is meant to step a row of",
                   stride >= Border.FULL.width);

        int wanted = stride * Border.FULL.height;
        assertTrue("the frame buffer holds " + pixels.capacity() + " bytes and a full "
                   + "frame at stride " + stride + " needs " + wanted
                   + " - Recorder.read asks for exactly that many",
                   pixels.capacity() >= wanted);
    }

    /**
     * Sixteen colours, because that is what the indices in the frame mean.
     *
     * {@code Recorder} carries the palette beside every frame and the encoders
     * index it by the byte they find. A short palette is an
     * {@code ArrayIndexOutOfBounds} inside a GIF or MP4 encode - on a worker,
     * halfway through somebody's recording.
     */
    @Test
    public void thePaletteHasTheSixteenColoursTheFrameIndexes() {
        int[] palette = FuseNative.palette();

        assertNotNull("palette() answered null", palette);
        assertEquals("the Spectrum has sixteen colours and the frame indexes them",
                     16, palette.length);
    }
}
