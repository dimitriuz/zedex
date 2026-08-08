package dev.ldlab.zedex;

import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.Facets;
import dev.ldlab.zedex.library.Filters;
import dev.ldlab.zedex.library.Listing;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.screen.LibraryActivity;
import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiObjectNotFoundException;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The first instrumentation test to drive the library screen at all, rather
 * than the machine {@link Emulator} exists for - see Task 7's own brief.
 * {@code LibraryActivity} never touches Fuse, so this does not use {@link
 * Emulator} either: there is no keyboard to find, no boot to wait through,
 * and starting the activity directly is the same door {@code
 * EmulatorActivity.openLibrary} already opens.
 *
 * Covers the half of "one state behind both doors" a device can actually
 * exercise without a controller: setting a filter from the toolbar's own
 * sheet narrows and flattens Browse, the chips say what is on, an empty
 * result says a filter did it rather than looking like a folder that lost
 * its games, and clearing puts the breadcrumb back. The gamepad half needs a
 * real pad - {@link dev.ldlab.zedex.library.ui.GamepadCursor#key} refuses
 * anything else - and is left to the unit tests for the state the two doors
 * share.
 *
 * Nothing here is hard-coded to one collection's own titles. The commonest
 * genre, a genre with no real match at all to prove emptiness with, and a
 * game of the commonest genre that lives inside a folder to prove
 * flattening with are all worked out from {@link Metadata} and {@link
 * Listing} themselves - the same two things the app's own filter sheet and
 * Browse's own flattened walk ask - so this stays correct whatever is
 * actually linked on the device it runs against. {@link Metadata#count} is
 * asked first so a device with nothing scraped skips rather than fails.
 */
@RunWith(AndroidJUnit4.class)
public class FilterTest {

    /** Long enough for a dialog, a page inside it, or a reload to settle. */
    private static final long FIND = 10_000;

    /** For a check that something is *not* there - no point waiting the
     *  whole of {@link #FIND} to be told so. */
    private static final long GLANCE = 1_000;

    private static final long SETTLE = 400;

    /** Scoped rather than a plain {@code scrollable(true)}: the pane sits
     *  right beside Browse's own list in landscape and has scrollable
     *  regions of its own (the description, the gallery), and a generic
     *  selector would as happily find one of those as the list this test
     *  actually means. */
    private static final String RECYCLER_CLASS = "androidx.recyclerview.widget.RecyclerView";

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    private Context context;

    /** Ranked first by {@link Facets#of} - the same list the toolbar's own
     *  Filter sheet builds its Genre rows from. */
    private String commonestGenre;

    /** A real file tagged with {@link #commonestGenre} that lives inside a
     *  folder under the root - proof that choosing the genre flattens
     *  Browse rather than merely narrowing whichever folder is open, which
     *  is the one thing a folder-scoped filter would get wrong. */
    private String nestedGameName;

    /** A genre the sheet offers that matches no real, currently browsable
     *  file at all - picking it can only ever show nothing, whatever else
     *  is asked for alongside it. */
    private String emptyGenre;

    @Before
    public void setUp() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences =
                context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);

        assumeTrue("no scraped games known - nothing to filter by",
                   Metadata.count(context) > 0);

        Map<Filters.Field, List<Facets.Value>> facetValues = Facets.of(Metadata.all(context));
        List<Facets.Value> genres = facetValues.get(Filters.Field.GENRE);
        assumeTrue("no genre scraped - nothing to filter by",
                   genres != null && !genres.isEmpty());
        commonestGenre = genres.get(0).name;

        String tree = preferences.getString(Storage.KEY_CONTENT_TREE, null);
        assumeTrue("no content folder granted", tree != null);

        // The same walk applyFilterSort itself does while filtering() - see
        // LibraryActivity.load - so what this finds is exactly what the
        // screen would show, not a guess at it.
        List<Entry> allFiles = Listing.everythingUnder(
                context.getContentResolver(), Listing.root(Uri.parse(tree)));

        // EntryAdapter shows the scraped name over the filename once it
        // resolves, and this row - buried inside a folder - is exactly the
        // shape of game likely to have one: searching for the filename
        // after that resolve has already happened would never find it. Not
        // LibraryActivity.KEY_LIBRARY_NAMES, which is private to that
        // class - the same key, mirrored, since this default is the one
        // EntryAdapter.setShowScrapedNames is actually given on a fresh
        // onResume.
        boolean showScrapedNames = preferences.getBoolean("libraryNames", true);

        Set<String> realGenres = new LinkedHashSet<>();
        String nested = null;

        for (Entry entry : allFiles) {
            String relativePath = Metadata.relativePath(context, entry.uri);
            if (relativePath == null) continue;

            Meta meta = Metadata.forPath(context, relativePath);
            if (meta == null) continue;

            List<String> ownGenres = Filters.genresOf(meta.genre);
            realGenres.addAll(ownGenres);

            // A path with a slash past its own leading "./" lives inside a
            // folder Browse would otherwise have to be walked into to see -
            // exactly what flattening is for.
            String withoutLeadingDot =
                    relativePath.startsWith("./") ? relativePath.substring(2) : relativePath;

            if (nested == null && ownGenres.contains(commonestGenre)
                    && withoutLeadingDot.contains("/")) {
                nested = showScrapedNames && meta.name != null && !meta.name.isEmpty()
                        ? meta.name : entry.name;
            }
        }

        assumeTrue("no " + commonestGenre + " game lives in a subfolder - "
                   + "nothing to prove flattening with", nested != null);
        nestedGameName = nested;

        // Facets.of ranks every genre the store has ever seen, real file or
        // not - see openFilterSheet's own comment on where "values" comes
        // from. The first one absent from realGenres matches nothing this
        // device can actually browse, at any rating.
        String candidate = null;
        for (Facets.Value value : genres) {
            if (!realGenres.contains(value.name)) {
                candidate = value.name;
                break;
            }
        }
        assumeTrue("every scraped genre matches a real, existing file - "
                   + "nothing to prove an empty result with", candidate != null);
        emptyGenre = candidate;

        launchLibrary();
    }

    @After
    public void tearDown() {
        // Best effort, and never worth failing a finished test over: leaves
        // the session-only Filters empty for whatever runs in this process
        // next, the same clearAll onFiltersChanged already exercises
        // mid-test.
        UiObject2 clear = device.wait(Until.findObject(
                By.desc(context.getString(R.string.library_filter_clear))), GLANCE);
        if (clear != null) clear.click();
    }

    @Test
    public void filteringNarrowsFlattensAndTellsEmptyFromClear() {
        int rootCount = rowCount();

        // 1. A filter narrows and flattens.
        openFilterSheet();
        selectGenre(commonestGenre);
        dismissFilterSheet();

        int filteredCount = rowCount();
        assertNotEquals("choosing " + commonestGenre
                         + " did not change how many rows Browse shows",
                         rootCount, filteredCount);
        assertTrue("flattening did not bring \"" + nestedGameName
                   + "\" up to the top level",
                   scrollToText(nestedGameName));

        // 2. The chips say what is on.
        assertNotNull("no view describes the active filter as " + commonestGenre,
                       device.wait(Until.findObject(By.descContains(commonestGenre)), FIND));

        // Cleared before the empty scenario: Genre's own values OR together,
        // so leaving commonestGenre chosen would only add matches to
        // emptyGenre's own rather than narrow to none.
        tapClearChip();

        // 3. An empty result says so.
        openFilterSheet();
        selectGenre(emptyGenre);
        tapExactText("‹ " + context.getString(R.string.library_filter_genre));
        selectRating(4.5f);
        dismissFilterSheet();

        String filteredMessage = context.getString(R.string.library_empty_filtered);
        String genericMessage = context.getString(R.string.library_empty);

        assertNotNull("the empty state does not read \"" + filteredMessage + "\"",
                      device.wait(Until.findObject(By.text(filteredMessage)), FIND));
        assertNull("the empty state fell back to \"" + genericMessage
                   + "\" rather than the filtered message",
                   device.wait(Until.findObject(By.text(genericMessage)), GLANCE));

        // 4. Clearing puts it back.
        tapClearChip();

        int clearedCount = rowCount();
        assertEquals("clearing the filter did not restore the row count",
                     rootCount, clearedCount);
        assertNull("the clear chip is still showing after being cleared",
                   device.wait(Until.findObject(By.desc(
                           context.getString(R.string.library_filter_clear))), GLANCE));
        assertNotNull("the breadcrumb did not come back once the filter was cleared",
                      device.wait(Until.findObject(By.text("/")), FIND));
    }

    // --- getting onto the screen ---------------------------------------------

    /**
     * The same door {@code EmulatorActivity.openLibrary} already uses - see
     * {@code LibraryActivity.EXTRA_FROM_MENU}'s own comment for why a plain
     * launcher intent is not this: it would bounce straight back out with no
     * content folder to browse whenever {@code startsInLibrary} is off, and
     * this test wants the screen regardless of that switch.
     */
    private void launchLibrary() {
        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        // The Filter button is Browse's own, and Browse is where this
        // activity always opens - waiting for it is waiting for the screen
        // itself, not any one row that might not have loaded yet.
        assertNotNull("the library screen never came up", device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_filter))), FIND));
    }

    // --- the filter sheet ------------------------------------------------------

    private void openFilterSheet() {
        UiObject2 button = device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_filter))), FIND);
        assertNotNull("the Filter button never appeared", button);
        button.click();
        SystemClock.sleep(SETTLE);
    }

    /**
     * The dialog's own back key, not one of its rows - {@link
     * dev.ldlab.zedex.library.ui.GamepadCursor#key} only answers a real
     * pad's events, so this ordinary key reaches the plain {@code Dialog}
     * underneath and dismisses it outright, whichever of its pages is
     * showing.
     */
    private void dismissFilterSheet() {
        device.pressBack();
        SystemClock.sleep(SETTLE);
    }

    private void tapClearChip() {
        UiObject2 clear = device.wait(Until.findObject(
                By.desc(context.getString(R.string.library_filter_clear))), FIND);
        assertNotNull("the chip's own clear control never appeared", clear);
        clear.click();
        SystemClock.sleep(SETTLE);
    }

    /**
     * Opens Genre's own values and taps the row named exactly {@code name} -
     * never a compound genre that merely contains it, which a plain
     * contains-match would risk once "Platform" sits right next to
     * "Platform / Run &amp; Jump" in the very same list.
     */
    private void selectGenre(String name) {
        tapExactText(context.getString(R.string.library_filter_genre));
        narrowAndTapValue(name);
    }

    private void selectRating(float stars) {
        tapExactText(context.getString(R.string.library_filter_rating));
        tapExactText(ratingLabel(stars));
    }

    /** "3+" or "4.5+" - the same shape {@code OptionsDialog.ratingLabel}
     *  draws its own rows with; duplicated rather than shared since that
     *  method is private to a dialog with no reason to widen itself for a
     *  test outside it. */
    private static String ratingLabel(float stars) {
        String number = stars == Math.rint(stars)
                ? String.valueOf((int) stars) : String.valueOf(stars);
        return number + "+";
    }

    private void tapExactText(String text) {
        UiObject2 target = device.wait(Until.findObject(By.text(text)), FIND);
        assertNotNull("nothing reads exactly \"" + text + "\"", target);
        target.click();
        SystemClock.sleep(SETTLE);
    }

    /**
     * Types into the value list's own search box - present past {@code
     * OptionsDialog.SEARCH_THRESHOLD}, which any collection with more than a
     * handful of genres clears - so the one row left visible is the one
     * this taps. Without it, searching for "Platform" by a plain
     * contains-match could land on any compound genre that merely contains
     * the word, not the exact one this test means.
     */
    private void narrowAndTapValue(String name) {
        UiObject2 search = device.wait(
                Until.findObject(By.clazz(android.widget.EditText.class)), FIND);
        if (search != null) {
            search.setText(name);
            SystemClock.sleep(SETTLE);
        }

        // The row's own text is "name  count", or "✓ name  count" once
        // picked - two literal spaces before the count, which is what tells
        // "Platform" apart from "Platform / Run & Jump  10" sitting right
        // beside it: that row's text does not end at two spaces and a
        // number the moment "Platform" does.
        Pattern exact = Pattern.compile("(✓ )?" + Pattern.quote(name) + "  \\d+");
        UiObject2 row = device.wait(Until.findObject(By.text(exact)), FIND);
        assertNotNull("no row named exactly \"" + name + "\"", row);
        row.click();
        SystemClock.sleep(SETTLE);
    }

    // --- reading the list --------------------------------------------------

    /**
     * How many rows Browse is showing - the adapter's own {@code
     * getItemCount()}, read off the resumed {@code LibraryActivity} rather
     * than reconstructed by scrolling.
     *
     * A count built by scrolling to the end and counting distinct row
     * titles was tried first and measured wrong by one or two on repeated
     * runs against the very same list: {@code UiScrollable}'s forward swipe
     * carries whatever momentum the gesture happens to leave it with, so
     * consecutive screens do not always overlap by the same amount, and a
     * row can be caught twice or not at all depending on exactly where a
     * swipe settles. Rows named the same way twice in one collection would
     * undercount the same way even with a perfect scroll. The RecyclerView
     * itself has no such uncertainty - {@code adapter} is private, so this
     * reaches it the one way available from outside {@code
     * dev.ldlab.zedex.screen}, the same as {@link Emulator} already reaches
     * around a screen accessibility cannot answer for and asks {@code
     * FuseNative} directly instead.
     */
    private int rowCount() {
        // Both the activity registry and the adapter it hands back belong to
        // the main thread - ActivityLifecycleMonitorImpl refuses to answer
        // from any other one, and a RecyclerView.Adapter is exactly the kind
        // of view-owned object CLAUDE.md's own "Fuse's core is single
        // threaded" reasoning generalises to: read it from where it lives.
        int[] result = { -1 };
        AssertionError[] failure = { null };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                LibraryActivity activity = currentLibraryActivity();
                java.lang.reflect.Field field = LibraryActivity.class.getDeclaredField("adapter");
                field.setAccessible(true);
                RecyclerView.Adapter<?> adapter = (RecyclerView.Adapter<?>) field.get(activity);
                result[0] = adapter.getItemCount();
            } catch (ReflectiveOperationException e) {
                failure[0] = new AssertionError("cannot read the adapter's own row count", e);
            } catch (AssertionError e) {
                failure[0] = e;
            }
        });

        if (failure[0] != null) throw failure[0];
        return result[0];
    }

    private LibraryActivity currentLibraryActivity() {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)) {
            if (activity instanceof LibraryActivity) return (LibraryActivity) activity;
        }
        throw new AssertionError("LibraryActivity is not the resumed activity");
    }

    private boolean scrollToText(String text) {
        try {
            UiScrollable list = new UiScrollable(new UiSelector().className(RECYCLER_CLASS));
            return list.scrollTextIntoView(text);
        } catch (UiObjectNotFoundException e) {
            return false;
        }
    }
}
