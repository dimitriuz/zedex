package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.ui.GameInfoView;
import dev.ldlab.zedex.library.ui.Ripple;
import dev.ldlab.zedex.view.ActivityLights;
import dev.ldlab.zedex.view.EmulatorLayout;
import dev.ldlab.zedex.view.JoystickView;
import dev.ldlab.zedex.view.QuickBar;
import dev.ldlab.zedex.view.SpectrumKeyboardView;
import dev.ldlab.zedex.view.SystemKeyboardView;
import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * What the panel of a two-screen handheld looks like; {@link Panels} owns the
 * window it lives in for the emulator, {@link LibraryPanel} for the library.
 *
 * A dual-screen handheld — the AYN Thor and its like — is a game machine on top
 * and a panel below. That is the shape of this app: the picture wants a whole
 * screen, and the keyboard, the joystick, the lamps and the bar want to be under
 * a thumb rather than taking the picture's space to get there.
 *
 * A {@link Presentation} is Android's own answer — a window on another display,
 * owned by the activity and following its lifetime — so each activity that uses
 * one stays one activity with one emulation thread and one surface. Both the
 * emulator and the library put one of these on the panel, and each gets a fresh
 * instance: a {@code Presentation} belongs to whichever activity opened it and
 * dies when that activity does, so nothing here is carried across the hand-over
 * between them - see {@code docs/LIBRARY.md}'s notes on that hand-over and
 * {@link #setGameInfo} for what does travel, which is a path, not a window.
 *
 * <b>Two things can be on screen, and a corner switch flips between them.</b>
 * The controls — the bar, the joystick, the keyboard, the lamps — are what the
 * emulator lends, exactly as before; the game info — the artwork, the name, the
 * facts, the description — is {@link GameInfoView}, built from nothing but a
 * path, since a {@code Presentation} cannot borrow a view from another
 * activity's layout the way the controls are borrowed from this one's. The
 * library has no controls to lend at all, so its own panel is game info and
 * nothing else, with no switch to offer - {@link #hasControls} is what tells
 * the two apart, and {@link #updateVisibility} is the one place that acts on
 * it. See {@link LibraryPanel} for how the library uses this with an empty
 * {@code borrowed} array.
 *
 * The controls' own views are <b>borrowed, not copied</b>. {@link
 * EmulatorLayout#setLentAway} detaches them and this window adopts them, so a
 * latched shift or an open bar group survives the move and every reference to
 * them still works. They are handed back the moment this window closes: nothing
 * may be left parented to a window that has gone. The game info side carries no
 * such state - a game's own details are rebuilt from the store every time, so
 * there is nothing to borrow and nothing to hand back for it.
 *
 * Top to bottom in the controls: the bar, the joystick in the space below it,
 * the keyboard, and the lamps at the foot — a hand's things near the hand, and
 * the thing that is only read out of the way. The bar is <em>over</em> the
 * column rather than in it, with a spacer holding its height, so a group
 * opening does not push the whole panel down.
 */
public final class SecondScreen extends Presentation {

    /** Black, like the main window: this is the same app, not a page. */
    private static final int BACKING = 0xff000000;

    /** Room around the strip, so nothing sits against the panel's edge, dp. */
    private static final int MARGIN = 8;

    /**
     * The joystick, in the same proportions the machine's screen gives it: the
     * pad's diameter, fire's share of that, and a key button's share of fire.
     */
    private static final int PAD_SIZE = 168;
    private static final float FIRE_OF_PAD = 0.72f;
    private static final float KEY_OF_FIRE = 0.46f;
    private static final int KEY_GAP = 5;

    /** The corner switch's own side, dp - comfortably tappable without being
     *  in the way of the artwork it floats over. */
    private static final int SWITCH_SIZE_DP = 40;

    private enum Mode { CONTROLS, INFO }

    private final View[] borrowed;

    /** Whether there are any controls to switch away from at all - true only
     *  for the emulator's own panel, which lends real ones; the library's has
     *  none to lend, so its panel is {@link Mode#INFO} always and {@link
     *  #switchButton} never appears. Decided once, from {@link #borrowed},
     *  never re-read: a panel with nothing to lend does not gain controls
     *  partway through being shown. */
    private final boolean hasControls;

    private LinearLayout column;

    /** Built and shown always, whether or not there is anything to switch
     *  away from - see {@link #hasControls}. */
    private GameInfoView infoView;

    /** Only when {@link #hasControls}; null otherwise, and every method that
     *  touches it checks that first. */
    private View controlsRoot;
    private ImageButton switchButton;

    /** The game's own path relative to the content tree, or null when there
     *  is nothing the store could name for whatever is loaded now - see
     *  {@link #setGameInfo}. */
    private String infoPath;

    /** {@link Mode#CONTROLS} until the switch is used - the emulator's own
     *  panel opens on its controls, since starting a game is choosing to
     *  play it; the library's panel is {@link Mode#INFO} regardless, since
     *  {@link #hasControls} overrides this in {@link #updateVisibility}.
     *  Not reset by {@link #setGameInfo}: a person who flips to the info
     *  side and then starts another game keeps looking at info for the new
     *  one too, rather than being silently carried back to the controls.
     *  {@link #setPreferInfo} is how a fresh instance is told what the last
     *  one's own switch was left at - this field alone does not survive a
     *  panel closing and reopening, since a new instance is what {@link
     *  Panels#apply} builds each time; see that field's own comment. */
    private Mode preferredMode = Mode.CONTROLS;

    /** Told whenever the switch is used, so whichever of {@link Panels} or
     *  {@link LibraryPanel} owns this instance can remember the choice past
     *  this panel's own lifetime - see {@link #setOnModeChanged}. */
    interface OnModeChanged {
        void onModeChanged(boolean info);
    }

    private OnModeChanged modeListener;

    /** Told the moment the info side puts a manual on this presentation's
     *  own display - see {@link GameInfoView#setOnManualOpened} and {@link
     *  Panels}'s class comment, the fourth corner. {@link Panels} and
     *  {@link LibraryPanel} both set this to step their own window aside
     *  for it, exactly as they already do for one of the app's own
     *  screens - a manual is a foreign one, and never reaches either
     *  owner's lifecycle callbacks the way one of ours would. */
    private Runnable foreignScreenListener;

    SecondScreen(Context context, Display display, View[] borrowed) {
        super(context, display);
        this.borrowed = borrowed;
        this.hasControls = borrowed.length > 0;
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

        FrameLayout stage = new FrameLayout(getContext());
        stage.setBackgroundColor(BACKING);

        infoView = new GameInfoView(getContext());
        infoView.setOnManualOpened(() -> {
            // The fifth of the moments listed on updateVisibility a video
            // must not be left running for - the manual is about to cover
            // this same display, whichever side of the switch is showing.
            infoView.release();
            if (foreignScreenListener != null) foreignScreenListener.run();
        });
        // The same room the controls get - see MARGIN's own comment - so
        // the cover's own patterned border is never cut by the panel's edge
        // the way it was when this had none at all.
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        infoParams.setMargins(margin, margin, margin, margin);
        stage.addView(infoView, infoParams);

        if (hasControls) {
            controlsRoot = buildControls(margin, room);
            stage.addView(controlsRoot, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            switchButton = new ImageButton(getContext());
            switchButton.setBackgroundColor(0x80000000);
            switchButton.setForeground(Ripple.make());
            switchButton.setColorFilter(0xffffffff);
            switchButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
            switchButton.setOnClickListener(v -> {
                preferredMode = preferredMode == Mode.CONTROLS ? Mode.INFO : Mode.CONTROLS;
                updateVisibility();
                if (modeListener != null) modeListener.onModeChanged(preferredMode == Mode.INFO);
            });

            int side = Math.round(SWITCH_SIZE_DP
                    * getContext().getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams switchParams = new FrameLayout.LayoutParams(
                    side, side, Gravity.TOP | Gravity.END);
            switchParams.topMargin = switchParams.rightMargin = margin;
            stage.addView(switchButton, switchParams);
        }

        setContentView(stage);
        updateVisibility();

        // After the content, not before: until there is a decor view there is
        // no insets controller to ask, and asking is a crash. No status bar
        // over the controls, for the same reason the machine's own window has
        // none - every row of a panel this size is a row of keys, and the
        // game info side wants the whole panel for the same reason the
        // library's own screens do.
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.hide(WindowInsets.Type.systemBars());
            insets.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /**
     * Told whenever the game changes - a fresh load, a different one while
     * this panel is already up, or nothing at all, which is read the same
     * way whether that is because nothing is selected or because whatever is
     * loaded has no path the store could look up: a file manager's
     * hand-over, ES-DE's {@code %ROMPROVIDER%}, <em>Open recent…</em>, or an
     * entry inside a zip. Null for {@code relativePath} covers every one of
     * those the same way - there is nothing to show, so the switch goes with
     * it rather than staying to offer an empty panel.
     *
     * Callers only reach this once {@link #show} has succeeded - {@link
     * Panels#apply} and {@link LibraryPanel#apply} both call it right after,
     * so a panel that appears later already knows what to show from the
     * very first frame.
     */
    void setGameInfo(String relativePath, String name) {
        infoPath = relativePath;

        if (relativePath == null) infoView.clear();
        else infoView.showEntry(relativePath, name);

        updateVisibility();
    }

    /** Told once, right after {@link #show} succeeds, what the switch was
     *  last left at - see {@link #preferredMode}'s own comment for why this
     *  instance cannot simply remember that for itself. A no-op for the
     *  library's own panel, which has no switch to set. */
    void setPreferInfo(boolean info) {
        preferredMode = info ? Mode.INFO : Mode.CONTROLS;
        updateVisibility();
    }

    /** Told whenever the switch is used - see {@link OnModeChanged}. */
    void setOnModeChanged(OnModeChanged listener) {
        this.modeListener = listener;
    }

    /** Told whenever a manual lands on this display - see {@link
     *  #foreignScreenListener}. */
    void setOnForeignScreen(Runnable listener) {
        this.foreignScreenListener = listener;
    }

    /**
     * Shows whichever side {@link #hasControls}, {@link #infoPath} and
     * {@link #preferredMode} say should be showing, and the switch with it -
     * named for the mode tapping it would go <em>to</em>, the same rule
     * {@code LibraryActivity.updateViewToggle} follows for its own view
     * button, rather than the one already on screen.
     *
     * The one place a video on the info side is stopped for having lost the
     * screen to the controls - the fourth of the moments CLAUDE.md lists a
     * video must not be left running for, the other three being the
     * selection moving on, the panel coming down, and the host activity
     * pausing. A manual opening over this same display is a fifth, stopped
     * where it happens instead - see {@link #foreignScreenListener}'s own
     * wiring in {@link #onCreate} - since nothing here changes about which
     * side is showing, only whether a foreign window covers it.
     */
    private void updateVisibility() {
        boolean canSwitch = hasControls && infoPath != null;
        boolean showInfo = !hasControls || (infoPath != null && preferredMode == Mode.INFO);

        infoView.setVisibility(showInfo ? View.VISIBLE : View.GONE);
        if (controlsRoot != null) controlsRoot.setVisibility(showInfo ? View.GONE : View.VISIBLE);

        if (switchButton != null) {
            switchButton.setVisibility(canSwitch ? View.VISIBLE : View.GONE);

            if (canSwitch) {
                switchButton.setImageResource(showInfo ? R.drawable.ic_chip : R.drawable.ic_info);
                switchButton.setContentDescription(getContext().getString(
                        showInfo ? R.string.library_machine : R.string.library_info));
            }
        }

        if (!showInfo) infoView.release();
    }

    /**
     * Stops the info side's own video without taking the panel down - the
     * host activity pausing is one of the four moments listed on {@link
     * #updateVisibility}, and it is not "the panel coming down": {@link
     * Panels#apply} and {@link LibraryPanel#apply} both put the panel back
     * on resume, and a video that was merely paused rather than reloaded is
     * one less thing for a person to notice restarting.
     */
    void pauseVideo() {
        infoView.release();
    }

    /**
     * Gives the controls back before the window goes, which is the whole of
     * the bargain: a view left parented here would be attached to a window
     * that no longer exists, and the layout that owns it would never see it
     * again. The info side has nothing borrowed to give back, only its own
     * video to stop.
     */
    @Override
    public void dismiss() {
        returnBorrowed();
        infoView.release();
        super.dismiss();
    }

    /**
     * The controls: the bar, the joystick, the keyboard and the lamps, sorted
     * out of {@link #borrowed} and stacked exactly as this panel always
     * stacked them before the info side existed. Only ever called when
     * {@link #hasControls}.
     */
    private View buildControls(int margin, int room) {
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

        View joystick = joystick(stick, margin, room);
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
        //
        // A subclass for one method: a touch anywhere but the bar shuts the
        // group it has open, the same as in the machine's window, and the keys
        // and the joystick below consume their own touches so a listener here
        // would never hear them.
        final QuickBar watching = bar;

        FrameLayout root = new FrameLayout(getContext()) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
                if (watching != null) watching.collapseIfOutside(event);

                return false;
            }
        };
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

        return root;
    }

    /**
     * The joystick, in a cluster of its own: the pad at one end, fire at the
     * other, and the three key buttons in an arc round the inboard side of
     * fire - the same arc the machine's screen puts them in, because it is the
     * shape a thumb makes reaching off fire and not a way of fitting them into
     * whatever black there happened to be.
     *
     * Bigger here than beside the picture. There the joystick is a guest in the
     * black at the edge of a 4:3 window; a panel is a control surface, and the
     * controls can have the room.
     *
     * The cluster is placed by arithmetic rather than by gravity, since an arc
     * is not something a FrameLayout can be asked for, and it is a fixed size
     * inside a band that centres it: the panel's spare height is above and
     * below the joystick, not inside it.
     */
    private View joystick(List<JoystickView> parts, int margin, int room) {
        if (parts.isEmpty()) return null;

        float density = getContext().getResources().getDisplayMetrics().density;
        int pad = Math.round(PAD_SIZE * density);
        int fire = Math.round(pad * FIRE_OF_PAD);
        int key = Math.round(fire * KEY_OF_FIRE);
        int gap = Math.round(KEY_GAP * density);

        // Centre to centre: out of fire, across the gap, to the middle of a key.
        int reach = fire / 2 + gap + key / 2;

        // Tall enough for the arc's own quarter turn, and never less than the
        // pad, which is the other thing in here.
        int tall = Math.max(pad, 2 * (Math.round(reach * 0.7071f) + key / 2));

        int fireX = room - margin - fire / 2;
        int middle = tall / 2;

        FrameLayout cluster = new FrameLayout(getContext());
        int slot = 0;

        for (JoystickView part : parts) {
            int size;
            int centreX;
            int centreY;

            switch (part.part()) {
                case PAD:
                    size = pad;
                    centreX = margin + pad / 2;
                    centreY = middle;
                    break;

                case FIRE:
                    size = fire;
                    centreX = fireX;
                    centreY = middle;
                    break;

                default:
                    // In profile order from the top of the arc round, an eighth
                    // of a turn apart, measured from the inboard horizontal and
                    // positive upwards.
                    double angle = (1 - slot) * Math.PI / 4;

                    size = key;
                    centreX = fireX - (int) Math.round(Math.cos(angle) * reach);
                    centreY = middle - (int) Math.round(Math.sin(angle) * reach);
                    slot++;
                    break;
            }

            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(size, size);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.leftMargin = centreX - size / 2;
            params.topMargin = centreY - size / 2;

            cluster.addView(part, params);
        }

        FrameLayout band = new FrameLayout(getContext());
        FrameLayout.LayoutParams held = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, tall);
        held.gravity = Gravity.CENTER_VERTICAL;
        band.addView(cluster, held);

        return band;
    }

    /**
     * Hardware keys belong to the machine, wherever they arrive.
     *
     * A window that can host an input method is a window that takes the input
     * focus, and on a handheld the panel is the screen a hand touches last - so
     * without this the gamepad and any real keyboard would be talking to a
     * window whose only job is to hold a keyboard picture. The activity gets
     * first refusal on everything, exactly as it would with one screen. Just as
     * true for the library's own panel: its gamepad handling lives in {@code
     * LibraryActivity.dispatchKeyEvent} the same way the emulator's does.
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
     * Undoes everything {@link #buildControls} did to the views it borrowed,
     * and hands every one of them back parentless. A no-op when {@link
     * #hasControls} is false: {@link #column} was never built, so there is
     * nothing to undo - the library's own panel reaches this through {@link
     * #dismiss} exactly as the emulator's does, and simply finds nothing to
     * do.
     *
     * Each view is asked for its own parent rather than the containers being
     * emptied, because they are not all in the same one: the joystick's five
     * parts are in a band of this window's making, and clearing the column
     * took the band away with them still inside it - so the layout they went
     * home to found them already spoken for, and threw.
     */
    private void returnBorrowed() {
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
