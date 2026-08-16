package dev.ldlab.zedex.view;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows every first-run screen is built out of: a card that does
 * something when tapped, a card that shows a value, a quiet note, and the
 * readable-width column they all sit in.
 *
 * Started life as private helpers on {@link dev.ldlab.zedex.screen.StartPanel}
 * for its two folders and its ROM screen; moved here once the setup wizard
 * wanted the same visual language for its own pages, so both can reach it
 * instead of two copies drifting apart.
 */
public final class Cards {

    private Cards() {
    }

    // --- the look of it ------------------------------------------------------
    //
    // Built here rather than in a theme or a drawable folder for the reason
    // everything else in this app is built in code: there are no dependencies
    // to inflate with, and one file that says how the screen looks beats a
    // colour in one place and a shape in another.
    //
    // The palette is the app's own - the dark plate of the icon, and the cyan
    // that marks a chosen thing in the key editor.

    public static final int BACK = 0xff0e0f13;
    public static final int CARD = 0xff1b1d24;
    private static final int EDGE = 0x14ffffff;
    public static final int CYAN = 0xff00b0c8;
    private static final int ON_CYAN = 0xff05222a;
    private static final int CAPTION_ON_CYAN = 0xcc05222a;

    /** Four dp, the step everything on this panel is spaced by. */
    public static int unit(Context context, int steps) {
        return Math.round(4 * steps * context.getResources()
                .getDisplayMetrics().density);
    }

    /** A card: the shape every row on this panel is. */
    private static Drawable card(Context context, int fill) {
        GradientDrawable shape = new GradientDrawable();

        shape.setColor(fill);
        shape.setCornerRadius(unit(context, 3));
        shape.setStroke(Math.max(1, unit(context, 1) / 4), EDGE);

        return shape;
    }

    /** The same, with a band of colour down the leading edge. */
    private static Drawable stripe(Context context, int fill, int accent) {
        GradientDrawable band = new GradientDrawable();
        band.setColor(accent);
        band.setCornerRadius(unit(context, 3));

        LayerDrawable both = new LayerDrawable(
                new Drawable[] { band, card(context, fill) });

        both.setLayerInset(1, unit(context, 1), 0, 0, 0);
        return both;
    }

    /** The platform's own press feedback, over whatever the row is painted. */
    private static void touchable(Context context, View row) {
        TypedValue found = new TypedValue();

        if (context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, found, true)) {
            row.setForeground(context.getDrawable(found.resourceId));
        }
    }

    /**
     * A button with a line under it saying what it does. {@code leading}
     * draws it in CYAN - the row that gets on with it, or the choice already
     * in force.
     *
     * A card rather than a button with a caption under it. A stock button on
     * black is a grey lozenge with no relation to anything else on the
     * screen, and the caption belonging to it was only implied by being
     * nearby - so a column of them read as a list of loose parts. Here the
     * two lines are inside the thing you tap, which is what makes it one row.
     */
    public static View choiceOf(Context context, CharSequence label, int description,
                                View.OnClickListener action, boolean leading) {
        LinearLayout row = new LinearLayout(context);

        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(card(context, leading ? CYAN : CARD));
        row.setPadding(unit(context, 4), unit(context, 3), unit(context, 4), unit(context, 3));
        row.setOnClickListener(action);
        touchable(context, row);

        TextView name = new TextView(context);
        name.setText(label);
        name.setTextSize(17);
        name.setTextColor(leading ? ON_CYAN : Palette.TEXT);
        row.addView(name);

        // Zero means no caption - a row whose name says everything, like a
        // plain "Next" or a link named after its own destination. TextView's
        // own setText(int) throws on a resource id of zero rather than
        // clearing itself, so this is the row's job and not that method's.
        if (description != 0) {
            TextView caption = new TextView(context);
            caption.setText(description);
            caption.setTextSize(13);
            caption.setTextColor(leading ? CAPTION_ON_CYAN : Palette.MUTED);
            caption.setLineSpacing(unit(context, 1) / 2f, 1f);
            caption.setPadding(0, unit(context, 1), 0, 0);
            row.addView(caption);
        }

        return spaced(context, row);
    }

    /** {@link #choiceOf} with a label that is a string resource. Delegates. */
    public static View choice(Context context, int label, int description,
                              View.OnClickListener action, boolean leading) {
        return choiceOf(context, context.getString(label), description, action, leading);
    }

    /**
     * A set of {@link #choiceOf} rows where exactly one leads, and tapping one
     * moves the lead there and off whichever row had it - <b>live</b>, not
     * only at the next redraw.
     *
     * The cyan {@code leading} a row is built with used to be the whole of it:
     * computed once when the page's body was built, from whatever preference
     * was stored at the time, and never touched again. Tapping a different
     * card wrote the new preference and left the old card highlighted until
     * the page was rebuilt from scratch - a lie the moment the page could show
     * two selections that disagreed with each other. This is the fix, kept
     * here rather than on any one page since the controls page needs it twice
     * over (a skin, a joystick) and the machine page needed it retrofitted.
     *
     * One instance per group of rows that share a lead - the controls page
     * has two, the machine page one - and nothing here knows what the rows
     * mean, only which of them is out front.
     */
    public static final class Group {
        private final List<View> cards = new ArrayList<>();
        private int leading = -1;

        /** Adds one row, wired to move the lead here on top of whatever
         *  {@code action} itself does when tapped. */
        public View add(Context context, CharSequence label, int description,
                        View.OnClickListener action, boolean leading) {
            int index = cards.size();

            View card = choiceOf(context, label, description, v -> {
                select(index);
                action.onClick(v);
            }, leading);

            cards.add(card);
            if (leading) this.leading = index;
            return card;
        }

        /** {@link #add} with a label that is a string resource. */
        public View add(Context context, int label, int description,
                        View.OnClickListener action, boolean leading) {
            return add(context, context.getString(label), description, action, leading);
        }

        private void select(int index) {
            if (index == leading) return;

            if (leading >= 0) setLeading(cards.get(leading), false);
            setLeading(cards.get(index), true);
            leading = index;
        }
    }

    /**
     * Repaints one {@link #choiceOf} row in or out of the lead, after it has
     * already been built and placed - the live half of {@link Group}.
     *
     * {@code row} is the holder {@link #spaced} wraps the real row in; its one
     * child is the row {@link #choiceOf} built, and that row's own children
     * are the label and, where there is one, the caption underneath it - the
     * same two views {@code choiceOf} painted the first time, repainted here
     * rather than rebuilt.
     */
    private static void setLeading(View held, boolean leading) {
        ViewGroup row = (ViewGroup) ((ViewGroup) held).getChildAt(0);
        Context context = row.getContext();

        row.setBackground(card(context, leading ? CYAN : CARD));

        TextView name = (TextView) row.getChildAt(0);
        name.setTextColor(leading ? ON_CYAN : Palette.TEXT);

        if (row.getChildCount() > 1) {
            TextView caption = (TextView) row.getChildAt(1);
            caption.setTextColor(leading ? CAPTION_ON_CYAN : Palette.MUTED);
        }
    }

    /**
     * A row whose button carries the answer rather than the action - what a
     * folder is called matters more there than what the row would do to it.
     *
     * The same shape, holding a value this class keeps up to date.
     */
    public static View valueCard(Context context, TextView value, int description,
                                 View.OnClickListener action) {
        LinearLayout row = new LinearLayout(context);

        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(card(context, CARD));
        row.setPadding(unit(context, 4), unit(context, 3), unit(context, 4), unit(context, 3));
        row.setOnClickListener(action);
        touchable(context, row);

        value.setTextSize(17);
        value.setTextColor(Palette.TEXT);
        row.addView(value);

        TextView caption = new TextView(context);
        caption.setText(description);
        caption.setTextSize(13);
        caption.setTextColor(Palette.MUTED);
        caption.setLineSpacing(unit(context, 1) / 2f, 1f);
        caption.setPadding(0, unit(context, 1), 0, 0);
        row.addView(caption);

        return spaced(context, row);
    }

    /**
     * Quiet text on a card with the icon's cyan down the near edge: the row
     * that asks for nothing should not look like the ones that do.
     */
    public static TextView note(Context context) {
        TextView note = new TextView(context);
        note.setTextSize(14);
        note.setTextColor(Palette.MUTED);
        note.setLineSpacing(unit(context, 1), 1f);
        note.setPadding(unit(context, 4), unit(context, 3), unit(context, 4), unit(context, 3));
        note.setBackground(stripe(context, CARD, CYAN));
        return note;
    }

    /** A plain section heading, for a page with two sections in it. */
    public static View note(Context context, int heading) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(heading);
        title.setTextSize(17);
        title.setTextColor(Palette.TEXT);
        title.setPadding(0, 0, 0, unit(context, 2));
        column.addView(title);

        return column;
    }

    /** A row, with air under it. */
    public static View spaced(Context context, View row) {
        LinearLayout holder = new LinearLayout(context);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.setPadding(0, 0, 0, unit(context, 3));

        return holder;
    }

    /**
     * A column that stops growing.
     *
     * A line of text the whole width of a tablet is a line nobody can follow
     * back to its start, and the panel is mostly prose. Sixty characters or so
     * is the width this settles at; the rest of the window is margin, and the
     * column sits in the middle of it.
     */
    public static final class Column extends LinearLayout {

        private final int most;

        Column(Context context, int most) {
            super(context);
            this.most = most;
            setOrientation(VERTICAL);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            if (MeasureSpec.getSize(widthSpec) > most) {
                widthSpec = MeasureSpec.makeMeasureSpec(most, MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /** The readable-width column every one of these screens is built in. */
    public static LinearLayout column(Context context, int maxWidthUnits) {
        return new Column(context, unit(context, maxWidthUnits));
    }
}
