package dev.ldlab.zedex.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;

/**
 * A song, written out as a machine.
 *
 * The specification describes playing one as a state to put a Z80 into, so
 * this builds that state as a snapshot and lets the emulator already in the
 * app do the rest. What can be checked without a device is that the file says
 * what it means to: the right machine, the registers the format asks for, the
 * player where it was put, and the driver's own bytes in the bank that
 * address belongs to.
 *
 * That last one is the interesting check. A 128K's memory is eight banks and
 * only three are visible at once; putting a block at {@code #EC60} means
 * putting it in bank 0, at {@code #2C60} within it. Getting that wrong gives a
 * snapshot that loads perfectly and plays silence.
 */
public class AySnapshotTest {

    /** The version 1 header, then the two-byte length of the extra one. */
    private static final int EXTRA_LENGTH_AT = 30;
    private static final int EXTRA_AT = 32;

    private static final int BANK_SIZE = 0x4000;

    private static AyFile.Song song() throws Exception {
        byte[] bytes;

        try (InputStream in = AySnapshotTest.class.getClassLoader()
                .getResourceAsStream("licence-to-kill.ay")) {
            assertNotNull("the fixture is missing", in);
            bytes = in.readAllBytes();
        }

        return AyFile.read(bytes).songs.get(0);
    }

    private static int word(byte[] z80, int at) {
        return (z80[at] & 0xff) | ((z80[at + 1] & 0xff) << 8);
    }

    /**
     * Where a bank's 16384 bytes start, found by walking the pages.
     *
     * Each is announced by a length, a page number and then the data - and
     * every one here is written uncompressed, which the format spells
     * {@code #FFFF}.
     */
    private static int bankAt(byte[] z80, int bank) {
        int at = EXTRA_AT + word(z80, EXTRA_LENGTH_AT);

        while (at < z80.length) {
            int length = word(z80, at);
            int page = z80[at + 2] & 0xff;

            assertEquals("the pages should be uncompressed", 0xffff, length);
            if (page == bank + 3) return at + 3;

            at += 3 + BANK_SIZE;
        }

        return -1;
    }

    @Test
    public void thesnapshotIsA128k() throws Exception {
        byte[] z80 = AySnapshot.of(song());

        assertEquals("a version 2 extra header", 23, word(z80, EXTRA_LENGTH_AT));
        assertEquals("hardware mode 3 is a 128K, which is the machine with an"
                     + " AY chip in it at all", 3, z80[EXTRA_AT + 2] & 0xff);
        // Bank 0 at #C000, and bit 4 set for the 48K ROM rather than the
        // 128K editor's. Measured on a device: the player leaves the machine
        // in interrupt mode 1, so what sits at #0038 is whatever ROM is paged
        // in - and with the editor ROM there, a real tune ran and made no
        // sound at all.
        assertEquals("port 0x7ffd should page bank 0 in at #C000 with the 48K ROM",
                     0x10, z80[EXTRA_AT + 3] & 0xff);
    }

    /**
     * The registers the format asks for: every common one filled with the
     * same two bytes.
     *
     * This is how a file with five tunes and one driver says which tune to
     * play, so getting it wrong plays the first one five times - which sounds
     * exactly like a working player.
     */
    @Test
    public void everyRegisterIsFilledFromHiRegAndLoReg() throws Exception {
        AyFile.Song song = song();
        byte[] z80 = AySnapshot.of(song);

        assertEquals("A", song.loReg, z80[0] & 0xff);
        assertEquals("F", song.loReg, z80[1] & 0xff);

        int pair = (song.hiReg << 8) | song.loReg;

        assertEquals("BC", pair, word(z80, 2));
        assertEquals("HL", pair, word(z80, 4));
        assertEquals("DE", pair, word(z80, 13));
        assertEquals("IY", pair, word(z80, 23));
        assertEquals("IX", pair, word(z80, 25));

        assertEquals("I is fixed at 3 by the format", 3, z80[10] & 0xff);
        assertEquals("SP", song.stack, word(z80, 8));
    }

    /** Interrupts off and mode 0 to begin with - the player's own first
     *  instructions choose what it wants. */
    @Test
    public void themachineStartsWithInterruptsOff() throws Exception {
        byte[] z80 = AySnapshot.of(song());

        assertEquals("IFF1", 0, z80[27] & 0xff);
        assertEquals("IFF2", 0, z80[28] & 0xff);
        assertEquals("IM 0", 0, z80[29] & 0x03);
    }

    /**
     * The driver's own bytes, in the bank that address belongs to.
     *
     * {@code #EC60} is in the top sixteen kilobytes, which on a 128K with
     * port {@code 0x7ffd} at zero is bank 0 - so the byte lands at
     * {@code #2C60} inside it. A snapshot that put it anywhere else would
     * load and run and make no sound.
     */
    @Test
    public void thedriverLandsInTheBankItsAddressBelongsTo() throws Exception {
        AyFile.Song song = song();
        byte[] z80 = AySnapshot.of(song);

        AyFile.Block block = song.blocks.get(0);
        int bank0 = bankAt(z80, 0);

        assertTrue("no bank 0 in the snapshot", bank0 > 0);

        for (int at = 0; at < 16; at++) {
            assertEquals("byte " + at + " of the driver",
                         block.bytes[at] & 0xff,
                         z80[bank0 + (block.address & 0x3fff) + at] & 0xff);
        }
    }

    /**
     * And the player is where the snapshot says to start.
     *
     * {@code CALL init} first, and the loop after it - the format's own six
     * instructions, less the {@code DI} that a snapshot starting with
     * interrupts off has already done.
     */
    @Test
    public void theplayerIsAtTheAddressThePcPointsAt() throws Exception {
        AyFile.Song song = song();
        byte[] z80 = AySnapshot.of(song);

        int pc = word(z80, EXTRA_AT);

        assertTrue("the player must not sit where the stack will walk over it"
                   + " - this song's stack is " + song.stack, pc < 0xf000);

        int bank = pc < 0x8000 ? 5 : pc < 0xc000 ? 2 : 0;
        int at = bankAt(z80, bank) + (pc & 0x3fff);

        assertEquals("the player should open with CALL", 0xcd, z80[at] & 0xff);
        assertEquals("...to the song's own init address",
                     song.init, word(z80, at + 1));

        assertEquals("then IM 1", 0xed, z80[at + 3] & 0xff);
        assertEquals(0x56, z80[at + 4] & 0xff);
        assertEquals("then EI", 0xfb, z80[at + 5] & 0xff);
        assertEquals("then HALT", 0x76, z80[at + 6] & 0xff);
        assertEquals("then CALL the interrupt routine", 0xcd, z80[at + 7] & 0xff);
        assertEquals(song.interrupt, word(z80, at + 8));
    }

    /** A song with nothing to load is no song. */
    @Test
    public void asongWithNoBlocksIsNotASnapshot() {
        assertNull(AySnapshot.of(null));
    }
}
