package dev.ldlab.zedex;

import dev.ldlab.zedex.screen.GameInfoActivity;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Pattern;

/**
 * The details screen wears one of two action rows, and which one turns on
 * whether a machine is behind it - {@link GameInfoActivity#EXTRA_URI} present
 * means the library sent it and there is a game to start; absent means the
 * machine's own ⓘ sent it and what is wanted is the way back.
 *
 * <b>Launched with the extras rather than driven to.</b> The two callers are
 * one line each - {@code LibraryActivity.showGameInfo} and {@code
 * EmulatorActivity.showDetails} - and what this is about is what the screen
 * builds out of what they send, so the extras are sent here directly, the same
 * way {@link DetailPaneTest} starts the library rather than walking a launcher.
 * The machine's variant cannot be driven to at all on a bench with a second
 * screen: {@code showDetails} turns the panel over instead of opening an
 * activity when there is a panel to turn, so the only way to see that row is to
 * ask for it. Nothing here needs the content folder either, which is what keeps
 * this class free of an {@code assumeTrue} that would print OK having asserted
 * nothing.
 *
 * The path is deliberately one no game has. An unscraped game is the ordinary
 * case in a real collection - most of eight hundred here - and it is also the
 * case that says most about the row: the manual and the music are the view's
 * own and stay hidden until an answer arrives that never will, so what is left
 * on screen is exactly the host's own actions and nothing else.
 *
 * Asserted by content description rather than by class, because {@code
 * ImageView.getAccessibilityClassName()} answers {@code android.widget.
 * ImageView} whatever you subclass it into - see CLAUDE.md - and every label
 * is read from resources rather than typed in English, since the app has eight
 * languages and a bench need not be in one of them. Scoped to this package
 * too: the system's own navigation bar carries a button described "Back".
 */
@RunWith(AndroidJUnit4.class)
public class GameInfoBarTest {

    private static final long FIND = 10_000;

    /** How long the absence of a button is given to disprove itself. Short,
     *  because the row it is not on is already up by the time it is asked. */
    private static final long GLANCE = 1_000;

    /** A game the store has never heard of - see the class comment. */
    private static final String NOTHING_SCRAPED = "uitest/GameInfoBarTest.tap";

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    /**
     * From the library: Play leads, Back trails, and the machine's own menu is
     * on neither end - there is no machine for it to open.
     */
    @Test
    public void openedFromTheLibraryItOffersPlayAndBackAndNotTheMachinesMenu() {
        openDetails(true);

        assertNotNull("no Play button on the row",
                      device.wait(Until.findObject(play()), FIND));

        assertNotNull("no Back icon on the row",
                      device.wait(Until.findObject(described(R.string.menu_back)), FIND));

        assertNull("the machine's menu belongs only to the machine's own variant",
                   device.wait(Until.findObject(described(R.string.menu_button)), GLANCE));

        // The other half of "opened from the library": there is nothing to
        // close the content of, so the cross is the machine's too.
        assertNull("the close icon belongs only to the machine's own variant",
                   device.wait(Until.findObject(described(R.string.library_title)), GLANCE));
    }

    /**
     * From the machine: Back leads, the machine's menu and the way out of the
     * content trail, and there is no Play - the game is already running.
     */
    @Test
    public void openedFromTheMachineItOffersBackTheMenuAndTheWayOutAndNoPlay() {
        openDetails(false);

        assertNotNull("no Back icon on the row",
                      device.wait(Until.findObject(described(R.string.menu_back)), FIND));

        assertNotNull("no ☰ icon on the row",
                      device.wait(Until.findObject(described(R.string.menu_button)), FIND));

        assertNotNull("no way out of the content on the row",
                      device.wait(Until.findObject(described(R.string.library_title)), FIND));

        assertNull("a game is already running, so there is nothing for Play to start",
                   device.wait(Until.findObject(play()), GLANCE));

        // The view's own two, which nothing scraped means nothing to reveal.
        // The absent case only: no game in this collection has music, since
        // the scrape-media chooser omits that folder outright.
        assertNull("a game with no manual must not be offered one",
                   device.wait(Until.findObject(described(R.string.library_manual)), GLANCE));

        assertNull("a game with no music must not be offered it",
                   device.wait(Until.findObject(described(R.string.music_title)), GLANCE));
    }

    /**
     * The screen, with the extras one of its two callers sends.
     *
     * {@code fromTheLibrary} is {@link GameInfoActivity#EXTRA_URI}: the file
     * itself, which only the library has and which is the whole of what the
     * row turns on. The Uri is never opened here - only a Play button that is
     * pressed hands it anywhere - so any well-formed one will do.
     *
     * Waited for rather than slept past, and checked to have landed on the
     * display this test can look at: an activity started on a bench with two
     * screens goes to whichever last had focus - see {@link Screen}.
     */
    private void openDetails(boolean fromTheLibrary) {
        Intent intent = new Intent(context, GameInfoActivity.class)
                .putExtra(GameInfoActivity.EXTRA_PATH, NOTHING_SCRAPED)
                .putExtra(GameInfoActivity.EXTRA_NAME, "GameInfoBarTest.tap")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        if (fromTheLibrary) {
            intent.putExtra(GameInfoActivity.EXTRA_URI,
                            "content://dev.ldlab.zedex.test/GameInfoBarTest.tap");
        }

        context.startActivity(intent, Screen.here());

        assertNotNull("the details screen never came up",
                      device.wait(Until.findObject(
                              By.pkg(context.getPackageName()).text("GameInfoBarTest.tap")),
                              FIND));

        Screen.assertHere();
    }

    /** One of this screen's own icons, by the words the app gives it. */
    private BySelector described(int label) {
        return By.pkg(context.getPackageName()).desc(context.getString(label));
    }

    /**
     * The one text button.
     *
     * Case-insensitively, because a {@code Button} draws its label upper-cased
     * under {@code Theme.DeviceDefault} and what accessibility reports is what
     * is drawn - so a match against the resource's own "Play" finds nothing on
     * a screen showing PLAY, which reads exactly like a missing button.
     */
    private BySelector play() {
        return By.pkg(context.getPackageName()).text(Pattern.compile(
                Pattern.quote(context.getString(R.string.library_play)),
                Pattern.CASE_INSENSITIVE));
    }
}
