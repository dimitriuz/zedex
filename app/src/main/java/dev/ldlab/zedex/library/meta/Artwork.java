package dev.ldlab.zedex.library.meta;

import dev.ldlab.zedex.storage.Storage;

import android.util.Log;
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

    private static final String TAG = "Zedex";

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
        "maps", "adverts",
    };

    /*
     * The last two are not ES-DE's - it has no folder for either, and a map
     * and an advertisement are worth having - so they are this app's own, and
     * they go last on purpose. This list is a preference order as well as a
     * gallery: the first that exists is what every row and tile draws, and a
     * game map is a poor thumbnail for a game that also has a cover.
     */

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

    /**
     * The cheats a provider found for one game.
     *
     * A {@code .pok} is a few lines of text naming pokes - see {@code
     * PokeDatabase}, which parses the same format out of the database that
     * ships with the app. Kept beside the pictures because it belongs to the
     * game in the same way and is fetched by the same machinery, and not
     * because it is artwork.
     */
    private static final String POKE_FOLDER = "pokes";
    private static final String POKE_EXTENSION = "pok";

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
        // No early return on a null root any more: ES-DE not being installed,
        // or its grant being gone, says nothing about what this app fetched
        // for itself. freshen still runs, because its job is to notice their
        // root changing and drop what was cached against it.
        Uri root = freshen(context);

        Uri cached = pictureCache.get(relativePath);
        if (cached != null) return cached == MISS ? null : cached;

        String stem = withoutExtension(relativePath);
        Uri found = null;

        for (String folder : PICTURE_FOLDERS) {
            found = inFolder(context, root, folder, stem);
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

        List<Uri> cached = galleryCache.get(relativePath);
        if (cached != null) return cached;

        String stem = withoutExtension(relativePath);
        List<Uri> found = new ArrayList<>();

        for (String folder : PICTURE_FOLDERS) {
            Uri one = inFolder(context, root, folder, stem);
            if (one != null) found.add(one);
        }

        List<Uri> result = Collections.unmodifiableList(found);
        galleryCache.put(relativePath, result);
        return result;
    }

    /** {@code videos/...}; null when there is none. */
    public static synchronized Uri video(Context context, String relativePath) {
        Uri root = freshen(context);

        Uri cached = videoCache.get(relativePath);
        if (cached != null) return cached == MISS ? null : cached;

        String name = withoutExtension(relativePath) + "." + VIDEO_EXTENSION;
        Uri found = ours(context, VIDEO_FOLDER, name);
        if (found == null && root != null) found = resolve(context, root, VIDEO_FOLDER, name);

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

        Uri cached = manualCache.get(relativePath);
        if (cached != null) return cached == MISS ? null : cached;

        String name = withoutExtension(relativePath) + "." + MANUAL_EXTENSION;
        Uri found = ours(context, MANUAL_FOLDER, name);
        if (found == null && root != null) found = resolve(context, root, MANUAL_FOLDER, name);

        manualCache.put(relativePath, found == null ? MISS : found);
        return found;
    }

    /**
     * The {@code .pok} fetched for this game, or null.
     *
     * <b>A {@code File} and not a {@code Uri}, unlike everything else here.</b>
     * ES-DE has no such folder, so this is only ever ours - there is no second
     * place to look and no document to resolve - and the caller reads the text
     * rather than handing it to something that opens a stream. Uncached for
     * the same reason it is cheap: a few hundred bytes read once, when
     * somebody opens the cheats page.
     */
    public static File pokes(Context context, String relativePath) {
        if (relativePath == null) return null;

        File file = new File(new File(Storage.mediaDirectory(context), POKE_FOLDER),
                             withoutExtension(relativePath) + "." + POKE_EXTENSION);

        return file.canRead() && file.length() > 0 ? file : null;
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
     * One folder's picture for this game: ours if we have it, otherwise
     * ES-DE's.
     *
     * <b>Per folder, not per source.</b> A cover this app scraped beats a
     * cover ES-DE scraped, and their screenshots still appear beside it in
     * the gallery - which is what somebody with a half-scraped collection
     * actually wants, and what "ours entirely, else theirs" would take away.
     */
    private static Uri inFolder(Context context, Uri esde, String folder, String stem) {
        for (String extension : PICTURE_EXTENSIONS) {
            Uri mine = ours(context, folder, stem + "." + extension);
            if (mine != null) return mine;
        }

        if (esde == null) return null;

        for (String extension : PICTURE_EXTENSIONS) {
            Uri theirs = resolve(context, esde, folder, stem + "." + extension);
            if (theirs != null) return theirs;
        }
        return null;
    }

    /**
     * One candidate in this app's own media folder, or null.
     *
     * Always a plain file - it is under {@link Storage#root}, which this app
     * owns and reaches by path - so this is the simple half of what {@link
     * #resolve} has to do for ES-DE's, whose root may be a SAF document.
     */
    private static Uri ours(Context context, String folder, String name) {
        File file = new File(new File(Storage.mediaDirectory(context), folder), name);
        return file.canRead() && file.length() > 0 ? Uri.fromFile(file) : null;
    }

    /**
     * Where a scraper should write one piece of media for a game.
     *
     * The counterpart to everything above: this names the file, {@link #ours}
     * finds it again, and the two must agree or a scrape appears to do
     * nothing. Parent folders are made here, since the caller is about to
     * write and there is nothing else it could reasonably do about them.
     *
     * @param folder one of ES-DE's own names - {@code covers}, {@code videos}
     *               and the rest - which this app mirrors so one list serves
     *               both roots; see {@link #PICTURE_FOLDERS}.
     */
    public static File fileFor(Context context, String relativePath,
                               String folder, String extension) {
        File file = new File(new File(Storage.mediaDirectory(context), folder),
                             withoutExtension(relativePath) + "." + extension);

        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "cannot make " + parent);
        }
        return file;
    }

    /**
     * Drops what was remembered about one game.
     *
     * For a scrape that has just written this game's cover: {@link #forget()}
     * would be correct too and throws away five hundred lookups to do it,
     * which in the middle of a multi-scrape is five hundred games' worth of
     * provider queries per game scraped.
     */
    public static synchronized void forget(String relativePath) {
        pictureCache.remove(relativePath);
        videoCache.remove(relativePath);
        manualCache.remove(relativePath);
        galleryCache.remove(relativePath);
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
