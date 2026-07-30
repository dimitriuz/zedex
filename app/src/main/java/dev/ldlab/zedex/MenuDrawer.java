package dev.ldlab.zedex;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The ☰ menu, as a sheet that slides in from the edge.
 *
 * A dialog was in the way of the thing it acted on — it took the middle of the
 * screen, which is where the machine is — and it could only ever be a flat list
 * of equal-looking items. A sheet down one side leaves the picture visible,
 * gives the items room to be grouped, and closes by tapping the screen you can
 * still see.
 *
 * Written out rather than taken from a library: androidx's DrawerLayout would
 * be the app's first dependency, and this is a translation, a fade and a list.
 *
 * The sheet has <em>pages</em>. A flat list of everything came to a dozen rows,
 * which is taller than a landscape window and so had to be scrolled to reach
 * the last of it — and scrolling to find a menu item is the thing a menu is
 * for avoiding. A page holds a handful of rows, and a row can lead to another
 * page instead of doing something.
 *
 * Pages are built when they are shown rather than once at startup, which is
 * what lets a page list the drives the machine has today, or say <em>Stop
 * recording</em> only while something is recording. The activity supplies each
 * one as a {@link Page}.
 *
 * The rows are ordinary text views with the words in them, which is what lets
 * the tests and {@code scripts/ui-tap.py} keep addressing the menu by name.
 */
final class MenuDrawer extends FrameLayout {

    /**
     * What goes on one page. Called every time the page is shown, so it can
     * read whatever the machine is doing now.
     */
    interface Page {
        void fill(MenuDrawer sheet);
    }

    /** Wide enough for the longest item, narrow enough to leave the screen. */
    private static final int WIDTH_DP = 300;

    /** Most of the width on a small phone in portrait, rather than all of it. */
    private static final float MAX_FRACTION = 0.8f;

    private static final long SLIDE_MS = 180;

    /** How far a page slides as it replaces another; enough to show which way. */
    private static final int PAGE_SHIFT_DP = 20;
    private static final long PAGE_MS = 140;

    /** The field colour from the icon, so the sheet belongs to the app. */
    private static final int SHEET = 0xff17171d;
    private static final int SCRIM = 0x99000000;
    private static final int LABEL = 0xffededf2;
    private static final int SECTION = 0xff8b8b99;
    private static final int RULE = 0x1affffff;

    private final View scrim;
    private final ScrollView sheet;
    private final LinearLayout items;
    private final int unit;

    /** The pages entered from the root, deepest last. */
    private final List<Page> trail = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    private Page root;
    private boolean open;
    private Runnable onClosed;

    MenuDrawer(Context context) {
        super(context);

        unit = Math.round(8 * getResources().getDisplayMetrics().density);
        setVisibility(GONE);

        scrim = new View(context);
        scrim.setBackgroundColor(SCRIM);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT,
                                        LayoutParams.MATCH_PARENT));

        items = new LinearLayout(context);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(0, unit * 2, 0, unit * 2);

        sheet = new ScrollView(context);
        sheet.setBackgroundColor(SHEET);
        sheet.addView(items, new LayoutParams(LayoutParams.MATCH_PARENT,
                                             LayoutParams.WRAP_CONTENT));

        LayoutParams params = new LayoutParams(WIDTH_DP, LayoutParams.MATCH_PARENT);
        params.gravity = Gravity.END;
        addView(sheet, params);
    }

    /** The page the sheet opens on. */
    void setRoot(Page page) {
        root = page;
    }

    // --- building a page ----------------------------------------------------

    /** A section heading; the groups are what a flat dialog could not show. */
    void addSection(String title) {
        TextView label = new TextView(getContext());

        // Locale.ROOT, not the default: in Turkish the default turns "i" into
        // a dotted capital and the heading stops being the word it was.
        label.setText(title.toUpperCase(java.util.Locale.ROOT));
        label.setTextColor(SECTION);
        label.setTextSize(11);
        label.setLetterSpacing(0.12f);
        label.setPadding(unit * 3, unit * 2, unit * 3, unit / 2);

        items.addView(label);
    }

    /** A row that does something, and closes the sheet on its way. */
    void addItem(String text, int icon, Runnable action) {
        addItem(text, icon, action, null);
    }

    /**
     * The same, with something else on a long press - which is where deleting
     * belongs: a row whose tap is "use this" cannot also be "throw this away",
     * and a second row per item would double the list.
     */
    void addItem(String text, int icon, Runnable action, Runnable longPress) {
        // Closing first keeps the sheet from sitting over whatever the item
        // opens, and makes the two feel like one gesture.
        addRow(text, icon, false, () -> {
            close();
            action.run();
        }, longPress == null ? null : () -> {
            close();
            longPress.run();
        });
    }

    /** A row that leads to another page. The sheet stays where it is. */
    void addSubmenu(String text, int icon, Page page) {
        addRow(text, icon, true, () -> enter(text, page));
    }

    /**
     * A submenu whose page calls itself something else.
     *
     * For a page whose heading sits over the first of two sections rather than
     * over the whole page: <i>Media…</i> leads to the tape and the drives, and
     * the heading it lands under belongs to the tape rows beneath it, so it says
     * TAPE and the drives keep their own DRIVES below.
     */
    void addSubmenu(String text, String heading, int icon, Page page) {
        addRow(text, icon, true, () -> enter(heading, page));
    }

    /** Something to read rather than to press: an empty list saying so. */
    void addNote(String text) {
        TextView note = new TextView(getContext());

        note.setText(text);
        note.setTextColor(SECTION);
        note.setTextSize(14);
        note.setPadding(unit * 3, unit, unit * 3, unit * 2);

        items.addView(note);
    }

    void addRule() {
        View rule = new View(getContext());

        rule.setBackgroundColor(RULE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(unit * 3, unit, unit * 3, unit);

        items.addView(rule, params);
    }

    /**
     * One row: a single view holding the words, because a label nested inside
     * a clickable container would put the text on one node and the click on
     * another, and both the tests and {@code ui-tap.py} look for them
     * together.
     *
     * The icon and the chevron are the view's own compound drawables for the
     * same reason. They also align properly that way — the icon on the text's
     * baseline box at the start, the chevron hard against the end — which a
     * glyph pasted into the string could not do.
     */
    private void addRow(String label, int icon, boolean leadsOn, Runnable action) {
        addRow(label, icon, leadsOn, action, null);
    }

    private void addRow(String label, int icon, boolean leadsOn, Runnable action,
                        Runnable longPress) {
        TextView row = new TextView(getContext());

        row.setText(label);
        row.setTextColor(LABEL);
        row.setTextSize(16);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(unit * 3, unit * 2, unit * 3, unit * 2);
        row.setCompoundDrawablePadding(unit * 2);
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                tinted(icon, LABEL), null,
                leadsOn ? tinted(R.drawable.ic_chevron_right, SECTION) : null, null);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());

        if (longPress != null) {
            row.setLongClickable(true);
            row.setOnLongClickListener(v -> {
                longPress.run();
                return true;
            });
        }

        items.addView(row);
    }

    /**
     * The icons are one colour each, drawn white and tinted here, so the
     * chevron can be quieter than the label without a second set of files.
     */
    private Drawable tinted(int resource, int colour) {
        if (resource == 0) return null;

        Drawable icon = getContext().getDrawable(resource);
        if (icon == null) return null;

        icon = icon.mutate();
        icon.setTint(colour);

        return icon;
    }

    // --- moving between pages -----------------------------------------------

    /**
     * Opens the sheet straight onto a page, for one that was chosen somewhere
     * else - a game picked out of a search, say. Back still leads out of it,
     * since it is entered the same way a row would enter it.
     */
    void go(String heading, Page page) {
        open();
        enter(heading, page);
    }

    private void enter(String name, Page page) {
        trail.add(page);
        names.add(name);
        show(1);
    }

    /**
     * Back: up one page, or shut if this is the root already. Says whether it
     * did anything, so the activity can let the key through when it did not.
     */
    boolean back() {
        if (!open) return false;

        if (trail.isEmpty()) {
            close();
            return true;
        }

        trail.remove(trail.size() - 1);
        names.remove(names.size() - 1);
        show(-1);

        return true;
    }

    /** Rebuilds whatever page is now current. {@code direction} is the slide. */
    private void show(int direction) {
        items.removeAllViews();

        Page current = trail.isEmpty() ? root : trail.get(trail.size() - 1);

        if (!trail.isEmpty()) {
            // "Back" rather than the page's own name, so that a test asking
            // for a row by name cannot land on the way out instead.
            String back = getContext().getString(R.string.menu_back);

            addRow(back, R.drawable.ic_chevron_left, false, this::back);

            // "MEDIA…" as a heading promises more still to come; the ellipsis
            // belonged to the row that got you here, not to where you are.
            String here = names.get(names.size() - 1);
            addSection(here.endsWith("…") ? here.substring(0, here.length() - 1)
                                          : here);
        }

        if (current != null) current.fill(this);

        sheet.scrollTo(0, 0);
        slide(direction);
    }

    /** A short shove in the direction of travel, so the change reads as depth. */
    private void slide(int direction) {
        if (direction == 0) return;

        float shift = PAGE_SHIFT_DP * getResources().getDisplayMetrics().density;

        items.setTranslationX(direction * shift);
        items.setAlpha(0f);
        items.animate().translationX(0f).alpha(1f).setDuration(PAGE_MS);
    }

    // --- opening and closing ------------------------------------------------

    boolean isOpen() {
        return open;
    }

    /** Told whenever the sheet has finished closing, however it was closed. */
    void setOnClosed(Runnable action) {
        onClosed = action;
    }

    void open() {
        if (open) return;

        open = true;
        setVisibility(VISIBLE);

        // Always from the top: where you were last time is not where you want
        // to be now, and a sheet that opens somewhere unexpected is worse than
        // one extra tap.
        trail.clear();
        names.clear();
        show(0);

        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(SLIDE_MS);

        // The sheet has no width until it has been laid out, which cannot have
        // happened while this was GONE - so hide it for the one frame that
        // takes, rather than letting it appear at rest and then jump.
        sheet.setAlpha(0f);
        post(() -> {
            sheet.setTranslationX(sheet.getWidth());
            sheet.setAlpha(1f);
            sheet.animate().translationX(0f).setDuration(SLIDE_MS);
        });
    }

    void close() {
        if (!open) return;

        open = false;
        scrim.animate().alpha(0f).setDuration(SLIDE_MS);
        sheet.animate().translationX(sheet.getWidth()).setDuration(SLIDE_MS)
             .withEndAction(() -> {
                 setVisibility(GONE);
                 if (onClosed != null) onClosed.run();
             });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int wanted = Math.min(Math.round(WIDTH_DP * getResources()
                                    .getDisplayMetrics().density),
                              Math.round(width * MAX_FRACTION));

        // Set before measuring rather than through setLayoutParams, which would
        // ask for another layout from inside this one.
        LayoutParams params = (LayoutParams) sheet.getLayoutParams();
        params.width = wanted;

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
