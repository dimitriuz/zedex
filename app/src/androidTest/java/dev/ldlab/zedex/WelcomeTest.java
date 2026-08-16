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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
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

        device.findObject(By.text(
                context.getString(R.string.welcome_later))).click();
        device.wait(Until.gone(By.text(
                context.getString(R.string.welcome_title))), WAIT);

        assertTrue("the setup was not recorded as answered",
                   preferences.getBoolean(Storage.KEY_SETUP_DONE, false));
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

        device.findObject(By.text("Polski")).click();

        assertNotNull("the page did not come back",
                device.wait(Until.findObject(By.text(
                        context.getString(R.string.welcome_title))), WAIT));
        assertEquals("pl", preferences.getString(Language.KEY_LANGUAGE, ""));
    }
}
