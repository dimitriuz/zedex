package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.scrape.Provider;
import dev.ldlab.zedex.library.scrape.Scrapers;
import dev.ldlab.zedex.screen.ScraperOrderEntry;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.welcome.Step;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Which services a scrape asks, in what order, and an optional account of
 * your own.
 *
 * <b>Starts nothing.</b> This sets up who would be asked, exactly as {@code
 * SettingsActivity}'s own scraper-order row does - nothing gathered in the
 * background, no request until somebody actually asks for one, which is the
 * whole of what {@code docs/PRIVACY.md} promises.
 */
public final class ScrapingPage implements Step {

    /** {@code ScraperOrderEntry.show} needs an {@code Activity} to host its
     *  own dialog, not a bare {@code Context} - the same reason {@code
     *  LanguagePage} takes a {@code Runnable} instead of doing its own
     *  recreate(). */
    private final Activity activity;

    private EditText user;
    private EditText password;

    public ScrapingPage(Activity activity) {
        this.activity = activity;
    }

    @Override
    public int title() {
        return R.string.welcome_scraping;
    }

    @Override
    public int blurb() {
        return R.string.welcome_scraping_hint;
    }

    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // The same two calls SettingsActivity's own scraper-order row makes:
        // every name this build can offer, and the ones already chosen (in
        // their own order), read straight off Scrapers rather than off a
        // preference this page would have to parse itself.
        List<String> available = Scrapers.names(context);

        List<String> enabled = new ArrayList<>();
        for (Provider provider : Scrapers.enabled(context)) {
            enabled.add(provider.name());
        }

        column.addView(Cards.choice(context,
                R.string.welcome_scraping_sources, 0,
                v -> ScraperOrderEntry.show(activity, available, enabled,
                        chosen -> Scrapers.save(activity, chosen)),
                false));

        column.addView(Cards.note(context, R.string.welcome_scraping_account));

        user = new EditText(context);
        user.setHint(R.string.settings_scraper_user);
        user.setText(preferences.getString(Prefs.KEY_SCRAPER_USER, ""));
        column.addView(user);

        password = new EditText(context);
        password.setHint(R.string.settings_scraper_password);
        password.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(preferences.getString(Prefs.KEY_SCRAPER_PASSWORD, ""));
        column.addView(password);

        TextView why = Cards.note(context);
        why.setText(R.string.welcome_scraping_account_hint);
        column.addView(why);

        return column;
    }

    /**
     * The account, settled on the way out rather than per keystroke.
     *
     * This is the one page with something to apply: every other page writes
     * as it is touched, so skipping it writes nothing. Skipping this one
     * writes nothing either, which is why the fields are read here and not
     * from a TextWatcher.
     */
    @Override
    public void apply(SharedPreferences preferences) {
        preferences.edit()
                .putString(Prefs.KEY_SCRAPER_USER,
                           user.getText().toString().trim())
                .putString(Prefs.KEY_SCRAPER_PASSWORD,
                           password.getText().toString())
                .apply();
    }
}
