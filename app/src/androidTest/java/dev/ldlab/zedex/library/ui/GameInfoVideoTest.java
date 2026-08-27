package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.Screen;
import dev.ldlab.zedex.screen.LibraryActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where a game's video link opens, and where it must not.
 *
 * <b>The bug this was written for.</b> {@code GameInfoView.openVideo} asked
 * for the view's own display through {@code ActivityOptions} and carried no
 * {@code FLAG_ACTIVITY_NEW_TASK}. On the second screen this view lives on a
 * {@code Presentation}, whose context is a theme wrapper over a display
 * context rather than an activity, and {@code startActivity} on one of those
 * without the flag throws {@code AndroidRuntimeException} - so the targeted
 * launch threw, the catch logged, the bare fallback threw the same way, and
 * the only thing a person saw was "Nothing on this phone can open that". The
 * icon was dead on the panel.
 *
 * <b>And the obvious fix was the forbidden one.</b> Adding the flag while
 * keeping the display target would have put a <em>foreign</em> activity on
 * the panel's display, which CLAUDE.md rules out outright: a {@code
 * Presentation} draws above every activity window on its display, nothing
 * anywhere reports a foreign activity closing, and on a handheld with
 * per-display focus the signal the step-aside latch would have to be cleared
 * from never fires at all - which hides every panel built afterwards. So the
 * fix is the flag <em>and no display target</em>, and no announcement to the
 * panel either.
 *
 * <b>What this can and cannot prove.</b> It cannot prove a browser opens -
 * that needs a browser, a network and somebody looking - and it deliberately
 * does not launch one: a real {@code startActivity} in a test leaves another
 * app in front of the suite, which is the documented way a whole run starts
 * reading the wrong screen. What it proves is the part that was wrong: which
 * of the two {@code startActivity} overloads is called (the one-argument one
 * carries no {@code ActivityOptions}, so there is no display target in
 * existence to get wrong), that the intent carries {@code
 * FLAG_ACTIVITY_NEW_TASK}, and that the panel is told nothing. The context is
 * a recording {@link ContextWrapper} over the real activity, so everything
 * else about building the view is real.
 */
@RunWith(AndroidJUnit4.class)
public class GameInfoVideoTest {

    private static final long FIND = 30_000;

    private static final String LINK = "https://www.youtube.com/watch?v=PdRuvdvLbjg";

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        launchLibrary();
    }

    /**
     * The link goes out as a new task, to whatever the phone has, with no
     * display named.
     *
     * The two-argument {@code startActivity} is the only way an {@code
     * ActivityOptions} bundle - and therefore {@code setLaunchDisplayId} -
     * can reach the platform at all, so "it was never called" is the whole
     * of "no display target is set". Asserted alongside the flag because
     * either one alone is a launch that fails on the panel: the flag without
     * the display was the fix, the display without the flag was the bug.
     */
    @Test
    public void aVideoLinkOpensAsANewTaskWithNoDisplayAsked() {
        Recording recording = new Recording(resumedLibrary());
        GameInfoView view = viewOn(recording);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view.openVideo(LINK));

        assertNotNull("nothing was launched at all", recording.launched);
        assertNull("a video link must never name a display: the two-argument"
                   + " startActivity is how ActivityOptions.setLaunchDisplayId"
                   + " reaches the platform, and a browser on the panel's own"
                   + " display renders under the panel with nothing to report"
                   + " it closing", recording.options);

        assertEquals(Intent.ACTION_VIEW, recording.launched.getAction());
        assertEquals(LINK, String.valueOf(recording.launched.getData()));

        assertTrue("without FLAG_ACTIVITY_NEW_TASK this throws from a"
                   + " Presentation's context, which is what the panel hands"
                   + " this view",
                   (recording.launched.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    /**
     * And the panel is told nothing.
     *
     * {@code onForeignScreen} sets a latch that only the host activity's own
     * {@code onTopResumedActivityChanged} clears - a signal a handheld with
     * per-display focus never sends, since the host never stops being
     * top-resumed on the screen it is already on. Announcing something that
     * was not put on this display is how the panel came to stay down for
     * good, so nothing foreign landing there means nothing to announce.
     */
    @Test
    public void theSecondScreenIsNotToldAboutALinkItNeverGot() {
        Recording recording = new Recording(resumedLibrary());
        GameInfoView view = viewOn(recording);

        AtomicBoolean announced = new AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view.setOnForeignScreen(() -> announced.set(true));
            view.openVideo(LINK);
        });

        assertFalse("a link that was never put on this display must not be"
                    + " announced to the panel", announced.get());
    }

    // --- the recording context ---------------------------------------------------------

    /**
     * The real activity for everything a view needs to be built, and a
     * notebook for the one call this test is about.
     *
     * Nothing is forwarded: a launch that actually went out would leave a
     * browser in front of the instrumentation, and every reading taken after
     * it would be of somebody else's screen.
     */
    private static final class Recording extends ContextWrapper {

        private Intent launched;
        private Bundle options;

        private Recording(Context real) {
            super(real);
        }

        @Override
        public void startActivity(Intent intent) {
            launched = intent;
        }

        @Override
        public void startActivity(Intent intent, Bundle options) {
            launched = intent;
            this.options = options == null ? Bundle.EMPTY : options;
        }
    }

    /** The view, built on the recording context on the main thread - never
     *  attached to anything: {@code openVideo} reads {@code getContext()} and
     *  nothing else, and a view added to the activity would put a second
     *  screen's worth of rows in front of the suite for no gain. */
    private GameInfoView viewOn(Context on) {
        GameInfoView[] built = { null };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> built[0] = new GameInfoView(on));

        assertNotNull("the game info view was never built", built[0]);
        return built[0];
    }

    /** The same door {@code CatalogueSortTest} uses, and the same check that
     *  a picker left open by hand is not what is really in front. */
    private void launchLibrary() {
        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        Screen.suppressFirstRun(context);
        context.startActivity(intent, Screen.here());

        assertNotNull("the library screen never came up", device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_options))), FIND));

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

        assertNotNull("LibraryActivity is not the resumed activity", found[0]);
        return found[0];
    }
}
