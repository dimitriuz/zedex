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
     * ScreenScraper first when the build was given a developer id and
     * password, which a source clone was not; then ZXInfo, which needs no
     * credentials at all and so is always here; then zxart last, needing
     * none either. zxart goes after both rather than beside ZXInfo at the
     * front: a new source must not outrank a proven one silently on
     * everybody's collection, and {@link ScrapersOrderTest} pins the order
     * this method actually builds - see {@code
     * ScrapersOrderTest.scrapersAllEndsWithZxart} and {@code
     * withNoScreenScraperCredentialsZxInfoStillComesBeforeZxart}. That
     * ordering is the default priority order for anybody who has never
     * chosen.
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
     * The account is ScreenScraper's alone. Neither ZXInfo nor zxart has
     * accounts, so a login set here changes nothing for either - worth
     * knowing rather than surprising.
     */
    private static List<Provider> all(Context context, String user, String password) {
        List<Provider> providers = new ArrayList<>();

        Provider screenScraper = user == null
                ? new ScreenScraper(context, new Http.Real(context))
                : new ScreenScraper(context, new Http.Real(context), user, password);
        if (screenScraper.configured()) providers.add(screenScraper);

        providers.add(new ZxInfo(new Http.Real(context)));
        providers.add(new Zxart(new Http.Real(context)));

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

        List<String> names = new ArrayList<>();
        for (Provider provider : available) names.add(provider.name());

        List<String> wanted = chosen(preferences.getString(Prefs.KEY_SCRAPERS, null),
                                     preferences.getString(Prefs.KEY_SCRAPER, null),
                                     names);

        List<Provider> using = new ArrayList<>();
        for (String name : wanted) {
            for (Provider provider : available) {
                if (provider.name().equals(name)) {
                    using.add(provider);
                    break;
                }
            }
        }
        return using;
    }

    /**
     * Which sources to ask and in what order, from what is stored and what
     * this build has - by name, and nothing else.
     *
     * <b>Pure, and that is the point.</b> No {@code Context}, no {@code
     * SharedPreferences}, no {@link Provider}: every rule that matters here is
     * about names, so pulling it out means the rules can be tested against
     * names that are made up rather than against whichever services this
     * particular build was given credentials for.
     *
     * That is not tidiness. These rules were tested through {@link #enabled},
     * which needs two real providers to say anything about ordering - so on a
     * source clone, which has no ScreenScraper credentials, three of those
     * tests skipped and the class still printed {@code OK}. A count that drops
     * from seven to four with no other signal is one CI reads as a pass, which
     * makes it a green tick over rules nothing checked.
     *
     * @param stored    {@code Prefs.KEY_SCRAPERS} - null when nobody has ever
     *                  chosen, empty when somebody turned them all off
     * @param single    {@code Prefs.KEY_SCRAPER}, an older build's one choice
     * @param available the names this build actually has, in its own order
     */
    static List<String> chosen(String stored, String single, List<String> available) {
        List<String> wanted;

        if (stored == null) {
            // Nobody has chosen. An older build's single name is a decision
            // and is honoured; nothing at all gets the new default.
            wanted = single != null && !single.isEmpty()
                    ? new ArrayList<>(java.util.Collections.singletonList(single))
                    : new ArrayList<>(available);
        } else if (stored.isEmpty()) {
            // Somebody turned them all off, which is a choice and not a fault.
            wanted = new ArrayList<>();
        } else {
            wanted = new ArrayList<>();
            for (String line : stored.split(SEPARATOR)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) wanted.add(trimmed);
            }
        }

        // A stored name this build does not have - a provider removed, or a
        // build without the credentials the choice was made against - is
        // dropped rather than failing the lot. Duplicates go too: the settings
        // list cannot make one, but a hand-edited preference file can, and
        // asking one service twice about every game is a whole second sweep.
        List<String> keeping = new ArrayList<>();
        for (String name : wanted) {
            if (available.contains(name) && !keeping.contains(name)) keeping.add(name);
        }
        return keeping;
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
