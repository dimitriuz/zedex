package dev.ldlab.zedex.screen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When the panel of a two-screen handheld has to be out of the way.
 *
 * On the JVM, and that is the whole reason {@link StepAside} exists as
 * something separate: the rule needs a second display to exercise on a device,
 * every bench in CI has one display, and a test guarded by an {@code
 * assumeTrue} on a second one prints {@code OK} having asserted nothing. This
 * rule has been wrong in both directions already - a panel that never came
 * back, and a panel that came back over the folder picker - and neither was
 * anything a device test was ever going to catch.
 */
public class StepAsideTest {

    private final StepAside stepAside = new StepAside();

    /** Two stand-ins for screens of the app's own. Identity is all {@link
     *  StepAside} uses them for. */
    private final Object settings = new Object();
    private final Object viewer = new Object();

    @Test
    public void anemptyPanelIsShowing() {
        assertFalse("nothing has covered the panel and it is hiding anyway",
                    stepAside.hidden());
    }

    @Test
    public void ascreenOfOursPutsThePanelAside() {
        stepAside.opened(settings);

        assertTrue("a screen of ours is up on the panel's display and the "
                   + "panel is still drawn over it",
                   stepAside.hidden());
    }

    /**
     * The folder picker, which is the fault this shape was written for.
     *
     * A foreign window covering our screen <em>stops</em> it, and a rule kept
     * on started/stopped read that as our screen having gone: the panel came
     * back and drew over the picker, which flashed up and vanished under the
     * app. Being covered is not being gone, and only {@link StepAside#closed}
     * says gone.
     */
    @Test
    public void ascreenCoveredByAForeignWindowIsStillAScreen() {
        stepAside.opened(settings);

        // ...the picker opens over it, our screen stops, and nothing at all
        // is reported here, because being stopped is not being closed.

        assertTrue("the panel came back while one of our screens was merely "
                   + "covered, so it now draws over whatever covered it - the "
                   + "folder picker was unusable exactly like this",
                   stepAside.hidden());
    }

    /** And it is the screen going that brings the panel back. */
    @Test
    public void apanelComesBackWhenOurScreenIsReallyGone() {
        stepAside.opened(settings);
        stepAside.closed(settings);

        assertFalse("our screen is gone and the panel stayed hidden, which "
                    + "leaves Android's own launcher on the second screen",
                    stepAside.hidden());
    }

    /**
     * A screen covered and uncovered is started twice and destroyed once.
     *
     * Which is why this is a set and not a counter: a counter incremented on
     * each start would come out one short at the end and hide the panel for
     * the rest of the session.
     */
    @Test
    public void ascreenThatStartsTwiceStillOnlyGoesOnce() {
        stepAside.opened(settings);
        stepAside.opened(settings);
        stepAside.closed(settings);

        assertFalse("the panel is still hidden after the only screen over it "
                    + "was destroyed, because it was counted twice for coming "
                    + "back from being covered",
                    stepAside.hidden());
    }

    /** Two of ours at once, and the panel waits for both. */
    @Test
    public void twoScreensOfOursBothHaveToGo() {
        stepAside.opened(settings);
        stepAside.opened(viewer);
        stepAside.closed(viewer);

        assertTrue("the panel came back with one of our two screens still up",
                   stepAside.hidden());

        stepAside.closed(settings);

        assertFalse("the panel stayed hidden with nothing of ours left",
                    stepAside.hidden());
    }

    /** A manual's viewer is nobody's callback, so it says so itself. */
    @Test
    public void amanualHoldsThePanelAsideOnItsOwn() {
        stepAside.foreignOpened();
        assertTrue("a manual is up on that display and the panel is over it",
                   stepAside.hidden());

        stepAside.foreignClosed();
        assertFalse("the manual has gone and the panel did not come back",
                    stepAside.hidden());
    }

    /**
     * The two reasons together, which is the whole point of one object
     * answering.
     *
     * Either alone deciding is how a panel ends up in a state neither of them
     * asked for: the manual going away must not bring the panel back over a
     * screen of ours, and our screen going must not bring it back over a
     * manual.
     */
    @Test
    public void neitherReasonGetsToDecideAlone() {
        stepAside.opened(settings);
        stepAside.foreignOpened();

        stepAside.foreignClosed();
        assertTrue("the manual closing brought the panel back over one of our "
                   + "own screens",
                   stepAside.hidden());

        stepAside.foreignOpened();
        stepAside.closed(settings);
        assertTrue("our screen closing brought the panel back over a manual",
                   stepAside.hidden());
    }

    /**
     * The panel going away forgets what it was waiting for, but not what is
     * actually on that display.
     *
     * A manual belongs to the panel that opened it - the next panel is a new
     * instance and has no such thing to wait for. A screen of ours is not:
     * it is still there, and still has to be waited for by whatever panel
     * comes next.
     */
    @Test
    public void apanelClosingForgetsTheManualAndNotOurScreens() {
        stepAside.foreignOpened();
        stepAside.panelClosed();

        assertFalse("a fresh panel is waiting for a manual the last one opened",
                    stepAside.hidden());

        stepAside.opened(settings);
        stepAside.panelClosed();

        assertTrue("a fresh panel would draw over a screen of ours that is "
                   + "still on that display",
                   stepAside.hidden());
    }

    /**
     * Coming back to the app clears a foreign latch nothing else can.
     *
     * Nothing in Android reports another app's activity closing, so the latch
     * is set on a signal we have and cleared on one we hope for - and on a
     * handheld that gives each display its own focus, the hoped-for one never
     * comes at all: the host never stops being top-resumed on the screen it is
     * already on. Every panel built afterwards was hidden the moment it
     * appeared, which is what "the second screen shows the app I had before
     * this one" turned out to be.
     */
    @Test
    public void thehostComingBackClearsAstuckForeignLatch() {
        stepAside.foreignOpened();
        assertTrue(stepAside.hidden());

        stepAside.hostResumed();

        assertFalse("a foreign window nothing ever reported closing kept the "
                    + "panel down for the life of the process",
                    stepAside.hidden());
    }

    /** But it does not clear our own screens: those are reported both ways,
     *  and one of them may genuinely still be up when the host resumes -
     *  a settings screen on the panel while the machine is touched. */
    @Test
    public void thehostComingBackDoesNotForgetOurOwnScreens() {
        stepAside.opened(settings);
        stepAside.hostResumed();

        assertTrue("a screen of ours on that display was forgotten, so the "
                   + "panel would draw over it",
                   stepAside.hidden());
    }
}
