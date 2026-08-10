package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.library.Types;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.storage.Tree;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turning one catalogue entry into a file - or a folder of them - in
 * somebody's own content tree.
 *
 * The order below is the design and is not to be reshuffled: into the cache
 * first, then the stated length is checked, then the zip is opened, and only
 * then does anything reach SAF. SAF writes are not atomic and a half-written
 * {@code .tap} is indistinguishable from a real one - the emulator's refusal
 * to load it reads as a broken download of a working game, not as what it
 * is. Every path out of {@link #bring} deletes whatever it put in the cache,
 * so a failed import leaves nothing behind, including there.
 */
public final class Imports {

    private static final String TAG = "Zedex";
    private static final String DOWNLOADED = "Downloaded";

    private Imports() {
    }

    /**
     * What one import came to. A plain record, no logic: {@link #failure} is
     * one of the three kinds a screen already knows how to explain, or null.
     */
    public static final class Result {
        public final Uri documentUri;
        public final String displayName;
        public final String folder;
        public final boolean alreadyThere;
        public final ScrapeException failure;

        public Result(Uri documentUri, String displayName, String folder,
                      boolean alreadyThere, ScrapeException failure) {
            this.documentUri = documentUri;
            this.displayName = displayName;
            this.folder = folder;
            this.alreadyThere = alreadyThere;
            this.failure = failure;
        }
    }

    /** One entry from the catalogue, filed under the folder its own kind
     *  maps to - see {@link Kinds#folderFor}. */
    public static Result game(Context context, Http http, Catalogue.Item item,
                              Catalogue.Download file) {
        return bring(context, http, item, file, Kinds.folderFor(item.kind()));
    }

    /**
     * A recording of somebody playing the item, filed under {@link
     * Kinds#RECORDINGS} whatever the item's own kind says.
     *
     * The one place a file's kind outranks the entry's category: the folder
     * scheme answers "what kind of thing is this file", and a recording of
     * Bomb Jack in the Games folder is not Bomb Jack.
     */
    public static Result recording(Context context, Http http, Catalogue.Item item,
                                   Catalogue.Download file) {
        return bring(context, http, item, file, Kinds.RECORDINGS);
    }

    /** One extracted entry: the name it carried inside the zip, and where it
     *  landed in the cache while it waits to be written through SAF. */
    private static final class Extracted {
        final String name;
        final File file;

        Extracted(String name, File file) {
            this.name = name;
            this.file = file;
        }
    }

    private static Result bring(Context context, Http http, Catalogue.Item item,
                                Catalogue.Download file, String folder) {
        File cache = new File(context.getCacheDir(), "imports");
        List<Extracted> extracted = new ArrayList<>();
        File zip = new File(cache, "zedex-" + System.nanoTime() + ".zip");

        try {
            if (!cache.isDirectory() && !cache.mkdirs()) {
                return new Result(null, null, null, false, new ScrapeException(
                        ScrapeException.Kind.MALFORMED, "cannot use the cache folder"));
            }

            // Step 1 & 2: into the cache first, then check the stated length.
            // ZXDB gives a size per file and no checksum, so length is what
            // there is - a truncated zip that unpacks to half a tape is a
            // game that loads and then crashes, which nobody attributes to
            // the download.
            try {
                http.save(file.url(), zip);
            } catch (Http.Refused refused) {
                return new Result(null, null, null, false, new ScrapeException(
                        ScrapeException.Kind.NETWORK,
                        "the server answered " + refused.status, refused));
            } catch (IOException e) {
                return new Result(null, null, null, false, new ScrapeException(
                        ScrapeException.Kind.NETWORK, "cannot fetch " + file.url(), e));
            }

            if (file.size() >= 0 && zip.length() < file.size()) {
                return new Result(null, null, null, false, new ScrapeException(
                        ScrapeException.Kind.MALFORMED,
                        "arrived short: " + zip.length() + " of " + file.size()
                                + " bytes"));
            }

            // Step 3: unzip, keeping only what the emulator can open. A
            // .rzx import keeps the .rzx - Types.openable already contains
            // it - and a readme or a cover sitting beside the game is left
            // in the zip rather than copied into somebody's library.
            try {
                extracted.addAll(unzip(zip, cache));
            } catch (IOException e) {
                return new Result(null, null, null, false, new ScrapeException(
                        ScrapeException.Kind.MALFORMED, "not a zip: " + file.url(), e));
            }

            // Step 4: one file, several, or none.
            if (extracted.isEmpty()) {
                return new Result(null, null, null, false, new ScrapeException(
                        ScrapeException.Kind.MALFORMED,
                        "nothing inside this app can open"));
            }

            Uri tree = Storage.contentFolder(context);
            if (tree == null) {
                return new Result(null, null, null, false, new ScrapeException(
                        ScrapeException.Kind.NOT_CONFIGURED, "no content folder granted"));
            }

            // Step 5: Downloaded/<folder>/ - folder is Kinds.RECORDINGS for
            // Imports.recording and Kinds.folderFor(item.kind()) for
            // Imports.game, decided by the caller above.
            Uri destination = Tree.folder(context, tree, DOWNLOADED, folder);
            if (destination == null) {
                return new Result(null, null, null, false, new ScrapeException(
                        ScrapeException.Kind.MALFORMED,
                        "cannot reach " + DOWNLOADED + "/" + folder));
            }

            if (extracted.size() == 1) {
                List<String> names = new ArrayList<>();
                names.add(extracted.get(0).name);
                String name = nameInside(file.url(), names);

                return writeOne(context, destination, name, extracted.get(0).file, folder);
            }

            return writeFolder(context, destination, item.title(), extracted, folder);

        } finally {
            // Step 7: whatever happened, the cache keeps nothing.
            delete(zip);
            for (Extracted one : extracted) delete(one.file);
        }
    }

    /**
     * The name to give a single file coming out of a zip - its own name,
     * never the zip's.
     *
     * The archive ships {@code HeadOverHeels.tzx.zip} holding {@code
     * HeadOverHeels.tzx}, and it is the second of those the metadata store
     * has to key on, or an import and a hand-copied file stop looking
     * identical to everything downstream. Takes the url only so a mismatch
     * can be logged against where the file came from.
     *
     * @return the one name, when there is exactly one; null otherwise - a
     *         caller with zero or several has already made its own decision.
     */
    public static String nameInside(String url, List<String> insideNames) {
        if (insideNames != null && insideNames.size() == 1) return insideNames.get(0);

        Log.w(TAG, (insideNames == null || insideNames.isEmpty()
                    ? "nothing openable inside " : "more than one candidate inside ") + url);
        return null;
    }

    /**
     * One openable file inside the destination folder, under its own name.
     *
     * Step 6: already there is not an error. SAF would happily create a
     * second {@code HeadOverHeels (1).tzx}, which is how a collection
     * acquires four of everything and how somebody comes to think the first
     * import failed.
     */
    private static Result writeOne(Context context, Uri destination, String name,
                                   File source, String folder) {
        Uri existing = Tree.find(context, destination, name);
        if (existing != null) return new Result(existing, name, folder, true, null);

        Uri written = Tree.write(context, destination, name, source);
        if (written == null) {
            return new Result(null, null, null, false, new ScrapeException(
                    ScrapeException.Kind.MALFORMED, "cannot write " + name));
        }
        return new Result(written, name, folder, false, null);
    }

    /**
     * Several openable files, in a folder named after the item - the library
     * already browses folders, so that is where a multi-load game goes.
     */
    private static Result writeFolder(Context context, Uri destination, String title,
                                      List<Extracted> files, String folder) {
        Uri existing = Tree.find(context, destination, title);
        boolean alreadyThere = existing != null;

        Uri gameFolder = existing != null ? existing : Tree.folder(context, destination, title);
        if (gameFolder == null) {
            return new Result(null, null, null, false, new ScrapeException(
                    ScrapeException.Kind.MALFORMED, "cannot create " + title));
        }

        for (Extracted one : files) {
            if (Tree.find(context, gameFolder, one.name) != null) continue;

            Uri written = Tree.write(context, gameFolder, one.name, one.file);
            if (written == null) {
                return new Result(null, null, null, false, new ScrapeException(
                        ScrapeException.Kind.MALFORMED, "cannot write " + one.name));
            }
        }

        return new Result(gameFolder, title, folder, alreadyThere, null);
    }

    /**
     * Unpacks every entry {@link Types#openable} can load, named as it is
     * named inside the zip and never as the zip itself is named - a zip
     * entry's own path may hold a folder of its own, which is stripped since
     * it is the zip's business and not this app's.
     *
     * Each is written under a name of its own in the cache; what it is
     * called once it reaches SAF is a separate decision the caller makes
     * from {@link Extracted#name}, not from this file's name on disk.
     */
    private static List<Extracted> unzip(File zip, File cache) throws IOException {
        List<Extracted> found = new ArrayList<>();
        int index = 0;

        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;

                String raw = entry.getName();
                int slash = raw.lastIndexOf('/');
                String name = slash >= 0 ? raw.substring(slash + 1) : raw;

                if (!Types.openable(name)) continue;

                File target = new File(cache, "zedex-" + System.nanoTime() + "-" + (index++));
                try (FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    for (int read; (read = in.read(buffer)) != -1; ) {
                        out.write(buffer, 0, read);
                    }
                }

                found.add(new Extracted(name, target));
            }
        }

        return found;
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) Log.w(TAG, "cannot remove " + file);
    }
}
