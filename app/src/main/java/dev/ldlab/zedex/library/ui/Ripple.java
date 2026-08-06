package dev.ldlab.zedex.library.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;

/**
 * A touch ripple in a colour this app chose, never the platform theme's -
 * shared by {@code LibraryActivity} and {@link OptionsDialog}, the two
 * places that already learned why the hard way.
 *
 * {@code android.R.drawable.list_selector_background} and {@code
 * ?android:attr/selectableItemBackground} alike draw some of their states
 * from the theme's own accent colour, which on at least one device is a
 * saturated orange - a rendering fault against a screen that is otherwise
 * dark grey and cyan on the rail, and worse in the dialog: drawn as a row's
 * own foreground, that accent came out fully opaque and hid the label under
 * it rather than merely clashing with it.
 *
 * One literal colour, for every state, is what closes this off for good
 * rather than papering over it on whichever screen happened to be looked at
 * first: a {@link ColorStateList} built from a single value, rather than
 * borrowed from a theme attribute, has no per-state slot left for a theme's
 * accent to fill. A ripple still shows on an actual touch; nothing shows for
 * "selected" or "focused" on their own, since nothing here reads either.
 */
public final class Ripple {

    private Ripple() {
    }

    /** Translucent white - reads over this app's dark backing and over its
     *  own cyan tints alike, on a rail or a row either one. */
    private static final int COLOR = 0x33ffffff;

    /** A fresh {@link Drawable} - never shared, since a view's own drawable
     *  keeps state (bounds, whether it is mid-ripple) that two views must
     *  not share. Call once per view, the same as every other background or
     *  foreground drawable here is built once per view. */
    public static Drawable make() {
        return new RippleDrawable(ColorStateList.valueOf(COLOR), null, null);
    }
}
