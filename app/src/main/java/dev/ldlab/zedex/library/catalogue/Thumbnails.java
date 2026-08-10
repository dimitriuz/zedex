package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.work.Work;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Covers for the rows of the catalogue that are on screen, and only those.
 *
 * A row's picture is a url on an ordinary web host - {@code
 * Catalogue.Item.pictureUrl()} - and this is what turns that into a decoded
 * {@link Bitmap} without a grid asking twice for the same one or the app
 * asking for one no row wants any more. Two things this deliberately is not:
 * a queue, since nothing here is asked before its row is on screen, and a
 * client of {@code Pace} - see CLAUDE.md, "Every ScreenScraper medium is a
 * request; every ZXInfo medium is free" - these are static files, not API
 * calls, and pacing them the way {@code Pace} paces {@code api.zxinfo.dk}
 * would make the grid unusable for nothing bought in return. They still
 * carry the identity header, because {@link Http.Real} sends it on
 * everything, static file or not.
 *
 * <b>One request per url, however many rows ask.</b> A grid flung past a row
 * and back is the ordinary case, not an edge case, and doubling every fetch
 * for it is exactly the behaviour pattern that got this app's own address
 * blocked once. {@link #inFlight} is what makes a second caller join the
 * first rather than start another - see its own comment for why the check
 * and the start have to be one atomic step rather than two, or the guarantee
 * is luck rather than a proof.
 *
 * Bounded the same way {@code PictureCache} is - an {@link LruCache} sized
 * from a fraction of this process's own heap, evicting the least-recently
 * used cover once it is full, rather than a count of rows guessed to be
 * enough for every screen size this runs on.
 */
public final class Thumbnails {

    private Thumbnails() {
    }

    /** Told once a cover has arrived - or has not, in which case {@code
     *  picture} is null and the row keeps whatever placeholder it already
     *  draws. Never told twice for the same {@link #load} call. */
    public interface Listener {
        void ready(String url, Bitmap picture);
    }

    /**
     * The row this is decoded for, in dp.
     *
     * A full-size cover scan decoded for anything bigger than the row that
     * draws it is the measured mistake CLAUDE.md keeps: 1.9 GB and 663
     * threads once, from an {@code ImageView} nobody sized before decoding
     * into it. 140 to match the grid row {@code EntryAdapter} already
     * decodes to - the two are drawn at the same size and there is no
     * reason for this one to guess a different number.
     */
    private static final int TARGET_DP = 140;

    /**
     * One entry per url, sized the same fraction of the heap {@code
     * PictureCache} and {@code Scraped} use, for the same reason: this is
     * one of several caches sharing the process's memory rather than the
     * only claim on it, and the emulator - almost always what is running
     * behind the library - would rather have a large budget go unused than
     * a small one force a decode to happen twice.
     */
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) Math.max(Runtime.getRuntime().maxMemory() / 8, 1)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /**
     * Every url a fetch is currently running for, and who is waiting to be
     * told when it finishes. The key's presence is the only fact that
     * decides "start a fetch" from "join the one already running" - so the
     * check and the start are done inside the same block synchronized on
     * this map, never as two separate steps a second thread could land
     * between.
     *
     * The same lock also guards writing the answer: {@link #fetch} puts the
     * finished bitmap into {@link #cache} and removes this map's entry
     * inside one more synchronized block, so a caller can never observe
     * "not cached, and nobody is fetching it" while an answer is on its way
     * in but has not been filed yet - the one interleaving that would let a
     * second, needless fetch start. That is what makes "one request per
     * url" a guarantee rather than something that happens to hold on a
     * loaded emulator and stops holding on a fast one.
     */
    private static final Map<String, List<Listener>> inFlight = new HashMap<>();

    /**
     * Whatever is cached for {@code url} - null on a miss. Cache only, and
     * never blocks: a row binds this while the recycler is laying out and
     * must not wait on a network read to draw itself. A miss here is
     * ordinary, not an error - the row draws its placeholder and {@link
     * #load} is what actually goes and gets one.
     */
    public static Bitmap get(String url) {
        if (url == null || url.isEmpty()) return null;
        return cache.get(url);
    }

    /**
     * {@link #get}, fetching on a miss.
     *
     * A null or empty {@code url} is not a request - plenty of catalogue
     * entries have no picture at all, and those rows are text rows; asking
     * for nothing here would only be a wasted round trip through {@link
     * Work}. A cache hit answers {@code listener} at once, on the calling
     * thread, exactly as {@code Scraped.picture} already does; a miss
     * fetches on {@link Work#run} and answers through {@link
     * Work#onMain}, so a caller never has to guess which thread it will be
     * told on.
     */
    public static void load(Context context, Http http, String url, Listener listener) {
        if (url == null || url.isEmpty()) return;

        Bitmap cached;
        boolean start = false;

        synchronized (inFlight) {
            cached = cache.get(url);
            if (cached == null) {
                List<Listener> waiting = inFlight.get(url);
                if (waiting == null) {
                    waiting = new ArrayList<>();
                    inFlight.put(url, waiting);
                    start = true;
                }
                waiting.add(listener);
            }
        }

        if (cached != null) {
            listener.ready(url, cached);
            return;
        }

        if (!start) return; // somebody else is already fetching this url

        Context app = context.getApplicationContext();
        int targetPx = pixels(app, TARGET_DP);

        Work.run("thumbnail", () -> fetch(app, http, url, targetPx));
    }

    /**
     * Drops every cover held.
     *
     * A fetch already in flight is not stopped by this - nothing here can
     * reach into {@link Http} mid-read - and still tells whoever is waiting
     * on it once it finishes; only the cache entry it would otherwise have
     * filled is gone, so the next {@link #get} still misses and the next
     * {@link #load} fetches again. Used by the test to start each case with
     * nothing cached from the one before, the same reason {@code
     * PictureCache.forget} exists.
     */
    public static void forget() {
        synchronized (inFlight) {
            inFlight.clear();
        }
        cache.evictAll();
    }

    private static void fetch(Context context, Http http, String url, int targetPx) {
        Bitmap picture = null;
        File file = new File(context.getCacheDir(), "thumbnail-" + System.nanoTime());

        try {
            http.save(url, file);
            picture = decode(file, targetPx);
        } catch (Exception e) {
            // A 404, a timeout, a corrupt file, a format BitmapFactory does
            // not know - any of them means "no picture" here, the same
            // reasoning PictureCache.decodeFresh already uses for a
            // provider answering unpredictably.
            picture = null;
        } finally {
            file.delete();
        }

        List<Listener> waiting;
        synchronized (inFlight) {
            if (picture != null) cache.put(url, picture);
            waiting = inFlight.remove(url);
        }

        Bitmap result = picture;
        if (waiting != null) {
            for (Listener listener : waiting) {
                Work.onMain(() -> listener.ready(url, result));
            }
        }
    }

    private static Bitmap decode(File file, int targetPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx);
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    /** Same reasoning as {@code PictureCache.sampleSize}: the largest
     *  power-of-two downsample that still leaves at least {@code targetPx}
     *  on the shorter side, since {@code BitmapFactory}'s own {@code
     *  inSampleSize} only understands powers of two. */
    private static int sampleSize(int width, int height, int targetPx) {
        if (targetPx <= 0) return 1;

        int shorter = Math.min(width, height);
        int sample = 1;
        while (shorter / (sample * 2) >= targetPx) {
            sample *= 2;
        }
        return sample;
    }

    private static int pixels(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
