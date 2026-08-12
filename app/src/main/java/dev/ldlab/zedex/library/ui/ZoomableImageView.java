package dev.ldlab.zedex.library.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * A picture that can be pinched into.
 *
 * For the one thing in a scraped record that is useless without it: a game map
 * is two thousand pixels across and is a grey smudge on a phone. Media scans
 * and inlays benefit too - the small print on a cassette is the sort of thing
 * somebody opens a scan to read.
 *
 * <b>Off unless asked for.</b> The gallery in the details pane is a small box
 * somebody flicks through, and pinch there would fight the pager for every
 * gesture; only the full-screen viewer turns this on. Off, this is an ordinary
 * {@code ImageView} with {@code FIT_CENTER} and no touch handling at all -
 * which is exactly what {@code Gallery} had before, comments and all.
 *
 * <b>It hands the pager back its gestures at the edges.</b> Zoomed in, a
 * horizontal drag belongs to the picture until the picture has no further to
 * go that way; then it belongs to the pager, so swiping to the next item still
 * works from the right-hand edge of a map. Keeping every drag would strand
 * somebody on a zoomed picture with no way out but the back button.
 *
 * A framework {@code ImageView} and not {@code AppCompatImageView}, which is
 * what lint asks for and would be wrong here: appcompat is in this project
 * for one screen - {@code SettingsActivity}, because {@code
 * PreferenceFragmentCompat} will not attach to anything else - and every view
 * in the app is a framework one. The tinting and vector support the compat
 * class exists to back-port are not used by anything this draws, which is
 * always a decoded bitmap.
 */
@SuppressLint("AppCompatCustomView")
public final class ZoomableImageView extends ImageView {

    /**
     * The matrix that makes the picture fit, and the one actually drawn.
     *
     * {@code FIT_CENTER} is not available once this is driving the matrix
     * itself, so its effect is computed and kept: every zoom is relative to
     * how the picture would have sat, which is what makes turning zoom off
     * and on again a no-op rather than a jump.
     */
    private final Matrix fitted = new Matrix();
    private final Matrix drawn = new Matrix();

    private final float[] values = new float[9];

    private boolean zoomable;
    private float scale = 1f;
    private float offsetX;
    private float offsetY;

    private ScaleGestureDetector pinch;
    private GestureDetector taps;

    public ZoomableImageView(Context context) {
        super(context);
        setScaleType(ScaleType.FIT_CENTER);
    }

    /**
     * Turns pinching on, once.
     *
     * The detectors are built here rather than in the constructor because most
     * of these views never need them: a gallery in the pane makes one per page
     * and turns none of them on.
     */
    public void setZoomable(boolean on) {
        if (zoomable == on) return;

        zoomable = on;

        if (!on) {
            reset();
            setScaleType(ScaleType.FIT_CENTER);
            return;
        }

        if (pinch == null) {
            pinch = new ScaleGestureDetector(getContext(), new Pinching());
            taps = new GestureDetector(getContext(), new Tapping());
        }

        setScaleType(ScaleType.MATRIX);
        refit();
    }

    /** Back to fitting, which every new picture starts at - a gallery page
     *  recycled from a map somebody had zoomed into must not arrive zoomed. */
    public void reset() {
        scale = 1f;
        offsetX = 0f;
        offsetY = 0f;

        if (zoomable) refit();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        reset();
    }

    @Override
    protected void onSizeChanged(int width, int height, int wasWidth, int wasHeight) {
        super.onSizeChanged(width, height, wasWidth, wasHeight);
        if (zoomable) refit();
    }

    // --- the gestures ------------------------------------------------------------

    /**
     * Kept because {@link #onTouchEvent} does not call {@code super}, and
     * without it lint is right to say a custom touch handler has swallowed
     * the accessibility click that {@code performClick} exists to deliver.
     * See {@code Tapping.onSingleTapConfirmed}, which calls it.
     */
    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!zoomable || getDrawable() == null) return super.onTouchEvent(event);

        // A second finger means a pinch, and the pager has to be told now.
        //
        // Telling it in ScaleGestureDetector's onScaleBegin - which is where
        // this was, and why zoom still did nothing after the first attempt at
        // it - is far too late. The detector will not begin until the span
        // passes getScaledMinimumScalingSpan, 446 pixels on one device here,
        // and a horizontal RecyclerView calls a gesture a scroll as soon as
        // one pointer moves past its touch slop, which is a few. The pager has
        // taken the gesture and cancelled this view long before the pinch is
        // wide enough to be recognised as one, so onScaleBegin never ran.
        //
        // The pointer count is the earliest honest signal there is: one finger
        // could still become a flick and belongs to the pager, two never can.
        if (event.getPointerCount() > 1) letThePagerHave(false);

        pinch.onTouchEvent(event);
        taps.onTouchEvent(event);

        // Consumed whatever happens, or a tap on a zoomed picture would fall
        // through to whatever is behind it while a drag on the same pixel is
        // handled here.
        return true;
    }

    /** Whether the pager may have this drag, which is only where the picture
     *  itself has run out of room. See the class comment. */
    private void letThePagerHave(boolean allowed) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(!allowed);
    }

    private final class Pinching extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        /**
         * A pinch is this view's, whatever the pager thinks.
         *
         * Without this, zoom did nothing at all - on every picture and every
         * screen, which is why it read as a feature nobody had written yet.
         * {@link Tapping#onDown} hands gestures to the pager while the picture
         * is unzoomed, and that is right for a flick: it is what makes a swipe
         * across a cover turn the page rather than drag something with nowhere
         * to go. But a pinch comes in through the same door, and a horizontal
         * {@code RecyclerView} calls a gesture a scroll the moment a pointer
         * passes its touch slop - from then on the child sees none of it, so
         * {@link #onScale} was never reached and the picture never grew.
         *
         * {@link ZoomableImageView#onTouchEvent} has already warned the pager
         * off by the time this runs - it does it on the second pointer, which
         * is far earlier - so this is belt and braces for a detector that
         * begins some other way, not the thing that makes a pinch work.
         */
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            letThePagerHave(false);
            return true;
        }

        /** Back to the pager's, unless the pinch left something to drag. The
         *  next {@code onDown} decides it the same way; this only stops a
         *  picture pinched back down to fitting from holding on to gestures it
         *  has no use for until then. */
        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            letThePagerHave(scale <= Zoom.MINIMUM);
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            zoomTo(scale * detector.getScaleFactor(),
                   detector.getFocusX(), detector.getFocusY());
            return true;
        }
    }

    private final class Tapping extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            // The pager keeps its own gestures until there is something to
            // drag, so a flick across a picture at 1x still turns the page.
            letThePagerHave(scale <= Zoom.MINIMUM);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent from, MotionEvent to,
                                float dx, float dy) {
            if (scale <= Zoom.MINIMUM) return false;

            float wasX = offsetX;
            move(-dx, -dy);

            // Nothing happened horizontally, so this drag is the pager's -
            // which is how somebody swipes off the edge of a zoomed map to
            // the next picture instead of being stuck on it.
            letThePagerHave(offsetX == wasX && dx != 0f);
            return true;
        }

        /**
         * A single tap still belongs to whoever set the click listener.
         *
         * <b>Confirmed, not raw.</b> The full-screen viewer closes on a tap,
         * and consuming every touch here took that away - a picture that
         * could not be dismissed, which is how this was found. Waiting for
         * the confirmation costs the double-tap timeout before it closes and
         * is the only way to tell the first tap of a zoom from a tap meaning
         * "done".
         */
        @Override
        public boolean onSingleTapConfirmed(MotionEvent event) {
            return performClick();
        }

        /**
         * Double tap zooms in on what was tapped, and out again from
         * anywhere.
         *
         * The way out matters as much as the way in: a pinch back to 1x on a
         * phone is fiddly, and without this the only way off a zoomed picture
         * is the back button.
         */
        @Override
        public boolean onDoubleTap(MotionEvent event) {
            zoomTo(scale > Zoom.MINIMUM ? Zoom.MINIMUM : Zoom.DOUBLE_TAP,
                   event.getX(), event.getY());
            return true;
        }
    }

    // --- the arithmetic, applied -------------------------------------------------

    private void zoomTo(float wanted, float focusX, float focusY) {
        float was = scale;
        scale = Zoom.clampScale(wanted);

        offsetX = Zoom.focused(offsetX, focusX, was, scale);
        offsetY = Zoom.focused(offsetY, focusY, was, scale);

        apply();
    }

    private void move(float dx, float dy) {
        offsetX += dx;
        offsetY += dy;
        apply();
    }

    /** How the picture would sit at 1x - {@code FIT_CENTER}'s own answer,
     *  asked of the framework rather than worked out again here. */
    private void refit() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0) return;

        fitted.reset();

        float wide = drawable.getIntrinsicWidth();
        float high = drawable.getIntrinsicHeight();
        if (wide <= 0 || high <= 0) return;

        float fit = Math.min(getWidth() / wide, getHeight() / high);

        fitted.setScale(fit, fit);
        fitted.postTranslate((getWidth() - wide * fit) / 2f,
                             (getHeight() - high * fit) / 2f);

        apply();
    }

    private void apply() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;

        fitted.getValues(values);

        float wide = drawable.getIntrinsicWidth() * values[Matrix.MSCALE_X] * scale;
        float high = drawable.getIntrinsicHeight() * values[Matrix.MSCALE_Y] * scale;

        // At 1x the offsets mean nothing - the fitted matrix already centres
        // it - so they are clamped to the centring rule and come out as the
        // same picture in the same place.
        offsetX = Zoom.clampOffset(wide, getWidth(), offsetX);
        offsetY = Zoom.clampOffset(high, getHeight(), offsetY);

        drawn.setScale(values[Matrix.MSCALE_X] * scale, values[Matrix.MSCALE_Y] * scale);
        drawn.postTranslate(offsetX, offsetY);

        setImageMatrix(drawn);
    }
}
