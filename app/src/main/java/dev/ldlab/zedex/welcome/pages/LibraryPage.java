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

import java.util.List;

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

        column.addView(Cards.note(context, R.string.welcome_library_archive));

        // Fetched once: Catalogues.preferred(context) would answer the same
        // Catalogue this page is about to draw cards for, but only by
        // calling Catalogues.all(context) a second time to get it - two
        // otherwise-identical lists of the same two lightweight objects.
        // Reading the fallback here instead - a stored name nothing
        // matches, including "nothing stored", leads on the first entry -
        // is the same rule Catalogues.preferred states in its own comment,
        // applied to the one list this method already has.
        List<Catalogue> catalogues = Catalogues.all(context);
        String stored = preferences.getString(Prefs.KEY_CATALOGUE, null);

        String chosen = null;
        for (Catalogue catalogue : catalogues) {
            if (catalogue.name().equals(stored)) {
                chosen = stored;
                break;
            }
        }
        if (chosen == null && !catalogues.isEmpty()) chosen = catalogues.get(0).name();

        // A Cards.Group so tapping a different archive moves the cyan
        // highlight there live, rather than leaving it on whichever card was
        // current when the page was built - see ScreenPage for the same
        // shape, with two groups instead of one.
        Cards.Group archives = new Cards.Group();

        for (Catalogue catalogue : catalogues) {
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
