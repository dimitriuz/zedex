package dev.ldlab.zedex.library;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reading folders and zip archives for the library. Everything here is
 * blocking - a folder query is a round trip to another app's content
 * provider, and reading a zip is a stream from one - so none of it may be
 * called from the UI thread. A folder of a few thousand files is exactly the
 * case this class exists for, and the one query per child that
 * {@code DocumentFile.listFiles()} would cost is the thing that made a
 * hand-rolled listing worth writing.
 */
public final class Listing {

    private static final String TAG = "Zedex";

    /** Everything {@link #folder} needs, in one cursor. */
    private static final String[] PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    /** Where extracted archive entries are kept, under the cache directory. */
    private static final String CACHE_DIR = "library";

    private Listing() {
    }

    /**
     * The tree's own root document.
     *
     * A persisted content tree grant is the tree itself, not a document within
     * it; this is the document at its root, which is what {@link #folder} and
     * everything downstream of it expects to be handed.
     */
    public static Uri root(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
    }

    /**
     * Children of a folder document: folders first, then the files the
     * emulator can open, each ordered A-Z case-insensitively. A file
     * {@link Types#supported} says no to is left out entirely rather than
     * shown and refused later; a folder is always kept; a {@code .zip} is
     * kept as {@link Entry.Kind#ARCHIVE} so it can be walked into like one.
     *
     * @throws IOException if the folder cannot be queried - a lost grant, a
     *                      removed SD card, anything the caller has to tell
     *                      the user about rather than read as empty.
     */
    public static List<Entry> folder(ContentResolver resolver, Uri folder) throws IOException {
        String parentId = DocumentsContract.getDocumentId(folder);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, parentId);

        Cursor cursor;
        try {
            cursor = resolver.query(children, PROJECTION, null, null, null);
        } catch (Exception e) {
            throw new IOException("cannot list " + folder, e);
        }
        if (cursor == null) throw new IOException("cannot list " + folder);

        List<Entry> entries = new ArrayList<>();
        try (Cursor result = cursor) {
            while (result.moveToNext()) {
                String documentId = result.getString(0);
                String name = result.getString(1);
                String mime = result.getString(2);
                long size = result.isNull(3) ? -1 : result.getLong(3);
                long modified = result.isNull(4) ? 0 : result.getLong(4);

                Entry.Kind kind;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    kind = Entry.Kind.FOLDER;
                } else if (Types.archive(name)) {
                    kind = Entry.Kind.ARCHIVE;
                } else if (Types.supported(name)) {
                    kind = Entry.Kind.FILE;
                } else {
                    continue;
                }

                Uri uri = DocumentsContract.buildDocumentUriUsingTree(folder, documentId);
                entries.add(new Entry(kind, name, uri, null, size, modified));
            }
        } catch (Exception e) {
            throw new IOException("cannot read " + folder, e);
        }

        entries.sort((a, b) -> {
            boolean aFolder = a.kind == Entry.Kind.FOLDER;
            boolean bFolder = b.kind == Entry.Kind.FOLDER;
            if (aFolder != bFolder) return aFolder ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });

        return entries;
    }

    /** How far {@link #everythingUnder} follows a folder into its own
     *  subfolders - see that method's own comment for why this is a cap
     *  rather than a limit worth raising. */
    private static final int MAX_FLATTEN_DEPTH = 8;

    /**
     * Every file reachable under {@code folder}, at any depth, for a filter
     * that is a question about the whole collection rather than about
     * whichever level Browse happens to be showing. Folders are excluded
     * from the result - a flat list is a list of games, and there is no
     * moving in one - but are still walked into, to whatever it holds.
     *
     * A {@code .zip} inside the tree is kept as itself, an {@link
     * Entry.Kind#ARCHIVE}, and never opened here: {@link #archive} reads a
     * whole zip's central directory to list it, and doing that for every
     * archive in a collection of hundreds while merely flattening a folder
     * view would cost minutes and a great deal of memory for a question
     * nobody asked. A game inside a zip is still reachable by walking into
     * the zip by hand; it is simply not part of a filtered listing.
     *
     * The walk stops descending past {@link #MAX_FLATTEN_DEPTH} levels below
     * {@code folder} rather than throwing - well beyond any real collection,
     * and there only so a pathological tree (a symlink loop a content
     * provider chases literally, say) cannot hang the screen.
     *
     * A subfolder below {@code folder} that cannot be read - see {@link
     * #descend} - is logged and skipped rather than losing everything
     * already found; only {@code folder} itself, the one folder the caller
     * actually chose, fails this method outright.
     *
     * @throws IOException if {@code folder} itself cannot be queried - a lost
     *                      grant, most likely, and the caller's to explain.
     */
    public static List<Entry> everythingUnder(ContentResolver resolver, Uri folder)
            throws IOException {
        List<Entry> found = new ArrayList<>();

        for (Entry entry : folder(resolver, folder)) {
            if (entry.kind == Entry.Kind.FOLDER) {
                descend(resolver, entry.uri, found, 1);
            } else {
                found.add(entry);
            }
        }

        return found;
    }

    /**
     * One level of {@link #everythingUnder}'s walk below the folder the
     * caller actually chose. Flattening queries far more folders in one
     * operation than a single-level listing ever does, so the odds of one
     * being unreadable - a permission that changed underneath, a removed SD
     * card, anything content-provider shaped - are materially higher; losing
     * the whole flattened list to it would read exactly like "nothing matches
     * this filter", the one kind of wrong answer this app has shipped twice
     * before because an empty result and a broken one looked identical. So
     * this catches and logs instead, the same call {@link
     * dev.ldlab.zedex.screen.StartPanel#collectRoms} already makes for the
     * same reason, and carries on with whatever else the walk still has.
     */
    private static void descend(ContentResolver resolver, Uri folder, List<Entry> found,
                                 int depth) {
        if (depth > MAX_FLATTEN_DEPTH) return;

        try {
            for (Entry entry : folder(resolver, folder)) {
                if (entry.kind == Entry.Kind.FOLDER) {
                    descend(resolver, entry.uri, found, depth + 1);
                } else {
                    found.add(entry);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "cannot list " + folder + " while flattening", e);
        }
    }

    /**
     * The supported entries inside a {@code .zip}, A-Z case-insensitively.
     * Directories inside the zip are skipped, as is anything
     * {@link Types#supported} rejects - and a zip entry that is itself a zip
     * is skipped too rather than offered and then refused: nothing here reads
     * an archive nested inside another one.
     */
    public static List<Entry> archive(ContentResolver resolver, Uri zip) throws IOException {
        List<Entry> entries = new ArrayList<>();

        try (InputStream in = resolver.openInputStream(zip)) {
            if (in == null) throw new IOException("cannot open " + zip);

            try (ZipInputStream zipIn = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;

                    String path = entry.getName();
                    String name = path.substring(path.lastIndexOf('/') + 1);

                    if (Types.archive(name) || !Types.supported(name)) continue;

                    long modified = entry.getTime();
                    entries.add(new Entry(Entry.Kind.FILE, name, zip, path,
                                          entry.getSize(), modified < 0 ? 0 : modified));
                }
            }
        }

        entries.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return entries;
    }

    /**
     * Extracts one archive entry into the cache and returns the file Fuse can
     * open by path.
     *
     * The extracted file is named after a hash of the entry's own key rather
     * than the entry's name alone, so two entries called the same inside two
     * different archives cannot collide: {@link Entry#key()} is the
     * <em>archive's</em> uri plus the path within it, not just the path, so
     * the hash differs whenever either does. Reusing the file this way avoids
     * doubling the folder's contents on asking for the same entry twice - but
     * only when the size still matches what the listing recorded; an archive
     * on shared storage can change between the listing and the open, and a
     * size mismatch is the cheap way to notice without reading the whole file
     * again. When the size was never known, or does not match, this
     * overwrites.
     *
     * @throws IOException if {@code entry} is not from an archive, the archive
     *                      cannot be opened, or the entry named by
     *                      {@link Entry#inside} is no longer in it.
     */
    public static File extract(Context context, Entry entry) throws IOException {
        if (entry.inside == null) {
            throw new IOException(entry.name + " is not an archive entry");
        }

        File folder = new File(context.getCacheDir(), CACHE_DIR);
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("cannot make " + folder);
        }

        String name = entry.inside.substring(entry.inside.lastIndexOf('/') + 1);
        File target = new File(folder,
                Integer.toHexString(entry.key().hashCode()) + "-" + name);

        if (entry.size >= 0 && target.isFile() && target.length() == entry.size) {
            return target;
        }

        try (InputStream in = open(context.getContentResolver(), entry);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        } catch (IOException e) {
            target.delete();
            throw e;
        }

        return target;
    }

    /**
     * The archive positioned at the one entry wanted, ready to be read until
     * the next {@code getNextEntry()} or a close. {@link ZipInputStream} has
     * no random access by name, so this is a linear scan every time; archives
     * are opened one entry at a time here; there was no listing already in
     * memory this could reuse instead.
     */
    private static InputStream open(ContentResolver resolver, Entry entry) throws IOException {
        InputStream in = resolver.openInputStream(entry.uri);
        if (in == null) throw new IOException("cannot open " + entry.uri);

        // Closed on every way out but the one that hands it to the caller.
        //
        // Not try-with-resources, because success here means *not* closing:
        // the stream is the return value, positioned at the entry. So the scan
        // is guarded by hand instead. Without this, a getNextEntry() that threw
        // partway - a truncated archive, or a SAF grant dying mid-read - leaked
        // the ZipInputStream and the ParcelFileDescriptor under it. One corrupt
        // zip in a library leaks a descriptor per attempt, the library re-lists
        // on scroll-back and on every rotation, and the process eventually
        // fails at unrelated things with EMFILE and nothing in the log naming
        // the archive that did it.
        boolean handedOver = false;

        try {
            ZipInputStream zipIn = new ZipInputStream(in);
            ZipEntry found;

            while ((found = zipIn.getNextEntry()) != null) {
                if (found.getName().equals(entry.inside)) {
                    handedOver = true;
                    return zipIn;
                }
            }

            throw new IOException(entry.inside + " is no longer in " + entry.uri);
        } finally {
            // Closing the ZipInputStream would close this too, but only if it
            // was constructed; a throw from its constructor leaves the raw
            // stream as the only thing holding the descriptor.
            if (!handedOver) in.close();
        }
    }
}
