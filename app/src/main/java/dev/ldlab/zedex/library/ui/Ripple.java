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
 * accent to fill.
 *
 * Focus is drawn here now. It did not used to be - the sentence that stood
 * here said "nothing shows for selected or focused on their own, since nothing
 * here reads either", and that was not true of focused: every view this is put
 * on is clickable and therefore focusable, so a Bluetooth keyboard, a switch
 * device or any other non-touch input moves the framework's focus through them
 * and drew absolutely nothing while it did. On an app whose README says a
 * controller drives all of it, that is the whole navigation model invisible.
 * {@link Selection} is the sibling of this for "you are here"; this is "you
 * are about to press this".
 */
public final class Ripple {

    private Ripple() {
    }

    /** Translucent white - reads over this app's dark backing and over its
     *  own cyan tints alike, on a rail or a row either one. */
    private static final int COLOR = 0x33ffffff;

    /** The focus ring, the same cyan {@link Selection} uses for the same
     *  reason: 6.98:1 against this app's backing, where a wash is 1.37:1. */
    private static final int FOCUS = 0xff00b0c8;

    /** A fresh {@link Drawable} - never shared, since a view's own drawable
     *  keeps state (bounds, whether it is mid-ripple) that two views must
     *  not share. Call once per view, the same as every other background or
     *  foreground drawable here is built once per view. */
    public static Drawable make(float density) {
        // The focused look is the ripple's *content* rather than another
        // layer: a RippleDrawable draws content underneath the ripple, so the
        // two compose without either having to know about the other, and a
        // view that is both focused and being pressed shows both.
        android.graphics.drawable.GradientDrawable focused =
                new android.graphics.drawable.GradientDrawable();
        focused.setColor(0x00000000);
        focused.setStroke(Math.max(1, Math.round(2f * density)), FOCUS);

        android.graphics.drawable.StateListDrawable content =
                new android.graphics.drawable.StateListDrawable();
        content.addState(new int[] { android.R.attr.state_focused }, focused);
        content.addState(new int[0],
                new android.graphics.drawable.GradientDrawable());

        return new RippleDrawable(ColorStateList.valueOf(COLOR), content, null);
    }
}
