package dev.ldlab.zedex.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

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
     * DetailPane.advanceToVideo}. Default true, which is what the
     * app already did before this switch existed, so upgrading changes
     * nobody's behaviour. Off leaves the video reachable by swiping to it,
     * same as always - only the automatic move is what this turns off.
     * Written here by a plain SwitchPreferenceCompat and read by
     * LibraryActivity; this screen never reads it back.
     */
    public static final String KEY_LIBRARY_VIDEO_AUTOPLAY = "libraryVideoAutoplay";
    /**
     * A ScreenScraper account of the user's own, optional, and the reason it
     * is offered.
     *
     * The app ships a developer id and password, and <b>they are readable by
     * anyone who has the APK</b> - {@code aapt2 dump resources} prints them,
     * no decompiler needed, and no amount of obfuscation changes that because
     * a client-side secret is not a thing that exists. What that shared
     * developer account can do is therefore what an attacker can do, and the
     * damage worth caring about is not somebody reading the password: it is
     * the id being hammered and <em>banned</em>, which would end scraping for
     * every install at once.
     *
     * So the shared account is meant to carry casual use and nothing more.
     * Anyone scraping a whole collection is asked to bring their own, which
     * also buys them a real daily allowance - see {@code ScreenScraper},
     * which has always taken {@code ssid}/{@code sspassword} and until now
     * was never given any.
     *
     * Both are strings, written by an {@code EditTextPreference}. <b>Both are
     * excluded from backup and device transfer</b> - see
     * {@code res/xml/backup_rules.xml}: a password is not ours to put in
     * somebody's cloud account, and carrying the name without it would
     * restore a half-configured login that fails authentication with nothing
     * on screen to say why. Neither goes anywhere near a bug report;
     * {@code Diagnostics} names the keys it prints one at a time, and
     * {@code DiagnosticsTest} checks these two are not among them.
     */
    public static final String KEY_SCRAPER_USER = "scraperUser";
    public static final String KEY_SCRAPER_PASSWORD = "scraperPassword";
    /**
     * Which media a scrape fetches, as ES-DE folder names.
     *
     * A {@code StringSet}, written by a {@code MultiSelectListPreference} and
     * turned into a {@code Provider.Wanted} by {@code Scrapers.wanted} -
     * which is why the stored values are folder names rather than an enum or
     * a bitmask: {@code Wanted} has always been addressed by folder, so the
     * setting hands straight through with nothing to translate between.
     *
     * <b>Each one costs a request per game.</b> A cover is a {@code
     * mediaJeu.php} call exactly like a search is, so this is the setting
     * that decides whether a collection fits in a day: the default three are
     * four requests a game, and all eight would be nine.
     *
     * Absent means the default, which is the three this app actually draws
     * everywhere - {@code Provider.Wanted.usual}. <b>An empty set is not the
     * same as absent</b> and must not be treated as one: it means metadata
     * only, which is a real thing to want and the cheapest scrape there is.
     * {@code getStringSet} tells them apart by returning null for absent.
     */
    public static final String KEY_SCRAPE_MEDIA = "scrapeMedia";
    /**
     * Which services a scrape asks, by {@code Provider.name()}, in the order
     * it asks them - one per line.
     *
     * A {@code String} and not a {@code StringSet}, although it is a set of
     * choices, because a set has no order and the order <em>is</em> the
     * feature: the first source to answer about a field keeps it, and every
     * later one may only fill what is still missing.
     *
     * A new key rather than a reuse of {@link #KEY_SCRAPER}, which held a
     * single name written by a {@code ListPreference}. The two must never
     * disagree about what they hold - see {@code scripts/check-prefs.py} for
     * why a key with two types is a crash that survives every test on a fresh
     * install.
     *
     * <b>Absent is not empty.</b> Nothing stored means nobody has chosen and
     * every source this build has is used; an empty value means somebody
     * deliberately turned them all off, which switches scraping off and is a
     * real thing to want. The same distinction {@link #KEY_SCRAPE_MEDIA}
     * turns on, and it is lost the moment the two are collapsed.
     */
    public static final String KEY_SCRAPERS = "scrapers";
    /**
     * Which service a scrape asks, by {@code Provider.name()}.
     *
     * A name rather than an index, so that adding or reordering providers
     * cannot silently repoint somebody's choice at a different service - and
     * so a value this build does not recognise falls back to the default
     * rather than to whatever happens to be second in a list.
     *
     * <b>Superseded by {@link #KEY_SCRAPERS}</b>, which holds several names in
     * priority order. This is read once to migrate a choice made by an older
     * build - faithfully, meaning that one name becomes that one source and
     * the others off - and never written again.
     */
    public static final String KEY_SCRAPER = "scraper";
    /**
     * Which catalogue the library browses, by its own {@code name()}.
     *
     * A String, and read with {@code getString} always. The same reasoning as
     * {@link #KEY_SCRAPER} beside it, for the same reason: a name rather than
     * an index, and absent means whichever {@code Catalogues} prefers. Nothing
     * writes it yet - there is one catalogue - which is why it reads as a
     * default rather than as a choice; it is declared here now so that a
     * settings row, when there is one to add, has a key to write rather than a
     * name invented at the point of use.
     */
    public static final String KEY_CATALOGUE = "catalogueProvider";
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

    /**
     * Whether the first run is still to be answered.
     *
     * <b>Not "are the preferences empty".</b> That is what this used to ask,
     * from {@code StartPanel.setupNeeded}, and it was answered for it: {@code
     * LibraryActivity} is the launcher and writes {@code libraryMigrated} in
     * its own {@code onCreate} before {@code EmulatorActivity} ever gets here,
     * so the file was never empty and the folders question was never put. The
     * app settled where it kept everything without asking, and nothing said
     * so. {@code SettingsActivity} had already learned this for its own
     * migration and written it down; this is the caller that did not get the
     * memo.
     *
     * The honest question is whether this install has ever been updated, which
     * nothing this process does to its own preferences can change.
     *
     * @param everUpdated {@link #isUpdate}, passed rather than asked, so the
     *                    rule can be tested on the JVM tier - a device test
     *                    cannot pose "an install that has been updated" either
     */
    public static boolean welcomeNeeded(boolean everUpdated,
                                        SharedPreferences preferences) {
        if (preferences.getBoolean(Storage.KEY_SETUP_DONE, false)) return false;
        return !everUpdated;
    }

    /** {@link #welcomeNeeded(boolean, SharedPreferences)}, asking the package
     *  manager the question it needs. */
    public static boolean welcomeNeeded(Context context,
                                        SharedPreferences preferences) {
        return welcomeNeeded(isUpdate(context), preferences);
    }

    /**
     * Whether this install has ever replaced itself.
     *
     * {@code firstInstallTime} and {@code lastUpdateTime} are the same instant
     * for exactly as long as an install has never been updated, and differ from
     * the first update on. Unlike the preferences file, that is unaffected by
     * anything this process has written.
     *
     * Any failure to read it answers "yes, this is an update" - the
     * conservative direction, since it is an existing user who must not be
     * interrogated, never a new one who must not be asked.
     */
    public static boolean isUpdate(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.firstInstallTime != info.lastUpdateTime;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return true;
        }
    }
}
