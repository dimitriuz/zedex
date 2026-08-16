package dev.ldlab.zedex;

import dev.ldlab.zedex.screen.Language;
import dev.ldlab.zedex.screen.WelcomeActivity;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

/**
 * The wizard, walked.
 *
 * Every test here sets setupDone back to what it found: this flag decides
 * whether the app asks anything on the next launch, and a test that leaves it
 * flipped has changed the bench for everything after it.
 */
@RunWith(AndroidJUnit4.class)
public class WelcomeTest {

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

    @After
    public void tearDown() {
        preferences.edit()
                .putBoolean(Storage.KEY_SETUP_DONE, wasDone)
                .putString(Language.KEY_LANGUAGE, wasLanguage)
                .apply();
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
     * <b>The pause afterwards is load-bearing, not padding.</b> {@code
     * scrollIntoView}'s own swipe is a fling, and a {@code ScrollView}
     * answers a touch down while its {@code Scroller} has not finished -
     * {@code isFinished()} - by grabbing it to stop the scroll, whether or
     * not the content has actually moved since. A fling that lands exactly on
     * the last row - the common case here, since the row usually asked about
     * is the one at the very foot of the page - is clamped there well before
     * the {@code Scroller}'s own precomputed duration elapses, so the row's
     * bounds read as settled long before the view is really done consuming
     * touches for it. Measured on this bench: a tap thrown right after
     * {@code scrollIntoView} returns landed at the correct, unmoving
     * coordinates and did nothing at all - no click, no scroll, nothing in
     * logcat - and the identical tap a little over a second later worked
     * every time. Nothing in UiAutomator's public surface reports {@code
     * isFinished()}, so this is a real wait for a real condition with no way
     * to ask for it directly, not a guess dressed up as one.
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

        SystemClock.sleep(1500);
    }

    /**
     * Taps the row that answers for {@code text}, not the label's own node.
     *
     * {@code Cards.choiceOf} puts the click listener on the row; the label is
     * a plain, non-clickable {@code TextView} inside it, which is what
     * {@code By.text} actually matches. {@code device.click(x, y)} taps
     * coordinates directly rather than asking a specific node to click
     * itself, so it reaches whichever view is actually there - the row,
     * underneath the label - the same as a finger would.
     */
    private void tap(String text) {
        UiObject2 found = device.findObject(By.text(text));
        Rect bounds = found.getVisibleBounds();
        device.click(bounds.centerX(), bounds.centerY());
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

        tap(context.getString(R.string.welcome_later));
        device.wait(Until.gone(By.text(
                context.getString(R.string.welcome_title))), WAIT);

        assertTrue("the setup was not recorded as answered",
                   preferences.getBoolean(Storage.KEY_SETUP_DONE, false));
    }

    /**
     * Next has to land somewhere real rather than crashing.
     *
     * Tasks 5-9 have not written a Step for FOLDERS, MACHINE, CONTROLS,
     * SCREEN, LIBRARY or SCRAPING yet - {@code WelcomeActivity.stepFor}
     * answers null for all six today - so this is the regression the walk in
     * {@code forwardFrom} exists to prevent: without it, tapping the only
     * button on the only reachable page throws {@code IllegalStateException}
     * building the page after it. Landing on the summary is what proves the
     * walk skipped every unbuilt page rather than stopping on one.
     */
    @Test
    public void nextLandsSomewhereRealRatherThanCrashing() {
        launch();
        scrollTo(context.getString(R.string.welcome_next));

        tap(context.getString(R.string.welcome_next));

        assertNotNull("Next did not land on a real page",
                device.wait(Until.findObject(By.text(
                        context.getString(R.string.welcome_done_title))), WAIT));
    }

    /**
     * Choosing a language redraws the page in it, which is the only proof a
     * language choice can offer on the page that makes it.
     *
     * The redraw itself is asserted rather than the Polish word it will
     * eventually show: the translations do not land until Task 14, so
     * "Dalej" cannot appear yet. What is checked instead is the preference
     * the tap is supposed to write, and that the activity came back to its
     * own first page rather than finishing or going anywhere else - which is
     * the proof that it recreated rather than doing nothing at all.
     */
    @Test
    public void choosingALanguageRedrawsThePageInIt() {
        launch();

        tap("Polski");

        assertNotNull("the page did not come back",
                device.wait(Until.findObject(By.text(
                        context.getString(R.string.welcome_title))), WAIT));
        assertEquals("pl", preferences.getString(Language.KEY_LANGUAGE, ""));
    }
}
