package dev.ldlab.zedex.library.ui;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/**
 * What "this is the one" looks like, in one place.
 *
 * Three screens said it in the same colour and none of them said it loudly
 * enough: {@code 0x3300b0c8} over the app's {@code #14151a} backing is a
 * twenty-per-cent cyan wash, and against an untinted row beside it that is
 * <b>1.37:1</b>. WCAG 1.4.11 asks 3:1 of anything that carries meaning. The
 * three were the library's selected row, its active tab, and the options
 * dialog's cursor row - which is to say every place a gamepad or a keyboard
 * shows you where you are, on the screen this app is meant to be driven from
 * with a controller.
 *
 * A wash cannot be made to carry it: taking the alpha high enough for 3:1
 * turns the row solid cyan and the label on it unreadable. So the tint stays
 * roughly as it was and a border does the work - {@code #00b0c8} at full
 * opacity against the backing is <b>6.98:1</b>, and two density-independent
 * pixels of it reads at a glance without moving anything or changing a row's
 * height.
 *
 * Kept beside {@link Ripple} because they are the same kind of thing and get
 * used together: a ripple says "you touched this", this says "you are here".
 */
public final class Selection {

    private Selection() {
    }

    /** The wash, near enough to what it always was. */
    private static final int TINT = 0x3300b0c8;

    /** And the edge that makes it legible: 6.98:1 against {@code #14151a}. */
    private static final int EDGE = 0xff00b0c8;

    private static final float EDGE_DP = 2f;

    /**
     * The background for the row, tab or cell that is currently the one.
     *
     * A fresh Drawable per call, for the reason {@link Ripple#make} gives: a
     * drawable carries bounds and state, and two views must not share them.
     */
    public static Drawable background(float density) {
        GradientDrawable shape = new GradientDrawable();

        shape.setColor(TINT);
        shape.setStroke(Math.max(1, Math.round(EDGE_DP * density)), EDGE);

        return shape;
    }
}
