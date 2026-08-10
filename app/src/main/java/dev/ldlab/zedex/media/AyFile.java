package dev.ldlab.zedex.media;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@code .ay} music file, read.
 *
 * <b>Not a recording.</b> An {@code .ay} is the game's own music driver -
 * Z80 code - together with the note data it plays, ripped out of the game and
 * given three entry points: where the stack goes, what to call once, and what
 * to call fifty times a second. Playing one means running that code and
 * listening to the AY chip it writes to, which is why nothing but an emulator
 * can do it at all, and why this app can.
 *
 * The format is Patrik Rak's, by way of an Amiga music player, and it shows:
 * every word is big-endian and every pointer is <em>signed and relative to its
 * own position in the file</em>. A pointer read as absolute lands somewhere
 * plausible in a small file and nowhere at all in a large one, which is the
 * mistake this class exists to make once and never again.
 *
 * Reading only, and no Android in it - see {@link AySnapshot}, which turns one
 * of these into a machine.
 */
public final class AyFile {

    /** {@code ZXAYEMUL} - four bytes of file id and four of type. */
    private static final byte[] MAGIC = {
        'Z', 'X', 'A', 'Y', 'E', 'M', 'U', 'L',
    };

    /** One tune. A file usually holds several - the same driver with a
     *  different tune number in {@link #hiReg}. */
    public static final class Song {

        public final String name;

        /** Where the stack goes before {@link #init} is called. */
        public final int stack;

        /**
         * Called once to set the tune up, and then fifty times a second.
         *
         * {@code interrupt} of zero means the driver installs an interrupt
         * handler of its own during {@code init} and wants nothing called -
         * see {@link AySnapshot}, which builds a different player for it.
         */
        public final int init;
        public final int interrupt;

        /** What every register is filled with before {@code init} runs. In
         *  practice this is how a file says which of its tunes to play. */
        public final int hiReg;
        public final int loReg;

        public final List<Block> blocks;

        Song(String name, int stack, int init, int interrupt,
             int hiReg, int loReg, List<Block> blocks) {
            this.name = name;
            this.stack = stack;
            this.init = init;
            this.interrupt = interrupt;
            this.hiReg = hiReg;
            this.loReg = loReg;
            this.blocks = java.util.Collections.unmodifiableList(blocks);
        }
    }

    /** A run of bytes and where in the Z80's memory it goes. */
    public static final class Block {
        public final int address;
        public final byte[] bytes;

        Block(int address, byte[] bytes) {
            this.address = address;
            this.bytes = bytes;
        }
    }

    public final String author;
    public final String misc;
    public final List<Song> songs;

    /** Which of {@link #songs} the file says to start with. */
    public final int first;

    private AyFile(String author, String misc, List<Song> songs, int first) {
        this.author = author;
        this.misc = misc;
        this.songs = java.util.Collections.unmodifiableList(songs);
        this.first = first;
    }

    /**
     * Reads one, or null when the bytes are not an {@code .ay} this
     * understands.
     *
     * Null rather than an exception for the same reason every other reader
     * here answers that way: a file that is not what it claims is an ordinary
     * thing to meet, and the caller's answer is "then there is no music",
     * which is not an error worth a stack trace.
     */
    public static AyFile read(byte[] bytes) {
        if (bytes == null || bytes.length < 20) return null;

        for (int at = 0; at < MAGIC.length; at++) {
            if (bytes[at] != MAGIC[at]) return null;
        }

        try {
            return parse(bytes);
        } catch (RuntimeException malformed) {
            // Every read below is bounds-checked by the array itself, so a
            // truncated or lying file arrives here rather than anywhere it
            // could do damage.
            return null;
        }
    }

    private static AyFile parse(byte[] bytes) {
        String author = string(bytes, pointer(bytes, 12));
        String misc = string(bytes, pointer(bytes, 14));

        int count = (bytes[16] & 0xff) + 1;   // stored one less than it is
        int first = bytes[17] & 0xff;

        int structure = pointer(bytes, 18);
        List<Song> songs = new ArrayList<>();

        for (int at = 0; at < count; at++) {
            int entry = structure + at * 4;

            String name = string(bytes, pointer(bytes, entry));
            songs.add(song(bytes, name, pointer(bytes, entry + 2)));
        }

        return new AyFile(author, misc, songs,
                          first < songs.size() ? first : 0);
    }

    private static Song song(byte[] bytes, String name, int data) {
        int points = pointer(bytes, data + 10);
        int addresses = pointer(bytes, data + 12);

        List<Block> blocks = new ArrayList<>();

        // A zero address ends the list, which is why a block can never be at
        // zero - the format says so itself.
        for (int at = addresses; word(bytes, at) != 0; at += 6) {
            int address = word(bytes, at);
            int length = word(bytes, at + 2);
            int offset = pointer(bytes, at + 4);

            // "In case CurrPosition+Offset+Length > FileSize, decrease the
            // size to make it equal" - and the format's own author adds that
            // a file needing this is broken, and that there are many.
            if (offset < 0 || offset >= bytes.length) continue;
            length = Math.min(length, bytes.length - offset);

            // The same clamp at the other end: a block may not run past the
            // top of memory, which DeliAY handled by shortening it.
            length = Math.min(length, 0x10000 - address);
            if (length <= 0) continue;

            byte[] block = new byte[length];
            System.arraycopy(bytes, offset, block, 0, length);
            blocks.add(new Block(address, block));
        }

        return new Song(name, word(bytes, points), word(bytes, points + 2),
                        word(bytes, points + 4),
                        bytes[data + 8] & 0xff, bytes[data + 9] & 0xff, blocks);
    }

    // --- the awkward reading ---------------------------------------------------------

    /** Big-endian, because this format came by way of a 68000. */
    private static int word(byte[] bytes, int at) {
        return ((bytes[at] & 0xff) << 8) | (bytes[at + 1] & 0xff);
    }

    /**
     * A pointer: signed, and relative to where it was read from.
     *
     * The single most important line in this class. Read as an absolute
     * offset a pointer still lands inside a small file and produces
     * plausible rubbish - a song called nothing, a block of zeroes - rather
     * than failing, which is how this sort of mistake survives being tested.
     */
    private static int pointer(byte[] bytes, int at) {
        return at + (short) word(bytes, at);
    }

    /** A null-terminated string, in whatever the author's machine used -
     *  Latin-1 covers it, and cannot fail. */
    private static String string(byte[] bytes, int at) {
        if (at < 0 || at >= bytes.length) return "";

        int end = at;
        while (end < bytes.length && bytes[end] != 0) end++;

        return new String(bytes, at, end - at, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
