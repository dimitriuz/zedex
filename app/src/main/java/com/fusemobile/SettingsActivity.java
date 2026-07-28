package com.fusemobile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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
            populateRoots();
            updateSummaries();

            Preference content = findPreference(Storage.KEY_CONTENT_TREE);
            if (content != null) {
                content.setOnPreferenceClickListener(preference -> {
                    pickContentFolder();
                    return true;
                });
            }
        }

        /**
         * The list of places save states can go is whatever this device
         * offers, so it is built here rather than in the XML.
         */
        private void populateRoots() {
            ListPreference preference =
                    (ListPreference) findPreference(Storage.KEY_STATES_ROOT);
            if (preference == null) return;

            List<File> roots = Storage.roots(getActivity());
            String[] labels = new String[roots.size()];
            String[] paths = new String[roots.size()];

            for (int i = 0; i < roots.size(); i++) {
                labels[i] = Storage.label(getActivity(), roots.get(i));
                paths[i] = roots.get(i).getAbsolutePath();
            }

            preference.setEntries(labels);
            preference.setEntryValues(paths);
            if (preference.getValue() == null) preference.setValue(paths[0]);
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

            if (request != REQUEST_CONTENT_TREE || result != Activity.RESULT_OK
                    || data == null || data.getData() == null) {
                return;
            }

            Uri tree = data.getData();

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
                case Storage.KEY_STATES_ROOT:
                    moveData();
                    break;
                default:
                    // The machine and keep-screen-on are read where they are
                    // needed rather than pushed.
                    break;
            }
        }

        /** Follows the setting with the files themselves. */
        private void moveData() {
            Storage.createFolders(getActivity());

            move("states", Storage.statesDirectory(getActivity()));
            move("roms", Storage.romsDirectory(getActivity()));

            // chdir is process wide and immediate, so the running emulator
            // finds ROMs in the new place too - no restart needed.
            FuseNative.setWorkingDirectory(
                    Storage.romsDirectory(getActivity()).getAbsolutePath());

            Toast.makeText(getActivity(), R.string.settings_states_moved,
                    Toast.LENGTH_SHORT).show();
        }

        private void move(String folder, File to) {
            for (File root : Storage.roots(getActivity())) {
                File from = new File(root, folder);
                if (from.equals(to) || !from.isDirectory()) continue;

                Storage.moveStates(getActivity(), from, to);
            }
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
            Preference states = findPreference(Storage.KEY_STATES_ROOT);
            if (states != null) {
                states.setSummary(
                        Storage.statesDirectory(getActivity()).getAbsolutePath());
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
