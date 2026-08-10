package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.library.Types;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.library.scrape.Candidate;
import dev.ldlab.zedex.library.scrape.Downloads;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.Provider;
import dev.ldlab.zedex.library.scrape.Scrape;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.library.scrape.Scrapers;
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
 * is.
 *
 * <b>The cache keeps nothing afterwards, including what a failure only got
 * halfway through.</b> {@link #bring}'s {@code extracted} list is handed
 * into {@link #unzip} and filled as each entry is written, rather than
 * assembled separately and only adopted on success - so a zip that extracts
 * three files cleanly and then goes corrupt on the fourth still has all
 * three, and the partial fourth, in the list the {@code finally} below
 * cleans up. Building the list only where {@code unzip} could hand it back
 * whole was tried first and was wrong: an exception thrown partway through
 * discarded everything {@code unzip} had made so far along with it, and
 * {@code bring}'s {@code finally} saw an empty list and deleted nothing.
 */
public final class Imports {

    private static final String TAG = "Zedex";
    private static final String DOWNLOADED = "Downloaded";

    /**
     * A generous ceiling on what a genuine download can expand to.
     *
     * These archives come from the public internet and nothing upstream
     * bounds them, so without a limit a small, deliberately hostile zip
     * could fill the cache partition before {@link #bring}'s {@code finally}
     * ever runs. A real Spectrum file is kilobytes and a generous
     * multi-load - several tape sides, a handful of disks - is a few
     * megabytes, so this can be tight enough to mean something: eight
     * entries' worth of headroom past that, and a cap on the entry count
     * too, since a bomb can just as easily be a million empty names as one
     * huge one.
     */
    private static final long MAX_EXTRACTED_BYTES = 8L * 1024 * 1024;
    private static final int MAX_ENTRIES = 64;

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

    /**
     * Details and artwork for what was just imported.
     *
     * <b>The entry id goes straight through.</b> {@link Provider#fetch} takes
     * a {@link Candidate} whose handle is the catalogue's own id, so this
     * builds one around the id already in hand rather than searching by the
     * name of a file it has only this second written. That is the difference
     * between certainty and a guess, and a guess acted on silently is one
     * game's cover on another for ever.
     *
     * {@code exact} is true for the same reason: a {@link Candidate} that
     * admits to guessing sends the flow back through a dialog asking
     * somebody to confirm the game they have just chosen off a list.
     *
     * <b>{@code provider} arrives as a parameter rather than this calling
     * {@code Scrapers.preferred(context)} itself.</b> Every other caller of
     * {@link Scrape#apply} - {@code ScrapeOneGame}, {@code Sweep} - is handed
     * a concrete {@link Provider} rather than resolving one internally, and
     * for the same reason here: {@code Scrapers} always wraps a real service
     * in a real {@code Http.Real}, so resolving it from inside this method
     * would make every test of it a live network call. The caller gets it
     * from {@code Scrapers.preferred(context)} and hands it through; whether
     * there is one at all is exactly the null this method returns for.
     *
     * Returns null when {@code provider} is null, which is not a failure -
     * the file is imported either way and details are the extra, so
     * somebody with no scraper configured must still be able to import - and
     * equally null on anything {@link Scrape#apply} itself throws: a spent
     * quota or a network hiccup here is a reason to leave the game
     * undescribed, not a reason to treat an import that already succeeded
     * as a failure.
     */
    public static Downloads.Result describe(Context context, Provider provider, Http http,
                                            Result result, Catalogue.Item item) {
        if (provider == null) return null;

        // The store does not read itself - a game imported before the
        // library has run in this process would otherwise resolve against
        // an empty cache and read as unscraped, silently. Never on the UI
        // thread.
        Metadata.ensureLoaded(context);

        // relativePath answers null for anything outside the granted
        // content tree. An import is inside it by construction, but the one
        // way this can still happen is somebody re-granting a different
        // folder mid-import, and a crash there is worse than an uncovered
        // game.
        String path = Metadata.relativePath(context, result.documentUri);
        if (path == null) return null;

        Candidate candidate =
                new Candidate(item.id(), item.title(), item.year(), item.publisher(), true);

        try {
            return Scrape.apply(context, provider, http, candidate, path,
                                Scrapers.wanted(context));
        } catch (ScrapeException e) {
            Log.w(TAG, "imported " + path + " but could not describe it: " + e.kind, e);
            return null;
        }
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
                return new Result(null, null, null, false, refusalFor(refused.status));
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
            // in the zip rather than copied into somebody's library. `extracted`
            // is filled as each entry is written rather than returned whole, so
            // whatever a later failure - corrupt data, the caps above - leaves
            // half-finished is still in the list the `finally` below cleans up.
            try {
                unzip(zip, cache, extracted);
            } catch (TooLarge too) {
                return new Result(null, null, null, false, new ScrapeException(
                        ScrapeException.Kind.MALFORMED, too.getMessage()));
            } catch (IOException e) {
                return new Result(null, null, null, false, new ScrapeException(
                        ScrapeException.Kind.MALFORMED, "not a zip: " + file.url(), e));
            }

            // Step 4: one file, several, or none. ZipInputStream.getNextEntry()
            // returns null rather than throwing for a file that was never a
            // zip at all - a plain .tap, or a .gz, which Types.openable already
            // covers since libspectrum decompresses it itself - so an empty
            // extraction is not necessarily a bad archive. Before deciding
            // there is nothing usable, try the download itself as the file,
            // named from the url it came from.
            if (extracted.isEmpty()) {
                String bare = basenameOf(file.url());
                if (Types.openable(bare)) extracted.add(new Extracted(bare, zip));
            }

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
     *
     * <b>Writes into {@code found} as it goes</b>, not into a list of its
     * own returned at the end - a corrupt archive can throw after several
     * entries have already landed on disk, and a caller that only sees the
     * whole return value on success never learns those existed, which is
     * exactly how they used to leak past the cache-emptying {@code finally}
     * in {@link #bring}. Each entry is added to {@code found} before its
     * bytes are written, for the same reason: a size-cap trip mid-entry
     * still leaves that entry's half-written file where the caller can find
     * and delete it.
     *
     * @throws TooLarge if the archive holds more than {@link #MAX_ENTRIES}
     *         entries or would extract past {@link #MAX_EXTRACTED_BYTES} -
     *         these are untrusted downloads and nothing upstream bounds them.
     */
    private static void unzip(File zip, File cache, List<Extracted> found) throws IOException {
        long totalBytes = 0;
        int count = 0;

        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;

                if (++count > MAX_ENTRIES) {
                    throw new TooLarge("more than " + MAX_ENTRIES + " entries inside");
                }

                String raw = entry.getName();
                int slash = raw.lastIndexOf('/');
                String name = slash >= 0 ? raw.substring(slash + 1) : raw;

                if (!Types.openable(name)) continue;

                File target = new File(cache, "zedex-" + System.nanoTime() + "-" + count);
                // Tracked before a byte is written: a size-cap trip partway
                // through this entry still leaves a file behind, and it has
                // to be in the caller's list to be cleaned up.
                found.add(new Extracted(name, target));

                try (FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    for (int read; (read = in.read(buffer)) != -1; ) {
                        totalBytes += read;
                        if (totalBytes > MAX_EXTRACTED_BYTES) {
                            throw new TooLarge("extracted past " + MAX_EXTRACTED_BYTES
                                    + " bytes");
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    /** Raised past {@link #MAX_ENTRIES} or {@link #MAX_EXTRACTED_BYTES} -
     *  kept apart from a plain {@link IOException} so {@link #bring} can
     *  report why rather than folding it into "not a zip". */
    private static final class TooLarge extends IOException {
        TooLarge(String message) {
            super(message);
        }
    }

    /**
     * What a bare HTTP refusal means, split by status the way {@code
     * ZxInfoCatalogue.refusalFor} already does - only the status says
     * whether asking again could ever help, and treating every refusal as a
     * hiccup tells somebody to retry a permanent 404 forever.
     */
    private static ScrapeException refusalFor(int status) {
        if (status == 429 || status == 403) {
            return new ScrapeException(ScrapeException.Kind.CLOSED,
                    "the server answered " + status + ", which usually means an address"
                            + " has been asking too often");
        }

        if (status >= 500) {
            return new ScrapeException(ScrapeException.Kind.NETWORK,
                                       "the server answered " + status);
        }

        return new ScrapeException(ScrapeException.Kind.MALFORMED,
                                   "the server answered " + status);
    }

    /** The last path segment of a url, ignoring any query string - what a
     *  download is called when nothing more specific is on offer. */
    private static String basenameOf(String url) {
        if (url == null) return "";

        String noQuery = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int slash = noQuery.lastIndexOf('/');
        return slash >= 0 ? noQuery.substring(slash + 1) : noQuery;
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) Log.w(TAG, "cannot remove " + file);
    }
}
