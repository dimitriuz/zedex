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
 * A third child — the on-screen joystick — belongs here too when it arrives;
 * each template decides where it goes.
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

    /** The emulated screen, border and all: 320x240 whatever the machine. */
    private static final float SCREEN_ASPECT = 4f / 3f;

    /** The ☰ button, and the gap it keeps from the corner, in dp. */
    private static final int MENU_SIZE = 48;

    private final Rect screenBox = new Rect();
    private final Rect keyboardBox = new Rect();
    private final Rect panelBox = new Rect();
    private final Rect menuBox = new Rect();

    private View screen;
    private View panel;
    private View menu;
    private View drawer;
    private SpectrumKeyboardView keyboard;
    private Template template = Template.BELOW;

    EmulatorLayout(Context context) {
        super(context);
        setBackgroundColor(0xff000000);
    }

    /**
     * The children, in the order they are added; none is ever removed.
     *
     * The panel is the ROMs message and takes the whole window rather than the
     * screen's share of it - it is a takeover, not part of the picture. The ☰
     * button is last so it stays reachable over the panel, and sits at the top
     * right of the screen rather than of the window, so it follows the picture
     * when the keyboard is beside it.
     */
    void setChildren(View screen, SpectrumKeyboardView keyboard, View panel,
                     View menuButton, View drawer) {
        this.screen = screen;
        this.keyboard = keyboard;
        this.panel = panel;
        this.menu = menuButton;
        this.drawer = drawer;

        // Front to back is the order below: the drawer covers everything, the
        // button stays over the panel, the panel covers the screen.
        addView(screen);
        addView(keyboard);
        addView(panel);
        addView(menuButton);
        addView(drawer);
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

        keyboard.setVisibility(landscape && template == Template.NONE ? GONE : VISIBLE);
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
        Template current = landscape ? template : Template.BELOW;

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

        int size = Math.round(MENU_SIZE * getResources().getDisplayMetrics().density);
        int gap = size / 4;
        menuBox.set(screenBox.right - gap - size, screenBox.top + gap,
                    screenBox.right - gap, screenBox.top + gap + size);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        arrange(width, height);

        measureChild(screen, screenBox);
        measureChild(keyboard, keyboardBox);
        measureChild(panel, panelBox);
        measureChild(menu, menuBox);
        measureChild(drawer, panelBox);

        setMeasuredDimension(width, height);
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
        placeChild(panel, panelBox);
        placeChild(menu, menuBox);
        placeChild(drawer, panelBox);
    }

    private void placeChild(View child, Rect box) {
        if (child == null || child.getVisibility() == GONE) return;

        child.layout(box.left, box.top, box.right, box.bottom);
    }
}
