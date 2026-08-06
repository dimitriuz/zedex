package dev.ldlab.zedex.library.meta;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.util.HashMap;
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

    /** Tried in this order: the first that exists is what the pane shows. */
    private static final String[] PICTURE_FOLDERS = {
        "covers", "miximages", "screenshots", "titlescreens",
    };

    /**
     * Both cases, because {@link EsdeLink#read} copies whatever extension
     * ES-DE actually scraped with, and that is not always {@code .png}.
     */
    private static final String[] PICTURE_EXTENSIONS = { "png", "jpg" };

    private static final String VIDEO_FOLDER = "videos";
    private static final String VIDEO_EXTENSION = "mp4";

    /** A cached miss, so the map can tell "not looked up" from "looked up, nothing there". */
    private static final Uri MISS = Uri.EMPTY;

    private static final Map<String, Uri> pictureCache = new HashMap<>();
    private static final Map<String, Uri> videoCache = new HashMap<>();

    /** The media root the two caches above were built against. */
    private static String cachedForRoot;

    private Artwork() {
    }

    /** covers, then miximages, then screenshots, then titlescreens; null when none exists. */
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

    /** The current media root, clearing both caches first if it has changed since last time. */
    private static Uri freshen(Context context) {
        Uri root = EsdeLink.mediaRoot(context);
        String key = root == null ? null : root.toString();

        if (!java.util.Objects.equals(key, cachedForRoot)) {
            pictureCache.clear();
            videoCache.clear();
            cachedForRoot = key;
        }

        return root;
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
