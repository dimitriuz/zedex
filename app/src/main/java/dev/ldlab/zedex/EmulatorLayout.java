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
        RIGHT("right");

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

    private final Rect screenBox = new Rect();
    private final Rect keyboardBox = new Rect();

    private View screen;
    private SpectrumKeyboardView keyboard;
    private Template template = Template.BELOW;

    EmulatorLayout(Context context) {
        super(context);
        setBackgroundColor(0xff000000);
    }

    /** Both children, in the order they are added; neither is ever removed. */
    void setChildren(View screen, SpectrumKeyboardView keyboard) {
        this.screen = screen;
        this.keyboard = keyboard;
        addView(screen);
        addView(keyboard);
    }

    Template template() {
        return template;
    }

    void setTemplate(Template template) {
        if (this.template == template) return;

        this.template = template;
        requestLayout();
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

        switch (current) {
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

                screenBox.set(0, 0, width, height - keyboardHeight);
                keyboardBox.set(0, height - keyboardHeight, width, height);
                break;
            }
        }

        if (keyboard != null) {
            keyboard.setAlpha(current == Template.OVERLAY ? OVERLAY_ALPHA : 1f);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        arrange(width, height);

        measureChild(screen, screenBox);
        measureChild(keyboard, keyboardBox);

        setMeasuredDimension(width, height);
    }

    private void measureChild(View child, Rect box) {
        if (child == null) return;

        child.measure(MeasureSpec.makeMeasureSpec(box.width(), MeasureSpec.EXACTLY),
                      MeasureSpec.makeMeasureSpec(box.height(), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // The boxes come from onMeasure, which always runs first with the same
        // size; recomputing here would only risk the two drifting apart.
        if (screen != null) {
            screen.layout(screenBox.left, screenBox.top, screenBox.right, screenBox.bottom);
        }
        if (keyboard != null) {
            keyboard.layout(keyboardBox.left, keyboardBox.top,
                            keyboardBox.right, keyboardBox.bottom);
        }
    }
}
