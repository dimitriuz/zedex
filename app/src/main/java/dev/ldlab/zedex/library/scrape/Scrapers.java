package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.storage.Prefs;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which providers a scrape uses, and in what order.
 *
 * The one place that knows there is more than one, so no entry point grows its
 * own {@code new ScreenScraper(...)} and its own idea of what to do when none
 * is configured.
 *
 * <b>Several services answer, in a priority order.</b> This used to say the
 * opposite - that one service answered everything, and that merging two was
 * rejected because "two sources disagreeing about a name or a year needs a
 * rule per field, ownership stops being one provider name, and every conflict
 * is invisible when it goes wrong". Each of those is answered rather than
 * overridden:
 *
 * <ul>
 * <li>There is one rule for every field, not a rule per field: a source may
 *     fill a gap and may never overwrite ({@link Merge}). The order decides
 *     who gets the gap.</li>
 * <li>Ownership is still legible; it is plural. {@code Meta.source} is a list
 *     of contributors, and the two predicates that read it generalised
 *     cleanly - a link owns a row only when ES-DE is its sole contributor.</li>
 * <li>The only conflict anybody can lose something to is a picture, and a
 *     picture is the one thing that can be shown. A sweep never replaces one;
 *     a one-game scrape puts the alternatives on screen side by side.</li>
 * </ul>
 *
 * Rows keep carrying the names of whoever wrote them, so {@code
 * Sweep.Only.NOT_SCRAPED} still means "not scraped by this app" rather than
 * "not scraped by whichever is currently first".
 */
public final class Scrapers {

    private Scrapers() {
    }

    /** One per line - a service name has spaces in it and could one day have
     *  a comma, and a newline is the one character it will not have. */
    private static final String SEPARATOR = "\n";

    /**
     * Every provider this build can offer, best first.
     *
     * ZXInfo needs no credentials at all, so it is always here; ScreenScraper
     * is only here when the build was given a developer id and password, which
     * a source clone was not. That ordering is the default priority order for
     * anybody who has never chosen.
     */
    public static List<Provider> all(Context context) {
        return all(context, null, null);
    }

    /**
     * Every provider, with the user's own ScreenScraper account when they have
     * set one.
     *
     * Their login buys a real daily allowance and is the only mitigation
     * available for the fact that the shared developer credentials are in the
     * APK and readable - see {@code Prefs.KEY_SCRAPER_USER}. It does not
     * replace the developer id, which identifies the application and is sent
     * either way.
     *
     * The account is ScreenScraper's alone. ZXInfo has no accounts, so a login
     * set here changes nothing for it - worth knowing rather than surprising.
     */
    private static List<Provider> all(Context context, String user, String password) {
        List<Provider> providers = new ArrayList<>();

        Provider screenScraper = user == null
                ? new ScreenScraper(context, new Http.Real(context))
                : new ScreenScraper(context, new Http.Real(context), user, password);
        if (screenScraper.configured()) providers.add(screenScraper);

        providers.add(new ZxInfo(new Http.Real(context)));

        return providers;
    }

    /** The names, for the settings list. Not translated: they are the
     *  services' own names. */
    public static List<String> names(Context context) {
        List<String> names = new ArrayList<>();
        for (Provider provider : all(context)) names.add(provider.name());
        return names;
    }

    /**
     * The sources to ask, in the order to ask them, with the user's own
     * account applied.
     *
     * Empty when this build can scrape from nothing, and empty when somebody
     * has turned every source off - which is a choice and not a fault.
     *
     * A stored name this build does not have - a provider removed, or a build
     * without the credentials the choice was made against - is skipped rather
     * than failing the lot.
     */
    public static List<Provider> enabled(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        String user = preferences.getString(Prefs.KEY_SCRAPER_USER, "");
        String password = preferences.getString(Prefs.KEY_SCRAPER_PASSWORD, "");

        // Half a login is a request that authenticates as nobody and is
        // refused, which reads to the user as the service being broken.
        boolean hasAccount = user != null && !user.trim().isEmpty()
                && password != null && !password.isEmpty();

        List<Provider> available = hasAccount
                ? all(context, user.trim(), password)
                : all(context);

        List<String> wanted = order(preferences, available);

        List<Provider> chosen = new ArrayList<>();
        for (String name : wanted) {
            for (Provider provider : available) {
                if (provider.name().equals(name)) {
                    chosen.add(provider);
                    break;
                }
            }
        }
        return chosen;
    }

    /**
     * The stored order, or what to do when there is none.
     *
     * <b>Absent is not empty.</b> Nothing stored means nobody has chosen and
     * everything is used; an empty value means somebody turned them all off.
     * {@code getString} answers null for the first and "" for the second, and
     * collapsing the two would make "none" unselectable - the same trap
     * {@code Prefs.KEY_SCRAPE_MEDIA} carries a warning about.
     */
    private static List<String> order(SharedPreferences preferences,
                                      List<Provider> available) {
        String stored = preferences.getString(Prefs.KEY_SCRAPERS, null);

        if (stored == null) return migrated(preferences, available);
        if (stored.isEmpty()) return new ArrayList<>();

        List<String> names = new ArrayList<>();
        for (String line : stored.split(SEPARATOR)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        return names;
    }

    /**
     * What an older build's single choice becomes.
     *
     * <b>Faithfully, not generously:</b> one stored name becomes that one
     * source and every other off. Widening what the app fetches - and what it
     * spends a ScreenScraper allowance on - because a feature arrived is not a
     * decision to make on somebody's behalf. Nothing stored at all means
     * nobody ever chose, and that gets the new default: everything, in
     * {@link #all}'s order.
     *
     * Not written back. Reading it every time costs one string lookup, and
     * writing it would turn "the user has never chosen" into "the user chose
     * exactly this", which is a lie that cannot be undone.
     */
    private static List<String> migrated(SharedPreferences preferences,
                                         List<Provider> available) {
        String single = preferences.getString(Prefs.KEY_SCRAPER, null);

        if (single != null && !single.isEmpty()) {
            return new ArrayList<>(java.util.Collections.singletonList(single));
        }

        List<String> everything = new ArrayList<>();
        for (Provider provider : available) everything.add(provider.name());
        return everything;
    }

    /** Stores the sources to use, in order. An empty list is stored as an
     *  empty value, which is "none" and not "nobody has chosen". */
    public static void save(Context context, List<String> namesInOrder) {
        context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(Prefs.KEY_SCRAPERS, String.join(SEPARATOR, namesInOrder))
                .apply();
    }

    /** Whether anything can be scraped from at all. Every menu row checks this
     *  before offering itself. */
    public static boolean any(Context context) {
        return !enabled(context).isEmpty();
    }

    /**
     * Which media a scrape should fetch, from the person's own choice.
     *
     * One place, read by both entry points - the popup's one-game scrape and
     * the sweep - so that the two cannot disagree about what a scrape takes.
     *
     * <b>Absent is not empty.</b> Nothing stored means nobody has chosen and
     * the default applies; an empty set means somebody deliberately chose
     * metadata only, which is legitimate and the cheapest scrape there is.
     */
    public static Provider.Wanted wanted(Context context) {
        Set<String> chosen = context
                .getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE)
                .getStringSet(Prefs.KEY_SCRAPE_MEDIA, null);

        return chosen == null ? Provider.Wanted.usual() : Provider.Wanted.of(chosen);
    }
}
