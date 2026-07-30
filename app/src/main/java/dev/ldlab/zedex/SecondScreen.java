package dev.ldlab.zedex;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * The controls, on a handheld's second screen.
 *
 * Dual-screen Android handhelds - the AYN Thor and its like - are a game
 * machine on top and a second panel below, with a real gamepad between them.
 * That is exactly the shape of this app: the picture wants a whole screen and
 * the keyboard, the lamps and the quick bar want to be under a thumb, and on
 * one screen they have been taking the picture's space to get there.
 *
 * A {@link Presentation} is Android's own answer for this - a window on
 * another display, owned by the activity and following its lifetime - so the
 * app stays one activity with one emulation thread and one surface.
 *
 * The views are <b>borrowed, not copied</b>. {@link EmulatorLayout#setLentAway}
 * detaches the same three views this window then adopts, so a latched shift or
 * an open group of the bar survives the move, and everything that already holds
 * a reference to them - the activity's fades, the menu's toggles - goes on
 * working. They are handed back the moment this window closes: nothing may be
 * left parented to a window that has gone.
 *
 * The stack is the bar at the top, the lamps under it and the keyboard filling
 * what is left, which is the same order they have down the side of a phone.
 * The keyboard scales itself into whatever box it gets, so a tall panel simply
 * gives it a wide one and letterboxes the rest.
 */
final class SecondScreen extends Presentation {

    /** Black, like the main window: this is the same app, not a page. */
    private static final int BACKING = 0xff000000;

    /** Room around the strip, so nothing sits against the panel's edge, dp. */
    private static final int MARGIN = 8;

    private final View[] borrowed;
    private LinearLayout column;

    SecondScreen(Context context, Display display, View[] borrowed) {
        super(context, display);
        this.borrowed = borrowed;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // Nothing here is worth dimming the machine's own screen for, and the
        // panel is a control surface: it stays lit as long as the app is up.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        getWindow().setDecorFitsSystemWindows(false);

        int margin = Math.round(MARGIN
                * getContext().getResources().getDisplayMetrics().density);
        int room = getContext().getResources().getDisplayMetrics().widthPixels;

        column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BACKING);
        // Top and bottom only: the keyboard is worth every pixel of the width,
        // and the two things that are not the keyboard centre themselves.
        column.setPadding(0, margin, 0, margin);

        QuickBar bar = null;
        ActivityLights lamps = null;
        SpectrumKeyboardView keys = null;
        View sheet = null;

        for (View view : borrowed) {
            if (view instanceof QuickBar) bar = (QuickBar) view;
            else if (view instanceof ActivityLights) lamps = (ActivityLights) view;
            else if (view instanceof SpectrumKeyboardView) keys = (SpectrumKeyboardView) view;
            else sheet = view;                      // the ☰ drawer
        }

        // The bar at the top, the keys under it and against them, the lamps at
        // the foot: the things a hand does are near the hand, and the thing it
        // only reads is out of the way at the bottom.
        if (bar != null) {
            // Sized to this panel rather than left at its full size: the bar is
            // as wide as its icons, and a panel narrower than they are would
            // simply lose the last of them off the edge.
            bar.setCompact(room - margin * 2);
            column.addView(bar, stacked(false, margin));
        }

        if (keys != null) {
            // The room left, never the keyboard's own natural height: a panel is
            // usually shorter than the keys are tall at this width, and asking
            // for that height loses the bottom row off the edge. It scales into
            // whatever box it gets, and sits at the bottom of it.
            keys.setBottomAligned(true);
            column.addView(keys, stacked(true, margin));
        }

        if (lamps != null) {
            // A strip of its own across a panel reads as a row whichever way up
            // the phone that lent it to us happens to be.
            lamps.setHorizontal(Boolean.TRUE);
            column.addView(lamps, stacked(false, 0));
        }

        // The sheet is not part of the stack: it slides in over the whole panel,
        // scrim and all, which is what it does over the machine's window.
        if (sheet == null) {
            setContentView(column);
        } else {
            FrameLayout root = new FrameLayout(getContext());
            root.addView(column, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(sheet, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(root);
        }

        // After the content, not before: until there is a decor view there is
        // no insets controller to ask, and asking is a crash. No status bar
        // over the controls, for the same reason the machine's own window has
        // none - every row of a panel this size is a row of keys.
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.hide(WindowInsets.Type.systemBars());
            insets.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /**
     * Gives the views back before the window goes, which is the whole of the
     * bargain: a view left parented here would be attached to a window that no
     * longer exists, and the layout that owns it would never see it again.
     */
    @Override
    public void dismiss() {
        release();
        super.dismiss();
    }

    /**
     * How one thing sits in the column: the keyboard takes what is left, and
     * everything else is as big as it wants to be and centred.
     */
    private static LinearLayout.LayoutParams stacked(boolean fills, int below) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                fills ? ViewGroup.LayoutParams.MATCH_PARENT
                      : ViewGroup.LayoutParams.WRAP_CONTENT,
                fills ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT,
                fills ? 1 : 0);

        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = below;

        return params;
    }

    /** Undoes everything this window did to the views it borrowed. */
    void release() {
        if (column == null) return;

        ViewGroup root = (ViewGroup) column.getParent();
        if (root != null) root.removeAllViews();

        for (View view : borrowed) {
            if (view instanceof ActivityLights) {
                ((ActivityLights) view).setHorizontal(null);
            }
            if (view instanceof SpectrumKeyboardView) {
                ((SpectrumKeyboardView) view).setBottomAligned(false);
            }
        }

        column.removeAllViews();
        column = null;
    }
}
