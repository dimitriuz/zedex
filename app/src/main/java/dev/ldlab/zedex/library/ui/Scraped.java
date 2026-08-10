package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.library.meta.Artwork;
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
 * The picture for one row, resolved and decoded off the UI thread.
 *
 * The facts used to come back through here as well, and no longer do:
 * {@link Metadata} is an in-memory map once it has been read, so a name is a
 * hash lookup and asking a worker for it only meant every row was drawn with
 * its filename and rewritten a moment later. Callers read the words
 * themselves; what is left here needs a thread because it needs decoding.
 *
 * See docs/LIBRARY.md, "the second pull request: linking to ES-DE": metadata
 * is copied into a store of our own, but artwork stays where ES-DE put it and
 * is resolved fresh each time it is drawn - {@link Artwork#picture} - and a
 * picture is a real decode from a SAF document, which is never cheap.
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
 * A row's own bind asks at a small size and the pane's selection at a larger
 * one; the key carries the size, so both for the same path sit in the cache
 * at once without one evicting the other.
 *
 * {@link #forget} answers a folder changing under a resolve still in
 * flight; {@link #clear} answers the store itself no longer being
 * trustworthy, which only a link does. Neither is called from here - {@code
 * EntryAdapter} and {@code LibraryActivity} are what know when either has
 * happened.
 */
public final class Scraped {


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
    /**
     * A decoded picture, or the absence of one.
     *
     * A wrapper rather than the Bitmap itself so that "asked, and there is
     * none" is cacheable: most of a collection has no artwork, and a null in
     * the map is indistinguishable from a miss.
     */
    private static final class Result {
        final Bitmap picture;

        Result(Bitmap picture) {
            this.picture = picture;
        }
    }

    /** Two at once is enough to keep a fast scroll from queuing dozens of
     *  decodes behind each other, and few enough not to fight the UI thread
     *  for the disk or the CPU either. */
    private static final int THREADS = 2;

    /**
     * Shared across every instance, for the reason all three of {@link
     * Gallery}'s pools are - and because this one was not, and leaked.
     *
     * One Scraped is built per {@code EntryAdapter}, which is built per
     * {@code LibraryActivity.onCreate}, and that activity declares no
     * {@code configChanges} - so every rotation made another pool. A
     * {@code newFixedThreadPool} does not retire its core threads once a task
     * has started them, and nothing here ever called {@code shutdown}, so each
     * rotation left two more threads parked for the life of the process with
     * nothing left to run. Twenty rotations, forty threads. This screen has
     * been killed by the low-memory killer once already for a different
     * unbounded thing; see the RecyclerView note in CLAUDE.md.
     *
     * Static rather than shut down from an {@code onDestroy} the activity does
     * not have: the pool is stateless and two threads is the right number for
     * the app rather than for one screen, which is exactly the argument
     * {@code Gallery} already makes.
     */
    private static final ExecutorService executor = Executors.newFixedThreadPool(THREADS);
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
                // A miss - no picture, which is the common answer here - is
                // as cheap as this guess says, so thousands of them cost the
                // cache almost nothing. A picture's own byte count is what
                // actually fills the budget.
                return value.picture != null ? value.picture.getByteCount() : 64;
            }
        };
    }

    /**
     * The picture for {@code relativePath}, decoded to roughly {@code
     * targetPx} on a side - see {@link #decode}. Answers from the cache at
     * once if it already knows; otherwise asks a background thread and calls
     * {@code callback} on the main thread once it has one, unless the folder
     * has moved on since it was asked, in which case the answer is dropped
     * rather than delivered late into whatever is on screen now.
     *
     * The picture alone. The facts used to come back through here too, and
     * that was the whole of the flicker: a row's name is a map read once
     * Metadata has been loaded, so sending it to a thread meant every row was
     * drawn with its filename and rewritten a moment later. Callers read the
     * words themselves now; only this needs a thread, because only this needs
     * decoding.
     */
    public void picture(Context context, String relativePath, int targetPx,
                        PictureCallback callback) {
        String key = relativePath + '@' + targetPx;

        Result cached = cache.get(key);
        if (cached != null) {
            callback.onReady(cached.picture);
            return;
        }

        int atGeneration = generation;
        Context app = context.getApplicationContext();

        executor.execute(() -> {
            Bitmap picture = decode(app, relativePath, targetPx);

            // Checked before the cache is written, not after. The key is a
            // path relative to the content folder, so two folders holding the
            // same game agree on it - and caching an answer from the folder we
            // have just left would hand that game the other folder's artwork,
            // from the cache, for as long as the process lives. forget() bumps
            // the generation without evicting, so this is the only guard.
            if (atGeneration != generation) return; // the folder moved on

            cache.put(key, new Result(picture));

            main.post(() -> callback.onReady(picture));
        });
    }

    /** @see #picture */
    public interface PictureCallback {
        void onReady(Bitmap picture);
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

    /** Told whether this game has music - see {@link #loadMusic}. */
    public interface MusicCallback {
        void onReady(boolean any);
    }

    /**
     * Whether a tune was fetched for this game, answered off the UI thread.
     *
     * The same shape as {@link #loadManual} and for the same reason: looking
     * in the media folder is a filesystem round trip, and on a device where
     * that folder is reached through a document tree it is a provider one -
     * never safe to make just to decide whether to draw a button.
     */
    public void loadMusic(Context context, String relativePath, MusicCallback callback) {
        int atGeneration = generation;
        Context app = context.getApplicationContext();

        executor.execute(() -> {
            boolean any;
            try {
                any = Artwork.music(app, relativePath) != null;
            } catch (Exception e) {
                any = false;
            }

            if (atGeneration != generation) return; // the folder moved on

            boolean result = any;
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
