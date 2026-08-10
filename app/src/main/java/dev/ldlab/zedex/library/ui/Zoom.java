package dev.ldlab.zedex.library.ui;

/**
 * Where a zoomed picture is allowed to sit.
 *
 * The arithmetic of {@link ZoomableImageView} and none of its Android: given
 * how big the picture is drawn, how big the window is and where somebody has
 * dragged it to, this says where it actually ends up. Separate because it is
 * the part that is easy to get wrong and impossible to check by eye - an
 * off-by-one here is a picture that drifts a pixel every time it is dragged,
 * or a map whose bottom edge cannot quite be reached.
 */
public final class Zoom {

    private Zoom() {
    }

    /** Never below 1: the picture is never smaller than it fits. */
    public static final float MINIMUM = 1f;

    /**
     * How far in a double tap goes, and as far as a pinch may.
     *
     * A game map is the reason this is as high as it is: they run to two
     * thousand pixels across and are unreadable at anything less.
     */
    public static final float DOUBLE_TAP = 3f;
    public static final float MAXIMUM = 8f;

    public static float clampScale(float scale) {
        return Math.max(MINIMUM, Math.min(MAXIMUM, scale));
    }

    /**
     * Where the picture may be offset to, in one dimension.
     *
     * <b>Two different rules, and which applies depends on the size.</b> A
     * picture wider than the window is dragged within its own edges: the
     * offset runs from the window's width minus the picture's, up to nought,
     * so neither edge can be pulled inside the window and leave a gap. A
     * picture narrower than the window - which is every picture at 1x, and
     * the short dimension of most of them zoomed in - has no room to be
     * dragged at all, and is centred instead. Treating both the same way is
     * the bug that pins a portrait map to the top of the screen.
     *
     * @param content how wide the picture is drawn, in pixels
     * @param window  how wide the space showing it is
     * @param offset  where the drag would put its left edge
     * @return where its left edge actually goes
     */
    public static float clampOffset(float content, float window, float offset) {
        if (content <= window) return (window - content) / 2f;

        return Math.max(window - content, Math.min(0f, offset));
    }

    /**
     * The offset that keeps {@code focus} over the same part of the picture
     * as the scale changes.
     *
     * What makes a pinch feel attached to the fingers rather than to the
     * middle of the screen: the point under them is the one thing that must
     * not move. Also what a double tap uses, so it zooms into what was
     * tapped.
     *
     * @param offset where the picture's edge is now
     * @param focus  the point being held still, in window coordinates
     * @param from   the scale it is at
     * @param to     the scale it is going to
     */
    public static float focused(float offset, float focus, float from, float to) {
        if (from <= 0f) return offset;

        return focus - (focus - offset) * (to / from);
    }
}
