package dev.ldlab.zedex;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * The joystick, in the same proportions the machine's screen gives it: the
     * pad's diameter, fire's share of that, and a key button's share of fire.
     */
    private static final int PAD_SIZE = 132;
    private static final float FIRE_OF_PAD = 0.72f;
    private static final float KEY_OF_FIRE = 0.46f;
    private static final int KEY_GAP = 5;

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
        SystemKeyboardView typist = null;
        List<JoystickView> stick = new ArrayList<>();
        View sheet = null;

        for (View view : borrowed) {
            if (view instanceof QuickBar) bar = (QuickBar) view;
            else if (view instanceof ActivityLights) lamps = (ActivityLights) view;
            else if (view instanceof SpectrumKeyboardView) keys = (SpectrumKeyboardView) view;
            else if (view instanceof SystemKeyboardView) typist = (SystemKeyboardView) view;
            else if (view instanceof JoystickView) stick.add((JoystickView) view);
            else sheet = view;                      // the ☰ drawer
        }

        // The bar at the top, the keys under it and against them, the lamps at
        // the foot: the things a hand does are near the hand, and the thing it
        // only reads is out of the way at the bottom.
        //
        // The bar itself is not in the column but over it - see below - so what
        // goes here is a space the height of its icons. A group opening adds a
        // list under them, and a list that pushed would take its room from the
        // joystick and then from the keys, which is the whole panel moving
        // because somebody looked at a menu.
        View strip = new View(getContext());
        column.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));

        View joystick = joystick(stick, margin);
        if (joystick != null) {
            // The whole width, because the pad goes at one end and fire at the
            // other; and all the height left over, because its contents centre
            // themselves in it - which is what puts the joystick in the middle
            // of the space between the bar and the keys rather than under the
            // bar with the emptiness below it.
            LinearLayout.LayoutParams across = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            across.bottomMargin = margin;
            column.addView(joystick, across);
        }

        if (keys != null) {
            // The room left, never the keyboard's own natural height: a panel is
            // usually shorter than the keys are tall at this width, and asking
            // for that height loses the bottom row off the edge. It scales into
            // whatever box it gets, and sits at the bottom of it.
            keys.setBottomAligned(true);

            // As tall as the keys need and no taller: the room left over goes
            // to the joystick above them. The view caps itself at the height it
            // is offered, so a panel too short for the whole keyboard still
            // gets all of it, smaller.
            LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            row.bottomMargin = margin;
            column.addView(keys, row);
        }

        if (lamps != null) {
            // A strip of its own across a panel reads as a row whichever way up
            // the phone that lent it to us happens to be.
            lamps.setHorizontal(Boolean.TRUE);
            column.addView(lamps, stacked(false, 0));
        }

        // Two things are not part of the stack. The sheet slides in over the
        // whole panel, scrim and all, which is what it does over the machine's
        // window; and the pixel the device's own keyboard types into is a pixel.
        FrameLayout root = new FrameLayout(getContext());
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        if (bar != null) {
            // Sized to this panel rather than left at its full size: the bar is
            // as wide as its icons, and a panel narrower than they are would
            // simply lose the last of them off the edge.
            bar.setCompact(room - margin * 2);

            FrameLayout.LayoutParams across = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            across.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            across.topMargin = margin;
            root.addView(bar, across);

            // The space kept for it below follows its icons, whatever they came
            // out as at this size, and ignores anything it opens.
            QuickBar sized = bar;
            bar.addOnLayoutChangeListener((view, l, t2, r2, b2, ol, ot, or2, ob) -> {
                int wanted = sized.rowHeight() + margin * 2;

                if (strip.getLayoutParams().height != wanted) {
                    strip.getLayoutParams().height = wanted;
                    strip.requestLayout();
                }
            });
        }

        if (typist != null) {
            root.addView(typist, new FrameLayout.LayoutParams(1, 1));
        }

        if (sheet != null) {
            root.addView(sheet, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        setContentView(root);

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
     * The joystick, laid out across a band of its own.
     *
     * The pad at one end and fire at the other, which is how two thumbs hold a
     * machine, with the three key buttons stacked inside fire's end - the arc
     * they make on the machine's screen is a way of fitting them into black
     * that happens to be there, and here there is a row to put them in.
     */
    private View joystick(List<JoystickView> parts, int margin) {
        if (parts.isEmpty()) return null;

        float density = getContext().getResources().getDisplayMetrics().density;
        int pad = Math.round(PAD_SIZE * density);
        int fire = Math.round(pad * FIRE_OF_PAD);
        int key = Math.round(fire * KEY_OF_FIRE);
        int gap = Math.round(KEY_GAP * density);

        FrameLayout band = new FrameLayout(getContext());
        int slot = 0;

        for (JoystickView part : parts) {
            FrameLayout.LayoutParams params;

            switch (part.part()) {
                case PAD:
                    params = new FrameLayout.LayoutParams(pad, pad);
                    params.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                    params.leftMargin = margin;
                    break;

                case FIRE:
                    params = new FrameLayout.LayoutParams(fire, fire);
                    params.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                    params.rightMargin = margin;
                    break;

                default:
                    // Beside fire, top to bottom in profile order: one above
                    // the middle, one on it, one below.
                    params = new FrameLayout.LayoutParams(key, key);
                    params.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                    params.rightMargin = margin + fire + gap;
                    params.topMargin = (slot - 1) * (key + gap);
                    slot++;
                    break;
            }

            band.addView(part, params);
        }

        band.setMinimumHeight(pad);
        return band;
    }

    /**
     * Hardware keys belong to the machine, wherever they arrive.
     *
     * A window that can host an input method is a window that takes the input
     * focus, and on a handheld the panel is the screen a hand touches last - so
     * without this the gamepad and any real keyboard would be talking to a
     * window whose only job is to hold a keyboard picture. The activity gets
     * first refusal on everything, exactly as it would with one screen.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Context owner = getContext();

        if (owner instanceof Activity
                && ((Activity) owner).dispatchKeyEvent(event)) {
            return true;
        }

        return super.dispatchKeyEvent(event);
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

    /**
     * Undoes everything this window did to the views it borrowed, and hands
     * every one of them back parentless.
     *
     * Each is asked for its own parent rather than the containers being emptied,
     * because they are not all in the same one: the joystick's five parts are in
     * a band of this window's making, and clearing the column took the band away
     * with them still inside it - so the layout they went home to found them
     * already spoken for, and threw.
     */
    void release() {
        if (column == null) return;

        for (View view : borrowed) {
            if (view instanceof ActivityLights) {
                ((ActivityLights) view).setHorizontal(null);
            }
            if (view instanceof SpectrumKeyboardView) {
                ((SpectrumKeyboardView) view).setBottomAligned(false);
            }

            if (view != null && view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        }

        column = null;
    }
}
