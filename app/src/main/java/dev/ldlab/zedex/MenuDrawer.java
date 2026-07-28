package dev.ldlab.zedex;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
 * The items are ordinary text views with the words in them, which is what lets
 * the tests and {@code scripts/ui-tap.py} keep addressing the menu by name.
 */
final class MenuDrawer extends FrameLayout {

    /** Wide enough for the longest item, narrow enough to leave the screen. */
    private static final int WIDTH_DP = 300;

    /** Most of the width on a small phone in portrait, rather than all of it. */
    private static final float MAX_FRACTION = 0.8f;

    private static final long SLIDE_MS = 180;

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

    void addItem(String text, Runnable action) {
        TextView row = new TextView(getContext());

        row.setText(text);
        row.setTextColor(LABEL);
        row.setTextSize(16);
        row.setPadding(unit * 3, unit * 2, unit * 3, unit * 2);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setClickable(true);
        row.setFocusable(true);
        // Closing first keeps the sheet from sitting over whatever the item
        // opens, and makes the two feel like one gesture.
        row.setOnClickListener(v -> {
            close();
            action.run();
        });

        items.addView(row);
    }

    void addRule() {
        View rule = new View(getContext());

        rule.setBackgroundColor(RULE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(unit * 3, unit, unit * 3, unit);

        items.addView(rule, params);
    }

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
