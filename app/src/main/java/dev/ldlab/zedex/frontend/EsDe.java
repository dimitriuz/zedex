package dev.ldlab.zedex.frontend;

import dev.ldlab.zedex.library.Types;
import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.storage.Storage;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
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
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Putting Zedex in ES-DE's list of emulators for the Spectrum.
 *
 * ES-DE is a frontend: it holds the games and hands one to an emulator. It
 * knows how to launch RetroArch's Fuse core and Speccy for {@code zxspectrum}
 * and cannot be told about anything else without editing two XML files, which
 * is the whole of what this class does — {@code custom_systems/es_find_rules.xml},
 * where an emulator is a package and an activity, and
 * {@code custom_systems/es_systems.xml}, where a system is a list of launch
 * commands. Both are documented in ES-DE's own INSTALL.md; the syntax here is
 * theirs and not ours to invent.
 *
 * Two things about those files are worth knowing before touching this:
 *
 *   * a file in {@code custom_systems} <em>complements</em> the bundled
 *     configuration but a system in it <em>replaces</em> the bundled system of
 *     the same name, commands and all. So the entry written here carries ES-DE's
 *     own two commands for {@code zxspectrum} as well as ours, or choosing
 *     Zedex once would cost the user Fuse and Speccy.
 *   * the user may have written those files themselves. Nothing here overwrites
 *     a file: it is parsed, ours is added if it is missing, and it is written
 *     back. Running it twice changes nothing the second time.
 *
 * ES-DE launches by explicit component, so no intent filter of ours is
 * involved; what matters is that the game arrives as a {@code content://} URI
 * the app can read, which is the same handover a file manager does.
 */
public final class EsDe {

    private static final String TAG = "Zedex";

    /**
     * The packages ES-DE ships as. It is not on Google Play — Patreon, the
     * Samsung Galaxy Store and Huawei's AppGallery — and the store build has a
     * package of its own, so both are looked for. Each has to be in the
     * manifest's {@code queries} block or Android 11 and later hide them and
     * this finds nothing on a device that has one.
     */
    private static final String[] PACKAGES = {
        "org.es_de.frontend",
        "org.es_de.frontend.galaxy",
    };

    /**
     * The folder ES-DE was shown to us as, when it was shown through the picker.
     *
     * Written once and kept: a persisted tree grant outlives the app, so the
     * user picks their ES-DE folder the first time and never again.
     */
    public static final String KEY_ESDE_TREE = "esdeTree";

    /** The folder inside ES-DE's own where both files belong, and the two files. */
    private static final String CUSTOM = "custom_systems";
    private static final String RULES = "es_find_rules.xml";
    private static final String SYSTEMS = "es_systems.xml";

    /** ES-DE's own name for the Spectrum, and the name it must keep. */
    private static final String SYSTEM = "zxspectrum";

    /** What the find rule calls us; {@code %EMULATOR_ZEDEX%} refers to it. */
    private static final String EMULATOR = "ZEDEX";

    /**
     * The activity ES-DE should start. Named rather than derived from the
     * running class so the debug build writes its own package and the two can
     * be told apart in ES-DE's list; see the package rule in CLAUDE.md.
     */
    private static final String ACTIVITY = "dev.ldlab.zedex.EmulatorActivity";

    /**
     * The launch command, in ES-DE's variables.
     *
     * {@code %ROMPROVIDER%} hands the game over through the FileProvider API,
     * which grants read access for that one file as it launches — so this works
     * without Zedex having any storage permission at all, and without the user
     * having to point Zedex at wherever ES-DE keeps its games.
     */
    private static final String COMMAND =
            "%EMULATOR_" + EMULATOR + "% %ACTIVITY_CLEAR_TASK% %ACTIVITY_CLEAR_TOP%"
            + " %ACTION%=android.intent.action.VIEW %DATA%=%ROMPROVIDER%";

    /** ES-DE's own two, kept so that adding ours does not take them away. */
    private static final String[][] BUNDLED = {
        {"Fuse",
         "%EMULATOR_RETROARCH% %EXTRA_CONFIGFILE%=%EXTERNALDATA%/Android/data/"
         + "%ANDROIDPACKAGE%/files/retroarch.cfg %EXTRA_LIBRETRO%=%INTERNALDATA%/"
         + "%ANDROIDPACKAGE%/cores/fuse_libretro_android.so %EXTRA_ROM%=%ROM%"},
        {"Speccy (Standalone)",
         "%EMULATOR_SPECCY% %ACTIVITY_CLEAR_TASK% %ACTIVITY_CLEAR_TOP%"
         + " %ACTION%=android.intent.action.VIEW %DATA%=%ROMSAF%"},
    };

    /** The rest of what a system entry needs, as ES-DE defines it. */
    private static final String FULLNAME = "Sinclair ZX Spectrum";
    private static final String PATH = "%ROMPATH%/" + SYSTEM;

    /**
     * Built from {@link Types#forEsDe()} rather than written out a second
     * time - two lists that could disagree about, say, {@code .udi} is a bug
     * nobody would find by reading either file on its own. That list is not
     * what the library shows: it carries {@code .sh} and {@code .7z} as well,
     * which are ES-DE's own business - a launcher script and something it
     * unpacks itself - and Fuse opens neither. ES-DE wants both cases of each
     * extension, dotted and space-separated; see {@link #extensions()}.
     */
    private static final String EXTENSIONS = extensions();

    private EsDe() {
    }

    /**
     * {@code Types.forEsDe()} in ES-DE's own syntax: each extension twice,
     * lower and upper case, dotted and separated by a single space. ES-DE's
     * INSTALL.md does not say why it wants both cases rather than matching
     * case-insensitively; it is copied here exactly as it always was.
     */
    private static String extensions() {
        StringBuilder built = new StringBuilder();

        for (String extension : Types.forEsDe()) {
            if (built.length() > 0) built.append(' ');
            built.append('.').append(extension)
                 .append(" .").append(extension.toUpperCase(Locale.ROOT));
        }

        return built.toString();
    }

    /** The installed ES-DE's package name, or null if there is none. */
    public static String installed(Context context) {
        PackageManager packages = context.getPackageManager();

        for (String candidate : PACKAGES) {
            try {
                packages.getPackageInfo(candidate, 0);
                return candidate;
            } catch (PackageManager.NameNotFoundException absent) {
                // The next one, or none.
            }
        }

        return null;
    }

    /**
     * ES-DE's application data directory.
     *
     * {@code ES-DE} at the root of shared storage is where it puts itself and
     * where its own documentation says the file goes. It can be moved, and a
     * moved one is not discoverable from here — ES-DE keeps the choice in its
     * own private storage — so this reports what it can see and the caller says
     * so plainly rather than writing a file nothing will read.
     */
    public static File folder() {
        File folder = new File(Environment.getExternalStorageDirectory(), "ES-DE");
        return folder.isDirectory() ? folder : null;
    }

    /** Whether ES-DE is there and its folder can be written to. */
    public static boolean canInstall(Context context) {
        return installed(context) != null && folder() != null;
    }

    /**
     * Writes both entries, leaving anything else in those files alone.
     *
     * @return false if either file could not be read or written, having said
     *         why in the log; true if ES-DE now knows about this build,
     *         including when it already did.
     */
    public static boolean install(Context context) {
        File folder = folder();
        if (folder == null) return false;

        File custom = new File(folder, CUSTOM);
        if (!custom.isDirectory() && !custom.mkdirs()) {
            Log.w(TAG, "cannot make " + custom);
            return false;
        }

        return write(context, new Paths(custom));
    }

    /**
     * The same, through a folder the user granted with the picker.
     *
     * This is the way that works everywhere: the folder is at the root of shared
     * storage, which scoped storage keeps an app out of, and All files access is
     * not something the Play build may even ask for — but two XML files in a
     * folder the user pointed at needs no permission at all.
     */
    public static boolean install(Context context, Uri tree) {
        Uri folder = child(context, tree, docId(tree), CUSTOM);

        if (folder == null) {
            folder = create(context, tree, docId(tree),
                            DocumentsContract.Document.MIME_TYPE_DIR, CUSTOM);
        }
        if (folder == null) return false;

        return write(context, new Tree(context, tree, DocumentsContract
                .getDocumentId(folder)));
    }

    /**
     * Whether a granted folder is ES-DE's own.
     *
     * A folder that is not is worse than none: the files would be written where
     * ES-DE will never look and the row would say it had worked. Its own name,
     * or the {@code custom_systems} folder it always has, is enough to tell.
     */
    public static boolean looksLikeEsDe(Context context, Uri tree) {
        String name = DocumentsContract.getTreeDocumentId(tree);

        return (name != null && name.endsWith("ES-DE"))
               || child(context, tree, docId(tree), CUSTOM) != null
               || child(context, tree, docId(tree), "settings") != null;
    }

    /**
     * However the app currently reaches ES-DE's own folder for
     * <em>reading</em> - a plain path when this build holds All files access
     * and the folder is visible, the granted tree from the picker otherwise,
     * or null when neither works. Unlike {@link Place}, a name here may be
     * several segments deep, {@code "gamelists/zxspectrum/gamelist.xml"},
     * because the library's metadata layer reads files that are not siblings
     * of each other and of {@code custom_systems}.
     */
    public interface Reach {
        /** The file's bytes, or null if it is not there. */
        InputStream open(String relativePath) throws Exception;

        /**
         * A document for whatever is at that relative path - file or folder -
         * without opening it, or null if it is not really there. What {@link
         * EsdeLink#mediaRoot} uses to reach ES-DE's media folder when it is a
         * child of ES-DE's own, which the ordinary, unconfigured case is.
         */
        Uri locate(String relativePath);
    }

    /**
     * The one way in for {@code library.meta}: this build's own folder access
     * if it has it, the same persisted {@link #KEY_ESDE_TREE} grant that
     * {@link #install(Context, Uri)} uses otherwise, or null when ES-DE cannot
     * be reached at all. Going through this rather than a second way of
     * finding ES-DE keeps "is it there, and can we read it" answered in one
     * place.
     */
    public static Reach reach(Context context) {
        if (Storage.canUseAnyFolder()) {
            File folder = folder();
            if (folder != null) return new PathReach(folder);
        }

        String stored = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ESDE_TREE, null);
        if (stored == null) return null;

        try {
            return new TreeReach(context, Uri.parse(stored));
        } catch (Exception e) {
            Log.w(TAG, "cannot use the granted ES-DE folder " + stored, e);
            return null;
        }
    }

    private static boolean write(Context context, Place place) {
        return findRule(context, place) && system(context, place);
    }

    /** {@code <emulator name="ZEDEX">} and the package to start. */
    private static boolean findRule(Context context, Place place) {
        Document document = read(place, RULES, "ruleList");
        if (document == null) return false;

        String entry = context.getPackageName() + "/" + ACTIVITY;

        Element emulator = child(document.getDocumentElement(), "emulator",
                                 "name", EMULATOR);
        if (emulator == null) {
            emulator = document.createElement("emulator");
            emulator.setAttribute("name", EMULATOR);
            document.getDocumentElement().appendChild(emulator);
        }

        Element rule = child(emulator, "rule", "type", "androidpackage");
        if (rule == null) {
            rule = document.createElement("rule");
            rule.setAttribute("type", "androidpackage");
            emulator.appendChild(rule);
        }

        if (!hasText(rule, "entry", entry)) {
            Element element = document.createElement("entry");
            element.setTextContent(entry);
            rule.appendChild(element);
        }

        return write(document, place, RULES);
    }

    /** The {@code zxspectrum} system, with our command first and theirs kept. */
    private static boolean system(Context context, Place place) {
        Document document = read(place, SYSTEMS, "systemList");
        if (document == null) return false;

        Element system = null;
        for (Element candidate : children(document.getDocumentElement(), "system")) {
            if (SYSTEM.equals(text(candidate, "name"))) {
                system = candidate;
                break;
            }
        }

        if (system == null) {
            // Theirs as well as ours: a custom system replaces the bundled one
            // of the same name, so a list with only Zedex in it would be the
            // only way left to start a Spectrum game.
            system = document.createElement("system");
            append(document, system, "name", SYSTEM);
            append(document, system, "fullname", FULLNAME);
            append(document, system, "path", PATH);
            append(document, system, "extension", EXTENSIONS);
            command(document, system, label(context), COMMAND);
            for (String[] bundled : BUNDLED) {
                command(document, system, bundled[0], bundled[1]);
            }
            append(document, system, "platform", SYSTEM);
            append(document, system, "theme", SYSTEM);
            document.getDocumentElement().appendChild(system);
            return write(document, place, SYSTEMS);
        }

        // A system the user wrote themselves. Add the command if it is not
        // there and touch nothing else - not the extensions they may have
        // trimmed, not the path they may have moved.
        if (child(system, "command", "label", label(context)) == null) {
            command(document, system, label(context), COMMAND);
        }

        return write(document, place, SYSTEMS);
    }

    /**
     * What the command is called in ES-DE's menu. The debug build says so,
     * because the two install side by side and a menu with the same label twice
     * in it would be a guess — and because it is how this was tested at all.
     */
    private static String label(Context context) {
        return context.getPackageName().endsWith(".debug")
                ? "Zedex (debug)" : "Zedex";
    }

    private static void command(Document document, Element system,
                                String label, String text) {
        Element element = document.createElement("command");
        element.setAttribute("label", label);
        element.setTextContent(text);
        system.appendChild(element);
    }

    private static void append(Document document, Element parent,
                               String name, String text) {
        Element element = document.createElement(name);
        element.setTextContent(text);
        parent.appendChild(element);
    }

    /**
     * The file as a document, or a new one with the right root.
     *
     * A file that exists and cannot be parsed is left alone and reported: it is
     * the user's, and half of a broken XML file is worse than none of ours.
     */
    private static Document read(Place place, String name, String root) {
        try {
            DocumentBuilder builder =
                    DocumentBuilderFactory.newInstance().newDocumentBuilder();

            try (InputStream in = place.read(name)) {
                if (in != null) {
                    Document document = builder.parse(in);
                    if (!root.equals(document.getDocumentElement().getNodeName())) {
                        Log.w(TAG, name + " is not a " + root + " file");
                        return null;
                    }
                    return document;
                }
            }

            Document document = builder.newDocument();
            document.appendChild(document.createElement(root));
            return document;
        } catch (Exception e) {
            Log.w(TAG, "cannot read " + name, e);
            return null;
        }
    }

    private static boolean write(Document document, Place place, String name) {
        try (OutputStream out = place.write(name)) {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(
                    "{http://xml.apache.org/xslt}indent-amount", "4");
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot write " + name, e);
            return false;
        }
    }

    // --- the two places the files can be -------------------------------------

    /**
     * Where the two files are, so that everything above this line is the same
     * whether they are reached by path or through a granted folder.
     */
    private interface Place {
        /** The file's bytes, or null if it is not there yet. */
        InputStream read(String name) throws Exception;

        /** An empty file of that name, replacing one that was there. */
        OutputStream write(String name) throws Exception;
    }

    /** With All files access: ordinary files in ES-DE's own folder. */
    private static final class Paths implements Place {

        private final File folder;

        Paths(File folder) {
            this.folder = folder;
        }

        @Override
        public InputStream read(String name) throws Exception {
            File file = new File(folder, name);
            return file.canRead() && file.length() > 0
                    ? new FileInputStream(file) : null;
        }

        @Override
        public OutputStream write(String name) throws Exception {
            return new FileOutputStream(new File(folder, name));
        }
    }

    /** Without it: documents inside the folder the user pointed at. */
    private static final class Tree implements Place {

        private final Context context;
        private final Uri tree;
        private final String folder;

        Tree(Context context, Uri tree, String folder) {
            this.context = context;
            this.tree = tree;
            this.folder = folder;
        }

        @Override
        public InputStream read(String name) throws Exception {
            Uri file = child(context, tree, folder, name);
            return file == null ? null
                    : context.getContentResolver().openInputStream(file);
        }

        @Override
        public OutputStream write(String name) throws Exception {
            Uri file = child(context, tree, folder, name);

            if (file == null) {
                file = create(context, tree, folder, "text/xml", name);
                if (file == null) throw new java.io.IOException("cannot make " + name);
            }

            // "wt" and not "w": a shorter document written over a longer one
            // would otherwise keep the tail of the old one and parse as neither.
            return context.getContentResolver().openOutputStream(file, "wt");
        }
    }

    /** With All files access: an ordinary file, possibly several folders deep. */
    private static final class PathReach implements Reach {

        private final File folder;

        PathReach(File folder) {
            this.folder = folder;
        }

        @Override
        public InputStream open(String relativePath) throws Exception {
            File file = new File(folder, relativePath);
            return file.canRead() && file.length() > 0
                    ? new FileInputStream(file) : null;
        }

        @Override
        public Uri locate(String relativePath) {
            File file = new File(folder, relativePath);
            return file.exists() ? Uri.fromFile(file) : null;
        }
    }

    /** Without it: a document under the granted tree, found without listing it. */
    private static final class TreeReach implements Reach {

        private final Context context;
        private final Uri tree;
        private final String rootDocId;

        TreeReach(Context context, Uri tree) {
            this.context = context;
            this.tree = tree;
            this.rootDocId = docId(tree);
        }

        @Override
        public InputStream open(String relativePath) throws Exception {
            // ExternalStorageProvider's own document ids are literally
            // volume:relative/path - the same fact Storage.pathFor relies on
            // to go from a tree to a real path - so the file several folders
            // down can be addressed in one step rather than by walking one
            // query per folder level to get there.
            Uri file = DocumentsContract.buildDocumentUriUsingTree(
                    tree, rootDocId + "/" + relativePath);

            try {
                return context.getContentResolver().openInputStream(file);
            } catch (Exception e) {
                return null; // not there, or this provider does not shape ids that way
            }
        }

        @Override
        public Uri locate(String relativePath) {
            Uri document = DocumentsContract.buildDocumentUriUsingTree(
                    tree, rootDocId + "/" + relativePath);

            try (Cursor cursor = context.getContentResolver().query(document,
                    new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID },
                    null, null, null)) {
                return cursor != null && cursor.moveToFirst() ? document : null;
            } catch (Exception e) {
                return null; // not there, or this provider does not shape ids that way
            }
        }
    }

    // --- the little that the framework makes verbose -------------------------

    private static String docId(Uri tree) {
        return DocumentsContract.getTreeDocumentId(tree);
    }

    /** The child of that folder with that name, or null. */
    private static Uri child(Context context, Uri tree, String parent, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
        ContentResolver resolver = context.getContentResolver();

        try (Cursor cursor = resolver.query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null) return null;

            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                            tree, cursor.getString(0));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot look inside " + parent, e);
        }

        return null;
    }

    private static Uri create(Context context, Uri tree, String parent,
                              String mime, String name) {
        try {
            return DocumentsContract.createDocument(
                    context.getContentResolver(),
                    DocumentsContract.buildDocumentUriUsingTree(tree, parent),
                    mime, name);
        } catch (Exception e) {
            Log.w(TAG, "cannot create " + name, e);
            return null;
        }
    }

    private static Element child(Element parent, String name,
                                 String attribute, String value) {
        for (Element element : children(parent, name)) {
            if (value.equals(element.getAttribute(attribute))) return element;
        }
        return null;
    }

    private static java.util.List<Element> children(Element parent, String name) {
        java.util.List<Element> found = new java.util.ArrayList<>();
        NodeList nodes = parent.getChildNodes();

        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && name.equals(node.getNodeName())) {
                found.add((Element) node);
            }
        }

        return found;
    }

    private static boolean hasText(Element parent, String name, String text) {
        for (Element element : children(parent, name)) {
            if (text.equals(element.getTextContent().trim())) return true;
        }
        return false;
    }

    private static String text(Element parent, String name) {
        for (Element element : children(parent, name)) {
            return element.getTextContent().trim();
        }
        return null;
    }
}
