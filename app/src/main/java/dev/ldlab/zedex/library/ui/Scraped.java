package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The scraped facts and the picture for one row, resolved and decoded
 * together - both come from the same path, and the same background thread
 * reaches both in one trip. See docs/LIBRARY.md, "the second pull request:
 * linking to ES-DE": metadata is copied into a store of our own, but artwork
 * stays where ES-DE put it and is resolved fresh each time it is drawn,
 * which is exactly what {@link Metadata#forPath} and {@link Artwork#picture}
 * do - cheaply or not, this class does not assume, and keeps both off the UI
 * thread either way. A picture is also a real decode from a SAF document,
 * which is never cheap.
 *
 * What this answers is cached, hit and miss alike, keyed by the path and the
 * size asked for. Most of a folder like this one is unscraped - on the
 * collection this was built against, 174 games under one subfolder are
 * scraped and everything else is not - so asking the store and decoding
 * nothing, over and over, on every scroll, is exactly the stutter a folder
 * of a couple of thousand tapes would otherwise cause. A miss costs the
 * cache a few bytes, the same structure as a hit, rather than a second one
 * to keep in step - see {@link Result}.
 *
 * The whole {@link Meta} is handed back rather than just a name, so both a
 * row - which only ever reads {@code meta.name} - and the pane - which reads
 * the rest of it too - are answered by the one resolve; a row's own bind
 * asks at a small size, the pane's own selection at a larger one, and the
 * key already tells the two apart, so both sizes for the same path sit in
 * the cache at once without one evicting the other.
 *
 * {@link #forget} answers a folder changing under a resolve still in
 * flight; {@link #clear} answers the store itself no longer being
 * trustworthy, which only a link does. Neither is called from here - {@code
 * EntryAdapter} and {@code LibraryActivity} are what know when either has
 * happened.
 */
public final class Scraped {

    /** Told the answer once it is ready - always on the main thread, so it
     *  can be drawn straight into a view. Either may be null: nothing known,
     *  no picture, or neither - which is what most rows in an unscraped
     *  collection answer, and is meant to look exactly as it always has. */
    public interface Callback {
        void onReady(Meta meta, Bitmap picture);
    }

    /** The pane's own use of {@link #loadVideo} - a video is never decoded
     *  or cached here the way a picture is, since {@code VideoView} does its
     *  own buffering and {@link Artwork#video} already caches the resolve
     *  itself; this only keeps that resolve off the UI thread. */
    public interface VideoCallback {
        void onReady(Uri video);
    }

    /** {@link #loadManual}'s own answer - null when there is none, which is
     *  what tells the pane's manual button to stay hidden. */
    public interface ManualCallback {
        void onReady(Uri manual);
    }

    /** What one path answered, cached as a single unit rather than two - the
     *  facts cost the cache nothing worth measuring, so there is no reason
     *  to track them apart from the picture they were resolved alongside. */
    private static final class Result {
        final Meta meta;
        final Bitmap picture;

        Result(Meta meta, Bitmap picture) {
            this.meta = meta;
            this.picture = picture;
        }
    }

    /** Two at once is enough to keep a fast scroll from queuing dozens of
     *  decodes behind each other, and few enough not to fight the UI thread
     *  for the disk or the CPU either. */
    private static final int THREADS = 2;

    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Result> cache;

    /**
     * Bumped whenever the content folder changes - see {@link #forget} - so
     * a resolve still running from before checks this after finishing and
     * before posting its answer to the main thread. Volatile since it is
     * read from whichever background thread happens to finish and written
     * from the main thread; nothing here needs more than that a write is
     * eventually visible, not a lock.
     */
    private volatile int generation;

    public Scraped() {
        // Runtime.maxMemory() in bytes, sizeOf() in bytes too - one unit
        // throughout rather than the KB Android's own docs use, which asks
        // for nothing this class does not already have.
        int maxBytes = (int) (Runtime.getRuntime().maxMemory() / 8);

        cache = new LruCache<String, Result>(Math.max(maxBytes, 1)) {
            @Override
            protected int sizeOf(String key, Result value) {
                // Meta is a handful of short strings, not worth measuring; a
                // miss - nothing known, no picture, which is the common
                // answer here - is as cheap as this guess says, so thousands
                // of them cost the cache almost nothing. A picture's own
                // byte count is what actually fills the budget.
                return value.picture != null ? value.picture.getByteCount() : 64;
            }
        };
    }

    /**
     * Resolves the facts and the picture for {@code relativePath}, the
     * picture decoded to roughly {@code targetPx} on a side - see {@link
     * #decode}. Answers from the cache at once if it already knows;
     * otherwise asks a background thread and calls {@code callback} on the
     * main thread once it has an answer - unless the folder has moved on
     * since it was asked, in which case the answer is dropped rather than
     * delivered late into whatever is on screen now.
     */
    public void load(Context context, String relativePath, int targetPx, Callback callback) {
        String key = relativePath + '@' + targetPx;

        Result cached = cache.get(key);
        if (cached != null) {
            callback.onReady(cached.meta, cached.picture);
            return;
        }

        int atGeneration = generation;
        Context app = context.getApplicationContext();

        executor.execute(() -> {
            Meta meta = resolveMeta(app, relativePath);
            Bitmap picture = decode(app, relativePath, targetPx);

            cache.put(key, new Result(meta, picture));

            if (atGeneration != generation) return; // the folder moved on

            main.post(() -> callback.onReady(meta, picture));
        });
    }

    /**
     * The pane's own extra: a scraped video, resolved off the UI thread but
     * neither decoded nor cached here - {@link Artwork#video} already caches
     * its own resolve, and playing it is {@code VideoView}'s job, not this
     * class's. Dropped the same way {@link #load}'s answer is if the folder
     * has moved on by the time it comes back.
     */
    public void loadVideo(Context context, String relativePath, VideoCallback callback) {
        int atGeneration = generation;
        Context app = context.getApplicationContext();

        executor.execute(() -> {
            Uri video;
            try {
                video = Artwork.video(app, relativePath);
            } catch (Exception e) {
                video = null;
            }

            if (atGeneration != generation) return; // the folder moved on

            Uri result = video;
            main.post(() -> callback.onReady(result));
        });
    }

    /**
     * The pane's own manual button: whether {@link Artwork#manual} has one
     * for {@code relativePath}, resolved off the UI thread for the same
     * reason {@link #loadVideo} is - a SAF query, never safe on this one -
     * and never decoded here, since a button either shows or does not and
     * {@link Manuals#open} is what actually opens the PDF once it is tapped.
     */
    public void loadManual(Context context, String relativePath, ManualCallback callback) {
        int atGeneration = generation;
        Context app = context.getApplicationContext();

        executor.execute(() -> {
            Uri manual;
            try {
                manual = Artwork.manual(app, relativePath);
            } catch (Exception e) {
                manual = null;
            }

            if (atGeneration != generation) return; // the folder moved on

            Uri result = manual;
            main.post(() -> callback.onReady(result));
        });
    }

    /**
     * The content folder changed - a resolve already in flight is answering
     * a question that no longer applies, so its own answer is dropped
     * rather than drawn; see {@link #load}. Cheap and safe to call whether
     * or not anything was actually pending.
     */
    public void forget() {
        generation++;
    }

    /**
     * A link replaced the metadata store, or anything else that makes what
     * is cached untrustworthy: forgets every answer, hit and miss alike, so
     * the next ask resolves fresh rather than repeating what a link may
     * have just changed the answer to.
     */
    public void clear() {
        cache.evictAll();
        forget();
    }

    private static Meta resolveMeta(Context context, String relativePath) {
        try {
            return Metadata.forPath(context, relativePath);
        } catch (Exception e) {
            // Metadata.forPath answering badly is not this row's fault to
            // crash over - nothing known is what "unscraped" already looks
            // like.
            return null;
        }
    }

    private static Bitmap decode(Context context, String relativePath, int targetPx) {
        Uri picture;
        try {
            picture = Artwork.picture(context, relativePath);
        } catch (Exception e) {
            return null;
        }

        // The actual decode-and-sample-down is PictureCache's job now, not
        // this class's own - see its class comment for why a row's decode
        // needs to be visible to the gallery this same game's details screen
        // opens, and not only to the Result cache below, which is keyed by
        // path rather than by the file this resolved to.
        return PictureCache.decode(context, picture, targetPx);
    }
}
