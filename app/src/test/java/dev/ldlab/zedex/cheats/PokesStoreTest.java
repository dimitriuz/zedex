package dev.ldlab.zedex.cheats;

import dev.ldlab.zedex.FakePreferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * The poke list's round trip, and the number parser people type into.
 *
 * 11.4's number eight, in the one of its three classes that a dependency
 * actually reaches: {@code Hotkeys} keeps its bindings in an {@code
 * android.util.SparseArray} and {@code ControlProfiles} reaches {@code
 * FuseNative}, so neither is one jar away from the JVM - they want a seam
 * first. This one needs {@link FakePreferences} and a real {@code org.json},
 * and nothing else.
 *
 * Named apart from the instrumentation {@code PokesTest}, which drives the
 * same feature through the screen. This is the storage underneath it.
 */
public class PokesStoreTest {

    private static FakePreferences empty() {
        return new FakePreferences();
    }

    // --- the round trip ---------------------------------------------------------

    /** Nothing stored is an empty list, never null - "never null" is what its
     *  own comment promises and what every caller is written against. */
    @Test
    public void afreshInstallHasAnEmptyList() {
        List<Pokes.Poke> pokes = Pokes.all(empty());

        assertEquals(0, pokes.size());
    }

    @Test
    public void apokeSurvivesBeingWrittenAndReadBack() {
        FakePreferences preferences = empty();

        Pokes.add(preferences, "Infinite lives", 44676, 0);

        List<Pokes.Poke> pokes = Pokes.all(preferences);
        assertEquals(1, pokes.size());
        assertEquals("Infinite lives", pokes.get(0).name);
        assertEquals(44676, pokes.get(0).address);
        assertEquals(0, pokes.get(0).value);
    }

    /** Oldest first, which is what its own comment promises and what makes the
     *  index `remove` takes mean anything. */
    @Test
    public void thelistKeepsTheOrderThingsWereAddedIn() {
        FakePreferences preferences = empty();

        Pokes.add(preferences, "first", 1, 1);
        Pokes.add(preferences, "second", 2, 2);
        Pokes.add(preferences, "third", 3, 3);

        List<Pokes.Poke> pokes = Pokes.all(preferences);
        assertEquals("first", pokes.get(0).name);
        assertEquals("second", pokes.get(1).name);
        assertEquals("third", pokes.get(2).name);
    }

    /** A name with a quote, a backslash and a newline in it - the three things
     *  that break a format nobody escapes properly, and the reason this is
     *  JSON rather than a delimited line. */
    @Test
    public void anameThatWouldBreakADelimitedFormatSurvives() {
        FakePreferences preferences = empty();
        String awkward = "He said \"don't\", \\ then\nleft";

        Pokes.add(preferences, awkward, 32768, 255);

        assertEquals(awkward, Pokes.all(preferences).get(0).name);
    }

    @Test
    public void removingTakesTheOneAtThatIndex() {
        FakePreferences preferences = empty();
        Pokes.add(preferences, "first", 1, 1);
        Pokes.add(preferences, "second", 2, 2);

        Pokes.remove(preferences, 0);

        List<Pokes.Poke> pokes = Pokes.all(preferences);
        assertEquals(1, pokes.size());
        assertEquals("second", pokes.get(0).name);
    }

    /** An index off either end does nothing rather than throwing - the list
     *  can be rewritten under a screen that is still holding an old position. */
    @Test
    public void removingSomethingThatIsNotThereDoesNothing() {
        FakePreferences preferences = empty();
        Pokes.add(preferences, "only", 1, 1);

        Pokes.remove(preferences, -1);
        Pokes.remove(preferences, 5);

        assertEquals(1, Pokes.all(preferences).size());
    }

    /**
     * A stored list that will not parse comes back empty rather than throwing.
     *
     * Its own comment: "a list that will not parse is a list nobody can fix
     * from here; an empty one at least lets a poke be added again". The
     * alternative is a screen that crashes every time it opens, with the thing
     * causing it unreachable from inside the app.
     */
    @Test
    public void arubbishStoredListReadsAsEmptyRatherThanThrowing() {
        FakePreferences preferences = empty().with(Pokes.KEY_POKES, "{not json at all");

        assertEquals(0, Pokes.all(preferences).size());
    }

    /** And it can be written over afterwards, which is the point of not
     *  throwing. */
    @Test
    public void arubbishStoredListCanBeReplaced() {
        FakePreferences preferences = empty().with(Pokes.KEY_POKES, "[[[");

        Pokes.add(preferences, "fresh", 32768, 1);

        assertEquals(1, Pokes.all(preferences).size());
        assertEquals("fresh", Pokes.all(preferences).get(0).name);
    }

    @Test
    public void thetwoNumbersReadTheWayEveryPokeListWritesThem() {
        assertEquals("44676, 0", new Pokes.Poke("x", 44676, 0).numbers());
    }

    // --- what somebody types ------------------------------------------------------

    @Test
    public void adecimalNumberIsRead() {
        assertEquals(44676, Pokes.number("44676", 65535));
        assertEquals(0, Pokes.number("0", 255));
    }

    /** Hexadecimal three ways, because poke lists in the wild use all three. */
    @Test
    public void hexadecimalIsReadInAnyOfItsThreeSpellings() {
        assertEquals(0xAE84, Pokes.number("0xAE84", 65535));
        assertEquals(0xAE84, Pokes.number("0XAE84", 65535));
        assertEquals(0xAE84, Pokes.number("$AE84", 65535));
        assertEquals(0xAE84, Pokes.number("#AE84", 65535));
    }

    @Test
    public void spaceRoundTheNumberIsIgnored() {
        assertEquals(255, Pokes.number("  255  ", 255));
        assertEquals(0xFF, Pokes.number("  $FF ", 255));
    }

    /**
     * Anything that is not a number is -1, and so is anything out of range.
     *
     * -1 for "every empty field and every typo" is what its comment says, and
     * it is the single value the caller checks - an address of 65536 or a byte
     * of 256 has to answer the same way a typo does, because writing either
     * into the machine is the thing this exists to prevent.
     */
    @Test
    public void atypoAndAnOutOfRangeNumberBothAnswerMinusOne() {
        assertEquals(-1, Pokes.number(null, 65535));
        assertEquals(-1, Pokes.number("", 65535));
        assertEquals(-1, Pokes.number("   ", 65535));
        assertEquals(-1, Pokes.number("lives", 65535));
        assertEquals(-1, Pokes.number("0x", 65535));
        assertEquals(-1, Pokes.number("-1", 65535));

        assertEquals("a byte of 256 must be refused", -1, Pokes.number("256", 255));
        assertEquals("an address past 65535 must be refused",
                     -1, Pokes.number("65536", 65535));
    }

    /** The bounds themselves are allowed - off-by-one here would refuse
     *  address 65535 and the value 255, both of which are real. */
    @Test
    public void theLimitItselfIsAllowed() {
        assertEquals(255, Pokes.number("255", 255));
        assertEquals(65535, Pokes.number("65535", 65535));
    }

    /** A number typed in hexadecimal is bounded the same way - the check is on
     *  the value, not on how it was spelled. */
    @Test
    public void theBoundAppliesToHexadecimalToo() {
        assertTrue(Pokes.number("$100", 255) < 0);
        assertEquals(255, Pokes.number("$FF", 255));
    }
}
