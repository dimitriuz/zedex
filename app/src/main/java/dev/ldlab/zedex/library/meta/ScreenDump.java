package dev.ldlab.zedex.library.meta;

/**
 * A {@code .scr} - 6912 bytes of Spectrum screen memory - as pixels.
 *
 * The one kind of picture in a scraped record that only this app can show. A
 * third of ZXDB's Spectrum entries carry their loading screen as a raw memory
 * dump and nothing else: 13,115 of 39,666, against 8,201 with a front inlay.
 * Every other scraper skips them, because to anything that is not a Spectrum
 * they are 6912 bytes of nothing.
 *
 * <b>The layout is the interesting part, and it is not a bitmap.</b> Sinclair
 * wired the display so that a line's address could be worked out with shifts
 * rather than a multiply, and the result is that consecutive addresses are not
 * consecutive lines. The screen is three thirds of 64 lines; within a third,
 * the eight pixel rows of a character line are 256 bytes apart, and the eight
 * character lines are 32 bytes apart. So the byte at 0x0020 is the top row of
 * the second character line, and the one at 0x0100 is the <em>second</em>
 * pixel row of the first. Reading it as 192 rows of 32 bytes - which is the
 * obvious thing, and what a first attempt does - produces a recognisable
 * picture sliced into eight interleaved bands, which is exactly wrong enough
 * to look like a decoding bug somewhere else.
 *
 * Colour is separate and coarser: one attribute byte per 8x8 cell, holding an
 * ink and a paper, so a cell can only ever be two colours. That is the
 * Spectrum's whole look.
 *
 * No Android in here, which is the point - the arithmetic above is worth
 * testing without a device. {@code ScreenPicture} makes the bitmap.
 */
public final class ScreenDump {

    private ScreenDump() {
    }

    public static final int WIDTH = 256;
    public static final int HEIGHT = 192;

    /** 6144 bytes of pixels then 768 of colour, and nothing else - a file of
     *  any other length is not one of these. */
    private static final int PIXELS = WIDTH * HEIGHT / 8;
    private static final int ATTRIBUTES = WIDTH * HEIGHT / 64;
    public static final int SIZE = PIXELS + ATTRIBUTES;

    /**
     * The sixteen colours, as this app already draws them.
     *
     * Taken from {@code native/ui/android/android_display.c} rather than from
     * a reference: a loading screen shown beside a screenshot of the same game
     * running has to be the same colours, and 192/255 is the pair that port
     * chose. Bright black is black twice, which is the hardware's own answer.
     */
    private static final int[] COLOURS = {
        0xff000000, 0xff0000c0, 0xffc00000, 0xffc000c0,
        0xff00c000, 0xff00c0c0, 0xffc0c000, 0xffc0c0c0,
        0xff000000, 0xff0000ff, 0xffff0000, 0xffff00ff,
        0xff00ff00, 0xff00ffff, 0xffffff00, 0xffffffff,
    };

    /** Whether this could be a screen at all. Length is the whole test: the
     *  format has no header, no magic and no version. */
    public static boolean looksLikeOne(long length) {
        return length == SIZE;
    }

    /**
     * The screen as {@link #WIDTH}x{@link #HEIGHT} pixels, row by row, or
     * null when {@code bytes} is not a screen.
     *
     * <b>Flashing attributes are drawn in their resting state</b> - ink as
     * ink, paper as paper. A still picture has no other option, and half of
     * these are a title screen with one flashing "press any key" on it, which
     * would otherwise be captured mid-blink and stored inverted for ever.
     */
    public static int[] decode(byte[] bytes) {
        if (bytes == null || bytes.length < SIZE) return null;

        int[] pixels = new int[WIDTH * HEIGHT];

        for (int y = 0; y < HEIGHT; y++) {
            int row = rowOffset(y);
            int cellRow = (y >> 3) * (WIDTH / 8);

            for (int cell = 0; cell < WIDTH / 8; cell++) {
                int bits = bytes[row + cell] & 0xff;
                int attribute = bytes[PIXELS + cellRow + cell] & 0xff;

                int ink = COLOURS[(attribute & 0x07) | ((attribute & 0x40) >> 3)];
                int paper = COLOURS[((attribute >> 3) & 0x07) | ((attribute & 0x40) >> 3)];

                for (int bit = 0; bit < 8; bit++) {
                    // The high bit is the leftmost pixel, which is the one
                    // thing about this format that is not surprising.
                    boolean set = (bits & (0x80 >> bit)) != 0;
                    pixels[y * WIDTH + cell * 8 + bit] = set ? ink : paper;
                }
            }
        }

        return pixels;
    }

    /**
     * Where a screen line's 32 bytes begin.
     *
     * The address arithmetic Sinclair chose so the hardware needed no
     * multiplier: the third, then the pixel row within a character line, then
     * the character line within the third. See the class comment for what
     * getting this wrong looks like.
     */
    static int rowOffset(int y) {
        int third = (y >> 6) & 0x03;
        int pixelRow = y & 0x07;
        int characterLine = (y >> 3) & 0x07;

        return third * 2048 + pixelRow * 256 + characterLine * 32;
    }
}
