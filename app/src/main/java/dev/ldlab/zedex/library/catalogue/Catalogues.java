package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.storage.Prefs;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Which catalogue the library browses.
 *
 * {@code Scrapers}' opposite number, and deliberately the same shape - {@link
 * #all}, {@link #preferred}, {@link #any} - so the two read as siblings rather
 * than as two people's ideas of the same job. One answers "what does this
 * service know about a file I already have"; this one answers "what is there
 * that I have not got".
 *
 * <b>Registration is a hand-written list, not discovery.</b> Exactly as {@code
 * Scrapers.all} is: a reflective scan of the package would make the set of
 * catalogues a fact about the build rather than a decision somebody made, and
 * the order here <em>is</em> the fallback order - see {@link #preferred}.
 *
 * <b>{@link #any} answers one question: whether there is a catalogue at all.</b>
 * It decides whether {@code LibraryActivity} builds the tab, and nothing else.
 * {@code startsInLibrary} once gated both "where does the app start" and "is
 * there a library at all", and turning the switch off removed the only way
 * back in - one predicate must not answer two questions.
 */
public final class Catalogues {

    private Catalogues() {
    }

    /**
     * Every catalogue this build can browse, best first.
     *
     * ZXInfo needs no credentials, so it is always here - which is why it is
     * the one that ships first. A catalogue that did need them would be added
     * the way {@code Scrapers.all} adds ScreenScraper, behind its own {@link
     * Catalogue#configured()}, so a build without them offers no tab rather
     * than a tab that can only fail.
     */
    public static List<Catalogue> all(Context context) {
        List<Catalogue> catalogues = new ArrayList<>();

        Catalogue zxInfo = new ZxInfoCatalogue(new Http.Real(context));
        if (zxInfo.configured()) catalogues.add(zxInfo);

        // The locale comes from this context, not the device: every
        // activity's attachBaseContext has already applied the app's own
        // language preference, and Locale.getDefault() would be a second
        // mechanism for the same fact.
        Catalogue zxart = new ZxartCatalogue(new Http.Real(context),
                                             context.getResources().getConfiguration()
                                                    .getLocales().get(0));
        if (zxart.configured()) catalogues.add(zxart);

        return catalogues;
    }

    /**
     * The one to browse, or null when this build can browse nothing.
     *
     * A stored name that matches nothing - a catalogue removed, or a build
     * without whatever the choice was made against - falls back to the first
     * available rather than to nothing, the same bargain {@code Scrapers}
     * makes: losing the tab because a preference went stale is a worse answer
     * than quietly using the other service.
     */
    public static Catalogue preferred(Context context) {
        List<Catalogue> catalogues = all(context);
        if (catalogues.isEmpty()) return null;

        String wanted = context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE)
                .getString(Prefs.KEY_CATALOGUE, null);

        if (wanted != null) {
            for (Catalogue catalogue : catalogues) {
                if (catalogue.name().equals(wanted)) return catalogue;
            }
        }

        return catalogues.get(0);
    }

    /** Whether there is anything to browse at all - what decides whether the
     *  library builds its fourth tab, and the only question this answers. */
    public static boolean any(Context context) {
        return preferred(context) != null;
    }
}
