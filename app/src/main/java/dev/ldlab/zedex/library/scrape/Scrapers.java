package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.storage.Prefs;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Set;

/**
 * Which provider a scrape uses.
 *
 * One today and three planned, so this exists to be the one place that
 * changes when the second arrives - rather than every entry point growing its
 * own {@code new ScreenScraper(...)} and its own idea of what to do when none
 * is configured.
 *
 * A build with no credentials has no providers at all, and {@link #any} is how
 * every menu row finds out before offering itself. That is the ordinary state
 * of a source download; see {@code app/build.gradle}.
 */
public final class Scrapers {

    private Scrapers() {
    }

    /** The one to use, or null when this build cannot scrape. */
    public static Provider preferred(Context context) {
        Provider screenScraper = new ScreenScraper(context, new Http.Real());
        return screenScraper.configured() ? screenScraper : null;
    }

    /**
     * The one to use, with the user's own account when they have set one.
     *
     * Their login buys a real daily allowance and is the only mitigation
     * available for the fact that the shared developer credentials are in the
     * APK and readable - see {@code Prefs.KEY_SCRAPER_USER}. It does not
     * replace the developer id, which identifies the application and is sent
     * either way; it is an extra pair of fields on the same request.
     *
     * An empty name means no account, and an empty password with a name set
     * means the same: half a login is a request that authenticates as nobody
     * and is refused, which reads to the user as the service being broken.
     */
    public static Provider withAccount(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        String user = preferences.getString(Prefs.KEY_SCRAPER_USER, "");
        String password = preferences.getString(Prefs.KEY_SCRAPER_PASSWORD, "");

        if (user == null || user.trim().isEmpty()
                || password == null || password.isEmpty()) {
            return preferred(context);
        }

        Provider screenScraper = new ScreenScraper(context, new Http.Real(),
                                                   user.trim(), password);
        return screenScraper.configured() ? screenScraper : null;
    }

    /** Whether anything can be scraped from at all. */
    public static boolean any(Context context) {
        return preferred(context) != null;
    }

    /**
     * Which media a scrape should fetch, from the person's own choice.
     *
     * One place, read by both entry points - the popup's one-game scrape and
     * the sweep - so that the two cannot disagree about what a scrape takes.
     * The sweep's own screen can edit it, but it edits <em>this</em> key
     * rather than keeping a second answer of its own.
     *
     * <b>Absent is not empty.</b> Nothing stored means nobody has chosen and
     * the default applies; an empty set means somebody deliberately chose
     * metadata only, which is legitimate and the cheapest scrape there is.
     * {@code getStringSet} returns null for the first and an empty set for
     * the second, and collapsing the two would make "none" unselectable.
     */
    public static Provider.Wanted wanted(Context context) {
        Set<String> chosen = context
                .getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE)
                .getStringSet(Prefs.KEY_SCRAPE_MEDIA, null);

        return chosen == null ? Provider.Wanted.usual() : Provider.Wanted.of(chosen);
    }
}
