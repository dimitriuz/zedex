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
 * in a tall one — and that is a thumb's width of room the picture was never
 * using. Only when a template leaves none does the joystick float over the
 * picture's bottom corners, and then it is translucent. See
 * {@link #placeJoystick}.
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

    /** The gap the quick bar keeps from the corner of the picture, in dp. */
    private static final int BAR_GAP = 8;

    private final Rect screenBox = new Rect();
    private final Rect keyboardBox = new Rect();
    private final Rect panelBox = new Rect();
    private final Rect menuBox = new Rect();
    private final Rect padBox = new Rect();
    private final Rect fireBox = new Rect();
    private final Rect lightsBox = new Rect();
    private final Rect playBox = new Rect();

    /**
     * Where the 4:3 picture actually lands inside {@link #screenBox}. The
     * renderer centres it in whatever box it is given, so this is the same sum
     * it does — and the rest of the box is black, which is where everything
     * that must not cover the picture goes.
     */
    private final Rect picture = new Rect();

    private View screen;
    private View panel;
    private View menu;
    private View drawer;
    private SpectrumKeyboardView keyboard;
    private JoystickView pad;
    private JoystickView fire;
    private ActivityLights lights;
    private View play;
    private Template template = Template.BELOW;

    /** Whether each is wanted at all; the ☰ menu decides. */
    private boolean joystick = true;
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
                     JoystickView pad, JoystickView fire, ActivityLights lights,
                     View play, View panel, View menuButton, View drawer) {
        this.screen = screen;
        this.keyboard = keyboard;
        this.pad = pad;
        this.fire = fire;
        this.lights = lights;
        this.play = play;
        this.panel = panel;
        this.menu = menuButton;
        this.drawer = drawer;

        // Front to back is the order below: the drawer covers everything, the
        // button stays over the panel, the panel covers the screen and the
        // joystick, which sits over the picture when it has to.
        addView(screen);
        addView(keyboard);
        addView(pad);
        addView(fire);
        addView(lights);
        addView(play);
        addView(panel);
        addView(menuButton);
        addView(drawer);

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
     * Gone rather than merely unplaced, for the same reason the keyboard is:
     * the five controls are accessibility nodes, and a screen reader would
     * still find them sitting on top of each other at nowhere.
     */
    private void applyJoystickVisibility() {
        int visibility = joystick ? VISIBLE : GONE;

        if (pad != null) pad.setVisibility(visibility);
        if (fire != null) fire.setVisibility(visibility);
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

        keyboard.setVisibility(!keyboardWanted || hiddenByTemplate ? GONE : VISIBLE);
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        // Hiding is a landscape template, so rotating changes whether it applies.
        applyKeyboardVisibility();
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
        // template that has none, whichever way up the device is.
        Template current = landscape ? template : Template.BELOW;
        if (!keyboardWanted) current = Template.NONE;

        panelBox.set(0, 0, width, height);

        switch (current) {
            case NONE: {
                screenBox.set(0, 0, width, height);
                keyboardBox.setEmpty();
                break;
            }

            case LEFT:
            case RIGHT: {
                int keyboardWidth = Math.round(width * LANDSCAPE_SIDE);
                // Full height: the keyboard centres itself inside whatever it
                // is given, so the screen never has to guess where it sits.
                if (current == Template.LEFT) {
                    keyboardBox.set(0, 0, keyboardWidth, height);
                    screenBox.set(keyboardWidth, 0, width, height);
                } else {
                    screenBox.set(0, 0, width - keyboardWidth, height);
                    keyboardBox.set(width - keyboardWidth, 0, width, height);
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
                int room = height - keyboardHeight;

                // Portrait leaves far more height than a 4:3 picture wants, and
                // the renderer centres inside whatever it is given - which put
                // the screen in the middle with a band of black above it as well
                // as below. Giving the box only the height the picture uses puts
                // it at the top and leaves the spare space in one place.
                int screenHeight = landscape
                        ? room
                        : Math.min(room, Math.round(width / SCREEN_ASPECT));

                screenBox.set(0, 0, width, screenHeight);
                keyboardBox.set(0, height - keyboardHeight, width, height);
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
     *     nothing.</li>
     * <li><b>Below it.</b> Portrait gives the picture only the height it uses
     *     and puts the keyboard at the foot of the window, so what is left is
     *     one wide band between them — the largest space of the three.</li>
     * <li><b>Over it.</b> Only the two side-by-side templates get here: the
     *     screen's half of a landscape window is taller than 4:3 wants, so
     *     there are no side bars, and the keyboard beside it leaves no band.
     *     The controls float in the picture's bottom corners, translucent.</li>
     * </ol>
     */
    private void placeJoystick(int width, int height) {
        padBox.setEmpty();
        fireBox.setEmpty();
        joystickFloating = false;

        if (pad == null || fire == null || !joystick) return;

        float density = getResources().getDisplayMetrics().density;
        int margin = Math.round(PAD_MARGIN * density);
        int minimum = Math.round(PAD_MINIMUM * density);
        int wanted = Math.round(PAD_SIZE * density);

        // A keyboard across the whole width is a floor; one beside the screen
        // takes nothing away from the height.
        boolean keyboardBelow = !keyboardBox.isEmpty()
                && keyboardBox.left == 0 && keyboardBox.right == width;
        int floor = keyboardBelow ? keyboardBox.top : height;

        // 1. The black bars beside the picture. Below the lamps, when they are
        // in the same bar.
        int bar = picture.left - screenBox.left;
        int barTop = lightsBox.isEmpty() ? screenBox.top
                                         : Math.max(screenBox.top, lightsBox.bottom);
        int barBottom = Math.min(screenBox.bottom, floor);
        int size = Math.min(wanted, bar - 2 * margin);

        if (size >= minimum && barBottom - barTop >= size + 2 * margin) {
            int centreY = barBottom - margin - size / 2;
            int fireSize = Math.round(size * FIRE_OF_PAD);

            square(padBox, screenBox.left + bar / 2, centreY, size);
            square(fireBox, screenBox.right - bar / 2, centreY, fireSize);
            return;
        }

        // 2. The band between the picture and the keyboard - or between the
        // lamps and the keyboard, since in portrait they are in it. Below the
        // picture, not below its box: with the keyboard hidden the box is the
        // whole window and the picture sits in the middle of it, so the two
        // are not the same edge.
        int bandTop = lightsBox.isEmpty() ? picture.bottom
                                          : Math.max(picture.bottom, lightsBox.bottom);
        size = Math.min(wanted, floor - bandTop - 2 * margin);

        if (size >= minimum && size <= (width - 2 * margin) / 2) {
            strip(screenBox.left, screenBox.right, (bandTop + floor) / 2,
                  size, margin);
            return;
        }

        // 3. Nowhere left: over the picture's bottom corners.
        joystickFloating = true;
        size = Math.max(minimum, Math.min(wanted, picture.width() / 4));
        strip(picture.left, picture.right,
              picture.bottom - margin - size / 2, size, margin);
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
        measureChild(pad, padBox);
        measureChild(fire, fireBox);
        measureChild(panel, panelBox);
        measureChild(drawer, panelBox);
        measureChild(lights, lightsBox);
        measureChild(play, playBox);
        measureBar();

        setMeasuredDimension(width, height);
    }

    /**
     * The quick bar is the one child that decides its own size: how many icons
     * it has, and whether a group has opened a second row underneath, are its
     * business and change as it is used. It is asked how big it would like to
     * be and then hung off the top right corner of the picture — of the
     * picture rather than of the window, so it follows the screen when the
     * keyboard is beside it, and inside the picture rather than beside it
     * because there is no guarantee of any black to sit in.
     */
    private void measureBar() {
        menuBox.setEmpty();
        if (menu == null || menu.getVisibility() == GONE) return;

        menu.measure(MeasureSpec.makeMeasureSpec(screenBox.width(), MeasureSpec.AT_MOST),
                     MeasureSpec.makeMeasureSpec(screenBox.height(), MeasureSpec.AT_MOST));

        int gap = Math.round(BAR_GAP * getResources().getDisplayMetrics().density);
        int wide = menu.getMeasuredWidth();
        int tall = menu.getMeasuredHeight();

        menuBox.set(screenBox.right - gap - wide, screenBox.top + gap,
                    screenBox.right - gap, screenBox.top + gap + tall);
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
        placeChild(pad, padBox);
        placeChild(fire, fireBox);
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
