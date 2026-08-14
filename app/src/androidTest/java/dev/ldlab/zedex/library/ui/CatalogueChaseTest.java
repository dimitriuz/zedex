package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.Screen;
import dev.ldlab.zedex.library.catalogue.Catalogue;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.screen.LibraryActivity;

import static org.junit.Assert.assertEquals;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a filtered shelf does while it is too short to be scrolled.
 *
 * <b>The bug this was written for.</b> A format filter keeps what arrives -
 * ZXInfo's search has no such parameter - so a page of thirty may hold one row
 * the filter wants, or none. {@code CatalogueView} chased the next page only
 * <em>while a page added nothing</em>, and stopped the moment one added
 * anything at all; from there the only thing that asks for another page is the
 * scroll listener, and <b>a list too short to scroll never scrolls</b>.
 * Measured against the live service with the filter set to RZX: four rows on
 * screen, page two of ten never asked for, and six more matches sitting in the
 * pages it stopped short of. Swiping did nothing, because there was nothing to
 * swipe.
 *
 * <b>Against a fake catalogue, and no request at all.</b> Two reasons, and the
 * second is the one that matters. It is deterministic - the live database's
 * answer to "how many of the next three hundred entries have an rzx" is a fact
 * about somebody else's collection and changes without warning - and it makes
 * no traffic: this host blocked this app's address once, at the network layer,
 * for behaviour patterns, and a test whose whole point is "keep asking for
 * pages" is precisely the one that must not ask a real service for ten of them
 * per run. {@code CatalogueScreenTest} is where the real catalogue is proven to
 * answer at all, at one request per run.
 *
 * The fake hands back a page of thirty holding exactly {@link #MATCHES} rows
 * this filter wants, for ever. That is sparser than the real thing and is meant
 * to be: it is the shape that fails, and one page of it cannot fill a screen at
 * {@code CatalogueAdapter.ROW_DP} - so a view that stops after the first page
 * carrying anything leaves a list nothing can grow.
 */
@RunWith(AndroidJUnit4.class)
public class CatalogueChaseTest {

    /** Long enough for a screen to come up and for the pages the fill needs -
     *  none of which is a request, so this is generous rather than tuned. */
    private static final long FIND = 30_000;

    /** What the fake answers with, the size a real page is. */
    private static final int PAGE = 30;

    /** How many of those thirty carry the filtered format. Two rather than one
     *  so that the buggy behaviour is a list of two rows, which no device this
     *  app runs on can scroll, and not a list of one - "a single row cannot
     *  scroll" would be true of a screen that drew nothing much too. */
    private static final int MATCHES = 2;

    /** {@code CatalogueView.CHASE}, which is private and has to be. Restated
     *  here because the bound is part of what this class pins: a fill that
     *  keeps going until the list can scroll must still give up on a filter
     *  nothing matches, or it walks a ten-thousand-row shelf a page at a
     *  time. */
    private static final int CHASE = 10;

    /** The fake would rather hand back an empty page than let a broken fill
     *  run away with the bench - and it is far enough past {@link #CHASE} that
     *  reaching it is a failure rather than the end of the shelf. */
    private static final int PAGES_HELD = 200;

    private static final String FILTERED = "rzx";

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    private Context context;
    private CatalogueView view;
    private Sparse catalogue;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        launchLibrary();
    }

    /**
     * A filtered shelf keeps asking until the list is long enough to scroll.
     *
     * <b>The assertion is that the list can be scrolled</b>, read off the
     * accessibility tree rather than counted in rows: how many rows fill a
     * screen is a fact about this device's height and the row's own, and a
     * count written here would be a number that is right on one bench. It is
     * also exactly the property the fill exists to reach - a scrollable list
     * asks for its own next page, and an unscrollable one is where a shelf
     * stops for good. Nothing else on this screen answers to it: the search
     * field is one line, and the pane is not showing.
     */
    @Test
    public void afilteredShelfKeepsFillingUntilTheListCanScroll() {
        install(new Sparse(MATCHES));
        chooseTheFilteredFormat();
        openTheShelf();

        UiObject2 list = device.wait(Until.findObject(By.scrollable(true)), FIND);

        assertNotNull("the shelf stopped filling at " + rowCount() + " rows, having"
                      + " asked for " + catalogue.pagesAsked() + " page(s), before the"
                      + " list was long enough to scroll - and a list that cannot be"
                      + " scrolled never asks for another page, so that is where it"
                      + " stays for good", list);

        assertTrue("a scrollable list came from somewhere other than the shelf:"
                   + " only " + rowCount() + " rows are on it",
                   rowCount() > MATCHES);
    }

    /**
     * ...and a filter nothing matches still gives up.
     *
     * The other half of the same rule, and the reason the fill is bounded at
     * all: a shelf of ten thousand rows holding nothing this filter wants must
     * not be walked a page at a time. Pinned at the bound rather than at "some
     * small number" because the cost of the fill is exactly this many paced
     * requests, against a host that blocks on behaviour patterns.
     */
    @Test
    public void afilterNothingMatchesStopsAtTheBound() {
        install(new Sparse(0));
        chooseTheFilteredFormat();
        openTheShelf();

        assertNotNull("the shelf that matched nothing never said so",
                      device.wait(Until.findObject(
                              By.text(context.getString(R.string.catalogue_empty))), FIND));

        assertEquals("no row should have got past a filter nothing matches",
                     0, rowCount());

        // Settled, rather than sampled the instant the words appeared: a fill
        // that never stopped would go on asking behind them.
        SystemClock.sleep(1_000);

        assertEquals("the fill did not stop at its own bound - a filter nothing"
                     + " matches would walk the whole shelf a page at a time",
                     CHASE, catalogue.pagesAsked());
    }

    // --- the fake ----------------------------------------------------------------

    /**
     * A shelf of thirty rows a page, of which a stated few carry the format
     * this test filters by.
     *
     * Endless on purpose - {@code Page.hasMore} reads a full page and an
     * unknown total as "there is more" - because the thing being pinned is
     * where the <em>view</em> stops, and a shelf that ended would stop it for
     * a reason of its own.
     */
    private static final class Sparse implements Catalogue {

        private final int matchesPerPage;
        private final AtomicInteger asked = new AtomicInteger();

        private Sparse(int matchesPerPage) {
            this.matchesPerPage = matchesPerPage;
        }

        /** Counted off the thread the fetch runs on, read from the test's. */
        int pagesAsked() {
            return asked.get();
        }

        @Override
        public String name() {
            return "Fake";
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public List<Shelf> shelves() {
            return Collections.singletonList(
                    new Shelf("sparse", SHELF, Shelf.Accepts.NOTHING));
        }

        @Override
        public Page open(Shelf shelf, Query query, int page) {
            asked.incrementAndGet();

            if (page >= PAGES_HELD) return new Page(null, null, page * PAGE, Page.UNKNOWN_TOTAL);

            List<Item> items = new ArrayList<>();
            for (int at = 0; at < PAGE; at++) {
                items.add(row(page * PAGE + at, at < matchesPerPage));
            }

            return new Page(items, null, page * PAGE, Page.UNKNOWN_TOTAL);
        }

        /** No picture, so nothing is fetched to draw one - the row is words at
         *  its own minimum height, which is what the fill measures against. */
        private static Item row(int number, boolean filtered) {
            Download file = new Download("https://example.invalid/" + number
                                         + (filtered ? ".rzx" : ".tap"),
                                         filtered ? FILTERED : "tap", 1024);

            return new Item(String.valueOf(number), "Fake Entry " + number, "1984",
                            "Nobody", "Arcade Game", "Available", null,
                            Arrays.asList(new Version(null, "1984",
                                                      Collections.singletonList(file))));
        }

        @Override
        public Item item(String id) {
            return row(0, true);
        }

        @Override
        public ScrapeException refusalFor(int status) {
            return new ScrapeException(ScrapeException.Kind.NETWORK, "fake " + status);
        }
    }

    /** What the fake's one shelf is called. Nothing a real catalogue says, so
     *  a stray row of somebody else's cannot be mistaken for it. */
    private static final String SHELF = "Sparse Shelf";

    // --- driving the screen -------------------------------------------------------

    /**
     * The same door {@code EmulatorActivity.openLibrary} uses - a launcher
     * intent bounces straight back to the machine when {@code startsInLibrary}
     * is off, and this test wants the screen whatever that setting says.
     */
    private void launchLibrary() {
        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(intent, Screen.here());

        assertNotNull("the library screen never came up", device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_options))), FIND));

        // Before anything is read off the screen: a picker left open by driving
        // the device by hand outlives a force-stop of this app, and the next
        // launch comes up behind it.
        Screen.assertHere();
    }

    /**
     * The view, over the real activity, with the fake behind it.
     *
     * {@code setContentView} rather than an overlay, for {@code
     * CatalogueScreenTest}'s reason: two view trees in the accessibility tree
     * at once means a selector can as happily find the library's own rows as
     * this one's.
     */
    private void install(Sparse fake) {
        catalogue = fake;
        Activity activity = resumedLibrary();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view = new CatalogueView(activity, fake,
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

    /**
     * The filter, chosen the way a person chooses it: the row, then the format
     * in the dialog it opens. Set at the roots, where no shelf is open, so the
     * shelf below is asked exactly once with the filter already on - {@code
     * setFormat} restarts an open shelf, and a test that filtered afterwards
     * would be counting two fills.
     */
    private void chooseTheFilteredFormat() {
        UiObject2 row = device.wait(Until.findObject(By.textStartsWith(
                context.getString(R.string.library_filter_format))), FIND);
        assertNotNull("the catalogue has no format row on screen", row);
        row.click();

        String label = FILTERED.toUpperCase(Locale.ROOT);
        UiObject2 choice = device.wait(Until.findObject(By.text(label)), FIND);
        assertNotNull("the format chooser does not offer " + label, choice);
        choice.click();

        assertNotNull("the format row does not say the filter is on",
                      device.wait(Until.findObject(By.textEndsWith(label)), FIND));
    }

    private void openTheShelf() {
        UiObject2 shelf = device.wait(Until.findObject(By.text(SHELF)), FIND);
        assertNotNull("the fake catalogue's shelf is not on screen", shelf);
        shelf.click();
    }

    /** The adapter's own count, read from the thread the view belongs to. A
     *  count built by scrolling and reading titles is off by one or two on
     *  repeated runs against the very same list. */
    private int rowCount() {
        int[] result = { -1 };
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> result[0] = view.rowCount());
        return result[0];
    }
}
