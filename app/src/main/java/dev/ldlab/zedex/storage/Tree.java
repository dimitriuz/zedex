package dev.ldlab.zedex.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Find-or-create a folder under a granted tree, and write a file into it.
 *
 * The three SAF verbs this app needs, in one place. {@code EsDe} has its own
 * private copies and is left alone here - the point of this class is that the
 * <em>next</em> caller does not make a third set, not that the existing one is
 * rewritten in a task about something else.
 *
 * <b>Why SAF at all, when the app usually has All-files access:</b> the Play
 * build does not, and this is the route that works either way. It is the same
 * one ES-DE's two files already take.
 *
 * <b>Find before create, always.</b> SAF's uniqueness is the document id, not
 * the display name, so createDocument will cheerfully make a second "Games"
 * beside the first - after which imports land in whichever one the last call
 * happened to return, and half of somebody's collection is invisible from the
 * other.
 */
public final class Tree {

    private static final String TAG = "Zedex";

    private Tree() {
    }

    /**
     * Whether this app can write into that granted tree, asked rather than
     * probed.
     *
     * A probe - writing a file and seeing whether it lands - is what
     * {@link Storage#isWritable} does for a plain path, because a path has
     * nothing else to ask. A SAF grant does: {@code
     * getPersistedUriPermissions()} is the same list {@code
     * takePersistableUriPermission} wrote to, so it is authoritative, and a
     * probe would leave a document behind on success - with MediaProvider
     * keeping a row per path that can outlive the file, which is exactly why
     * the probe elsewhere has to carry a {@code nanoTime}. Nothing here to
     * guess at.
     *
     * {@code tree} may be the raw uri a picker returned or the document uri
     * {@link Storage#contentFolder} builds from it - what is persisted is
     * always the former, so matching by {@code Uri.equals} against the
     * latter would answer "no" for every grant this class is actually asked
     * about. {@code getTreeDocumentId} reads the same tree id segment out of
     * either shape, which is what {@link #find} already relies on one layer
     * down to turn a document uri back into a tree's child-listing uri.
     */
    public static boolean canWrite(Context context, Uri tree) {
        if (tree == null) return false;

        String treeId;
        try {
            treeId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception e) {
            return false;
        }

        List<UriPermission> permissions =
                context.getContentResolver().getPersistedUriPermissions();

        for (UriPermission permission : permissions) {
            Uri granted = permission.getUri();
            if (!tree.getAuthority().equals(granted.getAuthority())) continue;

            try {
                if (treeId.equals(DocumentsContract.getTreeDocumentId(granted))) {
                    return permission.isWritePermission();
                }
            } catch (Exception e) {
                // Not a tree grant at all - a plain document permission from
                // somewhere else, which this call is not asking about.
            }
        }

        return false;
    }

    /**
     * The folder at the end of that path, made where it is absent.
     *
     * {@code parent} is a <b>document</b> uri, not a tree uri - which is
     * exactly what {@code Storage.contentFolder} already hands back, since it
     * builds the root document itself. Re-deriving one from the tree here
     * would work by luck for that caller and be wrong for every other level,
     * so it is not done: the parent is where the walk starts, whatever it is.
     * With no names that is the answer.
     *
     * @return null if any level could not be made, which on a tree the user
     *         granted means something is wrong with the grant rather than
     *         with the name.
     */
    public static Uri folder(Context context, Uri parent, String... names) {
        Uri at = parent;

        for (String name : names) {
            Uri existing = find(context, at, name);

            if (existing == null) {
                existing = create(context, at, DocumentsContract.Document.MIME_TYPE_DIR, name);
            }
            if (existing == null) {
                Log.w(TAG, "cannot reach " + name + " under " + at);
                return null;
            }

            at = existing;
        }

        return at;
    }

    /** The child of that folder with that display name, or null. */
    public static Uri find(Context context, Uri parent, String name) {
        if (parent == null || name == null) return null;

        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                parent, DocumentsContract.getDocumentId(parent));
        ContentResolver resolver = context.getContentResolver();

        try (Cursor cursor = resolver.query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (cursor == null) return null;

            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent,
                                                                       cursor.getString(0));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot look inside " + parent, e);
        }

        return null;
    }

    /**
     * Copies a local file into that folder under that name.
     *
     * The file is written whole and the document is deleted if the copy fails
     * part way: a half-written .tap is indistinguishable from a real one, and
     * the emulator's refusal to load it reads as a broken download of a
     * working game.
     *
     * @return the document written, or null.
     */
    public static Uri write(Context context, Uri parent, String name, File from) {
        Uri document = create(context, parent, "application/octet-stream", name);
        if (document == null) return null;

        try (InputStream in = new FileInputStream(from);
             OutputStream out = context.getContentResolver().openOutputStream(document, "wt")) {

            if (out == null) throw new java.io.IOException("no stream for " + document);

            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, read);
            }
            return document;

        } catch (Exception e) {
            Log.w(TAG, "cannot write " + name, e);
            delete(context, document);
            return null;
        }
    }

    private static Uri create(Context context, Uri parent, String mime, String name) {
        try {
            return DocumentsContract.createDocument(context.getContentResolver(),
                                                    parent, mime, name);
        } catch (Exception e) {
            Log.w(TAG, "cannot create " + name, e);
            return null;
        }
    }

    private static void delete(Context context, Uri document) {
        try {
            DocumentsContract.deleteDocument(context.getContentResolver(), document);
        } catch (Exception e) {
            Log.w(TAG, "cannot remove the half-written " + document, e);
        }
    }
}
