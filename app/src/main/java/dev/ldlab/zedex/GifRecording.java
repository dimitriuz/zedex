package dev.ldlab.zedex;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Writes frames straight out as an animated GIF.
 *
 * A GIF is a palette format and so is the Spectrum: sixteen colours, no more,
 * which is exactly what a global colour table holds. Nothing has to be
 * quantised or dithered, and the frame Fuse hands over is already the array
 * of indices the format wants — the pixels go in untouched.
 *
 * Frames are written as they arrive rather than collected first, so a long
 * recording costs no more memory than a short one. The one exception is that
 * a frame is held back until the next arrives: a GIF frame carries how long
 * it stays on screen, and that is only known once something replaces it.
 */
final class GifRecording implements Recording {

    /** Delays are in hundredths of a second, so this is the useful ceiling. */
    static final long INTERVAL = 40_000_000L;                    // 25 fps

    /** What the last frame of all gets, having nothing to follow it. */
    private static final int FINAL_DELAY = 4;

    private final File file;
    private final int[] palette;
    private final OutputStream out;

    private int width;
    private int height;

    private byte[] held;
    private long heldAt;
    private boolean started;

    GifRecording(File file, int[] palette) throws IOException {
        this.file = file;
        this.palette = palette;
        this.out = new BufferedOutputStream(new FileOutputStream(file), 64 * 1024);
    }

    @Override
    public File file() {
        return file;
    }

    @Override
    public long minimumIntervalNanos() {
        return INTERVAL;
    }

    @Override
    public void frame(byte[] pixels, int frameWidth, int frameHeight, int stride,
                      long timestampNanos) throws IOException {

        if (!started) {
            width = frameWidth;
            height = frameHeight;
            writeHeader();
            started = true;
        }

        // The machine can change resolution part way through — a Timex hi-res
        // mode is twice the size. A GIF cannot, so those frames are skipped
        // rather than written at the wrong size.
        if (frameWidth != width || frameHeight != height) return;

        if (held != null) {
            writeFrame(held, stride, centiseconds(timestampNanos - heldAt));
        }

        if (held == null || held.length < pixels.length) held = new byte[pixels.length];
        System.arraycopy(pixels, 0, held, 0, pixels.length);
        heldAt = timestampNanos;
        heldStride = stride;
    }

    private int heldStride;

    @Override
    public void close() throws IOException {
        try {
            if (held != null) writeFrame(held, heldStride, FINAL_DELAY);
            if (started) out.write(0x3b);                     // trailer
        } finally {
            out.close();
        }

        if (!started) throw new IOException("nothing was recorded");
    }

    // --- the format -------------------------------------------------------

    private void writeHeader() throws IOException {
        for (char c : "GIF89a".toCharArray()) out.write(c);

        writeShort(width);
        writeShort(height);
        // Global colour table present, 4 bits a pixel, sixteen entries.
        out.write(0x80 | 0x30 | 0x03);
        out.write(0);                                         // background
        out.write(0);                                         // aspect ratio

        for (int colour : palette) {
            out.write(colour & 0xff);                         // 0xAABBGGRR
            out.write((colour >> 8) & 0xff);
            out.write((colour >> 16) & 0xff);
        }

        // Netscape's application extension, the only way to say "loop".
        out.write(0x21);
        out.write(0xff);
        out.write(11);
        for (char c : "NETSCAPE2.0".toCharArray()) out.write(c);
        out.write(3);
        out.write(1);
        writeShort(0);                                        // forever
        out.write(0);
    }

    private void writeFrame(byte[] pixels, int stride, int delay) throws IOException {
        out.write(0x21);                                      // graphic control
        out.write(0xf9);
        out.write(4);
        out.write(0);                                         // no disposal
        writeShort(delay);
        out.write(0);                                         // no transparency
        out.write(0);

        out.write(0x2c);                                      // image descriptor
        writeShort(0);
        writeShort(0);
        writeShort(width);
        writeShort(height);
        out.write(0);                                         // no local table

        new Lzw(out).compress(pixels, width, height, stride);
    }

    private static int centiseconds(long nanos) {
        int hundredths = (int) Math.round(nanos / 10_000_000.0);
        return Math.max(2, Math.min(hundredths, 0xffff));
    }

    private void writeShort(int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    // --- LZW --------------------------------------------------------------

    /**
     * GIF's variable-width LZW, as the format defines it: codes start one bit
     * wider than the four bits a pixel takes, grow to twelve, and the table is
     * cleared and begun again when it fills.
     */
    private static final class Lzw {

        private static final int BITS = 12;
        private static final int TABLE_SIZE = 5003;           // ~80% occupancy

        private final OutputStream out;

        private final int[] hash = new int[TABLE_SIZE];
        private final int[] codes = new int[TABLE_SIZE];

        private final byte[] block = new byte[255];
        private int blockLength;

        private int bits, bitCount;
        private int codeSize, maxCode, next;
        private int clearCode, endCode;

        Lzw(OutputStream out) {
            this.out = out;
        }

        void compress(byte[] pixels, int width, int height, int stride)
                throws IOException {

            final int pixelBits = 4;                          // sixteen colours

            out.write(pixelBits);

            clearCode = 1 << pixelBits;
            endCode = clearCode + 1;
            resetTable();

            emit(clearCode);

            int prefix = pixels[0] & 0xff;

            for (int y = 0; y < height; y++) {
                int row = y * stride;

                for (int x = y == 0 ? 1 : 0; x < width; x++) {
                    int pixel = pixels[row + x] & 0xff;
                    int entry = (pixel << BITS) + prefix;
                    int slot = (pixel << (BITS - 8)) ^ prefix;
                    int step = slot == 0 ? 1 : TABLE_SIZE - slot;

                    boolean known = false;
                    while (hash[slot] >= 0) {
                        if (hash[slot] == entry) {
                            prefix = codes[slot];
                            known = true;
                            break;
                        }
                        slot -= step;
                        if (slot < 0) slot += TABLE_SIZE;
                    }
                    if (known) continue;

                    emit(prefix);
                    prefix = pixel;

                    if (next < (1 << BITS)) {
                        codes[slot] = next++;
                        hash[slot] = entry;
                    } else {
                        emit(clearCode);
                        resetTable();
                    }
                }
            }

            emit(prefix);
            emit(endCode);
            flushBits();
            endBlocks();
        }

        private void resetTable() {
            Arrays.fill(hash, -1);
            codeSize = 5;
            maxCode = (1 << codeSize) - 1;
            next = endCode + 1;
        }

        private void emit(int code) throws IOException {
            bits |= code << bitCount;
            bitCount += codeSize;

            while (bitCount >= 8) {
                addToBlock(bits & 0xff);
                bits >>>= 8;
                bitCount -= 8;
            }

            if (next > maxCode && codeSize < BITS) {
                codeSize++;
                maxCode = (1 << codeSize) - 1;
            }
        }

        private void flushBits() throws IOException {
            while (bitCount > 0) {
                addToBlock(bits & 0xff);
                bits >>>= 8;
                bitCount -= 8;
            }
            bits = 0;
            bitCount = 0;
        }

        /** Image data travels in sub-blocks of at most 255 bytes. */
        private void addToBlock(int value) throws IOException {
            block[blockLength++] = (byte) value;
            if (blockLength == 255) writeBlock();
        }

        private void writeBlock() throws IOException {
            if (blockLength == 0) return;

            out.write(blockLength);
            out.write(block, 0, blockLength);
            blockLength = 0;
        }

        private void endBlocks() throws IOException {
            writeBlock();
            out.write(0);
        }
    }
}
