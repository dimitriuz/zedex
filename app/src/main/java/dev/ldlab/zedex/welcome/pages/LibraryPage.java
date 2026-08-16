package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.catalogue.Catalogue;
import dev.ldlab.zedex.library.catalogue.Catalogues;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.welcome.Step;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Whether the app opens on the library, and which archive it browses.
 *
 * {@code Steps} is what decides whether this page is even reached - it
 * applies only with a catalogue this build can browse <em>and</em> a content
 * folder already chosen, since without one {@code startsInLibrary} is false
 * whatever the switch here says. Nothing here re-checks that; a page that
 * exists only asks what it was built to ask.
 */
public final class LibraryPage implements Step {

    @Override
    public int title() {
        return R.string.welcome_library;
    }

    @Override
    public int blurb() {
        return R.string.welcome_library_hint;
    }

    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        Switch open = new Switch(context);
        open.setText(R.string.welcome_library_start);
        open.setChecked(preferences.getBoolean(Prefs.KEY_LIBRARY, true));
        open.setOnCheckedChangeListener((button, on) ->
                preferences.edit().putBoolean(Prefs.KEY_LIBRARY, on).apply());
        column.addView(open);

        TextView startHint = Cards.note(context);
        startHint.setText(R.string.welcome_library_start_hint);
        column.addView(startHint);

        column.addView(Cards.note(context, R.string.welcome_library_archive));

        // Catalogues.preferred already applies the same fallback this page
        // must show as the leading card: a stored name that matches nothing
        // - nobody has chosen yet - falls back to the first catalogue rather
        // than leading nothing, exactly as MachinePage's own default machine
        // does for Prefs.KEY_MACHINE.
        Catalogue preferred = Catalogues.preferred(context);
        String chosen = preferred != null ? preferred.name() : null;

        // A Cards.Group so tapping a different archive moves the cyan
        // highlight there live, rather than leaving it on whichever card was
        // current when the page was built - see ScreenPage for the same
        // shape, with two groups instead of one.
        Cards.Group archives = new Cards.Group();

        for (Catalogue catalogue : Catalogues.all(context)) {
            String name = catalogue.name();

            // Catalogues.preferred matches Prefs.KEY_CATALOGUE against
            // Catalogue.name() directly, so that is what gets stored here too
            // - not a lower-cased id, there being none to lower-case.
            column.addView(archives.add(context, name, 0,
                    v -> preferences.edit()
                            .putString(Prefs.KEY_CATALOGUE, name).apply(),
                    name.equals(chosen)));
        }

        return column;
    }
}
