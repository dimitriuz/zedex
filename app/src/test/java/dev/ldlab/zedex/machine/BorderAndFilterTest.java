package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.FakePreferences;
import dev.ldlab.zedex.storage.Prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The two value objects the machine's picture is described by.
 *
 * They are together because they are the same kind of thing and the same three
 * questions: what a stored string means, what an unrecognised one means, and -
 * for the filter - what happens to a device set up before the setting existed.
 *
 * The last of those is the reason this file exists at all. {@link
 * Filter#migrate} carries two old booleans onto one string, and its comment
 * marks one rule in bold: <b>it writes nothing when there is nothing to
 * carry</b>. A device that has never run the app has no preferences, and that
 * emptiness is exactly how {@code StartPanel.setupNeeded} knows to ask where
 * the files should go. One key written before it looks and the first-start
 * screen never appears again, on every fresh install, for ever. That is a
 * documented invariant three lines long with nothing checking it, and it is
 * the kind that cannot be noticed by using the app - the person who would see
 * it has not installed it yet.
 */
public class BorderAndFilterTest {

    // --- what a stored string means -------------------------------------------

    @Test
    public void aStoredBorderIsTheOneItNames() {
        assertSame(Border.FULL, Border.of("full"));
        assertSame(Border.SLIM, Border.of("slim"));
        assertSame(Border.NONE, Border.of("none"));
    }

    /**
     * Anything else is FULL, including null.
     *
     * Not a detail: a stored value with no matching entry is how this app has
     * drawn a blank row before - see {@code Sorting.fieldOrDefault}, which
     * carries the same rule for the same reason - and a border is read on
     * every frame.
     */
    @Test
    public void anUnknownBorderIsTheDefault() {
        assertSame(Border.FULL, Border.of("panoramic"));
        assertSame(Border.FULL, Border.of(""));
        assertSame(Border.FULL, Border.of((String) null));
    }

    @Test
    public void aBorderComesFromPreferencesByItsOwnKey() {
        assertSame(Border.SLIM, Border.of(new FakePreferences()
                .with(Prefs.KEY_BORDER, "slim")));

        // Never written, which is every fresh install.
        assertSame(Border.FULL, Border.of(new FakePreferences()));
    }

    /** Round, and back to the start - a quick action steps through these, so
     *  the last one has to lead somewhere. */
    @Test
    public void theBordersStepRoundInACircle() {
        Border at = Border.FULL;

        for (int step = 0; step < Border.values().length; step++) at = at.next();

        assertSame("stepping through every border did not come back to the start",
                   Border.FULL, at);
    }

    @Test
    public void aStoredFilterIsTheOneItNames() {
        assertSame(Filter.OFF, Filter.of("off"));
        assertSame(Filter.SCANLINES, Filter.of("scanlines"));
        assertSame(Filter.CRT, Filter.of("crt"));
        assertSame(Filter.BOTH, Filter.of("both"));
        assertSame(Filter.OFF, Filter.of("bakelite"));
        assertSame(Filter.OFF, Filter.of((String) null));
    }

    /** The four are exactly the four combinations of the two booleans, and
     *  each one round trips - which is what lets the quick bar turn one on
     *  without knowing which of the four it is currently in. */
    @Test
    public void theFourFiltersAreTheFourCombinations() {
        for (Filter filter : Filter.values()) {
            assertSame(filter.value, filter, Filter.of(filter.scanlines, filter.crt));
            assertSame(filter.value, filter, Filter.of(filter.value));
        }
    }

    @Test
    public void turningOnePartOnKeepsTheOther() {
        assertSame(Filter.BOTH, Filter.SCANLINES.withCrt(true));
        assertSame(Filter.SCANLINES, Filter.BOTH.withCrt(false));
        assertSame(Filter.CRT, Filter.OFF.withCrt(true));
        assertSame(Filter.OFF, Filter.CRT.withCrt(false));
    }

    // --- the migration --------------------------------------------------------

    /**
     * A device that has never run the app is left completely alone.
     *
     * The one in bold in {@code migrate}'s own comment. Nothing else in the
     * app can catch this going wrong: the symptom is on a machine that has not
     * installed the app yet.
     */
    @Test
    public void migratingAFreshInstallWritesNothingAtAll() {
        FakePreferences preferences = new FakePreferences();

        Filter.migrate(preferences);

        assertTrue("migrate wrote " + preferences.stored() + " on a device with no "
                   + "preferences at all, which is how StartPanel.setupNeeded knows "
                   + "to ask where the files go - the first-start screen would never "
                   + "appear again",
                   preferences.stored().isEmpty());
    }

    /** Two old booleans become the one string that means the same thing, and
     *  the old pair goes - two answers to one question on disk is how the
     *  wrong one survives. */
    @Test
    public void migratingCarriesTheOldPairOntoOneValue() {
        FakePreferences preferences = new FakePreferences()
                .with("scanlines", true)
                .with("crt", true);

        Filter.migrate(preferences);

        assertEquals(Filter.BOTH.value, preferences.getString(Prefs.KEY_FILTER, null));
        assertFalse("the old scanlines key survived the migration",
                    preferences.contains("scanlines"));
        assertFalse("the old crt key survived the migration",
                    preferences.contains("crt"));
    }

    /** One of the two on its own is still something to carry - a device that
     *  only ever turned scanlines on has one key, not two. */
    @Test
    public void migratingCarriesOneOfThePairToo() {
        FakePreferences preferences = new FakePreferences().with("scanlines", true);

        Filter.migrate(preferences);

        assertEquals(Filter.SCANLINES.value, preferences.getString(Prefs.KEY_FILTER, null));
    }

    /** Already migrated, or set by hand since: left exactly as it is. Running
     *  it twice must not undo a choice made after the first run. */
    @Test
    public void migratingIsNotDoneTwice() {
        FakePreferences preferences = new FakePreferences()
                .with(Prefs.KEY_FILTER, Filter.CRT.value)
                .with("scanlines", true);

        Filter.migrate(preferences);

        assertEquals("an existing filter was overwritten by the old booleans",
                     Filter.CRT.value, preferences.getString(Prefs.KEY_FILTER, null));
    }
}
