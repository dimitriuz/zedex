package dev.ldlab.zedex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Pictures and films of the emulated screen.
 *
 * The screen itself cannot be reached from a test — it is a GL surface — so
 * what is checked is the file: that a screenshot really is a PNG the size the
 * machine is drawing, and that a recording really is a GIF or an MP4 with
 * frames in it.
 */
@RunWith(AndroidJUnit4.class)
public class CaptureTest {

    /** A 48K machine draws this, borders included. */
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;

    /** Long enough for a second or so of frames at 50Hz. */
    private static final long RECORD_FOR = 3 * Emulator.SECOND;

    /** A recording is not finished when Stop is tapped, only asked to finish. */
    private static final long FINISHING = 5 * Emulator.SECOND;

    private final Emulator emulator = new Emulator();

    private File screenshots;
    private File recordings;

    @Before
    public void setUp() {
        emulator.useDataFolder();
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());

        screenshots = Storage.screenshotsDirectory(emulator.context());
        recordings = Storage.recordingsDirectory(emulator.context());

        clear(screenshots);
        clear(recordings);
    }

    @Test
    public void savesTheScreenAsAPng() {
        emulator.menu("Capture", "Save screenshot");
        emulator.idle(3 * Emulator.SECOND);

        File png = onlyFile(screenshots);

        BitmapFactory.Options size = new BitmapFactory.Options();
        size.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(png.getAbsolutePath(), size);

        assertEquals("not a PNG", "image/png", size.outMimeType);
        assertEquals("width", WIDTH, size.outWidth);
        assertEquals("height", HEIGHT, size.outHeight);
    }

    @Test
    public void recordsAGif() throws IOException {
        File gif = record("Record a GIF");
        byte[] image = Files.readAllBytes(gif.toPath());

        assertEquals("not a GIF", "GIF89a", text(image, 0, 6));
        assertEquals("width", WIDTH, littleEndianShort(image, 6));
        assertEquals("height", HEIGHT, littleEndianShort(image, 8));

        // A global colour table of sixteen entries, which is the whole
        // Spectrum palette and the reason nothing has to be quantised.
        assertEquals("global colour table of sixteen", 0xb3, image[10] & 0xff);
        assertTrue("an animation needs more than one frame", frames(image) > 1);
    }

    @Test
    public void recordsAnMp4() throws IOException {
        File mp4 = record("Record an MP4");

        MediaMetadataRetriever media = new MediaMetadataRetriever();
        try {
            media.setDataSource(mp4.getAbsolutePath());

            assertEquals("width", String.valueOf(WIDTH),
                    media.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            assertEquals("height", String.valueOf(HEIGHT),
                    media.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));

            String duration = media.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            assertNotNull("no duration, so no video track", duration);
            assertTrue("barely any of it was recorded: " + duration + "ms",
                       Long.parseLong(duration) > 500);
        } finally {
            media.release();
        }
    }

    private File record(String item) {
        emulator.menu("Capture", item);
        emulator.idle(RECORD_FOR);

        emulator.menu("Capture", "Stop recording");
        emulator.idle(FINISHING);

        return onlyFile(recordings);
    }

    /**
     * Counts the frames by walking the file's blocks, which also proves the
     * blocks are well formed: a byte-counting shortcut would happily count
     * 0x2c bytes inside the compressed data and could not fail.
     */
    private static int frames(byte[] gif) {
        int at = 13 + 16 * 3;                         // header, then the palette
        int count = 0;

        while (at < gif.length) {
            int block = gif[at++] & 0xff;

            if (block == 0x3b) break;                 // trailer

            if (block == 0x21) {                      // extension
                at++;                                 // its label
                at = skipSubBlocks(gif, at);
                continue;
            }

            assertEquals("unknown block at " + (at - 1), 0x2c, block);

            at += 9;                                  // image descriptor
            assertEquals("local colour tables are not written",
                         0, gif[at - 1] & 0x80);
            at++;                                     // LZW minimum code size
            at = skipSubBlocks(gif, at);
            count++;
        }

        return count;
    }

    private static int skipSubBlocks(byte[] gif, int at) {
        while (at < gif.length) {
            int length = gif[at++] & 0xff;
            if (length == 0) break;
            at += length;
        }
        return at;
    }

    private static void clear(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            assertTrue("cannot clear " + file, file.delete());
        }
    }

    private static File onlyFile(File directory) {
        File[] files = directory.listFiles();

        assertNotNull("nothing was written to " + directory, files);
        assertEquals("expected one file in " + directory, 1, files.length);
        assertTrue("the file is empty", files[0].length() > 0);

        return files[0];
    }

    private static String text(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private static int littleEndianShort(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }
}
