package dev.ldlab.zedex;

import dev.ldlab.zedex.screen.Language;
import dev.ldlab.zedex.screen.WelcomeActivity;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

/**
 * The wizard, walked.
 *
 * Every test here sets setupDone back to what it found: this flag decides
 * whether the app asks anything on the next launch, and a test that leaves it
 * flipped has changed the bench for everything after it.
 */
@RunWith(AndroidJUnit4.class)
public class WelcomeTest {

    /**
     * Long enough for {@code launch()} to find the wizard, and for a single
     * {@link #tapUntil} call to wait out one page's fling-and-tap.
     *
     * <b>One page transition, not a whole walk.</b> Every caller of
     * {@link #tapUntil} waits on a marker only the *next* page shows, so this
     * only ever has to cover one page settling - never a multi-page walk. The
     * 5000ms this was originally measured against covers that case
     * comfortably; a test that needs to survive several pages in a row (see
     * {@link #nextLandsSomewhereRealRatherThanCrashing}) does so by calling
     * {@link #tapUntil} once per page, each with its own fresh {@code WAIT}
     * budget, rather than by this constant growing to cover all of them at
     * once. Widening this for one slow transition would loosen every other
     * call's deadline along with it.
     */
    private static final long WAIT = 5000;

    private UiDevice device;
    private Context context;
    private SharedPreferences preferences;
    private boolean wasDone;
    private String wasLanguage;

    @Before
    public void setUp() {
        device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation());
        context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        preferences = context.getSharedPreferences(Prefs.PREFS,
                                                   Context.MODE_PRIVATE);

        wasDone = preferences.getBoolean(Storage.KEY_SETUP_DONE, false);
        wasLanguage = preferences.getString(Language.KEY_LANGUAGE, "");
    }

    /**
     * The preferences back, and the notification shade shut.
     *
     * <b>The shade is not paranoia - this class pulls it down.</b> Every page
     * here can be taller than the screen, so {@link #scrollTo} flings a {@code
     * UiScrollable}, and the wizard's own ScrollView reaches the top of the
     * window; {@code scrollIntoView} begins by scrolling to the beginning,
     * which is a downward swipe starting near that top edge, and Android
     * reads one of those as the gesture that opens the shade. Measured:
     * launcher focused before this class, {@code mCurrentFocus=Window{...
     * NotificationShade}} after it, every run.
     *
     * It outlives the class, exactly like the file picker CLAUDE.md already
     * warns about - it belongs to SystemUI, so nothing done to this app
     * clears it - and a shade over the screen is a full-height window with
     * none of our views under it. Everything that ran afterwards read the
     * shade instead of the app: sixteen of twenty-three tests in one batch
     * failed reporting "the keyboard never appeared", none of them for a
     * reason of their own.
     *
     * Closed here rather than opened-and-closed around each fling: the swipe
     * that grabs it is the one this class needs, so the cure is to put it
     * back rather than to try not to touch it.
     */
    @After
    public void tearDown() {
        preferences.edit()
                .putBoolean(Storage.KEY_SETUP_DONE, wasDone)
                .putString(Language.KEY_LANGUAGE, wasLanguage)
                .apply();

        try {
            device.executeShellCommand("cmd statusbar collapse");
        } catch (java.io.IOException e) {
            // Nothing this test can do about it, and nothing it should fail
            // for: the assertions have already run by here.
        }
    }

    private void launch() {
        Intent intent = new Intent(context, WelcomeActivity.class);
        intent.putExtra(WelcomeActivity.EXTRA_RETURN, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent, Screen.here());

        assertNotNull("the wizard never appeared",
                device.wait(Until.findObject(
                        By.text(context.getString(R.string.welcome_title))),
                        WAIT));
    }

    /**
     * The page is a column in a plain ScrollView, and the language list ahead
     * of it - nine languages and the system default - is enough rows that the
     * way past sits below the fold. UiAutomator's own tree only reports what
     * a ScrollView has actually scrolled into view, exactly like {@code
     * Emulator.scrollTo} finding a machine by name in the ☰ sheet; without
     * this the row is there and unreadable by a query that never scrolled.
     *
     * <b>Scrolled into view is not the same as safe to tap.</b>
     * {@code scrollIntoView}'s own swipe is a fling, and a {@code ScrollView}
     * answers a touch down while its {@code Scroller} has not finished -
     * {@code isFinished()} - by grabbing it to stop the scroll, whether or
     * not the content has actually moved since. A fling that lands exactly on
     * the last row - the common case here, since the row usually asked about
     * is the one at the very foot of the page - is clamped there well before
     * the {@code Scroller}'s own precomputed duration elapses, so the row's
     * bounds read as settled long before the view is really done consuming
     * touches for it: polling the row's own bounds for two equal reads in a
     * row was tried here first, and it did not catch this - the bounds settle
     * before the fling does. Measured on this bench: a tap thrown right after
     * {@code scrollIntoView} returns lands at the correct, unmoving
     * coordinates and does nothing at all - no click, no scroll, nothing in
     * logcat.
     *
     * Nothing in UiAutomator's public surface reports {@code isFinished()},
     * so there is no condition here to wait on directly - which is why this
     * method does not sleep at all, and {@link #tapUntil} is what actually
     * copes with it, on the other side: it retries the tap itself against an
     * observed effect, rather than this method guessing how long a fling
     * takes to settle. A guessed duration was tried and measured first (1500ms,
     * chosen because it passed here) and dropped for exactly the reason
     * CLAUDE.md gives for never doing that: it is a number this bench
     * happened to agree with, not a number a loaded one would.
     */
    private void scrollTo(String text) {
        try {
            UiScrollable scrollable = new UiScrollable(new UiSelector().scrollable(true));
            if (scrollable.exists()) {
                scrollable.scrollIntoView(new UiSelector().textContains(text));
            }
        } catch (androidx.test.uiautomator.UiObjectNotFoundException e) {
            // Not there, or not scrollable; the caller's own assertion reports it.
        }
    }

    /**
     * Taps the row that answers for {@code text}, not the label's own node,
     * repeating the tap until {@code effect} says it landed - see
     * {@link #scrollTo}'s own comment for why a single tap right after a
     * scroll can be swallowed with nothing on screen or in logcat to say so.
     *
     * {@code Cards.choiceOf} puts the click listener on the row; the label is
     * a plain, non-clickable {@code TextView} inside it, which is what
     * {@code By.text} actually matches. {@code device.click(x, y)} taps
     * coordinates directly rather than asking a specific node to click
     * itself, so it reaches whichever view is actually there - the row,
     * underneath the label - the same as a finger would.
     *
     * The retry is what makes a swallowed tap cost one more attempt instead
     * of a wrong pass or a flaky failure: {@code text} is re-queried fresh
     * every time round, since a tap that did land may have moved or removed
     * it entirely (both {@link #skippingItAllStillFinishesTheSetup} and
     * {@link #nextLandsSomewhereRealRatherThanCrashing} leave the wizard's
     * first page, which is where {@code text} lived), and a control that is
     * genuinely not answering still fails the test, at the timeout, rather
     * than looping forever.
     *
     * <b>Scrolls before every attempt, not only before the first.</b> A
     * ScrollView only puts what it has actually scrolled into view into
     * UiAutomator's own tree - see {@link #scrollTo} - and on a page taller
     * than one screenful (the machine page's long list on a short phone), the
     * target can start below the fold *again* after a
     * page change lands the next page scrolled back to its own top. Calling
     * {@link #scrollTo} once before the loop, as this method used to, found
     * nothing on a later page reached mid-loop and looped inertly to the
     * timeout. {@code scrollTo} is a no-op once the target is already on
     * screen, so this costs nothing on the single-page case it already
     * handled.
     */
    private void tapUntil(String text, java.util.function.BooleanSupplier effect) {
        long deadline = SystemClock.uptimeMillis() + WAIT;

        while (SystemClock.uptimeMillis() < deadline) {
            tapOnce(text);
            if (effect.getAsBoolean()) return;
            SystemClock.sleep(200);
        }
    }

    /**
     * One tap on {@code text}, scrolled into view first - the single-shot
     * half of what {@link #tapUntil} loops. Its own method because
     * {@link #theLibraryPageIsDroppedWithoutAContentFolder} needs exactly one
     * tap and nothing more: a {@code tapUntil} whose effect is "the page
     * after LIBRARY appeared" cannot fail on the regression it exists to
     * catch, since a Next that lands on LIBRARY instead would simply be
     * tapped again by the retry loop until it reached SCRAPING anyway.
     */
    private void tapOnce(String text) {
        scrollTo(text);

        UiObject2 found = device.findObject(By.text(text));
        if (found != null) {
            Rect bounds = found.getVisibleBounds();
            device.click(bounds.centerX(), bounds.centerY());
        }
    }

    @Test
    public void theFirstPageOffersAWayStraightPast() {
        launch();
        scrollTo(context.getString(R.string.welcome_later));

        assertNotNull("no way past the wizard",
                device.findObject(By.text(
                        context.getString(R.string.welcome_later))));
    }

    /** Set it up later is a real answer, and answering means never being
     *  asked again. */
    @Test
    public void skippingItAllStillFinishesTheSetup() {
        preferences.edit().putBoolean(Storage.KEY_SETUP_DONE, false).apply();
        launch();
        scrollTo(context.getString(R.string.welcome_later));

        // The observed effect, not a wait on a clock: finishSetup() writes
        // this before it finishes the activity, so it is true the moment a
        // tap has actually landed - and re-tapped until it is.
        tapUntil(context.getString(R.string.welcome_later),
                () -> preferences.getBoolean(Storage.KEY_SETUP_DONE, false));

        assertTrue("the setup was not recorded as answered",
                   preferences.getBoolean(Storage.KEY_SETUP_DONE, false));
    }

    /**
     * Next has to land somewhere real rather than crashing, all the way
     * through every page there is.
     *
     * Task 9 built LIBRARY and SCRAPING, the last two pages, and retired the
     * {@code default: return null} scaffold {@code WelcomeActivity.stepFor}
     * used to answer for them - {@code forwardFrom}/{@code backwardFrom} no
     * longer walk past an unbuilt page, because there is not one any more.
     * This walk now covers all eight pages, and a page that failed to build
     * would throw {@code IllegalStateException} reaching it rather than
     * being skipped silently, which is what makes landing on the summary a
     * real proof rather than a lucky one.
     *
     * <b>LIBRARY appears here because this bench has a content folder.</b>
     * {@code Steps.applies} drops LIBRARY without one - see
     * {@link #theLibraryPageIsDroppedWithoutAContentFolder} for that side of
     * it - and every catalogue needs no credentials, so {@code hasCatalogue}
     * is true on every build. Nothing here touches the content folder
     * preference, so whichever way it happens to be set, this walk asserts
     * on the pages that preference actually produces rather than guessing.
     *
     * <b>One {@code tapUntil} per page, not one deadline for the whole walk.</b>
     * This used to wait on a single effect - the DONE title - covering
     * several intermediate pages behind one {@code WAIT}, which meant that
     * constant had to be inflated to survive every page's own fling-and-tap
     * in sequence. Instead this walks page by page, each waiting
     * only for the marker the very next page shows, so the shared
     * {@code WAIT} only ever has to cover one page settling - same as every
     * other test in this file.
     */
    @Test
    public void nextLandsSomewhereRealRatherThanCrashing() {
        launch();

        // WELCOME -> FOLDERS
        tapUntil(context.getString(R.string.welcome_next), () ->
                device.findObject(By.textStartsWith(
                        context.getString(R.string.setup_data, ""))) != null);

        // FOLDERS -> MACHINE
        tapUntil(context.getString(R.string.welcome_next), () ->
                device.findObject(By.text(
                        context.getString(R.string.welcome_machine))) != null);

        // MACHINE -> CONTROLS
        tapUntil(context.getString(R.string.welcome_next), () ->
                device.findObject(By.text(
                        context.getString(R.string.welcome_controls))) != null);

        // CONTROLS -> SCREEN
        tapUntil(context.getString(R.string.welcome_next), () ->
                device.findObject(By.text(
                        context.getString(R.string.welcome_screen))) != null);

        // SCREEN -> LIBRARY
        tapUntil(context.getString(R.string.welcome_next), () ->
                device.findObject(By.text(
                        context.getString(R.string.welcome_library))) != null);

        // LIBRARY -> SCRAPING
        tapUntil(context.getString(R.string.welcome_next), () ->
                device.findObject(By.text(
                        context.getString(R.string.welcome_scraping))) != null);

        // SCRAPING -> DONE
        tapUntil(context.getString(R.string.welcome_next), () ->
                device.findObject(By.text(
                        context.getString(R.string.welcome_done_title))) != null);

        assertNotNull("Next did not land on the summary",
                device.findObject(By.text(
                        context.getString(R.string.welcome_done_title))));
    }

    /**
     * The archive page is dropped when there is no content folder: without
     * one startsInLibrary is false whatever the switch says, so the switch
     * would be a setting that cannot take effect.
     *
     * <b>Walked page by page with {@code tapUntil}, not five bare taps.</b>
     * A bare {@code findObject(...).click()} for each of the five steps was
     * tried first and threw a {@code NullPointerException} on this bench -
     * a page can be taller than one screenful (see {@link #scrollTo}'s own
     * comment), so Next is not always in UiAutomator's tree yet when a fixed
     * loop asks for it, whether or not the tap before it
     * actually landed. {@code tapUntil} is what this file already has for
     * exactly that - it scrolls before every attempt and retries against an
     * observed marker - so this walks the same five transitions {@link
     * #nextLandsSomewhereRealRatherThanCrashing} does, except the last: with
     * no content folder, LIBRARY does not apply, so SCREEN's own Next lands
     * on SCRAPING directly.
     *
     * <b>The last step retaps only while still on SCREEN, never on
     * "SCRAPING appeared".</b> A plain {@code tapUntil} keyed on "SCRAPING
     * appeared" cannot fail on the very regression this test exists to catch:
     * were LIBRARY shown anyway despite there being no content folder, the
     * retry loop would simply tap its own Next again and land on SCRAPING
     * regardless, and the assertion below would still pass. A single bare
     * tap has its own problem instead - {@link #scrollTo}'s own comment notes
     * a tap right after a scroll can be swallowed with nothing on screen to
     * say so, which would fail this test for a reason that has nothing to do
     * with the rule it checks. So the retry condition here is "neither
     * SCRAPING nor LIBRARY has appeared yet" rather than "SCRAPING appeared":
     * a swallowed tap keeps retrying because neither marker showed, exactly
     * like {@code tapUntil} - but the moment LIBRARY appears, the loop stops
     * dead rather than tapping again, so the final assertions see the real
     * regression instead of a Next that got tapped straight past it.
     */
    @Test
    public void theLibraryPageIsDroppedWithoutAContentFolder() {
        String folder = preferences.getString(Storage.KEY_CONTENT_TREE, null);
        preferences.edit().remove(Storage.KEY_CONTENT_TREE).apply();

        try {
            launch();

            // WELCOME -> FOLDERS
            tapUntil(context.getString(R.string.welcome_next), () ->
                    device.findObject(By.textStartsWith(
                            context.getString(R.string.setup_data, ""))) != null);

            // FOLDERS -> MACHINE
            tapUntil(context.getString(R.string.welcome_next), () ->
                    device.findObject(By.text(
                            context.getString(R.string.welcome_machine))) != null);

            // MACHINE -> CONTROLS
            tapUntil(context.getString(R.string.welcome_next), () ->
                    device.findObject(By.text(
                            context.getString(R.string.welcome_controls))) != null);

            // CONTROLS -> SCREEN
            tapUntil(context.getString(R.string.welcome_next), () ->
                    device.findObject(By.text(
                            context.getString(R.string.welcome_screen))) != null);

            // SCREEN -> SCRAPING, straight past LIBRARY - the page this test
            // is about. Retapped only while still on SCREEN - see the method
            // comment above for why "SCRAPING appeared" is the wrong effect
            // to key the retry on here.
            String scraping = context.getString(R.string.welcome_scraping);
            String library = context.getString(R.string.welcome_library);
            tapUntil(context.getString(R.string.welcome_next), () ->
                    device.findObject(By.text(scraping)) != null
                            || device.findObject(By.text(library)) != null);

            assertNotNull("scraping should follow the screen page when there "
                        + "is no library page",
                    device.findObject(By.text(scraping)));

            assertNull("the library page should not appear without a content folder",
                    device.findObject(By.text(library)));
        } finally {
            if (folder != null) {
                preferences.edit()
                        .putString(Storage.KEY_CONTENT_TREE, folder).apply();
            }
        }
    }

    /**
     * The same string as the app would draw it with Polish chosen.
     *
     * Read out of the app's own resources rather than written out here, so
     * this test says "the page came back in Polish" and not "the page came
     * back saying <em>Witaj w Zedeksie</em>" - a translation is allowed to be
     * reworded without a test having to be edited to match, and a hard-coded
     * Polish word in an English source file is a thing nobody translating
     * would ever think to look at.
     */
    private String inPolish(int id) {
        Configuration configuration =
                new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(new Locale("pl"));

        return context.createConfigurationContext(configuration).getString(id);
    }

    /**
     * Choosing a language redraws the page in it, which is the only proof a
     * language choice can offer on the page that makes it.
     *
     * <b>The preference is reset first, and that is not a formality.</b>
     * {@link #tapUntil} retries against an observed effect and returns the
     * moment that effect holds - so on a bench whose language preference was
     * already {@code pl}, the very first check would pass before any tap had
     * landed and this test could not fail. Emptying it makes the effect a
     * real transition: empty is "follow the phone", which is never the string
     * {@code pl} whatever the phone is set to, and it is also the state in
     * which the app draws the same language {@link #context} reads its own
     * strings in - which is what {@link #launch}'s English assertion above
     * needs. {@code tearDown} puts back whatever was found, as it already did.
     *
     * Until Task 14 this could only assert that the preference had been
     * written and that the wizard's first page came back at all: there were
     * no Polish strings, so a Polish page and an English one read identically.
     * There are now, so the redraw is asserted in the language it redrew in.
     */
    @Test
    public void choosingALanguageRedrawsThePageInIt() {
        preferences.edit().putString(Language.KEY_LANGUAGE, "").apply();
        launch();

        // No scrollTo here - Polski is on screen without scrolling, so the
        // fling scrollTo's own comment describes does not apply. tapUntil is
        // still used rather than a bare tap, on the same reasoning: the
        // preference write is the real, observable effect of the click
        // landing, and re-querying "Polski" costs nothing if it already did.
        tapUntil("Polski",
                () -> "pl".equals(preferences.getString(Language.KEY_LANGUAGE, "")));

        assertNotNull("the page did not come back in Polish",
                device.wait(Until.findObject(By.text(
                        inPolish(R.string.welcome_title))), WAIT));
        assertEquals("pl", preferences.getString(Language.KEY_LANGUAGE, ""));
    }

    /** The folders page says where things will go, and both rows carry the
     *  answer on the button - what a folder is called matters more here than
     *  what the row would do to it. */
    @Test
    public void theFoldersPageNamesBothFolders() {
        launch();

        device.findObject(By.text(
                context.getString(R.string.welcome_next))).click();

        assertNotNull("the data folder row never appeared",
                device.wait(Until.findObject(By.textStartsWith(
                        context.getString(R.string.setup_data, ""))), WAIT));
        assertNotNull("the content folder row never appeared",
                device.findObject(By.textStartsWith(
                        context.getString(R.string.setup_content, ""))));
    }

}
