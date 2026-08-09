package dev.ldlab.zedex.library.meta;

import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.storage.Xml;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Our own gamelist-shaped store of what a link to ES-DE found, in the app's
 * data folder rather than beside the games - the content folder can be
 * read-only, and a library that cannot record what it learned is worse than
 * one that does not travel. See "Metadata lives in the app's data folder" in
 * docs/LIBRARY.md.
 *
 * One file, {@code library/gamelist.xml}, in ES-DE's own element names -
 * {@code path}, {@code name}, {@code desc} and so on - so a file the user
 * opens by hand still reads as a gamelist, plus {@code zedexSource} under a
 * name of its own so a later hand-edited field can never be confused with a
 * scraped one. The root element carries {@code zedexLinked}, the epoch millis
 * of the last write - the one thing {@link #lastLinked} needs, and parsing
 * every game just to read one attribute would be wasteful.
 *
 * Blocking, like {@code library.Favorites}: read and written whole, because a
 * few hundred games is still a small file and nothing here does a query a
 * database would earn its keep on. A missing, unreadable or malformed file
 * reads as no metadata at all - logged once, never thrown - and {@link
 * #replaceAll} writes to a temporary file first so a crash mid-write never
 * leaves gamelist.xml itself half written.
 *
 * The parsed file is cached in memory and only re-read when its own
 * modification time has moved on - which happens only when this class writes
 * it - so drawing a list of a few hundred rows costs one parse, not one per
 * row.
 */
public final class Metadata {

    private static final String TAG = "Zedex";
    private static final String FILE = "gamelist.xml";
    private static final String ROOT = "gameList";
    private static final String GAME = "game";
    private static final String LINKED = "zedexLinked";
    private static final String SOURCE = "zedexSource";

    /**
     * The provider's own control layout for a game - see {@link Meta#controls}.
     *
     * Ours, like {@link #SOURCE}, and not one of ES-DE's elements: they have
     * nothing that means this. Harmless in a file ES-DE never reads - this
     * store is the app's own - and it keeps the file a gamelist that opens in
     * an editor and still makes sense.
     */
    private static final String CONTROLS = "zedexControls";

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

        // A temporary file first: a transformer that fails partway through
        // must not leave gamelist.xml itself half written, which would then
        // read back as "no metadata at all" the moment it lost the tag that
        // made it well-formed.
        File temp = new File(directory, FILE + ".tmp");

        try (OutputStream out = new FileOutputStream(temp)) {
            write(build(linkedAt, games), out);
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

    private static Document build(long linkedAt, List<Meta> games) throws Exception {
        DocumentBuilder builder = Xml.builder();
        Document document = builder.newDocument();

        Element root = document.createElement(ROOT);
        root.setAttribute(LINKED, Long.toString(linkedAt));
        document.appendChild(root);

        for (Meta game : games) {
            if (game.path == null || game.path.isEmpty()) continue;

            Element element = document.createElement(GAME);
            append(document, element, "path", game.path);
            append(document, element, "name", game.name);
            append(document, element, "desc", game.desc);
            append(document, element, "developer", game.developer);
            append(document, element, "publisher", game.publisher);
            append(document, element, "genre", game.genre);
            append(document, element, "releasedate", game.released);
            append(document, element, "players", game.players);
            append(document, element, "rating", game.rating);
            append(document, element, SOURCE, game.source);
            append(document, element, CONTROLS, game.controls);
            root.appendChild(element);
        }

        return document;
    }

    /** Only when there is something to say - an empty file is omitted, ES-DE's own way. */
    private static void append(Document document, Element parent, String name, String text) {
        if (text == null || text.isEmpty()) return;

        String usable = xmlSafe(text);
        if (usable.isEmpty()) return;

        Element element = document.createElement(name);
        element.setTextContent(usable);
        parent.appendChild(element);
    }

    /**
     * Text with the characters XML cannot carry taken out of it.
     *
     * This is not defensive tidying; it is a file this app wrote and then could
     * not read. One scraped description ended in U+0001 - "Selection between
     * right-hand and left-hand drive." and then a stray control byte, from
     * whatever scraper filled ES-DE's own gamelist. The DOM took it without
     * complaint and the Transformer wrote it out as {@code &#1;}, which is not
     * well-formed XML 1.0 at all: no parser will read that reference back.
     *
     * So the next {@link #load} threw, the catch turned it into {@link #EMPTY},
     * and 803 games' worth of metadata vanished behind an app that said, in the
     * same tone it uses when it is true, that the library had never been
     * linked. Artwork went on working - it comes from ES-DE's media folder and
     * never touches this file - so the link looked like it had worked. One byte
     * out of 762 kilobytes.
     *
     * The permitted set is the one from the XML 1.0 specification: tab,
     * newline, carriage return, and everything from {@code #x20} up, less the
     * two non-characters at the end of the BMP. Surrogate pairs are left alone
     * - a Java char in the surrogate range is half of an astral character, and
     * dropping one of the pair would corrupt what it is trying to protect.
     */
    static String xmlSafe(String text) {
        StringBuilder kept = null;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            boolean allowed = c == '\t' || c == '\n' || c == '\r'
                    || (c >= 0x20 && c <= 0xD7FF)
                    || (c >= 0xE000 && c <= 0xFFFD)
                    || Character.isSurrogate(c);

            if (allowed) {
                if (kept != null) kept.append(c);
                continue;
            }

            // The first one that has to go: copy what came before it.
            if (kept == null) kept = new StringBuilder(text.length()).append(text, 0, i);
        }

        return kept == null ? text : kept.toString();
    }

    private static void write(Document document, OutputStream out) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(
                "{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.transform(new DOMSource(document), new StreamResult(out));
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
     * A pull parser rather than a DOM. The DOM built a tree of every element
     * and attribute in the file and then threw all of it away to keep ten
     * short strings per game: 776 KB of XML is on the order of ten megabytes
     * of transient objects, and it scales with the file - a five megabyte
     * gamelist would be sixty to ninety, which is an OutOfMemoryError while
     * linking rather than a slow read. This allocates the strings it keeps
     * and little else.
     *
     * The hardening {@code Xml.builder} exists for is not lost with it. That
     * class refuses external entities because a DocumentBuilder resolves them
     * by default; a pull parser does not process a doctype at all unless
     * FEATURE_PROCESS_DOCDECL is turned on, and it is off by default and not
     * turned on here. Same guarantee, from the other direction.
     */
    private static Store load(File file, long mtime) {
        if (mtime == 0) return EMPTY;

        try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(file))) {
            XmlPullParser parser = android.util.Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, null);

            Map<String, Meta> games = new HashMap<>();
            long linkedAt = 0;
            boolean sawRoot = false;

            for (int event = parser.getEventType();
                 event != XmlPullParser.END_DOCUMENT;
                 event = parser.next()) {

                if (event != XmlPullParser.START_TAG) continue;

                if (!sawRoot) {
                    if (!ROOT.equals(parser.getName())) {
                        Log.w(TAG, file + " is not a " + ROOT + " file");
                        return EMPTY;
                    }

                    sawRoot = true;
                    linkedAt = number(parser.getAttributeValue(null, LINKED));
                    continue;
                }

                if (!GAME.equals(parser.getName())) continue;

                Meta meta = readGame(parser);
                if (meta != null) games.put(meta.path, meta);
            }

            return new Store(mtime, linkedAt, games);
        } catch (Exception e) {
            // Loud, and specific about the consequence: what is returned
            // below is indistinguishable from an empty store, so every screen
            // goes on to say the library has never been linked. A file that
            // exists and will not parse is a different thing from no file at
            // all, and here is the only place that difference is visible.
            Log.e(TAG, "cannot read " + file + " - it exists but will not parse,"
                       + " so the library will report itself as never linked."
                       + " Linking again rewrites it; see Metadata.xmlSafe for"
                       + " the character that used to cause this.", e);
            return EMPTY;
        }
    }

    /**
     * One {@code <game>}, from its start tag to its end tag.
     *
     * Null when it has no path, which is what a game is identified by - the
     * same entry the DOM version skipped. Unknown children are read and
     * dropped rather than refused, so a gamelist carrying fields this app
     * does not know about still loads.
     */
    private static Meta readGame(XmlPullParser parser) throws Exception {
        int depth = parser.getDepth();

        String path = null, name = null, desc = null, developer = null;
        String publisher = null, genre = null, released = null;
        String players = null, rating = null, source = null, controls = null;

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            int event = parser.getEventType();

            if (event == XmlPullParser.END_TAG && parser.getDepth() == depth) break;
            if (event != XmlPullParser.START_TAG) continue;

            String tag = parser.getName();
            String value = parser.nextText().trim();

            switch (tag) {
                case "path":        path = value;      break;
                case "name":        name = value;      break;
                case "desc":        desc = value;      break;
                case "developer":   developer = value; break;
                case "publisher":   publisher = value; break;
                case "genre":       genre = value;     break;
                case "releasedate": released = value;  break;
                case "players":     players = value;   break;
                case "rating":      rating = value;    break;
                default:
                    if (SOURCE.equals(tag)) source = value;
                    else if (CONTROLS.equals(tag)) controls = value;
                    break;
            }
        }

        if (path == null || path.isEmpty()) return null;

        return new Meta(path, name, desc, developer, publisher,
                        genre, released, players, rating, source)
                .withControls(controls);
    }

    /** The {@code linked} attribute, or 0 for anything unreadable. */
    private static long number(String value) {
        if (value == null || value.isEmpty()) return 0;

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long attribute(Element element, String name) {
        String value = element.getAttribute(name);
        try {
            return value.isEmpty() ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element && name.equals(node.getNodeName())) {
                String text = node.getTextContent();
                return text == null ? null : text.trim();
            }
        }
        return null;
    }
}
