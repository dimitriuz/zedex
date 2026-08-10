package dev.ldlab.zedex.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

/**
 * Reading an {@code .ay}, whose every pointer is a trap.
 *
 * The format came to the Spectrum by way of an Amiga music player, so its
 * words are big-endian and its pointers are <b>signed and relative to their
 * own position</b>. Both are the opposite of what everything else in this app
 * reads, and the second one is worse than the first: a relative pointer read
 * as absolute still lands inside a small file and yields a plausible-looking
 * song with an empty name and a block of zeroes, rather than failing.
 *
 * So the fixture below is built the way a real file is - see the shape taken
 * off {@code 007-LicenceToKillA.ay}, five songs sharing one driver, each
 * naming its tune in {@code HiReg}.
 */
public class AyFileTest {

    /** Where the header ends and this test starts placing things. */
    private static final int SONGS = 20;

    /**
     * A file with one song, laid out as the format wants it.
     *
     * Written by hand rather than borrowed, because the point is the
     * arithmetic: every pointer here is the distance from where it is stored
     * to what it points at, and getting one wrong is the failure being
     * guarded against.
     */
    private static byte[] file(int stack, int init, int interrupt,
                               int hiReg, int loReg, int blockAddress,
                               byte[] blockBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 0: the magic, then versions and the special-player pointer.
        out.writeBytes("ZXAYEMUL".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(0);                       // FileVersion
        out.write(3);                       // PlayerVersion
        word(out, 0);                       // PSpecialPlayer, at 10

        int author = 60, misc = 70, structure = SONGS;

        word(out, author - 12);             // PAuthor, at 12
        word(out, misc - 14);               // PMisc, at 14
        out.write(0);                       // NumOfSongs - 1: one song
        out.write(0);                       // FirstSong - 1
        word(out, structure - 18);          // PSongsStructure, at 18

        int name = 80, data = 100, points = 120, addresses = 130, blockAt = 140;

        // 20: the song structure - a name and its data.
        word(out, name - structure);
        word(out, data - (structure + 2));

        pad(out, 60);
        out.writeBytes("Somebody\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        pad(out, 70);
        out.writeBytes("(c) 1989\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        pad(out, 80);
        out.writeBytes("A Tune\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        // 100: the song data.
        pad(out, data);
        out.write(0); out.write(1); out.write(2); out.write(3);   // channels
        word(out, 0);                       // SongLength
        word(out, 0);                       // FadeLength
        out.write(hiReg);
        out.write(loReg);
        word(out, points - (data + 10));
        word(out, addresses - (data + 12));

        // 120: the points, and 130: one block then the end word.
        pad(out, points);
        word(out, stack);
        word(out, init);
        word(out, interrupt);

        pad(out, addresses);
        word(out, blockAddress);
        word(out, blockBytes.length);
        word(out, blockAt - (addresses + 4));
        word(out, 0);                       // the end of the blocks

        pad(out, blockAt);
        out.writeBytes(blockBytes);

        return out.toByteArray();
    }

    private static void word(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xff);     // big-endian, unlike everything else
        out.write(value & 0xff);
    }

    private static void pad(ByteArrayOutputStream out, int to) {
        while (out.size() < to) out.write(0);
    }

    private static AyFile.Song only(byte[] bytes) {
        AyFile file = AyFile.read(bytes);
        assertNotNull("the file would not read at all", file);
        assertEquals(1, file.songs.size());

        return file.songs.get(0);
    }

    // --- the whole of it -------------------------------------------------------------

    @Test
    public void asongIsReadWithItsNamePointsAndBlock() {
        byte[] block = { 1, 2, 3, 4 };
        AyFile file = AyFile.read(file(0x8000, 0xec6e, 0xec65, 4, 0, 0xec60, block));

        assertEquals("Somebody", file.author);
        assertEquals("(c) 1989", file.misc);
        assertEquals(0, file.first);

        AyFile.Song song = file.songs.get(0);
        assertEquals("A Tune", song.name);
        assertEquals(0x8000, song.stack);
        assertEquals(0xec6e, song.init);
        assertEquals(0xec65, song.interrupt);
        assertEquals(4, song.hiReg);
        assertEquals(0, song.loReg);

        assertEquals(1, song.blocks.size());
        assertEquals(0xec60, song.blocks.get(0).address);
        assertEquals(4, song.blocks.get(0).bytes.length);
        assertEquals(3, song.blocks.get(0).bytes[2]);
    }

    /**
     * The pointers are relative, and this is the test that says so.
     *
     * Every pointer in the fixture is a distance rather than a position, so
     * a reader that treats them as absolute finds the wrong bytes for the
     * name, the points and the block all at once. Naming the addresses here
     * is what makes that failure loud instead of plausible.
     */
    @Test
    public void thepointersAreRelativeToWhereTheyAreStored() {
        byte[] bytes = file(0x8000, 0xec6e, 0xec65, 0, 0, 0xc000, new byte[] { 9 });

        // The author pointer sits at 12 and holds 48, because the string is
        // at 60. Read as an absolute offset it would point at the song
        // structure and read as an empty name.
        assertEquals(48, ((bytes[12] & 0xff) << 8) | (bytes[13] & 0xff));
        assertEquals("Somebody", AyFile.read(bytes).author);
    }

    /** Big-endian words, which is the other half of the same inheritance. */
    @Test
    public void thewordsAreBigEndian() {
        byte[] bytes = file(0x1234, 0xec6e, 0xec65, 0, 0, 0xc000, new byte[] { 9 });

        assertEquals("the stack word was read the wrong way round",
                     0x1234, only(bytes).stack);
    }

    // --- files that are not, or barely, files ------------------------------------------

    @Test
    public void somethingThatIsNotAnAyFileIsRefused() {
        assertNull(AyFile.read(null));
        assertNull(AyFile.read(new byte[0]));
        assertNull(AyFile.read("not a tune at all".getBytes()));
        assertNull("the type id is checked as well as the file id",
                   AyFile.read("ZXAYNOPE------------".getBytes()));
    }

    /**
     * A truncated file answers null rather than throwing.
     *
     * These are thirty years old and passed through several formats; the
     * format's own author says outright that broken ones are common. Half a
     * file is a tune that will not play, which is a thing to report and not a
     * crash to propagate.
     */
    @Test
    public void abrokenFileIsRefusedRatherThanThrowing() {
        byte[] whole = file(0x8000, 0xec6e, 0xec65, 0, 0, 0xc000, new byte[] { 1, 2 });

        for (int length = 20; length < whole.length; length += 7) {
            byte[] cut = new byte[length];
            System.arraycopy(whole, 0, cut, 0, length);

            AyFile.read(cut);               // must not throw; may be null
        }
    }

    /**
     * A block is clamped to the top of memory rather than wrapping.
     *
     * The format's own note: DeliAY shortened a block whose address plus
     * length passed 65536, and files that need it exist. Wrapping instead
     * would write the end of a driver over the bottom of the machine.
     */
    @Test
    public void ablockThatWouldRunPastTheTopOfMemoryIsShortened() {
        byte[] block = new byte[32];
        AyFile.Song song = only(file(0, 0xc000, 0, 0, 0, 0xfff0, block));

        assertEquals(1, song.blocks.size());
        assertEquals("the block should stop at #FFFF",
                     16, song.blocks.get(0).bytes.length);
    }

    /** And one whose data runs off the end of the file is shortened too,
     *  rather than read past it. */
    @Test
    public void ablockThatRunsPastTheEndOfTheFileIsShortened() {
        byte[] whole = file(0, 0xc000, 0, 0, 0, 0xc000, new byte[] { 1, 2, 3, 4 });

        byte[] cut = new byte[whole.length - 2];
        System.arraycopy(whole, 0, cut, 0, cut.length);

        AyFile file = AyFile.read(cut);
        assertNotNull(file);
        assertTrue("nothing should have been read past the end",
                   file.songs.get(0).blocks.get(0).bytes.length <= 2);
    }

    // --- against a real one ----------------------------------------------------------

    /**
     * A file off the archive, read whole.
     *
     * The fixtures above are this test's own idea of the format; this is
     * somebody else's, made in 2000 and shipped ever since. Five songs
     * sharing one driver, each naming its tune in {@code HiReg} - which is
     * what that field turns out to be for in practice, whatever the
     * specification calls it.
     */
    @Test
    public void arealFileFromTheArchiveReads() throws Exception {
        byte[] bytes;

        try (java.io.InputStream in = AyFileTest.class.getClassLoader()
                .getResourceAsStream("licence-to-kill.ay")) {
            assertNotNull("the fixture is missing", in);
            bytes = in.readAllBytes();
        }

        AyFile file = AyFile.read(bytes);
        assertNotNull("a real .ay would not read", file);

        assertEquals("(c) Domark 1989", file.misc);
        assertEquals(5, file.songs.size());

        for (int at = 0; at < file.songs.size(); at++) {
            AyFile.Song song = file.songs.get(at);

            assertEquals("Licence To Kill", song.name);
            assertEquals(0xec6e, song.init);
            assertEquals(0xec65, song.interrupt);
            assertEquals("the tune number is what HiReg carries here",
                         at, song.hiReg);

            assertEquals(1, song.blocks.size());
            assertEquals(0xec60, song.blocks.get(0).address);
            assertEquals(4508, song.blocks.get(0).bytes.length);
        }
    }
}
