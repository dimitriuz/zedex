package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.Screen;
import dev.ldlab.zedex.library.catalogue.Catalogue;
import dev.ldlab.zedex.library.catalogue.Catalogues;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.screen.LibraryActivity;
import dev.ldlab.zedex.storage.Prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Catalogue entry - the options menu's own way of naming which archive is
 * open, beside Format and Sort, and the first thing in this codebase to ever
 * write {@link Prefs#KEY_CATALOGUE}. {@code Catalogues.preferred} has read
 * that key since the day it was declared, with nothing writing it: this is
 * what makes it real.
 *
 * <b>Two questions, and two ways of answering them.</b> Whether {@link
 * CatalogueView#setCatalogue} swaps the shelves on screen and resets the
 * format and the sort is the mechanism, and is proved the way {@code
 * CatalogueChaseTest}/{@code CatalogueSortTest} prove everything else about
 * this view - a fake catalogue, built on the main thread, no network, no SAF
 * grant. Whether choosing a source actually <em>writes</em> the preference has
 * no such witness: {@code chooseSource()} lists every catalogue this build
 * can browse, which is {@link Catalogues#all}, not whichever fake a test
 * happens to be driving the view with - so that part is proved against the
 * two real catalogues this build ships, ZXInfo and zxart, and by reading
 * {@link Prefs#KEY_CATALOGUE} back out of {@link SharedPreferences} rather
 * than by relaunching the app. Building either real catalogue costs nothing
 * on its own - both {@code configured()} unconditionally and neither touches
 * the network before a shelf is actually opened, which this class never does.
 */
@RunWith(AndroidJUnit4.class)
public class CatalogueSourceTest {

    /** Generous rather than tuned - nothing here but the one real-catalogue
     *  test makes a request, and that test never opens a shelf either. */
    private static final long FIND = 30_000;

    private static final long POLL = 100;

    /** A format {@code Types.openable()} actually offers, and not {@code gz} -
     *  see {@code CatalogueView.chooseFormat}'s own reasoning for why that one
     *  is excluded. Which one is irrelevant here: this class is about the row
     *  resetting to "no filter", never about what filtering does. */
    private static final String FORMAT = "tap";

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    private Context context;
    private CatalogueView view;
    private SharedPreferences preferences;

    /** The bench's own choice, restored in {@link #putItBack()} - it is the
     *  user's device and the setting they left is the one they were using. */
    private String theirs;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);
        theirs = preferences.getString(Prefs.KEY_CATALOGUE, null);

        launchLibrary();
    }

    @After
    public void putItBack() {
        preferences.edit().putString(Prefs.KEY_CATALOGUE, theirs).apply();
    }

    /**
     * "Catalogue · " and whichever fake this view was built with - the same
     * shape the menu's Format/Sort entries already say what they are set to,
     * so this one has to as well.
     */
    @Test
    public void theSourceRowNamesTheCurrentCatalogue() {
        install(new Fake(NAME_A, SHELF_A, false, Collections.singletonList(Catalogue.Sort.DEFAULT)));

        openOptions();

        String expected = sourceLabel() + " · " + NAME_A;
        assertNotNull("the options menu never named " + NAME_A + ": expected \""
                      + expected + "\"", device.wait(Until.findObject(By.text(expected)), FIND));

        device.pressBack();   // close the menu without choosing anything
    }

    /**
     * The mechanism: {@link CatalogueView#setCatalogue} swaps which
     * catalogue's shelves are on screen and resets the format and the sort -
     * proved against two fakes with different {@code name()}s, never against
     * the real catalogues, since this is the part that needs no network and
     * has to stay deterministic.
     */
    @Test
    public void switchingTheCatalogueRedrawsTheRootsAndResetsFormatAndSort() {
        Fake a = new Fake(NAME_A, SHELF_A, true,
                          Arrays.asList(Catalogue.Sort.DEFAULT, Catalogue.Sort.TOP));
        Fake b = new Fake(NAME_B, SHELF_B, false,
                          Collections.singletonList(Catalogue.Sort.DEFAULT));

        install(a);

        // Both controls moved away from their defaults, at the roots - format
        // and sort apply to whatever is on screen whether or not a shelf is
        // open, and neither needs one to update its own menu entry.
        chooseTopRated();
        chooseFormat(FORMAT);

        openOptions();
        String formatChanged = formatLabel() + " · " + FORMAT.toUpperCase(Locale.ROOT);
        assertNotNull("the Format entry never actually changed away from its default",
                      device.wait(Until.findObject(By.text(formatChanged)), FIND));
        device.pressBack();   // close the menu without choosing anything

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view.setCatalogue(b));

        // Catalogue B's own shelf is on screen, and A's is gone - not merely
        // "something changed", but the roots of the catalogue just switched to.
        assertNotNull("switching never opened catalogue B's own root shelf",
                      device.wait(Until.findObject(By.text(SHELF_B)), FIND));
        assertNull("catalogue A's shelf is still on screen after switching to B",
                   device.findObject(By.text(SHELF_A)));

        openOptions();

        assertNotNull("the Catalogue entry did not update to name catalogue B",
                      device.wait(Until.findObject(By.text(sourceLabel() + " · " + NAME_B)), FIND));

        // B knows no formats and declares one sort - both entries must be
        // gone rather than merely reset to their defaults, the same bargain
        // the menu already keeps: a control that can only ever leave a shelf
        // exactly as it found it is worse than no control at all.
        assertNull("the Format entry must be absent for a catalogue that does not know formats",
                   device.findObject(By.textStartsWith(formatLabel())));
        assertNull("the Sort entry must be absent for a catalogue declaring one sort",
                   device.findObject(By.textStartsWith(sortLabel())));

        device.pressBack();   // close the menu without choosing anything
    }

    /**
     * The bug the coordinator found while reviewing this task: {@code
     * CataloguePane} used to hold its own {@code Catalogue}, set once at
     * construction, and {@link CatalogueView#setCatalogue} never told it
     * about a switch - so a pane opened after switching archives would go on
     * asking the <em>old</em> one for the item, the "games like this" shelf
     * and the scraping provider, against an id the new archive issued. Fixed
     * by {@code CataloguePane.setCatalogue}, called from {@code
     * CatalogueView.setCatalogue} before {@link #showRoots()}.
     *
     * <b>Proved by which fake actually answered {@link Catalogue#item}</b> -
     * the one fact a screenshot of the pane cannot show, since both fakes
     * answer with an item of the same title. Switching happens before any
     * shelf is opened, so there is nothing on screen from catalogue A for the
     * pane to have been holding onto except the stale reference itself.
     */
    @Test
    public void switchingSourceThenOpeningAnItemAsksTheNewCatalogue() {
        Fake a = new Fake(NAME_A, SHELF_A, false, Collections.singletonList(Catalogue.Sort.DEFAULT));
        Fake b = new Fake(NAME_B, SHELF_B, false, Collections.singletonList(Catalogue.Sort.DEFAULT));

        install(a);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view.setCatalogue(b));

        openShelf(SHELF_B);
        openTheItem();

        assertTrue("the pane never asked catalogue B for the item, even though B is what "
                   + "is on screen after the switch", awaitItemAsked(b));
        assertFalse("the pane asked catalogue A for the item after switching away from it -"
                    + " it kept a stale Catalogue reference from before the switch",
                    a.itemWasAsked());
    }

    /**
     * The part with no other witness: choosing a source actually writes
     * {@link Prefs#KEY_CATALOGUE}, read back out of {@link SharedPreferences}
     * rather than by relaunching the app - {@code chooseSource()} lists the
     * two real catalogues this build ships, ZXInfo and zxart, whatever fake
     * the view underneath happens to be showing, so this is where the real
     * ones are unavoidable.
     */
    @Test
    public void choosingASourceWritesThePreference() {
        install(new Fake(NAME_A, SHELF_A, false, Collections.singletonList(Catalogue.Sort.DEFAULT)));

        List<Catalogue> real = Catalogues.all(context);
        assertTrue("this build needs at least two catalogues to choose between, and has "
                   + real.size(), real.size() >= 2);

        // zxart, the one this task registers - proving its own name is what
        // is left in the preference is the most direct statement of what this
        // task is for.
        Catalogue want = null;
        for (Catalogue candidate : real) {
            if ("zxart".equals(candidate.name())) want = candidate;
        }
        assertNotNull("this build does not offer a catalogue named zxart", want);

        openOptions();

        UiObject2 row = device.wait(Until.findObject(By.textStartsWith(sourceLabel())), FIND);
        assertNotNull("the options menu has no Catalogue entry", row);
        row.click();

        for (Catalogue candidate : real) {
            assertNotNull("the chooser does not offer " + candidate.name(),
                          device.wait(Until.findObject(By.text(candidate.name())), FIND));
        }

        UiObject2 choice = device.wait(Until.findObject(By.text(want.name())), FIND);
        assertNotNull("the chooser does not offer " + want.name(), choice);
        choice.click();

        assertEquals("choosing a source never wrote Prefs.KEY_CATALOGUE",
                     want.name(), awaitPreference(want.name()));

        openOptions();
        assertNotNull("the Catalogue entry did not update to name " + want.name(),
                      device.wait(Until.findObject(By.text(sourceLabel() + " · " + want.name())), FIND));
        device.pressBack();   // close the menu without choosing anything

        // Three of zxart's own five shelves, in its own words - see
        // ZxartCatalogue.shelves, which has offered Music and Graphics as well
        // since Task 11. These three are the ones this test needs: they are
        // enough to say the switch really put zxart on screen, and asserting on
        // all five would make this a test of that class's shelf list rather
        // than of the source switch.
        assertNotNull("zxart's own Search shelf never appeared",
                      device.wait(Until.findObject(By.text("Search")), FIND));
        assertNotNull("zxart's own Categories shelf never appeared",
                      device.wait(Until.findObject(By.text("Categories")), FIND));
        assertNotNull("zxart's own Everything shelf never appeared",
                      device.wait(Until.findObject(By.text("Everything")), FIND));
    }

    // --- the fake --------------------------------------------------------------------

    /** What each fake's one root shelf is called, and its one item - nothing
     *  a real catalogue says, the same reasoning {@code CatalogueSortTest}
     *  gives for its own constants. */
    private static final String NAME_A = "Fake Source A";
    private static final String NAME_B = "Fake Source B";
    private static final String SHELF_A = "Fake Shelf A";
    private static final String SHELF_B = "Fake Shelf B";
    private static final String TITLE = "Fake Entry";

    /** Declares one shelf, whatever formats/sorts it is built with, and one
     *  row - enough to prove a switch without a request. */
    private static final class Fake implements Catalogue {

        private final String name;
        private final String shelfLabel;
        private final boolean knowsFormats;
        private final List<Sort> sorts;

        /** Set by {@link #item}, off the {@code Work.run} thread the pane
         *  fetches on - read from the test's, which is what proves which
         *  fake's own catalogue the pane actually asked. */
        private final AtomicBoolean itemAsked = new AtomicBoolean();

        private Fake(String name, String shelfLabel, boolean knowsFormats, List<Sort> sorts) {
            this.name = name;
            this.shelfLabel = shelfLabel;
            this.knowsFormats = knowsFormats;
            this.sorts = sorts;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public boolean knowsFormats() {
            return knowsFormats;
        }

        @Override
        public List<Shelf> shelves() {
            return Collections.singletonList(new Shelf("shelf", shelfLabel, Shelf.Accepts.NOTHING));
        }

        @Override
        public List<Sort> sorts() {
            return sorts;
        }

        @Override
        public Page open(Shelf shelf, Query query, int page) {
            List<Item> items = Collections.singletonList(row());
            return new Page(items, null, 0, items.size());
        }

        private Item row() {
            return Item.builder(name + "-1")
                    .title(TITLE).year("1984").publisher("Nobody").kind("Arcade Game")
                    .availability("Available").versions(Collections.emptyList())
                    .build();
        }

        @Override
        public Item item(String id) {
            itemAsked.set(true);
            return row();
        }

        boolean itemWasAsked() {
            return itemAsked.get();
        }

        @Override
        public ScrapeException refusalFor(int status) {
            return new ScrapeException(ScrapeException.Kind.NETWORK, "fake " + status);
        }
    }

    // --- driving the screen -----------------------------------------------------------

    /** The same door {@code CatalogueChaseTest}/{@code CatalogueSortTest} use. */
    private void launchLibrary() {
        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        Screen.suppressGuides(context);
        context.startActivity(intent, Screen.here());

        assertNotNull("the library screen never came up", device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_options))), FIND));

        // A picker left open by hand outlives a force-stop of this app, and
        // the next launch comes up behind it - checked before anything is
        // read off the screen.
        Screen.assertHere();
    }

    /** The view, over the real activity, with the fake behind it - the same
     *  way {@code CatalogueSortTest.install} does it. */
    private void install(Fake fake) {
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

    private void openShelf(String label) {
        UiObject2 shelf = device.wait(Until.findObject(By.text(label)), FIND);
        assertNotNull("the fake catalogue's shelf " + label + " never appeared", shelf);
        shelf.click();
    }

    private void openTheItem() {
        UiObject2 item = device.wait(Until.findObject(By.textStartsWith(TITLE)), FIND);
        assertNotNull("the fake catalogue's own item never appeared on its shelf", item);
        item.click();
    }

    /**
     * Until the fake has been asked at all, or the deadline runs out - the
     * fetch runs off the main thread ({@code Work.run}), so a reading taken
     * immediately after the tap says nothing.
     */
    private boolean awaitItemAsked(Fake fake) {
        long deadline = SystemClock.uptimeMillis() + FIND;

        while (!fake.itemWasAsked() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL);
        }

        return fake.itemWasAsked();
    }

    private void chooseTopRated() {
        openOptions();

        UiObject2 row = device.wait(Until.findObject(By.textStartsWith(sortLabel())), FIND);
        assertNotNull("the options menu has no Sort entry", row);
        row.click();

        String wanted = context.getString(R.string.library_sort_top);
        UiObject2 choice = device.wait(Until.findObject(By.text(wanted)), FIND);
        assertNotNull("the sort chooser does not offer " + wanted, choice);
        choice.click();
    }

    private void chooseFormat(String format) {
        openOptions();

        UiObject2 row = device.wait(Until.findObject(By.textStartsWith(formatLabel())), FIND);
        assertNotNull("the options menu has no Format entry", row);
        row.click();

        String wanted = format.toUpperCase(Locale.ROOT);
        UiObject2 choice = device.wait(Until.findObject(By.text(wanted)), FIND);
        assertNotNull("the format chooser does not offer " + wanted, choice);
        choice.click();
    }

    /** The one door to the Catalogue/Format/Sort entries now - {@code
     *  CatalogueView}'s own options button, named for what it opens rather
     *  than the library's generic "Options". */
    private void openOptions() {
        UiObject2 button = device.wait(Until.findObject(
                By.desc(context.getString(R.string.library_sort_filter))), FIND);
        assertNotNull("the catalogue has no options button on screen", button);
        button.click();
    }

    private String sourceLabel() {
        return context.getString(R.string.library_catalogue);
    }

    private String formatLabel() {
        return context.getString(R.string.library_filter_format);
    }

    private String sortLabel() {
        return context.getString(R.string.library_sort);
    }

    /**
     * Until the preference reads as {@code wanted}, or the deadline runs out -
     * the caller's own assertion is what reports a failure. Polls the store
     * rather than waiting a fixed duration, since how long the write takes to
     * land is a fact about the main thread's queue, not a number to guess at.
     */
    private String awaitPreference(String wanted) {
        long deadline = SystemClock.uptimeMillis() + FIND;
        String last = preferences.getString(Prefs.KEY_CATALOGUE, null);

        while (!wanted.equals(last) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL);
            last = preferences.getString(Prefs.KEY_CATALOGUE, null);
        }

        return last;
    }
}
