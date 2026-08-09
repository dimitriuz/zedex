package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.storage.Prefs;

import android.content.SharedPreferences;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Every setting Fuse itself holds, and both ways it can be told about one.
 *
 * There were two hand-kept lists for one set of settings: a command line
 * built at cold start, because Fuse reads most of its options as arguments,
 * and a switch in the settings screen that pushed a change into a Fuse
 * already running. They had already drifted - {@code divmmc} had a case in
 * the switch and no line in the command line, and was handled by bespoke code
 * in {@link Machine} instead; {@code mouse} and its sensitivity were in
 * neither and reached Fuse only by a third path, a re-read in
 * {@code EmulatorActivity.onResume}.
 *
 * What that costs is not a crash. Adding a setting means guessing which of
 * the places to put it in, and guessing wrong is silent: the setting stores,
 * the screen shows it, and nothing happens until the app is started again.
 * CLAUDE.md records exactly that once already - "the keyboard type bug looked
 * broken but was just late".
 *
 * One row per setting now. A row says how the setting is pushed into a
 * running Fuse and, where Fuse takes it as an argument, how it is written on
 * the command line - so the two can no longer disagree about whether a
 * setting exists, only about what it is worth, which is what they are for.
 *
 * What is deliberately not here: the machine itself, which is chosen at start
 * and changed by a command of its own; the picture's scale and border, which
 * are as much the layout's business as Fuse's and are applied through the
 * screen that owns the layout; and the joystick, whose value comes from the
 * controls rather than from a preference. Each is one thing in one place
 * already.
 */
public final class FuseSettings {

    private FuseSettings() {
    }

    /**
     * One setting: its key, how to push it, and how it appears as an argument.
     *
     * {@code arguments} is null for a setting Fuse has no command-line option
     * for - it is pushed after the machine starts and that is the only way it
     * ever arrives.
     */
    private static final class Setting {
        final String key;
        final Consumer<SharedPreferences> push;
        final BiConsumer<SharedPreferences, List<String>> arguments;

        Setting(String key, Consumer<SharedPreferences> push,
                BiConsumer<SharedPreferences, List<String>> arguments) {
            this.key = key;
            this.push = push;
            this.arguments = arguments;
        }
    }

    /** A boolean written as Fuse writes them: --thing or --no-thing. */
    private static BiConsumer<SharedPreferences, List<String>> flag(
            String key, boolean fallback, String option) {
        return (preferences, out) ->
                out.add(preferences.getBoolean(key, fallback) ? "--" + option
                                                              : "--no-" + option);
    }

    /** A number written as its own option and value. */
    private static BiConsumer<SharedPreferences, List<String>> value(
            String key, int fallback, String option) {
        return (preferences, out) -> {
            out.add("--" + option);
            out.add(String.valueOf(Prefs.number(preferences, key, fallback)));
        };
    }

    private static final Setting[] ALL = {

        // Three of Fuse's own options in three combinations; see
        // OPTION_LOADER_ACCELERATION in android_bridge.c for why these three.
        new Setting(Prefs.KEY_LOADER,
                p -> FuseNative.setLoaderAcceleration(Loader.levelOf(p)),
                (p, out) -> {
                    int level = Loader.levelOf(p);
                    out.add(level > 0 ? "--traps" : "--no-traps");
                    out.add(level > 0 ? "--fastload" : "--no-fastload");
                    out.add(level > 1 ? "--accelerate-loader"
                                      : "--no-accelerate-loader");
                }),

        new Setting(Prefs.KEY_DETECT_LOADER,
                p -> FuseNative.setDetectLoader(p.getBoolean(Prefs.KEY_DETECT_LOADER, true)),
                flag(Prefs.KEY_DETECT_LOADER, true, "detect-loader")),

        new Setting(Prefs.KEY_TAPE_SOUND,
                p -> FuseNative.setTapeSound(p.getBoolean(Prefs.KEY_TAPE_SOUND, true)),
                flag(Prefs.KEY_TAPE_SOUND, true, "loading-sound")),

        new Setting(Prefs.KEY_AUTOLOAD,
                p -> FuseNative.setAutoLoad(p.getBoolean(Prefs.KEY_AUTOLOAD, true)),
                flag(Prefs.KEY_AUTOLOAD, true, "auto-load")),

        new Setting(Prefs.KEY_ISSUE2,
                p -> FuseNative.setIssue2(p.getBoolean(Prefs.KEY_ISSUE2, false)),
                flag(Prefs.KEY_ISSUE2, false, "issue2")),

        new Setting(Prefs.KEY_BW_TV,
                p -> FuseNative.setBlackAndWhite(p.getBoolean(Prefs.KEY_BW_TV, false)),
                flag(Prefs.KEY_BW_TV, false, "bw-tv")),

        new Setting(Prefs.KEY_SOUND,
                p -> FuseNative.setSound(p.getBoolean(Prefs.KEY_SOUND, true)),
                flag(Prefs.KEY_SOUND, true, "sound")),

        new Setting(Prefs.KEY_TURBOSOUND,
                p -> FuseNative.setTurboSound(p.getBoolean(Prefs.KEY_TURBOSOUND, true)),
                flag(Prefs.KEY_TURBOSOUND, true, "turbosound")),

        new Setting(Prefs.KEY_TURBO,
                p -> FuseNative.setTurbo(p.getBoolean(Prefs.KEY_TURBO, false)),
                flag(Prefs.KEY_TURBO, false, "turbo")),

        new Setting(Prefs.KEY_SPEED,
                p -> FuseNative.setSpeed(Prefs.number(p, Prefs.KEY_SPEED, 100)),
                value(Prefs.KEY_SPEED, 100, "speed")),

        new Setting(Prefs.KEY_AY_VOLUME,
                p -> FuseNative.setAyVolume(Prefs.number(p, Prefs.KEY_AY_VOLUME, 100)),
                value(Prefs.KEY_AY_VOLUME, 100, "volume-ay")),

        new Setting(Prefs.KEY_BEEPER_VOLUME,
                p -> FuseNative.setBeeperVolume(Prefs.number(p, Prefs.KEY_BEEPER_VOLUME, 100)),
                value(Prefs.KEY_BEEPER_VOLUME, 100, "volume-beeper")),

        new Setting(Prefs.KEY_AY_STEREO,
                p -> FuseNative.setAyStereo(Stereo.of(p).value),
                (p, out) -> {
                    out.add("--separation");
                    out.add(Stereo.of(p).option);
                }),

        // The one the two lists disagreed about. It had a case in the switch
        // and no line here, so a DivMMC switched on and then a cold start
        // came up without it - repaired by hand in Machine.applyDivmmc, which
        // is where the card itself is inserted anyway and so stays. What was
        // missing was this row saying the setting exists at all.
        new Setting(Prefs.KEY_DIVMMC,
                p -> FuseNative.setDivmmc(p.getBoolean(Prefs.KEY_DIVMMC, false)),
                null),
    };

    /**
     * One filter strength: the preference it is stored under, the index the
     * renderer knows it by, and what it is worth when nothing is stored.
     *
     * Three typed fields rather than the {@code Object[][]} this was. That
     * table had the default written as a String and parsed on every call -
     * at startup, not only on change - so a transposed pair of columns or a
     * typed "5O" for "50" compiled cleanly and then threw ClassCastException
     * or an uncaught NumberFormatException at launch. All three of those are
     * compile errors now, and the parse is gone.
     */
    private static final class FilterKey {
        final String preference;
        final int index;
        final int fallback;

        FilterKey(String preference, int index, int fallback) {
            this.preference = preference;
            this.index = index;
            this.fallback = fallback;
        }
    }

    /** Which effects are on at all is one setting, handled apart from these. */
    private static final FilterKey[] FILTER_KEYS = {
        new FilterKey(Prefs.KEY_FILTER_SHARPNESS, FuseNative.FILTER_SHARPNESS, 100),
        new FilterKey(Prefs.KEY_FILTER_SCANLINE,  FuseNative.FILTER_SCANLINE,   50),
        new FilterKey(Prefs.KEY_FILTER_CURVE,     FuseNative.FILTER_CURVE,      40),
        new FilterKey(Prefs.KEY_FILTER_MASK,      FuseNative.FILTER_MASK,       40),
        new FilterKey(Prefs.KEY_FILTER_GLOW,      FuseNative.FILTER_GLOW,       30),
        new FilterKey(Prefs.KEY_VIDEO,            FuseNative.FILTER_VIDEO,       0),
        new FilterKey(Prefs.KEY_FILTER_BLEED,     FuseNative.FILTER_BLEED,      50),
        new FilterKey(Prefs.KEY_FILTER_NOISE,     FuseNative.FILTER_NOISE,      20),
    };

    /**
     * Pushes every filter number at once.
     *
     * Called at startup as well as on a change, because these are not Fuse
     * settings and so cannot ride in on its command line: the renderer has to
     * be told. Queued like everything else, so doing it before Fuse has started
     * is safe - the commands wait.
     */
    public static void applyFilter(android.content.SharedPreferences preferences) {
        Filter filter = Filter.of(preferences);

        FuseNative.setFilter(FuseNative.FILTER_SCANLINES, filter.scanlines ? 1 : 0);
        FuseNative.setFilter(FuseNative.FILTER_CRT, filter.crt ? 1 : 0);

        for (FilterKey key : FILTER_KEYS) {
            FuseNative.setFilter(key.index,
                                 Prefs.number(preferences,
                                                         key.preference,
                                                         key.fallback));
        }
    }

    /** Whether a key is one of the filters'. */
    private static boolean isFilterKey(String key) {
        if (Prefs.KEY_FILTER.equals(key)) return true;

        for (FilterKey entry : FILTER_KEYS) {
            if (entry.preference.equals(key)) return true;
        }
        return false;
    }

    /**
     * Pushes one setting into a running Fuse, if it is one Fuse holds.
     *
     * Unknown keys are not an error: most of what this app stores is its own
     * business - which screen to open on, whether the bar fades - and never
     * reaches the emulator at all.
     */
    public static boolean push(SharedPreferences preferences, String key) {
        // The ten filter strengths are one group with one push - the renderer
        // takes them together - so they are answered here rather than as ten
        // rows that would each rewrite all of them.
        if (isFilterKey(key)) {
            applyFilter(preferences);
            return true;
        }

        for (Setting setting : ALL) {
            if (setting.key.equals(key)) {
                setting.push.accept(preferences);
                return true;
            }
        }
        return false;
    }

    /** Pushes every one of them, for a caller told that everything changed. */
    public static void pushAll(SharedPreferences preferences) {
        for (Setting setting : ALL) setting.push.accept(preferences);
        applyFilter(preferences);
    }

    /**
     * Every key in the table, for a test that has to walk it.
     *
     * Package private and for that alone. The drift this table exists to stop
     * - a setting with a live push and no command-line argument, so switching
     * it on worked until the app was restarted - is invisible from outside
     * unless something can enumerate what is in here; see
     * {@code FuseSettingsTest}.
     */
    static String[] keys() {
        String[] keys = new String[ALL.length];
        for (int at = 0; at < ALL.length; at++) keys[at] = ALL[at].key;
        return keys;
    }

    /** Adds each setting Fuse takes as an argument to a command line. */
    public static void appendArguments(SharedPreferences preferences,
                                       List<String> out) {
        for (Setting setting : ALL) {
            if (setting.arguments != null) setting.arguments.accept(preferences, out);
        }
    }
}
