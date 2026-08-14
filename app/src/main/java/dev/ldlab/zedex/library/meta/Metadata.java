package dev.ldlab.zedex.library.meta;

import dev.ldlab.zedex.storage.Storage;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Our own store of what a link to ES-DE found and what this app has scraped
 * since, in the app's data folder rather than beside the games - the content
 * folder can be read-only, and a library that cannot record what it learned is
 * worse than one that does not travel. See "Metadata lives in the app's data
 * folder" in docs/LIBRARY.md.
 *
 * One file, {@code library/metadata.json}, keyed by the game's path, with
 * {@code linked} at the top - the epoch millis of the last write, which is the
 * one thing {@link #lastLinked} needs and not worth reading every game to
 * learn.
 *
 * <b>It was ES-DE's gamelist.xml, and is not any more.</b> Borrowing their
 * schema bought a file that opened as something familiar, and cost a growing
 * pile of {@code zedex*} elements ES-DE will never read in a file ES-DE
 * rewrites - which stops scaling the moment a provider offers fields their
 * format has no room for. This is our own file saying so, and it can hold
 * things XML could not: see {@link #write} for the control character that once
 * took eight hundred games' metadata down with it. Reading <em>ES-DE's</em>
 * gamelist is a separate job and still XML; see {@link EsdeLink}.
 *
 * Blocking, like {@code library.Favorites}: read and written whole, because a
 * few hundred games is still a small file and nothing here does a query a
 * database would earn its keep on. A missing, unreadable or malformed file
 * reads as no metadata at all - logged once, never thrown - and {@link
 * #replaceAll} writes to a temporary file first so a crash mid-write never
 * leaves metadata.json itself half written.
 *
 * The parsed file is cached in memory and only re-read when its own
 * modification time has moved on - which happens only when this class writes
 * it - so drawing a list of a few hundred rows costs one parse, not one per
 * row.
 */
public final class Metadata {

    private static final String TAG = "Zedex";
    private static final String FILE = "metadata.json";

    /**
     * What shape the file on disk is in.
     *
     * Read and ignored today, and here from the start anyway: a reader that
     * has never looked cannot tell version two from a corrupt version one, and
     * by then the file exists on people's devices. Unknown keys are skipped
     * rather than refused, so a newer file still loads whatever this build
     * understands.
     */
    private static final int VERSION = 1;

    private static final String VERSION_KEY = "version";
    private static final String LINKED = "linked";
    private static final String GAMES = "games";

    private static final class Store {
        final long mtime;
        final long linkedAt;
        final Map<String, Meta> games;

        Store(long mtime, long linkedAt, Map<String, Meta> games) {
            this.mtime = mtime;
            this.linkedAt = linkedAt;
            this.games = games;
        }
    }

    private static final Store EMPTY = new Store(0, 0, Collections.emptyMap());

    private static volatile Store cache;

    /** {@link #resolve}'s own answers, keyed by the document it was asked
     *  about - so a game opened the same way twice, {@code Open recent…}
     *  most often, does not read and hash a handful of same-named files a
     *  second time for an answer it already worked out. A miss is cached
     *  too, as {@code null}, which is why {@link Map#containsKey} rather
     *  than a null check is what {@link #resolve} tests this with. Cleared
     *  whenever the store itself is - see {@link #store}, {@link
     *  #replaceAll} and {@link #clear} - since a different set of games can
     *  only mean a different answer. */
    private static final Map<String, String> resolveCache = new HashMap<>();

    private Metadata() {
    }

    /** The stored facts for one game, or null when nothing is known about it. */
    public static Meta forPath(Context context, String relativePath) {
        return store(context).games.get(relativePath);
    }

    /** How many games the store holds. */
    public static int count(Context context) {
        return store(context).games.size();
    }

    /**
     * Every game the store knows, for {@code Facets} to count.
     *
     * A copy rather than the live map: the caller walks this on a background
     * thread, and the store can be replaced by a link while it does.
     */
    public static java.util.Collection<Meta> all(Context context) {
        return new java.util.ArrayList<>(store(context).games.values());
    }

    /**
     * A token that changes whenever the store does, and not otherwise.
     *
     * The Store object itself: it is immutable and replaced wholesale, so its
     * identity is exactly "which version of the facts is in memory". A caller
     * that derives something expensive from {@link #all} - the filter sheet's
     * genres, developers and publishers, counted over every game - can hold
     * its answer beside this and know in one reference comparison whether it
     * is still the answer.
     */
    public static Object version(Context context) {
        return store(context);
    }

    /** When the store was last written - a link, or an edit - as epoch
     *  millis, or 0 if it never has been. */
    public static long lastLinked(Context context) {
        return store(context).linkedAt;
    }

    /**
     * What a link writes: every scraped row replaced, every hand-edited one
     * kept.
     *
     * Pressing Link again is meant to replace what ES-DE owns - whatever it
     * still has is written again, a game that appeared is added by being in
     * {@code games} at all, and a game that is gone is dropped by not being
     * in it. The caller decides what {@code games} holds.
     *
     * What it does <em>not</em> replace is a row somebody edited by hand. Those
     * carry {@link Meta#USER} and survive untouched, and a scraped row for the
     * same game gives way to them - see {@link Meta#source}, where the rule
     * and what it costs are written down. Unlink is still {@link #clear}, which
     * takes everything: that is what unlinking means, and it is a different
     * button.
     *
     * Not refused when {@code games} is empty. {@link EsdeLink#read} throws
     * rather than returning empty for every case that used to masquerade as
     * "nothing to link" - a lapsed grant, ES-DE gone missing - so an empty
     * list reaching here is meant to be trustworthy: a real collection can
     * genuinely go to zero. But replacing everything this app ever recorded
     * with nothing at all is exactly the shape a failure elsewhere would
     * take too, and logging it costs nothing, so it is loud about doing it
     * rather than silently trusting the caller a second time.
     */
    public static synchronized void replaceScraped(Context context, List<Meta> games) {
        List<Meta> keeping = new ArrayList<>(games);

        // Every row ES-DE does not own survives the link and wins over a
        // scraped row for the same game - a hand edit, and anything this app
        // fetched from a provider of its own. Added after the scraped ones so
        // the map below takes them last. See Meta#isEsde, and why "not ES-DE's"
        // rather than "hand edited" is the rule that generalises.
        for (Meta mine : store(context).games.values()) {
            if (!mine.isEsde()) keeping.add(mine);
        }

        write(context, keeping);
    }

    /**
     * Replaces the store outright, hand-edited rows included.
     *
     * Only {@link #replaceScraped} above and the tests want this; a link goes
     * through that one, because a link that discarded somebody's own
     * corrections would be doing it silently and on a button they press
     * whenever anything is scraped.
     */
    static synchronized void replaceAll(Context context, List<Meta> games) {
        write(context, games);
    }

    private static void write(Context context, List<Meta> games) {
        File file = file(context);
        File directory = file.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            Log.w(TAG, "cannot make " + directory);
            return;
        }

        int had = store(context).games.size();
        if (games.isEmpty() && had > 0) {
            Log.w(TAG, "replacing " + had + " known game(s) with none at all");
        }

        long linkedAt = System.currentTimeMillis();
        Map<String, Meta> byPath = new HashMap<>();
        for (Meta game : games) {
            if (game.path != null && !game.path.isEmpty()) byPath.put(game.path, game);
        }

        // A temporary file first: a write that fails partway through must not
        // leave the store itself half written, which would read back as "no
        // metadata at all" the moment it lost its closing brace.
        File temp = new File(directory, FILE + ".tmp");

        try (OutputStream out = new FileOutputStream(temp)) {
            write(out, linkedAt, games);
        } catch (Exception e) {
            Log.w(TAG, "cannot write " + file, e);
            temp.delete();
            return;
        }

        if (!temp.renameTo(file)) {
            Log.w(TAG, "cannot replace " + file);
            temp.delete();
            return;
        }

        cache = new Store(file.lastModified(), linkedAt, byPath);
        resolveCache.clear(); // a different set of games can only mean a different answer
    }

    /**
     * Writes one game, as the editor's Save does.
     *
     * The whole store is read, this one row replaced by path, and the whole
     * thing written again - which is what every other write here does, and
     * what the file's own shape asks for: it is parsed whole and serialised
     * whole, and a thousand games is one pass.
     *
     * {@code game} arrives already carrying {@link Meta#USER}, because {@link
     * Meta#with} sets it - there is no way to change a field without meaning
     * to own the row.
     */
    public static synchronized void put(Context context, Meta game) {
        if (game == null || game.path == null || game.path.isEmpty()) {
            Log.w(TAG, "cannot store a game with no path");
            return;
        }

        List<Meta> keeping = new ArrayList<>();
        for (Meta existing : store(context).games.values()) {
            if (!game.path.equals(existing.path)) keeping.add(existing);
        }
        keeping.add(game);

        write(context, keeping);
    }

    /**
     * One more start of this game.
     *
     * <b>Not a hand edit.</b> {@code but()} carries the contributor list over
     * untouched, so a row this grows is still whichever sources wrote it -
     * marking it {@code USER} would make an ordinary game somebody played read
     * as one they had corrected, which is what {@code Sweep.Only.NOT_SCRAPED}
     * and the ES-DE link both turn on.
     *
     * <b>A row is made for a game that has none.</b> Most of a fresh
     * collection is unscraped, and a count that only worked for scraped games
     * would be a count of the wrong thing. A row carrying nothing but a path
     * and a number still reads as unscraped everywhere it matters - {@code
     * Meta.isEsde} answers true for no contributors at all, which is what
     * keeps it in a sweep's sights.
     *
     * Must not run on the UI thread: {@link #put} rewrites the store, and
     * {@link #forPath} needs it loaded first. {@code
     * EmulatorActivity.gameOpened} calls this from the background thread it
     * already has for exactly that reason.
     */
    public static synchronized void played(Context context, String path) {
        if (path == null || path.isEmpty()) return;

        ensureLoaded(context);

        Meta known = forPath(context, path);
        if (known == null) known = Meta.at(path).build();

        put(context, known.but().playCount(String.valueOf(known.plays() + 1)).build());
    }

    /**
     * Drops one game's row - the editor's "forget my edits".
     *
     * The way back out of {@link Meta#USER}: the row goes, the game reads as
     * unscraped, and the next link brings ES-DE's own version of it again.
     * There is a gap in between where the game has nothing, which is why the
     * wording on the button says so rather than calling itself "revert".
     */
    public static synchronized void forget(Context context, String path) {
        if (path == null || path.isEmpty()) return;

        List<Meta> keeping = new ArrayList<>();
        boolean dropped = false;

        for (Meta existing : store(context).games.values()) {
            if (path.equals(existing.path)) dropped = true;
            else keeping.add(existing);
        }

        if (dropped) write(context, keeping);
    }

    /** Forgets everything - Unlink in Settings. */
    public static synchronized void clear(Context context) {
        File file = file(context);
        if (file.isFile() && !file.delete()) {
            Log.w(TAG, "cannot delete " + file);
        }
        cache = EMPTY;
        resolveCache.clear();
    }

    /**
     * The path ES-DE would key {@code document} by - {@code "./GOTY/.../Dizzy
     * VIII ... v1.0.tap"} - derived from the document ids alone rather than by
     * asking the provider anything, because this runs once per visible row
     * while a list is drawn and has to stay cheap. A tree child's document id
     * is the root's own id with the path beneath it appended - the same fact
     * {@code Storage.pathFor} relies on to go from a tree to a real path - so
     * the difference between the two ids is exactly the relative path
     * wanted, and {@link DocumentsContract#getDocumentId} already returns it
     * decoded.
     *
     * @return null when {@code document} is not inside the content folder at
     *         all - a different grant, an archive entry, a hand-off from
     *         somewhere else - because guessing a key wrong would quietly
     *         attach one game's description to a different one.
     */
    public static String relativePath(Context context, Uri document) {
        Uri root = Storage.contentFolder(context);
        if (root == null || document == null) return null;
        if (!java.util.Objects.equals(root.getAuthority(), document.getAuthority())) return null;

        try {
            if (!DocumentsContract.getTreeDocumentId(root)
                    .equals(DocumentsContract.getTreeDocumentId(document))) {
                return null;
            }

            String rootId = DocumentsContract.getDocumentId(root);
            String docId = DocumentsContract.getDocumentId(document);

            if (!docId.startsWith(rootId + "/")) return null;

            return "./" + docId.substring(rootId.length() + 1);
        } catch (Exception e) {
            // A document uri that does not carry the ids this expects - not
            // one of ours to match, and not a crash either.
            return null;
        }
    }

    /**
     * The library's own key for {@code document}, or null when nothing here
     * can name it - the ordinary answer, since plenty of what opens this app
     * has nothing to do with the library at all, and most of a collection is
     * unscraped besides.
     *
     * Tries {@link #relativePath} first, which is exact and, being pure
     * document-id arithmetic, costs nothing to try even when it is going to
     * fail. That already covers a file manager's hand-over and <em>Open
     * recent…</em> exactly as it covers a row of the library itself, since
     * all three are documents from the very tree this app holds a grant for.
     *
     * ES-DE's own {@code %ROMPROVIDER%} hand-off is the case that needs the
     * rest of this: the document comes from ES-DE's own provider, so its
     * authority never matches the content tree's and {@link #relativePath}
     * answers null even when it is the very file sitting in the library.
     * {@code displayName} - the name the caller's own provider gave the
     * document - is matched against the store by filename alone.
     *
     * One match by name is used outright. Several is not a guess: two
     * folders holding a file of the same name is a real thing in a
     * collection like this, so {@link #resolveByHash} reads the bytes
     * instead, of {@code document} and of each same-named candidate in
     * turn, and answers with whichever candidate's own bytes are the same
     * file as the one actually opened - which settles it as a fact rather
     * than a guess, and settles it even when the match is not unique
     * either, since two files with the same name <em>and</em> the same
     * bytes are the same game twice over, not two games to choose between.
     * No candidate's bytes agreeing, the same as no candidate sharing the
     * name at all, is answered with null.
     *
     * A file parse, the same one {@link #forPath} pays, and on a tie a
     * handful of whole files read to be hashed - never call this from the
     * UI thread. See {@code EmulatorActivity.resolveLibraryPath} for the one
     * place that asks off it, {@code displayName} included: that is itself
     * a provider query when the caller does not already have the name some
     * other way. {@link #resolveCache} is what keeps a repeat of the same
     * question - {@code Open recent…} on the same game, most often - from
     * paying for the hashing twice.
     */
    public static synchronized String resolve(Context context, Uri document, String displayName) {
        String exact = relativePath(context, document);
        if (exact != null) return exact;
        if (displayName == null || displayName.isEmpty()) return null;

        String cacheKey = document.toString();
        if (resolveCache.containsKey(cacheKey)) return resolveCache.get(cacheKey);

        List<Meta> ties = new ArrayList<>();
        for (Meta meta : store(context).games.values()) {
            if (displayName.equals(filename(meta.path))) ties.add(meta);
        }

        String result = ties.size() == 1 ? ties.get(0).path
                       : ties.size() > 1 ? resolveByHash(context, document, ties)
                       : null;

        resolveCache.put(cacheKey, result);
        return result;
    }

    /**
     * Which of {@code ties} - every game the store holds under {@code
     * document}'s own display name - is actually the file that was opened,
     * settled by reading the bytes rather than by guessing between them.
     * {@code document} may be unreadable outright - a lapsed ES-DE grant,
     * most likely - which is read the same as no candidate matching: a miss,
     * quietly, not a failure.
     */
    private static String resolveByHash(Context context, Uri document, List<Meta> ties) {
        byte[] opened = md5(context, document);
        if (opened == null) return null;

        for (Meta candidate : ties) {
            Uri candidateDocument = documentFor(context, candidate.path);
            if (candidateDocument == null) continue;

            byte[] hash = md5(context, candidateDocument);
            if (hash != null && Arrays.equals(opened, hash)) return candidate.path;
        }

        return null;
    }

    /**
     * The reverse of {@link #relativePath}: the document {@code metaPath}
     * names, under the content tree - only {@link #resolveByHash} needs
     * this, since every other use of the store looks a game up by its path
     * rather than needing to read the file it names.
     */
    private static Uri documentFor(Context context, String metaPath) {
        Uri root = Storage.contentFolder(context);
        if (root == null || metaPath == null || !metaPath.startsWith("./")) return null;

        try {
            String rootId = DocumentsContract.getDocumentId(root);
            String docId = rootId + "/" + metaPath.substring(2);
            return DocumentsContract.buildDocumentUriUsingTree(root, docId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code document}'s own md5, read straight through rather than staged
     * anywhere - {@code Media}'s own hash, which the poke database matches
     * on, is taken on the way to writing the file it hashes into the
     * emulator's own cache, a side effect nothing here should have just to
     * compare a handful of same-named candidates. The same algorithm all
     * the same, read the same way, so the two cannot disagree about a file
     * they both simply read whole. Null when the document cannot be read at
     * all, which {@link #resolveByHash} reads as no match rather than a
     * failure.
     */
    private static byte[] md5(Context context, Uri document) {
        try (InputStream in = context.getContentResolver().openInputStream(document)) {
            if (in == null) return null;

            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (Exception e) {
            return null;
        }
    }

    private static String filename(String path) {
        return dev.ldlab.zedex.storage.Storage.filename(path);
    }
    private static File file(Context context) {
        // Storage.libraryDirectory, not a path built here from a FOLDER of our
        // own: this folder has to be in the list Storage moves when the data
        // folder changes, and while it was not, changing that folder left the
        // store behind - silently, because a missing store reads as an empty
        // one and the app then says it has never been linked.
        return new File(Storage.libraryDirectory(context), FILE);
    }

    /**
     * The whole store, streamed out.
     *
     * Keyed by path rather than a list of objects each carrying a path: that
     * is what the in-memory map is, so reading rebuilds it without a pass to
     * re-key, and it makes a duplicated path impossible to write rather than
     * something to check for.
     *
     * <b>This format can hold characters the old one could not.</b> The store
     * was XML, and one scraped description ended in U+0001 - a stray control
     * byte from whatever filled ES-DE's gamelist. XML 1.0 cannot carry that at
     * all: it was written out as a numeric reference no parser will read back,
     * so the next load threw, the catch turned it into an empty store, and 803
     * games' worth of metadata vanished behind an app saying - in the same
     * tone it uses when it is true - that the library had never been linked.
     * One byte in 762 kilobytes, and artwork went on working, so the link
     * looked like it had succeeded. JSON escapes that character and reads it
     * back, so the sanitising that used to guard this is gone rather than
     * ported.
     */
    private static void write(OutputStream out, long linkedAt, List<Meta> games)
            throws IOException {
        Writer text = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        JsonWriter writer = new JsonWriter(text);

        // Indented because somebody will open this by hand, and a store is
        // exactly the file people open by hand when something looks wrong.
        writer.setIndent("  ");

        writer.beginObject();
        writer.name(VERSION_KEY).value(VERSION);
        writer.name(LINKED).value(linkedAt);
        writer.name(GAMES).beginObject();

        for (Meta game : games) {
            if (game.path == null || game.path.isEmpty()) continue;

            writer.name(game.path).beginObject();
            field(writer, "name", game.name);
            field(writer, "desc", game.desc);
            field(writer, "developer", game.developer);
            field(writer, "publisher", game.publisher);
            field(writer, "genre", game.genre);
            field(writer, "subgenre", game.subgenre);
            field(writer, "released", game.released);
            field(writer, "players", game.players);
            field(writer, "rating", game.rating);
            field(writer, "source", game.source);
            field(writer, "keymap", game.keymap);
            field(writer, "machine", game.machine);
            field(writer, "price", game.price);
            field(writer, "series", game.series);

            // Written like everything else here, as strings, although one is a
            // count and the other a flag - see Meta.playCount. A store that
            // held three shapes would be three shapes to read back.
            field(writer, "playCount", game.playCount);
            field(writer, "videoLink", game.videoLink);
            field(writer, "completed", game.completed);
            list(writer, "inputs", game.inputs);
            list(writer, "authors", game.authors);
            links(writer, "seriesGames", game.seriesGames);
            links(writer, "compilations", game.compilations);
            links(writer, "contents", game.contents);
            writer.endObject();
        }

        writer.endObject();
        writer.endObject();
        writer.flush();
    }

    /** The first field here that is a list rather than a string, and the
     *  reason a format of our own was worth the change: in the gamelist this
     *  would have needed a separator convention and a rule about what to do
     *  when a value contained it. */
    private static void list(JsonWriter writer, String name, List<String> values)
            throws IOException {
        if (values == null || values.isEmpty()) return;

        writer.name(name).beginArray();
        for (String value : values) writer.value(value);
        writer.endArray();
    }

    /**
     * A list of other entries, each an object rather than a string.
     *
     * The id is written beside the title although nothing reads it yet - see
     * {@code Meta.Link}, which argues why. An object also leaves room for
     * whatever a link turns out to need without a second convention: this is
     * the shape a gamelist could not have held at all.
     */
    private static void links(JsonWriter writer, String name, List<Meta.Link> values)
            throws IOException {
        if (values == null || values.isEmpty()) return;

        writer.name(name).beginArray();

        for (Meta.Link link : values) {
            if (link == null || link.id == null || link.title == null) continue;

            writer.beginObject();
            writer.name("id").value(link.id);
            writer.name("title").value(link.title);
            writer.endObject();
        }

        writer.endArray();
    }

    /** Only when there is something to say: an absent field is left out
     *  rather than written null, so the file stays readable and a row with
     *  nothing known is two braces. */
    private static void field(JsonWriter writer, String name, String value)
            throws IOException {
        if (value == null || value.isEmpty()) return;
        writer.name(name).value(value);
    }

    /**
     * The store, from memory, and never anything else.
     *
     * This asks no question of the filesystem and takes no lock. It used to
     * stat the file on every call and every caller synchronized around that,
     * which is asked once per game per filter pass and once per row as it
     * binds - measured at 53 microseconds for what is a HashMap read.
     *
     * It also never parses. A caller on the UI thread that arrives before the
     * store has been read gets an empty answer rather than 776 KB of XML on
     * the thread drawing the list, and the screen rebinds when {@link
     * #preload} finishes. Whoever needs the real thing and can afford to wait
     * says so with {@link #ensureLoaded}.
     */
    private static Store store(Context context) {
        Store current = cache;
        return current != null ? current : EMPTY;
    }

    /**
     * Reads the store if it has not been read, blocking until it has.
     *
     * For a caller already on a background thread that cannot do its job
     * without the facts - the filter sheet's own facets, the details screen.
     * Never call this from the main thread.
     */
    public static void ensureLoaded(Context context) {
        if (cache != null) return;
        loadAndPublish(context);
    }

    /**
     * Reads the store now, off whatever thread calls this, so the first
     * screen that wants it does not wait for the parse.
     *
     * Called at app start. Cheap once it has run.
     */
    public static void preload(Context context) {
        ensureLoaded(context);
    }

    /**
     * Re-reads the file if it has moved on since it was last read.
     *
     * This is where the stat went. The moments the store can have changed
     * under a running app are few and known - the library coming back to the
     * front, a link or an unlink finishing - so the question is asked there
     * rather than before every lookup. Not on the main thread: it may parse.
     */
    public static void refresh(Context context) {
        File file = file(context);
        long mtime = file.isFile() ? file.lastModified() : 0;

        Store current = cache;
        if (current != null && current.mtime == mtime) return;

        loadAndPublish(context);
    }

    /**
     * Parses, then publishes.
     *
     * The parse is deliberately outside the lock. Holding the monitor across
     * it is what defeated the preload: refresh held it for the whole 776 KB
     * while the UI thread, arriving a moment later, either did the parse
     * itself or blocked on the monitor for the rest of it - and either way
     * the main thread paid for it at every cold start. Two threads arriving
     * together now parse twice, which costs one wasted read and is the
     * cheaper mistake by a wide margin.
     */
    private static void loadAndPublish(Context context) {
        File file = file(context);
        long mtime = file.isFile() ? file.lastModified() : 0;

        Store loaded = load(file, mtime);

        synchronized (Metadata.class) {
            cache = loaded;
            resolveCache.clear();
        }
    }

    /**
     * Reads the store, streaming.
     *
     * A {@link JsonReader} rather than a tree, for the same reason the XML
     * this replaced used a pull parser rather than a DOM: a tree of every
     * value in the file is built and then thrown away to keep eleven short
     * strings per game, which for a 776 KB store is on the order of ten
     * megabytes of transient objects and scales with the file. This allocates
     * the strings it keeps and little else.
     *
     * Unknown keys are skipped rather than refused, at both levels. A store
     * written by a newer build - or by a provider that learned a field this
     * one has never heard of - still loads everything this build understands.
     */
    private static Store load(File file, long mtime) {
        if (mtime == 0) return EMPTY;

        try (Reader text = new java.io.BufferedReader(
                     new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
             JsonReader reader = new JsonReader(text)) {

            Map<String, Meta> games = new HashMap<>();
            long linkedAt = 0;

            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();

                if (LINKED.equals(key)) {
                    linkedAt = reader.nextLong();
                } else if (GAMES.equals(key)) {
                    readGames(reader, games);
                } else if (VERSION_KEY.equals(key)) {
                    int version = reader.nextInt();
                    if (version > VERSION) {
                        Log.w(TAG, file + " is version " + version + " and this build"
                                   + " understands " + VERSION + "; reading what it can");
                    }
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();

            return new Store(mtime, linkedAt, games);
        } catch (Exception e) {
            // Loud, and specific about the consequence: what is returned below
            // is indistinguishable from an empty store, so every screen goes
            // on to say the library has never been linked. A file that exists
            // and will not parse is a different thing from no file at all, and
            // here is the only place that difference is visible.
            Log.e(TAG, "cannot read " + file + " - it exists but will not parse,"
                       + " so the library will report itself as never linked."
                       + " Linking again rewrites it.", e);
            return EMPTY;
        }
    }

    /** The games object: one member per game, named by its path. */
    private static void readGames(JsonReader reader, Map<String, Meta> into)
            throws IOException {
        reader.beginObject();

        while (reader.hasNext()) {
            String path = reader.nextName();

            // The key is the identity, so there is no such thing here as a
            // game without a path - the shape that the old format needed a
            // guard for, and this one cannot express.
            Meta game = readGame(reader, path);
            if (game != null) into.put(path, game);
        }

        reader.endObject();
    }

    /** One game's object. Null only when it is not an object at all, which
     *  means a hand edit went wrong rather than anything this app wrote. */
    private static Meta readGame(JsonReader reader, String path) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue();
            return null;
        }

        Meta.Builder building = Meta.at(path);
        reader.beginObject();

        while (reader.hasNext()) {
            String key = reader.nextName();

            // A null written by hand is the same as the field being absent,
            // and nextString would throw on it.
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                continue;
            }

            switch (key) {
                case "name":      building.name(reader.nextString());      break;
                case "desc":      building.desc(reader.nextString());      break;
                case "developer": building.developer(reader.nextString()); break;
                case "publisher": building.publisher(reader.nextString()); break;
                case "genre":     building.genre(reader.nextString());     break;
                case "subgenre":  building.subgenre(reader.nextString());  break;
                case "released":  building.released(reader.nextString());  break;
                case "players":   building.players(reader.nextString());   break;
                case "rating":    building.rating(reader.nextString());    break;
                case "source":    building.source(reader.nextString());    break;
                case "keymap":    building.keymap(reader.nextString());    break;
                case "machine":   building.machine(reader.nextString());   break;
                case "price":     building.price(reader.nextString());     break;
                case "series":    building.series(reader.nextString());    break;
                case "playCount": building.playCount(reader.nextString());  break;
                case "videoLink": building.videoLink(reader.nextString());  break;
                case "completed": building.completed(reader.nextString());  break;
                case "inputs":    building.inputs(strings(reader));        break;
                case "authors":   building.authors(strings(reader));       break;
                case "seriesGames":  building.seriesGames(links(reader));  break;
                case "compilations": building.compilations(links(reader)); break;
                case "contents":     building.contents(links(reader));     break;
                default:          reader.skipValue();                      break;
            }
        }

        reader.endObject();
        return building.build();
    }

    /**
     * An array of strings, skipping anything in it that is not one.
     *
     * A store is a file people open and edit, and one bad element should cost
     * that element rather than the game it belongs to - the same reason an
     * unknown key is skipped rather than refused.
     */
    /**
     * An array of {@code {id, title}}, skipping anything that is not one.
     *
     * <b>Half a link is dropped.</b> A row with only a title cannot be opened
     * and a row with only an id cannot be shown, so neither is a link - and
     * the store is a file people edit, so both are things that will happen.
     * The same reasoning as {@link #strings}: one bad element costs that
     * element rather than the game it belongs to.
     */
    private static List<Meta.Link> links(JsonReader reader) throws IOException {
        List<Meta.Link> values = new ArrayList<>();

        reader.beginArray();

        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue();
                continue;
            }

            String id = null, title = null;
            reader.beginObject();

            while (reader.hasNext()) {
                String key = reader.nextName();

                if (reader.peek() == JsonToken.NULL) { reader.nextNull(); continue; }
                if ("id".equals(key))         id = reader.nextString();
                else if ("title".equals(key)) title = reader.nextString();
                else                          reader.skipValue();
            }

            reader.endObject();

            if (id != null && !id.isEmpty() && title != null && !title.isEmpty()) {
                values.add(new Meta.Link(id, title));
            }
        }

        reader.endArray();
        return values;
    }

    private static List<String> strings(JsonReader reader) throws IOException {
        List<String> values = new ArrayList<>();

        reader.beginArray();
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.STRING) values.add(reader.nextString());
            else reader.skipValue();
        }
        reader.endArray();

        return values;
    }
}
