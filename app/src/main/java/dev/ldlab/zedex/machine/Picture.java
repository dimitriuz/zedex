package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.storage.Prefs;

import android.content.Context;
import android.content.res.Configuration;

/**
 * How much of the picture is shown, and how large.
 *
 * The border and the scale, which were static methods on the settings screen
 * that {@link Machine} and the emulator screen both reached up into. They are
 * not that screen's: it is one of three callers and the least interesting of
 * them, since it only forwards a change somebody made.
 *
 * Not in {@link FuseSettings} either, and that is the distinction worth
 * keeping: the scale depends on which way up the device is, so it is asked
 * again on a rotation rather than only when a preference changes, and its
 * value is as much a function of the display as of what is stored.
 */
public final class Picture {

    private Picture() {
    }

    /**
     * Pushes the scale for the way up the device is now, like
     * {@link #applyFilter} and for the same reason. EmulatorActivity does this
     * again on a rotation; the settings screen only ever needs the current one,
     * since changing the other orientation's cannot show until it turns.
     */
    public static void applyScale(Context context,
                           android.content.SharedPreferences preferences) {
        FuseNative.setScale(scale(preferences, isLandscape(context)));
    }

    /** The border, which the renderer crops and the scale list counts. */
    public static void applyBorder(android.content.SharedPreferences preferences) {
        FuseNative.setBorder(Border.of(preferences).ordinal());
    }

    /**
     * The stored scale for one orientation, or {@link FuseNative#SCALE_FIT}.
     *
     * Fitting is the default, and also what an unparseable value means: a scale
     * that was possible on the display the setting was made on may not be on
     * this one, and the picture being the wrong size is worse than it being the
     * size it has always been.
     */
    public static int scale(android.content.SharedPreferences preferences,
                     boolean landscape) {
        return Prefs.number(preferences,
                landscape ? Prefs.KEY_SCALE_LANDSCAPE : Prefs.KEY_SCALE_PORTRAIT,
                FuseNative.SCALE_FIT);
    }

    /** Which way up the device is; both the scale settings hang off this. */
    public static boolean isLandscape(Context context) {
        return context.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

}
