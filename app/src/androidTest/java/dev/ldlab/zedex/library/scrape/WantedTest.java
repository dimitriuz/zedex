package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.storage.Prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a scrape fetches, from the person's own choice.
 *
 * One question runs through all of this and it is the one that is easy to get
 * wrong: <b>absent is not empty.</b> Nothing stored means nobody has chosen
 * and the default applies; an empty set means somebody deliberately asked for
 * metadata only. Collapse the two and "none" stops being selectable - it
 * silently becomes "the usual three" - and nobody would ever see a bug report
 * about it, because the app would simply fetch more than it was told to.
 *
 * The bench's own choice is put back afterwards; it is a real setting on a
 * real device.
 */
@RunWith(AndroidJUnit4.class)
public class WantedTest {

    private Context context;
    private SharedPreferences preferences;

    /** Whether the bench had a choice stored, and what it was. */
    private boolean had;
    private Set<String> theirs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        had = preferences.contains(Prefs.KEY_SCRAPE_MEDIA);
        Set<String> stored = preferences.getStringSet(Prefs.KEY_SCRAPE_MEDIA, null);
        theirs = stored == null ? null : new LinkedHashSet<>(stored);
    }

    @After
    public void putItBack() {
        if (had) preferences.edit().putStringSet(Prefs.KEY_SCRAPE_MEDIA, theirs).commit();
        else preferences.edit().remove(Prefs.KEY_SCRAPE_MEDIA).commit();
    }

    private void choose(String... folders) {
        preferences.edit()
                .putStringSet(Prefs.KEY_SCRAPE_MEDIA,
                              new LinkedHashSet<>(java.util.Arrays.asList(folders)))
                .commit();
    }

    private void chooseNothing() {
        preferences.edit()
                .putStringSet(Prefs.KEY_SCRAPE_MEDIA, Collections.emptySet())
                .commit();
    }

    private void chooseNotAtAll() {
        preferences.edit().remove(Prefs.KEY_SCRAPE_MEDIA).commit();
    }

    // --- absent, which is not empty ------------------------------------------------

    /** Nobody has chosen, so the three this app actually draws. */
    @Test
    public void nothingStoredIsTheUsualThree() {
        chooseNotAtAll();

        Provider.Wanted wanted = Scrapers.wanted(context);

        assertTrue(wanted.wants("covers"));
        assertTrue(wanted.wants("screenshots"));
        assertTrue(wanted.wants("titlescreens"));
        assertEquals(3, wanted.requests());
        assertFalse("video is opt-in", wanted.wants("videos"));
    }

    /**
     * Chosen nothing is metadata only, and must not become the default.
     *
     * The whole reason {@code Scrapers.wanted} reads the set with a null
     * default and checks for null rather than using
     * {@code getStringSet(key, usual)}: the convenient version cannot tell
     * these two apart, and the failure is silent - somebody asks for no
     * pictures and quietly gets three per game against their allowance.
     */
    @Test
    public void chosenNothingIsMetadataOnlyAndNotTheDefault() {
        chooseNothing();

        Provider.Wanted wanted = Scrapers.wanted(context);

        assertFalse("an empty choice became the default", wanted.any());
        assertEquals(0, wanted.requests());
        assertFalse(wanted.wants("covers"));
    }

    // --- a real choice ----------------------------------------------------------------

    /** Exactly what was ticked, and nothing near it. */
    @Test
    public void whatWasChosenIsWhatIsFetched() {
        choose("covers", "videos");

        Provider.Wanted wanted = Scrapers.wanted(context);

        assertTrue(wanted.wants("covers"));
        assertTrue(wanted.wants("videos"));
        assertFalse(wanted.wants("screenshots"));
        assertFalse(wanted.wants("manuals"));
        assertEquals(2, wanted.requests());
    }

    /**
     * And the cost is one request per medium, which is the number the scrape
     * screen multiplies by.
     *
     * Worth asserting as arithmetic rather than trusting the set's size: this
     * is what decides whether a collection fits inside a day's allowance, and
     * a cover is a {@code mediaJeu.php} call exactly like a search is.
     */
    @Test
    public void everyMediumIsOneRequest() {
        choose("covers", "screenshots", "titlescreens", "backcovers",
               "physicalmedia", "miximages", "videos", "manuals");

        assertEquals("all eight, plus the search, is nine a game",
                     8, Scrapers.wanted(context).requests());
    }

    // --- not holding the preferences' own set ------------------------------------------

    /**
     * The set handed back by {@code getStringSet} belongs to the preferences
     * and must not be kept or mutated - a documented way to corrupt them.
     * {@code Wanted.of} copies, so changing what was stored afterwards does
     * not reach through into a {@code Wanted} already handed out.
     */
    @Test
    public void awantedDoesNotChangeUnderneathItsHolder() {
        choose("covers");
        Provider.Wanted before = Scrapers.wanted(context);

        choose("videos", "manuals");

        assertTrue("it read the store again, or shared its set", before.wants("covers"));
        assertFalse(before.wants("videos"));
        assertEquals(1, before.requests());
    }

    /** And the folders it reports cannot be edited by whoever is shown them. */
    @Test
    public void thefoldersItReportsAreNotEditable() {
        choose("covers");

        try {
            Scrapers.wanted(context).folders().add("videos");
            throw new AssertionError("a screen could add to what will be fetched");
        } catch (UnsupportedOperationException expected) {
            // what should happen
        }
    }
}
