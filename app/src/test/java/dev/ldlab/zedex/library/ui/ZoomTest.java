package dev.ldlab.zedex.library.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Where a zoomed picture is allowed to sit.
 *
 * On the JVM because none of it is Android: the whole reason this arithmetic
 * was pulled out of the view is that it is the part which cannot be checked by
 * looking. A picture that drifts a pixel per drag, or a map whose bottom edge
 * cannot quite be reached, looks like a rendering quirk and is a sign error.
 */
public class ZoomTest {

    private static final float EXACT = 0f;

    @Test
    public void thescaleNeverGoesBelowFittingOrPastTheCeiling() {
        assertEquals(Zoom.MINIMUM, Zoom.clampScale(0.2f), EXACT);
        assertEquals(Zoom.MINIMUM, Zoom.clampScale(-4f), EXACT);
        assertEquals(Zoom.MAXIMUM, Zoom.clampScale(1000f), EXACT);
        assertEquals(2.5f, Zoom.clampScale(2.5f), EXACT);
    }

    /**
     * A picture bigger than the window is dragged within its own edges.
     *
     * Neither edge may be pulled inside the window: dragging right stops when
     * the left edge reaches the window's, and dragging left stops when the
     * right edge does. The gap that would otherwise appear is the whole
     * failure this prevents.
     */
    @Test
    public void abigPictureCannotBeDraggedPastItsOwnEdges() {
        assertEquals("dragged right, stopped at its left edge",
                     0f, Zoom.clampOffset(3000f, 1000f, 500f), EXACT);
        assertEquals("dragged left, stopped at its right edge",
                     -2000f, Zoom.clampOffset(3000f, 1000f, -5000f), EXACT);
        assertEquals("in between, left alone",
                     -750f, Zoom.clampOffset(3000f, 1000f, -750f), EXACT);
    }

    /**
     * A picture smaller than the window is centred, whatever the drag says.
     *
     * The other rule, and the one that is easy to miss: every picture is
     * smaller than the window at 1x, and so is the short side of most of them
     * zoomed in. Clamping it the same way as a big one pins it to the top
     * left, which is how a portrait map ends up hanging off the top of the
     * screen with a gap under it.
     */
    @Test
    public void asmallPictureIsCentredRatherThanPinned() {
        assertEquals(250f, Zoom.clampOffset(500f, 1000f, 0f), EXACT);
        assertEquals(250f, Zoom.clampOffset(500f, 1000f, -900f), EXACT);
        assertEquals(250f, Zoom.clampOffset(500f, 1000f, 900f), EXACT);

        assertEquals("exactly filling is still centred, at nought",
                     0f, Zoom.clampOffset(1000f, 1000f, 400f), EXACT);
    }

    /**
     * The point under the fingers is the one that must not move.
     *
     * What makes a pinch feel attached to the hand rather than to the middle
     * of the screen. Doubling the scale about a point 100 into a picture whose
     * edge is at nought puts that edge at -100, so the same part of the
     * picture is still under the finger.
     */
    @Test
    public void thepointBeingHeldStaysUnderTheFinger() {
        assertEquals(-100f, Zoom.focused(0f, 100f, 1f, 2f), EXACT);

        assertEquals("zooming back out undoes it exactly",
                     0f, Zoom.focused(-100f, 100f, 2f, 1f), EXACT);

        assertEquals("a scale that does not change moves nothing",
                     -37f, Zoom.focused(-37f, 500f, 2f, 2f), EXACT);
    }

    /** And it does not divide by nought when asked to. */
    @Test
    public void anonsenseScaleIsNotDividedBy() {
        assertEquals(-12f, Zoom.focused(-12f, 100f, 0f, 2f), EXACT);
    }
}
