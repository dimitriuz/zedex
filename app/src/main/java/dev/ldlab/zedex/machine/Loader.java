package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.storage.Prefs;

import android.content.SharedPreferences;

/**
 * How hard to push a tape.
 *
 * Three of Fuse's own options in three combinations - traps, fastload and
 * accelerate-loader - reduced to one setting with three words, because they
 * are only ever wanted together and in that order. See
 * OPTION_LOADER_ACCELERATION in android_bridge.c.
 *
 * Here rather than on the settings screen: {@link Machine} needs the same
 * number for its command line before Fuse has finished starting, and reaching
 * up into a screen for it was one of the two things still doing that.
 */
public final class Loader {

    private Loader() {
    }

    /** The stored words, and the level each one means. */
    private static final String[] LEVELS = { "off", "safe", "turbo" };

    /** As {@link FuseNative#setLoaderAcceleration} wants it. */
    public static int levelOf(SharedPreferences preferences) {
        String stored = preferences.getString(Prefs.KEY_LOADER, null);

        // Migrating from the boolean this replaced: it was all or nothing, so
        // whichever end it was at is the end to start from.
        if (stored == null) {
            return preferences.getBoolean(Prefs.KEY_FAST_TAPE, true)
                    ? LEVELS.length - 1 : 0;
        }

        for (int level = 0; level < LEVELS.length; level++) {
            if (LEVELS[level].equals(stored)) return level;
        }

        return LEVELS.length - 1;
    }
}
