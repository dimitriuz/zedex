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
import androidx.test.uiautomator.UiObject2;
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
 * Polish, because it is one of the nine and its words for these are visibly
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

    // --- and on a screen that was already open ------------------------------------------

    /**
     * A language chosen while the library is open reaches it when it comes
     * back to the front - which is what happens when Settings, opened from
     * it, closes.
     *
     * It did not. The screen's words were resolved when it was built, and
     * nothing rebuilt it for a preference: Android recreates a screen when
     * the phone's language changes and never otherwise. A new intent to the
     * open library is a pause and a resume and nothing more, which is all
     * that coming back from Settings is to it - so this fails without the
     * check in {@code ZedexActivity.onResume}, rather than passing because
     * the screen was built afresh.
     */
    @Test
    public void theLibraryFollowsALanguageChosenWhileItWasOpen() {
        choose("");

        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        Screen.suppressFirstRun(context);
        context.startActivity(intent, Screen.here());

        assertNotNull("the library never came up in the phone's language",
                      device.wait(Until.findObject(
                              By.desc(context.getString(R.string.library_tab_browse))), FIND));

        choose(POLISH);

        // SINGLE_TOP, not CLEAR_TOP: a standard activity under CLEAR_TOP alone
        // is finished and started again, which would pass with or without the
        // fix.
        Intent again = new Intent(context, LibraryActivity.class);
        again.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        again.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(again, Screen.here());

        assertNotNull("the library came back in the language it was opened in - its "
                      + "Browse tab should read \"Przeglądaj\"",
                      device.wait(Until.findObject(By.desc("Przeglądaj")), FIND));

        Screen.assertHere();
    }

    /**
     * The same for the machine, which has the harder case and its own copy of
     * the check: it lists {@code locale} in configChanges, so nothing at all
     * rebuilds it but that check.
     *
     * Before, it compared the language its resources were built with against
     * itself - frozen once a language is chosen - so it stayed in the old one
     * until the app was restarted, while every screen opened from it spoke
     * the new one. It is {@code singleInstance}, so a new intent reaches the
     * open machine as a pause and a resume, like the library above.
     */
    @Test
    public void theMachineFollowsALanguageChosenWhileItWasOpen() {
        choose("");

        Emulator emulator = new Emulator();
        emulator.useDataFolder();
        emulator.launch();

        choose(POLISH);

        Intent intent = new Intent(context, EmulatorActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent, Screen.here());

        // The quick bar fades three seconds after it was last touched, and a
        // tap on the picture brings it back - so tap until the row is there
        // to be read, rather than guessing how long the rebuild takes.
        UiObject2 files = null;
        for (int i = 0; i < 10 && files == null; i++) {
            device.click(device.getDisplayWidth() / 2, device.getDisplayHeight() / 8);
            files = device.wait(Until.findObject(By.desc("Pliki…")), 1000);
        }

        assertNotNull("the machine came back in the language it was opened in - its "
                      + "quick bar should offer \"Pliki…\"", files);

        Screen.assertHere();
    }
}
