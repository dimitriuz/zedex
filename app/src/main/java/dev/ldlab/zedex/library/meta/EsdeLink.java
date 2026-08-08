package dev.ldlab.zedex.library.meta;

import dev.ldlab.zedex.frontend.EsDe;
import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.storage.Xml;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilder;

/**
 * Reading ES-DE: its gamelist, and where it keeps the media for one system.
 *
 * Everything here is blocking, and every method fails soft - null or empty -
 * when ES-DE is not installed, not reachable through {@link EsDe#reach}, or
 * its files cannot be parsed. See "Linking to ES-DE" in docs/LIBRARY.md for
 * why matching is by path and assumes one folder.
 *
 * ES-DE's own files are only loosely XML, and the two are not equally loose:
 * {@code gamelist.xml} is a real document with one root and parses as any
 * reader would expect, but {@code es_settings.xml} is a declaration followed
 * by a flat run of sibling elements with no wrapping root at all, which a DOM
 * parser refuses outright. Reading either has to expect that kind of rough
 * edge rather than take a parse failure as proof there is nothing there; see
 * {@link #mediaDirectory} for where that actually bit.
 */
public final class EsdeLink {

    private static final String TAG = "Zedex";
    private static final String SYSTEM = "zxspectrum";
    private static final String GAMELIST = "gamelists/" + SYSTEM + "/gamelist.xml";
    private static final String SETTINGS = "settings/es_settings.xml";
    private static final String MEDIA_KEY = "MediaDirectory";
    private static final String DEFAULT_MEDIA = "downloaded_media";

    /**
     * The folder the user grants for ES-DE's media when {@link #mediaRoot}
     * cannot reach it through {@link EsDe#KEY_ESDE_TREE} - the rare case.
     * ES-DE's own default for {@code MediaDirectory} is a <em>child</em> of
     * its own folder, {@code downloaded_media}, which the tree grant we
     * already hold reaches perfectly well, and a configured value that still
     * lies under ES-DE's folder is reached the same way; only a value that
     * names somewhere else entirely needs a grant of its own. Written and
     * read as a String, the tree Uri, exactly like {@link EsDe#KEY_ESDE_TREE}
     * - the settings tab that offers Link and Unlink is where this gets
     * written, once {@link #needsMediaFolder} says to ask.
     */
    public static final String KEY_MEDIA_TREE = "esdeMediaTree";

    // What mediaRoot resolved to last time, and the two preferences it was
    // resolved from - re-deriving it costs a settings-file parse and is
    // called once per row a list draws, so it is only redone when a grant
    // could plausibly have changed.
    private static boolean haveMediaCache;
    private static String cachedTree;
    private static String cachedMediaTree;
    private static Uri cachedMediaRoot;
    private static boolean cachedNeedsMediaFolder;

    private EsdeLink() {
    }

    /**
     * Every zxspectrum game ES-DE knows about.
     *
     * The empty list and an exception mean different facts, and the caller's
     * safety turns on never letting them collapse into one: {@link
     * Metadata#replaceAll} throws away whatever this app already recorded and
     * writes exactly what this method hands back, so a lapsed grant or an
     * uninstalled ES-DE answering "no games" instead of "cannot tell" would
     * silently erase a real scrape the moment it happened - which is exactly
     * what returning an empty list here used to do.
     *
     * @throws IOException if ES-DE cannot be reached at all - no All files
     *                      access and no granted folder - or its gamelist
     *                      could not be opened or parsed once reached. None
     *                      of that is "nothing to link"; it is "do not touch
     *                      what is already stored". An empty list means the
     *                      gamelist was genuinely read and holds no
     *                      {@code zxspectrum} game - a real answer, and a
     *                      rare one.
     */
    public static List<Meta> read(Context context) throws IOException {
        List<Meta> games = new ArrayList<>();

        EsDe.Reach reach = EsDe.reach(context);
        if (reach == null) {
            throw new IOException(
                    "ES-DE cannot be reached: no All files access and no granted folder");
        }

        InputStream in;
        try {
            in = reach.open(GAMELIST);
        } catch (Exception e) {
            throw new IOException("cannot open " + GAMELIST, e);
        }
        if (in == null) return games; // ES-DE is reachable but has scraped nothing for this system yet

        try (InputStream stream = in) {
            Document document = builder().parse(stream);
            if (!"gameList".equals(document.getDocumentElement().getNodeName())) {
                throw new IOException(GAMELIST + " is not a gameList file");
            }

            NodeList nodes = document.getDocumentElement().getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (!(node instanceof Element) || !"game".equals(node.getNodeName())) continue;

                Element element = (Element) node;
                String path = text(element, "path");
                if (path == null || path.isEmpty()) continue; // unmatchable without a key

                games.add(new Meta(path, text(element, "name"), text(element, "desc"),
                        text(element, "developer"), text(element, "publisher"),
                        text(element, "genre"), text(element, "releasedate"),
                        text(element, "players"), "esde"));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot parse " + GAMELIST, e);
        }

        return games;
    }

    /** Where ES-DE keeps its media for {@code zxspectrum}, or null if it cannot be reached. */
    public static synchronized Uri mediaRoot(Context context) {
        resolve(context);
        return cachedMediaRoot;
    }

    /** Whether {@link #mediaRoot} had to fall outside our grant - ask the user for it. */
    public static synchronized boolean needsMediaFolder(Context context) {
        resolve(context);
        return cachedNeedsMediaFolder;
    }

    /**
     * Re-derives the cached answer only when the two preferences it depends
     * on have moved on since last time: comparing two strings is far cheaper
     * than re-reading ES-DE's own settings file for every row a list draws,
     * and nothing but a settings screen writing a new grant can change the
     * answer.
     */
    private static void resolve(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS, Context.MODE_PRIVATE);
        String tree = preferences.getString(EsDe.KEY_ESDE_TREE, null);
        String mediaTree = preferences.getString(KEY_MEDIA_TREE, null);

        if (haveMediaCache && Objects.equals(tree, cachedTree)
                && Objects.equals(mediaTree, cachedMediaTree)) {
            return;
        }

        haveMediaCache = true;
        cachedTree = tree;
        cachedMediaTree = mediaTree;
        cachedMediaRoot = null;
        cachedNeedsMediaFolder = false;

        EsDe.Reach reach = EsDe.reach(context);
        if (reach == null) return; // nothing to link to, so nothing to ask for either

        // Never null: mediaDirectory() itself falls back to "" - ES-DE's own
        // default - on anything it cannot read or parse, logging why.
        String configured = mediaDirectory(reach);

        // ES-DE's own folder, by whichever means reached it - used only to
        // tell whether a configured path lies inside it. The actual read
        // always goes through reach.locate, i.e. the same grant or path that
        // got this far, never a second lookup of its own.
        File esdeFolder = EsDe.folder();
        if (esdeFolder == null && tree != null) {
            esdeFolder = Storage.pathFor(Uri.parse(tree));
        }

        // Empty means ES-DE's own default, and that default is a *child* of
        // its folder - "downloaded_media" inside ES-DE, not beside it - so it
        // is reached exactly the way ES-DE's own gamelist and settings are:
        // through whatever already got this far. A configured value may
        // still name somewhere under that same folder, which is reached the
        // same way; only a value naming somewhere else needs asking. See
        // "ES-DE's media folder is not always beside ES-DE" in
        // docs/LIBRARY.md.
        String relative = configured.isEmpty()
                ? DEFAULT_MEDIA : relativeToEsde(esdeFolder, configured);

        if (relative != null) {
            // Whether or not it is there yet is a question of whether ES-DE
            // has scraped anything, not of permission - the folder is inside
            // what this device already reaches either way, so a miss here is
            // never a reason to ask for one.
            cachedMediaRoot = reach.locate(relative);
            return;
        }

        // Genuinely somewhere else. A plain path needs All files access;
        // failing that, only a grant of our own for this exact folder will
        // do. See "What scoped storage allows" in CLAUDE.md.
        if (Storage.canUseAnyFolder()) {
            File custom = new File(configured);
            if (custom.isDirectory() && custom.canRead()) {
                cachedMediaRoot = Uri.fromFile(custom);
                return;
            }
        }

        if (mediaTree != null) {
            Uri granted = grantedRoot(context, mediaTree);
            if (granted != null) {
                cachedMediaRoot = granted;
                return;
            }
        }

        cachedNeedsMediaFolder = true;
    }

    /**
     * The part of {@code configured} beneath {@code esdeFolder}, or null when
     * {@code esdeFolder} is not known or {@code configured} does not lie
     * under it at all. A plain string comparison rather than
     * {@code getCanonicalPath()}: both sides are already absolute, and
     * canonicalising would touch the filesystem for a path this app may hold
     * no permission to stat.
     */
    private static String relativeToEsde(File esdeFolder, String configured) {
        if (esdeFolder == null) return null;

        String base = esdeFolder.getAbsolutePath();
        String target = new File(configured).getAbsolutePath();

        if (target.equals(base)) return "";
        return target.startsWith(base + "/") ? target.substring(base.length() + 1) : null;
    }

    /** The tree's own root document, if the grant is still actually held. */
    private static Uri grantedRoot(Context context, String stored) {
        try {
            Uri tree = Uri.parse(stored);

            for (UriPermission permission :
                    context.getContentResolver().getPersistedUriPermissions()) {
                if (permission.getUri().equals(tree) && permission.isReadPermission()) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                            tree, DocumentsContract.getTreeDocumentId(tree));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot use the granted media folder " + stored, e);
        }

        return null; // the grant was lost; ask for it again
    }

    /**
     * ES-DE's own {@code MediaDirectory} setting: empty for its default, a
     * path otherwise - and empty as well whenever the file could not be read
     * or parsed at all, logged loudly rather than left to look like a folder
     * with nothing scraped. This never answers "unknown": guessing the
     * default when it is wrong only means trying one wrong folder, which
     * {@link #resolve} already treats as "not there yet" rather than
     * anything destructive - a game's own path is what tells one game's
     * pictures from another's, so a wrong media root produces misses, never
     * somebody else's cover. Giving up outright would have turned a
     * malformed settings file - which is exactly what this one turned out to
     * be - into every picture disappearing instead of the default, which
     * happened to be right, ever being tried.
     */
    private static String mediaDirectory(EsDe.Reach reach) {
        InputStream in;
        try {
            in = reach.open(SETTINGS);
        } catch (Exception e) {
            Log.w(TAG, "cannot open " + SETTINGS + "; assuming ES-DE's default media folder", e);
            return "";
        }
        if (in == null) return ""; // no settings file yet is ES-DE's own default too

        try (InputStream stream = in) {
            Document document = builder().parse(wrapped(stream));
            NodeList strings = document.getElementsByTagName("string");

            for (int i = 0; i < strings.getLength(); i++) {
                Element element = (Element) strings.item(i);
                if (MEDIA_KEY.equals(element.getAttribute("name"))) {
                    return element.getAttribute("value");
                }
            }

            return ""; // the key is absent, which is the default as well
        } catch (Exception e) {
            Log.w(TAG, "cannot parse " + SETTINGS + "; assuming ES-DE's default media folder", e);
            return "";
        }
    }

    /**
     * {@code es_settings.xml} as ES-DE actually writes it - a declaration
     * followed by sibling elements with no root - wrapped in one of our own
     * so a normal parser accepts it. Still a real parse rather than a
     * hand-rolled scan for one attribute: entities and quoting are XML's
     * rules to get right, not this class's to reimplement.
     */
    private static InputStream wrapped(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);

        String text = buffer.toString(StandardCharsets.UTF_8.name());

        int declarationEnd = text.indexOf("?>");
        String body = text.startsWith("<?xml") && declarationEnd >= 0
                ? text.substring(declarationEnd + 2) : text;

        String wrapped = "<zedexSettings>" + body + "</zedexSettings>";
        return new ByteArrayInputStream(wrapped.getBytes(StandardCharsets.UTF_8));
    }

    private static DocumentBuilder builder() throws Exception {
        return Xml.builder();
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
