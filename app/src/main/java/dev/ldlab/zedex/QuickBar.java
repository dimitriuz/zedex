package dev.ldlab.zedex;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The strip of icons in the corner of the picture.
 *
 * ☰ opens a sheet, and a sheet is the right shape for reading a list of
 * choices — but the things done most often are not read, they are reached for:
 * a save state, a screenshot, the keyboard out of the way. Those become one
 * tap here rather than three through the sheet, and they are icons because a
 * word in the corner of the picture is a word over the picture.
 *
 * A <em>group</em> is an icon that opens a short list underneath rather than
 * doing something, so *Capture* offers a screenshot, a GIF and an MP4 without
 * leaving the bar. Only one group is open at a time and acting on anything
 * shuts it, so the bar is one strip again by the time you look back at it.
 *
 * The bar itself is icons alone and the list under it is not. An icon in the
 * bar is a place you have learned, and five of them are learnable; a list is
 * read once and its choices are not guessable from a picture — a dot and a
 * strip of film do not say *GIF* and *MP4*, and nothing drawn says whether
 * tapping the joystick will show it or hide it. So the list carries words, and
 * being a list rather than a row it has the width for them.
 *
 * Both rows hang off the same corner — the top right of the picture, where ☰
 * has always been — so the bar grows leftwards and downwards into the frame
 * instead of pushing anything about. Everything in it is named to
 * accessibility, since an icon has no text to read: that is what a screen
 * reader announces and what the tests and {@code scripts/ui-tap.py} address it
 * by.
 */
final class QuickBar extends LinearLayout {

    /** What goes in the list when a group is opened. */
    interface Row {
        void fill(QuickBar bar);
    }

    /** Big enough for a thumb, small enough to leave the picture alone. */
    private static final int BUTTON_DP = 44;
    private static final int ICON_DP = 22;

    /** The dropdown's own metrics: a smaller icon, and room for words. */
    private static final int LIST_ICON_DP = 20;
    private static final int LIST_PAD_DP = 14;
    private static final float LIST_TEXT_SP = 15;

    /** Dark enough to read a white icon against any Spectrum screen. */
    private static final int BACKING = 0x99000000;
    private static final int ICON = 0xffededf2;

    /** The open group's own icon, so it is clear which row belongs to what. */
    private static final int ICON_OPEN = 0xff00b0c8;

    private final LinearLayout primary;
    private final LinearLayout secondary;
    private final int button;
    private final int icon;

    /** Which group is showing its row, so tapping it again puts it away. */
    private View openGroup;

    QuickBar(Context context) {
        super(context);

        float density = getResources().getDisplayMetrics().density;
        button = Math.round(BUTTON_DP * density);
        icon = Math.round(ICON_DP * density);

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

    /** An icon that does something, and shuts any open group on its way. */
    void addAction(int drawable, String name, Runnable action) {
        primary.addView(makeButton(drawable, name, () -> {
            collapse();
            action.run();
        }));
    }

    /**
     * An icon that opens the second row instead of doing something. The row is
     * built each time it is opened, so it can offer <em>Stop recording</em>
     * only while something is recording.
     */
    void addGroup(int drawable, String name, Row row) {
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
    void addToRow(int drawable, String name, Runnable action) {
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

    /** Puts the dropdown away, wherever the tap came from. */
    void collapse() {
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
