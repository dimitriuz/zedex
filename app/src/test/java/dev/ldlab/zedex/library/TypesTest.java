package dev.ldlab.zedex.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the emulator can open, what can be walked into, and what ES-DE is told.
 *
 * Three questions rather than one, and the whole reason {@link Types} exists
 * apart from {@code EsDe.EXTENSIONS} - see its class comment. Getting the
 * difference wrong is not a crash: {@code .sh} shown in the library looks like
 * any other row and then fails the moment somebody taps it, which is the exact
 * thing the split is there to prevent, and nothing on screen would say why.
 */
public class TypesTest {

    // --- the extension itself -------------------------------------------------

    @Test
    public void theExtensionIsWhateverFollowsTheLastDot() {
        assertEquals("tap", Types.extension("game.tap"));
        assertEquals("tap", Types.extension("some.game.tap"));
        assertEquals("z80", Types.extension("/a/path/to/game.z80"));
    }

    /** Lower case, and by {@code Locale.ROOT} - a Turkish device folds a
     *  dotted I to one this would not recognise; see MenuDrawer's own note on
     *  exactly that. */
    @Test
    public void theExtensionIsFoldedToLowerCase() {
        assertEquals("tap", Types.extension("GAME.TAP"));
        assertEquals("tzx", Types.extension("Game.TzX"));
    }

    /**
     * A name ending in a dot has no extension, and neither has one with no dot
     * at all.
     *
     * {@code "file."} is the case worth naming: {@code lastIndexOf} finds the
     * dot, and the obvious {@code substring(dot + 1)} hands back an empty
     * string that would then be compared against the list. It is guarded, and
     * this is what says so.
     */
    @Test
    public void aTrailingDotIsNotAnExtension() {
        assertEquals("", Types.extension("file."));
        assertEquals("", Types.extension("README"));
        assertEquals("", Types.extension(""));
    }

    /** A dotfile is a name, not an extension - {@code lastIndexOf} finds the
     *  dot at position zero, and everything after it is the whole name. */
    @Test
    public void aLeadingDotIsPartOfTheName() {
        assertEquals("nomedia", Types.extension(".nomedia"));
    }

    // --- the three questions ---------------------------------------------------

    @Test
    public void theEmulatorsOwnFormatsAreOpenable() {
        for (String name : Arrays.asList("game.tap", "game.tzx", "game.z80", "game.sna",
                                         "game.szx", "game.dsk", "game.trd", "game.scl",
                                         "game.rzx", "game.mgt", "game.udi", "game.img")) {
            assertTrue(name + " should be openable", Types.openable(name));
        }
    }

    /** A zip is walked into, never loaded - so it is supported without being
     *  openable, which is the whole distinction. */
    @Test
    public void aZipIsAnArchiveAndNotOpenable() {
        assertTrue(Types.archive("games.zip"));
        assertTrue(Types.archive("games.ZIP"));
        assertFalse(Types.openable("games.zip"));
        assertTrue(Types.supported("games.zip"));
    }

    /**
     * ES-DE's two extras are its business and not the emulator's.
     *
     * {@code .sh} is ES-DE's shell-script launcher convention and {@code .7z}
     * is an archive it unpacks itself before handing anything over. Fuse opens
     * neither, so neither may be shown in the library - see docs/LIBRARY.md,
     * "hide what we cannot open".
     */
    @Test
    public void esDesOwnExtrasAreNotShownInTheLibrary() {
        assertFalse(".sh must not be shown - the emulator cannot open one",
                    Types.supported("launch.sh"));
        assertFalse(".7z must not be shown - ES-DE unpacks it, the emulator cannot",
                    Types.supported("games.7z"));
    }

    @Test
    public void anythingElseIsNotSupported() {
        assertFalse(Types.supported("notes.txt"));
        assertFalse(Types.supported("cover.png"));
        assertFalse(Types.supported("README"));
    }

    // --- what ES-DE is told -----------------------------------------------------

    /**
     * ES-DE's list, in the exact order it has always had.
     *
     * The order is load-bearing and the array's own comment says so: this is
     * what goes into {@code es_systems.xml}, and the sequence is what fixed
     * the byte-identical string {@code EsDe.EXTENSIONS} has always written.
     * {@code .sh} sits in the middle of it, alphabetically, which is why the
     * list is written out rather than derived - neither {@code openable} nor
     * {@code archive} would put it there.
     *
     * Spelled out here rather than compared against the source array, which
     * would assert only that a field equals itself.
     */
    @Test
    public void esDeIsToldExactlyThisListInExactlyThisOrder() {
        assertEquals(Arrays.asList(
                "dsk", "gz", "img", "mgt", "rzx", "scl", "sh", "sna", "szx",
                "tap", "trd", "tzx", "udi", "z80", "7z", "zip"),
                Arrays.asList(Types.forEsDe()));
    }

    /** Everything the library will show, ES-DE is told about - the two lists
     *  disagreeing about a format is what having two copies of them once
     *  caused, and it is silent: the row appears in one and not the other. */
    @Test
    public void esDeIsToldAboutEverythingTheLibraryShows() {
        Set<String> told = new HashSet<>(Arrays.asList(Types.forEsDe()));

        for (String name : Arrays.asList("game.tap", "game.tzx", "game.z80", "game.dsk",
                                         "game.sna", "game.szx", "game.trd", "game.scl",
                                         "game.rzx", "game.mgt", "game.udi", "game.img",
                                         "game.gz", "games.zip")) {
            assertTrue("the library shows " + name + " but ES-DE is not told about it",
                       told.contains(Types.extension(name)));
        }
    }

    /** A copy, not the array itself: a caller that sorted or cleared what it
     *  was handed would otherwise change what every later caller is told. */
    @Test
    public void theEsDeListCannotBeChangedFromOutside() {
        String[] first = Types.forEsDe();
        List<String> before = Arrays.asList(Types.forEsDe());

        Arrays.fill(first, ".broken");

        assertEquals("forEsDe handed out its own array", before,
                     Arrays.asList(Types.forEsDe()));
    }
}
