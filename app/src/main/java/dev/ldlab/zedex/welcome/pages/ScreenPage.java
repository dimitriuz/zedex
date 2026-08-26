package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.machine.Border;
import dev.ldlab.zedex.machine.Filter;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.welcome.Step;

import android.content.Context;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * What the picture should look like.
 *
 * <b>Photographs, not a preview.</b> The filter is a GL shader in
 * native/ui/android/android_gl.c running on Fuse's framebuffer, and there is
 * no Fuse when this page is shown - the wizard runs before the machine
 * starts, because the ROMs go into a folder the wizard has not settled yet.
 * A Java reimplementation for preview purposes would be a second copy of the
 * shader that nothing keeps in step with the first, so these are captures of
 * the real emulator instead. They cannot flatter a filter into looking like
 * something it is not, and nothing recaptures them when the shader changes -
 * that gap is recorded in docs/DEVELOPING.md.
 *
 * <b>Where the photograph sits depends on the room there is</b>, for the
 * same reason as {@link ControlsPage}: landscape has room sideways, so the
 * filter rows and the picture of the chosen one sit side by side; portrait
 * does not, so the list comes first and the photograph under it at full
 * width. Tapping a row swaps what the photograph shows. The border question
 * stays a plain list either way - its three answers have no picture to show.
 */
public final class ScreenPage implements Step {

    @Override
    public int title() {
        return R.string.welcome_screen;
    }

    @Override
    public int blurb() {
        return R.string.welcome_screen_hint;
    }

    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        Filter chosen = Filter.of(preferences);

        // The one photograph of the filter that is on: tapping a row swaps
        // what it shows rather than rebuilding it.
        ImageView still = new ImageView(context);
        still.setImageResource(stillFor(chosen));
        still.setAdjustViewBounds(true);

        LinearLayout filtersColumn = new LinearLayout(context);
        filtersColumn.setOrientation(LinearLayout.VERTICAL);

        // A Cards.Group so tapping a different filter moves the cyan
        // highlight there live, rather than leaving it on the row that was
        // current when the page was built - see MachinePage and
        // ControlsPage for the same shape.
        Cards.Group filters = new Cards.Group();

        for (Filter filter : Filter.values()) {
            filtersColumn.addView(filters.add(context, filter.title, 0,
                    v -> {
                        filter.store(preferences);
                        still.setImageResource(stillFor(filter));
                    },
                    filter == chosen));
        }

        // Landscape has room sideways: the rows and the photograph of the
        // chosen one sit side by side. Portrait does not - see ControlsPage
        // for the same rule - so the list comes first and the photograph
        // under it at full width.
        if (context.getResources().getConfiguration()
                .orientation == Configuration.ORIENTATION_LANDSCAPE) {
            LinearLayout sideBySide = new LinearLayout(context);
            sideBySide.setOrientation(LinearLayout.HORIZONTAL);

            sideBySide.addView(filtersColumn, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout.LayoutParams stillLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            stillLp.leftMargin = Cards.unit(context, 2);
            sideBySide.addView(still, stillLp);

            column.addView(sideBySide);
        } else {
            column.addView(filtersColumn);
            column.addView(still);
        }

        column.addView(Cards.note(context, R.string.welcome_screen_border));

        Border border = Border.of(preferences);

        // Border has no store(SharedPreferences) of its own - only Filter
        // does, for the quick bar - so the key is written explicitly here.
        Cards.Group borders = new Cards.Group();

        for (Border edge : Border.values()) {
            column.addView(borders.add(context, edge.title, 0,
                    v -> preferences.edit()
                            .putString(Prefs.KEY_BORDER, edge.value).apply(),
                    edge == border));
        }

        return column;
    }

    /**
     * Which capture goes with which filter.
     *
     * A switch here rather than a field on the enum: Filter lives in
     * machine/, which has no business knowing that a drawable of it exists.
     */
    private static int stillFor(Filter filter) {
        switch (filter) {
            case SCANLINES: return R.drawable.filter_scanlines;
            case CRT:       return R.drawable.filter_crt;
            case BOTH:      return R.drawable.filter_both;
            default:        return R.drawable.filter_off;
        }
    }
}
