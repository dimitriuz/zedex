package dev.ldlab.zedex;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Five lamps saying what the machine is busy with.
 *
 * A Spectrum gives almost nothing away. A tape that is not running looks like
 * a tape that is, a game that has stopped reading the keyboard looks like one
 * that never did, and a game waiting for a Kempston joystick when Cursor is
 * selected looks simply broken. Each of those is one bit of state the emulator
 * already has, and each of them is the difference between "it does not work"
 * and "it wants something else".
 *
 * Colour says which way the data is going, where anybody can tell. Reading is
 * the cool blue the keyboard uses for a pressed key; writing is amber, because
 * writing is the direction that changes something. Most of the lamps only ever
 * show one of the two, and that is the emulator's limit rather than the
 * display's: a keyboard is only ever read, what the AY does is sound on its way
 * out, and a disk will not say at all — Fuse reports the motor turning and not
 * which way the head is pointing. The tape can say, because the moment its save
 * trap appends a block is visible, and that moment is held for a fifth of a
 * second so it can be seen.
 *
 * Laid out along whichever axis there is room for: a row under the picture in
 * portrait, a column beside it in landscape. The view decides that from the
 * configuration rather than being told, so nothing has to keep the two in step.
 *
 * The state is polled rather than pushed. It changes at 50Hz, which is far
 * faster than an eye reads a lamp and far too fast to be worth a callback and
 * a thread hop each time; a look every {@link #POLL_MS} redraws only when
 * something has actually changed.
 */
final class ActivityLights extends View {

    /** Long enough to see a flicker, slow enough to cost nothing. */
    private static final long POLL_MS = 100;

    /** One lamp: what it watches, what it looks like, and what it is called. */
    private static final class Lamp {
        final int bit;
        final int icon;
        final int name;
        Drawable image;

        Lamp(int bit, int icon, int name) {
            this.bit = bit;
            this.icon = icon;
            this.name = name;
        }
    }

    /** An icon of 0 means the lamp draws itself; only the AY meter does. */
    private static final int METER = 0;

    private final Lamp[] lamps = {
        new Lamp(FuseNative.ACTIVITY_TAPE, R.drawable.ic_tape, R.string.lamp_tape),
        new Lamp(FuseNative.ACTIVITY_DISK, R.drawable.ic_disk, R.string.lamp_disk),
        new Lamp(FuseNative.ACTIVITY_AY, METER, R.string.lamp_ay),
        new Lamp(FuseNative.ACTIVITY_KEYBOARD, R.drawable.ic_keyboard,
                 R.string.lamp_keyboard),
        new Lamp(FuseNative.ACTIVITY_JOYSTICK, R.drawable.ic_joystick,
                 R.string.lamp_joystick),
        // Lit when the machine reads the Kempston mouse's ports, whether or not
        // the mouse is plugged in - which makes it the answer to "does this game
        // use the mouse?", and the moment to go and turn it on.
        new Lamp(FuseNative.ACTIVITY_MOUSE, R.drawable.ic_mouse,
                 R.string.lamp_mouse),
    };

    /** Dark enough to read against any Spectrum screen, like the quick bar. */
    private static final int BACKING = 0x99000000;

    /** Off, taking something in, and putting something out. */
    private static final int IDLE = 0x59ededf2;
    private static final int READING = 0xff00b0c8;
    private static final int WRITING = 0xffffb000;

    private static final int ICON_DP = 18;
    private static final int PAD_DP = 5;
    private static final int GAP_DP = 4;

    private final Paint backing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final RectF stick = new RectF();

    private final int icon;
    private final int pad;
    private final int gap;

    /** The last published words, so a redraw only happens on a change. */
    private int state;
    private int levels;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            int now = FuseNative.activity();
            int loudness = FuseNative.ayLevels();

            if (now != state || loudness != levels) {
                state = now;
                levels = loudness;
                invalidate();
            }

            handler.postDelayed(this, POLL_MS);
        }
    };

    ActivityLights(Context context) {
        super(context);

        float density = getResources().getDisplayMetrics().density;

        icon = Math.round(ICON_DP * density);
        pad = Math.round(PAD_DP * density);
        gap = Math.round(GAP_DP * density);

        backing.setColor(BACKING);

        bar.setColor(WRITING);

        for (Lamp lamp : lamps) {
            if (lamp.icon == METER) continue;

            Drawable image = context.getDrawable(lamp.icon);
            if (image != null) {
                image = image.mutate();
                image.setBounds(0, 0, icon, icon);
            }
            lamp.image = image;
        }

        describe();
    }

    /** A row in portrait, a column in landscape; see the class comment. */
    private boolean horizontal() {
        return getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_LANDSCAPE;
    }

    // --- size ---------------------------------------------------------------

    /** A lamp is its icon with room around it, and so square. */
    private int lampSize() {
        return icon + pad * 2;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int one = lampSize();
        int total = one * lamps.length + gap * (lamps.length - 1);

        setMeasuredDimension(
                resolveSize(horizontal() ? total : one, widthMeasureSpec),
                resolveSize(horizontal() ? one : total, heightMeasureSpec));
    }

    // --- drawing ------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        boolean row = horizontal();
        int one = lampSize();
        float radius = one / 2f;

        for (int i = 0; i < lamps.length; i++) {
            Lamp lamp = lamps[i];
            int start = i * (one + gap);

            if (row) {
                pill.set(start, 0, start + one, one);
            } else {
                pill.set(0, start, one, start + one);
            }

            canvas.drawRoundRect(pill, radius, radius, backing);

            if (lamp.icon == METER) {
                drawMeter(canvas);
                continue;
            }

            if (lamp.image == null) continue;

            boolean lit = (state & lamp.bit) != 0;
            boolean writing = (state & (lamp.bit << FuseNative.ACTIVITY_WRITING)) != 0;

            lamp.image.setTint(!lit ? IDLE : writing ? WRITING : READING);

            canvas.save();
            canvas.translate(pill.centerX() - icon / 2f, pill.centerY() - icon / 2f);
            lamp.image.draw(canvas);
            canvas.restore();
        }
    }

    /**
     * The AY as three bars rather than one lamp, because it is three
     * independent channels and a chip using one of them is doing something
     * quite different from a chip using all three. The height is the channel's
     * amplitude, so this is a level meter and not three more lamps.
     *
     * A faint full-height track behind each bar keeps it recognisable as a
     * meter while the chip is silent; without it the lamp simply vanishes.
     */
    private void drawMeter(Canvas canvas) {
        float wide = icon / 5f;
        float step = icon / 3f;
        float radius = wide / 2f;

        for (int channel = 0; channel < 3; channel++) {
            int level = (levels >> (channel * 8)) & 0xff;
            float left = pill.centerX() - icon / 2f + channel * step
                       + (step - wide) / 2f;
            float bottom = pill.centerY() + icon / 2f;

            bar.setColor(IDLE);
            stick.set(left, pill.centerY() - icon / 2f, left + wide, bottom);
            canvas.drawRoundRect(stick, radius, radius, bar);

            if (level <= 0) continue;

            // Never shorter than it is wide, so the quietest channel is still
            // a bar and not a dot.
            float tall = Math.max(wide, icon * Math.min(level, 15) / 15f);

            bar.setColor(WRITING);
            stick.set(left, bottom - tall, left + wide, bottom);
            canvas.drawRoundRect(stick, radius, radius, bar);
        }
    }

    // --- accessibility ------------------------------------------------------

    /**
     * One description for the strip, set once and never changed.
     *
     * It is tempting to say what is happening — "tape reading, AY writing" —
     * and that was the first attempt. It made the app untestable and would have
     * made it unusable with a screen reader, for the same reason: a
     * contentDescription that changes ten times a second is ten
     * window-content-changed events a second, the accessibility tree never
     * settles, and anything waiting for it to settle waits for ever. UI
     * Automator could not find the ☰ button any more.
     *
     * So what a screen reader gets is what the strip is, not what it is
     * momentarily doing. Nothing is lost that was ever any use: these are
     * lamps, and a lamp read aloud on every flicker is noise.
     */
    private void describe() {
        setContentDescription(getContext().getString(R.string.lights_name));
    }

    // --- polling ------------------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(poll);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(poll);
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);

        // Nothing to look at, nothing to ask about.
        handler.removeCallbacks(poll);
        if (visibility == VISIBLE && isAttachedToWindow()) handler.post(poll);
    }
}
