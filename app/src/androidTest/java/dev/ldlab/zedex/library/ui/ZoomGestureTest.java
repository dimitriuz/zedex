package dev.ldlab.zedex.library.ui;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Pinching a picture, against the pager it lives in.
 *
 * <b>Why this is not a UI Automator test.</b> A pinch is two fingers, and the
 * whole question here is which view gets them - the picture, or the horizontal
 * {@code RecyclerView} it is a page of. Driving that through a real gallery
 * would prove it end to end and say nothing about why; injecting the events
 * against a parent that records what it was asked says exactly which of the
 * two won, which is the fact the fix turns on.
 */
@RunWith(AndroidJUnit4.class)
public class ZoomGestureTest {

    /**
     * Stands in for the gallery's {@code RecyclerView}: all it has to do is
     * remember whether the picture asked it to keep its hands off.
     *
     * That request is the entire mechanism. A horizontal RecyclerView decides
     * a gesture is a scroll as soon as a pointer moves past its touch slop,
     * and from that moment the child sees no more of it - so a pinch that does
     * not ask first is a pinch the pager takes away before the second finger
     * has moved far enough to mean anything.
     */
    private static final class RecordingParent extends FrameLayout {

        Boolean lastAsked;

        RecordingParent(Context context) {
            super(context);
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallow) {
            lastAsked = disallow;
            super.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private Context context;
    private RecordingParent parent;
    private ZoomableImageView picture;

    /**
     * Built on the main thread, because {@code GestureDetector} wants a
     * Looper and the instrumentation's own thread has none - the same reason
     * every dispatch below goes through runOnMainSync.
     */
    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();

        onMain(() -> build());
    }

    private static void onMain(Runnable what) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(what);
    }

    private void build() {
        picture = new ZoomableImageView(context);
        // A real bitmap, not a ColorDrawable: refit() gives up when the
        // drawable has no intrinsic size, and a ColorDrawable reports -1 - so
        // the matrix stays identity and a pinch scales nothing, which reads
        // exactly like the bug being unfixed.
        Bitmap bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.GREEN);
        picture.setImageDrawable(new BitmapDrawable(context.getResources(), bitmap));
        picture.setZoomable(true);

        parent = new RecordingParent(context);
        parent.addView(picture, new FrameLayout.LayoutParams(WIDE, TALL));

        // Laid out by hand: nothing is attached to a window here, and a view
        // with no size answers every gesture with nothing.
        measureAndLay(parent);
    }

    /**
     * Big enough for a pinch this device will believe.
     *
     * ScaleGestureDetector refuses to start below getScaledMinimumScalingSpan,
     * which is 446 pixels on this emulator - a real span in real millimetres,
     * so a gesture synthesised smaller than it is recognised as nothing at
     * all and every assertion fails exactly as it would if the code were
     * broken. Measured, not guessed: the detector was asked what it wanted.
     */
    private static final int WIDE = 1600;
    private static final int TALL = 900;

    private static void measureAndLay(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(WIDE, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(TALL, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, WIDE, TALL);
    }

    // --- injecting two fingers ----------------------------------------------

    /**
     * Injected events have to look like a touchscreen's.
     *
     * ScaleGestureDetector ignores a gesture whose source is not a pointer
     * device, and MotionEvent.obtain defaults the source to zero - so a pinch
     * built without this is dispatched, handled, and silently recognised as
     * nothing at all. Costs an afternoon to find, because every assertion
     * fails exactly as it would if the code under test were broken.
     */
    private static final int TOUCHSCREEN = android.view.InputDevice.SOURCE_TOUCHSCREEN;

    private static MotionEvent twoFingers(long down, long at, int action,
                                          float leftX, float rightX) {
        MotionEvent.PointerProperties[] pointers = {
            new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties(),
        };
        pointers[0].id = 0;
        pointers[1].id = 1;

        MotionEvent.PointerCoords[] coords = {
            new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords(),
        };
        coords[0].x = leftX;
        coords[0].y = TALL / 2f;
        coords[1].x = rightX;
        coords[1].y = TALL / 2f;

        return MotionEvent.obtain(down, at, action, 2, pointers, coords,
                                  0, 0, 1f, 1f, 0, 0, TOUCHSCREEN, 0);
    }

    private static MotionEvent oneFinger(long down, long at, int action, float x) {
        MotionEvent event = MotionEvent.obtain(down, at, action, x, TALL / 2f, 0);
        event.setSource(TOUCHSCREEN);
        return event;
    }

    /** A pinch outwards, as a hand actually makes one: one finger, then a
     *  second, then both moving apart. */
    private void pinchOutwards() {
        onMain(() -> pinchNow());
    }

    private void pinchNow() {
        long down = SystemClock.uptimeMillis();

        float middle = WIDE / 2f;

        picture.dispatchTouchEvent(oneFinger(down, down, MotionEvent.ACTION_DOWN, middle));

        int secondDown = MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        picture.dispatchTouchEvent(twoFingers(down, down + 10, secondDown,
                                              middle - 20, middle + 20));

        // Time moves on between events, because a detector that measures a
        // gesture over no time at all measures nothing - and the fingers go a
        // long way apart, because it will not start below its minimum span.
        for (int step = 1; step <= 10; step++) {
            float apart = 20 + step * 70;
            picture.dispatchTouchEvent(twoFingers(down, down + 10 + step * 16,
                                                  MotionEvent.ACTION_MOVE,
                                                  middle - apart, middle + apart));
        }
    }

    /**
     * The pager is told the moment the second finger lands, not when the
     * pinch is big enough to recognise.
     *
     * This is the test the first fix did not have and needed. Waiting for
     * ScaleGestureDetector's onScaleBegin looks right and is far too late: it
     * will not begin below getScaledMinimumScalingSpan - 446 pixels on the
     * emulator here - while a horizontal RecyclerView calls the gesture a
     * scroll after a few pixels of movement. The pager has already taken it
     * and cancelled this view, so onScaleBegin never runs and zoom does
     * nothing, which is exactly what shipped.
     */
    @Test
    public void thepagerIsToldAsSoonAsASecondFingerLands() {
        onMain(() -> {
            long down = SystemClock.uptimeMillis();
            float middle = WIDE / 2f;

            picture.dispatchTouchEvent(oneFinger(down, down, MotionEvent.ACTION_DOWN, middle));

            // Barely apart: nowhere near the detector's minimum span, which is
            // the whole point - the pager must be warned off before a pinch is
            // big enough for anything to recognise it as one.
            int secondDown = MotionEvent.ACTION_POINTER_DOWN
                    | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            picture.dispatchTouchEvent(twoFingers(down, down + 10, secondDown,
                                                  middle - 20, middle + 20));
        });

        assertTrue("the pager was not warned off until the pinch was already "
                   + "big enough to recognise, by which time it has taken the "
                   + "gesture and cancelled this view",
                   Boolean.TRUE.equals(parent.lastAsked));
    }

    /**
     * A pinch has to be the picture's, not the pager's.
     *
     * The gallery is a horizontal RecyclerView, so at rest the picture hands
     * gestures to it - that is what makes a flick across a cover turn the page
     * rather than drag a picture that has nowhere to go. But a pinch is not a
     * flick, and nothing was taking that permission back when one began: the
     * pager claimed the gesture on the first movement past its slop and the
     * pinch never reached the picture at all, on every screen and every
     * picture.
     */
    @Test
    public void apinchAsksThePagerToKeepItsHandsOff() {
        pinchOutwards();

        assertTrue("the picture never asked the pager to leave a pinch alone, so "
                   + "the pager takes it as a scroll and zoom does nothing",
                   Boolean.TRUE.equals(parent.lastAsked));
    }

    /** And the picture actually grows, which is the point of the permission. */
    @Test
    public void apinchOutwardsZoomsIn() {
        pinchOutwards();

        assertTrue("a pinch outwards did not scale the picture up",
                   scaleOf(picture) > 1f);
    }

    /** A one-finger touch at rest still belongs to the pager, or a flick
     *  across a cover would stop turning the page. */
    @Test
    public void asingleFingerAtRestIsStillThePagersToTake() {
        onMain(() -> {
            long down = SystemClock.uptimeMillis();
            picture.dispatchTouchEvent(oneFinger(down, down, MotionEvent.ACTION_DOWN, WIDE / 2f));
        });

        assertTrue("a plain touch was taken from the pager, which would stop a "
                   + "flick across a picture turning the page",
                   Boolean.FALSE.equals(parent.lastAsked));
    }

    /** The horizontal scale out of the view's own matrix - what the picture is
     *  actually drawn at, rather than what it was asked for. */
    private static float scaleOf(ZoomableImageView view) {
        float[] values = new float[9];
        view.getImageMatrix().getValues(values);
        return values[android.graphics.Matrix.MSCALE_X];
    }
}
