package dev.ldlab.zedex.welcome;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.view.Palette;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A coach mark: a scrim over the whole window with a hole cut at some real
 * view's bounds, a caption card near the hole, and a <i>Next</i>. One overlay
 * view, added to {@code android.R.id.content} and removed again when it is
 * dismissed - {@link Tour} (a later task) sequences several of these into a
 * guide.
 *
 * <b>The caption is a real {@link TextView} and Next a real {@link Button},
 * not text drawn onto the canvas.</b> This app is driven by gamepad often
 * enough that a control only a finger can work is a control some people do
 * not have, and the test harness and {@code scripts/ui-tap.py} both address
 * things by name - neither can reach a canvas. The caption's text is set once,
 * when the mark is built, and never touched again: nothing on screen may
 * change its {@code contentDescription} continuously, since each change is a
 * window-content-changed event and the accessibility tree never settles - the
 * activity lamps did exactly this once and took the whole UI Automator suite
 * down.
 *
 * <b>Every touch, key and motion event is consumed while a mark is up.</b>
 * {@link #onTouchEvent} always returns true, so a tap on the scrim cannot
 * reach whatever it is drawn over, and {@link #dispatchKeyEvent} swallows
 * everything except the one that activates Next - so nothing behind is
 * half-pressed and no {@code GamepadCursor} moves a cursor or steals focus
 * mid-mark. {@link #dispatchGenericMotionEvent} does the same for a
 * gamepad's stick or hat: that class of input often arrives as an axis
 * rather than a key - see the project's own note on this, that falling
 * through to the view tree "looks like the tidier fix and leaves the D-pad
 * dead" - and on the machine's own screen an unclaimed one would reach
 * {@code EmulatorActivity}'s joystick handling and move the emulated
 * joystick, or move a list's selection on a screen that uses {@code
 * GamepadCursor}, either of them behind the scrim a mark is supposed to be
 * the only thing in front of.
 *
 * <b>Both of those only work while {@link #next} actually holds focus</b> -
 * {@code ViewGroup} routes a key event to whichever child is {@code
 * mFocused}, exactly the way it routes generic motion, so a mark whose
 * button never took focus never had either defence: not only was a
 * gamepad's hat free to move a selection behind the scrim, {@code
 * dispatchKeyEvent} itself was never being asked, so a real keyboard's
 * Enter or a gamepad's own A could not activate Next either - the only way
 * through was an actual touch on the visible button, whose hit-testing does
 * not depend on focus. See {@link #next}'s own comment for why that was true
 * of every mark this app has shown, until now.
 *
 * <b>Back is a third path, and neither of the above reaches it.</b> From API
 * 33 a predictive-back gesture is not a {@link KeyEvent} at all - it is
 * delivered to whichever {@code OnBackInvokedCallback} is registered with
 * the highest priority, entirely apart from the view tree {@link
 * #dispatchKeyEvent} and {@link #dispatchGenericMotionEvent} answer for. A
 * mark that only swallows keys and motion still lets a back gesture pop
 * whatever is underneath it - see {@link #claimBack}.
 *
 * <b>The hole needs a software layer to punch through.</b> {@link
 * PorterDuff.Mode#CLEAR} zeroes the alpha of whatever this view has already
 * drawn - the scrim - so it can reveal what is beneath; drawn straight onto a
 * hardware-accelerated canvas that operation has nothing of ours underneath it
 * to reveal (the layer is composited over the rest of the window afterwards)
 * and just leaves a black hole. {@link #setLayerType} to {@code
 * LAYER_TYPE_SOFTWARE} renders this view's own drawing into an off-screen
 * bitmap first, where the clear paint works as intended, and that bitmap is
 * then composited as one texture like anything else.
 */
public final class Coach extends FrameLayout {

    /** Space kept between the hole's edge and the target's real bounds. */
    private static final int RING_DP = 8;

    /** Space between the hole and the caption card. */
    private static final int GAP_DP = 16;

    /** The card's distance from the left and right edges of the window. */
    private static final int MARGIN_DP = 16;

    private static final int CARD_RADIUS_DP = 14;
    private static final int HOLE_RADIUS_DP = 12;
    private static final int CARD_PAD_DP = 16;
    private static final int BUTTON_GAP_DP = 12;

    private static final int CARD_STROKE = 0x1affffff;

    /** Text on {@link Cards#CYAN} - the same pairing {@code Cards.choiceOf}
     *  uses for its own leading row, kept here rather than exposed there for
     *  one more caller. */
    private static final int ON_CYAN = 0xff05222a;

    private final View target;
    private final Runnable onNext;

    private final Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect hole = new Rect();
    private final RectF holeF = new RectF();

    private final int ringPx;
    private final int gapPx;
    private final int marginPx;
    private final int holeRadiusPx;

    private final LinearLayout card;
    private final Button next;

    /** Registered by {@link #claimBack}, released by {@link #releaseBack} -
     *  null on API below 33, and null between the two calls on every other
     *  build. */
    private android.window.OnBackInvokedCallback backCallback;

    private Coach(Activity activity, View target, CharSequence caption,
                  boolean last, Runnable onNext) {
        super(activity);
        this.target = target;
        this.onNext = onNext;

        float density = getResources().getDisplayMetrics().density;
        ringPx = Math.round(RING_DP * density);
        gapPx = Math.round(GAP_DP * density);
        marginPx = Math.round(MARGIN_DP * density);
        holeRadiusPx = Math.round(HOLE_RADIUS_DP * density);
        int cardRadiusPx = Math.round(CARD_RADIUS_DP * density);
        int cardPadPx = Math.round(CARD_PAD_DP * density);
        int buttonGapPx = Math.round(BUTTON_GAP_DP * density);

        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(cardPadPx, cardPadPx, cardPadPx, cardPadPx);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Cards.CARD);
        background.setCornerRadius(cardRadiusPx);
        background.setStroke(Math.max(1, cardRadiusPx / 8), CARD_STROKE);
        card.setBackground(background);

        TextView captionView = new TextView(activity);
        captionView.setText(caption);
        captionView.setTextSize(15);
        captionView.setTextColor(Palette.TEXT);
        captionView.setLineSpacing(Math.round(4 * density), 1f);
        card.addView(captionView);

        next = new Button(activity);

        // A plain clickable View is focusable, but not focusable in touch
        // mode - and touch mode is exactly the state this app is in every
        // time a mark appears, since a guide always follows a tap or a
        // launch. Without this, the requestFocus() call in show() below
        // silently does nothing - and both {@link #dispatchKeyEvent} and
        // {@link #dispatchGenericMotionEvent} depend on it, not only the
        // second: ViewGroup routes a key event to whichever child is {@code
        // mFocused} exactly as it routes generic motion, so a mark that
        // never took focus was never being asked about either kind of
        // event. That is a bigger gap than a gamepad's stick or hat moving a
        // selection behind the scrim - Coach's own Next was not reliably
        // activatable from a real keyboard's Enter or a gamepad's A either,
        // since a touch is the one input class that reaches a view by
        // hit-testing rather than by focus and so was never affected.
        //
        // Measured rather than assumed, instrumenting the actually-focused
        // view: on {@code LibraryActivity}, where nothing else asks for
        // focus first, this line is the whole fix - confirmed, Coach's own
        // button now holds it for as long as the mark is up. On {@code
        // EmulatorActivity} the same request succeeds too, equally
        // confirmed - but {@code Coach.dismiss}'s own {@code removeView}
        // hands focus straight back to {@code EmulatorLayout} the instant a
        // mark is taken down (Android's own {@code rootViewRequestFocus},
        // automatic whenever a focused view is detached - confirmed by a
        // full stack trace, not inferred), because that is the only other
        // view in the activity asking to be focusable in touch mode. That
        // window is one mark's worth of transition, closed again the moment
        // the next mark's own {@code requestFocus()} runs - open for good
        // only once the last mark ends, when the tour is already over. Left
        // unfixed: narrower than first thought, and the tour is no worse
        // protected than before this line, only briefly less than fully so
        // between marks.
        next.setFocusableInTouchMode(true);

        next.setText(last ? R.string.guide_done : R.string.guide_next);
        next.setAllCaps(false);
        next.setTextColor(ON_CYAN);
        GradientDrawable buttonBg = new GradientDrawable();
        buttonBg.setColor(Cards.CYAN);
        buttonBg.setCornerRadius(cardRadiusPx);
        next.setBackground(buttonBg);
        next.setOnClickListener(v -> {
            Coach.dismiss(activity);
            onNext.run();
        });

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = buttonGapPx;
        buttonParams.gravity = Gravity.END;
        card.addView(next, buttonParams);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.leftMargin = marginPx;
        cardParams.rightMargin = marginPx;
        addView(card, cardParams);
    }

    /**
     * Puts up a mark ringing {@code target}, captioned {@code caption}. Any
     * mark already showing on this activity is dismissed first, so a caller
     * never has to check.
     *
     * @param last   whether this is the last mark of its guide - Next reads
     *               "Got it" instead
     * @param onNext run when Next is tapped or activated from the keyboard or
     *               a gamepad; this mark is dismissed first
     */
    public static void show(Activity activity, View target, CharSequence caption,
                            boolean last, Runnable onNext) {
        dismiss(activity);

        ViewGroup content = activity.findViewById(android.R.id.content);
        Coach coach = new Coach(activity, target, caption, last, onNext);

        content.addView(coach, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        coach.claimBack(activity);

        // Posted: the hole is placed from the target's own bounds and this
        // view's own size, neither of which exist until a layout pass has
        // happened - inline, this would ask about the frame before either was
        // laid out and ring the wrong place, or nothing.
        coach.post(() -> {
            coach.placeCard();
            coach.next.requestFocus();
        });
    }

    /**
     * Takes down whatever mark is showing on {@code activity}, if any.
     * Idempotent - a caller may dismiss on both an abandon path and the
     * activity's own {@code onPause} without checking which ran first.
     */
    public static void dismiss(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);

        for (int i = content.getChildCount() - 1; i >= 0; i--) {
            View child = content.getChildAt(i);
            if (child instanceof Coach) {
                ((Coach) child).releaseBack(activity);
                content.removeView(child);
            }
        }
    }

    /**
     * Swallows a predictive-back gesture for as long as this mark is up -
     * see the class comment on why {@link #dispatchKeyEvent} and {@link
     * #dispatchGenericMotionEvent} do not reach it at all from API 33.
     *
     * <b>{@code PRIORITY_OVERLAY}</b>, not {@code PRIORITY_DEFAULT}: the host
     * screen may already hold a callback of its own at the default priority
     * - {@code LibraryActivity} registers and unregisters one dynamically as
     * Browse's own stack fills and empties - and this has to be asked
     * first, above it, or a gesture would reach the host's callback and pop
     * a shelf out from under the scrim. Registering above whatever else is
     * there, rather than trying to already know it, is also simpler and
     * cannot drift out of step with a host screen's own back logic changing
     * independently later.
     *
     * <b>Swallows outright, same as every other key.</b> There is nothing
     * for Back to mean here that Next does not already mean, and giving it
     * a second action - dismissing the mark early, say - would be a second
     * way out of a guide the rest of this class deliberately gives only one.
     */
    private void claimBack(Activity activity) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return;

        backCallback = () -> { };
        activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback);
    }

    /** The other half of {@link #claimBack} - a no-op on API below 33, where
     *  {@link #backCallback} is never set in the first place. */
    private void releaseBack(Activity activity) {
        if (backCallback == null) return;

        activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        backCallback = null;
    }

    /**
     * Whether a mark is up on {@code activity} right now.
     *
     * <b>For a caller whose own {@code dispatchKeyEvent} runs before the
     * window's</b> - {@code LibraryActivity}'s does, to claim a pad's D-pad
     * and buttons ahead of a focused search field. {@link #dispatchKeyEvent}
     * above only ever gets a key once that caller has already decided not to
     * claim it itself, which for a screen wired this way is never - so
     * without a check like this one, a pad key would move a selection behind
     * an active mark, unseen by {@code Coach} at all, on every press. A
     * caller reached the ordinary way - through {@code onKeyDown}/{@code
     * onKeyUp}, downstream of the window's own dispatch, the way {@code
     * EmulatorActivity} is wired - never needs this: the view tree, and so
     * this class, already saw the key first.
     */
    public static boolean isShowing(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);

        for (int i = 0; i < content.getChildCount(); i++) {
            if (content.getChildAt(i) instanceof Coach) return true;
        }
        return false;
    }

    /**
     * Cuts the hole at {@code target}'s bounds and puts the card above or
     * below it, whichever {@link #above} says there is room for.
     *
     * <b>Kept inside the safe area, measured directly rather than through
     * this view's own padding.</b> The hole has to be positioned in raw
     * window coordinates - it has to land exactly on the target, wherever the
     * target's own layout put it, cutout or none - so giving this view
     * padding for the system bars and the cutout would double it: {@link
     * FrameLayout} adds a margin to its own padding when it places a child,
     * and the hole math already reasons in the same coordinate space that
     * padding would shift. So the safe rect is read once, here, and used only
     * to clamp where the card goes - the one part of this view actually free
     * to move.
     */
    private void placeCard() {
        int[] targetAt = new int[2];
        target.getLocationInWindow(targetAt);

        int[] selfAt = new int[2];
        getLocationInWindow(selfAt);

        int left = targetAt[0] - selfAt[0];
        int top = targetAt[1] - selfAt[1];

        hole.set(left - ringPx, top - ringPx,
                left + target.getWidth() + ringPx, top + target.getHeight() + ringPx);
        holeF.set(hole);

        int safeLeft = 0, safeTop = 0, safeRight = getWidth(), safeBottom = getHeight();
        WindowInsets insets = getRootWindowInsets();
        if (insets != null) {
            Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            safeLeft = safe.left;
            safeTop = safe.top;
            safeRight = getWidth() - safe.right;
            safeBottom = getHeight() - safe.bottom;
        }

        int cardWidth = Math.max(0, (safeRight - safeLeft) - 2 * marginPx);
        card.measure(
                MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int cardHeight = card.getMeasuredHeight();

        boolean placeAbove = above(hole.bottom, cardHeight, safeBottom);

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) card.getLayoutParams();
        params.leftMargin = safeLeft + marginPx;
        params.rightMargin = (getWidth() - safeRight) + marginPx;
        params.topMargin = placeAbove
                ? Math.max(safeTop, hole.top - gapPx - cardHeight)
                : Math.min(safeBottom - cardHeight, hole.bottom + gapPx);
        card.setLayoutParams(params);

        invalidate();
    }

    /**
     * Whether the caption belongs above the hole rather than below it.
     *
     * Below by default - a caption under the control reads in the direction
     * the eye is already travelling - and above only when there is not room
     * below. Exactly enough room below counts as room: the rule must not flip
     * on the boundary, or a control one pixel from the edge behaves
     * differently on two devices with the same layout.
     *
     * Takes the hole's bottom edge as an {@code int} rather than a {@link
     * Rect} - see {@code CoachPlacementTest}'s class comment for why, and
     * because this is all of a hole the rule ever reads.
     */
    static boolean above(int holeBottom, int captionHeight, int windowHeight) {
        return holeBottom + captionHeight > windowHeight;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // The scrim, then the hole punched out of it. CLEAR rather than a
        // second colour: anything else tints what it is meant to be showing.
        canvas.drawColor(Palette.SCRIM);
        canvas.drawRoundRect(holeF, holeRadiusPx, holeRadiusPx, cut);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Consumes everything reaching this far - a tap that landed on the
        // card or Next is already handled by that child and never gets here.
        // Without this a tap on the scrim, which no child claims, falls
        // through to whatever this view is drawn over.
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && activates(event.getKeyCode())) {
            // A gamepad's A is not a key Button itself reacts to the way
            // KEYCODE_DPAD_CENTER and KEYCODE_ENTER are, so it is turned into
            // the same click a finger makes rather than passed on for a
            // chain that would not know what to do with it.
            next.performClick();
        }

        // Everything else is swallowed, the activating key included - so
        // nothing behind this can be half-pressed and no GamepadCursor sees
        // a direction or a button and moves a cursor or a selection under it.
        return true;
    }

    /**
     * Swallows a gamepad's stick or hat, exactly as {@link #dispatchKeyEvent}
     * swallows every key but the one that activates Next.
     *
     * A pad direction often arrives as a hat axis rather than a key, which
     * Android turns into no focus move at all on its own - so whatever reads
     * generic motion has to claim it itself, and {@code
     * dispatchGenericMotionEvent} is the same early hook {@code
     * dispatchKeyEvent} already uses for keys rather than an {@code onX}
     * override a subclass further down could still see. Left unconsumed,
     * this axis would reach {@code EmulatorActivity.onGenericMotionEvent} and
     * move the emulated joystick, or a {@code GamepadCursor} on a screen that
     * has one, while the scrim is meant to be the only thing answering.
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return true;
    }

    private static boolean activates(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return true;
            default:
                return false;
        }
    }
}
