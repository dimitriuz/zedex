package dev.ldlab.zedex;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

/**
 * Arranges the emulated screen and the keyboard.
 *
 * A layout of its own rather than nested LinearLayouts, because the useful
 * arrangements are not all the same kind of container: two of them stack, two
 * sit side by side, and one puts the keyboard on top of the screen. Measuring
 * the two children here covers all of it without ever re-parenting them —
 * which matters, since detaching the {@link android.view.SurfaceView} would
 * destroy the surface Fuse is drawing into and cost a handover on every
 * change.
 *
 * The keyboard is one bitmap with a fixed 541x201 aspect. Given any box it
 * scales to fit, centres itself and hit-tests through the same transform, so a
 * box shorter or narrower than its natural shape simply letterboxes and still
 * works. That is what lets a template cap it: without a cap, full width in
 * landscape makes the keyboard 2.7 times as wide as it is tall and it takes
 * four fifths of the height, leaving the machine a letterbox slot.
 *
 * Portrait is always {@link Template#BELOW}: there is only one sensible
 * arrangement when the window is taller than it is wide.
 *
 * The on-screen joystick goes in the black rather than on the picture. The
 * renderer centres a 4:3 quad in whatever box it is given, so there is nearly
 * always spare black somewhere — at the sides of a wide box, below the picture
 * in a tall one, above the keyboard when it is beside the screen —
 * and that is a thumb's width of room the picture was never using. Only when a
 * template leaves none does the joystick float over the picture's bottom
 * corners, and then it is translucent. See {@link #placeJoystick}.
 */
final class EmulatorLayout extends ViewGroup {

    /** How the screen and the keyboard share a landscape window. */
    enum Template {
        /** Keyboard across the bottom, capped so the screen keeps most of it. */
        BELOW("below"),
        /** Keyboard over the screen, translucent, so the screen keeps all of it. */
        OVERLAY("overlay"),
        /** Keyboard down the left, screen to its right. */
        LEFT("left"),
        /** Screen on the left, keyboard down the right. */
        RIGHT("right"),
        /**
         * No keyboard at all: the whole window is the machine. For a physical
         * keyboard, or a game that only wants a joystick. The ☰ button stays
         * over the screen, so this is not a way of getting stuck.
         */
        NONE("none");

        final String value;

        Template(String value) {
            this.value = value;
        }

        /** The stored preference, or {@link #BELOW} for anything unrecognised. */
        static Template of(String stored) {
            if (stored != null) {
                for (Template template : values()) {
                    if (template.value.equals(stored)) return template;
                }
            }
            return BELOW;
        }
    }

    /**
     * Height the keyboard may take in landscape, as a fraction of the window.
     * The screen is 4:3, so at 0.42 it still gets its full width back.
     */
    private static final float LANDSCAPE_BELOW = 0.42f;

    /** Overlaying costs the screen nothing, so the keyboard can be bigger. */
    private static final float LANDSCAPE_OVERLAY = 0.5f;

    /** Side by side: an even split leaves the screen more than it needs. */
    private static final float LANDSCAPE_SIDE = 0.5f;

    /**
     * Portrait cap. The natural height at full width is around a third, so
     * this only ever bites on a very short window.
     */
    private static final float PORTRAIT_BELOW = 0.5f;

    /** Enough to read the screen through, still solid enough to aim at. */
    private static final float OVERLAY_ALPHA = 0.8f;

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

    /** The play button, as a share of the picture's shorter side. */
    private static final float PLAY_OF_PICTURE = 0.28f;

    /** The emulated screen, border and all: 320x240 whatever the machine. */
    private static final int SOURCE_WIDTH = 320;
    private static final int SOURCE_HEIGHT = 240;
    private static final float SCREEN_ASPECT =
            (float) SOURCE_WIDTH / (float) SOURCE_HEIGHT;

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
     * same space. Set by {@link #placeJoystick} in each of its four branches -
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
    private ActivityLights lights;
    private View play;
    private Template template = Template.BELOW;

    /** Whether each is wanted at all; the ☰ menu decides. */
    private boolean joystick = true;

    /**
     * Set while a real controller is plugged in and the on-screen one is to step
     * aside for it. Kept apart from {@link #joystick} rather than writing to it,
     * so that unplugging brings back whatever the user had chosen.
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
    private boolean keyboardWanted = true;

    /**
     * Device pixels per emulated pixel, per orientation, or
     * {@link FuseNative#SCALE_FIT}. The same numbers the renderer has: this is
     * not what draws the picture, but everything placed around the picture needs
     * to know how big it came out.
     */
    private int scalePortrait = FuseNative.SCALE_FIT;
    private int scaleLandscape = FuseNative.SCALE_FIT;

    /** Set by {@link #placeJoystick}: it found no black and used the picture. */
    private boolean joystickFloating;

    EmulatorLayout(Context context) {
        super(context);
        setBackgroundColor(0xff000000);
    }

    /**
     * The children, in the order they are added; none is ever removed.
     *
     * The panel is the ROMs message and takes the whole window rather than the
     * screen's share of it - it is a takeover, not part of the picture. The
     * quick bar is last so it stays reachable over the panel, and sits at the
     * top right of the screen rather than of the window, so it follows the
     * picture when the keyboard is beside it.
     */
    void setChildren(View screen, SpectrumKeyboardView keyboard,
                     SystemKeyboardView system,
                     JoystickView pad, JoystickView fire, JoystickView[] keys,
                     ActivityLights lights,
                     View play, View panel, QuickBar bar, View drawer) {
        this.screen = screen;
        this.keyboard = keyboard;
        this.system = system;
        this.pad = pad;
        this.fire = fire;
        this.keys = keys;
        this.lights = lights;
        this.play = play;
        this.panel = panel;
        this.menu = bar;
        this.drawer = drawer;

        // Front to back is the order below: the drawer covers everything, the
        // button stays over the panel, the panel covers the screen and the
        // joystick, which sits over the picture when it has to.
        addView(screen);
        addView(keyboard);
        addView(system);
        addView(pad);
        addView(fire);
        for (JoystickView key : keys) addView(key);
        addView(lights);
        addView(play);
        addView(panel);
        addView(bar);
        addView(drawer);

        applyBarMetrics();
        applyJoystickVisibility();
    }

    Template template() {
        return template;
    }

    void setTemplate(Template template) {
        if (this.template == template) return;

        this.template = template;
        applyKeyboardVisibility();
        requestLayout();
    }

    /**
     * Redraws every control, for when the profile behind their faces has
     * changed: the pad's four ways, fire and the three buttons all show which
     * key they send.
     *
     * Here rather than in the activity because this is the one place that has
     * all of them - the activity would need three sets of references, and it
     * forgetting one of them is exactly how fire and then the pad came to be
     * showing yesterday's keys.
     */
    void refreshControls() {
        if (pad != null) pad.invalidate();
        if (fire != null) fire.invalidate();

        for (JoystickView key : keys) key.invalidate();
    }

    boolean joystickVisible() {
        return joystick;
    }

    void setJoystickVisible(boolean visible) {
        if (joystick == visible) return;

        joystick = visible;
        applyJoystickVisibility();
        requestLayout();
    }

    /**
     * Whether the picture has the window to itself.
     *
     * The bar stops reserving its strip and starts overlapping the corner as it
     * used to, and the keyboard goes away in landscape - where it costs the
     * picture nearly half the window. In portrait it costs the picture nothing,
     * since a 4:3 image in a tall window is limited by the width and not by the
     * height, so it stays.
     */
    void setFullscreen(boolean on) {
        // No early return on an unchanged value: whether fullscreen hides the
        // keyboard depends on the orientation, so this is also how a rotation
        // gets the rule applied again. Called from onConfigurationChanged for
        // exactly that, and a config change costs a layout anyway.
        fullscreen = on;
        applyKeyboardVisibility();
        requestLayout();
    }

    boolean fullscreen() {
        return fullscreen;
    }

    /** Whether a real controller is standing in for the on-screen one. */
    void setJoystickSuppressed(boolean standingAside) {
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
    void setKeyboardSkin(SpectrumKeyboardView.Skin skin) {
        if (keyboard == null || keyboard.skin() == skin) return;

        keyboard.setSkin(skin);
        applyKeyboardVisibility();
        requestLayout();
    }

    /** The device's own keyboard, for the skin that is not drawn here. */
    SystemKeyboardView systemKeyboard() {
        return system;
    }

    /**
     * How far up the window the system keyboard reaches, so the picture can get
     * out of its way.
     *
     * Told rather than asked, because the insets arrive at the activity and this
     * is a measure input like any other; a change in it is a change of layout.
     */
    void setInsets(int ime, int gestures) {
        if (imeInset == ime && gestureInset == gestures) return;

        imeInset = ime;
        gestureInset = gestures;
        requestLayout();
    }

    boolean keyboardVisible() {
        return keyboardWanted;
    }

    /**
     * Whether the lamps are shown at all. They are a diagnostic, and once you
     * know what a game wants there is nothing left for them to tell you.
     */
    void setLightsVisible(boolean visible) {
        if (lights == null || (lights.getVisibility() == VISIBLE) == visible) return;

        lights.setVisibility(visible ? VISIBLE : GONE);
        requestLayout();
    }

    /**
     * How big the picture is drawn, matching what the renderer was told.
     *
     * Kept in step by hand rather than asked for, because the renderer runs on
     * the emulation thread and this is a layout pass: both apply the same rule
     * to the same box, so both get the same answer.
     */
    void setScale(int portrait, int landscape) {
        if (scalePortrait == portrait && scaleLandscape == landscape) return;

        scalePortrait = portrait;
        scaleLandscape = landscape;
        requestLayout();
    }

    /**
     * Puts the keyboard away, or brings it back, whichever way up the device
     * is. Separate from the landscape template that also hides it: that one is
     * an arrangement of the window, this one is a decision about the keyboard,
     * and rotating should not undo it.
     */
    void setKeyboardVisible(boolean visible) {
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
        if (keyboard == null) return;

        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        boolean hiddenByTemplate = landscape && template == Template.NONE;
        boolean hiddenByFullscreen = landscape && fullscreen;

        keyboard.setVisibility(!keyboardWanted || hiddenByTemplate
                               || hiddenByFullscreen || !keyboard.skin().drawn()
                               ? GONE : VISIBLE);
    }

    /**
     * Full sized across the top in portrait, compact in the corner sideways.
     *
     * The room it is given is the black down the side of a 4:3 picture as wide as
     * the window can make it - the narrowest that black ever gets, since a
     * template that gives the screen less makes the picture smaller and the black
     * wider. Worked out from the display rather than from the boxes, because the
     * bar is measured before the boxes are: in portrait the screen starts
     * underneath it.
     *
     * Set from here rather than during a measure, since changing a child's size
     * asks for another layout.
     */
    private void applyBarMetrics() {
        if (menu == null) return;

        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        if (!landscape) {
            menu.setCompact(0);
            return;
        }

        int across = Math.max(metrics.widthPixels, metrics.heightPixels);
        int down = Math.min(metrics.widthPixels, metrics.heightPixels);
        int beside = ( across - Math.round( down * SCREEN_ASPECT ) ) / 2;

        menu.setCompact(beside - 2 * Math.round(BAR_GAP * metrics.density));
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        // Hiding is a landscape template, so rotating changes whether it applies.
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

        // Portrait has one arrangement; the templates are a landscape question.
        // A keyboard the user has put away leaves the same window as the
        // template that has none, whichever way up the device is - and so does
        // fullscreen, which is the point of it: hiding the keyboard while still
        // reserving its share of the height would leave the picture the size it
        // was with a band of black where the keys had been.
        Template current = landscape ? template : Template.BELOW;
        if (!keyboardWanted || (landscape && fullscreen)) current = Template.NONE;

        // The system keyboard is Android's own and comes up over the window
        // whenever it is asked to, so there is nothing here to leave room for.
        if (keyboard != null && !keyboard.skin().drawn()) current = Template.NONE;

        panelBox.set(0, 0, width, height);

        // The bar first, because in portrait the screen starts underneath it.
        measureBar(width, height);

        int top = landscape || fullscreen || menuBox.isEmpty()
                ? 0 : menuBox.bottom + Math.round(BAR_GAP * getResources()
                        .getDisplayMetrics().density);

        switch (current) {
            case NONE: {
                screenBox.set(0, 0, width, height);
                keyboardBox.setEmpty();

                // Except while the system keyboard is up, which covers the
                // bottom of the window: then the picture takes the space above
                // it and sits at the top of that, rather than staying centred in
                // a window whose lower half it can no longer be seen in.
                if (imeInset > 0) {
                    int room = height - imeInset - top;
                    int tall = Math.min(room, Math.round(width / SCREEN_ASPECT));

                    if (tall > 0) screenBox.set(0, top, width, top + tall);
                }
                break;
            }

            case LEFT:
            case RIGHT: {
                int keyboardWidth = Math.round(width * LANDSCAPE_SIDE);

                // The foot of its half, not the middle of it. Half a landscape
                // window is far wider than the keyboard is tall, so centring it
                // left a band of nothing above and another below; putting it at
                // the bottom makes that one band, in one place, and the
                // joystick goes in it. It is also where a thumb already is.
                int keyboardHeight = Math.min(height,
                        Math.round(keyboardWidth / aspect));

                if (current == Template.LEFT) {
                    keyboardBox.set(0, height - keyboardHeight,
                                    keyboardWidth, height);
                    screenBox.set(keyboardWidth, 0, width, height);
                } else {
                    screenBox.set(0, 0, width - keyboardWidth, height);
                    keyboardBox.set(width - keyboardWidth, height - keyboardHeight,
                                    width, height);
                }
                break;
            }

            case OVERLAY: {
                int natural = Math.round(width / aspect);
                int keyboardHeight = Math.min(natural,
                        Math.round(height * LANDSCAPE_OVERLAY));

                screenBox.set(0, 0, width, height);
                keyboardBox.set(0, height - keyboardHeight, width, height);
                break;
            }

            case BELOW:
            default: {
                int natural = Math.round(width / aspect);
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

                screenBox.set(0, top, width, top + screenHeight);
                keyboardBox.set(0, height - keyboardHeight, width, height);

                // Room to miss into around the keys that are hardest to hit.
                if (!landscape) {
                    int pad = Math.round(KEYBOARD_PAD
                            * getResources().getDisplayMetrics().density);

                    keyboardBox.set(pad, keyboardBox.top - pad,
                                    width - pad, height - pad);
                }
                break;
            }
        }

        if (keyboard != null) {
            keyboard.setAlpha(current == Template.OVERLAY ? OVERLAY_ALPHA : 1f);
        }

        measurePicture(landscape);
        placePlay();
        placeLights();
        placeJoystick(width, height);
        placeKeyButtons();

        // Set here rather than in placeJoystick, which returns from three
        // places; alpha is a draw property, so this is safe during a measure.
        float alpha = joystickFloating ? FLOATING_ALPHA : 1f;
        if (pad != null) pad.setAlpha(alpha);
        if (fire != null) fire.setAlpha(alpha);
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

        while (wanted > 1 && (wanted * SOURCE_WIDTH > screenBox.width()
                              || wanted * SOURCE_HEIGHT > screenBox.height())) {
            wanted--;
        }

        if (wanted >= 1 && wanted * SOURCE_WIDTH <= screenBox.width()
                        && wanted * SOURCE_HEIGHT <= screenBox.height()) {
            wide = wanted * SOURCE_WIDTH;
            tall = wanted * SOURCE_HEIGHT;
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
     * Against the <em>picture</em> and not the window, so they stay with the
     * thing they describe when the keyboard is beside the screen. Which way
     * round they run is the strip's own decision — it reads the orientation —
     * so this only has to ask how big it wants to be, the way the quick bar
     * does.
     *
     * They come first, and {@link #placeJoystick} is told to keep clear of
     * them: both want the space under the picture in portrait, and of the two
     * it is the joystick that has somewhere else to go.
     */
    private void placeLights() {
        lightsBox.setEmpty();
        if (lights == null || lights.getVisibility() == GONE) return;

        lights.measure(MeasureSpec.makeMeasureSpec(screenBox.width(), MeasureSpec.AT_MOST),
                       MeasureSpec.makeMeasureSpec(screenBox.height(), MeasureSpec.AT_MOST));

        int wide = lights.getMeasuredWidth();
        int tall = lights.getMeasuredHeight();
        int gap = Math.round(BAR_GAP * getResources().getDisplayMetrics().density);

        boolean landscape = wide < tall;

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
     * Finds the joystick somewhere that is not the picture.
     *
     * Three answers, tried in order, and which one applies falls out of the
     * template rather than being written down per template:
     *
     * <ol>
     * <li><b>Beside the picture.</b> A 4:3 quad in a wide box leaves a black
     *     bar down each side — 480px of a 2400px landscape window with no
     *     keyboard, and more with one below, because the shorter box makes the
     *     picture narrower. The pad goes low in the left bar and fire low in
     *     the right, where the thumbs already are, and the picture loses
     *     nothing. The lamps hang down the inside of the left bar, so the pad
     *     takes the width outside them rather than the height below them: they
     *     are a narrow strip and treating them as blocking the whole bar cost
     *     the largest space on offer.</li>
     * <li><b>Below it.</b> Portrait gives the picture only the height it uses
     *     and puts the keyboard at the foot of the window, so what is left is
     *     one wide band between them — the largest space of the three.</li>
     * <li><b>Above the keyboard.</b> The two side-by-side templates give the
     *     screen a box taller than 4:3 wants, so there are no side bars, and
     *     the band under the picture is thin. But the keyboard is one bitmap
     *     with a fixed aspect and half a landscape window is far wider than it
     *     is tall, so it sits at the foot of its half and leaves 634px of a
     *     1080px window empty above it. Both controls go there, centred in the
     *     band, since only one half of the window is ours — the pad at one end
     *     and fire at the other.</li>
     * <li><b>Over it.</b> Nothing left: the controls float in the picture's
     *     bottom corners, translucent.</li>
     * </ol>
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

        // A keyboard across the whole width is a floor; one beside the screen
        // takes nothing away from the height. The system keyboard is a floor too
        // - it is not a child of this layout, but it covers the bottom of the
        // window just the same, and a thumb cannot reach through it.
        // Wider than half the window, rather than exactly the whole of it: the
        // portrait keyboard is inset from the edges so that its corner keys can
        // be hit, and testing for 0 to width made that inset read as "no
        // keyboard below" - which put the joystick at the foot of the window,
        // on top of the keys.
        boolean keyboardBelow = !keyboardBox.isEmpty()
                && keyboardBox.width() > width / 2;
        int floor = keyboardBelow ? keyboardBox.top
                                  : height - Math.max(imeInset, gestureInset);

        // 1. The black bars beside the picture, outside the lamps.
        //
        // The left bar stops at whichever of the two is further out. In
        // landscape the lamps are a vertical strip against the picture's left
        // edge, so that leaves the pad the rest of the bar's width and all of
        // its height; in portrait they are under the picture and do not narrow
        // the bar at all. Either way the right bar is untouched, which is why
        // the two are measured separately - fire would otherwise be pushed out
        // towards the window's edge by however much the lamps took.
        int leftBar = (lightsBox.isEmpty() ? picture.left
                                           : Math.min(picture.left, lightsBox.left))
                      - screenBox.left;

        // The bar is a column down the same black sideways, so the right one
        // stops at it: fire is at the bottom of the bar and the column reaches
        // most of the way down.
        int rightEdge = menuBox.isEmpty() || menuBox.left <= picture.right
                ? screenBox.right
                : Math.min(screenBox.right, menuBox.left - margin);
        int rightBar = rightEdge - picture.right;
        int barTop = screenBox.top;
        int barBottom = Math.min(screenBox.bottom, floor);
        int size = Math.min(wanted, leftBar - 2 * margin);

        if (size >= minimum && barBottom - barTop >= size + 2 * margin) {
            int centreY = barBottom - margin - size / 2;
            int fireSize = Math.round(size * FIRE_OF_PAD);

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
        int underPicture = lightsBox.isEmpty()
                ? picture.bottom : Math.max(picture.bottom, lightsBox.bottom);
        size = Math.min(wanted, floor - underPicture - 2 * margin);

        if (size >= minimum && size <= (width - 2 * margin) / 2) {
            // Against the keyboard rather than floating in the middle of the
            // band. That is where a thumb rests, it is what the side bars
            // already do, and centring it left the controls in the middle of
            // nowhere when the band was tall - or over the keys when the band
            // was measured against a window the keyboard was covering.
            strip(screenBox.left, screenBox.right, floor - margin - size / 2,
                  size, margin);
            fireArea.set(screenBox.left, underPicture, screenBox.right, floor);
            return;
        }

        // 3. The band above the keyboard, when it is beside the screen rather
        // than below it. Above by preference and below if that will not have
        // it: the keyboard is centred in its half, so on a phone the two are
        // the same size and either is a whole thumb's width away from the
        // picture.
        if (!keyboardBox.isEmpty() && !keyboardBelow) {
            int bandTop = 0;
            int bandBottom = keyboardBox.top;

            // The lamps are the one thing that can already be in here. They
            // hang down the inside edge of the screen's half, which with the
            // keyboard on the left is this band's far end - fire lands on them
            // otherwise - and with it on the right is nowhere near.
            int left = keyboardBox.left;
            int right = keyboardBox.right;

            if (!lightsBox.isEmpty() && lightsBox.top < bandBottom
                                     && lightsBox.bottom > bandTop) {
                if (lightsBox.centerX() > (left + right) / 2) {
                    right = Math.min(right, lightsBox.left - margin);
                } else {
                    left = Math.max(left, lightsBox.right + margin);
                }
            }

            size = Math.min(wanted, bandBottom - bandTop - 2 * margin);

            if (size >= minimum && size <= (right - left - 2 * margin) / 2) {
                strip(left, right, (bandTop + bandBottom) / 2, size, margin);
                fireArea.set(left, bandTop, right, bandBottom);
                return;
            }
        }

        // 4. Nowhere left: over the picture's bottom corners.
        joystickFloating = true;
        size = Math.max(minimum, Math.min(wanted, picture.width() / 4));
        strip(picture.left, picture.right,
              picture.bottom - margin - size / 2, size, margin);
        fireArea.set(picture);
    }

    /**
     * The three key buttons, in an arc on the inboard side of fire.
     *
     * Round the inboard side because that is the side with room: fire is at the
     * far end of whatever space the joystick found, so outboard of it is the
     * window's edge. Up-left, left and down-left, in profile order top to
     * bottom, at a radius that clears both rings.
     *
     * Two things can be in the way, and both are handled by moving rather than
     * by giving up. If the arc would reach past the space fire was placed in,
     * the whole cluster slides outboard as far as the margin allows - fire is
     * centred in its bar, so there is usually slack there and nothing else
     * wants it. If it would reach the pad, or there is still not enough room,
     * the buttons shrink; below thirty dp they are not worth aiming at and are
     * dropped altogether.
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

        // What the arc must not cross on the inboard side: the pad, and the
        // edge of the space fire is in - which is black by construction, so
        // the picture and the keyboard are covered by the same test.
        int inboard = Math.max(fireArea.left, padBox.isEmpty() ? fireArea.left
                                                              : padBox.right);
        if (!joystickFloating) inboard += margin;

        // The middle button of the three reaches furthest: fire's radius, the
        // gap, then the whole button.
        int reach = fireRadius + gap + size;
        int slack = fireArea.right - margin - fireBox.right;

        if (centreX - reach < inboard && slack > 0) {
            centreX += Math.min(slack, inboard - (centreX - reach));
        }

        if (centreX - reach < inboard) {
            size -= inboard - (centreX - reach);
            if (size < least) return;
            reach = fireRadius + gap + size;
        }

        // Vertically the corner buttons reach less far than the middle one, by
        // the cosine of forty-five degrees, but the box still has to fit.
        int distance = fireRadius + gap + size / 2;
        int corner = Math.round(distance * 0.7071f);

        if (centreY - corner - size / 2 < fireArea.top
                || centreY + corner + size / 2 > fireArea.bottom) {
            if (!joystickFloating) return;
        }

        square(keyBoxes[0], centreX - corner, centreY - corner, size);
        square(keyBoxes[1], centreX - distance, centreY, size);
        square(keyBoxes[2], centreX - corner, centreY + corner, size);
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        arrange(width, height);

        measureChild(screen, screenBox);
        measureChild(keyboard, keyboardBox);
        measureChild(system, systemBox);
        measureChild(pad, padBox);
        measureChild(fire, fireBox);
        for (int i = 0; i < keys.length; i++) measureChild(keys[i], keyBoxes[i]);
        measureChild(panel, panelBox);
        measureChild(drawer, panelBox);
        measureChild(lights, lightsBox);
        measureChild(play, playBox);
        measureChild(menu, menuBox);

        setMeasuredDimension(width, height);
    }

    /**
     * The quick bar is the one child that decides its own size: how many icons it
     * has, and whether a group has opened a second row underneath, are its
     * business and change as it is used. It is asked how big it would like to be
     * and then hung off the <b>window's</b> top right corner.
     *
     * The window's corner and not the picture's, which is where it used to go. It
     * is on screen the whole time now rather than fading after three seconds, and
     * a bar that is always there must not be always over the game: in portrait
     * the screen starts underneath it - see the strip {@link #arrange} reserves -
     * and in landscape the corner is the black beside a 4:3 picture in a wide
     * window, which is nobody's picture. The one arrangement where it still
     * overlaps is a keyboard on the left, where the screen's half reaches the
     * window's right edge and only a thin band is left above it; the corner of
     * the border is what it costs.
     *
     * In fullscreen it goes back to overlapping, because there it is not there at
     * all until the picture is tapped and a layout that shifted for a control
     * about to fade would be worse than the overlap.
     */
    private void measureBar(int width, int height) {
        menuBox.setEmpty();
        if (menu == null || menu.getVisibility() == GONE) return;

        menu.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                     MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));

        int gap = Math.round(BAR_GAP * getResources().getDisplayMetrics().density);
        int wide = menu.getMeasuredWidth();
        int tall = menu.getMeasuredHeight();

        menuBox.set(width - gap - wide, gap, width - gap, gap + tall);
    }

    private void measureChild(View child, Rect box) {
        if (child == null || child.getVisibility() == GONE) return;

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
        placeChild(lights, lightsBox);
        placeChild(play, playBox);
        placeChild(panel, panelBox);
        placeChild(menu, menuBox);
        placeChild(drawer, panelBox);
    }

    private void placeChild(View child, Rect box) {
        if (child == null || child.getVisibility() == GONE) return;

        child.layout(box.left, box.top, box.right, box.bottom);
    }
}
