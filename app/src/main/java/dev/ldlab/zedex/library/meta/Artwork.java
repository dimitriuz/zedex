package dev.ldlab.zedex.library.meta;

import android.util.LruCache;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Artwork and video for one game, resolved against wherever {@link
 * EsdeLink#mediaRoot} says ES-DE keeps its media.
 *
 * ES-DE's media tree mirrors the ROM tree exactly, folder and extension
 * aside, so a game's picture is found by trying the plausible names in turn
 * rather than by reading the folder - which, over the SAF grant most of this
 * runs through, would be a query per game's <em>entire</em> folder rather
 * than a query per candidate name.
 *
 * Most of a collection is unscraped, so a miss is the ordinary answer, not
 * the exception - and this is called once per visible row as a list scrolls.
 * Both a hit and a miss are cached in memory, keyed by the game's path, so
 * scrolling back over the same rows costs no further lookup at all; the
 * cache is only thrown away when {@link EsdeLink#mediaRoot} itself resolves
 * to something different, which is what a new or revoked grant does.
 */
public final class Artwork {

    private static final String SYSTEM = "zxspectrum";

    /**
     * Tried in this order: the first that exists is what {@link #picture}
     * shows, and the cover has to stay first in the list for exactly that
     * reason - every row and tile draws whichever one this resolves. After
     * it, the back and the cartridge/tape photo before the screenshot-ish
     * folders, which is the order a box on a shelf would show them in.
     */
    private static final String[] PICTURE_FOLDERS = {
        "covers", "backcovers", "physicalmedia", "miximages", "screenshots", "titlescreens",
    };

    /**
     * Both cases, because {@link EsdeLink#read} copies whatever extension
     * ES-DE actually scraped with, and that is not always {@code .png}.
     */
    private static final String[] PICTURE_EXTENSIONS = { "png", "jpg" };

    private static final String VIDEO_FOLDER = "videos";
    private static final String VIDEO_EXTENSION = "mp4";

    /**
     * ES-DE keeps a manual as a PDF, not a picture, so it is resolved on its
     * own rather than folded into {@link #PICTURE_FOLDERS} - {@link
     * #picture} and {@link #pictures} both decode with {@code
     * BitmapFactory}, which cannot read one at all.
     */
    private static final String MANUAL_FOLDER = "manuals";
    private static final String MANUAL_EXTENSION = "pdf";

    /** A cached miss, so the map can tell "not looked up" from "looked up, nothing there". */
    private static final Uri MISS = Uri.EMPTY;

    /**
     * How many answers each cache keeps.
     *
     * These were unbounded maps, cleared only when the ES-DE media root
     * changed - so browsing a few thousand games accumulated four entries
     * each for the life of the process, and leaving the library released
     * none of it. What is being kept is small (a path and a document Uri),
     * but "small and for ever" is still a leak with a slow fuse.
     *
     * Five hundred is far more than fits on a screen and about the size of a
     * folder somebody actually scrolls through, so an eviction means going
     * back past five hundred games - at which point one more provider query
     * is the cheaper of the two costs.
     */
    private static final int CACHE_ENTRIES = 500;

    private static final LruCache<String, Uri> pictureCache = new LruCache<>(CACHE_ENTRIES);
    private static final LruCache<String, Uri> videoCache = new LruCache<>(CACHE_ENTRIES);
    private static final LruCache<String, Uri> manualCache = new LruCache<>(CACHE_ENTRIES);

    /** {@link #pictures}, which asks for all four folders where the two
     *  above stop at the first answer. */
    private static final LruCache<String, List<Uri>> galleryCache =
            new LruCache<>(CACHE_ENTRIES);

    /** The media root the two caches above were built against. */
    private static String cachedForRoot;

    private Artwork() {
    }

    /** The first of {@link #PICTURE_FOLDERS} that exists; null when none does. */
    public static synchronized Uri picture(Context context, String relativePath) {
        Uri root = freshen(context);
        if (root == null) return null;

        Uri cached = pictureCache.get(relativePath);
        if (cached != null) return cached == MISS ? null : cached;

        String stem = withoutExtension(relativePath);
        Uri found = null;

        for (String folder : PICTURE_FOLDERS) {
            for (String extension : PICTURE_EXTENSIONS) {
                found = resolve(context, root, folder, stem + "." + extension);
                if (found != null) break;
            }
            if (found != null) break;
        }

        pictureCache.put(relativePath, found == null ? MISS : found);
        return found;
    }

    /**
     * Every picture ES-DE has for this game, in {@link #PICTURE_FOLDERS}
     * order - the cover first, which is what a single-picture caller wants -
     * and empty when it has none.
     *
     * One per folder, not one per file: a game has a cover *or* nothing in
     * {@code covers}, and whether it was scraped as {@code .png} or
     * {@code .jpg} is ES-DE's business rather than a second picture. So this
     * is at most as many as {@link #PICTURE_FOLDERS} has, and usually one.
     *
     * Kept apart from {@link #picture} deliberately, cache and all. That one
     * stops at the first hit and is called once per visible row as a list
     * scrolls; this one always asks every folder, and is called once, for
     * the one game whose details are open.
     */
    public static synchronized List<Uri> pictures(Context context, String relativePath) {
        Uri root = freshen(context);
        if (root == null) return Collections.emptyList();

        List<Uri> cached = galleryCache.get(relativePath);
        if (cached != null) return cached;

        String stem = withoutExtension(relativePath);
        List<Uri> found = new ArrayList<>();

        for (String folder : PICTURE_FOLDERS) {
            for (String extension : PICTURE_EXTENSIONS) {
                Uri one = resolve(context, root, folder, stem + "." + extension);
                if (one != null) {
                    found.add(one);
                    break;
                }
            }
        }

        List<Uri> result = Collections.unmodifiableList(found);
        galleryCache.put(relativePath, result);
        return result;
    }

    /** {@code videos/...}; null when there is none. */
    public static synchronized Uri video(Context context, String relativePath) {
        Uri root = freshen(context);
        if (root == null) return null;

        Uri cached = videoCache.get(relativePath);
        if (cached != null) return cached == MISS ? null : cached;

        Uri found = resolve(context, root,
                VIDEO_FOLDER, withoutExtension(relativePath) + "." + VIDEO_EXTENSION);

        videoCache.put(relativePath, found == null ? MISS : found);
        return found;
    }

    /**
     * {@code manuals/....pdf}; null when there is none. Never a picture -
     * see {@link #MANUAL_FOLDER} - so this only tells a caller whether one
     * exists, for a manual button to show itself or not; opening it is
     * {@link dev.ldlab.zedex.library.ui.Manuals#open}'s job, not this
     * class's.
     */
    public static synchronized Uri manual(Context context, String relativePath) {
        Uri root = freshen(context);
        if (root == null) return null;

        Uri cached = manualCache.get(relativePath);
        if (cached != null) return cached == MISS ? null : cached;

        Uri found = resolve(context, root,
                MANUAL_FOLDER, withoutExtension(relativePath) + "." + MANUAL_EXTENSION);

        manualCache.put(relativePath, found == null ? MISS : found);
        return found;
    }

    /** The current media root, clearing every cache first if it has changed since last time. */
    private static Uri freshen(Context context) {
        Uri root = EsdeLink.mediaRoot(context);
        String key = root == null ? null : root.toString();

        if (!java.util.Objects.equals(key, cachedForRoot)) {
            forget();
            cachedForRoot = key;
        }

        return root;
    }

    /**
     * Drops everything remembered.
     *
     * Called when the media root changes, because none of it can still be
     * true, and by the library screen when it goes away, because that is when
     * this stops being a working set and becomes something held for nobody -
     * these are static, so without this they outlive every screen that wanted
     * them.
     */
    public static synchronized void forget() {
        pictureCache.evictAll();
        videoCache.evictAll();
        manualCache.evictAll();
        galleryCache.evictAll();
    }

    /** ES-DE's path, without the ROM's own extension and without the leading {@code ./}. */
    private static String withoutExtension(String relativePath) {
        String path = relativePath.startsWith("./")
                ? relativePath.substring(2) : relativePath;
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(0, dot) : path;
    }

    /**
     * One candidate under {@code root}, or null if it is not really there.
     *
     * {@code root} is a plain folder when this build reached ES-DE's media by
     * path, or a SAF document otherwise. The SAF case is resolved by
     * extending the document id directly rather than walking it a folder at
     * a time: {@code ExternalStorageProvider}, what a folder picker hands
     * back for local storage, names a document {@code volume:relative/path} -
     * the same fact {@code Storage.pathFor} relies on to go the other way -
     * so the candidate's id is built in one step and checked with a
     * single-row query instead of a directory listing.
     */
    private static Uri resolve(Context context, Uri root, String folder, String name) {
        String relative = SYSTEM + "/" + folder + "/" + name;

        if ("file".equals(root.getScheme())) {
            File file = new File(root.getPath(), relative);
            return file.canRead() && file.length() > 0 ? Uri.fromFile(file) : null;
        }

        try {
            String docId = DocumentsContract.getDocumentId(root) + "/" + relative;
            Uri candidate = DocumentsContract.buildDocumentUriUsingTree(root, docId);

            try (Cursor cursor = context.getContentResolver().query(candidate,
                    new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID },
                    null, null, null)) {
                return cursor != null && cursor.moveToFirst() ? candidate : null;
            }
        } catch (Exception e) {
            // Not there, or this provider does not shape ids that way; either
            // way, no picture is the right answer rather than a crash.
            return null;
        }
    }
}
