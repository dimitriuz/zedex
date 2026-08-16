package dev.ldlab.zedex.welcome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Where the caption goes relative to the thing it is about.
 *
 * Below by default - a caption under the control reads in the direction the
 * eye is already travelling - and above only when there is not room below.
 * The quick bar is at the very top of the window and the library's rail at
 * the very bottom, so both cases are real on the first screen anybody sees.
 *
 * <b>Takes the hole's bottom edge as a plain {@code int}, not a {@code
 * Rect}.</b> The JVM tier has no Robolectric, and {@code
 * unitTests.returnDefaultValues} makes {@code android.graphics.Rect}'s
 * constructor a silent no-op rather than a thrown exception - {@code new
 * Rect(0, 1850, 400, 1980)} compiles and runs, but every field reads back as
 * zero. That is worse than a thrown {@code Stub!}, because it does not fail
 * loudly: {@code aCaptionGoesAboveSomethingNearTheBottom} below is the test
 * that caught it, failing with {@code Rect} where the other two passed for
 * the wrong reason (both expect {@code false}, which a zeroed hole also
 * produces). The rule only ever reads the hole's bottom edge, so that is all
 * this signature takes.
 */
public class CoachPlacementTest {

    private static final int WINDOW = 2000;
    private static final int CAPTION = 300;

    @Test
    public void aCaptionGoesBelowSomethingNearTheTop() {
        assertFalse(Coach.above(120, CAPTION, WINDOW));
    }

    @Test
    public void aCaptionGoesAboveSomethingNearTheBottom() {
        assertTrue(Coach.above(1980, CAPTION, WINDOW));
    }

    /** Exactly enough room below is enough: the rule must not flip on the
     *  boundary, or a control one pixel from the edge behaves differently on
     *  two devices with the same layout. */
    @Test
    public void exactlyEnoughRoomBelowIsBelow() {
        assertFalse(Coach.above(1700, CAPTION, WINDOW));
    }
}
