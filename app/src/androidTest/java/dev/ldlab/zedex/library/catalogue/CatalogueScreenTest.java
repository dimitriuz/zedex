package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.Screen;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.ui.CatalogueView;
import dev.ldlab.zedex.screen.LibraryActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

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

import java.util.function.IntPredicate;

/**
 * The catalogue on screen: its shelves, its Back, and a search that brings
 * rows back from the live service.
 *
 * <b>Why this drives the view directly rather than tapping a tab.</b> It was
 * written before the fourth library tab existed, when there was nothing to
 * tap; it stays this way now that there is, because putting the view into a
 * real activity on the real display is what exercises the view itself - the
 * shelf list, the stack, the search field, the fetch off the UI thread and the
 * rows it produces - without a rail, a tab or an activity's own Back in the
 * way. The door that opens it is {@code CatalogueTabTest}, which cannot use
 * this shape for the opposite reason: {@code setContentView} here destroys the
 * rail that test is about.
 *
 * <b>There is no skip, on purpose.</b> The obvious one - "no network, so pass
 * quietly" - would hide the failure this project most needs to hear about.
 * There are two reasons the catalogue would not answer: this bench is offline,
 * and <em>this app's address has been blocked by that host again</em>, which
 * has happened once and took an email to lift. A green run says nothing about
 * either. So the test waits for the rows and fails when they do not come,
 * naming both causes in the message - a red test that names what to check is
 * strictly more use than a skip that says nothing. (It would also have needed
 * ACCESS_NETWORK_STATE, which this app does not declare and should not start
 * declaring to power a skip.)
 *
 * <b>And the waits are for conditions.</b> A page arrives when the service
 * answers, which depends on the service, the connection and what else the
 * device is doing. {@link #awaitRowCount} polls the adapter the way {@code
 * FilterTest} does; a sample taken after a fixed sleep passes alone and fails
 * behind three other classes.
 *
 * <b>One live request per run</b>, and only in the test that needs one: this
 * host blocked this app's address once, at the network layer, for behaviour
 * patterns. The first test makes none at all - {@code Catalogue.shelves()} is
 * declared data.
 */
@RunWith(AndroidJUnit4.class)
public class CatalogueScreenTest {

    /** Long enough for the screen, and for one paced request over a phone's
     *  connection to a server in another country. */
    private static final long FIND = 30_000;

    private static final long POLL = 100;

    /** What {@code ZxInfoCatalogue.shelves()} declares: Search, A-Z,
     *  Categories, Newest, Surprise me. Asserted rather than assumed, since
     *  "some rows appeared" is what a screen that failed to clear also looks
     *  like. */
    private static final int SHELVES = 5;

    /** A game that has been in this database since 1983 and is not going to
     *  stop matching. */
    private static final String SEARCH = "Manic Miner";

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    private Context context;
    private CatalogueView view;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        launchLibrary();
        installTheView();
    }

    /**
     * The ways in are on screen without a single request, and Back at the
     * roots is not this view's to handle.
     *
     * The second half is what keeps the activity's own Back working: a view
     * that answered true everywhere would swallow it, and the only way out of
     * the library would be the task switcher.
     */
    @Test
    public void theShelvesAreTheWayInAndBackAtTheRootsIsNotOurs() {
        assertEquals("the catalogue did not draw its declared shelves",
                     SHELVES, rowCount());

        assertNotNull("no row on screen is the Search shelf",
                      device.wait(Until.findObject(By.text("Search")), FIND));
        assertNotNull("no row on screen is the Newest shelf",
                      device.wait(Until.findObject(By.text("Newest")), FIND));

        assertFalse("the view claimed Back at the roots, which would leave the"
                    + " activity with no way out of the library",
                    onBack());
    }

    /**
     * A typed search reaches the service and comes back as rows - and Back
     * from inside it returns to the shelves.
     *
     * Typed into the field and committed with Enter, rather than called
     * straight through: the editor action is the whole of how a search is
     * started, and a test that called the method behind it would pass on a
     * screen where nothing was wired to anything.
     */
    @Test
    public void aTypedSearchBringsRowsBackFromTheCatalogue() {
        UiObject2 field = device.wait(Until.findObject(By.desc(
                context.getString(R.string.catalogue_search_hint))), FIND);
        assertNotNull("the catalogue has no search field on screen", field);

        field.click();
        field.setText(SEARCH);
        device.pressEnter();

        // Not "more than none": the shelves were five rows, and a page that
        // never arrived leaves exactly those five. What is waited for is a
        // count that is a page of results rather than the list that was
        // already there.
        int found = awaitRowCount(count -> count > 0 && count != SHELVES);
        assertTrue("searching for \"" + SEARCH + "\" brought nothing back within "
                   + (FIND / 1000) + "s - the rows on screen are still the "
                   + SHELVES + " shelves (counted " + found + "). Two things to"
                   + " check before the screen: whether this device has a"
                   + " network at all, and whether api.zxinfo.dk is refusing"
                   + " this address again - it has blocked it once, at the"
                   + " network layer, and DNS still resolves while port 443 is"
                   + " refused, so that looks exactly like the host being down.",
                   found > 0 && found != SHELVES);

        assertNotNull("no row on screen names \"" + SEARCH + "\", so what came"
                      + " back is not this search's own results",
                      device.wait(Until.findObject(By.textContains(SEARCH)), FIND));

        Screen.assertHere();

        assertTrue("Back inside a shelf was not handled by the view", onBack());
        assertEquals("Back did not return to the catalogue's own shelves",
                     SHELVES, awaitRowCount(count -> count == SHELVES));
    }

    // --- getting onto the screen ------------------------------------------------

    /**
     * The same door {@code EmulatorActivity.openLibrary} uses. A plain
     * launcher intent would bounce straight back to the machine whenever
     * {@code startsInLibrary} is off, and this test wants the screen
     * regardless of that switch.
     */
    private void launchLibrary() {
        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        Screen.suppressGuides(context);

        // On the display this test can see - see Screen. A bench with a second
        // display hands the launch to whichever one last had focus, and then
        // every tap lands on that display's launcher while the accessibility
        // tree goes on answering about this one.
        context.startActivity(intent, Screen.here());

        assertNotNull("the library screen never came up", device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_options))), FIND));

        // Before anything is read off the screen. A file picker left open by
        // driving the device by hand survives a force-stop of the app - it
        // belongs to another package - and the next launch comes up behind it,
        // after which every reading here is of the wrong screen.
        Screen.assertHere();
    }

    /**
     * Puts a real {@link CatalogueView} over the real activity, on the main
     * thread, with the real ZXInfo catalogue behind it.
     *
     * {@code setContentView} rather than an overlay: two view trees in the
     * accessibility tree at once means a selector can as happily find the
     * library's own rows as the catalogue's, and a test that reads the wrong
     * one is the failure this whole class is written to avoid.
     */
    private void installTheView() {
        Activity activity = resumedLibrary();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view = new CatalogueView(activity,
                    new ZxInfoCatalogue(new Http.Real(activity)),
                    () -> { /* nothing here imports; see ImportFlowTest. */ });
            activity.setContentView(view);
        });

        assertNotNull("the catalogue view was never built", view);
    }

    private Activity resumedLibrary() {
        Activity[] found = { null };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (activity instanceof LibraryActivity) found[0] = activity;
            }
        });

        assertNotNull("LibraryActivity is not the resumed activity", found[0]);
        return found[0];
    }

    // --- reading the list -------------------------------------------------------

    /**
     * The adapter's own row count, once it says something the caller is
     * willing to believe - or the last thing it said when the wait runs out,
     * so the caller's assertion is what reports the failure.
     *
     * The adapter, not the screen: a count built by scrolling and counting
     * titles is off by one or two on repeated runs against the very same list,
     * because a swipe carries whatever momentum it happens to leave with. See
     * {@code FilterTest.rowCount}.
     */
    private int awaitRowCount(IntPredicate settled) {
        long deadline = SystemClock.uptimeMillis() + FIND;
        int count = rowCount();

        while (!settled.test(count) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL);
            count = rowCount();
        }
        return count;
    }

    /** Read from the thread the view belongs to, never from this one. */
    private int rowCount() {
        int[] result = { -1 };
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> result[0] = view.rowCount());
        return result[0];
    }

    private boolean onBack() {
        boolean[] handled = { false };
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> handled[0] = view.onBack());
        return handled[0];
    }
}
