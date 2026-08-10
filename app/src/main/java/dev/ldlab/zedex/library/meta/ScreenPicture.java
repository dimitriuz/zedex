package dev.ldlab.zedex.library.meta;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * A downloaded {@code .scr}, turned into a picture the rest of the app can
 * draw.
 *
 * <b>Converted on the way in, not on the way out.</b> A screen dump could have
 * been kept as it arrived and decoded whenever something wanted to draw it,
 * and that would have meant teaching {@code Artwork} a third extension, {@code
 * PictureCache} a second decoder, and every future caller that a picture is
 * not always a picture. Writing a PNG once at fetch time costs a few
 * milliseconds and leaves every one of those untouched: what lands in {@code
 * titlescreens} is a png, the same as ES-DE's own.
 *
 * The bytes are not kept. They are recoverable from the archive whenever
 * anybody wants them, the PNG is smaller than the dump for most screens, and a
 * second file per game that nothing reads is clutter with a maintenance bill.
 */
public final class ScreenPicture {

    private static final String TAG = "Zedex";

    private ScreenPicture() {
    }

    /** What a converted screen is written as - see the class comment. */
    public static final String EXTENSION = "png";

    /**
     * Reads {@code dump}, writes {@code into}, and says whether that worked.
     *
     * False for anything that is not a screen, which is the only check there
     * is: the format has no header to be wrong, so a truncated download is
     * simply a file of the wrong length and has to be refused on that alone.
     */
    public static boolean convert(File dump, File into) {
        if (dump == null || into == null) return false;

        if (!ScreenDump.looksLikeOne(dump.length())) {
            Log.w(TAG, dump.getName() + " is " + dump.length()
                       + " bytes, and a screen is " + ScreenDump.SIZE);
            return false;
        }

        int[] pixels;

        try {
            pixels = ScreenDump.decode(Files.readAllBytes(dump.toPath()));
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + dump, e);
            return false;
        }

        if (pixels == null) return false;

        Bitmap bitmap = Bitmap.createBitmap(pixels, ScreenDump.WIDTH, ScreenDump.HEIGHT,
                                            Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(into)) {
            // PNG and not JPEG: this is sixteen flat colours in 8x8 blocks,
            // which PNG stores in a couple of kilobytes and JPEG turns into a
            // haze of ringing around every hard edge - and a Spectrum screen
            // is nothing but hard edges.
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + into, e);
            return false;
        } finally {
            bitmap.recycle();
        }
    }
}
