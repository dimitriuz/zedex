package dev.ldlab.zedex.storage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The three name functions in {@link Storage} that are pure strings.
 *
 * {@code Storage} is the largest class in the app and CLAUDE.md records five
 * separate shipped bugs in its problem space - but almost all of it is
 * {@code File} and MediaProvider behaviour that can only be found on a device,
 * which is what {@code WritableTest} is for. These three are not. They are
 * string in, string out, and each exists because several callers had written
 * their own copy and could disagree - which is precisely the shape of thing a
 * test pins down cheaply and a device test never would.
 */
public class StorageNamesTest {

    // --- sanitise --------------------------------------------------------------

    /** Every character a filename cannot carry, in one go. The set is the
     *  point: three callers used to keep their own idea of it. */
    @Test
    public void everyForbiddenCharacterBecomesAnUnderscore() {
        assertEquals("a_b", Storage.sanitise("a/b"));
        assertEquals("a_b", Storage.sanitise("a\\b"));
        assertEquals("a_b", Storage.sanitise("a:b"));
        assertEquals("a_b", Storage.sanitise("a*b"));
        assertEquals("a_b", Storage.sanitise("a?b"));
        assertEquals("a_b", Storage.sanitise("a\"b"));
        assertEquals("a_b", Storage.sanitise("a<b"));
        assertEquals("a_b", Storage.sanitise("a>b"));
        assertEquals("a_b", Storage.sanitise("a|b"));
    }

    /** One underscore per character, not one per run - a path keeps its shape
     *  so two different names cannot collapse onto one. */
    @Test
    public void eachForbiddenCharacterCountsSeparately() {
        assertEquals("a___b", Storage.sanitise("a/\\:b"));
    }

    /**
     * Trimmed, and trimmed <em>after</em> the substitution.
     *
     * A name that was all spaces, or one ending in a space, is a filename some
     * volumes refuse and others keep in a way nothing can address afterwards.
     */
    @Test
    public void surroundingSpaceIsTaken() {
        assertEquals("Tujad", Storage.sanitise("  Tujad  "));
        assertEquals("", Storage.sanitise("   "));
    }

    /** Space in the middle is a perfectly good filename and is left alone -
     *  most of this collection has one. */
    @Test
    public void spaceInsideTheNameIsKept() {
        assertEquals("Manic Miner", Storage.sanitise("Manic Miner"));
    }

    /** Nothing else is touched. Accents, brackets and the rest are what real
     *  Spectrum filenames are made of. */
    @Test
    public void anythingElseSurvives() {
        assertEquals("Ms. Pac-Man (1984)(Atarisoft)",
                     Storage.sanitise("Ms. Pac-Man (1984)(Atarisoft)"));
        assertEquals("Sábado", Storage.sanitise("Sábado"));
    }

    // --- filename ---------------------------------------------------------------

    /** A document path, a zip entry and a bare name all answer the same way -
     *  no separator means the whole string is already the name. */
    @Test
    public void theFilenameIsTheLastComponentWhateverThePath() {
        assertEquals("game.tap", Storage.filename("/storage/emulated/0/games/game.tap"));
        assertEquals("game.tap", Storage.filename("inside/the/zip/game.tap"));
        assertEquals("game.tap", Storage.filename("game.tap"));
    }

    /** Null in, null out - it is handed document paths that may not resolve. */
    @Test
    public void aNullPathHasNoFilename() {
        assertEquals(null, Storage.filename(null));
    }

    /** A path ending in a separator names no file, and says so with an empty
     *  string rather than by handing back the folder. */
    @Test
    public void aTrailingSeparatorLeavesNothing() {
        assertEquals("", Storage.filename("/a/folder/"));
    }

    // --- withoutExtension ---------------------------------------------------------

    @Test
    public void theExtensionComesOff() {
        assertEquals("Tujad", Storage.withoutExtension("Tujad.z80"));
        assertEquals("some.game", Storage.withoutExtension("some.game.tap"));
    }

    /** No dot, nothing to take off. */
    @Test
    public void aNameWithNoExtensionIsItself() {
        assertEquals("Tujad", Storage.withoutExtension("Tujad"));
    }

    /**
     * A dotfile keeps its whole name.
     *
     * {@code dot > 0}, not {@code dot >= 0}: the dot at position zero is the
     * start of the name, and treating it as an extension would leave nothing
     * at all to call the file.
     */
    @Test
    public void aLeadingDotIsNotAnExtension() {
        assertEquals(".nomedia", Storage.withoutExtension(".nomedia"));
    }

    /**
     * It sanitises too, which its own comment warns is not true of the bare
     * extension strips elsewhere in the tree.
     *
     * Worth pinning: this is what names a state after the game that is
     * loaded, and the game's name comes from a file somebody else wrote.
     */
    @Test
    public void itSanitisesAsWellAsStripping() {
        assertEquals("a_b", Storage.withoutExtension("a/b.tap"));
        assertEquals("Tujad", Storage.withoutExtension("  Tujad  .z80"));
    }
}
