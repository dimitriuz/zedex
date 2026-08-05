package dev.ldlab.zedex.frontend;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

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
    private static final String EXTENSIONS =
            ".dsk .DSK .gz .GZ .img .IMG .mgt .MGT .rzx .RZX .scl .SCL .sh .SH "
            + ".sna .SNA .szx .SZX .tap .TAP .trd .TRD .tzx .TZX .udi .UDI .z80 "
            + ".Z80 .7z .7Z .zip .ZIP";

    private EsDe() {
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

        File custom = new File(folder, "custom_systems");
        if (!custom.isDirectory() && !custom.mkdirs()) {
            Log.w(TAG, "cannot make " + custom);
            return false;
        }

        return findRule(context, new File(custom, "es_find_rules.xml"))
               && system(context, new File(custom, "es_systems.xml"));
    }

    /** {@code <emulator name="ZEDEX">} and the package to start. */
    private static boolean findRule(Context context, File file) {
        Document document = read(file, "ruleList");
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

        return write(document, file);
    }

    /** The {@code zxspectrum} system, with our command first and theirs kept. */
    private static boolean system(Context context, File file) {
        Document document = read(file, "systemList");
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
            return write(document, file);
        }

        // A system the user wrote themselves. Add the command if it is not
        // there and touch nothing else - not the extensions they may have
        // trimmed, not the path they may have moved.
        if (child(system, "command", "label", label(context)) == null) {
            command(document, system, label(context), COMMAND);
        }

        return write(document, file);
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
    private static Document read(File file, String root) {
        try {
            DocumentBuilder builder =
                    DocumentBuilderFactory.newInstance().newDocumentBuilder();

            if (file.canRead() && file.length() > 0) {
                Document document = builder.parse(file);
                if (!root.equals(document.getDocumentElement().getNodeName())) {
                    Log.w(TAG, file + " is not a " + root + " file");
                    return null;
                }
                return document;
            }

            Document document = builder.newDocument();
            document.appendChild(document.createElement(root));
            return document;
        } catch (Exception e) {
            Log.w(TAG, "cannot read " + file, e);
            return null;
        }
    }

    private static boolean write(Document document, File file) {
        try (OutputStream out = new FileOutputStream(file)) {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(
                    "{http://xml.apache.org/xslt}indent-amount", "4");
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot write " + file, e);
            return false;
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
