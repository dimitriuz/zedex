package dev.ldlab.zedex.storage;

/**
 * Every setting this app stores, by name.
 *
 * These lived on {@code screen.SettingsActivity} - the file that draws the
 * settings screen - and thirteen classes across machine, storage, media,
 * input, view, feedback, frontend and library reached up into a screen to
 * find out what a preference was called. A schema is not a screen: the
 * screen is one of its readers, and the least important of them, since it
 * only shows what everything else acts on.
 *
 * In {@code storage} because that is what a preference is - state that
 * outlives the process - and because everything that needs these already
 * depends on this package.
 *
 * A note that has cost this project a shipped crash: <em>a preference's type
 * is whatever wrote it</em>. {@code joystickType} is written with
 * {@code putInt} and {@code getString} on it throws, but only once the key is
 * present, so it passes every fresh-install test. {@code scripts/check-prefs.py}
 * greps for that disagreement; keeping the names here does not make it a typed
 * schema, and the script is still what catches it.
 */
public final class Prefs {

    private Prefs() {
    }

    public static final String PREFS = "fuse";

    // Keys shared with EmulatorActivity.
    public static final String KEY_MACHINE = "machine";
    /** "off", "safe" or "turbo"; {@link #LOADER_LEVELS} turns it into a number. */
    public static final String KEY_LOADER = "loaderAcceleration";
    public static final String KEY_DETECT_LOADER = "detectLoader";
    /** The boolean this replaced, kept only long enough to migrate from it. */
    public static final String KEY_FAST_TAPE = "fastTape";
    public static final String KEY_TAPE_SOUND = "tapeSound";
    public static final String KEY_AUTOLOAD = "autoLoad";
    public static final String KEY_ISSUE2 = "issue2";
    /** The DivMMC interface; the card itself is in the ☰ Media page. */
    public static final String KEY_DIVMMC = "divmmc";
    /** Not a stored value: the row that imports the firmware file. */
    public static final String KEY_DIVMMC_FIRMWARE = "divmmcFirmware";

    public static final String KEY_BW_TV = "bwTv";
    public static final String KEY_SPEED = "speed";
    public static final String KEY_BORDER = "border";
    public static final String KEY_MOUSE = "kempstonMouse";
    public static final String KEY_MOUSE_SENSITIVITY = "mouseSensitivity";
    public static final String KEY_SOUND = "sound";
    public static final String KEY_AY_VOLUME = "volumeAy";
    /** Fuse's own three words; it matches them with strcmp. */
    public static final String KEY_AY_STEREO = "ayStereo";
    /**
     * A second AY chip on the machines that could have had one - the Pentagons
     * and the Scorpion. It does nothing at all on any other machine, where the
     * two bytes that select a chip are ordinary register selects.
     */
    public static final String KEY_TURBOSOUND = "turbosound";
    /**
     * A 7MHz Z80, on the machines that had one. Not the speed setting: the
     * frame is still a fiftieth of a second, so only the CPU is faster.
     */
    public static final String KEY_TURBO = "turbo";
    public static final String KEY_BEEPER_VOLUME = "volumeBeeper";
    public static final String KEY_KEEP_SCREEN_ON = "keepScreenOn";
    /**
     * The keyboard, the lamps and the bar on a second display, for a handheld
     * built with one. Read by EmulatorActivity, which owns that window.
     */
    public static final String KEY_SECOND_SCREEN = "secondScreen";
    public static final String KEY_SNAPSHOT_FORMAT = "snapshotFormat";
    /** "tap" or "tzx": what a tape is written as without an explicit extension. */
    public static final String KEY_TAPE_FORMAT = "tapeFormat";
    /** Written from the ☰ Controls menu; there is no preference screen for them. */
    public static final String KEY_JOYSTICK = "joystick";
    /** Whether the on-screen pad steps aside for a real controller. */
    public static final String KEY_JOYSTICK_AUTO_HIDE = "joystickAutoHide";
    /** Written from the quick bar: the picture has the window to itself. */
    public static final String KEY_FULLSCREEN = "fullscreen";
    public static final String KEY_JOYSTICK_TYPE = "joystickType";
    public static final String KEY_KEYBOARD = "keyboard";
    /** Which machine's keyboard is drawn; see SpectrumKeyboardView.Skin. */
    public static final String KEY_KEYBOARD_SKIN = "keyboardSkin";
    /** Read by EmulatorActivity on resume; there is no immediate push for it. */
    public static final String KEY_INDICATORS = "indicators";

    /** Stops the quick bar fading after three seconds; see
     *  EmulatorActivity.keepBarUp. Always on in effect while touch
     *  exploration is, whatever this says. */
    public static final String KEY_KEEP_BAR = "keepBar";
    /**
     * Whether the app opens on the library or on the machine, as it always
     * did. Disabled without a content folder to browse - see
     * docs/LIBRARY.md, "A content folder is the gate" - and kept in step
     * with that in {@link SettingsFragment#updateSummaries}.
     */
    public static final String KEY_LIBRARY = "library";
    /**
     * Set once, the first time {@link #startsInLibrary} runs after this
     * version introduced the library. Never read anywhere else - it exists
     * only to stop that migration running a second time and overriding
     * whatever the user has since chosen.
     */
    public static final String KEY_LIBRARY_MIGRATED = "libraryMigrated";
    /**
     * Whether the library shows the name ES-DE scraped rather than the
     * filename - see docs/LIBRARY.md, "The list shows the scraped name".
     * Written here by a plain SwitchPreferenceCompat and read by
     * LibraryActivity; this screen never reads it back.
     */
    public static final String KEY_LIBRARY_NAMES = "libraryNames";
    /**
     * Whether the pane moves itself to a scraped video three seconds after
     * the selection stops changing - see docs/LIBRARY.md and {@code
     * LibraryActivity.advanceToPaneVideo}. Default true, which is what the
     * app already did before this switch existed, so upgrading changes
     * nobody's behaviour. Off leaves the video reachable by swiping to it,
     * same as always - only the automatic move is what this turns off.
     * Written here by a plain SwitchPreferenceCompat and read by
     * LibraryActivity; this screen never reads it back.
     */
    public static final String KEY_LIBRARY_VIDEO_AUTOPLAY = "libraryVideoAutoplay";
    /* How big the picture is drawn, one per orientation: the number of device
       pixels per emulated pixel, or "0" to fill the space. Stored as strings
       because a ListPreference stores strings, and separate because the two
       ways up of a phone have wildly different room. */
    public static final String KEY_SCALE_PORTRAIT = "scalePortrait";
    public static final String KEY_SCALE_LANDSCAPE = "scaleLandscape";

    /*
     * The picture filter. One key per number the shader takes, because that is
     * what a settings screen can show and what the renderer wants anyway.
     */
    /** One of {@link Filter}'s four words; it was two booleans. */
    public static final String KEY_FILTER = "filter";
    public static final String KEY_FILTER_SHARPNESS = "filterSharpness";
    public static final String KEY_FILTER_SCANLINE = "filterScanline";
    public static final String KEY_FILTER_CURVE = "filterCurve";
    public static final String KEY_FILTER_MASK = "filterMask";
    public static final String KEY_FILTER_GLOW = "filterGlow";
    public static final String KEY_VIDEO = "video";
    public static final String KEY_FILTER_BLEED = "filterBleed";
    public static final String KEY_FILTER_NOISE = "filterNoise";
    /**
     * A number out of a preference that stores one as a String.
     *
     * ListPreference writes strings whatever the values look like, so every
     * numeric setting comes back as one. This was a static on the nested
     * PreferenceFragment - a pure six-line function reachable only through
     * {@code SettingsActivity.SettingsFragment}, which is what half of the
     * upward imports were actually for.
     *
     * The fallback answers both an absent key and an unparseable one: a value
     * that made sense on another device, or a hand-edited file, should leave
     * the setting at its default rather than throwing on the startup path.
     */
    public static int number(android.content.SharedPreferences preferences,
                             String key, int fallback) {
        try {
            return Integer.parseInt(preferences.getString(key, String.valueOf(fallback)));
        } catch (NumberFormatException | ClassCastException e) {
            return fallback;
        }
    }
}
