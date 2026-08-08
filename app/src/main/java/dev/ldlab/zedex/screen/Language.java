package dev.ldlab.zedex.screen;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * What language the app speaks.
 *
 * The phone's, unless somebody says otherwise: an empty preference means follow
 * it, which is what a fresh install does and what most people never change. The
 * override exists because a phone set to one language is not always the language
 * its owner wants a Spectrum emulator in — and because half the machines this
 * emulates were sold in countries whose language Android will happily not be in.
 *
 * One mechanism for every version the app supports, rather than two. Android 13
 * has a per-app language of its own, in Settings › Apps, and it would be the
 * nicer home for this — but it does not exist on 11 or 12, which this app still
 * runs on, and an app that had both would have two controls able to disagree.
 * So the preference is the only truth, and every activity puts it on in
 * {@code attachBaseContext}: resources are resolved against the context an
 * activity was built with, so a screen speaks whatever was chosen when it
 * opened, and changing it recreates what is open.
 */
public final class Language {

    /** Written by the list in Settings; empty means the phone's own. */
    public static final String KEY_LANGUAGE = "language";

    private Language() {
    }

    /** The chosen tag, or empty for the phone's own. */
    public static String tag(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS, Context.MODE_PRIVATE);

        // getString and not getAll: the list writes a String, and an absent key
        // is the answer here rather than a missing one.
        String tag = preferences.getString(KEY_LANGUAGE, "");
        return tag == null ? "" : tag;
    }

    /**
     * The language actually in force for a context, chosen or inherited.
     *
     * {@link #tag} answers what the *preference* says, and an empty answer -
     * follow the phone - is the default. That makes it useless for noticing a
     * change: with no preference set it reads "" before a system language
     * change and "" after it, so a screen comparing it concludes nothing
     * happened. This resolves what the resources were actually built with, so
     * both kinds of change show up.
     */
    public static String effectiveTag(Context context) {
        Configuration configuration = context.getResources().getConfiguration();

        return configuration.getLocales().isEmpty()
                ? ""
                : configuration.getLocales().get(0).toLanguageTag();
    }

    /**
     * The context an activity should be built on.
     *
     * Called from {@code attachBaseContext}, which runs before anything else an
     * activity has — so it takes its preferences from the context it is given
     * rather than from a field that does not exist yet.
     */
    public static Context wrap(Context base) {
        Configuration theirs = base.getResources().getConfiguration();
        String tag = tag(base);

        if (tag.isEmpty()) {
            // Back to the phone's own, which has to put the default back too:
            // it is process wide, so a language chosen and then unchosen would
            // otherwise keep formatting dates until the app was killed.
            Locale.setDefault(theirs.getLocales().get(0));
            return base;
        }

        Locale locale = Locale.forLanguageTag(tag);

        // The default too, and not only the resources: a date in the save state
        // list and anything through String.format go by it, and a screen half in
        // one language reads worse than a screen in the wrong one.
        Locale.setDefault(locale);

        Configuration configuration = new Configuration(theirs);
        configuration.setLocale(locale);

        return base.createConfigurationContext(configuration);
    }
}
