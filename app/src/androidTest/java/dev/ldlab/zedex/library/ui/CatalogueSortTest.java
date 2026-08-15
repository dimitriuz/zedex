package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.Screen;
import dev.ldlab.zedex.library.catalogue.Catalogue;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.screen.LibraryActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The sort control - an entry in {@code CatalogueView}'s options menu, absent
 * when a catalogue has nothing to offer it and, when it is there, a way to
 * ask for Top rated inside whatever shelf is open.
 *
 * <b>Against a fake catalogue, on the main thread, exactly the way {@code
 * CatalogueChaseTest} does it</b> - and for that file's own three reasons: it
 * is deterministic (a live "is Top honoured" answer is a fact about zxart's
 * current vote counts and changes without warning), it makes no traffic
 * (against a host that has been blocked once already for looking like a
 * crawler), and it needs no content-folder grant. {@code
 * ZxInfoCatalogueTest}/{@code ZxartCatalogueTest} are where the real
 * catalogues are proven to build the right URL for a sort; this is where the
 * screen is proven to reach {@link Catalogue.Query#sort()} at all.
 *
 * <b>What this proves and how.</b> Not "the menu entry looks right" read off a
 * screenshot - {@link Catalogue.Query#sort()} the fake was actually handed,
 * which is the one fact a screenshot cannot show: a menu can say "Top rated"
 * while the request underneath it still asks for nothing.
 */
@RunWith(AndroidJUnit4.class)
public class CatalogueSortTest {

    /** Generous rather than tuned - nothing here is a request, so the only
     *  thing this waits on is a layout pass and a fake's own return. */
    private static final long FIND = 30_000;

    private static final long POLL = 100;

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    private Context context;
    private CatalogueView view;
    private Fake catalogue;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        launchLibrary();
    }

    /**
     * One sort declared, and the options menu offers no entry for it at all.
     *
     * The same bargain {@code chooseOptions}'s Format entry already rests on:
     * a control that could only ever leave a shelf exactly as it found it is
     * worse than no control, so {@code CatalogueView} leaves it out of the
     * menu rather than showing one that does nothing. Checked before any
     * shelf is opened - what the menu offers is decided fresh each time it is
     * built, off {@link Catalogue#sorts()} alone at the roots.
     */
    @Test
    public void theSortRowIsAbsentWhenOnlyOneSortIsDeclared() {
        install(new Fake(Collections.singletonList(Catalogue.Sort.DEFAULT)));

        // Something of the fake's has to be on screen first, or an absent
        // entry could just as well mean the view never finished building.
        assertNotNull("the fake's own shelf never appeared",
                      device.wait(Until.findObject(By.text(SHELF)), FIND));

        openOptions();

        assertNull("a catalogue declaring one sort must not offer a menu"
                   + " entry for it - there is nothing a second choice could do",
                   device.findObject(By.textStartsWith(sortLabel())));

        device.pressBack();   // close the menu without choosing anything
    }

    /**
     * Several sorts declared: the menu offers an entry, and it says which one
     * is current before anybody has touched it.
     *
     * "Sort · Default" rather than just "Sort" - the entry has to say what it
     * is set to as well as offer to change it, the same reasoning {@code
     * chooseOptions}'s Format entry rests on.
     */
    @Test
    public void theSortRowNamesTheCurrentSortWhenThereAreSeveral() {
        install(new Fake(Arrays.asList(Catalogue.Sort.DEFAULT, Catalogue.Sort.TOP)));

        openOptions();

        String expected = sortLabel() + " · " + label(Catalogue.Sort.DEFAULT);

        assertNotNull("the options menu never said Sort was at the default:"
                      + " expected \"" + expected + "\"",
                      device.wait(Until.findObject(By.text(expected)), FIND));

        device.pressBack();   // close the menu without choosing anything
    }

    /**
     * Choosing Top rated re-queries the open shelf and the catalogue is asked
     * with {@link Catalogue.Sort#TOP} - the mechanism, proved by recording
     * what {@link Catalogue#open} was actually handed rather than by reading
     * pixels.
     */
    @Test
    public void choosingAsortReQueriesTheShelfWithIt() {
        install(new Fake(Arrays.asList(Catalogue.Sort.DEFAULT, Catalogue.Sort.TOP)));

        openTheShelf();
        assertEquals("the first fetch of an untouched shelf must ask for no"
                     + " sort at all", Catalogue.Sort.DEFAULT, awaitASort());

        chooseTopRated();

        assertEquals("choosing Top rated never reached the catalogue's own"
                     + " Query.sort()", Catalogue.Sort.TOP, awaitSort(Catalogue.Sort.TOP));

        openOptions();
        String expected = sortLabel() + " · " + label(Catalogue.Sort.TOP);
        assertNotNull("the menu did not update to say Top rated",
                      device.wait(Until.findObject(By.text(expected)), FIND));
        device.pressBack();   // close the menu without choosing anything
    }

    /**
     * A shelf that cannot honour the catalogue's sorts drops the menu entry -
     * and the sort goes back to the default rather than being sent.
     *
     * <b>The bug this pins.</b> Honouring a sort is a property of the shelf,
     * not of the catalogue: ZXInfo sends {@code sort=score_desc} only on the
     * two shelves that are really a search, and zxart's {@code date,desc} and
     * {@code title,asc} were measured on prods alone. Declared per catalogue,
     * the control appeared everywhere - refetching a shelf, relabelling the
     * entry and answering with byte-identical rows on three of ZXInfo's five
     * shelves, and on zxart sending an order name to an endpoint nobody had
     * asked it of, against a service that ignores an unrecognised name and
     * answers success.
     *
     * Both halves are asserted, because either alone is still wrong: the
     * entry has to go (a control that does nothing is worse than none), and
     * the request has to carry {@code DEFAULT} (a sort left set would be
     * handed to the shelf that cannot honour it).
     */
    @Test
    public void ashelfThatCannotHonourTheSortHidesTheRowAndIsAskedWithTheDefault() {
        install(new Fake(Arrays.asList(Catalogue.Sort.DEFAULT, Catalogue.Sort.TOP))
                        .onlyOnTheShelf(Collections.singletonList(Catalogue.Sort.DEFAULT)));

        // At the roots the menu offers Sort - no shelf is open, so what the
        // catalogue declares is all there is to go on - and Top rated can be
        // chosen there, which is the state that used to be carried into a shelf
        // that ignores it.
        chooseTopRated();

        openTheShelf();

        assertEquals("a shelf that declares one ordering must be asked with"
                     + " DEFAULT, never with the sort chosen outside it",
                     Catalogue.Sort.DEFAULT, awaitASort());

        openOptions();
        assertNull("the options menu still offers Sort over a shelf that"
                   + " cannot honour any ordering",
                   device.findObject(By.textStartsWith(sortLabel())));
        device.pressBack();   // close the menu without choosing anything
    }

    // --- the fake ------------------------------------------------------------------

    /**
     * Declares whatever sorts it is built with, one shelf, one row, and
     * records the sort every {@link #open} was actually asked for.
     */
    private static final class Fake implements Catalogue {

        private final List<Sort> sorts;

        /** What {@link #sortsFor} answers for the one shelf, or null to leave
         *  the seam's own default alone - which is {@link #sorts()}. Set only
         *  by the test about a shelf that cannot honour what the catalogue
         *  declares. */
        private List<Sort> shelfSorts;

        private final AtomicReference<Sort> lastSort = new AtomicReference<>();
        private final AtomicInteger asked = new AtomicInteger();

        private Fake(List<Sort> sorts) {
            this.sorts = sorts;
        }

        private Fake onlyOnTheShelf(List<Sort> shelfSorts) {
            this.shelfSorts = shelfSorts;
            return this;
        }

        /** Counted off the thread the fetch runs on, read from the test's. */
        Sort lastSort() {
            return lastSort.get();
        }

        int askedCount() {
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

        /**
         * False, on purpose, not by omission.
         *
         * {@link #row} builds every item with an empty version list - no
         * {@code Download}, no format, nothing a format filter could act on -
         * because this class is about the sort control and not about
         * formats. Answering true would claim a shape these rows do not
         * have, exactly the mistake {@code CatalogueChaseTest.Sparse} made by
         * leaving this unstated (see that class's own {@code knowsFormats}
         * javadoc, added in the same fix as this comment). None of the four
         * tests here open a Format entry - it stays out of the options menu
         * for this fake, correctly, and is not what this class exercises.
         */
        @Override
        public boolean knowsFormats() {
            return false;
        }

        @Override
        public List<Shelf> shelves() {
            return Collections.singletonList(new Shelf("shelf", SHELF, Shelf.Accepts.NOTHING));
        }

        @Override
        public List<Sort> sorts() {
            return sorts;
        }

        /** The seam's own default unless a test says otherwise - see {@link
         *  Catalogue#sortsFor}, and {@link #shelfSorts}. */
        @Override
        public List<Sort> sortsFor(Shelf shelf) {
            return shelfSorts == null ? sorts : shelfSorts;
        }

        @Override
        public Page open(Shelf shelf, Query query, int page) {
            asked.incrementAndGet();
            lastSort.set(query.sort());

            // One row, and a total that matches it - so hasMore() is false
            // from the first answer and nothing here ever chases a second
            // page on its own. This test drives the shelf by hand.
            List<Item> items = Collections.singletonList(row());
            return new Page(items, null, 0, items.size());
        }

        private static Item row() {
            return Item.builder("1")
                    .title(TITLE).year("1984").publisher("Nobody").kind("Arcade Game")
                    .availability("Available").versions(Collections.emptyList())
                    .build();
        }

        @Override
        public Item item(String id) {
            return row();
        }

        @Override
        public ScrapeException refusalFor(int status) {
            return new ScrapeException(ScrapeException.Kind.NETWORK, "fake " + status);
        }
    }

    /** What the fake's one shelf is called - nothing a real catalogue says.
     *  Deliberately not starting with "Sort": once this shelf is open its own
     *  label becomes the header, and a shelf named "Sort ..." would answer
     *  {@code textStartsWith(sortLabel())} in place of the row itself, which
     *  is exactly the failure this comment is here because of. */
    private static final String SHELF = "Fake Test Shelf";

    /** ...and its one row, for the same reason. */
    private static final String TITLE = "Fake Entry";

    // --- driving the screen -----------------------------------------------------------

    /** The same door {@code CatalogueChaseTest} uses. */
    private void launchLibrary() {
        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(intent, Screen.here());

        assertNotNull("the library screen never came up", device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_options))), FIND));

        // A picker left open by hand outlives a force-stop of this app, and
        // the next launch comes up behind it - checked before anything is
        // read off the screen.
        Screen.assertHere();
    }

    /** The view, over the real activity, with the fake behind it - {@code
     *  setContentView} rather than an overlay, so a selector cannot find the
     *  library's own rows as happily as this view's. */
    private void install(Fake fake) {
        catalogue = fake;
        Activity activity = resumedLibrary();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view = new CatalogueView(activity, fake,
                    () -> { /* nothing here imports. */ });
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

    private void openTheShelf() {
        UiObject2 shelf = device.wait(Until.findObject(By.text(SHELF)), FIND);
        assertNotNull("the fake catalogue's shelf is not on screen", shelf);
        shelf.click();
    }

    /**
     * The options menu, then its Sort entry, then Top rated in the chooser
     * that opens - the way a person chooses it.
     */
    private void chooseTopRated() {
        openOptions();

        UiObject2 row = device.wait(Until.findObject(By.textStartsWith(sortLabel())), FIND);
        assertNotNull("the options menu has no Sort entry", row);
        row.click();

        String wanted = label(Catalogue.Sort.TOP);
        UiObject2 choice = device.wait(Until.findObject(By.text(wanted)), FIND);
        assertNotNull("the sort chooser does not offer " + wanted, choice);
        choice.click();
    }

    /** The one door to the Sort entry now - {@code CatalogueView}'s own
     *  options button, named for what it opens rather than the library's
     *  generic "Options". */
    private void openOptions() {
        UiObject2 button = device.wait(Until.findObject(
                By.desc(context.getString(R.string.library_sort_filter))), FIND);
        assertNotNull("the catalogue has no options button on screen", button);
        button.click();
    }

    private String sortLabel() {
        return context.getString(R.string.library_sort);
    }

    private String label(Catalogue.Sort sort) {
        switch (sort) {
            case TOP:          return context.getString(R.string.library_sort_top);
            case NEWEST:       return context.getString(R.string.library_sort_newest);
            case ALPHABETICAL: return context.getString(R.string.library_sort_alphabetical);
            default:           return context.getString(R.string.library_sort_default);
        }
    }

    /**
     * Until the fake has been asked at all, then whatever sort it was last
     * asked with - the fake records this off the thread the fetch runs on,
     * and a reading taken before the first request says nothing.
     */
    private Catalogue.Sort awaitASort() {
        long deadline = SystemClock.uptimeMillis() + FIND;

        while (catalogue.askedCount() == 0 && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL);
        }

        return catalogue.lastSort();
    }

    /**
     * Until the fake's last sort is the one wanted, or the deadline runs out -
     * so the caller's own assertion is what reports a failure, never this
     * helper timing out silently. Waits for the condition rather than for a
     * fixed duration, since how long a restart takes to reach the fake is a
     * fact about the thread pool and the device, not a number to guess at.
     */
    private Catalogue.Sort awaitSort(Catalogue.Sort wanted) {
        long deadline = SystemClock.uptimeMillis() + FIND;
        Catalogue.Sort last = catalogue.lastSort();

        while (last != wanted && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL);
            last = catalogue.lastSort();
        }

        return last;
    }
}
