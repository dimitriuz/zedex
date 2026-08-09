package dev.ldlab.zedex.frontend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * What {@link EsDe} writes into ES-DE's two files, and what it leaves alone.
 *
 * The most valuable thing in 11.4's list, because the failure is silent and
 * it is somebody else's data. A {@code <system>} in {@code custom_systems}
 * <em>replaces</em> the bundled one of the same name, commands and all - so a
 * regression here does not break Zedex, it quietly takes ES-DE's own Fuse and
 * Speccy entries away from a user who never asked us to touch them. Nothing on
 * either screen would say so; they would simply find two emulators missing.
 *
 * And CLAUDE.md states a property this had no way of checking: <i>"Nothing is
 * overwritten: both files are parsed, ours added if absent, and written back -
 * a second run changes nothing, and user edits stay."</i> That is three claims,
 * all of them asserted here.
 *
 * No device, no ES-DE, and no SAF. {@link EsDe.Place} was always the seam
 * between the merge and where the bytes live - it is what lets one code path
 * serve both All files access and a granted tree - so a third implementation
 * over two byte arrays needs nothing else. The merge itself never wanted a
 * {@code Context}, only the package name, which is what it now takes.
 */
public class EsDeMergeTest {

    private static final String PACKAGE = "dev.ldlab.zedex";
    private static final String DEBUG_PACKAGE = "dev.ldlab.zedex.debug";

    /** ES-DE's two files, in memory. */
    private static final class Memory implements EsDe.Place {

        private final Map<String, byte[]> files = new HashMap<>();

        Memory with(String name, String contents) {
            files.put(name, contents.getBytes());
            return this;
        }

        String text(String name) {
            byte[] bytes = files.get(name);
            return bytes == null ? null : new String(bytes);
        }

        @Override
        public InputStream read(String name) {
            // Null for "not there yet", exactly as Paths does for a file that
            // cannot be read or is empty - which is the first-run case.
            byte[] bytes = files.get(name);
            return bytes == null || bytes.length == 0 ? null : new ByteArrayInputStream(bytes);
        }

        @Override
        public OutputStream write(String name) {
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    files.put(name, toByteArray());
                }
            };
        }
    }

    // --- a first run ------------------------------------------------------------

    /**
     * With nothing there, both files are created - and the system entry
     * carries ES-DE's own two emulators as well as ours.
     *
     * This is the rule with the most at stake in the whole feature. Writing
     * only our own command would leave a {@code zxspectrum} system that
     * replaces ES-DE's bundled one and offers exactly one way to start a
     * Spectrum game.
     */
    @Test
    public void afirstRunWritesBothFilesAndKeepsEsDesOwnEmulators() {
        Memory place = new Memory();

        assertTrue(EsDe.write(PACKAGE, place));

        Element system = theZxSpectrumSystem(place);
        assertNotNull("no zxspectrum system was written", system);

        List<String> commands = commandLabels(system);
        assertTrue("Zedex's own command is missing", commands.contains("Zedex"));
        assertTrue("ES-DE's own Fuse command was not carried over: " + commands,
                   commands.contains("Fuse"));
        assertTrue("ES-DE's own Speccy command was not carried over: " + commands,
                   commands.stream().anyMatch(l -> l.startsWith("Speccy")));

        assertEquals("zxspectrum", textOf(system, "name"));
        assertEquals("Sinclair ZX Spectrum", textOf(system, "fullname"));
    }

    @Test
    public void afirstRunWritesTheFindRuleForThisPackage() {
        Memory place = new Memory();

        assertTrue(EsDe.write(PACKAGE, place));

        assertTrue("the find rule does not name this package",
                   place.text("es_find_rules.xml")
                        .contains(PACKAGE + "/dev.ldlab.zedex.EmulatorActivity"));
    }

    // --- the property CLAUDE.md states ------------------------------------------

    /** A second run changes nothing. Byte for byte, since that is the claim. */
    @Test
    public void aSecondRunChangesNothing() {
        Memory place = new Memory();

        assertTrue(EsDe.write(PACKAGE, place));
        String systemsAfterFirst = place.text("es_systems.xml");
        String rulesAfterFirst = place.text("es_find_rules.xml");

        assertTrue(EsDe.write(PACKAGE, place));

        assertEquals("a second run rewrote es_systems.xml",
                     systemsAfterFirst, place.text("es_systems.xml"));
        assertEquals("a second run rewrote es_find_rules.xml",
                     rulesAfterFirst, place.text("es_find_rules.xml"));
    }

    /** And a third, and a fourth - the row is one a person can keep tapping. */
    @Test
    public void runningItRepeatedlyStillChangesNothing() {
        Memory place = new Memory();
        EsDe.write(PACKAGE, place);
        String settled = place.text("es_systems.xml");

        for (int again = 0; again < 4; again++) EsDe.write(PACKAGE, place);

        assertEquals(settled, place.text("es_systems.xml"));
        assertEquals("one zxspectrum system, not several",
                     1, systems(place).size());
    }

    /**
     * A system the user wrote themselves keeps everything they wrote.
     *
     * The comment in {@code EsDe.system} promises this in as many words: "Add
     * the command if it is not there and touch nothing else - not the
     * extensions they may have trimmed, not the path they may have moved."
     * Both are checked, because both are things somebody would be annoyed to
     * lose and neither would announce itself.
     */
    @Test
    public void aUserWrittenSystemKeepsItsOwnPathExtensionsAndCommands() {
        Memory place = new Memory().with("es_systems.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><systemList>"
                + "<system>"
                + "<name>zxspectrum</name>"
                + "<fullname>My Speccy</fullname>"
                + "<path>%ROMPATH%/somewhere/else</path>"
                + "<extension>.tap .tzx</extension>"
                + "<command label=\"My own emulator\">whatever</command>"
                + "<platform>zxspectrum</platform>"
                + "<theme>zxspectrum</theme>"
                + "</system></systemList>");

        assertTrue(EsDe.write(PACKAGE, place));

        Element system = theZxSpectrumSystem(place);
        assertEquals("the user's own fullname was replaced",
                     "My Speccy", textOf(system, "fullname"));
        assertEquals("the path the user moved was rewritten",
                     "%ROMPATH%/somewhere/else", textOf(system, "path"));
        assertEquals("the extensions the user trimmed were put back",
                     ".tap .tzx", textOf(system, "extension"));

        List<String> commands = commandLabels(system);
        assertTrue("the user's own command was dropped",
                   commands.contains("My own emulator"));
        assertTrue("Zedex was not added to the user's own system",
                   commands.contains("Zedex"));
    }

    /** Another system entirely is not touched, and not counted as ours. */
    @Test
    public void anUnrelatedSystemIsLeftAlone() {
        Memory place = new Memory().with("es_systems.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><systemList>"
                + "<system><name>c64</name><fullname>Commodore 64</fullname>"
                + "<command label=\"Vice\">vice</command></system>"
                + "</systemList>");

        assertTrue(EsDe.write(PACKAGE, place));

        assertEquals("the c64 system was disturbed", 2, systems(place).size());

        Element c64 = systemNamed(place, "c64");
        assertNotNull("the c64 system disappeared", c64);
        assertEquals(java.util.Collections.singletonList("Vice"), commandLabels(c64));
    }

    // --- the two builds side by side ---------------------------------------------

    /**
     * Debug and release install side by side and each gets its own command,
     * labelled apart.
     *
     * The label is what tells them apart in ES-DE's menu, and it is also what
     * decides whether a second install adds a row or finds its own - so a
     * regression here reads as "the debug build overwrote the release one",
     * which is the same shape as the bug this whole file guards against.
     */
    @Test
    public void theDebugBuildAddsItsOwnCommandBesideTheReleaseOne() {
        Memory place = new Memory();

        assertTrue(EsDe.write(PACKAGE, place));
        assertTrue(EsDe.write(DEBUG_PACKAGE, place));

        List<String> commands = commandLabels(theZxSpectrumSystem(place));
        assertTrue("the release command went missing", commands.contains("Zedex"));
        assertTrue("the debug build did not label itself apart",
                   commands.contains("Zedex (debug)"));

        // And neither adds itself twice.
        assertTrue(EsDe.write(PACKAGE, place));
        assertTrue(EsDe.write(DEBUG_PACKAGE, place));
        assertEquals(commands, commandLabels(theZxSpectrumSystem(place)));
    }

    @Test
    public void bothBuildsGetTheirOwnFindRuleEntry() {
        Memory place = new Memory();

        EsDe.write(PACKAGE, place);
        EsDe.write(DEBUG_PACKAGE, place);
        EsDe.write(PACKAGE, place);

        String rules = place.text("es_find_rules.xml");
        assertEquals("the release entry is not there exactly once",
                     1, occurrences(rules, ">" + PACKAGE + "/"));
        assertEquals("the debug entry is not there exactly once",
                     1, occurrences(rules, ">" + DEBUG_PACKAGE + "/"));
    }

    // --- what it does with a file it cannot read -----------------------------------

    /**
     * A file that is not well-formed is refused, not overwritten.
     *
     * Reading it fails, and the merge stops rather than replacing whatever is
     * there with a fresh copy - which would be the same silent data loss from
     * the other direction. The user has to fix or remove it; we do not decide
     * that for them.
     */
    @Test
    public void aFileThatWillNotParseIsNotReplaced() {
        String broken = "<systemList><system><name>zxspectrum</name>";
        Memory place = new Memory().with("es_systems.xml", broken);

        assertEquals("a malformed es_systems.xml was reported as written",
                     false, EsDe.write(PACKAGE, place));
        assertEquals("a malformed es_systems.xml was overwritten",
                     broken, place.text("es_systems.xml"));
    }

    // --- reading the result --------------------------------------------------------

    private static Document parse(String xml) {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes()));
        } catch (Exception e) {
            throw new AssertionError("what was written is not well-formed XML: " + xml, e);
        }
    }

    private static List<Element> systems(Memory place) {
        Document document = parse(place.text("es_systems.xml"));
        List<Element> found = new ArrayList<>();
        NodeList nodes = document.getDocumentElement().getChildNodes();

        for (int at = 0; at < nodes.getLength(); at++) {
            Node node = nodes.item(at);
            if (node instanceof Element && "system".equals(node.getNodeName())) {
                found.add((Element) node);
            }
        }
        return found;
    }

    private static Element systemNamed(Memory place, String name) {
        for (Element system : systems(place)) {
            if (name.equals(textOf(system, "name"))) return system;
        }
        return null;
    }

    private static Element theZxSpectrumSystem(Memory place) {
        return systemNamed(place, "zxspectrum");
    }

    private static String textOf(Element parent, String child) {
        NodeList nodes = parent.getElementsByTagName(child);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private static List<String> commandLabels(Element system) {
        List<String> labels = new ArrayList<>();
        NodeList nodes = system.getElementsByTagName("command");

        for (int at = 0; at < nodes.getLength(); at++) {
            labels.add(((Element) nodes.item(at)).getAttribute("label"));
        }
        return labels;
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
             at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
