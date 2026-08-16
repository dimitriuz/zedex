package dev.ldlab.zedex;

import dev.ldlab.zedex.storage.Prefs;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * The first instrumentation test to drive the library screen at all, rather
 * than the machine {@link Emulator} exists for.
 * {@code LibraryActivity} never touches Fuse, so this does not use {@link
 * Emulator} either: there is no keyboard to find, no boot to wait through,
 * and starting the activity directly is the same door {@code
 * EmulatorActivity.openLibrary} already opens.
 *
 * Covers the half of "one state behind one dialog" a device can actually
 * exercise without a controller: setting a filter from the toolbar's own
 * Options button - {@link dev.ldlab.zedex.library.ui.OptionsDialog}'s own
 * menu, the same dialog a pad's Select opens, now that the toolbar's
 * separate sort, filter and view buttons have been folded into that one
 * door - narrows and flattens Browse, the chips say what is on, an empty result
 * says a filter did it rather than looking like a folder that lost its
 * games, and clearing puts the breadcrumb back. Driving the dialog with an
 * actual pad needs real hardware - {@link
 * dev.ldlab.zedex.library.ui.GamepadCursor#key} refuses anything else - and
 * is left to the unit tests for the state the two ways of opening the
 * dialog share.
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

    /** How often awaitRowCount asks the adapter again. */
    private static final long POLL = 100;

    /**
     * What Browse's own list calls itself to accessibility, in the order worth
     * trying.
     *
     * Scoped rather than a plain {@code scrollable(true)}: the pane sits right
     * beside the list in landscape and has scrollable regions of its own (the
     * description, the gallery), and a generic selector would as happily find
     * one of those.
     *
     * Not one name, though, and not the class's own. A RecyclerView reports
     * itself as whatever its layout manager implies - GridView for a grid,
     * ListView for a linear one - so the answer changes with the view toggle.
     * It also changed with the library: 1.0.0 reported the real class name and
     * 1.3.2 does not, which broke this the moment recyclerview was raised off
     * the 2018 version. The class name is kept last for a bench still on an
     * older copy.
     */
    private static final String[] BROWSE_LIST_CLASSES = {
        "android.widget.GridView",
        "android.widget.ListView",
        "androidx.recyclerview.widget.RecyclerView",
    };

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    private Context context;

    /** Ranked first by {@link Facets#of} - the same list the filter sheet's
     *  own Genre rows are built from. */
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

    /**
     * The folder names to tap, in order, to reach a folder that is not on
     * {@link #nestedGameName}'s own path at all - see {@link
     * #filterFlattensFromRootRatherThanCurrentFolder}, the one test that
     * walks this chain first: flattening from wherever Browse happens to be
     * standing, rather than from the root, could never find {@link
     * #nestedGameName} once this folder - not one of its own ancestors - is
     * what is on screen.
     *
     * Not necessarily a single root-level folder: on the collection this was
     * exercised against, the root holds exactly one folder at all, and that
     * one *is* {@link #nestedGameName}'s own ancestor, so there is nothing to
     * diverge from at the root. The chain instead follows {@link
     * #nestedGameName}'s own path down until a level actually offers a
     * sibling folder to step sideways into - see the walk in {@link #setUp}.
     */
    private final List<String> pathToOtherFolder = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences =
                context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        // Asked for before it is asked about. Metadata answers from memory
        // and never parses on demand, so a caller that has not waited for it
        // reads an empty store - and this test would then skip itself for
        // "no scraped games known" on a device with eight hundred of them,
        // reporting OK in two seconds. Instrumentation runs on its own
        // thread, which is where waiting is allowed.
        Metadata.ensureLoaded(context);

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
        List<String> nestedFolderChain = null;

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

                // Every folder name between the root and the game itself, in
                // order - the game's own filename is the last segment, and is
                // dropped: it is never a folder to tap through.
                List<String> segments = new ArrayList<>(Arrays.asList(withoutLeadingDot.split("/")));
                segments.remove(segments.size() - 1);
                nestedFolderChain = segments;
            }
        }

        assumeTrue("no " + commonestGenre + " game lives in a subfolder - "
                   + "nothing to prove flattening with", nested != null);
        nestedGameName = nested;

        // Follows nestedFolderChain down from the root, one real Listing.folder
        // query per level - the same call load() itself makes - until a level
        // offers a folder that is not the one on nestedGameName's own path.
        // That folder is where the chain stops: everything walked through to
        // reach it goes in pathToOtherFolder too, since the test has to tap
        // through each of those in turn to get there.
        Uri currentUri = Listing.root(Uri.parse(tree));
        boolean diverged = false;

        for (String expected : nestedFolderChain) {
            List<Entry> children = Listing.folder(context.getContentResolver(), currentUri);

            Entry onPath = null;
            String sibling = null;
            for (Entry candidate : children) {
                if (candidate.kind != Entry.Kind.FOLDER) continue;
                if (candidate.name.equals(expected)) {
                    onPath = candidate;
                } else if (sibling == null) {
                    sibling = candidate.name;
                }
            }

            if (sibling != null) {
                pathToOtherFolder.add(sibling);
                diverged = true;
                break;
            }

            if (onPath == null) break; // the chain came from a real file - should not happen
            pathToOtherFolder.add(expected);
            currentUri = onPath.uri;
        }

        assumeTrue("every folder between the root and \"" + nestedGameName
                   + "\" has nothing else in it - nowhere to walk into instead",
                   diverged);

        // Facets.of ranks every genre the store has ever seen, real file or
        // not - see LibraryActivity.openFilterSheet's own comment on where
        // "values" comes from. The first one absent from realGenres matches
        // nothing this device can actually browse, at any rating.
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

        int filteredCount = awaitRowCount(count -> count != rootCount);
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

        int clearedCount = awaitRowCount(count -> count == rootCount);
        assertEquals("clearing the filter did not restore the row count",
                     rootCount, clearedCount);
        assertNull("the clear chip is still showing after being cleared",
                   device.wait(Until.findObject(By.desc(
                           context.getString(R.string.library_filter_clear))), GLANCE));
        assertNotNull("the breadcrumb did not come back once the filter was cleared",
                      device.wait(Until.findObject(By.text("/")), FIND));
    }

    /**
     * {@code LibraryActivity.load} flattens from the root down, not from
     * whichever folder happens to be on screen - the same root {@code
     * everythingForFacets} already walks to build the sheet's own value
     * lists and counts. Walking {@link #pathToOtherFolder} first is what
     * tells the two apart: it ends on a folder that is not an ancestor of
     * {@link #nestedGameName} at all, so a flatten scoped to the folder on
     * screen can never find it, while one scoped to the root always does.
     *
     * Also covers the Up chevron: it has somewhere to go while standing in
     * a folder unfiltered, and nowhere sensible once the breadcrumb is
     * replaced by the filter's own chips, since a flat list is not a
     * folder and nothing else on screen would say where Up had taken you.
     */
    @Test
    public void filterFlattensFromRootRatherThanCurrentFolder() {
        enterFolder(pathToOtherFolder);

        assertNotNull("the Up chevron did not appear after walking into "
                      + pathToOtherFolder,
                      device.wait(Until.findObject(
                              By.desc(context.getString(R.string.library_up))), FIND));

        int inFolder = rowCount();

        openFilterSheet();
        selectGenre(commonestGenre);
        dismissFilterSheet();

        // The same wait the other test needs, for the same reason: closing the
        // sheet starts the walk, it does not finish it. Scrolling a list that
        // is still the folder's own finds no game from anywhere else, which is
        // precisely what this test would then report as the bug it looks for.
        awaitRowCount(count -> count != inFolder);

        assertTrue("filtering from inside " + pathToOtherFolder + " did not reach \""
                   + nestedGameName + "\" - the walk is scoped to whichever folder is "
                   + "on screen rather than to the root",
                   scrollToText(nestedGameName));

        assertNull("the Up chevron is still showing while a filter has flattened the list",
                   device.wait(Until.findObject(
                           By.desc(context.getString(R.string.library_up))), GLANCE));

        tapClearChip();

        assertNotNull("the Up chevron did not come back once the filter was cleared",
                      device.wait(Until.findObject(
                              By.desc(context.getString(R.string.library_up))), FIND));
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
        // CLEAR_TOP, not REORDER_TO_FRONT: the activity keeps which folder
        // Browse is standing in, and the filter, in its own fields, so
        // reusing the instance means starting wherever the last test left it.
        // The first test here walks into a folder; the second then began
        // inside it and failed looking for a game that had been filtered out
        // of that subtree - but only in a suite, where a prior class had not
        // already destroyed the instance. This activity is launchMode
        // standard, so CLEAR_TOP recreates it, and a new instance is at the
        // root with nothing filtered.
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        Screen.suppressGuides(context);

        // On the display this test can see - see Screen. Run on its own the
        // library came up here anyway; run after a class that had left another
        // display focused it did not, and then every tap below landed on that
        // display's launcher while the accessibility tree went on answering
        // about this one. The filter changed no rows, and said so as though
        // filtering were broken.
        context.startActivity(intent, Screen.here());

        // The Options button is up in every tab, not only Browse's own - see
        // OptionsDialog.Callbacks.filteringAllowed - but Browse is where this
        // activity always opens regardless, so waiting for it is still
        // waiting for the screen itself, not any one row that might not have
        // loaded yet.
        assertNotNull("the library screen never came up", device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_options))), FIND));

        Screen.assertHere();
    }

    /**
     * Walks Browse down through a chain of folders, tapping each by its own
     * name in turn - a folder row shows exactly {@code entry.name}, scraped
     * or not, since {@code EntryAdapter} only ever replaces a file's title,
     * never a folder's; see that class's own {@code onBindViewHolder}.
     */
    private void enterFolder(List<String> names) {
        for (String name : names) tapExactText(name);
    }

    // --- the filter sheet ------------------------------------------------------

    /**
     * Taps the toolbar's own Options button, then MENU's own Filter row -
     * the one door into the filter sheet now that the toolbar's own
     * separate Filter button is gone; see {@code LibraryActivity.buildToolbar}
     * and {@code OptionsDialog.buildMenuPage}.
     */
    private void openFilterSheet() {
        UiObject2 button = device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_options))), FIND);
        assertNotNull("the Options button never appeared", button);
        button.click();
        SystemClock.sleep(SETTLE);

        // MENU's own Filter row reads "Filter · No filter" or "Filter · N
        // fields" - see OptionsDialog.filterSummary - so a pattern rather
        // than an exact match is what finds it regardless of what is
        // currently set.
        Pattern filterRow = Pattern.compile(
                Pattern.quote(context.getString(R.string.library_filter)) + " · .*");
        UiObject2 row = device.wait(Until.findObject(By.text(filterRow)), FIND);
        assertNotNull("MENU's own Filter row never appeared", row);
        row.click();
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
        tapExactText(Filters.ratingLabel(stars));
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
    /**
     * The adapter's row count, once it says something the caller is willing
     * to believe - or the last thing it said, when the wait runs out, so the
     * caller's own assertion is what reports the failure.
     *
     * A filter does not land when the sheet closes. It runs
     * {@code LibraryActivity.load}, whose walk of the whole content tree is
     * a recursive query per folder through the documents provider, off the
     * UI thread; only when that comes back does the adapter change. Sampling
     * the count after a fixed sleep asked how long that takes, and the answer
     * depends on the tree, on the provider's caches, and on what else the
     * device has been doing: this class passed on its own and failed in a
     * suite behind three emulator classes, reading the pre-filter count and
     * reporting it as a filter that changed nothing.
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
        for (String className : BROWSE_LIST_CLASSES) {
            UiScrollable list = new UiScrollable(new UiSelector().className(className));
            if (!list.exists()) continue;

            // From the top, and with room to reach the bottom. The default is
            // thirty swipes from wherever the list happens to be sitting,
            // which is an arbitrary bound on a list whose whole point is that
            // filtering makes it longer: this is the flattened collection, and
            // in grid mode a swipe covers a third of the rows a list-mode
            // swipe does. A test that finds the row or not depending on how
            // far down somebody left the list is not testing filtering.
            list.setMaxSearchSwipes(80);

            try {
                list.scrollToBeginning(20);
                if (list.scrollTextIntoView(text)) return true;
            } catch (UiObjectNotFoundException e) {
                // This one is not the list after all; try the next name.
            }
        }
        return false;
    }
}
