package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Artwork;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Media onto disk, and the checking that makes it safe to keep.
 *
 * The same work whichever provider named the file, which is why it is here
 * rather than in {@link ScreenScraper}: deciding what a game is called differs
 * completely between the three services, and downloading a picture does not
 * differ at all.
 *
 * <b>A download that arrived wrong is deleted, not kept.</b> ScreenScraper
 * gives an MD5 for each medium, and a truncated cover that stays on disk is
 * indistinguishable from a real one for ever after - {@code Artwork} tests
 * length and readability, both of which a half a picture passes. This is the
 * same lesson as the updater's checksum, and the same reason: the cost of
 * getting it wrong is silent and permanent, and the check is nearly free.
 */
public final class Downloads {

    private static final String TAG = "Zedex";

    private Downloads() {
    }

    /**
     * What one game's media came to.
     *
     * Counted rather than thrown, because a scrape that got the cover and
     * missed the video has still largely worked, and a multi-scrape needs to
     * carry on rather than stop at the first missing manual.
     */
    public static final class Result {
        public final int saved;
        public final int failed;

        Result(int saved, int failed) {
            this.saved = saved;
            this.failed = failed;
        }

        public boolean anything() {
            return saved > 0;
        }
    }

    /**
     * Fetches every medium for one game into this app's own media folder.
     *
     * The game is forgotten from {@code Artwork}'s caches afterwards and only
     * if something arrived: a miss is cached, so without this the cover just
     * written stays invisible until something else happens to clear it - and
     * forgetting one game rather than all five hundred is why {@link
     * Artwork#forget(String)} takes a path.
     *
     * @param relativePath the game's own key, {@code ./folder/Game.tap}
     */
    public static Result fetch(Context context, Http http, String relativePath,
                               List<Medium> media) {
        int saved = 0;
        int failed = 0;

        for (Medium medium : media) {
            if (save(context, http, relativePath, medium)) saved++;
            else failed++;
        }

        if (saved > 0) Artwork.forget(relativePath);

        return new Result(saved, failed);
    }

    private static boolean save(Context context, Http http, String relativePath,
                                Medium medium) {
        File into = Artwork.fileFor(context, relativePath, medium.folder, medium.extension);

        try {
            String got = http.save(medium.url, into);

            if (medium.md5 != null && !medium.md5.equalsIgnoreCase(got)) {
                Log.w(TAG, medium.folder + " for " + relativePath + " hashed " + got
                           + ", provider said " + medium.md5 + "; discarding it");
                delete(into);
                return false;
            }

            if (into.length() == 0) {
                // A 200 with nothing behind it. Keeping it would be a file
                // Artwork reads as absent anyway, and clutter that never
                // heals - fileFor would hand back the same name next time.
                delete(into);
                return false;
            }

            return true;
        } catch (IOException e) {
            Log.w(TAG, "cannot fetch " + medium.folder + " for " + relativePath, e);
            delete(into);
            return false;
        }
    }

    /** A half-written file must not be left where a reader would take it for
     *  a whole one. */
    private static void delete(File file) {
        if (file.exists() && !file.delete()) Log.w(TAG, "cannot remove " + file);
    }
}
