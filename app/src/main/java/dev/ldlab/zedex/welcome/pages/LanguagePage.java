package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.screen.Language;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.welcome.Step;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Welcome, and the language, on one page.
 *
 * Together deliberately: choosing a language recreates the activity onto this
 * same page, so the proof that the choice took is the page you are standing
 * on. A language page anywhere else in the wizard would prove it by changing
 * the <em>next</em> page, which is a worse demonstration and a slower one.
 */
public final class LanguagePage implements Step {

    /** What the activity does when a language is chosen: recreate itself. */
    private final Runnable chosen;

    public LanguagePage(Runnable chosen) {
        this.chosen = chosen;
    }

    @Override
    public int title() {
        return R.string.welcome_title;
    }

    @Override
    public int blurb() {
        return R.string.welcome_message;
    }

    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        String[] names = context.getResources()
                .getStringArray(R.array.language_names);
        String[] values = context.getResources()
                .getStringArray(R.array.language_values);

        String current = preferences.getString(Language.KEY_LANGUAGE, "");

        for (int i = 0; i < names.length && i < values.length; i++) {
            String value = values[i];

            // The hint explains what "the system language" means, which only
            // the empty value needs saying about it - every other row is
            // already the name of the language it chooses. Nine identical
            // captions were what pushed the way past the wizard below the
            // fold; 0 is Cards' own "no caption" now.
            int description = value.isEmpty() ? R.string.welcome_language_hint : 0;

            // The one already in force leads, in the icon's cyan, so the page
            // says what it is set to as well as what it could be set to.
            column.addView(Cards.choiceOf(context, names[i], description,
                    v -> {
                        preferences.edit()
                                .putString(Language.KEY_LANGUAGE, value)
                                .apply();
                        chosen.run();
                    },
                    value.equals(current)));
        }

        return column;
    }
}
