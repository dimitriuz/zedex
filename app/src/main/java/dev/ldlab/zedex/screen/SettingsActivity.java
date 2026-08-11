package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.machine.Picture;
import dev.ldlab.zedex.work.Work;
import dev.ldlab.zedex.input.ControlProfiles;
import dev.ldlab.zedex.input.Controls;
import dev.ldlab.zedex.view.SpectrumKeyboardView;
import dev.ldlab.zedex.view.SafeArea;
import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.input.Controls;
import dev.ldlab.zedex.machine.FuseSettings;
import dev.ldlab.zedex.machine.Video;
import dev.ldlab.zedex.machine.Border;
import dev.ldlab.zedex.machine.Filter;
import dev.ldlab.zedex.media.Media;
import dev.ldlab.zedex.frontend.EsDe;
import dev.ldlab.zedex.library.scrape.Provider;
import dev.ldlab.zedex.library.scrape.Scrapers;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.EsdeLink;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.storage.Storage;

// The settings schema moved to storage.Prefs; these are used bare
// throughout this file, which is the one place that reads nearly all
// of them.
import static dev.ldlab.zedex.storage.Prefs.*;
import dev.ldlab.zedex.update.Updater;
import dev.ldlab.zedex.view.EmulatorLayout;
import dev.ldlab.zedex.view.SpectrumKeyboardView;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
     * promise - a content folder that is still granted, which is
     * {@link #libraryExists}. The grant is revocable from Android's own
     * settings independently of this app, and a library with the switch on
     * but nothing to list would be worse than the machine it replaced; see
     * docs/LIBRARY.md, "A content folder is the gate".
     *
     * Two different questions, kept apart on purpose: this one is about
     * where the app opens, {@link #libraryExists} about whether the library
     * is there to be reached at all. Folding them together cost
     * {@code EmulatorActivity}'s own ☰ Library row, once - turning the
     * switch off, which only ever promised to change where the app starts,
     * also took the only way back into the library out of the menu, with
     * nothing left short of Settings to undo it.
     */
    public static boolean startsInLibrary(android.content.SharedPreferences preferences) {
        return preferences.getBoolean(KEY_LIBRARY, true)
                && libraryExists(preferences);
    }

    /**
     * Runs the one-time library migration, if it has not run.
     *
     * Called from an entry point rather than from inside startsInLibrary,
     * which is where it used to live. A question that writes is a question
     * nobody expects to have to ask in the right order, and it only ever ran
     * at all because LibraryActivity is the launcher and therefore asks
     * first - true today, undocumented as a dependency, and silently skipped
     * the day that changes. Both activities that can start this app call it
     * now, and it is idempotent, so being asked twice costs one boolean read.
     */
    public static void migrateIfNeeded(Context context,
            android.content.SharedPreferences preferences) {
        migrateLibraryDefault(context, preferences);
    }

    /**
     * Whether there is a library to show at all - a content folder chosen
     * and still granted, regardless of what {@link #KEY_LIBRARY} says. See
     * {@link #startsInLibrary}'s own comment for why this is not folded into
     * that one: this is the question {@code EmulatorActivity}'s ☰ Library
     * row needs answered, since that row is about whether the library
     * exists, not about where the app happens to open.
     */
    public static boolean libraryExists(android.content.SharedPreferences preferences) {
        return preferences.getString(Storage.KEY_CONTENT_TREE, null) != null;
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
     * and *App* holds three unrelated questions - the data folder, formats,
     * updates. The other four are each one subject and need no headings at
     * all.
     *
     * *Library* sits between *Sound* and *App* - after the machine's own
     * settings, before Zedex's housekeeping, which is what linking to a
     * frontend's metadata is nearer to. See docs/LIBRARY.md, "The second
     * pull request: linking to ES-DE". The content folder and the switch
     * that opens on it live here too, first, above the ES-DE group - turning
     * the library on is what somebody comes to this tab to do, and the App
     * tab is no longer where they would look for it; see CLAUDE.md.
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

        // Named now rather than smuggled in through a question. Idempotent,
        // and here as well as at the two entry points because this screen's
        // own switch has to read right for somebody who opens Settings before
        // anything else has asked.
        migrateIfNeeded(this, getSharedPreferences(PREFS, MODE_PRIVATE));

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

        // With no ellipsize, setSingleLine turns on horizontal scrolling
        // instead, so a label too wide for its cell is cut through the middle
        // of a glyph at both ends with nothing to say it was. A seventh of a
        // 360dp window is 51dp - about nine characters - which Bibliothek,
        // Bibliotheque, Biblioteka and Управление all exceed, and at 200% font
        // scale every label does, English included.
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
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

    /** The size of the DivMMC's EPROM, and so of any firmware for it. */
    private static final int FIRMWARE_LENGTH = 8 * 1024;

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
            Activity activity = getActivity();
            if (activity == null || esdeWriting) return;

            String kept = getPreferenceManager().getSharedPreferences()
                    .getString(EsDe.KEY_ESDE_TREE, null);

            esdeWork(activity, "esde-add", context -> {
                // The quick way, for a build that already holds All files
                // access: ES-DE's folder is where its own documentation says
                // it is and ordinary file writes reach it.
                if (Storage.canUseAnyFolder() && EsDe.folder() != null) {
                    return EsDe.install(context) ? Written.YES : Written.NO;
                }

                // Otherwise a folder the user granted once. Trying it first
                // means the picker only ever appears again if the grant was
                // lost.
                if (kept != null && EsDe.install(context, Uri.parse(kept))) {
                    return Written.YES;
                }

                return Written.ASK;
            });
        }

        /**
         * Answers the picker dialog {@link #addToEsDe} used to put up inline.
         *
         * The decision that leads here - is All files access enough, is there
         * a kept grant, did writing through it work - is two SAF queries and
         * two XML round trips, so it happens on the worker and only the dialog
         * comes back.
         */
        private void askForEsDeFolder() {
            Activity activity = getActivity();
            if (activity == null) return;

            new AlertDialog.Builder(activity,
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

        /**
         * How a write into ES-DE's folder turned out.
         *
         * More than a boolean because there is genuinely more than a boolean
         * to say, and each of the three that are not "it worked" or "it did
         * not" ends somewhere different: a folder that is not ES-DE's gets its
         * own wording - see {@code useEsDeFolder} on why writing into one
         * anyway looks exactly like success - "nowhere to write yet" ends in
         * the picker, and the read-only grant ends in a link rather than in
         * any Toast at all.
         */
        private enum Written { YES, NO, NOT_ESDE, ASK, LINK }

        /** What the worker below is handed, so the lambda cannot reach for a
         *  fragment that may be gone by the time it runs. */
        private interface EsdeTask {
            Written run(Context context);
        }

        /**
         * Whether one of the ES-DE writes is still going, so a second tap
         * cannot start another. Two of these at once would interleave two
         * serialisations of the same two XML files, which was possible before
         * only because they were fast enough on the main thread that nobody
         * managed it.
         */
        private boolean esdeWriting;

        /**
         * Runs an ES-DE write off the UI thread and reports it back on.
         *
         * Every one of these is SAF queries plus two XML parses and two
         * serialisations against another app's folder - see {@code EsDe} -
         * which is not work to do while the screen is waiting on it. The same
         * shape as {@link #runEsdeLink}, which has always done this correctly
         * for the read side.
         */
        private void esdeWork(Activity activity, String name, EsdeTask task) {
            esdeWriting = true;
            Context context = activity.getApplicationContext();

            Work.alone(name, () -> {
                Written written = task.run(context);
                activity.runOnUiThread(() -> finishEsdeWork(written));
            });
        }

        private void finishEsdeWork(Written written) {
            esdeWriting = false;

            if (getActivity() == null) return;

            if (written == Written.ASK) {
                askForEsDeFolder();
                return;
            }

            if (written == Written.NOT_ESDE) {
                Toast.makeText(getActivity(), R.string.esde_not_esde,
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (written == Written.LINK) {
                // The grant was the whole point of the row, so the link it
                // was blocking runs at once - see grantEsdeFolder.
                updateSummaries();
                runEsdeLink();
                return;
            }

            updateSummaries();
            report(written == Written.YES);
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
            //
            // Before moveData rather than after, now that moveData answers
            // later than it returns: it puts the folder row into "Moving…",
            // and this call would otherwise wipe that a line later. Its own
            // finish calls this again once the files are actually there.
            updateSummaries();
            moveData(previous);
        }

        /**
         * A folder the user says is ES-DE's.
         *
         * Checked before anything is written: a folder that is not ES-DE's would
         * take the two files and never be read, and the row would have said it
         * worked. The grant is kept so this happens once.
         */
        private void useEsDeFolder(Uri tree) {
            Activity activity = getActivity();
            if (activity == null || esdeWriting) return;

            SharedPreferences preferences =
                    getPreferenceManager().getSharedPreferences();

            // Check, grant and write in one task rather than hopping back for
            // the grant in the middle: takePersistableUriPermission is a
            // resolver call and wants no particular thread, and keeping the
            // three together is what makes their order obvious - the grant has
            // to be held before the write goes through the tree.
            esdeWork(activity, "esde-folder", context -> {
                if (!EsDe.looksLikeEsDe(context, tree)) return Written.NOT_ESDE;

                Storage.keepAccessTo(context, tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                preferences.edit().putString(EsDe.KEY_ESDE_TREE, tree.toString()).apply();

                return EsDe.install(context, tree) ? Written.YES : Written.NO;
            });
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

            Work.alone("esde-link", () -> {
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
                            Metadata.replaceScraped(context, found);

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
            });
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
                        counted(R.plurals.settings_esde_link_done_needs_media,
                                found.size(), found.size()),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getActivity(),
                        counted(R.plurals.settings_esde_link_done,
                                found.size(), found.size(), artwork),
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
            Activity activity = getActivity();
            if (activity == null || esdeWriting) return;

            SharedPreferences preferences =
                    getPreferenceManager().getSharedPreferences();

            // looksLikeEsDe is the SAF walk here too, so it goes to the worker
            // like the other two. Nothing is written into ES-DE's folder on
            // this path - the grant is read only - so the answer is only ever
            // "not ES-DE" or "carry on", and carrying on means the link, which
            // has always run on a thread of its own.
            esdeWork(activity, "esde-grant", context -> {
                if (!EsDe.looksLikeEsDe(context, tree)) return Written.NOT_ESDE;

                Storage.keepAccessTo(context, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                preferences.edit().putString(EsDe.KEY_ESDE_TREE, tree.toString()).apply();

                return Written.LINK;
            });
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
                Storage.keepAccessTo(getActivity(), tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);

                getPreferenceManager().getSharedPreferences().edit()
                        .putString(EsdeLink.KEY_MEDIA_TREE, tree.toString()).apply();
                updateSummaries();
                return;
            }

            if (request != REQUEST_CONTENT_TREE) return;

            // Without this the grant dies with the activity. Write too, now
            // that importing a game means writing into this same folder -
            // see Tree.canWrite; Task 11's import flow re-asks when an
            // existing grant is read-only, since a grant already taken
            // cannot be upgraded in place.
            Storage.keepAccessTo(getActivity(), tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

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
                    names[i] = getString(all[i].title);
                    values[i] = all[i].value;
                }

                skins.setEntries(names);
                skins.setEntryValues(values);
                if (skins.getValue() == null) skins.setValue(all[0].value);
            }

            // The same, for the video signal: the list used to be two arrays
            // in arrays.xml that had to stay in step with FuseNative's own
            // three constants and with a nextVideo that counted them by
            // taking the modulus of an array's length. Video owns all of that
            // now, so this is where the enum meets the screen.
            ListPreference video = (ListPreference) findPreference(KEY_VIDEO);
            if (video != null) {
                Video[] signals = Video.values();
                String[] names = new String[signals.length];
                String[] values = new String[signals.length];

                for (int i = 0; i < signals.length; i++) {
                    names[i] = getString(signals[i].title);
                    values[i] = String.valueOf(signals[i].value);
                }

                video.setEntries(names);
                video.setEntryValues(values);
                if (video.getValue() == null) {
                    video.setValue(String.valueOf(Video.RGB.value));
                }
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
        /**
         * Pushes every setting on this screen into Fuse, one at a time.
         *
         * For the null key, which since API 30 is how Editor.clear() announces
         * itself: everything changed at once, and the switch in apply() takes
         * one key. The keys come from the preference hierarchy rather than a
         * list written out here, because a second list is a second thing to
         * forget - this one is the same set the screen was built from, so a
         * row added to the XML is covered without anybody remembering to.
         *
         * Only this screen's own. A clear() while another page is open leaves
         * that page's settings to whichever fragment is resumed next, which is
         * the same fragment that would have applied them anyway.
         */
        private void applyAll(android.content.SharedPreferences preferences) {
            PreferenceGroup screen = getPreferenceScreen();
            if (screen == null) return;

            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference group = screen.getPreference(i);

                if (!(group instanceof PreferenceGroup)) {
                    applyOne(preferences, group);
                    continue;
                }

                PreferenceGroup category = (PreferenceGroup) group;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    applyOne(preferences, category.getPreference(j));
                }
            }
        }

        private void applyOne(android.content.SharedPreferences preferences,
                              Preference preference) {
            String key = preference.getKey();
            if (key != null) apply(preferences, key);
        }

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

            // Every entry parsed before any of them is compared, because one
            // that does not parse means this is not a numeric list at all and
            // must not be snapped - "numeric entries only" in the doc above.
            //
            // The machine list is why, and it is not hypothetical: its values
            // are Fuse's own ids, and they are mixed - 16, 48, 128, 2048 and
            // 2068 parse, while 48_ntsc, plus2, plus3e, pentagon, scorpion, se
            // and ts2068 do not. Skipping the ones that fail and snapping among
            // the rest would answer a stored 2048 with 2068, silently changing
            // which machine the user has, on the grounds that the numbers are
            // close. They are ids. A review of this file read the abandon below
            // as a bug and proposed exactly that; it is the opposite.
            int[] candidates = new int[values.length];

            for (int i = 0; i < values.length; i++) {
                try {
                    candidates[i] = Integer.parseInt(values[i].toString());
                } catch (NumberFormatException e) {
                    return;
                }
            }

            String nearest = null;
            int distance = Integer.MAX_VALUE;

            for (int i = 0; i < values.length; i++) {
                // <= and not <: the entries ascend, so a value exactly
                // between two of them lands on the higher one, which is
                // where a default that has moved up will be.
                if (Math.abs(candidates[i] - wanted) <= distance) {
                    distance = Math.abs(candidates[i] - wanted);
                    nearest = values[i].toString();
                }
            }

            if (nearest != null) list.setValue(nearest);
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);

            // The ES-DE rows read how many games are known and when they were
            // linked, and Metadata answers from memory without ever parsing -
            // so a Settings screen opened without the library having run
            // would read an empty store and say "never linked" about a
            // library that is linked. Read it off-thread and say so again
            // when it lands.
            Context app = getActivity().getApplicationContext();
            Work.run("metadata-settings", () -> {
                Metadata.ensureLoaded(app);

                Activity activity = getActivity();
                if (activity == null) return;

                activity.runOnUiThread(() -> {
                    if (isAdded()) updateSummaries();
                });
            });

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
            // Since API 30 a null key means Editor.clear() - everything at
            // once, not one setting. Nothing calls clear() today, so this has
            // never fired; it is the switch below that would throw, and a
            // "reset all settings" row is exactly the sort of thing that gets
            // added without anyone thinking about this method. Re-reading the
            // lot is the honest response to being told the lot changed.
            if (key == null) {
                applyAll(preferences);
                updateSummaries();
                return;
            }

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
            // One table, shared with the command line Machine builds at a
            // cold start - see FuseSettings. This used to be a switch of its
            // own, and the two had already drifted: divmmc had a case here
            // and no argument there, so switching it on and then restarting
            // came up without it.
            if (FuseSettings.push(preferences, key)) return;

            switch (key) {
                // What is left is what is not Fuse's: the picture's scale and
                // border are as much this app's layout as the emulator's, so
                // they are applied through the screen that owns it.
                case KEY_SCALE_PORTRAIT:
                case KEY_SCALE_LANDSCAPE:
                    Picture.applyScale(getActivity(), preferences);
                    break;
                case KEY_BORDER:
                    Picture.applyBorder(preferences);
                    Picture.applyScale(getActivity(), preferences);
                    break;
                default:
                    // The machine and keep-screen-on are read where they are
                    // needed rather than pushed.
                    break;
            }
        }

        /**
         * Whether {@link #moveData} is still running, so {@link
         * #updateSummaries} leaves the folder row's "Moving…" state alone
         * instead of resetting it to the path on every unrelated preference
         * change - the same reasoning, and the same shape, as {@link
         * #esdeLinking} beside it.
         */
        private boolean movingData;

        /**
         * Takes the files along, from wherever they were - on a worker, since
         * how long it takes is not bounded by anything this screen knows.
         *
         * {@code renameTo} is instant within a volume, so the ordinary case
         * always was fast and this looked harmless for a long time. Across a
         * volume it is a byte copy of every file, captures included, and
         * putting the data folder on an SD card is the main reason anyone
         * changes it - so the one case the setting exists for is the one that
         * froze the screen. See {@link Storage#move}.
         *
         * The preference has already been written by {@link #useFolder} at
         * this point, deliberately, which leaves a window where the app names
         * a folder the files have not reached yet. Disabling the row is what
         * keeps that from getting worse: a second folder change starting on
         * top of this one would move a subset twice, from two different
         * places. The window itself is the price of not inverting an ordering
         * that exists for its own reason - see {@code useFolder}'s comment.
         */
        private void moveData(File previous) {
            Activity activity = getActivity();
            if (activity == null) return;

            movingData = true;

            Preference row = findPreference(Storage.KEY_STATES_ROOT);
            if (row != null) {
                row.setEnabled(false);
                row.setSummary(R.string.settings_states_moving);
            }

            // Both captured here rather than reached for on the worker: this
            // fragment can be gone by the time the copy finishes, and
            // getActivity() and getPreferenceManager() both answer null then.
            // SharedPreferences is thread safe, and the application context
            // serves everything Storage asks a Context for.
            Context context = activity.getApplicationContext();
            SharedPreferences preferences =
                    getPreferenceManager().getSharedPreferences();

            Work.alone("data-move", () -> {
                boolean whole = moveEverything(context, preferences, previous);
                activity.runOnUiThread(() -> finishDataMove(context, whole));
            });
        }

        /**
         * The move itself, and nothing that touches a view - every line of
         * this runs on {@code Work.alone}'s own thread.
         *
         * @return whether all of it arrived. {@code &=} rather than {@code &&}
         *         throughout, since every folder must be attempted whatever an
         *         earlier one answered - short-circuiting here would stop the
         *         move at the first failure and leave the rest behind.
         */
        private static boolean moveEverything(Context context, SharedPreferences preferences,
                                              File previous) {
            Storage.createFolders(context);

            // One list, from Storage, rather than a fourth copy of it here.
            // This one used to be written out by hand and was missing two of
            // them: cards, which orphans a DivMMC image and every game save on
            // it, and library, which is the scraped metadata store - and losing
            // that reads as "never linked" rather than as an error, because a
            // missing store is an empty store. See Storage.dataFolders.
            String[] folders = Storage.dataFolderNames();
            File[] destinations = Storage.dataFolders(context);

            boolean whole = true;

            for (int i = 0; i < folders.length; i++) {
                whole &= Storage.move(context, new File(previous, folders[i]),
                                      destinations[i]);
            }

            // Captures are not in that list: they go to Pictures/Zedex rather
            // than into the data folder, so both of the old names move there.
            whole &= Storage.move(context, new File(previous, "screenshots"),
                                  Storage.capturesDirectory(context));
            whole &= Storage.move(context, new File(previous, "recordings"),
                                  Storage.capturesDirectory(context));

            // The card is remembered by absolute path, so moving the file
            // out from under that setting loses it: Machine.applyDivmmc checks
            // isFile() and quietly inserts nothing, which reads as every save
            // on the card having disappeared. The image itself is safe in the
            // new folder - it is the pointer to it that has to move too.
            repointCard(context, preferences, previous);

            return whole;
        }

        /**
         * Back on the main thread once the files are there.
         *
         * The chdir happens whether or not this screen is still up, and before
         * the check that it is: somebody who changed the folder and walked
         * straight back to the machine still has an emulator that has to find
         * its ROMs. Everything below that line is only worth doing to a screen
         * somebody can see.
         */
        private void finishDataMove(Context context, boolean whole) {
            movingData = false;

            // chdir is process wide and immediate, so the running emulator
            // finds ROMs in the new place too - no restart needed. Still on
            // the main thread, which is where it has always been, and now
            // also not until the files have actually arrived.
            FuseNative.setWorkingDirectory(
                    Storage.romsDirectory(context).getAbsolutePath());

            Activity activity = getActivity();
            if (activity == null) return;

            Preference row = findPreference(Storage.KEY_STATES_ROOT);
            if (row != null) row.setEnabled(true);

            updateSummaries();

            // Which of the two actually happened. This said "moved" either
            // way, including for a move that gave up on half the files and
            // logged it where nobody would look - and a move nobody watched
            // happen is exactly the one that needs telling.
            Toast.makeText(activity,
                    whole ? R.string.settings_states_moved
                          : R.string.settings_states_move_partial,
                    Toast.LENGTH_LONG).show();
        }

        /**
         * Follows the inserted card to wherever its folder has just gone.
         *
         * Only when the stored path was inside the folder that moved: a card
         * the user opened from somewhere else entirely is still where it was,
         * and rewriting that would point the setting at a file that does not
         * exist. Checked against the file rather than assumed, so a move that
         * did not carry this one leaves the old path alone to be reported
         * rather than silently replaced with another wrong one.
         */
        private static void repointCard(Context context, SharedPreferences preferences,
                                        File previous) {
            String card = preferences.getString(Media.PREF_CARD, null);
            if (card == null) return;

            File was = new File(card);
            File from = new File(previous, Storage.CARDS);
            if (!was.getParentFile().equals(from)) return;

            File now = new File(Storage.cardsDirectory(context), was.getName());
            if (!now.isFile()) return;

            preferences.edit().putString(Media.PREF_CARD,
                                         now.getAbsolutePath()).apply();
        }

        /**
         * A counted string, in whichever form the language wants for that
         * number. The count goes in twice: once to choose the form, once as
         * the argument that fills the %d - separate ideas, since the form
         * follows the last digit in Russian while the number printed is the
         * whole count.
         */
        private String counted(int plural, int count, Object... arguments) {
            return getResources().getQuantityString(plural, count, arguments);
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

            /*
             * The ScreenScraper login, which is one row that shows its value
             * and one that must never show its own.
             *
             * A password under its row on a settings page is readable by
             * anyone standing behind you and by any screenshot in a bug
             * report, so this says whether there is one and nothing else -
             * which is also the only thing anybody needs to know from here.
             * The name is not a secret and is worth showing: it is how you
             * tell "signed in as somebody" from "signed in as nobody".
             */
            Preference scraperUser = findPreference(Prefs.KEY_SCRAPER_USER);
            if (scraperUser != null) {
                String user = settings.getString(Prefs.KEY_SCRAPER_USER, "");
                scraperUser.setSummary(user == null || user.trim().isEmpty()
                        ? getString(R.string.settings_scraper_user_summary)
                        : user.trim());
            }

            /*
             * What a scrape fetches, and what that costs.
             *
             * The arithmetic belongs here rather than in anybody's head:
             * every medium is a request against the day's allowance, so
             * ticking two more boxes is the difference between a collection
             * fitting in a day and not. Naming the count as well as the cost
             * because "6 requests a game" alone does not say how many were
             * chosen out of how many there are.
             */
            /*
             * Which service answers, from the providers this build actually
             * has - ZXInfo always, ScreenScraper only when the build carries
             * credentials. Filled in here rather than in XML for that reason:
             * a static list would offer one that cannot work and then fail
             * with nothing on screen to explain it.
             *
             * The whole row is hidden when there is only one, because a
             * choice between one thing is a question with no answer.
             */
            androidx.preference.ListPreference scraper =
                    findPreference(Prefs.KEY_SCRAPER);
            if (scraper != null) {
                java.util.List<String> names = Scrapers.names(getActivity());
                CharSequence[] entries = names.toArray(new CharSequence[0]);

                scraper.setEntries(entries);
                scraper.setEntryValues(entries);
                scraper.setVisible(names.size() > 1);

                java.util.List<Provider> using = Scrapers.enabled(getActivity());
                scraper.setSummary(using.isEmpty() ? "" : using.get(0).name());
            }

            Preference scrapeMedia = findPreference(Prefs.KEY_SCRAPE_MEDIA);
            if (scrapeMedia != null) {
                int chosen = Scrapers.wanted(getActivity()).requests();
                int available = getResources()
                        .getStringArray(R.array.scrape_media_folders).length;

                scrapeMedia.setSummary(chosen == 0
                        ? getString(R.string.scrape_media_none)
                        : getString(R.string.scrape_media_summary,
                                    chosen, available, chosen + 1));
            }

            Preference scraperPassword = findPreference(Prefs.KEY_SCRAPER_PASSWORD);
            if (scraperPassword != null) {
                String password = settings.getString(Prefs.KEY_SCRAPER_PASSWORD, "");
                scraperPassword.setSummary(password == null || password.isEmpty()
                        ? R.string.settings_scraper_password_none
                        : R.string.settings_scraper_password_set);
            }

            // Not while a move is in flight - see movingData. This runs after
            // every preference change, so without the guard the row goes back
            // to naming a folder the files have not reached yet, which is
            // exactly the thing "Moving…" is there to say is not true yet.
            Preference folder = findPreference(Storage.KEY_STATES_ROOT);
            if (folder != null && !movingData) {
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
                        : counted(R.plurals.settings_esde_status_summary,
                                Metadata.count(getActivity()),
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
