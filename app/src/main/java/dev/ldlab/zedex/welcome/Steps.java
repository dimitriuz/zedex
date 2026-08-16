package dev.ldlab.zedex.welcome;

import dev.ldlab.zedex.storage.Storage;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of the {@link Page}s apply, given what has been answered so far.
 *
 * <b>Asked afresh on every move, never settled at the start.</b> Whether the
 * archive page applies depends on whether the folders page chose a content
 * folder, and a list built before the folders page was answered cannot know
 * that. So the activity holds only which page it is on and asks this what
 * comes next - which also means Back walks the pages that were actually
 * shown rather than the ones that were expected.
 *
 * A pure function over the preferences and one capability flag, so the whole
 * rule is testable on the JVM tier. {@code Catalogues.any} needs a Context and
 * is therefore passed in rather than asked here.
 */
public final class Steps {

    private Steps() {
    }

    /**
     * Every page that applies, in order.
     *
     * @param hasCatalogue {@code Catalogues.any(context)} - whether this build
     *                     has an archive to browse at all
     */
    public static List<Page> applicable(SharedPreferences preferences,
                                        boolean hasCatalogue) {
        List<Page> pages = new ArrayList<>();

        for (Page page : Page.values()) {
            if (applies(page, preferences, hasCatalogue)) pages.add(page);
        }

        return pages;
    }

    /** The next page after {@code current}, or null when it is the last. */
    public static Page after(Page current, SharedPreferences preferences,
                             boolean hasCatalogue) {
        List<Page> pages = applicable(preferences, hasCatalogue);
        int at = pages.indexOf(current);

        return at >= 0 && at + 1 < pages.size() ? pages.get(at + 1) : null;
    }

    /** The page before {@code current}, or null when it is the first. */
    public static Page before(Page current, SharedPreferences preferences,
                              boolean hasCatalogue) {
        List<Page> pages = applicable(preferences, hasCatalogue);
        int at = pages.indexOf(current);

        return at > 0 ? pages.get(at - 1) : null;
    }

    private static boolean applies(Page page, SharedPreferences preferences,
                                   boolean hasCatalogue) {
        if (page != Page.LIBRARY) return true;

        // Both halves are needed. Without an archive there is nothing for the
        // provider row to choose between; without a content folder
        // startsInLibrary is false whatever the switch says, so offering the
        // switch would be offering a setting that cannot take effect.
        return hasCatalogue
                && preferences.getString(Storage.KEY_CONTENT_TREE, null) != null;
    }
}
