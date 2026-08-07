package dev.ldlab.zedex.library.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * A frame as tall as its own width says it should be - 3:4, the shape of
 * scraped box art.
 *
 * The grid tile's picture box used to be a fixed 100dp tall and as wide as
 * whatever the column count left it, so its shape changed with the window and
 * {@code CENTER_CROP} then cropped by however far that differed from the
 * picture's. Mild in portrait; in landscape the box measured 104x263 and half
 * the width of every cover was thrown away. A row of tiles that are all the
 * same shape is most of the point of a grid, and that shape has to be the
 * picture's, not the leftovers'.
 *
 * Measured twice on purpose: the first pass is only to learn the width the
 * parent is allowing, the second imposes the height that follows from it. The
 * children are measured against the final height, which is what keeps the
 * fallback icon centred in the box rather than in the box's first guess.
 */
public class AspectFrame extends FrameLayout {

    private static final int WIDTH = 3;
    private static final int HEIGHT = 4;

    public AspectFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);

        int width = getMeasuredWidth();
        if (width <= 0) return;

        super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(width * HEIGHT / WIDTH, MeasureSpec.EXACTLY));
    }
}
