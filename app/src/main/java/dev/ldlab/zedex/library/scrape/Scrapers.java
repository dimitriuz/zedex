package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.storage.Prefs;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which provider a scrape uses.
 *
 * The one place that knows there is more than one, so no entry point grows its
 * own {@code new ScreenScraper(...)} and its own idea of what to do when none
 * is configured.
 *
 * <b>One service answers everything.</b> Whichever is chosen does the searching
 * and the fetching for every scrape. Merging fields from two was considered and
 * rejected: two sources disagreeing about a name or a year needs a rule per
 * field, ownership stops being one provider name, and every conflict is
 * invisible when it goes wrong.
 *
 * Rows keep carrying the name of whoever wrote them, so switching provider and
 * running <em>Everything, again</em> re-scrapes cleanly, and
 * {@code Sweep.Only.NOT_SCRAPED} still means "not scraped by this app" rather
 * than "not scraped by the one currently selected" - which is the right answer,
 * since having already scraped a game is having already scraped it.
 */
public final class Scrapers {

    private Scrapers() {
    }

    /**
     * Every provider this build can offer, best first.
     *
     * ZXInfo needs no credentials at all, so it is always here; ScreenScraper
     * is only here when the build was given a developer id and password, which
     * a source clone was not. That ordering is also the fallback order - see
     * {@link #preferred}.
     *
     * ScreenScraper first, and only because it came first and is what existing
     * rows were scraped from. For a Spectrum collection specifically ZXInfo is
     * the better answer - it hash-matches like ScreenScraper does, its ratings
     * come from hundreds of votes rather than a handful, and its media cost
     * nothing against a daily allowance - so this default is worth revisiting
     * once there is more than one person's collection to judge it on.
     */
    public static List<Provider> all(Context context) {
        List<Provider> providers = new ArrayList<>();

        Provider screenScraper = new ScreenScraper(context, new Http.Real());
        if (screenScraper.configured()) providers.add(screenScraper);

        providers.add(new ZxInfo(new Http.Real()));

        return providers;
    }

    /** The names, for a settings list. Not translated: they are the services'
     *  own names. */
    public static List<String> names(Context context) {
        List<String> names = new ArrayList<>();
        for (Provider provider : all(context)) names.add(provider.name());
        return names;
    }

    /**
     * The one to use, or null when this build can scrape from nothing.
     *
     * A stored name that no longer matches anything - a provider removed, or a
     * build without the credentials the choice was made against - falls back to
     * the first available rather than to nothing. Losing the ability to scrape
     * because a preference went stale would be a worse answer than quietly
     * using the other service.
     */
    public static Provider preferred(Context context) {
        return chosen(context, all(context));
    }

    /**
     * The one to use, with the user's own ScreenScraper account when they have
     * set one.
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
     *
     * The account is ScreenScraper's alone. ZXInfo has no accounts, so a login
     * set here changes nothing when it is the chosen provider - which is worth
     * knowing rather than surprising.
     */
    public static Provider withAccount(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        String user = preferences.getString(Prefs.KEY_SCRAPER_USER, "");
        String password = preferences.getString(Prefs.KEY_SCRAPER_PASSWORD, "");

        boolean hasAccount = user != null && !user.trim().isEmpty()
                && password != null && !password.isEmpty();
        if (!hasAccount) return preferred(context);

        List<Provider> providers = new ArrayList<>();

        Provider screenScraper =
                new ScreenScraper(context, new Http.Real(), user.trim(), password);
        if (screenScraper.configured()) providers.add(screenScraper);

        providers.add(new ZxInfo(new Http.Real()));

        return chosen(context, providers);
    }

    private static Provider chosen(Context context, List<Provider> providers) {
        if (providers.isEmpty()) return null;

        String wanted = context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE)
                .getString(Prefs.KEY_SCRAPER, null);

        if (wanted != null) {
            for (Provider provider : providers) {
                if (provider.name().equals(wanted)) return provider;
            }
        }

        return providers.get(0);
    }

    /** Whether anything can be scraped from at all. Always true now that one
     *  provider needs no credentials, and asked anyway: a build could yet ship
     *  without it, and every menu row checks this before offering itself. */
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
