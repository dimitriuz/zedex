package dev.ldlab.zedex.view;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.machine.Border;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Arranges the emulated screen and the keyboard.
 *
 * A ViewGroup of its own rather than nested LinearLayouts: the picture is a 4:3
 * quad centred in whatever box it gets, and nearly everything else is placed
 * against the black that leaves. Measuring the children here does all of it
 * without ever re-parenting them, which matters — detaching the
 * {@link android.view.SurfaceView} would destroy the surface Fuse draws into.
 *
 * One arrangement, the same either way up: the keyboard across the foot of the
 * window and the picture above it. There were four more sideways and they are
 * gone; see {@link #arrange}.
 *
 * The keyboard is one bitmap at a fixed 541x201. Given any box it scales to fit,
 * centres itself and hit-tests through the same transform, so a short box simply
 * letterboxes and every key still lands. That is what lets the cap work: at full
 * width in landscape it is 2.7 times wider than tall and takes four fifths of the
 * height, leaving the machine a slot.
 *
 * The joystick goes in the black, never on the picture, except when there is no
 * black left — then it floats over the bottom corners, translucent. See
 * {@link #placeJoystick}.
 */
public final class EmulatorLayout extends ViewGroup {

    /**
     * Height the keyboard may take in landscape, as a fraction of the window.
     * The screen is 4:3, so at 0.42 it still gets its full width back.
     */
    private static final float LANDSCAPE_BELOW = 0.42f;

    /**
     * And the width it may take there, which is what decides the height of a
     * keyboard flat enough to fit under that cap. A slim one is flat enough:
     * given the whole width it comes out with keys half again the size of a full
     * keyboard's and a picture no larger for it, which is not what it is for.
     * Both of them are inset by this and centred in the strip.
     */
    private static final float LANDSCAPE_WIDE = 0.62f;

    /**
     * Portrait cap. The natural height at full width is around a third, so
     * this only ever bites on a very short window.
     */
    private static final float PORTRAIT_BELOW = 0.5f;

    /** Rather less, because this one is over the middle of a game. */
    private static final float FLOATING_ALPHA = 0.55f;

    /** The pad's diameter, and the fire button's share of it, in dp. */
    private static final int PAD_SIZE = 132;
    private static final float FIRE_OF_PAD = 0.72f;

    /** A key button's share of fire, and the gap between the two rings. */
    private static final float KEY_OF_FIRE = 0.46f;
    private static final int KEY_GAP = 5;

    /** Below this a key button is not worth aiming at, so it is not shown. */
    private static final int KEY_MINIMUM = 30;

    /** Below this a pad is not worth aiming a thumb at, in dp. */
    private static final int PAD_MINIMUM = 84;

    /** Keeps the controls clear of the picture and of the window's edge, dp. */
    private static final int PAD_MARGIN = 12;

    /**
     * How far down the window the controls sit when they are in the side bars,
     * as a share of its height. A little below the middle: that is where a hand
     * holding a tablet is, and the foot of a bar most of a screen tall is well
     * below it.
     */
    private static final float PAD_HEIGHT = 0.58f;

    /** The play button, as a share of the picture's shorter side. */
    private static final float PLAY_OF_PICTURE = 0.28f;

    /**
     * The emulated screen as much of it as is shown, in its own pixels. Follows
     * the border setting - 320x240, 272x204 or 256x192 - and all three are 4:3,
     * so only the whole-pixel arithmetic changes and never the shape.
     */
    private int sourceWidth = Border.FULL.width;
    private int sourceHeight = Border.FULL.height;

    /** 4:3, whichever border is shown and whatever the machine. */
    private static final float SCREEN_ASPECT = 4.0f / 3.0f;

    /** The gap the quick bar keeps from the corner of the window, in dp. */
    private static final int BAR_GAP = 8;

    /**
     * What the keyboard is kept clear of the window's edges by in portrait, dp.
     *
     * The keys in the corners are the hardest to hit on a tall phone: the bottom
     * two are where the gesture bar and the curve of the glass are, and a thumb
     * reaching the outer columns arrives at an angle. A few dp costs a fraction
     * of the key size and gives every key a border to miss into.
     */
    private static final int KEYBOARD_PAD = 10;

    private final Rect screenBox = new Rect();
    private final Rect keyboardBox = new Rect();

    /**
     * One pixel in the corner for the system keyboard's input view. It has to be
     * in the window and focusable for an input method to talk to it, and there
     * is nothing to see: what it types shows up on the machine's screen.
     */
    private final Rect systemBox = new Rect(0, 0, 1, 1);
    private final Rect panelBox = new Rect();
    private final Rect menuBox = new Rect();
    private final Rect padBox = new Rect();
    private final Rect fireBox = new Rect();
    private final Rect lightsBox = new Rect();
    private final Rect playBox = new Rect();

    /** The three key buttons in the arc beside fire, in profile order. */
    private final Rect[] keyBoxes = { new Rect(), new Rect(), new Rect() };

    /**
     * The black that fire went in, so the arc beside it can be fitted to the
     * same space. Set by {@link #placeJoystick} in each of its three branches -
     * the space is a different shape in each, and the arc has no other way to
     * know how much of it there is.
     */
    private final Rect fireArea = new Rect();

    /**
     * Where the 4:3 picture actually lands inside {@link #screenBox}. The
     * renderer centres it in whatever box it is given, so this is the same sum
     * it does — and the rest of the box is black, which is where everything
     * that must not cover the picture goes.
     */
    private final Rect picture = new Rect();

    private View screen;
    private View panel;
    private QuickBar menu;
    private View drawer;
    private SpectrumKeyboardView keyboard;
    private SystemKeyboardView system;
    private JoystickView pad;
    private JoystickView fire;
    private JoystickView[] keys = new JoystickView[0];

    /**
     * The keyboard that lies over the picture instead of taking room from it.
     *
     * Only where there is no room to take: fullscreen sideways, which is the
     * one layout with no keyboard at all - the point of fullscreen is the
     * picture, and a keyboard reserving a third of a landscape window is
     * exactly what the button was pressed to be rid of. So this one is not in
     * the layout at all: it is drawn over the foot of the picture, see-through,
     * for the handful of moments a game wants a key rather than a stick.
     */
    private SpectrumKeyboardView overlay;
    private View overlayOpen;
    private View overlayClose;

    private final Rect overlayBox = new Rect();
    private final Rect overlayOpenBox = new Rect();
    private final Rect overlayCloseBox = new Rect();

    private boolean overlayShown;

    /** Whether the last measure was of a landscape window; see the buttons. */
    private boolean landscapeNow;
    private ActivityLights lights;
    private View play;

    /** Whether each is wanted at all; the ☰ menu decides. */
    private boolean joystick = true;

    /**
     * Set while the on-screen joystick is to step aside for something better: a
     * real controller, or a second screen, which means a handheld with a real
     * one built in. Kept apart from {@link #joystick} rather than writing to it,
     * so that the reason going away brings back whatever the user had chosen.
     */
    private boolean suppressed;

    /**
     * Fullscreen: the bar is not given a strip of its own and the keyboard is put
     * away where that would gain the picture anything. Kept apart from the
     * keyboard the user asked for, like {@link #suppressed}, so that leaving
     * fullscreen brings back what they had.
     */
    private boolean fullscreen;

    /**
     * Whether the lamps are wanted, as against whether they are showing:
     * fullscreen hides them without changing the answer. True to begin with
     * because a freshly built view is visible, so a setting that says otherwise
     * still has something to change.
     */
    private boolean lightsWanted = true;

    /**
     * How much of the bottom of the window the system keyboard is covering, or
     * zero while it is not up. Only the Android keyboard produces this: the two
     * drawn ones are children of this layout and have boxes of their own.
     */
    private int imeInset;

    /**
     * The strip at the foot of the window that the system keeps for its own
     * gestures. Nothing of ours goes in it: a thumb that means "fire" and lands
     * there sends the app to the background instead.
     */
    private int gestureInset;

    /**
     * The part of the window nothing of ours may use: a camera hole, and the
     * system bars while they are showing, which here is never.
     *
     * An app targeting API 35 is laid out into the cutout whether it asks or
     * not - the mode that used to letterbox the window away from it is
     * interpreted as "always" now - so the quick bar's icons appeared under the
     * camera. Everything is arranged inside this instead, which is the geometry
     * the window used to be given, and the strip outside it stays the layout's
     * own black.
     *
     * All four sides, not just the top: sideways the hole is at one end of the
     * bar, and this is where the joystick and the keys go.
     */
    private final Rect safe = new Rect();

    private boolean keyboardWanted = true;

    /**
     * Device pixels per emulated pixel, per orientation, or
     * {@link FuseNative#SCALE_FIT}. The same numbers the renderer has: this is
     * not what draws the picture, but everything placed around the picture needs
     * to know how big it came out.
     */
    private int scalePortrait = FuseNative.SCALE_FIT;
    private int scaleLandscape = FuseNative.SCALE_FIT;

    /**
     * Set while the keyboard, the lamps and the bar are in another window - a
     * handheld's second screen. What is left here is the picture and the
     * joystick, which is the window fullscreen leaves anyway.
     */
    private boolean lent;

    /** Every child, back to front: the order {@link #attach} has to keep. */
    private View[] order = new View[0];

    /** Set by {@link #placeJoystick}: it found no black and used the picture. */
    private boolean joystickFloating;

    /**
     * How much of the top of the window {@link #arrange} keeps for the quick bar,
     * or zero in fullscreen where the bar overlaps instead. Everything else
     * starts below it.
     */
    private int barStrip;

    public EmulatorLayout(Context context) {
        super(context);
        setBackgroundColor(0xff000000);
    }

    /**
     * The children, back to front.
     *
     * The panel is the ROMs message and takes the whole window - it is a
     * takeover, not part of the picture - so the bar goes after it to stay
     * reachable over it, and the sheet after that to cover everything.
     *
     * The order is kept in a field rather than left implied by these calls,
     * because most of these views leave for a second screen and have to come
     * back in the same order; see {@link #setLentAway}.
     */
    public void setChildren(View screen, SpectrumKeyboardView keyboard,
                     SystemKeyboardView system,
                     JoystickView pad, JoystickView fire, JoystickView[] keys,
                     SpectrumKeyboardView overlay, View overlayOpen,
                     View overlayClose,
                     ActivityLights lights,
                     View play, View panel, QuickBar bar, View drawer) {
        this.screen = screen;
        this.keyboard = keyboard;
        this.system = system;
        this.pad = pad;
        this.fire = fire;
        this.keys = keys;
        this.overlay = overlay;
        this.overlayOpen = overlayOpen;
        this.overlayClose = overlayClose;
        this.lights = lights;
        this.play = play;
        this.panel = panel;
        this.menu = bar;
        this.drawer = drawer;

        // Front to back is the order below: the drawer covers everything, the
        // button stays over the panel, the panel covers the screen and the
        // joystick, which sits over the picture when it has to.
        List<View> all = new ArrayList<>();
        all.add(screen);
        all.add(keyboard);
        all.add(system);
        all.add(pad);
        all.add(fire);
        all.addAll(Arrays.asList(keys));
        all.add(overlay);
        all.add(overlayOpen);
        all.add(overlayClose);
        all.add(lights);
        all.add(play);
        all.add(panel);
        all.add(bar);
        all.add(drawer);

        order = all.toArray(new View[0]);
        for (View child : order) addView(child);

        applyBarMetrics();
        applyJoystickVisibility();

        // Both keyboards read the chosen skin for themselves, so they already
        // agree; this is only here for the one that cannot be drawn.
        dressOverlay();
    }

    /**
     * Lends everything but the picture to another window, or takes it back; see
     * {@link #lendable}.
     *
     * The views move rather than the other screen building a second set: they
     * hold what a copy would not - a latched shift, whichever bar group is open -
     * and every caller that already talks to them goes on working. The picture is
     * the one thing that cannot move, since detaching the SurfaceView would
     * destroy the surface Fuse draws into.
     *
     * What is left is a window holding the picture alone, which is what
     * fullscreen makes of it anyway.
     */
    public void setLentAway(boolean away) {
        if (lent == away) return;
        lent = away;

        for (View child : lendable()) {
            if (away) detach(child); else attach(child);
        }

        applyKeyboardVisibility();
        applyLightsVisibility();
        // The second screen sizes the bar to its own panel, so coming back means
        // being sized to this window again.
        if (!away) applyBarMetrics();
        requestLayout();
    }

    public boolean lentAway() {
        return lent;
    }

    /**
     * What the second screen borrows.
     *
     * The ☰ sheet goes with the bar that opens it: a button on one screen whose
     * answer appears on the other is a button that looks broken. It is not part
     * of the panel's stack - it covers whatever is under it - which the second
     * screen sorts out for itself.
     *
     * So does the one pixel the device's own keyboard types into. An input
     * method appears on the display of the window it is talking to, so leaving
     * that pixel here would put the phone's keyboard over the machine while the
     * panel sat empty - which is the one place it must not be.
     */
    public View[] lendable() {
        List<View> away = new ArrayList<>();

        away.add(menu);
        away.add(lights);
        away.add(keyboard);
        away.add(system);

        // The joystick too: a panel under the picture is a better place for a
        // thumb than the black beside it, and it leaves the machine's screen
        // with nothing on it at all.
        away.add(pad);
        away.add(fire);
        away.addAll(Arrays.asList(keys));

        away.add(drawer);

        return away.toArray(new View[0]);
    }

    /** Puts a child back where it belongs among the ones still here. */
    private void attach(View child) {
        if (child == null || child.getParent() == this) return;

        // Whoever had it should have let go of it; taking it back anyway costs
        // nothing and is better than the crash that not doing so is.
        if (child.getParent() instanceof ViewGroup) {
            ((ViewGroup) child.getParent()).removeView(child);
        }

        int index = 0;
        for (View other : order) {
            if (other == child) break;
            if (other != null && other.getParent() == this) index++;
        }

        addView(child, index);
    }

    private void detach(View child) {
        if (child != null && child.getParent() == this) removeView(child);
    }

    /** Whether a child is this layout's to measure and place at all. */
    private boolean here(View child) {
        return child != null && child.getParent() == this
                && child.getVisibility() != GONE;
    }

    /**
     * Redraws every control, for when the profile behind their faces has
     * changed: the pad's four ways, fire and the three buttons all show which
     * key they send.
     *
     * Here rather than in the activity because this is the one place holding all
     * of them - and forgetting one is exactly how fire and then the pad came to
     * be showing yesterday's keys.
     */
    public void refreshControls() {
        if (pad != null) pad.invalidate();
        if (fire != null) fire.invalidate();

        for (JoystickView key : keys) key.invalidate();
    }

    public boolean joystickVisible() {
        return joystick;
    }

    public void setJoystickVisible(boolean visible) {
        if (joystick == visible) return;

        joystick = visible;
        applyJoystickVisibility();
        requestLayout();
    }

    /**
     * Whether the picture has the window to itself.
     *
     * The bar stops reserving its strip and hangs over the corner instead, where
     * the activity fades it out after three seconds and a tap on the picture
     * brings it back; the keyboard and the lamps go away. Whichever way up: the
     * button means the picture and nothing else, and what the furniture happens
     * to cost in a given orientation is not the point of it.
     */
    public void setFullscreen(boolean on) {
        // No early return on an unchanged value: whether fullscreen hides the
        // keyboard depends on the orientation, so this is also how a rotation
        // gets the rule applied again. Called from onConfigurationChanged for
        // exactly that, and a config change costs a layout anyway.
        fullscreen = on;
        applyKeyboardVisibility();
        applyLightsVisibility();
        applyOverlayVisibility();
        requestLayout();
    }

    // --- the keyboard that lies over the picture ----------------------------

    /** Of the window's height, the most the overlay may cover. */
    private static final float OVERLAY_TALL = 0.42f;

    /** See-through enough to play through, solid enough to aim at. */
    private static final float OVERLAY_ALPHA = 0.82f;

    /** How big the two buttons are, and how far off the things they sit by. */
    private static final int OVERLAY_BUTTON = 44;
    private static final int OVERLAY_GAP = 8;

    /**
     * Whether this window is one the overlay is for.
     *
     * Fullscreen, either way up, and nowhere else: fullscreen is the one layout
     * with no keyboard in it, so it is the one that needs another way to reach
     * a key. Everywhere else there is a real keyboard a tap away, and a second
     * one would be two answers to the same question.
     *
     * Where it goes differs, and follows from what the window has spare. See
     * {@link #placeOverlay}.
     */
    public boolean overlayAvailable() {
        return !keyboardHere() && !lent && imeInset == 0;
    }

    /**
     * Whether a drawn keyboard has a place in this window.
     *
     * Four ways it has not, and they are the same question whichever way up the
     * device is: the user has put it away; fullscreen, which is the point of it;
     * it is lent to a second screen; or the skin is Android's own, which comes
     * up over the window rather than inside the layout.
     *
     * Asked by {@link #arrange} to decide whether to reserve room, and by
     * {@link #overlayAvailable} to decide whether to offer the other keyboard -
     * one question, so the two answers cannot disagree and leave the window
     * with two keyboards or none.
     */
    private boolean keyboardHere() {
        return keyboardWanted && !fullscreen && !lent
                && (keyboard == null || keyboard.skin().drawn());
    }

    public boolean overlayShown() {
        return overlayShown && overlayAvailable();
    }

    public void setOverlayShown(boolean on) {
        if (overlayShown == on) return;

        overlayShown = on;
        applyOverlayVisibility();
        requestLayout();
    }

    /**
     * Which of the three is on screen, worked out from one rule rather than at
     * each of the places that can change it: leaving fullscreen, turning the
     * device, opening it and closing it all end up here.
     */
    private void applyOverlayVisibility() {
        boolean available = overlayAvailable();
        if (!available) overlayShown = false;

        if (overlay != null) {
            overlay.setVisibility(available && overlayShown ? VISIBLE : GONE);
        }
        if (overlayOpen != null) {
            overlayOpen.setVisibility(available && !overlayShown ? VISIBLE : GONE);
        }
        if (overlayClose != null) {
            overlayClose.setVisibility(available && overlayShown ? VISIBLE : GONE);
        }
    }

    /**
     * The overlay keyboard, and the one button that shows and hides it.
     *
     * Sideways it lies over the foot of the picture, see-through, because a
     * landscape window is all picture and there is nowhere else for it. Upright
     * there is: a 4:3 picture in a tall window leaves black below it, so the
     * keyboard goes in the black and covers nothing, and needs no transparency
     * to be seen through because there is nothing behind it.
     *
     * Both buttons take the same box, above the pad, because only ever one of
     * them is on screen: the thing that shows the keyboard and the thing that
     * puts it away are the same control, and moving it to the far end while it
     * is open makes it a different one.
     */
    /**
     * The right edge of whatever control is on the left of the window, or zero.
     *
     * Only the ones on that side: a control past the middle is on the other side
     * of the keyboard, and taking its right edge would push the keyboard off the
     * screen.
     */
    private int besideOnTheLeft(int width) {
        int edge = 0;

        for (Rect box : controlBoxes()) {
            if (box.isEmpty() || box.centerX() > width / 2) continue;
            edge = Math.max(edge, box.right);
        }

        return edge;
    }

    /** And the left edge of whatever is on the right, or the window's own. */
    private int besideOnTheRight(int width) {
        int edge = width;

        for (Rect box : controlBoxes()) {
            if (box.isEmpty() || box.centerX() <= width / 2) continue;
            edge = Math.min(edge, box.left);
        }

        return edge;
    }

    /** The pad, fire, and the key buttons in the arc beside it. */
    private List<Rect> controlBoxes() {
        List<Rect> boxes = new ArrayList<>(2 + keyBoxes.length);

        boxes.add(padBox);
        boxes.add(fireBox);
        boxes.addAll(Arrays.asList(keyBoxes));

        return boxes;
    }

    private void placeOverlay(int width, int height) {
        overlayBox.setEmpty();
        overlayOpenBox.setEmpty();
        overlayCloseBox.setEmpty();

        if (overlay == null || !overlayAvailable()) return;

        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(OVERLAY_BUTTON * density);
        int gap = Math.round(OVERLAY_GAP * density);

        if (overlayShown) {
            // The same share of the width the keyboard below the picture takes,
            // centred like it - so the keys come out the size they would there.
            int across = landscapeNow ? Math.round(width * LANDSCAPE_WIDE) : width;
            int left = (width - across) / 2;
            int right = left + across;

            /*
             * The controls stay on screen while this is open, and it must not
             * cover them. Which way it gives is the whole difference between a
             * keyboard the size of the real one and a toy.
             *
             * Sideways they are at the two ends, so it gives way sideways: held
             * clear of the pad on the left and of fire and its keys on the right,
             * and then as tall as the keyboard below the picture is allowed to be.
             * It used to clamp the *height* against the bottom of those controls
             * instead - and in fullscreen the pad sits low, so a full 128K plate
             * came out 295px tall where the real one is 454, keys a third the
             * size. There is room between the controls; there was never room
             * beneath them.
             */
            if (landscapeNow) {
                left = Math.max(left, besideOnTheLeft(width) + gap);
                right = Math.min(right, besideOnTheRight(width) - gap);
            }

            int tall = Math.min(Math.round((right - left) / overlay.aspect()),
                                Math.round(height * OVERLAY_TALL));

            // Upright the controls are under the picture, squarely in the way,
            // and there is nowhere sideways to go: the height is what yields.
            if (!landscapeNow) {
                int floor = Math.max(padBox.bottom, fireBox.bottom);
                if (floor > 0) tall = Math.min(tall, height - floor - gap);
            }

            overlayBox.set(left, height - tall, right, height);

            // Alpha is a draw property, so this is safe here - the same reason
            // the floating joystick sets its own during a measure.
            overlay.setAlpha(landscapeNow ? OVERLAY_ALPHA : 1f);
        }

        // Above the pad, or in the corner the pad would have been in. Worked
        // out whether the keys are up or not, so the button stays where it was:
        // the pad stands down while they are, and its box with it.
        int centreX = padBox.isEmpty() ? gap + size / 2 : padBox.centerX();
        int bottom = padBox.isEmpty() ? height - gap : padBox.top - gap;

        // Unless that is where the keys now are. Upright they take the whole
        // width and the pad was at the foot of the window, so the button would
        // have landed on the leftmost keys - the one place it must not be, since
        // it is what puts them away.
        if (overlayShown && bottom > overlayBox.top - gap
                && centreX < overlayBox.right) {
            bottom = overlayBox.top - gap;
        }

        square(overlayShown ? overlayCloseBox : overlayOpenBox,
               centreX, bottom - size / 2, size);
    }

    public boolean fullscreen() {
        return fullscreen;
    }

    /** Whether a real controller is standing in for the on-screen one. */
    public void setJoystickSuppressed(boolean standingAside) {
        if (suppressed == standingAside) return;

        suppressed = standingAside;
        applyJoystickVisibility();
        requestLayout();
    }

    /**
     * Gone rather than merely unplaced, for the same reason the keyboard is:
     * the five controls are accessibility nodes, and a screen reader would
     * still find them sitting on top of each other at nowhere.
     */
    private void applyJoystickVisibility() {
        int visibility = joystick && !suppressed ? VISIBLE : GONE;

        if (pad != null) pad.setVisibility(visibility);
        if (fire != null) fire.setVisibility(visibility);

        // The key buttons are part of the joystick: they are where they are
        // because fire is, and hiding the joystick to get the picture back
        // would be no use if three rings stayed behind.
        for (JoystickView key : keys) key.setVisibility(visibility);
    }

    /** Draws another machine's keyboard; its aspect changes with it. */
    public void setKeyboardSkin(SpectrumKeyboardView.Skin skin) {
        if (keyboard == null || keyboard.skin() == skin) return;

        keyboard.setSkin(skin);
        dressOverlay();
        applyKeyboardVisibility();
        requestLayout();
    }

    /**
     * The overlay wears the same skin as the keyboard proper.
     *
     * Except Android's own, which is not a picture this app owns and cannot be
     * painted over a game: there the 48K slim stands in, being the drawn skin
     * that costs the picture least. Any other choice comes through as it is -
     * a full plate simply asks for more height, and gets what there is.
     */
    private void dressOverlay() {
        if (overlay == null || keyboard == null) return;

        SpectrumKeyboardView.Skin chosen = keyboard.skin();

        overlay.setSkin(chosen.drawn() ? chosen
                                       : SpectrumKeyboardView.Skin.RUBBER_SLIM);
    }

    /** The device's own keyboard, for the skin that is not drawn here. */
    public SystemKeyboardView systemKeyboard() {
        return system;
    }

    /**
     * How far up the window the system keyboard reaches, so the picture can get
     * out of its way.
     *
     * Told rather than asked, because the insets arrive at the activity and this
     * is a measure input like any other; a change in it is a change of layout.
     */
    public void setInsets(int ime, int gestures) {
        setInsets(ime, gestures, safe.left, safe.top, safe.right, safe.bottom);
    }

    /**
     * @param safeLeft the four sides of the window that are not ours to use;
     *                 see {@link #safe}
     */
    public void setInsets(int ime, int gestures,
                          int safeLeft, int safeTop, int safeRight, int safeBottom) {
        if (imeInset == ime && gestureInset == gestures
                && safe.left == safeLeft && safe.top == safeTop
                && safe.right == safeRight && safe.bottom == safeBottom) {
            return;
        }

        imeInset = ime;
        gestureInset = gestures;
        safe.set(safeLeft, safeTop, safeRight, safeBottom);

        // The phone's own keyboard is a keyboard too, and this is the only sign
        // of it: while it covers the window the overlay is not offered.
        applyOverlayVisibility();
        requestLayout();
    }

    public boolean keyboardVisible() {
        return keyboardWanted;
    }

    /** Whether the lamps are wanted, which is not whether they are showing. */
    public boolean lightsVisible() {
        return lightsWanted;
    }

    /**
     * Whether the lamps are shown at all. They are a diagnostic, and once you
     * know what a game wants there is nothing left for them to tell you.
     *
     * Separate from whether they are showing, the way the keyboard's own choice
     * is: fullscreen takes them away without touching this, and leaving
     * fullscreen brings back whatever it was.
     */
    public void setLightsVisible(boolean visible) {
        if (lightsWanted == visible) return;

        lightsWanted = visible;
        applyLightsVisibility();
        requestLayout();
    }

    /**
     * The lamps take a strip beside the picture rather than floating over it, so
     * fullscreen drops six of the seven and gives the picture that strip back -
     * which is what fullscreen is for.
     *
     * The one it keeps is the disk, and only while a drive is turning. That is
     * the lamp with something to say that cannot be waited for: whether what was
     * written has finished being written. The other six answer questions asked
     * while setting a game up, not while playing it.
     *
     * They do not come back with the quick bar on a tap: they are in the layout
     * rather than over it, so that would move the picture every time anyone
     * touched it. The quick bar's own toggle is the way to have them.
     */
    private void applyLightsVisibility() {
        if (lights == null) return;

        // Wanted at all is the Display setting; on a second screen they are a
        // strip of their own and not taking room from any picture.
        boolean showing = lightsWanted;
        boolean only = fullscreen && !lent;

        lights.setDiskOnly(only);

        if ((lights.getVisibility() == VISIBLE) != showing) {
            lights.setVisibility(showing ? VISIBLE : GONE);
        }
    }

    /**
     * How big the picture is drawn, matching what the renderer was told.
     *
     * Kept in step by hand rather than asked for, because the renderer runs on
     * the emulation thread and this is a layout pass: both apply the same rule
     * to the same box, so both get the same answer.
     */
    /** How much of the border the renderer is showing; see {@link Border}. */
    public void setBorder(Border border) {
        if (sourceWidth == border.width && sourceHeight == border.height) return;

        sourceWidth = border.width;
        sourceHeight = border.height;
        requestLayout();
    }

    public void setScale(int portrait, int landscape) {
        if (scalePortrait == portrait && scaleLandscape == landscape) return;

        scalePortrait = portrait;
        scaleLandscape = landscape;
        requestLayout();
    }

    /**
     * Puts the keyboard away, or brings it back, whichever way up the device
     * is. A decision about the keyboard, kept apart from fullscreen and from
     * the second screen, which also take it away: those are about the window,
     * and leaving either should bring back whatever was chosen here.
     */
    public void setKeyboardVisible(boolean visible) {
        if (keyboardWanted == visible) return;

        keyboardWanted = visible;
        applyKeyboardVisibility();
        requestLayout();
    }

    /**
     * A hidden keyboard is really gone, not merely given an empty box: its keys
     * are accessibility nodes, and forty of them with no bounds would still be
     * there for a screen reader to find.
     *
     * Set from here rather than from {@link #arrange}, because changing
     * visibility asks for another layout and doing that during a measure pass
     * is how layout loops start.
     */
    private void applyKeyboardVisibility() {
        // Whether a keyboard is here decides whether the other one is offered,
        // so the two are settled together: every path that changes the keyboard
        // comes through this, and none of them has to remember the overlay.
        applyOverlayVisibility();

        if (keyboard == null) return;

        // On a second screen the keyboard has a window of its own, so the rules
        // about what it costs the picture do not apply; only whether it is
        // wanted at all, and whether this skin is one we draw.
        if (lent) {
            keyboard.setVisibility(keyboardWanted && keyboard.skin().drawn()
                                   ? VISIBLE : GONE);
            return;
        }

        // Fullscreen is the picture and nothing else, whichever way up. The keys
        // used to stay in portrait, where a 4:3 picture is limited by the width
        // and they cost it nothing - but what they cost is not the point of the
        // button.
        keyboard.setVisibility(!keyboardWanted || fullscreen
                               || !keyboard.skin().drawn() ? GONE : VISIBLE);
    }

    /**
     * The bar gets the whole width of the window, which is nearly always full
     * size. The width is passed rather than zero — nine icons at 44dp is 396dp
     * and a small phone in portrait is 360dp across, so they shrink a little
     * there rather than running off the end.
     *
     * From the display and not from a measured width: this runs before the first
     * layout, and changing a child's size during a measure pass is how layout
     * loops start.
     */
    private void applyBarMetrics() {
        // Not while it is over there: the panel sized it to itself, and this
        // window rotating is none of its business.
        if (menu == null || lent) return;

        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();

        menu.setCompact(metrics.widthPixels
                        - 2 * Math.round(BAR_GAP * metrics.density));
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        applyKeyboardVisibility();
        applyBarMetrics();
    }

    /**
     * Works out both boxes for a window of this size. Called from measure and
     * layout with the same numbers, so they cannot disagree.
     */
    private void arrange(int width, int height) {
        boolean landscape = width > height;
        float aspect = keyboard != null ? keyboard.aspect()
                                        : SpectrumKeyboardView.NATURAL_ASPECT;

        // Whether there is a keyboard in this window to leave room for. Four
        // ways there is not, and they are the same question whichever way up
        // the device is: the user has put it away; fullscreen, which is the
        // point of it - reserving its share of the height while hiding it would
        // leave the picture the size it was with a band of black where the keys
        // had been; it is lent to a second screen; or the skin is Android's own
        // keyboard, which comes up over the window whenever it is asked to.
        boolean keys = keyboardHere();

        panelBox.set(0, 0, width, height);

        // The bar first, because the screen starts underneath it.
        measureBar(width, height);

        // The strip the bar keeps for itself is its icons, not whatever it has
        // opened underneath them: a group's list is over the picture for as
        // long as it is open, where moving the machine down and up again every
        // time one is looked at is a picture that will not sit still.
        barStrip = fullscreen || menuBox.isEmpty()
                ? 0 : menuBox.top + menu.rowHeight()
                      + Math.round(BAR_GAP * getResources()
                            .getDisplayMetrics().density);

        int top = barStrip;

        if (!keys) {
            keyboardBox.setEmpty();

            // The box is the picture's own height, never more, so the picture
            // goes to the top of the window and every pixel left over ends up
            // in one piece below it.
            //
            // The renderer centres the picture in whatever box it is given, so
            // a box the height of the window put a 4:3 picture in the middle of
            // an upright one with a band of black above it as well as below -
            // and the band below is the half that is worth having: it is where
            // a hand is, and where the joystick goes. Sideways this changes
            // nothing, the picture being taller than the window at that width.
            //
            // It also covers the system keyboard, which takes the bottom of the
            // window while it is up: the room is simply smaller.
            int room = height - imeInset - top;
            int tall = Math.min(room, Math.round(width / SCREEN_ASPECT));

            screenBox.set(0, top, width, top + Math.max(0, tall));
        } else {
            // The box stays the width of the window - the keyboard's own
            // background is the strip across the foot - but the keyboard inside
            // it is only as wide as it is allowed, and the height it asks for
            // follows from that: a box of exactly that height leaves the view's
            // own fitting to centre the picture in it.
            int across = landscape ? Math.round(width * LANDSCAPE_WIDE) : width;
            int natural = Math.round(across / aspect);
            float cap = landscape ? LANDSCAPE_BELOW : PORTRAIT_BELOW;
            int keyboardHeight = Math.min(natural, Math.round(height * cap));
            int room = height - keyboardHeight - top;

            // Portrait leaves far more height than a 4:3 picture wants, and
            // the renderer centres inside whatever it is given - which put
            // the screen in the middle with a band of black above it as well
            // as below. Giving the box only the height the picture uses puts
            // it under the bar and leaves the spare space in one place.
            int screenHeight = landscape
                    ? room
                    : Math.min(room, Math.round(width / SCREEN_ASPECT));

            // Clamped at zero, as the no-keyboard branch above does. `room` is
            // what is left after the keyboard and the bar have taken theirs,
            // and in a short enough window - split-screen, or a freeform one -
            // there is nothing left and it goes negative. An inverted screenBox
            // then inverts `picture` in measurePicture(), and placePlay() sizes
            // the play button from a negative width and puts it somewhere off
            // the window entirely, with the picture gone.
            screenBox.set(0, top, width, top + Math.max(0, screenHeight));
            keyboardBox.set(0, height - keyboardHeight, width, height);

            // Room to miss into around the keys that are hardest to hit.
            if (!landscape) {
                int pad = Math.round(KEYBOARD_PAD
                        * getResources().getDisplayMetrics().density);

                keyboardBox.set(pad, keyboardBox.top - pad,
                                width - pad, height - pad);
            }
        }

        // The overlay's rule is fullscreen sideways, and sideways is only known
        // here. A turn that takes the window out of the rule has to put the
        // overlay away, and doing that from inside a measure would ask for
        // another one - so it is posted, and this pass simply leaves the boxes
        // empty.
        if (landscapeNow != landscape) {
            landscapeNow = landscape;
            post(this::applyOverlayVisibility);
        }

        measurePicture(landscape);
        placePlay();
        placeLights();
        placeJoystick(width, height);
        placeKeyButtons();
        placeOverlay(width, height);

        // The joystick stays while the keys are up. It used to stand down,
        // because both wanted the foot of the window and the pad would have sat
        // behind the keyboard, unreachable; now the controls are at 59% and the
        // slim keyboard is under 300 pixels tall, so they never meet - and a
        // game that wants a key *and* a stick can have both.

        // Set here rather than in placeJoystick, which returns from three
        // places; alpha is a draw property, so this is safe during a measure.
        // Every control that floats, not just the two that were remembered: the
        // three key buttons stayed at full strength beside a fire button at
        // half, which read as a heavier ring rather than as a brighter one. The
        // overlay's buttons go with them, sitting where the pad does.
        float alpha = joystickFloating ? FLOATING_ALPHA : 1f;

        // The palette goes with the alpha, and for the same reason: floating
        // means the picture is what is behind the control, and the translucent
        // set is unreadable over it - 1.04:1 against white paper once this
        // alpha has been applied as well. JoystickView.setOverPicture explains
        // why opacity rather than a darker scrim is the answer.
        if (pad != null) { pad.setAlpha(alpha); pad.setOverPicture(joystickFloating); }
        if (fire != null) { fire.setAlpha(alpha); fire.setOverPicture(joystickFloating); }

        // this.keys, since a local of the same name says whether a keyboard has
        // a place in this window.
        for (JoystickView key : this.keys) {
            key.setAlpha(alpha);
            key.setOverPicture(joystickFloating);
        }

        if (overlayOpen != null) overlayOpen.setAlpha(alpha);
        if (overlayClose != null) overlayClose.setAlpha(alpha);

        restyleOverlayButton(overlayOpen, joystickFloating);
        restyleOverlayButton(overlayClose, joystickFloating);
    }

    /**
     * The overlay keyboard's two buttons take the joystick's palette, because
     * they sit among the controls and share whatever those are sitting on.
     * Held here as plain Views - the activity builds them - so the background
     * and the glyph tint are set rather than a method called on a known type.
     */
    private void restyleOverlayButton(View button, boolean overPicture) {
        if (button == null) return;

        button.setBackground(JoystickView.disc(
                getResources().getDisplayMetrics().density, overPicture));

        if (button instanceof android.widget.ImageView) {
            ((android.widget.ImageView) button)
                    .setColorFilter(JoystickView.markColour(overPicture));
        }
    }

    /**
     * Fills in {@link #picture} for the screen box just decided.
     *
     * The same sum android_gl.c's place() does, and it has to stay that way:
     * a whole-pixel scale is used if it fits, reduced until it does, and the
     * picture fitted to the box if not even one to one will go. The floor
     * division for the offset is deliberate too - both sides land on the same
     * pixel that way.
     *
     * One difference, and it only shows on a Timex in its hi-res mode: that
     * doubles the emulated frame, so the renderer's 1x is twice the size of the
     * one worked out here and the controls sit a little further out than they
     * need to.
     */
    private void measurePicture(boolean landscape) {
        int wanted = landscape ? scaleLandscape : scalePortrait;
        int wide, tall;

        while (wanted > 1 && (wanted * sourceWidth > screenBox.width()
                              || wanted * sourceHeight > screenBox.height())) {
            wanted--;
        }

        if (wanted >= 1 && wanted * sourceWidth <= screenBox.width()
                        && wanted * sourceHeight <= screenBox.height()) {
            wide = wanted * sourceWidth;
            tall = wanted * sourceHeight;
        } else {
            wide = Math.min(screenBox.width(),
                            Math.round(screenBox.height() * SCREEN_ASPECT));
            tall = Math.round(wide / SCREEN_ASPECT);
        }

        int left = screenBox.left + (screenBox.width() - wide) / 2;
        int top = screenBox.top + (screenBox.height() - tall) / 2;

        picture.set(left, top, left + wide, top + tall);
    }

    /**
     * The play button goes in the middle of the picture, which is the one place
     * nothing else wants and the first place anybody looks.
     */
    private void placePlay() {
        playBox.setEmpty();
        if (play == null || play.getVisibility() == GONE) return;

        int size = Math.round(Math.min(picture.width(), picture.height())
                              * PLAY_OF_PICTURE);

        playBox.set(picture.centerX() - size / 2, picture.centerY() - size / 2,
                    picture.centerX() - size / 2 + size,
                    picture.centerY() - size / 2 + size);
    }

    /**
     * The lamps go against the edge of the picture: a row under it in
     * portrait, a column beside it in landscape.
     *
     * Against the <em>picture</em> and not the window, so they stay beside the
     * thing they describe whatever size it comes out. Which way round they run is
     * the strip's own decision, so this only asks how big it wants to be.
     *
     * They are placed first and {@link #placeJoystick} keeps clear of them: both
     * want the space under the picture in portrait, and the joystick is the one
     * with somewhere else to go.
     */
    private void placeLights() {
        lightsBox.setEmpty();
        if (!here(lights)) return;

        lights.measure(MeasureSpec.makeMeasureSpec(screenBox.width(), MeasureSpec.AT_MOST),
                       MeasureSpec.makeMeasureSpec(screenBox.height(), MeasureSpec.AT_MOST));

        int wide = lights.getMeasuredWidth();
        int tall = lights.getMeasuredHeight();
        int gap = Math.round(BAR_GAP * getResources().getDisplayMetrics().density);

        /*
         * The one lamp fullscreen keeps goes exactly where the strip goes -
         * under the picture upright, beside it sideways - so it is where anyone
         * who has seen the lamps already looks.
         *
         * Its axis has to be asked of the window rather than read off the box,
         * though: the strip is long and thin and its shape says which way it
         * runs, and one lamp is square and says nothing.
         *
         * It takes no room from anything, unlike the strip. See {@link
         * #lampsTakeRoom} - the joystick ignores this box while it holds the one
         * lamp, because reserving the space would move the joystick for
         * something that is invisible almost all of the time.
         */
        boolean landscape = lights.diskOnly() ? landscapeNow : wide < tall;

        if (landscape) {
            // Down the left of the picture, from the top: the joystick's bar
            // sits at the bottom of the same strip of black.
            int left = picture.left - gap - wide;
            if (left < 0) left = picture.left + gap;

            lightsBox.set(left, picture.top + gap, left + wide, picture.top + gap + tall);
        } else {
            int left = picture.left + ( picture.width() - wide ) / 2;

            lightsBox.set(left, picture.bottom + gap, left + wide,
                          picture.bottom + gap + tall);
        }
    }

    /**
     * Whether the lamps are something the joystick has to keep clear of.
     *
     * The strip is: it is seven lamps against the edge of the picture and it is
     * there for as long as it is wanted. The one lamp fullscreen keeps is not -
     * it is drawn while a drive is turning and at no other time, so reserving
     * its height would move the joystick permanently for something almost never
     * on screen. It is small, it is at the edge, and on the rare occasion both
     * are up at once they are simply near each other.
     */
    private boolean lampsTakeRoom() {
        return !lightsBox.isEmpty() && !(lights != null && lights.diskOnly());
    }

    /**
     * Finds the joystick somewhere that is not the picture: the black bars beside
     * it, the band below it, or — with neither — floating over the bottom corners.
     *
     * Which applies falls out of the arrangement rather than being written down
     * per arrangement. Two things are worth knowing:
     *
     * The pad takes the width <em>outside</em> the lamps, not the height below
     * them. Ducking under them cost the largest space on offer, because they are a
     * narrow column reaching most of the way down the picture.
     *
     * The controls go against the keyboard rather than centred in the band —
     * that is where a thumb rests, and centring left them in the middle of nowhere
     * when the band was tall.
     */
    private void placeJoystick(int width, int height) {
        padBox.setEmpty();
        fireBox.setEmpty();
        fireArea.setEmpty();
        joystickFloating = false;

        if (pad == null || fire == null || !joystick || suppressed) return;

        float density = getResources().getDisplayMetrics().density;
        int margin = Math.round(PAD_MARGIN * density);
        int minimum = Math.round(PAD_MINIMUM * density);
        int wanted = Math.round(PAD_SIZE * density);

        // The keyboard is a floor: it lies across the foot of the window and a
        // thumb cannot reach through it. So is the system keyboard - not a
        // child of this layout, but it covers the bottom just the same.
        int floor = keyboardBox.isEmpty()
                ? height - Math.max(imeInset, gestureInset)
                : keyboardBox.top;

        // 1. The black bars beside the picture, outside the lamps.
        //
        // The left bar stops at whichever of the two is further out. In
        // landscape the lamps are a vertical strip against the picture's left
        // edge, so that leaves the pad the rest of the bar's width and all of
        // its height; in portrait they are under the picture and do not narrow
        // the bar at all. Either way the right bar is untouched, which is why
        // the two are measured separately - fire would otherwise be pushed out
        // towards the window's edge by however much the lamps took.
        int leftBar = (lampsTakeRoom() ? Math.min(picture.left, lightsBox.left)
                                       : picture.left)
                      - screenBox.left;

        int barTop = screenBox.top;
        int barBottom = Math.min(screenBox.bottom, floor);
        int size = Math.min(wanted, leftBar - 2 * margin);

        if (size >= minimum && barBottom - barTop >= size + 2 * margin) {
            int centreY = comfortableY(height, barTop, barBottom, size, margin);
            int fireSize = Math.round(size * FIRE_OF_PAD);

            // The right bar reaches the window's edge unless the quick bar is
            // in the way at this height. Reserving the bar's whole height
            // unconditionally left fire a strip barely wider than itself, which
            // shrank the three key buttons past thirty dp and dropped them.
            int rightEdge = screenBox.right;
            if (!menuBox.isEmpty() && menuBox.left > picture.right
                    && menuBox.bottom + margin > centreY - size / 2) {
                rightEdge = Math.min(rightEdge, menuBox.left - margin);
            }

            int rightBar = rightEdge - picture.right;

            square(padBox, screenBox.left + leftBar / 2, centreY, size);
            square(fireBox, rightEdge - rightBar / 2, centreY, fireSize);
            fireArea.set(picture.right, barTop, rightEdge, barBottom);
            return;
        }

        // 2. The band between the picture and the keyboard - or between the
        // lamps and the keyboard, since in portrait they are in it. Below the
        // picture, not below its box: with the keyboard hidden the box is the
        // whole window and the picture sits in the middle of it, so the two
        // are not the same edge.
        int underPicture = lampsTakeRoom()
                ? Math.max(picture.bottom, lightsBox.bottom) : picture.bottom;
        size = Math.min(wanted, floor - underPicture - 2 * margin);

        if (size >= minimum && size <= (width - 2 * margin) / 2) {
            // The same height as the other two arrangements, which in a band
            // only as tall as the controls comes out against the keyboard -
            // where a thumb rests, and where this used to be pinned outright.
            // Upright with the keyboard away the band is half the window, and
            // pinning put the controls at ninety per cent of a ten-inch tablet:
            // under the hand holding it rather than in it.
            strip(screenBox.left, screenBox.right,
                  comfortableY(height, underPicture, floor, size, margin),
                  size, margin);
            fireArea.set(screenBox.left, underPicture, screenBox.right, floor);
            return;
        }

        // 3. Nowhere left: floating over the picture, at the same height as
        // anywhere else. This is the case a 4:3 picture on a 16:10 tablet
        // actually lands in - the bars either side are too narrow for a pad, so
        // it floats - and it was the one branch still pinning the controls to
        // the foot of the screen, which is what "the joystick is at the bottom
        // again" was. Over the picture the cost is covering a little more of
        // the game; they are translucent here, and a thumb that has to curl
        // under the tablet to reach the stick is the worse trade.
        joystickFloating = true;
        size = Math.max(minimum, Math.min(wanted, picture.width() / 4));
        strip(picture.left, picture.right,
              comfortableY(height, picture.top, picture.bottom, size, margin),
              size, margin);
        fireArea.set(picture);
    }

    /**
     * A little below the middle of the window, kept inside the space given.
     *
     * That is where a hand holding a tablet is. The foot of the window is below
     * it - reaching there means curling a thumb under the device rather than
     * resting it - and sideways there is no shortage of height to spend.
     *
     * The clamp is what makes one rule fit every arrangement: a space too short
     * to hold the controls that far up simply gets them as far up as it can,
     * which for a narrow band is exactly where they used to be.
     */
    private int comfortableY(int height, int top, int bottom,
                             int size, int margin) {
        int wanted = Math.round(height * PAD_HEIGHT);

        return Math.max(top + margin + size / 2,
                        Math.min(wanted, bottom - margin - size / 2));
    }

    /**
     * The three key buttons, in an arc on the inboard side of fire.
     *
     * Inboard because that is the side with room: fire is at the far end of
     * whatever space the joystick found, so outboard of it is the window's edge.
     * In profile order, at a radius that clears both rings.
     *
     * Two things can be in the way and both are handled by moving, not by giving
     * up. Reaching past fire's own space slides the cluster outboard, where fire's
     * centring usually leaves slack. Reaching the pad shrinks the buttons; below
     * thirty dp they are not worth aiming at and are dropped.
     */
    private void placeKeyButtons() {
        for (Rect box : keyBoxes) box.setEmpty();

        if (keys.length == 0 || fireBox.isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        int margin = Math.round(PAD_MARGIN * density);
        int gap = Math.round(KEY_GAP * density);
        int least = Math.round(KEY_MINIMUM * density);

        int fireRadius = fireBox.width() / 2;
        int size = Math.round(fireBox.width() * KEY_OF_FIRE);
        int centreX = fireBox.centerX();
        int centreY = fireBox.centerY();

        // Two limits inboard, and only one of them is hard.
        //
        // The pad is another control, and an arc reaching it would put two
        // things under the same thumb - so that one is never crossed. The edge
        // of the black is the other, and it is a preference: what lies beyond
        // it is the picture, and a button over the corner of a picture is what
        // the floating joystick already does where there is no black at all.
        int wanted = fireArea.left + (joystickFloating ? 0 : margin);
        int inboard = padBox.isEmpty() ? 0 : padBox.right + margin;

        // The middle button of the three reaches furthest: fire's radius, the
        // gap, then the whole button.
        int reach = fireRadius + gap + size;
        int slack = fireArea.right - margin - fireBox.right;

        if (centreX - reach < wanted && slack > 0) {
            // Fire moves, not the arc around it. Sliding the arc alone leaves
            // it off centre from the button it belongs to, and that is visible
            // at both ends: the level button closes onto fire's rim - ten pixels
            // *over* it on a phone in fullscreen, where the strip is barely
            // wider than fire - while the top one drifts a gap away. Whatever
            // slack fire's centring left is room for fire.
            //
            // Against the edge of the black rather than the pad: this is what
            // keeps the arc off the picture where the black can hold it, and
            // going further would only push fire out of its own strip.
            fireBox.offset(Math.min(slack, wanted - (centreX - reach)), 0);
            centreX = fireBox.centerX();
        }

        // Still short, so the arc lies over the edge of the picture rather than
        // shrinking out of existence. That is not a new liberty: where there is
        // no black at all the whole joystick already floats over the corners,
        // and this is the same trade for a few pixels of it.
        //
        // It is worth taking because the alternative was losing all three. Fire
        // is sized from the pad, which is sized from the bar on the *other*
        // side of the picture, and nothing in that sum knows an arc has to fit
        // beside it here - so a 4:3 picture at a whole multiple on a 16:10
        // tablet left the arc a few pixels short of being worth tapping.
        //
        // What it may not reach is the pad, which is another control: two
        // things wanting the same thumb is worse than one lying over a corner
        // of the picture. So that limit stays hard, and only the picture gives.
        if (centreX - reach < inboard) {
            size -= inboard - (centreX - reach);
            if (size < least) return;
            reach = fireRadius + gap + size;
        }

        int distance = fireRadius + gap + size / 2;

        // Turned up out of fire's way: level with it, the lowest of the three sat
        // where a thumb arrives at fire itself. That wants room above, which a
        // narrow strip of black may not have, so the turn is as much as fits -
        // a full step, half a step, or none.
        for (double turn : new double[] { Math.PI / 4, Math.PI / 8, 0 }) {
            boolean fits = true;

            for (int i = 0; i < keyBoxes.length; i++) {
                // Measured from the inboard horizontal, positive upwards, one
                // eighth turn apart, top of the arc first.
                double angle = turn + ( 1 - i ) * Math.PI / 4;
                int x = centreX - (int) Math.round( Math.cos( angle ) * distance );
                int y = centreY - (int) Math.round( Math.sin( angle ) * distance );

                square(keyBoxes[i], x, y, size);

                if (y - size / 2 < fireArea.top || y + size / 2 > fireArea.bottom) {
                    fits = false;
                }
            }

            // Floating over the picture there is no band to fit inside, so the
            // first and largest turn is taken.
            if (fits || joystickFloating) return;
        }

        // Not even the old arc fits: below thirty dp they are not worth aiming
        // at, and a button off the bottom of the window is worth less again.
        for (Rect box : keyBoxes) box.setEmpty();
    }

    /** The pad at one end of a strip and the fire button at the other. */
    private void strip(int left, int right, int centreY, int size, int margin) {
        int fireSize = Math.round(size * FIRE_OF_PAD);

        square(padBox, left + margin + size / 2, centreY, size);
        square(fireBox, right - margin - fireSize / 2, centreY, fireSize);
    }

    private static void square(Rect out, int centreX, int centreY, int size) {
        out.set(centreX - size / 2, centreY - size / 2,
                centreX - size / 2 + size, centreY - size / 2 + size);
    }

    /**
     * A touch anywhere but the bar shuts whatever group it has open.
     *
     * Intercepting rather than listening, because everything in here that could
     * be pressed - the picture, the keys, the joystick - consumes its own
     * touches and a listener on the parent would never hear them. It looks and
     * returns false, so the press goes on to whichever child it landed on.
     */
    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
        if (here(menu)) menu.collapseIfOutside(event);

        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        // Everything is arranged inside the safe area and shifted into place by
        // placeChild, so no branch of arrange() has to know a cutout exists.
        arrange(width - safe.left - safe.right, height - safe.top - safe.bottom);

        measureChild(screen, screenBox);
        measureChild(keyboard, keyboardBox);
        measureChild(system, systemBox);
        measureChild(pad, padBox);
        measureChild(fire, fireBox);
        for (int i = 0; i < keys.length; i++) measureChild(keys[i], keyBoxes[i]);
        measureChild(overlay, overlayBox);
        measureChild(overlayOpen, overlayOpenBox);
        measureChild(overlayClose, overlayCloseBox);
        measureChild(panel, panelBox);
        measureChild(drawer, panelBox);
        measureChild(lights, lightsBox);
        measureChild(play, playBox);
        measureChild(menu, menuBox);

        setMeasuredDimension(width, height);
    }

    /**
     * The bar is the one child that decides its own size — how many icons it has,
     * and whether a group is open, change as it is used — so it is asked, then
     * hung off the window's top right corner.
     *
     * The window's corner and not the picture's: the bar is on screen the whole
     * time, so the screen starts below it instead. See the strip {@link #arrange}
     * reserves. In fullscreen it overlaps again, because there it is gone until
     * the picture is tapped and a layout that shifted for a control about to fade
     * would be worse.
     */
    private void measureBar(int width, int height) {
        menuBox.setEmpty();
        if (!here(menu)) {
            menuBox.setEmpty();
            return;
        }

        menu.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                     MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));

        int gap = Math.round(BAR_GAP * getResources().getDisplayMetrics().density);
        int wide = menu.getMeasuredWidth();
        int tall = menu.getMeasuredHeight();

        menuBox.set(width - gap - wide, gap, width - gap, gap + tall);
    }

    private void measureChild(View child, Rect box) {
        if (!here(child)) return;

        child.measure(MeasureSpec.makeMeasureSpec(box.width(), MeasureSpec.EXACTLY),
                      MeasureSpec.makeMeasureSpec(box.height(), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // The boxes come from onMeasure, which always runs first with the same
        // size; recomputing here would only risk the two drifting apart.
        placeChild(screen, screenBox);
        placeChild(keyboard, keyboardBox);
        placeChild(system, systemBox);
        placeChild(pad, padBox);
        placeChild(fire, fireBox);
        for (int i = 0; i < keys.length; i++) placeChild(keys[i], keyBoxes[i]);
        placeChild(overlay, overlayBox);
        placeChild(overlayOpen, overlayOpenBox);
        placeChild(overlayClose, overlayCloseBox);
        placeChild(lights, lightsBox);
        placeChild(play, playBox);
        placeChild(panel, panelBox);
        placeChild(menu, menuBox);
        placeChild(drawer, panelBox);
    }

    private void placeChild(View child, Rect box) {
        if (!here(child)) return;

        // arrange() worked from 0,0 within the safe area; this is where that
        // becomes a position in the window.
        child.layout(box.left + safe.left, box.top + safe.top,
                     box.right + safe.left, box.bottom + safe.top);
    }
}
