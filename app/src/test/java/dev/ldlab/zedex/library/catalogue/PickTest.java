package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One tap has to choose a file, and the choice is invisible.
 *
 * Whatever it picks, something loads and the game runs - so a wrong preference
 * order is never reported as a bug, it is just a collection that quietly
 * became snapshots. That is why this is mutation-tested and why the order is
 * written down as a constant rather than as a sort.
 */
public class PickTest {

    private static Catalogue.Download file(String format) {
        return new Catalogue.Download("https://example/x." + format + ".zip", format, 1000);
    }

    private static Catalogue.Version version(String label, Catalogue.Download... files) {
        return new Catalogue.Version(label, "1987", Arrays.asList(files));
    }

    private static Catalogue.Item item(Catalogue.Version... versions) {
        return Catalogue.Item.builder("1")
                .title("A game").year("1987").publisher("Ocean").kind("Arcade Game")
                .availability("Available").versions(Arrays.asList(versions))
                .build();
    }

    // --- the order ---------------------------------------------------------------------

    /**
     * A tape image beats a disk image beats a snapshot.
     *
     * Not arbitrary: a tape carries the loading scheme and the custom loader,
     * and half of what a Spectrum game is remembered for happens while it
     * loads. A snapshot always works and always starts after the part worth
     * seeing.
     */
    @Test
    public void atapeBeatsAdiskBeatsAsnapshot() {
        assertEquals("tzx", Pick.forGame(version(null, file("z80"), file("dsk"),
                                                 file("tzx"))).format());
        assertEquals("dsk", Pick.forGame(version(null, file("z80"), file("dsk"))).format());
        assertEquals("z80", Pick.forGame(version(null, file("sna"), file("z80"))).format());
    }

    /** tzx before tap: the same tape, and tzx is the format that can hold the
     *  loader a tap has already flattened. */
    @Test
    public void tzxBeatsTap() {
        assertEquals("tzx", Pick.forGame(version(null, file("tap"), file("tzx"))).format());
    }

    /**
     * The scan follows {@link Pick#PREFERENCE} in declaration order, whatever
     * that order is.
     *
     * <b>Deliberately indifferent to the array's contents.</b> Every expected
     * winner here comes from {@code PREFERENCE} itself, so this proves only
     * that a file earlier in the array beats one later in it - it would pass
     * exactly as well if {@code PREFERENCE} were reversed, or in any other
     * order, because "earlier index wins" would still hold either way.
     * That is a real property of {@link Pick#forGame} worth pinning, but it
     * is not the same claim as "the order is the right one" - for that see
     * {@link #theWholeOrderIsWhatThisAppIntends}, which hard-codes its
     * expectations independently of the array and so would catch exactly
     * the reordering this test cannot.
     */
    @Test
    public void theScanHonoursTheDeclaredOrderWhateverItIs() {
        for (int first = 0; first < Pick.PREFERENCE.length; first++) {
            for (int second = first + 1; second < Pick.PREFERENCE.length; second++) {
                Catalogue.Download chosen = Pick.forGame(
                        version(null, file(Pick.PREFERENCE[second]),
                                file(Pick.PREFERENCE[first])));

                assertEquals(Pick.PREFERENCE[first] + " lost to " + Pick.PREFERENCE[second],
                             Pick.PREFERENCE[first], chosen.format());
            }
        }
    }

    /** Tape, disk and snapshot formats, exactly as {@link Pick#PREFERENCE}
     *  groups them - used to state the order rule without restating the
     *  array as a literal, which a reordering that also reordered the
     *  literal would still pass. */
    private static final String[] TAPES = { "tzx", "tap" };
    private static final String[] DISKS = { "trd", "scl", "dsk", "mgt", "img", "udi" };
    private static final String[] SNAPSHOTS = { "szx", "z80", "sna" };

    private static String[] concat(String[]... groups) {
        List<String> all = new ArrayList<>();
        for (String[] group : groups) all.addAll(Arrays.asList(group));
        return all.toArray(new String[0]);
    }

    /** The order this app intends, written out independently of the constant
     *  under test - which is the whole point. A change to {@link
     *  Pick#PREFERENCE} that is not also made here fails, and that is the
     *  guard.
     *
     *  Yes, this restates the order {@code PREFERENCE} declares. Deliberately:
     *  the order encodes domain decisions expensive to rediscover - see
     *  {@link Pick#PREFERENCE}'s own javadoc - so writing it down twice, once
     *  as the constant the code reads and once as the expectation the test
     *  asserts with the reasoning attached, is what makes a casual reordering
     *  fail rather than pass quietly. */
    private static final String[] EXPECTED = concat(TAPES, DISKS, SNAPSHOTS);

    /**
     * The whole order, pairwise, pinned against {@link #EXPECTED} rather than
     * against {@link Pick#PREFERENCE} itself.
     *
     * Subsumes "tape beats disk beats snapshot": a tape image carries the
     * loading scheme and the custom loader that a snapshot has already thrown
     * away, and half of what a Spectrum game is remembered for happens while
     * it loads - a disk falls in between for the same reason, one step
     * further from the original medium. But it goes further than that
     * three-group shape - every entry has to beat every one that comes after
     * it in {@code EXPECTED}, including two entries in the same group, so
     * swapping {@code udi} and {@code trd} inside the disk run fails this
     * exactly as a full reversal does. {@link
     * #theScanHonoursTheDeclaredOrderWhateverItIs} cannot catch either, since
     * it derives its own expectations from {@code PREFERENCE}.
     */
    @Test
    public void theWholeOrderIsWhatThisAppIntends() {
        for (int first = 0; first < EXPECTED.length; first++) {
            for (int second = first + 1; second < EXPECTED.length; second++) {
                Catalogue.Download chosen = Pick.forGame(
                        version(null, file(EXPECTED[second]), file(EXPECTED[first])));

                assertEquals(EXPECTED[first] + " lost to " + EXPECTED[second],
                             EXPECTED[first], chosen.format());
            }
        }
    }

    /** {@link #TAPES}, {@link #DISKS} and {@link #SNAPSHOTS} have to add up
     *  to the whole of {@link Pick#PREFERENCE} - otherwise a format added to
     *  the array later sits outside {@link #theWholeOrderIsWhatThisAppIntends}'s
     *  guarantee with nothing here to say so. */
    @Test
    public void theGroupsAccountForEveryDeclaredFormat() {
        List<String> grouped = Arrays.asList(EXPECTED);
        List<String> declared = Arrays.asList(Pick.PREFERENCE);

        assertEquals(declared.size(), grouped.size());
        assertTrue(grouped.containsAll(declared));
        assertTrue(declared.containsAll(grouped));
    }

    // --- what is never the game --------------------------------------------------------

    /**
     * <b>A recording is not the game.</b>
     *
     * An RZX is somebody playing, and this app can play it back - verified on
     * a device, 10th Frame bowled a frame with nobody touching the controls.
     * So it is worth importing and it is never what "import this game" means:
     * a recording where the game should be is a game you cannot play.
     */
    @Test
    public void arecordingIsNeverChosenAsTheGame() {
        assertNull("a recording was taken as the game",
                   Pick.forGame(version(null, file("rzx"))));

        assertEquals("tap", Pick.forGame(version(null, file("rzx"), file("tap"))).format());
    }

    /** And a wrapper is not a format. .gz is how a thing arrived, not what it
     *  is, so nothing is ever chosen for being one. */
    @Test
    public void agzipIsNeverChosen() {
        assertNull(Pick.forGame(version(null, file("gz"))));
    }

    /** Nor is anything this app has never heard of. */
    @Test
    public void anunknownFormatIsNotChosen() {
        assertNull(Pick.forGame(version(null, file("exe"), file("pdf"))));
    }

    /** A version with nothing in it answers null rather than throwing - an
     *  entry with no files at all is an ordinary thing to find. */
    @Test
    public void aversionOfNothingIsNotAcrash() {
        assertNull(Pick.forGame(new Catalogue.Version(null, null, null)));
        assertNull(Pick.forGame(new Catalogue.Version(null, null,
                                                      Collections.<Catalogue.Download>emptyList())));
    }

    // --- which version ------------------------------------------------------------------

    /**
     * The original, which is whichever the catalogue lists first.
     *
     * Not the best-formatted one: the second version may be a Spanish
     * re-release with a tzx where the original has only a tap, and quietly
     * importing that instead is how somebody ends up with a game in a language
     * they cannot read.
     */
    @Test
    public void theoriginalIsThefirstVersionListed() {
        Catalogue.Item game = item(version("original", file("tap")),
                                   version("Spanish re-release", file("tzx")));

        assertEquals("tap", Pick.forGame(game).format());
    }

    /**
     * Unless the first has nothing this app can open.
     *
     * A version whose only file is a scan or a manual is not a reason to
     * refuse the whole entry - fall through to the next that has something.
     */
    @Test
    public void aversionWithNothingUsableFallsThroughToTheNext() {
        Catalogue.Item game = item(version("original", file("pdf")),
                                   version("re-release", file("tap")));

        assertEquals("tap", Pick.forGame(game).format());
    }

    @Test
    public void anitemWithNothingUsableAnywhereIsNull() {
        assertNull(Pick.forGame(item(version("original", file("pdf")))));
        assertNull(Pick.forGame(item()));
    }

    // --- the recording, in its own right ------------------------------------------------

    /** Offered separately, and found wherever it is - a recording may hang off
     *  any version. */
    @Test
    public void therecordingIsFoundWhereverItIs() {
        Catalogue.Item game = item(version("original", file("tap")),
                                   version("re-release", file("rzx")));

        Catalogue.Download found = Pick.recording(game);

        assertNotNull(found);
        assertEquals("rzx", found.format());
    }

    @Test
    public void nothingIsNotArecording() {
        assertNull(Pick.recording(item(version("original", file("tap")))));
        assertNull(Pick.recording(item()));
    }

    @Test
    public void whatArecordingIs() {
        assertTrue(Pick.isRecording(file("rzx")));
        assertFalse(Pick.isRecording(file("tap")));
        assertFalse(Pick.isRecording(null));
    }

    // --- what is not for the machine ------------------------------------------------

    /**
     * A book is a PDF, and a fifth of this catalogue is not a program.
     *
     * 1,570 books, 1,819 electronic magazines, 1,147 hardware entries -
     * counted from the service's own /metadata/. Every one of them used to end
     * at "Nothing here the Spectrum can open", with the file sitting in the
     * record one tap away.
     */
    @Test
    public void abookAnswersWithItsDocument() {
        Catalogue.Item book = item(version(null, file("pdf")));

        assertNull("a pdf is not something the machine can be handed",
                   Pick.forGame(book));
        assertEquals("pdf", Pick.otherFile(book).format());
    }

    /**
     * The cover is not the book.
     *
     * Both are on the same entry, the picture often first, and answering with
     * it would be answering with the wrapper rather than the thing.
     */
    @Test
    public void apictureGoesLast() {
        assertEquals("pdf", Pick.otherFile(
                item(version(null, file("jpg"), file("pdf")))).format());

        assertEquals("pdf", Pick.otherFile(
                item(version("cover", file("jpg")),
                     version("book", file("pdf")))).format());
    }

    /** ...but an entry that is only a picture still answers with it - an
     *  advertisement, a photographed cassette. */
    @Test
    public void apictureOnItsOwnIsStillAnAnswer() {
        assertEquals("jpg", Pick.otherFile(item(version(null, file("jpg")))).format());
    }

    /** Never the machine's own file: that one has its own answer, and offering
     *  it twice under two names is how a tap comes to mean two things. */
    @Test
    public void agameIsNeverOfferedHere() {
        Catalogue.Item game = item(version(null, file("tzx"), file("z80")));

        assertNotNull(Pick.forGame(game));
        assertNull("a playable file was offered as something to read",
                   Pick.otherFile(game));
    }

    /** And never the recording, for the same reason - it is Play the
     *  recording's, and it is somebody playing the game rather than a thing to
     *  open. */
    @Test
    public void arecordingIsNeverOfferedHere() {
        Catalogue.Item recorded = item(version(null, file("rzx")));

        assertNotNull(Pick.recording(recorded));
        assertNull(Pick.otherFile(recorded));
    }

    /** An entry with nothing at all is still an entry: a row can exist for a
     *  title nobody has uploaded anything for, and that is what the refusal
     *  line is left for. */
    @Test
    public void anentryWithNoFilesAnswersNothing() {
        assertNull(Pick.otherFile(item(version(null))));
        assertNull(Pick.otherFile(item()));
        assertNull(Pick.otherFile(null));
    }

    /** A file with no url is not a file - the catalogue lists what it knows
     *  about, and it does not always know where a thing is. */
    @Test
    public void afileWithNowhereToGetItIsSkipped() {
        Catalogue.Download nowhere = new Catalogue.Download("", "pdf", -1);
        Catalogue.Item item = item(version(null, nowhere, file("txt")));

        assertEquals("txt", Pick.otherFile(item).format());
    }

    /**
     * A {@code .scr} is a picture, and until it was in the list it was not.
     *
     * zxart's graphics entries hold the rendered PNG and the original screen
     * dump. Pick.otherFile answers with the first file that is neither a
     * picture nor for the machine - so a .scr, being in neither list, won,
     * and Open handed a Spectrum screen dump to a phone with nothing that can
     * read one. A .scr *is* a picture, this app renders them already, and with
     * it listed the PNG wins by being listed first.
     */
    @Test
    public void aScreenDumpIsAPicture() {
        Catalogue.Item picture = item(version(null, file("png"), file("scr")));

        assertEquals("png", Pick.otherFile(picture).format());
    }

    /** And an entry whose only file is a screen dump still answers with it,
     *  the same way an advertisement or a photographed cassette does. */
    @Test
    public void aScreenDumpAloneIsStillTheAnswer() {
        Catalogue.Item dump = item(version(null, file("scr")));

        assertEquals("scr", Pick.otherFile(dump).format());
    }
}
