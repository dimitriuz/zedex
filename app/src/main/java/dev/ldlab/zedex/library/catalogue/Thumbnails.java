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
 * is luck rather than a proof - and for why every listener that joins is
 * told exactly once, whatever the fetch actually does.
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
     *  draws. Told exactly once for the same {@link #load} call. */
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
     * Urls that were asked for and answered with nothing - a 404, a
     * timeout, a file {@code BitmapFactory} could not decode - remembered
     * so that scrolling the same row off screen and back does not repeat a
     * request already known to fail. The same shape {@code Artwork} uses,
     * caching a miss as {@code Uri.EMPTY} rather than re-deriving it every
     * time it is asked; see CLAUDE.md on this very address having been
     * blocked once for exactly this pattern of repeated requests for
     * nothing. Bounded by entry count rather than by heap fraction the way
     * {@link #cache} is - a remembered failure costs a few bytes, not a
     * decoded bitmap, so sizing it off memory pressure would answer a
     * question this map does not raise.
     */
    private static final LruCache<String, Boolean> failed = new LruCache<>(512);

    /**
     * Every url a fetch is currently running for, and who is waiting to be
     * told when it finishes.
     *
     * <p>The key's presence is the only fact that decides "start a fetch"
     * from "join the one already running", so {@link #load} makes that
     * decision - together with the {@link #cache} and {@link #failed}
     * lookups, since a caller must tell all three apart in one look - inside
     * a single block synchronized on this map, never as separate steps a
     * second caller could land between.
     *
     * <p>The same lock guards the other end: {@link #finish} files the
     * answer (into {@link #cache} on a picture, {@link #failed} on nothing)
     * and removes this map's entry inside one more synchronized block, so a
     * caller can never observe "not cached, not a known failure, and nobody
     * is fetching it" while an answer is on its way in but not yet filed -
     * the one interleaving that would let a second, needless fetch start.
     * That is what makes "one request per url" a guarantee rather than
     * something that happens to hold under one timing and stops holding
     * under another.
     *
     * <p>It is also what guarantees every listener that ever entered this
     * map is told exactly once. A listener is added to a url's list inside
     * the same synchronized block that confirms the url is (or is about to
     * become) in flight, so it can never be added after {@link #finish} has
     * already removed and notified that same list - there is no state
     * between "still in flight" and "answered" for it to be added into by
     * mistake. And {@link #finish} itself runs exactly once per fetch,
     * reached through {@link #fetch}'s own {@code finally} whatever the
     * fetch actually did - decoded a picture, threw, or ran out of memory -
     * so a list, once handed to {@link #finish}, is always notified and
     * never notified twice.
     *
     * <p>{@link #forget} deliberately does not touch this map. Clearing an
     * in-flight entry here - rather than only a finished answer in {@link
     * #cache} or {@link #failed} - is exactly what would strand its
     * listeners: {@link #finish} would find nothing to remove and notify
     * nobody, including the very caller that started the fetch.
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
     * Work}. A url already known to answer with nothing - see {@link
     * #failed} - is not re-requested either, for the same reason: a row
     * scrolling back on screen must not repeat a request already known to
     * fail.
     *
     * A cache hit, or a known failure, answers {@code listener} at once, on
     * the calling thread, exactly as {@code Scraped.picture} already does
     * for its own cache hit; an actual miss fetches on {@link Work#run} and
     * answers through {@link Work#onMain}, so a caller never has to guess
     * which thread it will be told on.
     */
    public static void load(Context context, Http http, String url, Listener listener) {
        if (url == null || url.isEmpty()) return;

        Bitmap cached;
        boolean knownFailure = false;
        boolean start = false;

        synchronized (inFlight) {
            cached = cache.get(url);
            if (cached == null) {
                if (Boolean.TRUE.equals(failed.get(url))) {
                    knownFailure = true;
                } else {
                    List<Listener> waiting = inFlight.get(url);
                    if (waiting == null) {
                        waiting = new ArrayList<>();
                        inFlight.put(url, waiting);
                        start = true;
                    }
                    waiting.add(listener);
                }
            }
        }

        if (cached != null) {
            listener.ready(url, cached);
            return;
        }

        if (knownFailure) {
            listener.ready(url, null);
            return;
        }

        if (!start) return; // somebody else is already fetching this url

        Context app = context.getApplicationContext();
        int targetPx = pixels(app, TARGET_DP);

        Work.run("thumbnail", () -> fetch(app, http, url, targetPx));
    }

    /**
     * Drops every cover held and every remembered failure - a fresh {@link
     * #load} for anything tries again as though nothing had been asked
     * before.
     *
     * <b>Does not touch a fetch already in flight.</b> That fetch's own
     * {@link #finish} still runs when it completes and still notifies
     * whatever listeners joined it - {@link #inFlight} is untouched here on
     * purpose; see its own comment for why clearing it is exactly what would
     * strand those listeners. It can be called while the grid is live and a
     * row's cover mid-fetch, so a permanently blank row is the one outcome
     * that is worse than this method doing slightly less than "forget"
     * suggests. What it does free is only what {@link #finish} would
     * otherwise have found already filed - a fetch still in flight simply
     * files its answer into an empty cache when it lands.
     *
     * <b>Nothing calls this when a view goes away, and that is deliberate.</b>
     * A rotation detaches the catalogue's grid, and emptying the cache on one
     * would mean fetching a screenful of covers all over again from a host
     * that blocked this app's address once for behaviour patterns: the cache
     * is meant to outlive a detach. This is for a deliberate emptying, and
     * memory pressure would be its honest caller - there is no such caller
     * yet. Leaving it uncalled leaks nothing: {@link #cache} is bounded by a
     * fraction of the heap and evicts its own least-recently-used entry.
     */
    public static void forget() {
        synchronized (inFlight) {
            cache.evictAll();
            failed.evictAll();
        }
    }

    private static void fetch(Context context, Http http, String url, int targetPx) {
        Bitmap picture = null;
        File file = new File(context.getCacheDir(), "thumbnail-" + System.nanoTime());

        try {
            try {
                http.save(url, file);
                picture = decode(file, targetPx);
            } finally {
                file.delete();
            }
        } catch (Throwable t) {
            // A 404, a timeout, a corrupt file, a format BitmapFactory does
            // not know, or a decode too large to fit in memory - an
            // OutOfMemoryError, not an Exception; PictureCache.decodeFresh
            // catches the same width for the same reason. Any of them means
            // "no picture", never "leave this url in flight for ever": the
            // outer finally below always runs regardless of what was thrown
            // here, so every listener that joined is still told and no
            // future load() for this url is left joining a fetch that can
            // never finish.
            picture = null;
        } finally {
            // Reached on every path out of the try above - success, the
            // caught failure, or anything this method did not anticipate -
            // which is what makes finish() run exactly once per fetch
            // rather than a catch clause hoped to be wide enough. See
            // inFlight's own comment for why that is what guarantees every
            // listener is told exactly once.
            finish(url, picture);
        }
    }

    /**
     * The one place a fetch ends, always reached through {@link #fetch}'s
     * own {@code finally}. Files the answer and hands the url's own waiters
     * to it, atomically with {@link #load}'s hit / known-failure / join /
     * start decision - see {@link #inFlight}'s comment for why that
     * atomicity is what guarantees every listener is told exactly once.
     */
    private static void finish(String url, Bitmap picture) {
        List<Listener> waiting;

        synchronized (inFlight) {
            if (picture != null) {
                cache.put(url, picture);
            } else {
                failed.put(url, Boolean.TRUE);
            }
            waiting = inFlight.remove(url);
        }

        if (waiting == null) return;

        for (Listener listener : waiting) {
            Work.onMain(() -> listener.ready(url, picture));
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
