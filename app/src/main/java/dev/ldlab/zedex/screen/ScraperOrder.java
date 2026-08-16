package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Which services to scrape from, and in what order.
 *
 * <b>Arrows rather than drag.</b> With two or three entries drag buys nothing,
 * and arrows are reachable by a pad and by a screen reader, which drag is not -
 * this app is driven by a gamepad often enough that a control only a finger
 * can work is a control some people do not have.
 *
 * The list shown is every source this build has, in the order they will be
 * asked, with the disabled ones after the enabled ones. Rebuilt in place on
 * every move rather than animated: it is three rows.
 *
 * <b>Public, not package-private any more.</b> {@code welcome.ScrapingPage}
 * calls {@link #show} from a different package - a member another layer
 * needs has to be public, or the boundary stops it, exactly as {@code
 * FoldersPage} widened whatever the wizard needed of {@code storage}.
 */
public final class ScraperOrder {

    /** What the dialog came to: the enabled names, in order. */
    public interface Chosen {
        void take(List<String> namesInOrder);
    }

    private ScraperOrder() {
    }

    public static void show(Activity activity, List<String> available, List<String> enabled,
                     Chosen onSave) {
        // Enabled first and in their own order, then whatever is left - which
        // is what the list means: the order it will ask them in.
        List<String> order = new ArrayList<>(enabled);
        for (String name : available) {
            if (!order.contains(name)) order.add(name);
        }

        List<String> ticked = new ArrayList<>(enabled);

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);

        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        rows.setPadding(padding, padding, padding, padding);

        draw(activity, rows, order, ticked);

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.settings_scraper)
                .setView(rows)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<String> chosen = new ArrayList<>();
                    for (String name : order) {
                        if (ticked.contains(name)) chosen.add(name);
                    }
                    onSave.take(chosen);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void draw(Activity activity, LinearLayout rows, List<String> order,
                             List<String> ticked) {
        rows.removeAllViews();

        for (int at = 0; at < order.size(); at++) {
            final int position = at;
            String name = order.get(at);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            CheckBox box = new CheckBox(activity);
            box.setText(name);
            box.setChecked(ticked.contains(name));
            box.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            box.setOnCheckedChangeListener((view, isChecked) -> {
                if (isChecked) {
                    if (!ticked.contains(name)) ticked.add(name);
                } else {
                    ticked.remove(name);
                }
            });

            row.addView(box);
            row.addView(arrow(activity, "▲", R.string.settings_scrapers_up,
                              position > 0,
                              () -> swap(activity, rows, order, ticked, position,
                                         position - 1)));
            row.addView(arrow(activity, "▼", R.string.settings_scrapers_down,
                              position < order.size() - 1,
                              () -> swap(activity, rows, order, ticked, position,
                                         position + 1)));

            rows.addView(row);
        }
    }

    /**
     * One arrow.
     *
     * Disabled rather than hidden at the ends of the list, so the rows do not
     * change width as things move and the two buttons stay where the finger
     * last found them.
     */
    private static Button arrow(Activity activity, String glyph, int description,
                                boolean usable, Runnable move) {
        Button button = new Button(activity);
        button.setText(glyph);
        button.setContentDescription(activity.getString(description));
        button.setEnabled(usable);
        button.setOnClickListener(view -> move.run());
        return button;
    }

    private static void swap(Activity activity, LinearLayout rows, List<String> order,
                             List<String> ticked, int from, int to) {
        String moved = order.remove(from);
        order.add(to, moved);

        draw(activity, rows, order, ticked);
    }
}
