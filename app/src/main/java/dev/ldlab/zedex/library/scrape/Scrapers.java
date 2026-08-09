package dev.ldlab.zedex.library.scrape;

import android.content.Context;

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

    /** Whether anything can be scraped from at all. */
    public static boolean any(Context context) {
        return preferred(context) != null;
    }
}
