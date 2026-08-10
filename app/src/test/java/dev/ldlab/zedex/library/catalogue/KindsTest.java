package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Which folder an imported thing lands in.
 *
 * The table is asserted <b>in both directions</b>, which is the lesson from
 * the machine table: written from one collection, it matched "16" inside "ZX81
 * 16K" and offered a 16K Spectrum for the third commonest value in the
 * database. So here the vocabulary is the recorded one - every genreType ZXDB
 * actually uses, 23 of them over 42,828 entries - and the test says both that
 * each known word lands where it should and that nothing outside the list can
 * reach a folder by accident.
 */
public class KindsTest {

    // --- the six folders and the fallback ---------------------------------------------

    @Test
    public void thegameGenresAreGames() {
        assertEquals(Kinds.GAMES, Kinds.folderFor("Arcade Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Adventure Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Puzzle Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Casual Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Sport Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Strategy Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Game"));
    }

    @Test
    public void theprogramsAreApplications() {
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Utility"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Programming"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Emulator"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Replacement ROM"));
    }

    @Test
    public void thecollectionsAreCompilations() {
        assertEquals(Kinds.COMPILATIONS, Kinds.folderFor("Compilation"));
        assertEquals(Kinds.COMPILATIONS, Kinds.folderFor("Covertape"));
        assertEquals(Kinds.COMPILATIONS, Kinds.folderFor("Box Set"));
    }

    @Test
    public void thereadingIsMagazines() {
        assertEquals(Kinds.MAGAZINES, Kinds.folderFor("Electronic Magazine"));
        assertEquals(Kinds.MAGAZINES, Kinds.folderFor("Book"));
        assertEquals(Kinds.MAGAZINES, Kinds.folderFor("E-Book"));
    }

    @Test
    public void thesceneIsDemoscene() {
        assertEquals(Kinds.DEMOSCENE, Kinds.folderFor("Demoscene"));
        assertEquals(Kinds.DEMOSCENE, Kinds.folderFor("Tech Demo"));
        assertEquals(Kinds.DEMOSCENE, Kinds.folderFor("Animation"));
    }

    /**
     * <b>Unknown falls to Other - never a guess, never dropped.</b>
     *
     * "General" alone is 3,650 entries, and a genre added upstream next year
     * has to land somewhere sensible rather than somewhere plausible.
     */
    @Test
    public void anythingUnrecognisedFallsToOther() {
        assertEquals(Kinds.OTHER, Kinds.folderFor("General"));
        assertEquals(Kinds.OTHER, Kinds.folderFor("Hardware"));
        assertEquals(Kinds.OTHER, Kinds.folderFor("Advertising"));

        assertEquals("a genre invented after this was written",
                     Kinds.OTHER, Kinds.folderFor("Firmware"));
        assertEquals(Kinds.OTHER, Kinds.folderFor(null));
        assertEquals(Kinds.OTHER, Kinds.folderFor(""));
    }

    /**
     * The catch-all takes any game, and the ordering protects the rest.
     *
     * The bare "game" keyword is deliberately greedy, and that is not the
     * ZX81 mistake repeating: there, "16" matched inside "ZX81 16K" - a
     * numeric fragment matching across an unrelated machine. Here "game" is a
     * whole word of the domain matching something that genuinely is one - a
     * holographic game, a board game and an educational game are all games -
     * so pin it deliberately, or a later reader mistakes the greediness for a
     * bug and narrows the match.
     *
     * But greedy only works because two rows are tried first: "Gameboy
     * Emulator" and "Electronic Magazine Game" both contain "game" too, and
     * both have to keep landing in Applications and Magazines rather than
     * being swallowed by it. Without asserting these here, moving the GAMES
     * row to the top of the table would silently break them while every
     * other assertion in this method kept passing.
     */
    @Test
    public void thecatchAllTakesAnyGameAndTheOrderingProtectsTheRest() {
        assertEquals(Kinds.GAMES, Kinds.folderFor("Holographic Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Board Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Educational Game"));

        assertEquals("the ordering catches this one first",
                     Kinds.APPLICATIONS, Kinds.folderFor("Gameboy Emulator"));
        assertEquals("and this one",
                     Kinds.MAGAZINES, Kinds.folderFor("Electronic Magazine Game"));
    }

    // --- both directions --------------------------------------------------------------

    /**
     * Every word ZXDB actually uses has a home.
     *
     * The direction that catches an upstream vocabulary this table has drifted
     * behind. Failing here is not a bug in the mapping - it is notice that the
     * database has a word this app has never seen, which is worth knowing
     * before somebody's import lands in Other.
     */
    @Test
    public void everyRecordedGenreLandsSomewhere() {
        for (String genre : Kinds.ZXDB_VOCABULARY) {
            assertNotNull(genre + " has no folder", Kinds.folderFor(genre));
        }
    }

    /**
     * And nothing lands anywhere but the seven.
     *
     * The direction that catches a typo in the table itself, which would
     * otherwise create a folder named after a mistake and put games in it.
     */
    @Test
    public void nothingLandsOutsideTheSeven() {
        Set<String> allowed = new HashSet<>(Arrays.asList(Kinds.ALL));

        for (String genre : Kinds.ZXDB_VOCABULARY) {
            assertTrue(genre + " landed in " + Kinds.folderFor(genre),
                       allowed.contains(Kinds.folderFor(genre)));
        }

        assertEquals("seven folders, no more", 7, allowed.size());
    }

    // --- the order is a rule ----------------------------------------------------------

    /**
     * A compilation of games is a compilation.
     *
     * An entry can honestly be both, and the table is read in order so that
     * the more specific word wins. Without a stated order the rule is not a
     * rule and the answer depends on which line somebody happened to write
     * first.
     */
    @Test
    public void themoreSpecificWordWins() {
        assertEquals(Kinds.COMPILATIONS, Kinds.folderFor("Compilation Game"));
        assertEquals(Kinds.MAGAZINES, Kinds.folderFor("Electronic Magazine Game"));
    }

    /** Matching is case-insensitive, since a second catalogue's vocabulary is
     *  its own and zxart's categories are not capitalised like ZXDB's. */
    @Test
    public void thecaseIsNotTheCatalogueSproblem() {
        assertEquals(Kinds.GAMES, Kinds.folderFor("arcade game"));
        assertEquals(Kinds.DEMOSCENE, Kinds.folderFor("DEMOSCENE"));
    }
}
