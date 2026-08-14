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
     * ever runs. A real Spectrum file is kilobytes - a 48K snapshot is 49,
     * a tape image rarely more - and a generous multi-load of several tape
     * sides or a handful of disks is a couple of megabytes, so eight is
     * roomy for anything genuine and small enough to be worth having.
     */
    private static final long MAX_EXTRACTED_BYTES = 8L * 1024 * 1024;

    /**
     * How many files one import may put in front of somebody, and how many
     * names it will read to find them. <b>Two numbers, because they answer
     * two questions.</b>
     *
     * {@link #MAX_ENTRIES} bounds what is <em>kept</em> - counted after
     * {@link Types#openable}, which is the correction that matters here: it
     * used to count every entry the archive declared, so a perfectly
     * ordinary release with seventy cover scans and one {@code .tap} in it
     * was refused as a bomb. Sixty-four playable files is far past any real
     * multi-load; nothing measured needs more than a dozen.
     *
     * {@link #MAX_SCANNED} bounds the <em>walk</em>, which is the thing a
     * bomb of a million empty names actually costs - {@code
     * ZipInputStream.getNextEntry} reads through each entry's data to reach
     * the next, so an archive can be expensive without a byte of it ever
     * being kept. Four thousand is nowhere near any real archive and stops
     * that walk while it is still cheap. It counts <b>every entry the walk
     * visits, directories included</b>: a million empty names <em>is</em> a
     * million directory entries, so a cap that skipped those before counting
     * would be a cap that missed the case it was written for.
     */
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_SCANNED = 4096;

    private Imports() {
    }

    /**
     * What one import came to. A plain record, no logic: {@link #failure} is
     * one of the kinds a screen already knows how to explain, or null.
     */
    public static final class Result {

        /** What was imported, as somebody would find it: the file, or - for a
         *  multi-load - the folder holding its several files. */
        public final Uri documentUri;

        /**
         * Where the game's details and artwork belong, which is not always
         * {@link #documentUri}.
         *
         * <b>A folder has no row that can draw them.</b> {@code
         * EntryAdapter.onBind} returns before it looks anything up for an
         * {@code Entry.Kind.FOLDER} - correctly, since a folder is not a game
         * - so a {@code Meta} keyed on {@code ./Downloaded/Games/<Title>} is
         * written where nothing will ever read it: the scrape request and
         * every picture it downloaded are spent on a key no row uses, the
         * multi-load game shows as a bare folder name, and the orphan is still
         * counted by {@code Facets.of(Metadata.all(...))}, which then offers a
         * filter value that can select nothing.
         *
         * So a folder import points this at its <b>first member</b> - first in
         * the archive's own order, which for a multi-load is side one or disk
         * one - and the details land on a row somebody can see, in the folder
         * they will open to play it. The alternative considered was describing
         * nothing at all and saying so, which spends no request but leaves the
         * commonest multi-load arriving with no name, no cover and no details
         * at all, against a README that promises otherwise.
         *
         * Equal to {@link #documentUri} for every single-file import, which is
         * almost all of them.
         */
        public final Uri describeUri;

        public final String displayName;
        public final String folder;
        public final boolean alreadyThere;
        public final ScrapeException failure;

        /** One file: the document is also where its details go. */
        public Result(Uri documentUri, String displayName, String folder,
                      boolean alreadyThere, ScrapeException failure) {
            this(documentUri, documentUri, displayName, folder, alreadyThere, failure);
        }

        public Result(Uri documentUri, Uri describeUri, String displayName, String folder,
                      boolean alreadyThere, ScrapeException failure) {
            this.documentUri = documentUri;
            this.describeUri = describeUri;
            this.displayName = displayName;
            this.folder = folder;
            this.alreadyThere = alreadyThere;
            this.failure = failure;
        }
    }

    /**
     * What comes out of the archive, and out of a download that was never one.
     *
     * The whole pipeline was written around one answer - keep what the
     * emulator can open, leave the readme and the cover in the zip - which is
     * right for a game and is the wrong question entirely for a book. A fifth
     * of this catalogue is books, magazines and hardware; their file is a PDF,
     * and "nothing inside this app can open" was both true and the end of the
     * matter.
     */
    private enum Keep {

        /** A game, a recording: the machine's own formats and nothing else. */
        WHAT_THE_MACHINE_OPENS,

        /**
         * Whatever arrived - a document the phone will open rather than the
         * machine.
         *
         * The caps still apply: an archive is still walked, still bounded, and
         * still refused when it is absurd. What is dropped is only the test of
         * whether Fuse would take the file, which for a book is not a question
         * worth asking.
         */
        WHATEVER_ARRIVED,
    }

    /** One entry from the catalogue, filed under the folder its own kind
     *  maps to - see {@link Kinds#folderFor}. */
    public static Result game(Context context, Http http, Catalogue.Item item,
                              Catalogue.Download file) {
        return bring(context, http, item, file, Kinds.folderFor(item.kind()),
                     Keep.WHAT_THE_MACHINE_OPENS);
    }

    /**
     * A file for the reader rather than the machine - a book's PDF, a
     * magazine, a scanned advertisement.
     *
     * The same folder its own kind maps to, exactly like {@link #game}: it is
     * still that entry, and {@code Downloaded/Other/} is where a book belongs
     * whether or not the app can open it. What differs is only what is kept
     * out of the download; see {@link Keep}.
     */
    public static Result document(Context context, Http http, Catalogue.Item item,
                                  Catalogue.Download file) {
        return bring(context, http, item, file, Kinds.folderFor(item.kind()),
                     Keep.WHATEVER_ARRIVED);
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
        return bring(context, http, item, file, Kinds.RECORDINGS,
                     Keep.WHAT_THE_MACHINE_OPENS);
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
     * {@code Scrapers} itself.</b> {@link Scrape#apply} has exactly one
     * caller left, and this is it - {@code ScrapeOneGame} and {@code Sweep}
     * both used to call it directly and have since moved to {@code
     * Blend.run}, which merges rather than replaces (see {@code ScrapeTest}'s
     * own class doc for why that changed and why this call site did not).
     * Both the old callers and {@code Blend} share the same shape this one
     * does, for the same reason: a concrete {@link Provider} - or list of
     * them - is handed in rather than resolved internally, because {@code
     * Scrapers} always wraps a real service in a real {@code Http.Real}, so
     * resolving it from inside this method would make every test of it a
     * live network call.
     *
     * <b>It must not be the first of {@code Scrapers.enabled(context)}, and
     * never was meant to be.</b> The whole reason {@code item.id()} goes
     * straight through as an already-matched {@link Candidate} is that it is
     * the catalogue's own id - certain against the service that issued it,
     * and meaningless to any other. {@code Scrapers.enabled} answers
     * whichever scrapers the user picked for ordinary scraping, in whatever
     * order, which need not put, and by default on a build with ScreenScraper
     * credentials baked in does not put, the service this id came from;
     * handing a ZXInfo id to ScreenScraper is
     * not a lookup, it is a coincidence waiting to happen - at best {@link
     * Provider#fetch} refuses it outright, at worst it is answered by
     * whatever that other service's own numbering happens to mean by the
     * same number, and the game is silently described with somebody else's
     * details and cover. The caller must instead resolve the one provider
     * whose {@code name()} matches the catalogue's own - see {@code
     * CataloguePane.providerFor} - and hand through null when nothing
     * matches, which this method already treats as the clean, honest
     * outcome below.
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

        // describeUri, not documentUri: they are the same thing for a single
        // file and, for a folder of several, this is the member a row can
        // actually draw the answer on. See Result.describeUri.
        //
        // relativePath answers null for anything outside the granted
        // content tree. An import is inside it by construction, but the one
        // way this can still happen is somebody re-granting a different
        // folder mid-import, and a crash there is worse than an uncovered
        // game.
        String path = Metadata.relativePath(context, result.describeUri);
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
                                Catalogue.Download file, String folder, Keep keep) {
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
                unzip(zip, cache, extracted, keep);
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
                String bare = namedFrom(file);
                if (keep == Keep.WHATEVER_ARRIVED || Types.openable(bare)) {
                    extracted.add(new Extracted(bare, zip));
                }
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
     *
     * <b>The first member is remembered, and it is not decoration.</b> The
     * folder is what somebody sees and what {@link Result#documentUri} points
     * at; the details have to go somewhere a row will read them, and a folder
     * row never does - see {@link Result#describeUri}. First in the archive's
     * own order, which nothing here re-sorts, so for a multi-load it is side
     * one or disk one.
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

        Uri first = null;

        for (Extracted one : files) {
            Uri already = Tree.find(context, gameFolder, one.name);
            if (already != null) {
                if (first == null) first = already;
                continue;
            }

            Uri written = Tree.write(context, gameFolder, one.name, one.file);
            if (written == null) {
                return new Result(null, null, null, false, new ScrapeException(
                        ScrapeException.Kind.MALFORMED, "cannot write " + one.name));
            }
            if (first == null) first = written;
        }

        return new Result(gameFolder, first, title, folder, alreadyThere, null);
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
     *         files this app can open, declares more than {@link
     *         #MAX_SCANNED} entries at all, or would extract past {@link
     *         #MAX_EXTRACTED_BYTES} - these are untrusted downloads and
     *         nothing upstream bounds them.
     */
    private static void unzip(File zip, File cache, List<Extracted> found, Keep keep)
            throws IOException {
        long totalBytes = 0;
        int scanned = 0;
        int kept = 0;

        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                // Counted before anything is skipped, because the walk has
                // already paid for this entry by the time we can see what it
                // is. Counting after the directory skip left the one case
                // this cap is named for entirely unbounded: an archive of a
                // million empty names is an archive of a million directory
                // entries, and none of them were ever counted.
                if (++scanned > MAX_SCANNED) {
                    throw new TooLarge("more than " + MAX_SCANNED + " entries inside");
                }

                if (entry.isDirectory()) continue;

                String raw = entry.getName();
                int slash = raw.lastIndexOf('/');
                String name = slash >= 0 ? raw.substring(slash + 1) : raw;

                // Counted here and not above: an archive is allowed to be
                // full of things this app does not want. See MAX_ENTRIES.
                if (keep == Keep.WHAT_THE_MACHINE_OPENS && !Types.openable(name)) continue;

                if (++kept > MAX_ENTRIES) {
                    throw new TooLarge("more than " + MAX_ENTRIES
                            + " openable files inside");
                }

                File target = new File(cache, "zedex-" + System.nanoTime() + "-" + scanned);
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

    /** Raised past any of the three caps above -
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

    /**
     * The name to give a download that was never a zip - its own basename,
     * with the catalogue's stated format appended when the url gave none.
     *
     * <b>zxart's rendered picture has no extension to read off the url at
     * all</b> ({@code zximages/id=2232;border=0;pal=srgb;type=standard;zoom=1}
     * - measured: 200, PNG magic number, no {@code Content-Type} header). A
     * document written under that bare name still opens - the emulator does
     * not care what a file is called - but nothing else does: {@code
     * CataloguePane.openOutside} hands the document off with whatever {@code
     * ContentResolver.getType()} answers, and SAF derives that from the
     * <em>display name</em>'s extension, not from anything the writer passed
     * in. So a name with no extension is a document with no openable type,
     * silently - "Open" resolves to nothing and there is no error to show,
     * because nothing failed.
     *
     * <b>{@code Tree.write}'s own mime argument is not the fix and must stay
     * {@code application/octet-stream}.</b> It is inert by the time Open
     * asks: SAF ignores what a document was created with and re-derives the
     * type from the name every time it is queried. That inert argument is
     * also why today's PDF imports already work through this same call -
     * their basename already ends {@code .pdf}, so the derived type is
     * right without anyone stating a mime at all. Passing a better mime here
     * would look like a fix and change nothing; the name is the only lever
     * that reaches {@code getType()}.
     *
     * {@link Catalogue.Download#format()} is lower-case and dotless by
     * contract, which is what makes a bare {@code "." + format} always
     * correct. Only a url with no extension at all is affected - a game's
     * own file, and a tune's {@code mp3FilePath}, both already end in one.
     */
    private static String namedFrom(Catalogue.Download file) {
        String bare = basenameOf(file.url());
        if (bare.contains(".")) return bare;

        String format = file.format();
        return format.isEmpty() ? bare : bare + "." + format;
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
