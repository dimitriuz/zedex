package dev.ldlab.zedex;

import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.Listing;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.screen.LibraryActivity;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
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
import java.util.List;

/**
 * The pane's own three-second wait to the video, which nothing else covers.
 *
 * {@link FilterTest} drives the same screen and touches the pane, but only as
 * far as a selection and the facts line. The wait is the one piece of {@link
 * dev.ldlab.zedex.library.ui.DetailPane} with a timer in it, the one that has
 * already broken once - see {@code fix: the pane's video played nothing
 * because a jump is not a scroll} - and the one a refactor is most likely to
 * break silently, because a wait that never fires looks exactly like a game
 * with nothing scraped.
 *
 * Two halves, the same mechanism from both sides:
 *
 *  1. left alone, the pager arrives at the video by itself;
 *  2. swiped inside the wait, it stays on the page the swipe chose - {@code
 *     userSwiped} exists so a timer that wins the race never overrides a page
 *     somebody picked for themselves.
 *
 * What this cannot see is whether the video is <em>playing</em>: a {@code
 * VideoView}'s picture is not in the accessibility tree, and proving motion
 * needs two screenshots and a pixel comparison rather than an assertion.
 * Reaching the page is what this covers; that the frame is not black is
 * checked by hand on a device - two screenshots four seconds apart, sampled
 * inside the {@code VideoView}'s own bounds.
 *
 * Nothing here is hard-coded to one collection: the game is whichever
 * root-level row {@link Artwork#video} actually answers for, and the class
 * skips on that fact rather than on anything timed - see {@code NewDiskTest},
 * which once decided the ROMs were missing from what the screen said three
 * seconds after a machine change and reported OK having done nothing.
 */
@RunWith(AndroidJUnit4.class)
public class DetailPaneTest {

    /** Long enough for the screen, a selection, or the pane's own async
     *  answers to land. Comfortably past the three-second wait itself. */
    private static final long FIND = 10_000;

    /** How long the suppressed case is given to fail before it is believed.
     *  Deliberately well past the wait it has to outlast. */
    private static final long OUTLAST_THE_WAIT = 6_000;

    private static final long POLL = 100;

    /** For the snap, and only for the snap - see {@link #swipeToNextPage}. */
    private static final long SNAP = 500;

    /**
     * What Browse's own list calls itself to accessibility, in the order worth
     * trying - the same three {@link FilterTest} needs, for the same reasons:
     * a RecyclerView reports whatever its layout manager implies, so the
     * answer changes with the view toggle, and the class name is kept last for
     * a bench on an older recyclerview.
     */
    private static final String[] BROWSE_LIST_CLASSES = {
        "android.widget.GridView",
        "android.widget.ListView",
        "androidx.recyclerview.widget.RecyclerView",
    };

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    private Context context;
    private SharedPreferences preferences;

    /** The row to select: a root-level game with a scraped video, by the name
     *  its row actually shows. */
    private String gameWithVideo;

    /**
     * Another root-level game, with no video of its own, to select in
     * between.
     *
     * Two reasons it cannot simply be the same row again. Reselecting the row
     * already selected asks {@code DetailPane.refreshFacts}, which
     * deliberately does not restart the wait - so the test would be waiting
     * for a timer nobody scheduled. And it must have no video itself, or a
     * timer of its own could carry the gallery to a video page while this test
     * is looking for the next selection's.
     */
    private String otherGame;

    /** What the bench had these set to, put back in {@link #restoreBench} -
     *  it is the user's device, and the setting they left on is the one they
     *  were using. */
    private boolean hadSecondScreen;
    private boolean hadAutoplay;

    @Before
    public void setUp() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        // Before it is asked about, and on this thread, which is allowed to
        // wait - see FilterTest's own note. An unloaded store answers empty
        // and this class would skip itself on a device with eight hundred
        // scraped games.
        Metadata.ensureLoaded(context);

        String tree = preferences.getString(Storage.KEY_CONTENT_TREE, null);
        assumeTrue("no content folder granted", tree != null);

        setTheWorldThisNeeds();
        findTwoGames(tree);

        // A fact about the collection, not a wait: either a root-level game
        // has a scraped video or none does, and no amount of settling changes
        // the answer.
        assumeTrue("no root-level game has a scraped video - nothing to advance to",
                   gameWithVideo != null);
        assumeTrue("no second root-level game without a video - nothing to select "
                   + "in between", otherGame != null);

        launchLibrary();
    }

    @After
    public void restoreBench() {
        if (preferences == null) return;

        preferences.edit()
                .putBoolean(Prefs.KEY_SECOND_SCREEN, hadSecondScreen)
                .putBoolean("libraryVideoAutoplay", hadAutoplay)
                .apply();
    }

    /**
     * The two settings these tests depend on, said rather than inherited.
     *
     * Autoplay off and there is no wait to test at all - the pane does not
     * even schedule one. Second screen on and the pane is hidden outright in
     * favour of a {@code Presentation} on another display, which UI Automator
     * taps nothing on: the symptom would be a gallery that never appears, and
     * nothing on screen would say why. A bench left configured for the last
     * thing worked on by hand is the normal case, not the exception.
     *
     * Written before the activity is launched, since both are read in {@code
     * onResume}.
     */
    private void setTheWorldThisNeeds() {
        hadSecondScreen = preferences.getBoolean(Prefs.KEY_SECOND_SCREEN, false);

        // The same key LibraryActivity reads, mirrored because it is private
        // to that class - the same reason FilterTest mirrors libraryNames.
        hadAutoplay = preferences.getBoolean("libraryVideoAutoplay", true);

        preferences.edit()
                .putBoolean(Prefs.KEY_SECOND_SCREEN, false)
                .putBoolean("libraryVideoAutoplay", true)
                .apply();
    }

    /**
     * Root level only. A nested game would have to be walked into first, and
     * every folder tapped through is another thing that can go wrong in a test
     * about a three-second timer.
     */
    private void findTwoGames(String tree) throws IOException {
        List<Entry> rows = Listing.folder(
                context.getContentResolver(), Listing.root(Uri.parse(tree)));

        // The same default EntryAdapter.setShowScrapedNames is given on a
        // fresh onResume, mirrored here because the key is private to
        // LibraryActivity: a row whose name has been replaced by a scraped one
        // cannot be found by its filename.
        boolean showScrapedNames = preferences.getBoolean("libraryNames", true);

        for (Entry row : rows) {
            if (row.isContainer()) continue;

            String relativePath = Metadata.relativePath(context, row.uri);
            if (relativePath == null) continue;

            String shown = row.name;
            if (showScrapedNames) {
                Meta meta = Metadata.forPath(context, relativePath);
                if (meta != null && meta.name != null && !meta.name.isEmpty()) shown = meta.name;
            }

            boolean hasVideo = Artwork.video(context, relativePath) != null;

            if (hasVideo) {
                if (gameWithVideo == null) gameWithVideo = shown;
            } else if (otherGame == null) {
                otherGame = shown;
            }

            if (gameWithVideo != null && otherGame != null) return;
        }
    }

    /**
     * Left alone, the pane's own wait carries the gallery to the video.
     *
     * The game is selected twice, with another row in between, and only the
     * second selection is measured. The wait fires three seconds after a
     * selection whatever the gallery has managed to resolve by then and does
     * not try again, so a first, cold walk of the documents provider that
     * takes longer than that would look like a broken timer when what actually
     * happened is that the pictures were not there yet. The second selection
     * runs the same timer against a gallery already resolved once, which is
     * the case this is about.
     */
    @Test
    public void theWaitCarriesTheGalleryToTheVideo() {
        select(gameWithVideo);
        assertNotNull("the pane never showed a gallery for \"" + gameWithVideo + "\"",
                      awaitPage());

        selectTheOtherGame();
        select(gameWithVideo);

        assertTrue("three seconds after selecting \"" + gameWithVideo
                   + "\" the pane is still not on the video page",
                   awaitVideoPage(FIND));
    }

    /**
     * Swiped inside the wait, the pane stays on the page the swipe chose.
     *
     * The same three-second timer is running throughout, and the whole
     * assertion is that it declines to act. The swipe is checked to have
     * actually moved a page first: a swipe that moved nothing would leave the
     * gallery on page one and this test would then pass on the wrong thing
     * entirely.
     */
    @Test
    public void aSwipeInsideTheWaitIsNotOverridden() {
        // Warm, for the reason the other test gives - and this one needs it
        // twice over, since it has to have real pages to swipe between well
        // inside the three seconds.
        select(gameWithVideo);
        assertNotNull("the pane never showed a gallery for \"" + gameWithVideo + "\"",
                      awaitPage());

        selectTheOtherGame();
        select(gameWithVideo);

        UiObject2 page = awaitPage();
        assertNotNull("the pane never showed a gallery on reselecting \""
                      + gameWithVideo + "\"", page);
        String before = pageDescription(page);

        swipeToNextPage(page);

        UiObject2 after = awaitPage();
        assertNotNull("the gallery disappeared after being swiped", after);
        String chosen = pageDescription(after);
        assertFalse("the swipe did not move the gallery off \"" + before
                    + "\", so this proves nothing about the wait",
                    chosen.equals(before));

        // A duration, and the one place one is right: this is waiting out a
        // timer that must not fire, and there is no event for something not
        // happening. Longer than the wait, so a timer that was going to fire
        // has had every chance to.
        SystemClock.sleep(OUTLAST_THE_WAIT);

        UiObject2 settled = awaitPage();
        assertNotNull("the gallery disappeared while waiting out the timer", settled);
        assertTrue("the three-second wait overrode a page the swipe chose: was \""
                   + chosen + "\", now \"" + pageDescription(settled) + "\"",
                   pageDescription(settled).equals(chosen));
    }

    // --- getting onto the screen ---------------------------------------------

    /** The same door {@link FilterTest} uses, and for the same reasons - see
     *  its own {@code launchLibrary} for why a plain launcher intent is not
     *  this, and why the display has to be said out loud. */
    private void launchLibrary() {
        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(intent, Screen.here());

        assertNotNull("the library screen never came up", device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_options))), FIND));

        Screen.assertHere();
    }

    // --- driving the pane ------------------------------------------------------

    /**
     * Taps a row by the name it shows, scrolling Browse to it first if it is
     * not already up.
     *
     * Nothing is waited for here on purpose. What proves the tap landed is the
     * assertion that follows it in each test - a gallery appearing, or the
     * video page being reached - and a wait of this method's own would only be
     * a second, weaker version of the same question. It would also eat into
     * the three seconds these tests are about.
     */
    private void select(String name) {
        UiObject2 row = device.findObject(By.text(name));

        if (row == null) {
            assertTrue("no row reads \"" + name + "\", even after scrolling Browse",
                       scrollToText(name));
            row = device.wait(Until.findObject(By.text(name)), FIND);
        }

        assertNotNull("no row on screen reads \"" + name + "\"", row);
        row.click();
    }

    /**
     * The selection in between, waited out properly: the point of it is that
     * the pane has genuinely let go of the previous game's gallery, and until
     * that has happened the next selection's own gallery cannot be told apart
     * from the last one's still being on screen.
     */
    private void selectTheOtherGame() {
        select(otherGame);

        // otherGame has no video of its own - see its field comment - so its
        // gallery is pictures or nothing either way, and the video page being
        // gone is what says the previous selection's has been cleared.
        long deadline = SystemClock.uptimeMillis() + FIND;
        while (device.findObject(By.descStartsWith(videoPrefix())) != null
                && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL);
        }
    }

    /**
     * Whichever gallery page is on screen - a picture or the video, both of
     * which name themselves "… N of M"; see {@code Gallery}'s own {@code
     * onBindViewHolder}, and why an unlabelled page was a screen reader
     * announcing "unlabelled, button".
     *
     * Null rather than a failure when there is none: an unscraped game has an
     * empty gallery, and it is the caller that knows whether that is the bug.
     */
    private UiObject2 awaitPage() {
        long deadline = SystemClock.uptimeMillis() + FIND;

        while (SystemClock.uptimeMillis() < deadline) {
            UiObject2 page = device.findObject(By.descStartsWith(picturePrefix()));
            if (page == null) page = device.findObject(By.descStartsWith(videoPrefix()));
            if (page != null) return page;

            SystemClock.sleep(POLL);
        }
        return null;
    }

    /**
     * Polls until the video page is the one showing, rather than sleeping past
     * the wait and looking once. Three seconds is when the timer fires, not
     * when the pager has finished moving: {@code Gallery.showPage} scrolls to
     * a neighbour and jumps anything further, and either takes a frame or two
     * to settle - see CLAUDE.md on {@code PagerSnapHelper} correcting a rest a
     * frame after the IDLE you read.
     */
    private boolean awaitVideoPage(long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;

        while (SystemClock.uptimeMillis() < deadline) {
            if (device.findObject(By.descStartsWith(videoPrefix())) != null) return true;
            SystemClock.sleep(POLL);
        }
        return false;
    }

    /**
     * One page forward, as a drag rather than a programmatic move: only a real
     * drag reaches {@code Gallery.setOnUserSwipe}, since {@code
     * Gallery.showPage} deliberately does not tell that listener about a move
     * it made itself. That distinction is the whole thing under test, so a
     * swipe that did not read as one would quietly invert the result.
     *
     * Slow enough to be a drag and not a fling - a fling can carry past the
     * next page, and this wants exactly one.
     */
    private void swipeToNextPage(UiObject2 page) {
        Rect bounds = page.getVisibleBounds();
        int y = bounds.centerY();
        int inset = bounds.width() / 8;

        device.swipe(bounds.right - inset, y, bounds.left + inset, y, 20);

        // The snap helper corrects a misaligned rest a frame after the IDLE
        // that reads as settled - see CLAUDE.md - so what a page calls itself
        // the instant a swipe ends can still be the page being left.
        SystemClock.sleep(SNAP);
    }

    /** From the top and with room to reach the bottom, the same as {@link
     *  FilterTest#scrollToText} and for the same reason: a row found or not
     *  depending on where somebody left the list is not a test. */
    private boolean scrollToText(String text) {
        for (String className : BROWSE_LIST_CLASSES) {
            UiScrollable list = new UiScrollable(new UiSelector().className(className));
            if (!list.exists()) continue;

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

    private String pageDescription(UiObject2 page) {
        CharSequence description = page.getContentDescription();
        return description == null ? "" : description.toString();
    }

    /** "Picture " - everything the format string says before its numbers, so a
     *  page is recognised without knowing how many there are, and in whatever
     *  language the app is currently in. */
    private String picturePrefix() {
        return prefixOf(context.getString(R.string.gallery_picture, 1, 1));
    }

    private String videoPrefix() {
        return prefixOf(context.getString(R.string.gallery_video, 1, 1));
    }

    private static String prefixOf(String formatted) {
        int firstDigit = 0;
        while (firstDigit < formatted.length()
                && !Character.isDigit(formatted.charAt(firstDigit))) {
            firstDigit++;
        }
        return formatted.substring(0, firstDigit);
    }
}
