package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
        return new Catalogue.Item("1", "A game", "1987", "Ocean", "Arcade Game",
                                  "Available", null, Arrays.asList(versions));
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

    /** The whole order, walked - every format beats every one after it. */
    @Test
    public void everyFormatBeatsTheOnesAfterIt() {
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
}
