package dev.ldlab.zedex.library.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;

import java.io.InputStream;

/**
 * Decoded artwork, shared by {@link Scraped} and {@link Gallery} rather than
 * each holding a cache of its own that disagrees with the other's - a grid
 * tile, a list row, the pane, {@code GameInfoActivity} and {@code
 * MediaViewerActivity} all draw the same handful of files, and a tile's
 * decode sitting uselessly out of any gallery's reach was exactly what made
 * the details screen feel slow while the grid it was opened from was already
 * showing the same cover: see docs/LIBRARY.md and CLAUDE.md's own notes on
 * measuring before assuming.
 *
 * Keyed by the picture's {@code Uri} rather than a game's path, because that
 * is the thing every caller already has and the thing that is actually
 * expensive to read again - {@link Artwork} keeps its own cache of *which*
 * file answers a path, and this is the layer under it that keeps what was
 * decoded from whichever file that turned out to be.
 */
final class PictureCache {

    /**
     * One entry per {@code uri + target size} - a hit only when a caller
     * asks for a picture at the exact size it was decoded to before, which
     * is the case that actually saves a decode: swiping back to a page
     * already shown, or a fresh gallery reopened on the same game. Sized
     * the same fraction of the heap {@code Scraped}'s own cache uses, for
     * the same reason - see its comment - even though this one also holds
     * the details screen's and the viewer's much larger decodes, which is
     * exactly what {@link LruCache} eviction is for rather than a bigger
     * number guessed to fit every caller's own target size.
     */
    private static final LruCache<String, Bitmap> exact = new LruCache<String, Bitmap>(
            (int) Math.max(Runtime.getRuntime().maxMemory() / 8, 1)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /**
     * One entry per {@code uri}, whichever size was decoded to it most
     * recently - not here to save a decode, {@link #exact} already does
     * that, but so a bind that misses {@link #exact} has *something* to
     * draw at once rather than a blank box: the tile or the row this game
     * was opened from almost always decoded this same file moments earlier,
     * at its own smaller size, and showing that while the size actually
     * wanted decodes turns several seconds of grey into a sharpen. A small
     * budget on purpose - a placeholder is only ever a stand-in for a
     * moment, and the small sizes a tile or a row asks for are what usually
     * end up here.
     */
    private static final LruCache<String, Bitmap> latest = new LruCache<String, Bitmap>(
            (int) Math.max(Runtime.getRuntime().maxMemory() / 32, 1)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private PictureCache() {
    }

    /**
     * Drops every decoded picture.
     *
     * These are bounded already - an eighth of the heap and a thirty-second
     * of it - so this is not about a leak but about when the budget is worth
     * spending. Held after the library has gone away it is tens of megabytes
     * for a screen nobody is looking at, and the emulator, which is what is
     * usually in front of it, would rather have the memory.
     */
    static void forget() {
        exact.evictAll();
        latest.evictAll();
    }

    private static String key(Uri picture, int targetPx) {
        return picture.toString() + '@' + targetPx;
    }

    /** Whatever is cached for {@code picture} at exactly {@code targetPx} - null on a miss. */
    static Bitmap get(Uri picture, int targetPx) {
        return exact.get(key(picture, targetPx));
    }

    /** Whatever size of {@code picture} is cached, regardless of target - see {@link #latest}. */
    static Bitmap placeholder(Uri picture) {
        return latest.get(picture.toString());
    }

    /**
     * {@link #get}, decoding and caching on a miss - the same two-pass
     * sample-then-decode every picture in this app goes through, so a
     * scraped cover far larger than any box wants is never held at its own
     * resolution just to be scaled down on screen. Called off the UI
     * thread; a SAF read and a decode are both real work.
     */
    static Bitmap decode(Context context, Uri picture, int targetPx) {
        if (picture == null) return null;

        Bitmap cached = get(picture, targetPx);
        if (cached != null) return cached;

        Bitmap decoded = decodeFresh(context, picture, targetPx);
        if (decoded != null) put(picture, targetPx, decoded);
        return decoded;
    }

    private static void put(Uri picture, int targetPx, Bitmap bitmap) {
        exact.put(key(picture, targetPx), bitmap);
        latest.put(picture.toString(), bitmap);
    }

    private static Bitmap decodeFresh(Context context, Uri picture, int targetPx) {
        try (InputStream probe = context.getContentResolver().openInputStream(picture)) {
            if (probe == null) return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(probe, null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx);

            try (InputStream in = context.getContentResolver().openInputStream(picture)) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Throwable t) {
            // A corrupt file, a format BitmapFactory does not know, a grant
            // that died mid-read, or simply too large to fit in memory -
            // any of them means "no picture" here, not a crash; the same
            // reasoning Favorites.resolvable already uses for a foreign
            // provider answering unpredictably.
            return null;
        }
    }

    /**
     * The largest power-of-two downsample that still leaves at least {@code
     * targetPx} on the shorter side - {@code BitmapFactory}'s own {@code
     * inSampleSize} only understands powers of two, and rounding down keeps
     * a picture from coming out smaller than what is actually drawn.
     */
    private static int sampleSize(int width, int height, int targetPx) {
        if (targetPx <= 0) return 1;

        int shorter = Math.min(width, height);
        int sample = 1;
        while (shorter / (sample * 2) >= targetPx) {
            sample *= 2;
        }
        return sample;
    }
}
