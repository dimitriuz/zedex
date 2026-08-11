package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.Screen;
import dev.ldlab.zedex.screen.LibraryActivity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The door: the fourth tab in the library's own rail, and what a tap on it
 * does to the screen around it.
 *
 * <b>Why this exists beside {@code CatalogueScreenTest}.</b> That one, and
 * {@code ImportFlowTest} with it, put a {@link
 * dev.ldlab.zedex.library.ui.CatalogueView} into the activity with {@code
 * setContentView} - which destroys the rail, so neither of them can see the tab
 * at all. Everything the tab itself is made of - the button being built only
 * when a catalogue is configured, the right one lighting up, the library's own
 * column giving way, and Back reaching the view before it reaches the activity
 * - would go on passing green with the tab deleted. So: they test the view,
 * this tests the door.
 *
 * <b>No request is made by any of this.</b> {@code Catalogue.shelves()} is
 * declared data, and ZXInfo's A-Z answers its twenty-seven shelves without asking
 * anything either - see {@code ZxInfoCatalogue.letters}. That is deliberate:
 * this class is about the tab, and a test of the tab that fails when a server
 * in another country is slow is a test of the wrong thing. The live half is
 * {@code CatalogueScreenTest}'s.
 *
 * <b>No skip.</b> The tab is built whenever {@code Catalogues.any} is true, and
 * ZXInfo needs no credentials, so it is always true in this build - a run that
 * cannot find the button has found a defect, not a bench without something set
 * up.
 */
@RunWith(AndroidJUnit4.class)
public class CatalogueTabTest {

    /** The screen and a local shelf, with nothing to wait for a network for. */
    private static final long FIND = 15_000;

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        launchLibrary();
    }

    /**
     * The button is in the rail, tapping it puts the catalogue on screen, and
     * the library's own column gets out of the way.
     *
     * The last of those is the half that a screenshot would not settle: the
     * catalogue could be drawn perfectly over a library toolbar still taking
     * taps and still answering the accessibility tree. So the Options button -
     * which lives in the column the tab hides - is asserted <em>absent</em>.
     */
    @Test
    public void theTabOpensTheCatalogueAndTheLibrarysOwnColumnGivesWay() {
        UiObject2 catalogue = tab(R.string.library_tab_catalogue);
        assertNotNull("no Catalogue button in the library's rail - the fourth"
                      + " tab is built whenever a catalogue is configured, and"
                      + " ZXInfo always is", catalogue);

        assertTrue("the library did not come up on Browse",
                   tab(R.string.library_tab_browse).isSelected());
        assertFalse("the Catalogue tab was already the selected one before it"
                    + " was tapped", catalogue.isSelected());

        catalogue.click();

        assertNotNull("tapping Catalogue did not bring up the catalogue's own"
                      + " search field", device.wait(Until.findObject(By.desc(
                              context.getString(R.string.catalogue_search_hint))), FIND));

        assertNotNull("the catalogue's declared shelves are not on screen",
                      device.wait(Until.findObject(By.text("A-Z")), FIND));
        assertNotNull("the catalogue's declared shelves are not on screen",
                      device.wait(Until.findObject(By.text("Categories")), FIND));

        // The rail is keyed by tab now rather than indexed by ordinal, and the
        // failure that change exists to prevent is exactly this: the wrong
        // button lit, which reads as a tap that went somewhere else.
        assertTrue("the Catalogue tab is showing but its own button is not the"
                   + " one lit", tab(R.string.library_tab_catalogue).isSelected());
        assertFalse("Browse is still the lit tab while the catalogue is showing",
                    tab(R.string.library_tab_browse).isSelected());

        assertNull("the library's own toolbar is still on screen behind the"
                   + " catalogue - its column should be gone, not merely"
                   + " covered",
                   device.findObject(By.desc(context.getString(R.string.library_options))));

        Screen.assertHere();
    }

    /**
     * Back inside the catalogue pops its shelf; it does not leave the screen.
     *
     * The activity claims Back for the whole of this tab - {@code
     * syncBackCallback} cannot ask a view whose answer changes with every tap -
     * so the press arriving with a shelf open has to reach {@code
     * CatalogueView.onBack} and stop there. Getting that wrong finishes the
     * activity instead, which is why "the shelves are back" is not enough on
     * its own and the activity is asserted still resumed.
     */
    @Test
    public void backInsideAShelfPopsItRatherThanLeavingTheLibrary() {
        tab(R.string.library_tab_catalogue).click();

        UiObject2 letters = device.wait(Until.findObject(By.text("A-Z")), FIND);
        assertNotNull("the catalogue's shelves never appeared", letters);
        letters.click();

        // Twenty-seven sub-shelves, and no request behind them - see the class
        // comment. "B" and not "Z": all of them are in the list, but only
        // the first few are on screen, and a selector only sees what is drawn -
        // asking for the last letter failed here for a shelf that had opened
        // perfectly.
        assertNotNull("descending into A-Z did not open its own shelves",
                      device.wait(Until.findObject(By.text("B")), FIND));

        device.pressBack();

        assertNotNull("Back did not return to the catalogue's own shelves",
                      device.wait(Until.findObject(By.text("Categories")), FIND));

        assertNotNull("Back popped the shelf and left the library as well -"
                      + " LibraryActivity is no longer the resumed activity",
                      resumedLibrary());

        // And it is still the catalogue that is showing, not Browse behind it.
        assertTrue("Back left the catalogue tab",
                   tab(R.string.library_tab_catalogue).isSelected());
    }

    // --- getting onto the screen ------------------------------------------------

    /** One rail button, by the name it is described with. */
    private UiObject2 tab(int label) {
        return device.wait(Until.findObject(By.desc(context.getString(label))), FIND);
    }

    /**
     * The same door {@code EmulatorActivity.openLibrary} uses - a plain
     * launcher intent bounces straight back to the machine whenever {@code
     * startsInLibrary} is off, and this test wants the screen regardless of
     * that switch. Copied from {@code CatalogueScreenTest} rather than shared:
     * a helper between two test classes is a third thing to keep working, and
     * this is six lines.
     */
    private void launchLibrary() {
        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        // On the display this test can see - see Screen.
        context.startActivity(intent, Screen.here());

        assertNotNull("the library screen never came up", device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_options))), FIND));

        // Before anything is read off the screen: a file picker left open by
        // hand survives a force-stop of the app and the next launch comes up
        // behind it, after which every reading here is of the wrong screen.
        Screen.assertHere();
    }

    private Activity resumedLibrary() {
        Activity[] found = { null };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (activity instanceof LibraryActivity) found[0] = activity;
            }
        });

        return found[0];
    }
}
