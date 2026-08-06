package dev.ldlab.zedex.library.meta;

import dev.ldlab.zedex.storage.Storage;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
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
    private static final String FOLDER = "library";
    private static final String FILE = "gamelist.xml";
    private static final String ROOT = "gameList";
    private static final String GAME = "game";
    private static final String LINKED = "zedexLinked";
    private static final String SOURCE = "zedexSource";

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

    private Metadata() {
    }

    /** The stored facts for one game, or null when nothing is known about it. */
    public static synchronized Meta forPath(Context context, String relativePath) {
        return store(context).games.get(relativePath);
    }

    /** How many games the store holds. */
    public static synchronized int count(Context context) {
        return store(context).games.size();
    }

    /** When {@link #replaceAll} last ran, epoch millis, or 0 if it never has. */
    public static synchronized long lastLinked(Context context) {
        return store(context).linkedAt;
    }

    /**
     * Writes the whole store, stamping the time. Pressing Link again is meant
     * to replace: whatever ES-DE still has is written again, a game that
     * appeared is added by being in {@code games} at all, and a game that is
     * gone is dropped by not being in it - the caller decides what {@code
     * games} holds, this only ever writes exactly that.
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
    public static synchronized void replaceAll(Context context, List<Meta> games) {
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
    }

    /** Forgets everything - Unlink in Settings. */
    public static synchronized void clear(Context context) {
        File file = file(context);
        if (file.isFile() && !file.delete()) {
            Log.w(TAG, "cannot delete " + file);
        }
        cache = EMPTY;
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

    private static File file(Context context) {
        return new File(new File(Storage.root(context), FOLDER), FILE);
    }

    private static Document build(long linkedAt, List<Meta> games) throws Exception {
        DocumentBuilder builder =
                DocumentBuilderFactory.newInstance().newDocumentBuilder();
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
            append(document, element, SOURCE, game.source);
            root.appendChild(element);
        }

        return document;
    }

    /** Only when there is something to say - an empty file is omitted, ES-DE's own way. */
    private static void append(Document document, Element parent, String name, String text) {
        if (text == null || text.isEmpty()) return;
        Element element = document.createElement(name);
        element.setTextContent(text);
        parent.appendChild(element);
    }

    private static void write(Document document, OutputStream out) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(
                "{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.transform(new DOMSource(document), new StreamResult(out));
    }

    /** The current store, reloading only when the file's own mtime has moved on. */
    private static Store store(Context context) {
        File file = file(context);
        long mtime = file.isFile() ? file.lastModified() : 0;

        Store current = cache;
        if (current != null && current.mtime == mtime) return current;

        Store loaded = load(file, mtime);
        cache = loaded;
        return loaded;
    }

    private static Store load(File file, long mtime) {
        if (mtime == 0) return EMPTY;

        try (InputStream in = new FileInputStream(file)) {
            DocumentBuilder builder =
                    DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document document = builder.parse(in);

            if (!ROOT.equals(document.getDocumentElement().getNodeName())) {
                Log.w(TAG, file + " is not a " + ROOT + " file");
                return EMPTY;
            }

            long linkedAt = attribute(document.getDocumentElement(), LINKED);
            Map<String, Meta> games = new HashMap<>();

            NodeList nodes = document.getDocumentElement().getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (!(node instanceof Element) || !GAME.equals(node.getNodeName())) continue;

                Element element = (Element) node;
                String path = text(element, "path");
                if (path == null || path.isEmpty()) continue;

                games.put(path, new Meta(path, text(element, "name"), text(element, "desc"),
                        text(element, "developer"), text(element, "publisher"),
                        text(element, "genre"), text(element, "releasedate"),
                        text(element, "players"), text(element, SOURCE)));
            }

            return new Store(mtime, linkedAt, games);
        } catch (Exception e) {
            Log.w(TAG, "cannot read " + file, e);
            return EMPTY;
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
