package dev.ldlab.zedex;

import dev.ldlab.zedex.media.AyFile;
import dev.ldlab.zedex.media.Music;
import dev.ldlab.zedex.media.AySnapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The machine playing an {@code .ay}, which is the only test of this that
 * means anything.
 *
 * Everything else about this feature can be checked by reading bytes: that
 * the file parsed, that the snapshot claims to be a 128K, that the driver
 * landed in the right bank. None of it says whether a sound comes out. This
 * does, by asking the emulator what its AY chip's channels are doing - which
 * is the same thing the meter on screen reads, and cannot be true unless the
 * game's own driver is running and writing to the chip.
 *
 * The tune is a real one from the archive, 007 Licence to Kill, carried in
 * this test's own assets so the run needs no network.
 */
@RunWith(AndroidJUnit4.class)
public class AyPlaybackTest {

    /** Long enough for the machine to come up on the snapshot's own model,
     *  run the driver's init and take a few dozen interrupts. */
    private static final long PLAYS_FOR = 5 * Emulator.SECOND;

    /** Green, and then nothing else - so the border says whether this
     *  program is still the thing running. */
    private static final int GREEN = 4;

    private static final TapeProgram REPORTER = new TapeProgram()
            .line(10, "BORDER 4")
            .line(20, "GO TO 20")
            .startingAt(10);

    private final Emulator emulator = new Emulator();

    @Before
    public void setUp() {
        emulator.useDataFolder();
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());
    }

    /**
     * A song, turned into a machine, makes a noise.
     *
     * <b>Asserted on the chip rather than on the speaker.</b> There is no way
     * to hear a test, and the audio device says nothing about whether what it
     * is playing is music - but a channel's amplitude is zero until something
     * writes to the AY, and the only thing that could write to it here is the
     * driver ripped out of the game.
     */
    @Test
    public void asongFromTheArchiveActuallyPlays() throws IOException {
        AyFile file = AyFile.read(asset("licence-to-kill.ay"));
        assertNotNull("the tune would not read", file);

        byte[] snapshot = AySnapshot.of(file.songs.get(file.first));
        assertNotNull("the tune would not build into a machine", snapshot);

        File into = new File(emulator.context().getCacheDir(), "uitest-tune.z80");
        try (FileOutputStream out = new FileOutputStream(into)) {
            out.write(snapshot);
        }

        FuseNative.openFile(into.getAbsolutePath());
        SystemClock.sleep(PLAYS_FOR);

        // Loaded at all, which is a different failure from not playing: a
        // snapshot of a music file draws nothing, so the screen is black -
        // where a machine that refused it is still on its own boot menu,
        // which is white. Asked first, so the two say which went wrong.
        assertEquals("the snapshot was refused: the machine is still showing"
                     + " its own screen", 0, emulator.borderColour());

        assertTrue("nothing is coming out of the AY chip, so the driver is not"
                   + " running - levels were " + Integer.toHexString(FuseNative.ayLevels()),
                   loudestChannel() > 0);

        into.delete();
    }

    /**
     * A snapshot built by this code runs the code it was given.
     *
     * The narrower question, and the one to ask first when a tune is silent:
     * a driver that makes no sound could be a broken snapshot, a player that
     * never runs, or a machine with no chip in it. This puts six instructions
     * in a block, points the song at them, and asks the chip - so anything
     * that fails here is the snapshot's fault and nothing here can be blamed
     * on somebody's thirty-year-old driver.
     */
    @Test
    public void asnapshotRunsTheCodeItWasGiven() throws IOException {
        // Select the mixer, turn the three tones on, set channel A to full
        // volume and give it a period to sound at.
        byte[] code = {
            0x01, (byte) 0xfd, (byte) 0xff, 0x3e, 0x07, (byte) 0xed, 0x79,
            0x01, (byte) 0xfd, (byte) 0xbf, 0x3e, 0x38, (byte) 0xed, 0x79,
            0x01, (byte) 0xfd, (byte) 0xff, 0x3e, 0x08, (byte) 0xed, 0x79,
            0x01, (byte) 0xfd, (byte) 0xbf, 0x3e, 0x0f, (byte) 0xed, 0x79,
            0x01, (byte) 0xfd, (byte) 0xff, 0x3e, 0x00, (byte) 0xed, 0x79,
            0x01, (byte) 0xfd, (byte) 0xbf, 0x3e, (byte) 0x80, (byte) 0xed, 0x79,
            0x18, (byte) 0xfe,                                  // JR -2, for ever
        };

        play(AySnapshot.of(AyFile.read(tune(0x8000, 0x8000, 0, code)).songs.get(0)),
             "uitest-tone.z80");

        assertTrue("a snapshot of six instructions did not reach the AY chip -"
                   + " levels were " + Integer.toHexString(FuseNative.ayLevels()),
                   loudestChannel() > 0);
    }

    /**
     * An {@code .ay} with one song and one block, written the way the format
     * wants - big-endian words, pointers relative to themselves.
     */
    private static byte[] tune(int block, int init, int interrupt, byte[] code) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        out.writeBytes("ZXAYEMUL".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(0); out.write(3);
        big(out, 0);                       // PSpecialPlayer
        big(out, 48); big(out, 46);        // PAuthor, PMisc, both to 60
        out.write(0); out.write(0);        // one song, first is it
        big(out, 2);                       // PSongsStructure, to 20

        big(out, 60);                      // 20: name, at 80
        big(out, 78);                      //     data, at 100

        while (out.size() < 80) out.write(0);
        out.writeBytes("Tone\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        while (out.size() < 100) out.write(0);
        out.write(0); out.write(1); out.write(2); out.write(3);
        big(out, 0); big(out, 0);          // lengths
        out.write(0); out.write(0);        // HiReg, LoReg
        big(out, 10);                      // points, at 120
        big(out, 18);                      // addresses, at 130

        while (out.size() < 120) out.write(0);
        big(out, 0x8000); big(out, init); big(out, interrupt);

        while (out.size() < 130) out.write(0);
        big(out, block); big(out, code.length); big(out, 6); big(out, 0);

        while (out.size() < 140) out.write(0);
        out.writeBytes(code);

        return out.toByteArray();
    }

    private static void big(java.io.ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    private void play(byte[] snapshot, String name) throws IOException {
        assertNotNull("nothing was built", snapshot);

        File into = new File(emulator.context().getCacheDir(), name);
        try (FileOutputStream out = new FileOutputStream(into)) {
            out.write(snapshot);
        }

        FuseNative.openFile(into.getAbsolutePath());
        SystemClock.sleep(PLAYS_FOR);
        into.delete();
    }

    /**
     * The game survives the music.
     *
     * <b>The whole design in one test.</b> There is one machine, so a tune is
     * played by loading it - and loading anything is the end of what was
     * running. A player who wanted to hear the theme and lost their game
     * mid-level would never press it twice. So the machine is put aside
     * first and put back after, and this is what says so: a program is left
     * running with the border a colour of its own, the music plays over it,
     * and the border is that colour again afterwards.
     */
    @Test
    public void thegameIsWhereItWasLeftAfterTheMusicStops() throws IOException {
        Music.forget(emulator.context());

        runTheReporter();
        assertEquals("the program did not start", GREEN, emulator.borderColour());

        AyFile file = AyFile.read(asset("licence-to-kill.ay"));
        assertTrue("the tune would not play", Music.play(emulator.context(),
                                                         file.songs.get(file.first)));
        emulator.idle(PLAYS_FOR);

        assertTrue("the music is not playing", loudestChannel() > 0);
        assertTrue("the machine was not put aside",
                   Music.interrupted(emulator.context()));

        Music.stop(emulator.context());
        emulator.idle(3 * Emulator.SECOND);

        assertEquals("the game did not come back - the border should be the"
                     + " colour the program left it", GREEN, emulator.borderColour());
        assertFalse("the kept machine was left lying about",
                    Music.interrupted(emulator.context()));
    }

    /** Something to interrupt: a program that paints the border and stays
     *  running, so its state is visible before and after. */
    private void runTheReporter() throws IOException {
        File tape = new File(emulator.context().getCacheDir(), "uitest-music.tap");
        REPORTER.writeTo(tape, "music");

        emulator.open(tape);
        emulator.idle(10 * Emulator.SECOND);
    }

    /** The loudest of the three channels, as the meter reads them. */
    private static int loudestChannel() {
        int levels = FuseNative.ayLevels();

        return Math.max(levels & 0xff,
                        Math.max((levels >> 8) & 0xff, (levels >> 16) & 0xff));
    }

    private static String machine() {
        int current = FuseNative.currentMachine();
        String[] names = FuseNative.machineNames();

        return current >= 0 && current < names.length ? names[current] : null;
    }

    private static byte[] asset(String name) throws IOException {
        try (InputStream in = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(name)) {
            return in.readAllBytes();
        }
    }
}
