package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * actually uses, 23 of them over the 42,828 entries that carry a genre - and
 * the test says both that each known word lands where it should and that
 * nothing outside the list can reach a folder by accident.
 *
 * That 42,828 is deliberately not the 39,666 the rest of this feature counts:
 * of the dump's 44,215 entries, 39,666 carry a machine type and 42,828 carry a
 * genre. See {@link Kinds}, which explains why more things have a kind than
 * have a computer.
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
     * And nothing lands anywhere but the nine.
     *
     * The direction that catches a typo in the table itself, which would
     * otherwise create a folder named after a mistake and put games in it.
     *
     * Was "the seven" until {@link Kinds#MUSIC} and {@link Kinds#GRAPHICS}
     * joined {@link Kinds#ALL} for zxart's two non-program entities - the
     * count is a fact about {@code ALL}'s size, not a fixed target, so it
     * moves with it. The loop over {@link Kinds#ZXDB_VOCABULARY} is untouched:
     * that half is what would catch a typo in the table, and adding two
     * folders neither of ZXDB's words reaches must not weaken it.
     */
    @Test
    public void nothingLandsOutsideTheNine() {
        Set<String> allowed = new HashSet<>(Arrays.asList(Kinds.ALL));

        for (String genre : Kinds.ZXDB_VOCABULARY) {
            assertTrue(genre + " landed in " + Kinds.folderFor(genre),
                       allowed.contains(Kinds.folderFor(genre)));
        }

        assertEquals("nine folders, no more", 9, allowed.size());
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

    // --- zxart's own nine ---------------------------------------------------------------

    /**
     * The nine root ids, recorded from the live tree on 2026-08-14.
     *
     * Ids rather than words because this is what a prod can be traced to in
     * any language, and a recorded table rather than a lookup because these
     * are what the tree is <em>expected</em> to contain: a tenth root, or one
     * renumbered, should fail here rather than quietly file a fifth of the
     * catalogue under Other.
     */
    @Test
    public void zxartsRootsAreTheNineRecorded() {
        assertEquals("Games", Kinds.zxartRoot(92177));
        assertEquals("System Software", Kinds.zxartRoot(92183));
        assertEquals("Misc", Kinds.zxartRoot(92188));
        assertEquals("Educational", Kinds.zxartRoot(92534));
        assertEquals("Compilation", Kinds.zxartRoot(202588));
        assertEquals("Demoscene", Kinds.zxartRoot(204819));
        assertEquals("Press", Kinds.zxartRoot(244858));
        assertEquals("Applications", Kinds.zxartRoot(244880));
        assertEquals("Series", Kinds.zxartRoot(551860));

        assertEquals(9, Kinds.ZXART_ROOTS.length);
    }

    /** An id that is not a root - a leaf, or something new upstream - is not
     *  answered with a plausible guess. */
    @Test
    public void anythingElseIsNotARoot() {
        assertNull(Kinds.zxartRoot(523395));
        assertNull(Kinds.zxartRoot(0));
    }

    /**
     * Every one of zxart's nine words reaches a folder, and the three new ones
     * reach the right one.
     *
     * This is the both-directions rule applied to a second vocabulary: the
     * words come from the service and the folders are ours, and a word that
     * fell through to Other silently would be a fifth of an archive landing in
     * the wrong place.
     */
    @Test
    public void everyZxartRootLandsSomewhereDeliberate() {
        assertEquals(Kinds.GAMES, Kinds.folderFor("Games"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("System Software"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Applications"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Educational"));
        assertEquals(Kinds.COMPILATIONS, Kinds.folderFor("Compilation"));
        assertEquals(Kinds.MAGAZINES, Kinds.folderFor("Press"));
        assertEquals(Kinds.DEMOSCENE, Kinds.folderFor("Demoscene"));
        assertEquals(Kinds.OTHER, Kinds.folderFor("Misc"));
        assertEquals(Kinds.OTHER, Kinds.folderFor("Series"));
    }

    /** The two entities that are not programs at all. */
    @Test
    public void musicAndGraphicsHaveFoldersOfTheirOwn() {
        assertEquals(Kinds.MUSIC, Kinds.folderFor("Music"));
        assertEquals(Kinds.GRAPHICS, Kinds.folderFor("Graphics"));
    }

    /**
     * The new words do not steal ZXDB's.
     *
     * "Educational" reaches Applications and must not drag "Educational Game"
     * with it - zxart has such a category and ZXDB has the phrase - and
     * "Press" must not catch anything else. Asserted because folderFor
     * matches by contains and order, so a new row is a new chance to shadow
     * an old one.
     */
    @Test
    public void theNewWordsDoNotShadowTheOldOnes() {
        assertEquals(Kinds.GAMES, Kinds.folderFor("Educational Game"));
        assertEquals(Kinds.MAGAZINES, Kinds.folderFor("Electronic Magazine"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Utility"));
    }
}
