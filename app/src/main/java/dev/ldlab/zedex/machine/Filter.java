package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.R;
import android.content.SharedPreferences;

/**
 * What the picture is put through on its way to the glass.
 *
 * Four values and not three: the shader keeps the beam and the glass apart —
 * scanlines are one, the curve, mask and glow the other — and a real tube has
 * both, so dropping the combination would lose the best-looking one.
 *
 * One setting where there were two booleans, because the settings screen showed
 * them with their strengths interleaved: ten rows to say what the picture looks
 * like. The quick bar still offers the two separately, which is what
 * {@link #withScanlines} and {@link #withCrt} are for.
 */
public enum Filter {

    /** Nothing: what the machine drew, scaled. */
    OFF("off", R.string.filter_off, false, false),

    /** The beam alone: a dark line per emulated row. */
    SCANLINES("scanlines", R.string.filter_scanlines, true, false),

    /** The glass alone: curve, shadow mask, glow. */
    CRT("crt", R.string.filter_crt, false, true),

    /** Both, which is what a tube actually does. */
    BOTH("both", R.string.filter_both, true, true);

    /** What is stored. */
    public final String value;

    /** One line, for the list and for the quick bar's note. */
    public final int title;

    public final boolean scanlines;
    public final boolean crt;

    Filter(String value, int title, boolean scanlines, boolean crt) {
        this.value = value;
        this.title = title;
        this.scanlines = scanlines;
        this.crt = crt;
    }

    /** The same glass, with the beam turned on or off. */
    public Filter withScanlines(boolean on) {
        return of(on, crt);
    }

    /** The same beam, with the glass put in front of it or taken away. */
    public Filter withCrt(boolean on) {
        return of(scanlines, on);
    }

    public static Filter of(boolean scanlines, boolean crt) {
        for (Filter filter : values()) {
            if (filter.scanlines == scanlines && filter.crt == crt) return filter;
        }
        return OFF;
    }

    public static Filter of(SharedPreferences preferences) {
        return of(preferences.getString(Prefs.KEY_FILTER, null));
    }

    /** The stored value, or {@link #OFF} for anything unrecognised. */
    public static Filter of(String stored) {
        if (stored != null) {
            for (Filter filter : values()) {
                if (filter.value.equals(stored)) return filter;
            }
        }
        return OFF;
    }

    /** Writes it, for the quick bar; the settings list writes its own. */
    public void store(SharedPreferences preferences) {
        preferences.edit().putString(Prefs.KEY_FILTER, value).apply();
    }

    /**
     * Carries a device set up before there was one setting over to it.
     *
     * Called once at startup, before anything reads the filter. The two old
     * booleans are removed rather than left behind: leaving them would mean two
     * answers to the same question on disk, and the wrong one is the one that
     * survives an uninstall of this code.
     *
     * <b>It writes nothing when there is nothing to carry.</b> Neither old
     * boolean present means there was never an old setting to carry forward,
     * so the early return above leaves the preferences file exactly as it
     * found it rather than writing a default {@link Prefs#KEY_FILTER} - two
     * answers to the same question on disk is one too many, which is the
     * whole reason this migration exists in the first place.
     */
    public static void migrate(SharedPreferences preferences) {
        if (preferences.contains(Prefs.KEY_FILTER)) return;

        boolean hadScanlines = preferences.contains(OLD_SCANLINES);
        boolean hadCrt = preferences.contains(OLD_CRT);

        if (!hadScanlines && !hadCrt) return;

        preferences.edit()
                .putString(Prefs.KEY_FILTER,
                           of(preferences.getBoolean(OLD_SCANLINES, false),
                              preferences.getBoolean(OLD_CRT, false)).value)
                .remove(OLD_SCANLINES)
                .remove(OLD_CRT)
                .apply();
    }

    private static final String OLD_SCANLINES = "scanlines";
    private static final String OLD_CRT = "crt";
}
