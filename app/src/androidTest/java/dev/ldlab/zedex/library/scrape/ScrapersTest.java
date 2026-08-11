package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.storage.Prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Which sources a scrape asks, and in what order.
 *
 * The bench's own choice is put back afterwards: it is the user's device and
 * the setting they left is the one they were using.
 */
@RunWith(AndroidJUnit4.class)
public class ScrapersTest {

    private Context context;
    private SharedPreferences preferences;
    private String theirs;
    private String theirOldOne;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        theirs = preferences.getString(Prefs.KEY_SCRAPERS, null);
        theirOldOne = preferences.getString(Prefs.KEY_SCRAPER, null);
    }

    @After
    public void putItBack() {
        preferences.edit()
                .putString(Prefs.KEY_SCRAPERS, theirs)
                .putString(Prefs.KEY_SCRAPER, theirOldOne)
                .apply();
    }

    private void stored(String scrapers, String oldOne) {
        preferences.edit()
                .putString(Prefs.KEY_SCRAPERS, scrapers)
                .putString(Prefs.KEY_SCRAPER, oldOne)
                .apply();
    }

    private static List<String> namesOf(List<Provider> providers) {
        List<String> names = new ArrayList<>();
        for (Provider provider : providers) names.add(provider.name());
        return names;
    }

    /** Nobody has ever chosen: everything this build has, in its own order. */
    @Test
    public void withNothingStoredEverySourceIsEnabled() {
        stored(null, null);

        assertEquals(Scrapers.names(context), namesOf(Scrapers.enabled(context)));
    }

    /**
     * An older build stored one name, and that was a decision.
     *
     * Migrated faithfully rather than generously: turning "ScreenScraper" into
     * "both of them" would widen what the app fetches, and what it spends a
     * ScreenScraper allowance on, because a feature arrived. That is not ours
     * to decide.
     */
    @Test
    public void anOlderSingleChoiceMigratesToThatOneAlone() {
        List<String> available = Scrapers.names(context);
        assumeTrue("this build has only one source", available.size() > 1);

        stored(null, available.get(1));

        assertEquals(Collections.singletonList(available.get(1)),
                     namesOf(Scrapers.enabled(context)));
    }

    @Test
    public void theStoredOrderIsTheOrderTheyAreAsked() {
        List<String> available = Scrapers.names(context);
        assumeTrue("this build has only one source", available.size() > 1);

        List<String> backwards = new ArrayList<>(available);
        Collections.reverse(backwards);

        Scrapers.save(context, backwards);

        assertEquals(backwards, namesOf(Scrapers.enabled(context)));
    }

    @Test
    public void aSourceLeftOutIsNotAsked() {
        List<String> available = Scrapers.names(context);
        assumeTrue("this build has only one source", available.size() > 1);

        Scrapers.save(context, Collections.singletonList(available.get(0)));

        assertEquals(Collections.singletonList(available.get(0)),
                     namesOf(Scrapers.enabled(context)));
        assertTrue(Scrapers.any(context));
    }

    /** Choosing none is a real choice, and it is not the same as never having
     *  chosen - which is exactly the trap the media setting beside this one
     *  already carries a warning about. */
    @Test
    public void choosingNoneMeansNoneRatherThanTheDefault() {
        Scrapers.save(context, Collections.emptyList());

        assertTrue(Scrapers.enabled(context).isEmpty());
        assertFalse(Scrapers.any(context));
    }

    /** A name from a build that had credentials this one has not. */
    @Test
    public void aStoredNameThisBuildDoesNotHaveIsIgnored() {
        List<String> available = Scrapers.names(context);

        List<String> withAGhost = new ArrayList<>(available);
        withAGhost.add("A Service That Went Away");

        Scrapers.save(context, withAGhost);

        assertEquals(available, namesOf(Scrapers.enabled(context)));
    }

    @Test
    public void savingOneNameIsReadBackAsAString() {
        Scrapers.save(context, Arrays.asList("ZXInfo"));

        // The check-prefs rule, asserted rather than assumed: a getter of the
        // wrong type throws only when the key is present, which is how a
        // mismatch survives every fresh-install test.
        assertEquals("ZXInfo", preferences.getString(Prefs.KEY_SCRAPERS, null));
    }
}
