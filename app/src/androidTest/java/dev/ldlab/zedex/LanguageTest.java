package dev.ldlab.zedex;

import dev.ldlab.zedex.screen.Language;
import dev.ldlab.zedex.screen.LibraryActivity;
import dev.ldlab.zedex.storage.Prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

/**
 * The app's own language switch: eight translations and, until now, no test.
 *
 * 11.4's number six. What makes this worth covering is not that a translation
 * might be missing - {@code check-strings.py} already fails on that, in CI,
 * before anything is built - but that the *mechanism* has three separate
 * pieces that each fail quietly and differently:
 *
 *  - {@code wrap} builds the context an activity's resources come from. Wrong,
 *    and a screen is simply in the phone's language with nothing to say why.
 *  - it also sets {@code Locale.getDefault()}, and its comment says why: a
 *    date in the save-state list and anything through {@code String.format} go
 *    by that, so missing it gives "a screen half in one language", which reads
 *    worse than one wholly in the wrong one.
 *  - {@code effectiveTag} exists precisely because {@code tag} cannot notice a
 *    system language change - with no preference set it answers "" before and
 *    "" after. A screen comparing the wrong one concludes nothing happened and
 *    never recreates.
 *
 * Polish, because it is one of the eight and its words for these are visibly
 * not the English ones.
 *
 * The preference is put back in {@link #restoreTheLanguage} whatever happens:
 * it is the user's bench, and an app left in Polish is a confusing thing to
 * come back to.
 */
@RunWith(AndroidJUnit4.class)
public class LanguageTest {

    private static final String POLISH = "pl";

    /** Long enough for a screen to come up. */
    private static final long FIND = 10_000;

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    private Context context;
    private SharedPreferences preferences;
    private String languageBefore;
    private Locale defaultBefore;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        languageBefore = preferences.getString(Language.KEY_LANGUAGE, "");
        defaultBefore = Locale.getDefault();
    }

    @After
    public void restoreTheLanguage() {
        preferences.edit().putString(Language.KEY_LANGUAGE, languageBefore).apply();
        Locale.setDefault(defaultBefore);
    }

    private void choose(String tag) {
        preferences.edit().putString(Language.KEY_LANGUAGE, tag).apply();
    }

    private String inPolish(int id) {
        Context polish = Language.wrap(context);
        return polish.getString(id);
    }

    // --- what wrap builds ---------------------------------------------------------

    /** The chosen language is what a wrapped context's resources answer in. */
    @Test
    public void awrappedContextSpeaksTheChosenLanguage() {
        choose(POLISH);

        String english = context.createConfigurationContext(
                context.getResources().getConfiguration()).getString(R.string.library_play);

        assertEquals("Graj", inPolish(R.string.library_play));
        assertNotEquals("the wrapped context answered in the phone's language",
                        english, inPolish(R.string.library_play));
    }

    /**
     * And so does {@code Locale.getDefault()}, which is the half that is easy
     * to forget.
     *
     * Its own comment: "a date in the save state list and anything through
     * String.format go by it, and a screen half in one language reads worse
     * than a screen in the wrong one."
     */
    @Test
    public void wrappingSetsTheProcessDefaultTooAndNotOnlyTheResources() {
        choose(POLISH);

        Language.wrap(context);

        assertEquals("Locale.getDefault() was left on the phone's language, so dates "
                     + "and String.format would still be in it",
                     POLISH, Locale.getDefault().getLanguage());
    }

    /**
     * Choosing nothing hands back the very context it was given, and puts the
     * process default back.
     *
     * The second half is the one with a comment on it: the default is process
     * wide, so a language chosen and then unchosen would go on formatting
     * dates in it until the app was killed.
     */
    @Test
    public void choosingNothingFollowsThePhoneAndPutsTheDefaultBack() {
        choose(POLISH);
        Language.wrap(context);
        assertEquals(POLISH, Locale.getDefault().getLanguage());

        choose("");
        Context back = Language.wrap(context);

        assertSame("an empty preference should hand back the context it was given",
                   context, back);
        assertNotEquals("the process default is still Polish after the language was "
                        + "unchosen - dates would stay in it until the app was killed",
                        POLISH, Locale.getDefault().getLanguage());
    }

    // --- tag against effectiveTag ---------------------------------------------------

    /**
     * The distinction {@code effectiveTag} exists for.
     *
     * With nothing chosen, {@code tag} is empty - it answers what the
     * preference says, and "follow the phone" is the default. {@code
     * effectiveTag} answers what the resources were actually built with, which
     * is a real language either way. A screen watching the first for a change
     * sees "" before a system language change and "" after, concludes nothing
     * happened, and never recreates itself.
     */
    @Test
    public void tagIsThePreferenceAndEffectiveTagIsWhatIsActuallyInForce() {
        choose("");

        assertEquals("tag() should be empty when nothing is chosen",
                     "", Language.tag(context));

        String effective = Language.effectiveTag(context);
        assertNotNull(effective);
        assertTrue("effectiveTag() answered nothing with no language chosen, so a "
                   + "screen could not notice the phone's own changing",
                   !effective.isEmpty());
    }

    /** With a language chosen, both answer it - and the effective one comes
     *  from a context that was actually wrapped, not from the preference. */
    @Test
    public void bothAnswerTheChosenLanguageOnceThereIsOne() {
        choose(POLISH);

        assertEquals(POLISH, Language.tag(context));
        assertTrue("effectiveTag() on a wrapped context did not say Polish",
                   Language.effectiveTag(Language.wrap(context)).startsWith(POLISH));
    }

    // --- and on a real screen ---------------------------------------------------------

    /**
     * The whole of it, through {@code attachBaseContext}: a screen opened with
     * Polish chosen is in Polish.
     *
     * Every activity has to call {@code Language.wrap} for itself, and one that
     * forgets is not broken in any way the compiler or the string checker can
     * see - it simply comes up in the phone's language. This is the only thing
     * that would notice.
     */
    @Test
    public void ascreenOpenedWithPolishChosenIsInPolish() {
        choose(POLISH);

        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        Screen.suppressFirstRun(context);
        context.startActivity(intent, Screen.here());

        assertNotNull("the library never came up in Polish - its Browse tab should "
                      + "read \"Przeglądaj\"",
                      device.wait(Until.findObject(By.desc("Przeglądaj")), FIND));

        Screen.assertHere();
    }
}
