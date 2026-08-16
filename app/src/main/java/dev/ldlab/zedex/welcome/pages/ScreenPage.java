package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.machine.Border;
import dev.ldlab.zedex.machine.Filter;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.welcome.Step;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
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

        // A Cards.Group so tapping a different filter moves the cyan
        // highlight there live, rather than leaving it on the row that was
        // current when the page was built - see MachinePage and
        // ControlsPage for the same shape.
        Cards.Group filters = new Cards.Group();

        for (Filter filter : Filter.values()) {
            ImageView still = new ImageView(context);
            still.setImageResource(stillFor(filter));
            still.setAdjustViewBounds(true);
            column.addView(still);

            column.addView(filters.add(context, filter.title, 0,
                    v -> filter.store(preferences),
                    filter == chosen));
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
