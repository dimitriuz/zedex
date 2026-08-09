package dev.ldlab.zedex.input;

import dev.ldlab.zedex.FakePreferences;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The key profiles' round trip, and the two rules that keep the controls
 * working when the stored list does not.
 *
 * Half of 11.4's number eight. The finding says this class "reaches
 * FuseNative" and so wants a seam before a JVM test can get at it - measured,
 * it does not: {@code FuseNative.JOYSTICK_LEFT} and its neighbours are
 * {@code static final int} with constant initialisers, which javac inlines, so
 * nothing here loads that class or its library at run time. The only real
 * couplings are {@code SharedPreferences}, which is an interface, and {@code
 * org.json}. Both are already answered.
 *
 * {@code label} is left alone deliberately: it falls through to {@code
 * KeyEvent.keyCodeToString} for anything that is not one of the Spectrum's
 * own, and that is a real framework method the stub answers null for. Testing
 * it would need Robolectric, and what it produces is a caption rather than
 * anything a control depends on.
 */
public class ControlProfilesTest {

    private static FakePreferences empty() {
        return new FakePreferences();
    }

    private static List<ControlProfiles.Profile> listOf(ControlProfiles.Profile... profiles) {
        return new ArrayList<>(Arrays.asList(profiles));
    }

    private static ControlProfiles.Profile profile(String name, int firstKey) {
        int[] keys = ControlProfiles.QAOPM.clone();
        keys[0] = firstKey;
        return new ControlProfiles.Profile(name, keys);
    }

    // --- never empty ------------------------------------------------------------

    /**
     * A device with nothing stored gets the built-in layouts.
     *
     * "Never empty" is what the method promises, and {@code current()} does
     * {@code all().get(currentIndex())} with no guard at all - so an empty
     * list is not a blank screen, it is an
     * {@code IndexOutOfBoundsException} the first time anything asks which
     * profile is in use.
     */
    @Test
    public void afreshInstallGetsTheBuiltInProfiles() {
        List<ControlProfiles.Profile> profiles = ControlProfiles.all(empty());

        assertTrue("a fresh install has no control profiles at all",
                   profiles.size() > 0);
    }

    /**
     * And so does a stored list that will not parse.
     *
     * Its own comment: "a profile list is not worth an error message - the
     * controls have to do something, and doing what they have always done is
     * the least surprising thing available."
     */
    @Test
    public void arubbishStoredListFallsBackToTheBuiltInsRatherThanThrowing() {
        FakePreferences preferences = empty()
                .with(ControlProfiles.KEY_PROFILES, "{not json");

        List<ControlProfiles.Profile> profiles = ControlProfiles.all(preferences);

        assertEquals(ControlProfiles.all(empty()).size(), profiles.size());
        assertTrue(profiles.size() > 0);
    }

    /** An empty JSON array is a stored list with nothing in it, which is the
     *  same situation and must answer the same way. */
    @Test
    public void anEmptyStoredListAlsoFallsBack() {
        FakePreferences preferences = empty().with(ControlProfiles.KEY_PROFILES, "[]");

        assertTrue(ControlProfiles.all(preferences).size() > 0);
    }

    // --- the round trip -----------------------------------------------------------

    @Test
    public void aprofileSurvivesBeingWrittenAndReadBack() {
        FakePreferences preferences = empty();
        int[] keys = { 10, 11, 12, 13, 14, 15, 16, 17 };

        ControlProfiles.store(preferences,
                listOf(new ControlProfiles.Profile("Mine", keys)), 0);

        List<ControlProfiles.Profile> back = ControlProfiles.all(preferences);
        assertEquals(1, back.size());
        assertEquals("Mine", back.get(0).name);
        assertArrayEquals(keys, back.get(0).keys);
    }

    @Test
    public void severalProfilesKeepTheirOrderAndWhichOneIsInUse() {
        FakePreferences preferences = empty();

        ControlProfiles.store(preferences,
                listOf(profile("first", 1), profile("second", 2), profile("third", 3)), 2);

        List<ControlProfiles.Profile> back = ControlProfiles.all(preferences);
        assertEquals("third", back.get(2).name);
        assertEquals(2, ControlProfiles.currentIndex(preferences));
        assertEquals("third", ControlProfiles.current(preferences).name);
    }

    /**
     * A stored index pointing past the end of the list answers 0.
     *
     * "Always a profile that exists", and it has to be: a profile removed by
     * one screen while another remembers its index is an ordinary sequence,
     * and {@code current()} indexes straight into the list.
     */
    @Test
    public void anIndexThatNoLongerExistsFallsBackToTheFirst() {
        FakePreferences preferences = empty();
        ControlProfiles.store(preferences, listOf(profile("only", 1)), 0);

        preferences.edit().putInt(ControlProfiles.KEY_CURRENT, 7).apply();

        assertEquals(0, ControlProfiles.currentIndex(preferences));
        assertEquals("only", ControlProfiles.current(preferences).name);
    }

    @Test
    public void anegativeIndexAlsoFallsBackToTheFirst() {
        FakePreferences preferences = empty()
                .with(ControlProfiles.KEY_CURRENT, -1);

        assertEquals(0, ControlProfiles.currentIndex(preferences));
    }

    // --- the profile value object ---------------------------------------------------

    /**
     * A profile given the wrong number of keys takes the default layout
     * instead - which is what stops a truncated stored entry becoming an
     * array index nobody bounds-checks later.
     */
    @Test
    public void aprofileWithTheWrongNumberOfKeysTakesTheDefaultLayout() {
        ControlProfiles.Profile short_ = new ControlProfiles.Profile("x", new int[] { 1, 2 });

        assertArrayEquals(ControlProfiles.QAOPM, short_.keys);
        assertEquals(ControlProfiles.SLOTS, short_.keys.length);
    }

    /** The array is copied in, not held: the caller's own array changing
     *  afterwards must not change the profile. */
    @Test
    public void aprofileCopiesTheKeysItIsGiven() {
        int[] keys = ControlProfiles.QAOPM.clone();
        ControlProfiles.Profile made = new ControlProfiles.Profile("x", keys);

        keys[0] = 999;

        assertNotSame(keys, made.keys);
        assertEquals(ControlProfiles.QAOPM[0], made.keys[0]);
    }

    /** And the default layout itself cannot be edited through a profile made
     *  from it - QAOPM is a public array and every profile would share it. */
    @Test
    public void thedefaultLayoutCannotBeChangedThroughAProfile() {
        int[] before = ControlProfiles.QAOPM.clone();

        ControlProfiles.Profile made =
                new ControlProfiles.Profile("x", new int[] { 1, 2 }).withKey(0, 999);

        assertArrayEquals("QAOPM was edited through a profile built from it",
                          before, ControlProfiles.QAOPM);
        assertEquals(999, made.keys[0]);
    }

    @Test
    public void changingOneKeyLeavesTheRestAndTheNameAlone() {
        ControlProfiles.Profile made = profile("Mine", 5).withKey(3, 42);

        assertEquals("Mine", made.name);
        assertEquals(42, made.keys[3]);
        assertEquals(ControlProfiles.QAOPM[4], made.keys[4]);
    }

    @Test
    public void renamingKeepsEveryKey() {
        ControlProfiles.Profile made = profile("Mine", 5).withName("Yours");

        assertEquals("Yours", made.name);
        assertEquals(5, made.keys[0]);
    }
}
