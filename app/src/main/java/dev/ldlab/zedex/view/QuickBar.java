package dev.ldlab.zedex.view;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.menu.Capture;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The strip of icons across the top of the window.
 *
 * ☰ opens a sheet, which is the right shape for reading a list of choices — but
 * the things done most often are not read, they are reached for. Those are one
 * tap here, and they are icons because a word over the picture is a word over the
 * picture.
 *
 * A <em>group</em> is an icon that opens a short list under itself instead of
 * doing something. Only one is open at a time, acting on anything shuts it, and
 * so does a touch anywhere off the bar — see {@link #collapseIfOutside}.
 *
 * The bar is icons and the list is words, deliberately: an icon is a place you
 * learn, but a dot and a reel of film do not say <i>GIF</i> and <i>MP4</i>, and
 * nothing drawn says whether tapping the joystick will show it or hide it.
 *
 * Everything is named to accessibility, since an icon has no text to read. That
 * is what a screen reader announces and what the tests and
 * {@code scripts/ui-tap.py} address it by.
 */
public final class QuickBar extends LinearLayout implements Rows {

    /** What goes in the list when a group is opened. */
    public interface Row {
        void fill(QuickBar bar);
    }

    /**
     * {@link Rows}: the bar's half of a list both surfaces can show. Delegates
     * for the same reason the sheet's does - the bar's own callers pass an icon
     * first because that is what a bar is made of.
     */
    @Override
    public void item(int icon, String text, Runnable action) {
        addToRow(icon, text, action);
    }

    @Override
    public void rule() {
        addToRowRule();
    }

    /** Big enough for a thumb, small enough to leave the picture alone. */
    private static final int BUTTON_DP = 44;
    private static final int ICON_DP = 22;

    /** And the smallest it will shrink to when the black beside the picture is
     *  narrow; the activity lamps are 28dp for comparison. */
    private static final int LEAST_DP = 22;

    /** The dropdown's own metrics: a smaller icon, and room for words. */
    private static final int LIST_ICON_DP = 20;
    private static final int LIST_PAD_DP = 14;

    /** How wide a row may get before its words are cut, in dp. */
    private static final int LIST_MAX_DP = 240;
    private static final float LIST_TEXT_SP = 15;

    /** Dark enough to read a white icon against any Spectrum screen. */
    private static final int BACKING = 0x99000000;
    private static final int ICON = 0xffededf2;

    /** The open group's own icon, so it is clear which row belongs to what. */
    private static final int ICON_OPEN = 0xff00b0c8;

    /** The line between what a group does and what it opens. */
    private static final int RULE = 0x1affffff;

    private final LinearLayout primary;
    private final LinearLayout secondary;
    private final int button;
    private final int icon;

    /** Below this an icon is not worth aiming at, however little room there is. */
    private final int least;

    /** What a cell came out as, so the same answer is not applied twice. */
    private int cellSize;

    /** Which group is showing its row, so tapping it again puts it away. */
    private View openGroup;

    public QuickBar(Context context) {
        super(context);

        float density = getResources().getDisplayMetrics().density;
        button = Math.round(BUTTON_DP * density);
        icon = Math.round(ICON_DP * density);
        least = Math.round(LEAST_DP * density);
        cellSize = button;

        setOrientation(VERTICAL);
        setGravity(Gravity.END);

        primary = strip(context, density);
        secondary = strip(context, density);
        secondary.setOrientation(VERTICAL);
        secondary.setVisibility(GONE);

        LayoutParams below = new LayoutParams(LayoutParams.WRAP_CONTENT,
                                              LayoutParams.WRAP_CONTENT);
        below.topMargin = Math.round(6 * density);
        below.gravity = Gravity.END;

        addView(primary, new LayoutParams(LayoutParams.WRAP_CONTENT,
                                          LayoutParams.WRAP_CONTENT));
        addView(secondary, below);
    }

    /**
     * How big the icons are, given the room the bar has been left.
     *
     * {@code room} of zero means full size, which is the emulator's own window:
     * there the bar has a strip of its own across the top, whichever way up the
     * device is, and the whole width to use.
     *
     * A number is a handheld's second screen, where the bar shares a short panel
     * with a keyboard and a joystick and gets whatever band is left over. Nine
     * full sized icons is around a thousand pixels, so on a narrow panel they
     * shrink towards the size of the activity lamps rather than running off the
     * end of it.
     */
    public void setCompact(int room) {
        int count = 0;

        // What it is showing, not what it holds: a hidden icon that still took
        // its share of the width would make all the others smaller for nothing.
        for (int i = 0; i < primary.getChildCount(); i++) {
            if (primary.getChildAt(i).getVisibility() != GONE) count++;
        }

        int cell = room <= 0 || count == 0
                ? button
                : Math.max(least, Math.min(button, room / count));

        if (cell == cellSize) return;

        cellSize = cell;
        collapse();

        int glyph = Math.round(cell * ICON_DP / (float) BUTTON_DP);
        int padding = ( cell - glyph ) / 2;

        for (int i = 0; i < primary.getChildCount(); i++) {
            View child = primary.getChildAt(i);

            child.setLayoutParams(new LayoutParams(cell, cell));
            child.setPadding(padding, padding, padding, padding);
        }
    }

    /** One strip of choices on its own rounded backing. */
    private LinearLayout strip(Context context, float density) {
        LinearLayout row = new LinearLayout(context);
        GradientDrawable backing = new GradientDrawable();

        backing.setColor(BACKING);
        backing.setCornerRadius(BUTTON_DP * density / 2f);

        row.setOrientation(HORIZONTAL);
        row.setBackground(backing);
        row.setClipToOutline(true);

        return row;
    }

    // --- building -----------------------------------------------------------

    /**
     * An icon that does something, and shuts any open group on its way.
     *
     * Returns the button, for the one action whose icon is not fixed: pause
     * becomes play and back again, and the bar is built once.
     */
    public ImageButton addAction(int drawable, String name, Runnable action) {
        ImageButton button = makeButton(drawable, name, () -> {
            collapse();
            action.run();
        });

        primary.addView(button);

        return button;
    }

    /**
     * An icon that does something for as long as it is held, and stops when it is
     * let go.
     *
     * A touch listener rather than a click listener, because a click is a press
     * and a release together and there is nothing in between it can report. That
     * makes it the one control here a screen reader cannot work: an accessibility
     * click has no duration. Nothing is lost that is not elsewhere - the speed is
     * a setting too - and a control that has to be held is what was asked for.
     */
    public ImageButton addHold(int drawable, String name, Runnable press, Runnable release) {
        ImageButton button = makeButton(drawable, name, null);

        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    collapse();
                    view.setPressed(true);
                    press.run();
                    return true;

                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    release.run();
                    return true;

                default:
                    return true;
            }
        });

        primary.addView(button);

        return button;
    }

    /** Changes what an action looks like and is called, after the fact. */
    public void setAction(ImageButton button, int drawable, String name) {
        Drawable image = getContext().getDrawable(drawable);

        button.setImageDrawable(image);
        button.setColorFilter(ICON);
        button.setContentDescription(name);
    }

    /**
     * An icon that opens the second row instead of doing something. The row is
     * built each time it is opened, so it can offer <em>Stop recording</em>
     * only while something is recording.
     */
    public void addGroup(int drawable, String name, Row row) {
        ImageButton group = makeButton(drawable, name, null);

        group.setOnClickListener(v -> {
            boolean wasOpen = openGroup == group;
            collapse();
            if (wasOpen) return;

            secondary.removeAllViews();
            row.fill(this);
            secondary.setVisibility(VISIBLE);

            openGroup = group;
            group.setColorFilter(ICON_OPEN);

            // Under the icon that opened it. The list is as wide as its widest
            // row, and a row can be a filename, so left to itself it reaches
            // away across the window from a bar hanging in the right-hand
            // corner - which reads as a menu belonging to nothing.
            hangUnder(group);
        });

        primary.addView(group);
    }

    /**
     * One choice in the dropdown, with its name beside it. Only valid from
     * inside {@link Row#fill}.
     *
     * A text view with the icon as its own compound drawable rather than the
     * two side by side in a container, for the same reason the sheet's rows
     * are: it keeps the words and the click on one accessibility node.
     */
    public void addToRow(int drawable, String name, Runnable action) {
        TextView row = new TextView(getContext());
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(LIST_PAD_DP * density);
        int size = Math.round(LIST_ICON_DP * density);

        Drawable image = getContext().getDrawable(drawable);
        if (image != null) {
            image = image.mutate();
            image.setBounds(0, 0, size, size);
            image.setTint(ICON);
        }

        row.setText(name);
        row.setTextColor(ICON);
        row.setTextSize(LIST_TEXT_SP);

        // A row is one of two kinds of thing, and they want opposite treatment.
        //
        // A label with a line already in it is a title over a value - "Change
        // machine…" over "Scorpion ZS 256" - and it must keep the line. Made
        // single line it is flattened into one long one, which then overruns
        // the cap and has its middle taken out of both halves at once: "Change
        // ma…pion ZS 256", which says neither thing.
        //
        // Everything else is one line, and often a filename - "Sherlock 48K
        // (1984)(Melbourne House).z80" - where a list as wide as its longest
        // row is a list that covers the machine. Cut in the middle, so a name
        // and an extension both survive. That cut needs setSingleLine and not
        // merely one line: MIDDLE is only honoured on a single-line view.
        boolean titled = name.indexOf('\n') >= 0;

        row.setMaxWidth(Math.round(LIST_MAX_DP * density));

        if (titled) {
            row.setMaxLines(2);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        } else {
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        }

        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, pad, pad + pad / 2, pad);
        row.setCompoundDrawablePadding(pad);
        row.setCompoundDrawablesRelative(image, null, null, null);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            collapse();
            action.run();
        });

        secondary.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT,
                                                LayoutParams.WRAP_CONTENT));
    }

    /**
     * Puts the dropdown under one of the icons: its right edge under the
     * icon's, so it opens downwards from what was pressed and grows leftwards
     * into the window rather than off it.
     *
     * Waited for rather than measured now, because the row that decides the
     * width has only just been added and nothing has been laid out yet.
     */
    private void hangUnder(View group) {
        // A width of its own first. The rows are MATCH_PARENT so that they are
        // all the same width and all a full-width target, which leaves the list
        // itself with nothing to size to: it took the whole window, and a menu
        // that starts at the far edge of the screen belongs to nothing.
        int widest = 0;
        int unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

        for (int i = 0; i < secondary.getChildCount(); i++) {
            View row = secondary.getChildAt(i);

            row.measure(unbounded, unbounded);
            widest = Math.max(widest, row.getMeasuredWidth());
        }

        LayoutParams params = (LayoutParams) secondary.getLayoutParams();

        params.width = widest;
        params.gravity = Gravity.END;
        params.rightMargin = 0;
        secondary.setLayoutParams(params);

        // Then under the icon: its right edge under the icon's, which needs the
        // bar laid out at its new size, so it waits a pass.
        secondary.post(() -> {
            if (openGroup != group) return;

            LayoutParams now = (LayoutParams) secondary.getLayoutParams();
            int under = getWidth() - group.getRight();

            // Never so far that the list runs off the other edge: on a narrow
            // window the first icon's list is wider than what is left of the
            // row beside it.
            int most = Math.max(0, getWidth() - secondary.getWidth());

            now.rightMargin = Math.min(under, most);
            secondary.setLayoutParams(now);
        });
    }

    /**
     * A line across the list, for the group that holds two kinds of thing:
     * what can be done, and then what can be opened.
     */
    public void addToRowRule() {
        View rule = new View(getContext());
        float density = getResources().getDisplayMetrics().density;

        rule.setBackgroundColor(RULE);

        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, 1);
        params.setMargins(Math.round(LIST_PAD_DP * density),
                          Math.round(4 * density),
                          Math.round(LIST_PAD_DP * density),
                          Math.round(4 * density));

        secondary.addView(rule, params);
    }

    /** Puts the dropdown away, wherever the tap came from. */
    /**
     * How tall the bar is with nothing opened - the icons alone.
     *
     * The strip the picture is moved down by in portrait, and the space the
     * second screen keeps for it, and only that: a group opening adds a list
     * underneath, and moving the machine's screen down every time somebody
     * looks at a menu is worse than the list covering a band of black.
     *
     * The cell size rather than anything measured. A button is its icon with
     * room around it and the row is one button tall, so this is the answer
     * before a layout as well as after one - which matters, since whoever keeps
     * room for the bar has to know how much before the bar has been drawn.
     */
    public int rowHeight() {
        return cellSize;
    }

    /**
     * Shuts an open group when the touch that started was not on the bar.
     *
     * Called from whichever container holds the bar, out of
     * {@code onInterceptTouchEvent} and in that container's coordinates: it looks
     * and never takes, so the tap still reaches the picture, a key or the
     * joystick underneath.
     *
     * ACTION_DOWN only — a Kempston mouse drag across the picture would otherwise
     * close the group again on every move it reported.
     */
    public void collapseIfOutside(android.view.MotionEvent event) {
        if (openGroup == null
                || event.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) {
            return;
        }

        float x = event.getX();
        float y = event.getY();

        boolean onTheBar = x >= getLeft() && x < getRight()
                        && y >= getTop() && y < getBottom();

        if (!onTheBar) collapse();
    }

    /** Whether one of the groups is showing its row. */
    public boolean isOpen() {
        return openGroup != null;
    }

    public void collapse() {
        if (openGroup == null) return;

        ((ImageButton) openGroup).setColorFilter(ICON);
        openGroup = null;

        secondary.setVisibility(GONE);
        secondary.removeAllViews();
    }

    private ImageButton makeButton(int drawable, String name, Runnable action) {
        ImageButton view = new ImageButton(getContext());
        Drawable image = getContext().getDrawable(drawable);

        view.setImageDrawable(image);
        view.setColorFilter(ICON);
        view.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        view.setBackgroundResource(
                android.R.drawable.list_selector_background);
        // The only thing an icon can be called: a screen reader reads this,
        // and so do the tests.
        view.setContentDescription(name);

        int padding = (button - icon) / 2;
        view.setPadding(padding, padding, padding, padding);

        if (action != null) view.setOnClickListener(v -> action.run());

        view.setLayoutParams(new LayoutParams(button, button));

        return view;
    }
}
