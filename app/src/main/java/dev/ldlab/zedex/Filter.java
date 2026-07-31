package dev.ldlab.zedex;

import android.content.SharedPreferences;

/**
 * What the picture is put through on its way to the glass.
 *
 * Two effects, and the shader keeps them apart for a reason it states itself:
 * scanlines are the beam and the curve, the shadow mask and the glow are the
 * glass in front of it. A real tube has both, and either can be had without
 * the other — so the honest list is four entries and not three.
 *
 * <b>One setting, not two switches.</b> They were {@code scanlines} and
 * {@code crt}, two booleans that happened to be next to each other, and the
 * settings screen showed them with their strengths interleaved: ten rows to say
 * what the picture looks like. One row of four choices says the same thing, and
 * the strengths go behind <i>Advanced</i> where nobody has to read them to find
 * the border. {@link #migrate} carries the old pair over the first time.
 *
 * The quick bar still offers the two separately, because turning scanlines off
 * to read something is a decision of the moment and it should not cost a trip
 * through a list — {@link #withScanlines} and {@link #withCrt} are how it asks
 * for one without disturbing the other.
 */
enum Filter {

    /** Nothing: what the machine drew, scaled. */
    OFF("off", R.string.filter_off, false, false),

    /** The beam alone: a dark line per emulated row. */
    SCANLINES("scanlines", R.string.filter_scanlines, true, false),

    /** The glass alone: curve, shadow mask, glow. */
    CRT("crt", R.string.filter_crt, false, true),

    /** Both, which is what a tube actually does. */
    BOTH("both", R.string.filter_both, true, true);

    /** What is stored. */
    final String value;

    /** One line, for the list and for the quick bar's note. */
    final int title;

    final boolean scanlines;
    final boolean crt;

    Filter(String value, int title, boolean scanlines, boolean crt) {
        this.value = value;
        this.title = title;
        this.scanlines = scanlines;
        this.crt = crt;
    }

    /** The same glass, with the beam turned on or off. */
    Filter withScanlines(boolean on) {
        return of(on, crt);
    }

    /** The same beam, with the glass put in front of it or taken away. */
    Filter withCrt(boolean on) {
        return of(scanlines, on);
    }

    static Filter of(boolean scanlines, boolean crt) {
        for (Filter filter : values()) {
            if (filter.scanlines == scanlines && filter.crt == crt) return filter;
        }
        return OFF;
    }

    static Filter of(SharedPreferences preferences) {
        return of(preferences.getString(SettingsActivity.KEY_FILTER, null));
    }

    /** The stored value, or {@link #OFF} for anything unrecognised. */
    static Filter of(String stored) {
        if (stored != null) {
            for (Filter filter : values()) {
                if (filter.value.equals(stored)) return filter;
            }
        }
        return OFF;
    }

    /** Writes it, for the quick bar; the settings list writes its own. */
    void store(SharedPreferences preferences) {
        preferences.edit().putString(SettingsActivity.KEY_FILTER, value).apply();
    }

    /**
     * Carries a device set up before there was one setting over to it.
     *
     * Called once at startup, before anything reads the filter. The two old
     * booleans are removed rather than left behind: leaving them would mean two
     * answers to the same question on disk, and the wrong one is the one that
     * survives an uninstall of this code.
     *
     * <b>It writes nothing when there is nothing to carry.</b> A device that has
     * never run the app has no preferences at all, and that is precisely how
     * {@link StartPanel#setupNeeded} knows to ask where the files should go —
     * one key written here before it looks would answer that question for it,
     * and the first-start screen would never appear again.
     */
    static void migrate(SharedPreferences preferences) {
        if (preferences.contains(SettingsActivity.KEY_FILTER)) return;

        boolean hadScanlines = preferences.contains(OLD_SCANLINES);
        boolean hadCrt = preferences.contains(OLD_CRT);

        if (!hadScanlines && !hadCrt) return;

        preferences.edit()
                .putString(SettingsActivity.KEY_FILTER,
                           of(preferences.getBoolean(OLD_SCANLINES, false),
                              preferences.getBoolean(OLD_CRT, false)).value)
                .remove(OLD_SCANLINES)
                .remove(OLD_CRT)
                .apply();
    }

    private static final String OLD_SCANLINES = "scanlines";
    private static final String OLD_CRT = "crt";
}
