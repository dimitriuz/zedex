package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.input.ControlProfiles;
import dev.ldlab.zedex.input.Controls;
import dev.ldlab.zedex.view.SpectrumKeyboardView;
import dev.ldlab.zedex.view.SafeArea;
import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.input.Controls;
import dev.ldlab.zedex.machine.Border;
import dev.ldlab.zedex.machine.Filter;
import dev.ldlab.zedex.media.Media;
import dev.ldlab.zedex.frontend.EsDe;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.EsdeLink;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.update.Updater;
import dev.ldlab.zedex.view.EmulatorLayout;
import dev.ldlab.zedex.view.SpectrumKeyboardView;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Settings, backed by the same {@code fuse} preferences file the emulator
 * reads.
 *
 * Everything here is applied twice over: written to preferences so it
 * survives a restart, and pushed to the running emulator through
 * {@link FuseNative} so it takes effect immediately. Only the default
 * machine waits for the next launch, since changing machine mid-game is
 * what the ☰ menu is for.
 */
public class SettingsActivity extends AppCompatActivity
        implements androidx.preference.PreferenceFragmentCompat
                   .OnPreferenceStartScreenCallback {

    /** Every screen speaks the chosen language; see {@link Language}. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Language.wrap(base));
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

    /** The size of the DivMMC's EPROM, and so of any firmware for it. */
    private static final int FIRMWARE_LENGTH = 8 * 1024;
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
     * Each strength, the index it sets and what it is worth by default. Which
     * effects are on at all is one setting and is handled apart from these.
     */
    private static final Object[][] FILTER_KEYS = {
        { KEY_FILTER_SHARPNESS, FuseNative.FILTER_SHARPNESS, "100" },
        { KEY_FILTER_SCANLINE,  FuseNative.FILTER_SCANLINE,  "50"  },
        { KEY_FILTER_CURVE,     FuseNative.FILTER_CURVE,     "40"  },
        { KEY_FILTER_MASK,      FuseNative.FILTER_MASK,      "40"  },
        { KEY_FILTER_GLOW,      FuseNative.FILTER_GLOW,      "30"  },
        { KEY_VIDEO,            FuseNative.FILTER_VIDEO,     "0"   },
        { KEY_FILTER_BLEED,     FuseNative.FILTER_BLEED,     "50"  },
        { KEY_FILTER_NOISE,     FuseNative.FILTER_NOISE,     "20"  },
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

        for (Object[] entry : FILTER_KEYS) {
            FuseNative.setFilter((Integer) entry[1],
                                 SettingsFragment.number(preferences,
                                        (String) entry[0],
                                        Integer.parseInt((String) entry[2])));
        }
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
        return SettingsFragment.number(preferences,
                landscape ? KEY_SCALE_LANDSCAPE : KEY_SCALE_PORTRAIT,
                FuseNative.SCALE_FIT);
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
     * Turns the library off for anyone who already had the app, and leaves it
     * at its own default of on for anyone who did not - see docs/LIBRARY.md,
     * "On for new installs, off for updates". Changing what an update opens
     * on for someone who has had it one way for a year, without asking, is
     * not a thing to do; a fresh install has nobody to ask.
     *
     * "Already had the app" is not a question the preferences can answer, and
     * an earlier version of this asked them anyway: a fresh install writes
     * several of its own within moments of starting - setupDone from the
     * first-run panel, demoInstalled and romsElsewhere from Storage,
     * mediaName the first time anything is opened - so by the time anything
     * calls this, an empty preferences file is not a thing that reliably
     * still exists to test, and every new install read as an update. See
     * {@link #isUpdate} for the question asked instead.
     *
     * Runs once, guarded by {@link #KEY_LIBRARY_MIGRATED}: a user who flips
     * the switch either way afterwards is never put back by a second run of
     * this. Not called directly by whatever decides the launch screen - see
     * {@link #startsInLibrary}, which is.
     */
    private static void migrateLibraryDefault(Context context,
            android.content.SharedPreferences preferences) {
        if (preferences.getBoolean(KEY_LIBRARY_MIGRATED, false)) return;

        android.content.SharedPreferences.Editor edit = preferences.edit();
        if (isUpdate(context)) {
            edit.putBoolean(KEY_LIBRARY, false);
        }
        edit.putBoolean(KEY_LIBRARY_MIGRATED, true);
        edit.apply();
    }

    /**
     * Whether this install has ever been updated - the question
     * {@link #migrateLibraryDefault} actually needs to ask, in place of the
     * preferences file, which a fresh install fills in within moments of
     * starting and so cannot be read as "still empty" by the time anything
     * calls this.
     *
     * {@code firstInstallTime} and {@code lastUpdateTime} are the same
     * instant for exactly as long as an install has never replaced itself,
     * and differ from the first update on - which is unaffected by anything
     * this process has done to its own preferences, unlike
     * {@code getAll().isEmpty()}. Any failure to read it answers "yes, this
     * is an update": the conservative direction, since it is an existing
     * user's launch screen that must not change under them, never a new
     * user's.
     */
    private static boolean isUpdate(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.firstInstallTime != info.lastUpdateTime;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    /**
     * Whether the app should open on the library rather than on the machine.
     *
     * Called by whoever builds the launcher - LibraryActivity, not this
     * class - which is the point of it: that code asks one question and
     * never has to know a migration exists. This runs
     * {@link #migrateLibraryDefault} first, so the very first call after an
     * update settles {@code library} before anything reads it, and then
     * answers with the switch and one more thing the switch alone cannot
     * promise - a content folder that is still granted. The grant is
     * revocable from Android's own settings independently of this app, and a
     * library with the switch on but nothing to list would be worse than the
     * machine it replaced; see docs/LIBRARY.md, "A content folder is the
     * gate".
     */
    public static boolean startsInLibrary(Context context,
            android.content.SharedPreferences preferences) {
        migrateLibraryDefault(context, preferences);

        return preferences.getBoolean(KEY_LIBRARY, true)
                && preferences.getString(Storage.KEY_CONTENT_TREE, null) != null;
    }

    /** Which way up the device is; both the scale settings hang off this. */
    public static boolean isLandscape(Context context) {
        return context.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * The largest whole-pixel scale this display has room for, one orientation
     * at a time.
     *
     * From the display and not from the box the picture will actually get, which
     * is smaller and depends on whether the keyboard is up. Offering a scale that
     * will not fit is harmless - the renderer and the layout both reduce it until
     * it does - and working the box out here would be a second copy of
     * EmulatorLayout's sums, which could only drift.
     */
    public static int maximumScale(Context context, boolean landscape) {
        Rect bounds = context.getSystemService(WindowManager.class)
                .getCurrentWindowMetrics().getBounds();

        int longer = Math.max(bounds.width(), bounds.height());
        int shorter = Math.min(bounds.width(), bounds.height());
        int width = landscape ? longer : shorter;
        int height = landscape ? shorter : longer;

        Border border = Border.of(context.getSharedPreferences(PREFS, MODE_PRIVATE));

        return Math.max(1, Math.min(width / border.width, height / border.height));
    }

    /** Whether a key is one of the filters'. */
    private static boolean isFilterKey(String key) {
        if (KEY_FILTER.equals(key)) return true;

        for (Object[] entry : FILTER_KEYS) {
            if (entry[0].equals(key)) return true;
        }
        return false;
    }

    /** The stored words, and the level each one means. */
    private static final String[] LOADER_LEVELS = { "off", "safe", "turbo" };

    /**
     * AY stereo separation. These are Fuse's own words rather than ours,
     * because {@code --separation} takes them verbatim and Fuse compares them
     * with strcmp — so what is stored is what is passed.
     */
    private static final String[] AY_STEREO = { "None", "ACB", "ABC" };

    /** The stored separation as {@link FuseNative#setAyStereo} wants it. */
    public static int ayStereo(android.content.SharedPreferences preferences) {
        String stored = preferences.getString(KEY_AY_STEREO, AY_STEREO[0]);

        for (int i = 0; i < AY_STEREO.length; i++) {
            if (AY_STEREO[i].equals(stored)) return i;
        }

        return 0;
    }

    /** And as Fuse's command line wants it. */
    public static String ayStereoName(android.content.SharedPreferences preferences) {
        return AY_STEREO[ ayStereo(preferences) ];
    }

    /**
     * How hard to push a tape, as {@link FuseNative#setLoaderAcceleration}
     * wants it. Shared with the emulator, which needs the same number for the
     * command line before Fuse has finished starting.
     */
    public static int loaderLevel(android.content.SharedPreferences preferences) {
        String stored = preferences.getString(KEY_LOADER, null);

        // Migrating from the boolean this replaced: it was all or nothing, so
        // whichever end it was at is the end to start from.
        if (stored == null) {
            return preferences.getBoolean(KEY_FAST_TAPE, true)
                    ? LOADER_LEVELS.length - 1 : 0;
        }

        for (int level = 0; level < LOADER_LEVELS.length; level++) {
            if (LOADER_LEVELS[level].equals(stored)) return level;
        }

        return LOADER_LEVELS.length - 1;
    }

    private static final int REQUEST_CONTENT_TREE = 2;
    private static final int REQUEST_DATA_TREE = 3;
    private static final int REQUEST_FIRMWARE = 4;
    private static final int REQUEST_ESDE_TREE = 5;
    private static final int REQUEST_ESDE_MEDIA_TREE = 6;
    private static final int REQUEST_ESDE_FOLDER = 7;

    /** One tab: what it is called, what it looks like, and what is on it. */
    private static final class Tab {
        final int label, icon, screen;

        Tab(int label, int icon, int screen) {
            this.label = label;
            this.icon = icon;
            this.screen = screen;
        }
    }

    /**
     * Seven tabs, in the order you would go looking through them.
     *
     * Twenty-eight preferences in one list was a scroll nobody could hold in
     * their head, and the picture filters alone were ten of it. The tab is now
     * the grouping, so a category inside one only survives where it still
     * divides something: the picture tab keeps *Filters* and *Display* apart,
     * and *App* holds three unrelated questions - folders, formats, updates.
     * The other four are each one subject and need no headings at all.
     *
     * *Library* sits between *Sound* and *App* - after the machine's own
     * settings, before Zedex's housekeeping, which is what linking to a
     * frontend's metadata is nearer to. See docs/LIBRARY.md, "The second
     * pull request: linking to ES-DE".
     *
     * The last tab is *App* rather than *Files* because what is in it is about
     * Zedex and not about a Spectrum. Everything else here is the machine.
     */
    private static final Tab[] TABS = {
        new Tab(R.string.settings_tab_machine, R.drawable.ic_chip,
                R.xml.settings_machine),
        new Tab(R.string.settings_tab_tape, R.drawable.ic_tape,
                R.xml.settings_tape),
        new Tab(R.string.settings_tab_display, R.drawable.ic_picture,
                R.xml.settings_display),
        new Tab(R.string.settings_tab_controls, R.drawable.ic_controls,
                R.xml.settings_controls),
        new Tab(R.string.settings_tab_sound, R.drawable.ic_sound,
                R.xml.settings_sound),
        new Tab(R.string.settings_tab_library, R.drawable.ic_library,
                R.xml.settings_library),
        new Tab(R.string.settings_tab_app, R.drawable.ic_settings,
                R.xml.settings_app),
    };

    private static final String STATE_TAB = "tab";

    /** Where the fragment goes; any id will do as long as it is ours. */
    private static final int CONTENT_ID = 0x7e5;

    private final List<View> tabViews = new ArrayList<>();
    private int selected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The manifest label is resolved in the phone's language rather than
        // this screen's, so the title is set here; see Language.
        setTitle(R.string.settings_title);

        // The emulator does this too and it is idempotent; here because the
        // list about to be inflated reads the value, and this screen is not
        // reached only through that one.
        Filter.migrate(getSharedPreferences(PREFS, MODE_PRIVATE));

        // Idempotent for the same reason: the launcher is where this really
        // has to run, and opening Settings is not guaranteed to happen
        // before that does. Calling it here as well costs nothing and keeps
        // this screen's own switch right even for somebody who opens it
        // first, without this screen having to know the migration exists.
        startsInLibrary(this, getSharedPreferences(PREFS, MODE_PRIVATE));

        if (savedInstanceState != null) {
            selected = savedInstanceState.getInt(STATE_TAB, 0);
        }

        setContentView(buildTabs());

        // Nothing of ours under the status bar or the camera; see SafeArea.
        SafeArea.fit(findViewById(android.R.id.content));
        show(selected);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt(STATE_TAB, selected);
    }

    /**
     * The tab strip and the space under it.
     *
     * Hand-built, like the ☰ sheet and the quick bar: tabs otherwise mean
     * ViewPager2 and a TabLayout, which would be the app's first dependencies
     * for a row of buttons and a fragment swap.
     *
     * The colours come from the theme rather than from here: this screen follows
     * the device's light or dark setting, and anything hardcoded is wrong under
     * one of them.
     */
    private View buildTabs() {
        LinearLayout root = new LinearLayout(this);
        LinearLayout strip = new LinearLayout(this);
        FrameLayout content = new FrameLayout(this);

        root.setOrientation(LinearLayout.VERTICAL);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        content.setId(CONTENT_ID);

        for (int i = 0; i < TABS.length; i++) {
            View tab = buildTab(TABS[i], i);

            tabViews.add(tab);
            strip.addView(tab, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        root.addView(strip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    /** Icon over a word: an icon alone would be a guess at this size. */
    private View buildTab(Tab tab, int index) {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(10 * density);

        LinearLayout holder = new LinearLayout(this);
        ImageView icon = new ImageView(this);
        TextView label = new TextView(this);
        View underline = new View(this);

        icon.setImageResource(tab.icon);
        icon.setLayoutParams(new LinearLayout.LayoutParams(
                Math.round(24 * density), Math.round(24 * density)));

        label.setText(tab.label);
        label.setTextSize(11);
        label.setSingleLine();
        label.setGravity(Gravity.CENTER);

        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);
        holder.setPadding(0, pad, 0, 0);
        holder.setClickable(true);
        holder.setFocusable(true);
        holder.setBackgroundResource(
                android.R.drawable.list_selector_background);
        holder.setContentDescription(getString(tab.label));
        holder.setOnClickListener(v -> show(index));

        holder.addView(icon);
        holder.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        holder.addView(underline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(2 * density)));

        // Top of the pad above, and the underline sits at the very bottom.
        ((LinearLayout.LayoutParams) underline.getLayoutParams()).topMargin = pad;

        return holder;
    }

    /**
     * A nested PreferenceScreen — <i>Advanced…</i> — being entered.
     *
     * The framework's preferences opened one by themselves; AndroidX asks
     * instead, and does nothing at all if nobody answers. The answer is another
     * fragment over the same file, rooted at that screen's key.
     */
    @Override
    public boolean onPreferenceStartScreen(
            androidx.preference.PreferenceFragmentCompat caller,
            androidx.preference.PreferenceScreen screen) {
        swap(TABS[selected].screen, screen.getKey(), true);
        return true;
    }

    /** Swaps in a tab's preferences and marks it as the one you are on. */
    private void show(int index) {
        selected = index;

        swap(TABS[index].screen, null, false);
        paintTabs();
    }

    /**
     * The fragment showing one screen, or one nested screen of it.
     *
     * A nested one goes on the back stack so Back leaves it; a tab does not,
     * since Back from a tab should leave the settings.
     */
    private void swap(int resource, String rootKey, boolean nested) {
        SettingsFragment fragment = new SettingsFragment();
        Bundle arguments = new Bundle();

        arguments.putInt(SettingsFragment.ARG_SCREEN, resource);
        if (rootKey != null) {
            arguments.putString(
                    androidx.preference.PreferenceFragmentCompat.ARG_PREFERENCE_ROOT,
                    rootKey);
        }
        fragment.setArguments(arguments);

        androidx.fragment.app.FragmentTransaction change =
                getSupportFragmentManager().beginTransaction()
                        .replace(CONTENT_ID, fragment);

        if (nested) change.addToBackStack(null);
        change.commit();
    }

    private void paintTabs() {
        int active = themeColour(android.R.attr.colorAccent);
        int idle = themeColour(android.R.attr.textColorSecondary);

        for (int i = 0; i < tabViews.size(); i++) {
            LinearLayout tab = (LinearLayout) tabViews.get(i);
            boolean on = i == selected;
            int colour = on ? active : idle;

            ((ImageView) tab.getChildAt(0)).setColorFilter(colour);
            ((TextView) tab.getChildAt(1)).setTextColor(colour);
            tab.getChildAt(2).setBackgroundColor(on ? active : 0x00000000);
        }
    }

    /** Whatever this theme says, rather than whatever looks right on mine. */
    private int themeColour(int attribute) {
        android.content.res.TypedArray values =
                getTheme().obtainStyledAttributes(new int[] { attribute });
        int colour = values.getColor(0, 0xff888888);

        values.recycle();

        return colour;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat
            implements android.content.SharedPreferences.OnSharedPreferenceChangeListener {

        /** Which tab's preferences this instance is showing. */
        static final String ARG_SCREEN = "screen";

        /**
         * One class over every screen rather than one class each: everything
         * below asks findPreference() whether a setting is on this screen
         * before touching it, because it had to cope with an absent one anyway.
         *
         * {@code rootKey} is how a nested PreferenceScreen is entered - see
         * {@link SettingsActivity#onPreferenceStartScreen}. Null is the whole
         * file.
         */
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            getPreferenceManager().setSharedPreferencesName(PREFS);
            setPreferencesFromResource(getArguments().getInt(ARG_SCREEN), rootKey);

            populateMachines();
            populateScales();
            populateControls();
            snapToEntries();
            updateSummaries();

            /*
             * The update switch is only a setting where updating is possible: a
             * Play install updates itself, and the Play build has no updater in
             * it at all. Hidden rather than disabled - a switch that cannot be
             * moved is a question with no answer - and the whole category goes,
             * because a heading over nothing is worse than either.
             */
            Preference updates = findPreference("updates");
            if (updates != null) updates.setVisible(Updater.available(getActivity()));

            // Nothing to add Zedex to unless ES-DE is installed, and the whole
            // category goes with the row rather than leaving a heading over
            // nothing - the same reasoning as the updates one above.
            // Wherever there is an ES-DE. It used to want All files access and
            // so hid itself in the Play build; it asks to be shown the folder
            // instead, which every build may do.
            Preference frontends = findPreference("frontends");
            if (frontends != null) {
                frontends.setVisible(EsDe.installed(getActivity()) != null);
            }

            Preference esde = findPreference("esde");
            if (esde != null) {
                esde.setOnPreferenceClickListener(preference -> {
                    addToEsDe();
                    return true;
                });
            }

            Preference folder = findPreference(Storage.KEY_STATES_ROOT);
            if (folder != null) {
                folder.setOnPreferenceClickListener(preference -> {
                    chooseDataFolder();
                    return true;
                });
            }

            Preference content = findPreference(Storage.KEY_CONTENT_TREE);
            if (content != null) {
                content.setOnPreferenceClickListener(preference -> {
                    pickContentFolder();
                    return true;
                });
            }

            Preference firmware = findPreference(KEY_DIVMMC_FIRMWARE);
            if (firmware != null) {
                firmware.setOnPreferenceClickListener(preference -> {
                    pickFirmware();
                    return true;
                });
            }

            // The Library tab. "esde" above is the App tab's row that adds
            // Zedex to ES-DE's own list of emulators; this one instead reads
            // ES-DE's list of games back - two different rows, both named
            // for the same frontend.
            Preference esdeLink = findPreference("esdeLink");
            if (esdeLink != null) {
                esdeLink.setOnPreferenceClickListener(preference -> {
                    runEsdeLink();
                    return true;
                });
            }

            Preference esdeUnlink = findPreference("esdeUnlink");
            if (esdeUnlink != null) {
                esdeUnlink.setOnPreferenceClickListener(preference -> {
                    confirmUnlink();
                    return true;
                });
            }

            Preference esdeFolder = findPreference("esdeFolder");
            if (esdeFolder != null) {
                esdeFolder.setOnPreferenceClickListener(preference -> {
                    pickEsdeFolderForLink();
                    return true;
                });
            }

            Preference esdeMediaTree = findPreference(EsdeLink.KEY_MEDIA_TREE);
            if (esdeMediaTree != null) {
                esdeMediaTree.setOnPreferenceClickListener(preference -> {
                    pickEsdeMediaFolder();
                    return true;
                });
            }
        }

        /**
         * The DivMMC's firmware, which is esxDOS and is not ours to ship: the
         * picked file is copied into the ROM folder under a name of our own, so
         * that from then on the interface has it whatever happens to the
         * document that was picked.
         */
        private void pickFirmware() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");

            try {
                startActivityForResult(intent, REQUEST_FIRMWARE);
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(getActivity(), R.string.open_failed,
                        Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Copies the firmware in and hands it to the emulator.
         *
         * The length is checked here rather than only in the native side,
         * because the file that gets copied is the one the app will use from
         * now on and there is no point keeping something that is not firmware.
         * 8K is not a guess: the DivMMC's EPROM is exactly that, and every
         * firmware built for it fills it.
         */
        private void useFirmware(Uri picked) {
            File target = Storage.divmmcFirmware(getActivity());

            if (!Storage.romsDirectory(getActivity()).isDirectory()) {
                Storage.createFolders(getActivity());
            }

            try (java.io.InputStream in = getActivity().getContentResolver()
                                                       .openInputStream(picked)) {
                if (in == null) throw new java.io.IOException("cannot read " + picked);

                byte[] image = new byte[FIRMWARE_LENGTH + 1];
                int read = 0, step;

                while (read < image.length
                       && (step = in.read(image, read, image.length - read)) != -1) {
                    read += step;
                }

                if (read != FIRMWARE_LENGTH) {
                    Toast.makeText(getActivity(), R.string.settings_firmware_wrong,
                            Toast.LENGTH_LONG).show();
                    return;
                }

                try (java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                    out.write(image, 0, FIRMWARE_LENGTH);
                }
            } catch (java.io.IOException | SecurityException e) {
                android.util.Log.w("Zedex", "cannot import firmware", e);
                Toast.makeText(getActivity(), R.string.open_failed,
                        Toast.LENGTH_LONG).show();
                return;
            }

            FuseNative.loadDivmmcFirmware(target.getAbsolutePath());

            // The switch may have been turned on before there was firmware to
            // honour it, in which case the interface was left out.
            if (getPreferenceManager().getSharedPreferences()
                                      .getBoolean(KEY_DIVMMC, false)) {
                FuseNative.setDivmmc(true);
            }

            updateSummaries();
        }

        /**
         * The folders this device offers without a permission, plus anywhere
         * at all if the user is willing to grant one.
         */
        private void chooseDataFolder() {
            List<File> roots = Storage.roots(getActivity());

            // Only a build that declares All files access can go looking
            // outside them; see Storage.canAskForAnyFolder.
            boolean anywhere = Storage.canAskForAnyFolder(getActivity());
            if (anywhere) roots.add(Storage.sharedRoot(getActivity()));

            String[] items = new String[roots.size() + (anywhere ? 1 : 0)];

            for (int i = 0; i < roots.size(); i++) {
                items[i] = Storage.label(getActivity(), roots.get(i))
                        + "\n" + roots.get(i).getAbsolutePath();
            }
            if (anywhere) {
                items[roots.size()] = getString(R.string.settings_choose_folder);
            }

            new AlertDialog.Builder(getActivity(),
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.settings_data_folder)
                    .setItems(items, (dialog, which) -> {
                        if (which < roots.size()) {
                            useFolder(roots.get(which));
                        } else {
                            chooseAnyFolder();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        /**
         * A folder outside the app's own directories is only reachable by
         * path with All files access - a document tree grant hands back a
         * content:// URI, which Fuse's stdio cannot open.
         */
        /**
         * A folder chosen before the app was allowed to use it.
         *
         * The page that grants All files access is Android's own and returns no
         * result, so the choice is kept here and made on the way back - see
         * {@link #onResume}. Without that, granting it left the folder unchanged
         * and nothing said, which is worse than the refusal it replaced.
         */
        private File pendingFolder;

        /**
         * Writes Zedex into ES-DE's two configuration files.
         *
         * Its folder is at the root of shared storage, which scoped storage puts
         * out of reach, so this needs All files access - and asks for it the same
         * way the data folder does, remembering that it was ES-DE that was
         * wanted and carrying on in {@link #onResume}.
         */
        private void addToEsDe() {
            // The quick way, for a build that already holds All files access:
            // ES-DE's folder is where its own documentation says it is and
            // ordinary file writes reach it.
            if (Storage.canUseAnyFolder() && EsDe.folder() != null) {
                report(EsDe.install(getActivity()));
                return;
            }

            // Otherwise a folder the user granted once. Trying it first means
            // the picker only ever appears again if the grant was lost.
            String kept = getPreferenceManager().getSharedPreferences()
                    .getString(EsDe.KEY_ESDE_TREE, null);

            if (kept != null && EsDe.install(getActivity(), Uri.parse(kept))) {
                report(true);
                return;
            }

            new AlertDialog.Builder(getActivity(),
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(R.string.esde_pick)
                    .setPositiveButton(R.string.settings_choose_folder,
                                       (dialog, which) -> pickEsDeFolder())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        /** Opens the picker at ES-DE's folder if Android will take the hint. */
        private void pickEsDeFolder() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            Uri hint = Storage.documentUriFor(new File(
                    android.os.Environment.getExternalStorageDirectory(), "ES-DE"));
            if (hint != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, hint);
            }

            try {
                startActivityForResult(intent, REQUEST_ESDE_TREE);
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(getActivity(), R.string.open_failed,
                        Toast.LENGTH_LONG).show();
            }
        }

        private void report(boolean written) {
            Toast.makeText(getActivity(),
                    written ? R.string.esde_done : R.string.esde_failed,
                    Toast.LENGTH_LONG).show();
        }

        /** The one dialog that offers the permission, for both ways in. */
        private void askForAllFiles(int why) {
            if (!Storage.canAskForAnyFolder(getActivity())) return;

            new AlertDialog.Builder(getActivity(),
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(why)
                    .setPositiveButton(R.string.settings_grant, (dialog, which) ->
                            startActivity(new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getActivity().getPackageName()))))
                    .setNegativeButton(android.R.string.cancel,
                            (dialog, which) -> pendingFolder = null)
                    .show();
        }

        private void chooseAnyFolder() {
            // Unreachable in a build with no permission to grant - the item that
            // leads here is not in the list - but the dialog it would put up
            // offers a settings page that would open empty.
            if (!Storage.canAskForAnyFolder(getActivity())) return;

            if (!Storage.canUseAnyFolder()) {
                askForAllFiles(R.string.settings_all_files);
                return;
            }

            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                    REQUEST_DATA_TREE);
        }

        private void useFolder(File folder) {
            /*
             * A folder outside the ones Android gives an app for free needs All
             * files access, and this offered one - the folder at the root of
             * storage - and then refused it with "cannot write to that folder"
             * without ever mentioning a permission. Which is what a user hit, on
             * the folder holding every save state they had: the first run had
             * always asked, and this screen never did.
             *
             * Asked before isWritable, because without the permission that test
             * fails for a reason no wording about a bad folder would explain.
             */
            if (!Storage.canUseAnyFolder()
                    && Storage.needsAllFilesFor(getActivity(), folder)) {
                pendingFolder = folder;
                askForAllFiles(R.string.settings_all_files_folder);
                return;
            }

            if (!Storage.isWritable(folder)) {
                Toast.makeText(getActivity(), R.string.settings_folder_unusable,
                        Toast.LENGTH_LONG).show();
                return;
            }

            File previous = Storage.root(getActivity());
            if (previous.equals(folder)) return;

            getPreferenceManager().getSharedPreferences().edit()
                    .putString(Storage.KEY_STATES_ROOT, folder.getAbsolutePath())
                    .apply();

            // Not left to the preference listener: choosing through the
            // picker means this activity was paused, and onPause unregisters
            // it, so the change would arrive with nobody listening.
            moveData(previous);
            updateSummaries();
        }

        /**
         * A folder the user says is ES-DE's.
         *
         * Checked before anything is written: a folder that is not ES-DE's would
         * take the two files and never be read, and the row would have said it
         * worked. The grant is kept so this happens once.
         */
        private void useEsDeFolder(Uri tree) {
            if (!EsDe.looksLikeEsDe(getActivity(), tree)) {
                Toast.makeText(getActivity(), R.string.esde_not_esde,
                        Toast.LENGTH_LONG).show();
                return;
            }

            getActivity().getContentResolver().takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION
                          | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            getPreferenceManager().getSharedPreferences().edit()
                    .putString(EsDe.KEY_ESDE_TREE, tree.toString()).apply();

            report(EsDe.install(getActivity(), tree));
        }

        /**
         * Whether {@link #runEsdeLink} is still running, so
         * {@link #updateSummaries} leaves the row's "Linking…" state alone
         * instead of undoing it on every unrelated preference change - see
         * {@link #onSharedPreferenceChanged}, which calls it after every one.
         */
        private boolean esdeLinking;

        /**
         * Reads ES-DE's list of games and copies it into our own metadata
         * store - see docs/LIBRARY.md, "The second pull request: linking to
         * ES-DE". {@link EsdeLink#read} both reads a file and walks a folder,
         * so it runs off the UI thread, the same way {@code LibraryActivity}
         * loads a folder; the result comes back through
         * {@link Activity#runOnUiThread}.
         */
        private void runEsdeLink() {
            if (esdeLinking) return;

            Activity activity = getActivity();
            if (activity == null) return;

            esdeLinking = true;
            Preference link = findPreference("esdeLink");
            if (link != null) {
                link.setEnabled(false);
                link.setSummary(R.string.settings_esde_linking);
            }

            Context context = activity.getApplicationContext();

            new Thread(() -> {
                // Checked first, off its own back: EsdeLink.read fails soft
                // (or, as it comes to throw for this reason too, fails loud
                // in a way this would otherwise have to tell apart from a
                // parse error) and either way this is the one question worth
                // answering before attempting a read at all - see EsDe.reach,
                // "is it there, and can we read it", asked in one place.
                boolean reachable = EsDe.reach(context) != null;

                List<Meta> found = null;
                IOException failure = null;
                int artwork = 0;

                if (reachable) {
                    try {
                        found = EsdeLink.read(context);

                        // Nothing is written unless there is something to
                        // write: a folder that answered nothing - a lapsed
                        // grant, ES-DE not actually scraped for this system -
                        // is a reason to say so, never a reason to replace
                        // whatever a real link found before it. See the
                        // status line, which is exactly why this matters:
                        // stamping a time here would have told the truth
                        // about the attempt and a lie about the collection.
                        if (!found.isEmpty()) {
                            Metadata.replaceAll(context, found);

                            // Off the UI thread along with the read itself: a
                            // picture is resolved by a SAF query per game,
                            // same as EsdeLink.read walks ES-DE's gamelist.
                            for (Meta game : found) {
                                if (Artwork.picture(context, game.path) != null) artwork++;
                            }
                        }
                    } catch (IOException e) {
                        failure = e;
                    }
                }

                boolean finalReachable = reachable;
                List<Meta> finalFound = found;
                IOException finalFailure = failure;
                int finalArtwork = artwork;
                activity.runOnUiThread(() -> finishEsdeLink(
                        finalReachable, finalFound, finalArtwork, finalFailure));
            }, "zedex-esde-link").start();
        }

        /**
         * Back on the UI thread: says what the link found, then settles down.
         *
         * Four outcomes, told apart rather than folded together, because they
         * are different problems with different fixes: ES-DE could not be
         * reached at all (the folder row above appears for exactly this), it
         * was reached but has scraped nothing for this system, its gamelist
         * could not be parsed, or games were found - possibly none showing a
         * picture yet, which {@link EsdeLink#needsMediaFolder} says apart from
         * "no pictures were ever scraped".
         */
        private void finishEsdeLink(boolean reachable, List<Meta> found,
                int artwork, IOException failure) {
            esdeLinking = false;
            if (!isAdded()) return;

            if (!reachable) {
                Toast.makeText(getActivity(), R.string.settings_esde_link_unreachable,
                        Toast.LENGTH_LONG).show();
            } else if (failure != null) {
                android.util.Log.w("Zedex", "cannot link ES-DE", failure);
                Toast.makeText(getActivity(), R.string.settings_esde_link_failed,
                        Toast.LENGTH_LONG).show();
            } else if (found.isEmpty()) {
                Toast.makeText(getActivity(), R.string.settings_esde_link_empty,
                        Toast.LENGTH_LONG).show();
            } else if (artwork == 0 && EsdeLink.needsMediaFolder(getActivity())) {
                Toast.makeText(getActivity(),
                        getString(R.string.settings_esde_link_done_needs_media, found.size()),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getActivity(),
                        getString(R.string.settings_esde_link_done, found.size(), artwork),
                        Toast.LENGTH_LONG).show();
            }

            updateSummaries();
        }

        /** Asks first: nothing of the user's own is lost, but it sounds final. */
        private void confirmUnlink() {
            new AlertDialog.Builder(getActivity(),
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.settings_esde_unlink_confirm_title)
                    .setMessage(R.string.settings_esde_unlink_confirm_message)
                    .setPositiveButton(R.string.settings_esde_unlink, (dialog, which) -> {
                        Metadata.clear(getActivity());
                        updateSummaries();
                        Toast.makeText(getActivity(), R.string.settings_esde_unlink_done,
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        /**
         * ES-DE's own folder, shown only when {@link EsDe#reach} cannot find
         * a way in at all - no All files access and no grant yet, which on
         * the Play build is the only way in there ever is, since that build
         * cannot hold All files access at all. Hinted and validated exactly
         * like the App tab's own "Add to ES-DE" row - see {@link
         * #pickEsDeFolder} and {@link #useEsDeFolder} - but kept apart from
         * it: that row's job is writing ES-DE's configuration files, and this
         * one's is only enabling a read, so it stores the grant and retries
         * the link rather than doing what the other row does.
         */
        private void pickEsdeFolderForLink() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            Uri hint = Storage.documentUriFor(new File(
                    android.os.Environment.getExternalStorageDirectory(), "ES-DE"));
            if (hint != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, hint);
            }

            try {
                startActivityForResult(intent, REQUEST_ESDE_FOLDER);
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(getActivity(), R.string.open_failed,
                        Toast.LENGTH_LONG).show();
            }
        }

        /**
         * The folder just granted for {@link #pickEsdeFolderForLink}.
         *
         * Checked before it is kept, exactly as {@link #useEsDeFolder} checks
         * its own - a folder that is not really ES-DE's is worse than none,
         * since {@link EsdeLink} would then just find nothing and say so
         * rather than explain what actually went wrong. Read only: nothing
         * reached through this ever writes into ES-DE's folder. Retries the
         * link immediately once the grant is kept, since fixing that is the
         * whole reason the row was there.
         */
        private void grantEsdeFolder(Uri tree) {
            if (!EsDe.looksLikeEsDe(getActivity(), tree)) {
                Toast.makeText(getActivity(), R.string.esde_not_esde,
                        Toast.LENGTH_LONG).show();
                return;
            }

            getActivity().getContentResolver().takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);

            getPreferenceManager().getSharedPreferences().edit()
                    .putString(EsDe.KEY_ESDE_TREE, tree.toString()).apply();

            updateSummaries();
            runEsdeLink();
        }

        /**
         * ES-DE's own media folder, only asked for when
         * {@link EsdeLink#needsMediaFolder} says it is not already inside the
         * folder we hold a grant for - see docs/LIBRARY.md, "ES-DE's media
         * folder is not always beside ES-DE". Hinted at ES-DE's own folder
         * like {@link #pickEsDeFolder}, since the media folder is usually
         * near it even when it is not inside our content folder.
         */
        private void pickEsdeMediaFolder() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            Uri hint = Storage.documentUriFor(new File(
                    android.os.Environment.getExternalStorageDirectory(), "ES-DE"));
            if (hint != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, hint);
            }

            try {
                startActivityForResult(intent, REQUEST_ESDE_MEDIA_TREE);
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(getActivity(), R.string.open_failed,
                        Toast.LENGTH_LONG).show();
            }
        }

        private void pickContentFolder() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            try {
                startActivityForResult(intent, REQUEST_CONTENT_TREE);
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(getActivity(), R.string.open_failed,
                        Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onActivityResult(int request, int result, Intent data) {
            super.onActivityResult(request, result, data);

            if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
                return;
            }

            Uri tree = data.getData();

            if (request == REQUEST_FIRMWARE) {
                useFirmware(tree);
                return;
            }

            if (request == REQUEST_DATA_TREE) {
                File folder = Storage.pathFor(tree);

                if (folder == null || !Storage.isWritable(folder)) {
                    Toast.makeText(getActivity(), R.string.settings_folder_unusable,
                            Toast.LENGTH_LONG).show();
                    return;
                }

                useFolder(folder);
                return;
            }

            if (request == REQUEST_ESDE_TREE) {
                useEsDeFolder(tree);
                return;
            }

            if (request == REQUEST_ESDE_FOLDER) {
                grantEsdeFolder(tree);
                return;
            }

            if (request == REQUEST_ESDE_MEDIA_TREE) {
                // Read only: this folder is just resolved for pictures to
                // draw, never written to.
                getActivity().getContentResolver().takePersistableUriPermission(
                        tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);

                getPreferenceManager().getSharedPreferences().edit()
                        .putString(EsdeLink.KEY_MEDIA_TREE, tree.toString()).apply();
                updateSummaries();
                return;
            }

            if (request != REQUEST_CONTENT_TREE) return;

            // Without this the grant dies with the activity.
            getActivity().getContentResolver().takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);

            getPreferenceManager().getSharedPreferences().edit()
                    .putString(Storage.KEY_CONTENT_TREE, tree.toString()).apply();
            updateSummaries();
        }

        /** The machine list comes from Fuse itself rather than a fixed array. */
        /**
         * The Controls tab.
         *
         * The joystick interface and the key profile are stored as ints, which a
         * ListPreference cannot hold - it stores strings - so both are plain rows
         * that open a list of their own and write the int themselves. Changing
         * the stored type instead would mean a migration for a screen that did
         * not exist yesterday.
         *
         * Everything here writes the same key the ☰ menu writes, and the menu
         * reads it back, so the two cannot disagree.
         */
        private void populateControls() {
            android.content.SharedPreferences preferences =
                    getPreferenceManager().getSharedPreferences();

            Preference interfaces = findPreference(KEY_JOYSTICK_TYPE);
            if (interfaces != null) {
                interfaces.setOnPreferenceClickListener(row -> {
                    chooseJoystickType();
                    return true;
                });
            }

            Preference profile = findPreference(ControlProfiles.KEY_CURRENT);
            if (profile != null) {
                profile.setOnPreferenceClickListener(row -> {
                    chooseProfile();
                    return true;
                });
            }

            Preference edit = findPreference("editProfiles");
            if (edit != null) {
                edit.setOnPreferenceClickListener(row -> {
                    ProfileActivity.open(getActivity());
                    return true;
                });
            }

            Preference hotkeys = findPreference("controllerHotkeys");
            if (hotkeys != null) {
                hotkeys.setOnPreferenceClickListener(row -> {
                    GamepadActivity.open(getActivity());
                    return true;
                });
            }

            ListPreference skins = (ListPreference) findPreference(KEY_KEYBOARD_SKIN);
            if (skins != null) {
                SpectrumKeyboardView.Skin[] all = SpectrumKeyboardView.Skin.values();
                String[] names = new String[all.length];
                String[] values = new String[all.length];

                for (int i = 0; i < all.length; i++) {
                    names[i] = all[i].title;
                    values[i] = all[i].value;
                }

                skins.setEntries(names);
                skins.setEntryValues(values);
                if (skins.getValue() == null) skins.setValue(all[0].value);
            }
        }

        /** Fuse's own interfaces, and Keyboard after them; see ControlsUi. */
        private void chooseJoystickType() {
            android.content.SharedPreferences preferences =
                    getPreferenceManager().getSharedPreferences();

            String[] fuseTypes = FuseNative.joystickTypeNames();
            if (fuseTypes.length == 0) return;

            String[] names = new String[fuseTypes.length + 1];
            System.arraycopy(fuseTypes, 0, names, 0, fuseTypes.length);
            names[fuseTypes.length] = getString(R.string.joystick_keyboard);

            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.joystick_type_title)
                    .setItems(names, (dialog, which) -> {
                        int chosen = which == fuseTypes.length
                                ? Controls.JOYSTICK_KEYBOARD : which;

                        preferences.edit()
                                .putInt(KEY_JOYSTICK_TYPE, chosen).apply();
                        FuseNative.setJoystickType(
                                chosen == Controls.JOYSTICK_KEYBOARD
                                        ? Controls.JOYSTICK_NONE : chosen);
                        Controls.setPadSendsKeys(
                                chosen == Controls.JOYSTICK_KEYBOARD);
                        updateSummaries();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void chooseProfile() {
            android.content.SharedPreferences preferences =
                    getPreferenceManager().getSharedPreferences();

            List<ControlProfiles.Profile> profiles =
                    ControlProfiles.all(preferences);
            String[] names = new String[profiles.size()];

            for (int i = 0; i < names.length; i++) names[i] = profiles.get(i).name;

            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.profile_title)
                    .setItems(names, (dialog, which) -> {
                        ControlProfiles.store(preferences, profiles, which);
                        Controls.setProfile(
                                ControlProfiles.current(preferences).keys);
                        updateSummaries();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void populateMachines() {
            ListPreference machines = (ListPreference) findPreference(KEY_MACHINE);
            if (machines == null) return;

            String[] names = FuseNative.machineNames();
            String[] ids = FuseNative.machineIds();

            if (names.length == 0 || names.length != ids.length) {
                // Fuse has not started yet; leave whatever is stored alone.
                machines.setEnabled(false);
                return;
            }

            machines.setEntries(names);
            machines.setEntryValues(ids);
        }

        /**
         * The scale lists depend on the display, so they are built here rather
         * than being a string-array: 1x up to as many as fit, and then fitting
         * to the screen last - which is the default, because it is what the app
         * did before there was a choice and what wastes least of the panel.
         */
        private void populateScales() {
            for (boolean landscape : new boolean[] { false, true }) {
                ListPreference list = (ListPreference) findPreference(
                        landscape ? KEY_SCALE_LANDSCAPE : KEY_SCALE_PORTRAIT);
                if (list == null) continue;

                int most = maximumScale(getActivity(), landscape);
                String[] names = new String[most + 1];
                String[] values = new String[most + 1];

                Border border = Border.of(getPreferenceManager()
                        .getSharedPreferences());

                for (int n = 1; n <= most; n++) {
                    names[n - 1] = getString(R.string.settings_scale_integer,
                                             n, n * border.width, n * border.height);
                    values[n - 1] = String.valueOf(n);
                }
                names[most] = getString(R.string.settings_scale_fit);
                values[most] = String.valueOf(FuseNative.SCALE_FIT);

                list.setEntries(names);
                list.setEntryValues(values);
            }
        }

        /**
         * Moves any list whose stored value is not one of its own entries to the
         * nearest entry that is.
         *
         * A ListPreference with a value it cannot find shows no checked row and
         * no summary: the setting looks unset while quietly still applying. That
         * is what a default of 55 did to the dot gap, whose entries go up in
         * tens - the effect was there and the screen said nothing. Snapping is
         * better than clearing, since the value that was applied is the one the
         * user has been looking at.
         *
         * Numeric entries only. A machine id is not nearer or further from
         * another one.
         */
        private void snapToEntries() {
            for (int i = 0; i < getPreferenceScreen().getPreferenceCount(); i++) {
                Preference group = getPreferenceScreen().getPreference(i);
                if (!(group instanceof PreferenceGroup)) {
                    snapOne(group);
                    continue;
                }

                PreferenceGroup category = (PreferenceGroup) group;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    snapOne(category.getPreference(j));
                }
            }
        }

        private void snapOne(Preference preference) {
            if (!(preference instanceof ListPreference)) return;

            ListPreference list = (ListPreference) preference;
            CharSequence[] values = list.getEntryValues();
            String stored = list.getValue();

            if (values == null || stored == null || list.getEntry() != null) return;

            int wanted;
            try {
                wanted = Integer.parseInt(stored);
            } catch (NumberFormatException e) {
                return;
            }

            String nearest = null;
            int distance = Integer.MAX_VALUE;

            for (CharSequence value : values) {
                try {
                    int candidate = Integer.parseInt(value.toString());
                    // <= and not <: the entries ascend, so a value exactly
                    // between two of them lands on the higher one, which is
                    // where a default that has moved up will be.
                    if (Math.abs(candidate - wanted) <= distance) {
                        distance = Math.abs(candidate - wanted);
                        nearest = value.toString();
                    }
                } catch (NumberFormatException e) {
                    return;
                }
            }

            if (nearest != null) list.setValue(nearest);
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);

            // Back from the All files access page. Only a folder the user went
            // there for, and only once it is actually allowed; still refused
            // keeps it, since they may be on their way to allow it.
            if (pendingFolder != null && Storage.canUseAnyFolder()) {
                File folder = pendingFolder;
                pendingFolder = null;
                useFolder(folder);
            }

            // The library row depends on the content folder, which the user
            // may have just come back from choosing - the listener above was
            // not registered to hear it happen while this fragment was paused
            // for the trip to the picker.
            updateSummaries();
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceManager().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(
                android.content.SharedPreferences preferences, String key) {
            // A language is not pushed anywhere: it is what the screens were
            // built with, so the open one is built again. Nothing below would
            // change a word of what is already on the display.
            if (Language.KEY_LANGUAGE.equals(key)) {
                getActivity().recreate();
                return;
            }

            apply(preferences, key);
            updateSummaries();
        }

        private void apply(android.content.SharedPreferences preferences, String key) {
            if (isFilterKey(key)) {
                applyFilter(preferences);
                return;
            }

            switch (key) {
                case KEY_LOADER:
                    FuseNative.setLoaderAcceleration(loaderLevel(preferences));
                    break;
                case KEY_DETECT_LOADER:
                    FuseNative.setDetectLoader(preferences.getBoolean(key, true));
                    break;
                case KEY_TAPE_SOUND:
                    FuseNative.setTapeSound(preferences.getBoolean(key, true));
                    break;
                case KEY_AUTOLOAD:
                    FuseNative.setAutoLoad(preferences.getBoolean(key, true));
                    break;
                case KEY_ISSUE2:
                    FuseNative.setIssue2(preferences.getBoolean(key, false));
                    break;
                case KEY_DIVMMC:
                    FuseNative.setDivmmc(preferences.getBoolean(key, false));
                    break;
                case KEY_BW_TV:
                    FuseNative.setBlackAndWhite(preferences.getBoolean(key, false));
                    break;
                case KEY_SOUND:
                    FuseNative.setSound(preferences.getBoolean(key, true));
                    break;
                case KEY_SPEED:
                    FuseNative.setSpeed(number(preferences, key, 100));
                    break;
                case KEY_SCALE_PORTRAIT:
                case KEY_SCALE_LANDSCAPE:
                    applyScale(getActivity(), preferences);
                    break;
                // A different border is a different number of pixels to scale,
                // so the scale list is rebuilt as well as the renderer told.
                case KEY_BORDER:
                    applyBorder(preferences);
                    applyScale(getActivity(), preferences);
                    populateScales();
                    break;
                case KEY_AY_STEREO:
                    FuseNative.setAyStereo(ayStereo(preferences));
                    break;
                case KEY_TURBOSOUND:
                    FuseNative.setTurboSound(preferences.getBoolean(key, true));
                    break;
                case KEY_TURBO:
                    FuseNative.setTurbo(preferences.getBoolean(key, false));
                    break;
                case KEY_AY_VOLUME:
                    FuseNative.setAyVolume(number(preferences, key, 100));
                    break;
                case KEY_BEEPER_VOLUME:
                    FuseNative.setBeeperVolume(number(preferences, key, 100));
                    break;
                default:
                    // The machine and keep-screen-on are read where they are
                    // needed rather than pushed.
                    break;
            }
        }

        /** Takes the files along, from wherever they were. */
        private void moveData(File previous) {
            Storage.createFolders(getActivity());

            Storage.move(getActivity(), new File(previous, "states"),
                         Storage.statesDirectory(getActivity()));
            Storage.move(getActivity(), new File(previous, "roms"),
                         Storage.romsDirectory(getActivity()));
            Storage.move(getActivity(), new File(previous, "tapes"),
                         Storage.tapesDirectory(getActivity()));
            Storage.move(getActivity(), new File(previous, "disks"),
                         Storage.disksDirectory(getActivity()));
            Storage.move(getActivity(), new File(previous, "screenshots"),
                         Storage.capturesDirectory(getActivity()));
            Storage.move(getActivity(), new File(previous, "recordings"),
                         Storage.capturesDirectory(getActivity()));

            // chdir is process wide and immediate, so the running emulator
            // finds ROMs in the new place too - no restart needed.
            FuseNative.setWorkingDirectory(
                    Storage.romsDirectory(getActivity()).getAbsolutePath());

            Toast.makeText(getActivity(), R.string.settings_states_moved,
                    Toast.LENGTH_SHORT).show();
        }

        /** ListPreference stores numbers as strings. */
        public static int number(android.content.SharedPreferences preferences,
                          String key, int fallback) {
            try {
                return Integer.parseInt(preferences.getString(key, String.valueOf(fallback)));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        /**
         * Greys out the settings that would do nothing.
         *
         * Colour bleed and noise are properties of a signal, so with the
         * output set to RGB there is no signal for them to spoil: the shader
         * gates them on the video output and the screen has to say so, or it
         * offers a number that changes nothing. Greying out rather than a word
         * in the summary, because the summary of a list is its value here and
         * anything else put there is overwritten.
         *
         * The four strengths go the same way. They were android:dependency on
         * the two switches, which is all dependency can express - a boolean
         * that is on; now which effects are running is one word in a list, and
         * whether the beam or the glass is part of it is Filter's answer.
         *
         * Sharpness is not in here on purpose. It is the sampling, and it
         * applies whatever the output is.
         */
        private void updateFilterEnabled() {
            android.content.SharedPreferences preferences =
                    getPreferenceManager().getSharedPreferences();

            int video = number(preferences, KEY_VIDEO, 0);
            Filter filter = Filter.of(preferences);

            enable(KEY_FILTER_BLEED, video != FuseNative.VIDEO_RGB);
            enable(KEY_FILTER_NOISE, video == FuseNative.VIDEO_RF);

            enable(KEY_FILTER_SCANLINE, filter.scanlines);
            enable(KEY_FILTER_CURVE, filter.crt);
            enable(KEY_FILTER_MASK, filter.crt);
            enable(KEY_FILTER_GLOW, filter.crt);
        }

        /** Only where the setting is on this screen; four of the five are not. */
        private void enable(String key, boolean on) {
            Preference preference = findPreference(key);
            if (preference != null) preference.setEnabled(on);
        }

        private void updateSummaries() {
            updateFilterEnabled();

            // The two int-backed rows: their summary is their value, which is
            // what a ListPreference would have done for them.
            android.content.SharedPreferences settings =
                    getPreferenceManager().getSharedPreferences();

            Preference interfaces = findPreference(KEY_JOYSTICK_TYPE);
            if (interfaces != null) {
                int type = settings.getInt(KEY_JOYSTICK_TYPE,
                                           Controls.JOYSTICK_KEMPSTON);
                String[] names = FuseNative.joystickTypeNames();

                interfaces.setSummary(
                        type == Controls.JOYSTICK_KEYBOARD
                                ? getString(R.string.joystick_keyboard)
                                : type < names.length ? names[type] : "");
                interfaces.setEnabled(names.length > 0);
            }

            Preference profile = findPreference(ControlProfiles.KEY_CURRENT);
            if (profile != null) {
                profile.setSummary(ControlProfiles.current(settings).name);
            }

            Preference folder = findPreference(Storage.KEY_STATES_ROOT);
            if (folder != null) {
                folder.setSummary(Storage.root(getActivity()).getAbsolutePath());
            }

            Preference content = findPreference(Storage.KEY_CONTENT_TREE);
            if (content != null) {
                String described = Storage.describe(getPreferenceManager()
                        .getSharedPreferences()
                        .getString(Storage.KEY_CONTENT_TREE, null));
                content.setSummary(described != null ? described
                        : getString(R.string.settings_content_folder_none));
            }

            Preference firmware = findPreference(KEY_DIVMMC_FIRMWARE);
            if (firmware != null) {
                boolean have = Storage.divmmcFirmware(getActivity()).isFile();
                firmware.setSummary(have ? R.string.settings_firmware_loaded
                                         : R.string.settings_firmware_none);
            }

            // A switch for hardware that may not be there. Left visible rather
            // than hidden, so a handheld that has a panel is not a feature
            // nobody knew about - it says why it cannot be turned on.
            Preference second = findPreference(KEY_SECOND_SCREEN);
            if (second != null) {
                DisplayManager displays =
                        getActivity().getSystemService(DisplayManager.class);
                boolean have = displays != null && displays.getDisplays(
                        DisplayManager.DISPLAY_CATEGORY_PRESENTATION).length > 0;

                second.setEnabled(have);
                second.setSummary(have ? R.string.settings_second_screen_summary
                                       : R.string.settings_second_screen_none);
            }

            // The same idea as the second screen above: disabled with an
            // explanation rather than hidden, because a content folder chosen
            // a moment later legitimately turns this on - see
            // docs/LIBRARY.md, "A content folder is the gate".
            Preference library = findPreference(KEY_LIBRARY);
            if (library != null) {
                boolean haveFolder = settings.getString(
                        Storage.KEY_CONTENT_TREE, null) != null;

                library.setEnabled(haveFolder);
                library.setSummary(haveFolder ? R.string.settings_library_summary
                                              : R.string.settings_library_needs_folder);
            }

            // The Library tab's own rows - see docs/LIBRARY.md, "The second
            // pull request: linking to ES-DE".
            Preference esdeLink = findPreference("esdeLink");
            if (esdeLink != null && !esdeLinking) {
                boolean installed = EsDe.installed(getActivity()) != null;

                esdeLink.setEnabled(installed);
                esdeLink.setSummary(installed
                        ? R.string.settings_esde_link_summary
                        : R.string.settings_esde_link_needs_esde);
            }

            // Shown only when ES-DE is there but EsDe.reach cannot find a way
            // into its folder at all - the one thing that turned an empty
            // link into a silent one, and the only row that can fix it from
            // here, since the media-folder row below asks for something
            // different.
            Preference esdeFolder = findPreference("esdeFolder");
            if (esdeFolder != null) {
                esdeFolder.setVisible(EsDe.installed(getActivity()) != null
                        && EsDe.reach(getActivity()) == null);
            }

            Preference esdeStatus = findPreference("esdeStatus");
            if (esdeStatus != null) {
                long when = Metadata.lastLinked(getActivity());

                esdeStatus.setSummary(when == 0
                        ? getString(R.string.settings_esde_status_never)
                        : getString(R.string.settings_esde_status_summary,
                                Metadata.count(getActivity()),
                                DateFormat.getDateTimeInstance(
                                        DateFormat.SHORT, DateFormat.SHORT)
                                        .format(new Date(when))));
            }

            Preference esdeUnlink = findPreference("esdeUnlink");
            if (esdeUnlink != null) {
                esdeUnlink.setEnabled(Metadata.lastLinked(getActivity()) != 0);
            }

            // Shown only when ES-DE's own media directory is not already
            // inside the folder we hold a grant for - a row that said "not
            // needed" would be worse than no row at all.
            Preference esdeMediaTree = findPreference(EsdeLink.KEY_MEDIA_TREE);
            if (esdeMediaTree != null) {
                boolean needed = EsdeLink.needsMediaFolder(getActivity());

                esdeMediaTree.setVisible(needed);
                if (needed) {
                    String stored = settings.getString(EsdeLink.KEY_MEDIA_TREE, null);
                    String described = stored != null ? Storage.describe(stored) : null;

                    esdeMediaTree.setSummary(described != null ? described
                            : getString(R.string.settings_esde_media_folder_none));
                }
            }

            for (String key : new String[] { KEY_MACHINE, KEY_SPEED, KEY_SNAPSHOT_FORMAT,
                                             KEY_FILTER,
                                             KEY_LOADER, KEY_AY_STEREO,
                                             KEY_TAPE_FORMAT,
                                             KEY_BORDER,
                                             KEY_SCALE_PORTRAIT, KEY_SCALE_LANDSCAPE,
                                             KEY_FILTER_SHARPNESS,
                                             KEY_FILTER_SCANLINE, KEY_FILTER_CURVE,
                                             KEY_FILTER_MASK, KEY_FILTER_GLOW,
                                             KEY_VIDEO, KEY_FILTER_BLEED,
                                             KEY_FILTER_NOISE,
                                             KEY_AY_VOLUME, KEY_BEEPER_VOLUME,
                                             KEY_KEYBOARD_SKIN,
                                             KEY_MOUSE_SENSITIVITY }) {
                Preference preference = findPreference(key);
                if (preference instanceof ListPreference) {
                    CharSequence entry = ((ListPreference) preference).getEntry();
                    // ListPreference runs its summary through String.format, so
                    // a per cent sign in the entry has to be escaped.
                    if (entry != null) {
                        preference.setSummary(entry.toString().replace("%", "%%"));
                    }
                }
            }
        }
    }
}
