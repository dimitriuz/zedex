package com.fusemobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.widget.Toast;

import java.io.File;
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
public class SettingsActivity extends Activity {

    static final String PREFS = "fuse";

    // Keys shared with FuseActivity.
    static final String KEY_MACHINE = "machine";
    static final String KEY_FAST_TAPE = "fastTape";
    static final String KEY_TAPE_SOUND = "tapeSound";
    static final String KEY_AUTOLOAD = "autoLoad";
    static final String KEY_ISSUE2 = "issue2";
    static final String KEY_BW_TV = "bwTv";
    static final String KEY_SPEED = "speed";
    static final String KEY_SOUND = "sound";
    static final String KEY_AY_VOLUME = "volumeAy";
    static final String KEY_BEEPER_VOLUME = "volumeBeeper";
    static final String KEY_KEEP_SCREEN_ON = "keepScreenOn";
    static final String KEY_SNAPSHOT_FORMAT = "snapshotFormat";

    private static final int REQUEST_CONTENT_TREE = 2;
    private static final int REQUEST_DATA_TREE = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new SettingsFragment())
                .commit();
    }

    public static class SettingsFragment extends PreferenceFragment
            implements android.content.SharedPreferences.OnSharedPreferenceChangeListener {

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            PreferenceManager manager = getPreferenceManager();
            manager.setSharedPreferencesName(PREFS);
            addPreferencesFromResource(R.xml.settings);

            populateMachines();
            updateSummaries();

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
        }

        /**
         * The folders this device offers without a permission, plus anywhere
         * at all if the user is willing to grant one.
         */
        private void chooseDataFolder() {
            List<File> roots = Storage.roots(getActivity());
            String[] items = new String[roots.size() + 1];

            for (int i = 0; i < roots.size(); i++) {
                items[i] = Storage.label(getActivity(), roots.get(i))
                        + "\n" + roots.get(i).getAbsolutePath();
            }
            items[roots.size()] = getString(R.string.settings_choose_folder);

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
        private void chooseAnyFolder() {
            if (!Storage.canUseAnyFolder()) {
                new AlertDialog.Builder(getActivity(),
                        android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage(R.string.settings_all_files)
                        .setPositiveButton(R.string.settings_grant, (dialog, which) -> {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getActivity().getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }

            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                    REQUEST_DATA_TREE);
        }

        private void useFolder(File folder) {
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

            if (request != REQUEST_CONTENT_TREE) return;

            // Without this the grant dies with the activity.
            getActivity().getContentResolver().takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);

            getPreferenceManager().getSharedPreferences().edit()
                    .putString(Storage.KEY_CONTENT_TREE, tree.toString()).apply();
            updateSummaries();
        }

        /** The machine list comes from Fuse itself rather than a fixed array. */
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

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
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
            apply(preferences, key);
            updateSummaries();
        }

        private void apply(android.content.SharedPreferences preferences, String key) {
            switch (key) {
                case KEY_FAST_TAPE:
                    FuseNative.setFastTape(preferences.getBoolean(key, true));
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
                case KEY_BW_TV:
                    FuseNative.setBlackAndWhite(preferences.getBoolean(key, false));
                    break;
                case KEY_SOUND:
                    FuseNative.setSound(preferences.getBoolean(key, true));
                    break;
                case KEY_SPEED:
                    FuseNative.setSpeed(number(preferences, key, 100));
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
                         Storage.screenshotsDirectory(getActivity()));
            Storage.move(getActivity(), new File(previous, "recordings"),
                         Storage.recordingsDirectory(getActivity()));

            // chdir is process wide and immediate, so the running emulator
            // finds ROMs in the new place too - no restart needed.
            FuseNative.setWorkingDirectory(
                    Storage.romsDirectory(getActivity()).getAbsolutePath());

            Toast.makeText(getActivity(), R.string.settings_states_moved,
                    Toast.LENGTH_SHORT).show();
        }

        /** ListPreference stores numbers as strings. */
        private static int number(android.content.SharedPreferences preferences,
                                  String key, int fallback) {
            try {
                return Integer.parseInt(preferences.getString(key, String.valueOf(fallback)));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private void updateSummaries() {
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

            for (String key : new String[] { KEY_MACHINE, KEY_SPEED, KEY_SNAPSHOT_FORMAT,
                                             KEY_AY_VOLUME, KEY_BEEPER_VOLUME }) {
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
