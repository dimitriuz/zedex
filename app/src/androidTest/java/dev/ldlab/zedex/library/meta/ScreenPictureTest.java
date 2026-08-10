package dev.ldlab.zedex.library.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * A screen dump becoming a picture on disk.
 *
 * {@code ScreenDumpTest} covers the decoding, on the JVM, where the arithmetic
 * lives. What needs a device is the half that cannot be faked: that the PNG
 * written here is one {@code BitmapFactory} reads back - which is the whole
 * point of converting at all, since everything downstream of a fetch decodes
 * with it and would otherwise be handed 6912 bytes of nothing.
 */
@RunWith(AndroidJUnit4.class)
public class ScreenPictureTest {

    private Context context;
    private File folder;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        folder = new File(context.getCacheDir(), "uitest-screens");
        folder.mkdirs();
    }

    /**
     * A real screen, drawn by hand in the format the hardware used.
     *
     * Two character cells inked in different colours, which is enough to tell
     * a correct decode from one that has the strides swapped: the second cell
     * is eight rows down, not one.
     */
    private static byte[] screen() {
        byte[] bytes = new byte[ScreenDump.SIZE];

        for (int y = 0; y < 8; y++) bytes[ScreenDump.rowOffset(y)] = (byte) 0xff;
        for (int y = 8; y < 16; y++) bytes[ScreenDump.rowOffset(y)] = (byte) 0xff;

        bytes[6144] = 0x02;                          // black paper, red ink
        bytes[6144 + 32] = 0x39;                     // white paper, blue ink

        return bytes;
    }

    private File write(String name, byte[] bytes) throws IOException {
        File file = new File(folder, name);

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    /** The written file is a picture, of the size a Spectrum screen is, with
     *  the colours the decode says. */
    @Test
    public void adumpBecomesApngAnythingCanDecode() throws IOException {
        File dump = write("real.scr", screen());
        File into = new File(folder, "real.png");

        assertTrue("the conversion refused a real screen",
                   ScreenPicture.convert(dump, into));

        Bitmap bitmap = BitmapFactory.decodeFile(into.getAbsolutePath());
        assertNotNull("what was written is not a picture at all", bitmap);

        assertEquals(ScreenDump.WIDTH, bitmap.getWidth());
        assertEquals(ScreenDump.HEIGHT, bitmap.getHeight());

        assertEquals("the first cell's ink", 0xffc00000, bitmap.getPixel(0, 0));
        assertEquals("the second character line, eight rows down",
                     0xff0000c0, bitmap.getPixel(0, 8));

        bitmap.recycle();
    }

    /**
     * Anything that is not 6912 bytes is refused, and nothing is written.
     *
     * The length is the only check available - the format has no header to be
     * wrong - so a truncated download is a file of the wrong size and has to
     * be caught here or become a broken thumbnail that never heals.
     */
    @Test
    public void atruncatedDumpIsRefusedAndLeavesNothingBehind() throws IOException {
        File dump = write("short.scr", new byte[ScreenDump.SIZE - 1]);
        File into = new File(folder, "short.png");

        assertFalse(ScreenPicture.convert(dump, into));
        assertFalse("a refused conversion left a file behind", into.isFile());
    }

    /** And something that was never a screen at all - an HTML error page
     *  saved under the right name is the realistic case. */
    @Test
    public void somethingThatWasNeverAscreenIsRefused() throws IOException {
        File dump = write("page.scr", "<html>404</html>".getBytes());

        assertFalse(ScreenPicture.convert(dump, new File(folder, "page.png")));
    }
}
