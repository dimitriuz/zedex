package dev.ldlab.zedex.media;

/**
 * One song of an {@code .ay}, as a 128K snapshot Fuse can open.
 *
 * <b>No new emulation, which is the whole idea.</b> The format's own
 * specification describes playing a song as a machine state - fill memory so,
 * set every register so, put a six-instruction player here, start the Z80
 * there - and a snapshot is exactly a machine state written down. So this
 * builds one and hands it to the emulator that is already in the app. The
 * music is then made the way the specification says it should be: by the
 * machine, running the game's own driver.
 *
 * <b>128K, because the AY chip is.</b> A 48K Spectrum has no sound chip at
 * all - it is the 128K's addition - so a snapshot of a 48K would run the
 * driver in silence.
 *
 * <b>The player is not at address zero, and that is the one deliberate
 * departure.</b> The specification has the player fill {@code #0000-#3FFF}
 * with its own bytes and start the Z80 at zero, which means replacing the ROM
 * - something no snapshot format this app can write is able to say. So the
 * player goes into spare RAM instead and the machine's real ROM stays, which
 * is what the players written for real Spectrums do, and what makes those work
 * on real hardware. What it costs is the handful of tunes that call into low
 * memory expecting the specification's {@code #C9} fill to return
 * harmlessly; those find the real ROM there instead.
 */
public final class AySnapshot {

    private AySnapshot() {
    }

    /**
     * Where the player may go, and how much room to leave under the stack.
     *
     * <b>Not the top of memory, which was the first attempt and was silent.</b>
     * The stack is wherever the song says, and this tune says zero - which
     * means the first {@code CALL} pushes to {@code #FFFE} and every one after
     * it walks down through anything sitting at the top. A player written
     * there is overwritten by its own first instruction.
     *
     * So it goes in the lowest gap the blocks leave, above the screen and
     * clear of the stack's descent. Above the screen because a player in the
     * display file is drawn on it - harmless, but a snapshot that paints
     * static across the top of the screen looks broken to anybody watching.
     */
    private static final int LOWEST = 0x6000;

    /** How far under the stack pointer to stay - a driver may push a good
     *  deal before it settles. */
    private static final int UNDER_STACK = 0x0200;

    /** 128K RAM in eight banks, and the three that are paged in at boot. */
    private static final int BANKS = 8;
    private static final int BANK_SIZE = 0x4000;

    /** Which bank sits where, with port {@code 0x7ffd} left at zero. */
    private static final int BANK_AT_4000 = 5;
    private static final int BANK_AT_8000 = 2;
    private static final int BANK_AT_C000 = 0;

    /**
     * The song as {@code .z80}, version 2, hardware mode 3 - a 128K.
     *
     * @return the file's bytes, or null when the song has nothing to load
     */
    public static byte[] of(AyFile.Song song) {
        if (song == null || song.blocks.isEmpty()) return null;

        byte[][] banks = new byte[BANKS][BANK_SIZE];

        for (AyFile.Block block : song.blocks) load(banks, block);

        int init = song.init != 0 ? song.init : firstCall(song);
        byte[] player = player(init, song.interrupt);

        int at = free(song, player.length);
        write(banks, at, player);

        return z80(song, banks, at);
    }

    /**
     * The lowest run of free memory the player fits in.
     *
     * Free means no block covers it and it is clear of where the stack will
     * grow - see {@link #UNDER_STACK}. A song whose blocks fill everything
     * from {@link #LOWEST} up has nowhere to be helped: the player goes at
     * {@code LOWEST} anyway and is written after the blocks, so it wins the
     * overlap and the tune has a chance of playing rather than none.
     */
    private static int free(AyFile.Song song, int length) {
        boolean[] taken = new boolean[0x10000];

        for (AyFile.Block block : song.blocks) {
            for (int at = 0; at < block.bytes.length; at++) {
                taken[(block.address + at) & 0xffff] = true;
            }
        }

        // A stack of zero starts at the very top and descends, which is the
        // case that made this method necessary.
        int stack = song.stack == 0 ? 0x10000 : song.stack;

        for (int at = LOWEST; at + length < 0x10000; at++) {
            if (at + length > stack - UNDER_STACK && at < stack) continue;

            boolean room = true;
            for (int by = 0; by < length && room; by++) room = !taken[at + by];

            if (room) return at;
        }

        return LOWEST;
    }

    /**
     * The six instructions that are the whole player.
     *
     * Straight out of the specification, less the {@code DI} it opens with -
     * a snapshot starts with interrupts already disabled, so the byte would
     * do nothing but shift every address after it.
     *
     * The two forms differ by one instruction and by which interrupt mode
     * they leave the machine in. A driver that named an interrupt routine
     * wants to be called from the ordinary one; a driver that named none has
     * installed a handler of its own during {@code init} and wants only to be
     * left alone with interrupts on.
     */
    private static byte[] player(int init, int interrupt) {
        if (interrupt == 0) {
            return new byte[] {
                (byte) 0xcd, low(init), high(init),      // CALL init
                (byte) 0xed, (byte) 0x5e,               // IM 2
                (byte) 0xfb,                            // EI
                (byte) 0x76,                            // HALT
                (byte) 0x18, (byte) 0xfd,               // JR -3, to the HALT
            };
        }

        return new byte[] {
            (byte) 0xcd, low(init), high(init),         // CALL init
            (byte) 0xed, (byte) 0x56,                   // IM 1
            (byte) 0xfb,                                // EI
            (byte) 0x76,                                // HALT
            (byte) 0xcd, low(interrupt), high(interrupt), // CALL interrupt
            (byte) 0x18, (byte) 0xfa,                   // JR -6, to the EI
        };
    }

    /**
     * Where to call when the song names no init address.
     *
     * The specification says to use "the first CALL instruction address of
     * the first block", which means: find the first {@code CD} in the block
     * and take the address it calls. A block with no call at all leaves
     * nothing to do but start at its beginning, which is what a driver
     * written as straight code would want anyway.
     */
    private static int firstCall(AyFile.Song song) {
        AyFile.Block first = song.blocks.get(0);

        for (int at = 0; at + 2 < first.bytes.length; at++) {
            if ((first.bytes[at] & 0xff) != 0xcd) continue;

            return (first.bytes[at + 1] & 0xff) | ((first.bytes[at + 2] & 0xff) << 8);
        }

        return first.address;
    }

    // --- memory -----------------------------------------------------------------

    /**
     * A block into the banks it covers, ignoring whatever falls in the ROM.
     *
     * A block may start below {@code #4000}, where there is no RAM to put it
     * in - the specification's player has no ROM to get in the way and this
     * one does. Silently, because it is not a failure of the file: it is the
     * price of keeping the machine's own ROM, and a tune that depends on it
     * will simply not sound right rather than not run.
     */
    private static void load(byte[][] banks, AyFile.Block block) {
        write(banks, block.address, block.bytes);
    }

    private static void write(byte[][] banks, int address, byte[] bytes) {
        for (int at = 0; at < bytes.length; at++) {
            int where = (address + at) & 0xffff;
            int bank = bankFor(where);

            if (bank < 0) continue;
            banks[bank][where & 0x3fff] = bytes[at];
        }
    }

    /** Which RAM bank an address is in, or -1 for the ROM. */
    private static int bankFor(int address) {
        if (address < 0x4000) return -1;
        if (address < 0x8000) return BANK_AT_4000;
        if (address < 0xc000) return BANK_AT_8000;

        return BANK_AT_C000;
    }

    // --- the snapshot -----------------------------------------------------------

    /**
     * Version 2 rather than 3, and uncompressed.
     *
     * Two decisions worth naming. Version 2's extra header is 23 bytes and
     * version 3's is 54 or 55 with a great deal in it this has no opinion
     * about; the older one says everything needed and there is less of it to
     * get wrong. And a page written with a length of {@code #FFFF} is the
     * format's own way of saying "not compressed", which costs 128 KB of
     * temporary file and saves implementing a run-length encoder whose bugs
     * would show up as a tune that plays almost right.
     */
    private static byte[] z80(AyFile.Song song, byte[][] banks, int player) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        int hi = song.hiReg;
        int lo = song.loReg;
        int pair = (hi << 8) | lo;

        // The first 30 bytes are the original version 1 header.
        out.write(lo);                       // A  - "including AF"
        out.write(lo);                       // F
        word(out, pair);                     // BC
        word(out, pair);                     // HL
        word(out, 0);                        // PC zero: this is a v2 file
        word(out, song.stack);               // SP
        out.write(3);                        // I, which the format fixes at 3
        out.write(0);                        // R

        // Bit 1-3 are the border colour; black, since nothing is drawn.
        out.write(0);
        word(out, pair);                     // DE
        word(out, pair);                     // BC'
        word(out, pair);                     // DE'
        word(out, pair);                     // HL'
        out.write(lo);                       // A'
        out.write(lo);                       // F'
        word(out, pair);                     // IY
        word(out, pair);                     // IX

        // Interrupts off and mode 0, exactly as the specification's step (n)
        // says - the player's own first instructions choose the mode it wants
        // and switch them on.
        out.write(0);                        // IFF1
        out.write(0);                        // IFF2
        out.write(0);                        // IM 0

        word(out, 23);                       // the version 2 extra header
        word(out, player);                   // PC, where the player is
        out.write(3);                        // hardware mode 3: a 128K

        // Bank 0 at #C000, and bit 4 set to page in the 48K ROM rather than
        // the 128K editor's.
        //
        // <b>Measured, and it is the difference between music and silence.</b>
        // The player leaves the machine in interrupt mode 1, so fifty times a
        // second the Z80 goes to #0038 - whatever is in ROM there. The 48K
        // ROM's routine is the small one every driver ever written was
        // written against: count a frame, scan the keyboard, return. The 128K
        // editor's is not, and with it paged in this tune ran and made no
        // sound at all.
        out.write(0x10);
        out.write(0);                        // interface 1 rom absent

        for (int at = 0; at < 18; at++) out.write(0);

        // Eight banks, each announced as uncompressed. Fuse numbers them from
        // three: page 3 is bank 0, and so on to page 10.
        for (int bank = 0; bank < BANKS; bank++) {
            word(out, 0xffff);
            out.write(bank + 3);
            out.write(banks[bank], 0, BANK_SIZE);
        }

        return out.toByteArray();
    }

    private static void word(java.io.ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private static byte low(int value) {
        return (byte) (value & 0xff);
    }

    private static byte high(int value) {
        return (byte) ((value >> 8) & 0xff);
    }
}
