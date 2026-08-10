package dev.ldlab.zedex.library.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Reading a Spectrum screen out of 6912 bytes.
 *
 * On the JVM, because the whole of it is arithmetic and the arithmetic is the
 * part that goes wrong: the display is not stored as rows, and reading it as
 * though it were gives a recognisable picture sliced into eight interleaved
 * bands - which looks like a bug in whatever is drawing it rather than in
 * whatever decoded it.
 */
public class ScreenDumpTest {

    private static final int BLACK = 0xff000000;
    private static final int WHITE = 0xffc0c0c0;
    private static final int BRIGHT_WHITE = 0xffffffff;
    private static final int RED = 0xffc00000;
    private static final int BLUE = 0xff0000c0;

    /** All paper, no ink: white paper is attribute 0x38. */
    private static byte[] blank(int attribute) {
        byte[] screen = new byte[ScreenDump.SIZE];

        for (int at = 6144; at < ScreenDump.SIZE; at++) screen[at] = (byte) attribute;
        return screen;
    }

    private static int pixel(int[] screen, int x, int y) {
        return screen[y * ScreenDump.WIDTH + x];
    }

    // --- the layout ------------------------------------------------------------------

    /**
     * The address of each line, which is the whole trick.
     *
     * Written out for the boundaries that matter rather than computed the same
     * way twice: the first line of each third, and the step between pixel rows
     * against the step between character lines. Those two being the wrong way
     * round is the interleaving bug, and it is invisible in a formula that
     * simply mirrors the one under test.
     */
    @Test
    public void everyLineIsWhereTheHardwarePutIt() {
        assertEquals("the very first line", 0, ScreenDump.rowOffset(0));

        assertEquals("the second pixel row of the first character line",
                     256, ScreenDump.rowOffset(1));
        assertEquals("the second character line, top row",
                     32, ScreenDump.rowOffset(8));

        assertEquals("the second third", 2048, ScreenDump.rowOffset(64));
        assertEquals("the third third", 4096, ScreenDump.rowOffset(128));

        assertEquals("the very last line", 2048 * 2 + 256 * 7 + 32 * 7,
                     ScreenDump.rowOffset(191));
    }

    /** Every line lands somewhere different, and inside the pixel area. */
    @Test
    public void nolineOverlapsAnother() {
        boolean[] taken = new boolean[6144];

        for (int y = 0; y < ScreenDump.HEIGHT; y++) {
            int at = ScreenDump.rowOffset(y);

            assertTrue("line " + y + " is at " + at, at >= 0 && at + 32 <= 6144);
            assertFalse("two lines share " + at, taken[at]);
            taken[at] = true;
        }
    }

    // --- the pixels ------------------------------------------------------------------

    /** Ink where a bit is set, paper where it is not, and the high bit is the
     *  left-hand pixel. */
    @Test
    public void asetBitIsInkAndTheHighBitIsLeftmost() {
        byte[] screen = blank(0x38);            // white paper, black ink
        screen[ScreenDump.rowOffset(0)] = (byte) 0x80;

        int[] pixels = ScreenDump.decode(screen);

        assertEquals("the leftmost pixel of the top line", BLACK, pixel(pixels, 0, 0));
        assertEquals("its neighbour", WHITE, pixel(pixels, 1, 0));
        assertEquals("and the line below is untouched", WHITE, pixel(pixels, 0, 1));
    }

    /**
     * A byte written to the second character line appears on line 8, not
     * line 1.
     *
     * The interleave, asserted from the outside: this is the test that fails
     * when the two strides are swapped, and the pixel test above is not.
     */
    @Test
    public void thesecondCharacterLineIsEightRowsDown() {
        byte[] screen = blank(0x38);
        screen[32] = (byte) 0xff;

        int[] pixels = ScreenDump.decode(screen);

        assertEquals("row 8 should be inked", BLACK, pixel(pixels, 0, 8));
        assertEquals("row 1 should not be", WHITE, pixel(pixels, 0, 1));
    }

    /** And the thirds are 64 lines apart, not 2048 pixels apart. */
    @Test
    public void thesecondThirdStartsAtLineSixtyFour() {
        byte[] screen = blank(0x38);
        screen[2048] = (byte) 0xff;

        int[] pixels = ScreenDump.decode(screen);

        assertEquals(BLACK, pixel(pixels, 0, 64));
        assertEquals(WHITE, pixel(pixels, 0, 63));
    }

    // --- the colour ------------------------------------------------------------------

    /**
     * Ink is the low three bits, paper the next three, and bright applies to
     * both at once.
     *
     * 0x47 is bright, paper black, ink white - so a set bit is bright white
     * and a clear one is black. Getting bright wrong shows up only on the
     * eight colours that have a bright twin, which is every colour but black.
     */
    @Test
    public void inkPaperAndBrightAreReadFromTheAttribute() {
        byte[] screen = blank(0x47);
        screen[0] = (byte) 0x80;

        int[] pixels = ScreenDump.decode(screen);

        assertEquals(BRIGHT_WHITE, pixel(pixels, 0, 0));
        assertEquals(BLACK, pixel(pixels, 1, 0));
    }

    /** Two colours per cell, and the cell is 8x8 - the Spectrum's whole look,
     *  and the reason a screenshot of one is unmistakable. */
    @Test
    public void eachCellCarriesItsOwnPairOfColours() {
        byte[] screen = new byte[ScreenDump.SIZE];

        screen[6144] = 0x02;                    // black paper, red ink
        screen[6145] = 0x39;                    // white paper, blue ink

        for (int y = 0; y < 8; y++) {
            screen[ScreenDump.rowOffset(y)] = (byte) 0xff;
            screen[ScreenDump.rowOffset(y) + 1] = (byte) 0xff;
        }

        int[] pixels = ScreenDump.decode(screen);

        assertEquals("the first cell's ink", RED, pixel(pixels, 0, 0));
        assertEquals("the second cell's ink", BLUE, pixel(pixels, 8, 0));
        assertEquals("still the first cell seven rows down", RED, pixel(pixels, 7, 7));
    }

    /**
     * A flashing cell is drawn resting.
     *
     * Bit 7 swaps ink and paper twice a second on real hardware. A still
     * picture cannot do that, and picking the swapped state would store half
     * the "press any key" title screens in the archive inverted for ever.
     */
    @Test
    public void aflashingCellIsDrawnAsThoughItWereNot() {
        byte[] flashing = blank(0xb8);          // 0x38 with flash set
        byte[] resting = blank(0x38);

        flashing[0] = (byte) 0x80;
        resting[0] = (byte) 0x80;

        int[] one = ScreenDump.decode(flashing);
        int[] other = ScreenDump.decode(resting);

        assertEquals(BLACK, pixel(one, 0, 0));
        assertEquals(pixel(other, 0, 0), pixel(one, 0, 0));
        assertEquals(pixel(other, 1, 0), pixel(one, 1, 0));
    }

    // --- what is not one -------------------------------------------------------------

    /**
     * The length is the whole test of whether this is a screen.
     *
     * There is no header, no magic number and no version - which is why the
     * fetcher has to check the size before believing a file is one, and why a
     * truncated download reads as "not a screen" rather than as half a
     * picture.
     */
    @Test
    public void anythingThatIsNotSixNineOneTwoBytesIsNotAscreen() {
        assertTrue(ScreenDump.looksLikeOne(6912));
        assertFalse(ScreenDump.looksLikeOne(6911));
        assertFalse(ScreenDump.looksLikeOne(0));
        assertFalse(ScreenDump.looksLikeOne(6913));

        assertNull(ScreenDump.decode(null));
        assertNull(ScreenDump.decode(new byte[6911]));
        assertNotNull(ScreenDump.decode(new byte[6912]));
    }

    /** A screen of nothing is a screen of black, not a crash. */
    @Test
    public void anemptyScreenDecodesToBlack() {
        int[] pixels = ScreenDump.decode(new byte[ScreenDump.SIZE]);

        assertEquals(ScreenDump.WIDTH * ScreenDump.HEIGHT, pixels.length);
        assertEquals(BLACK, pixel(pixels, 0, 0));
        assertEquals(BLACK, pixel(pixels, 255, 191));
    }
}
