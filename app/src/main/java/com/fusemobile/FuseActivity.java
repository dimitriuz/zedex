package com.fusemobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Hosts the emulator.
 *
 * Fuse runs unmodified on its own thread behind {@link FuseNative}, drawing
 * into this activity's {@link SurfaceView} through GLES. All this class does
 * is prepare the Unix environment Fuse expects, hand it a surface, and
 * forward input.
 */
public class FuseActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "FuseMobile";

    /** Assets subdirectory unpacked into {@code getFilesDir()/fuse}. */
    private static final String DATA_DIR = "fuse";

    private static final String PREFS = SettingsActivity.PREFS;

    /** Fuse's short id for the machine to boot, e.g. "48" or "128". */
    private static final String PREF_MACHINE = SettingsActivity.KEY_MACHINE;
    private static final String DEFAULT_MACHINE = "128";

    /** How long to give the emulation thread to act on a machine change. */
    private static final long MACHINE_SETTLE_MS = 500;

    private static final int REQUEST_OPEN_FILE = 1;

    /** Where picked files are staged for Fuse to open. */
    private static final String MEDIA_DIR = "media";

    /** Save state slots, kept in app storage. */
    private static final String STATE_DIR = "states";
    private static final int STATE_SLOTS = 4;

    private SharedPreferences preferences;
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        File files = getFilesDir();
        try {
            installAssets(DATA_DIR, new File(files, DATA_DIR));
        } catch (IOException e) {
            Log.e(TAG, "failed to unpack Fuse data files", e);
        }

        try {
            // Fuse resolves its config through $XDG_CONFIG_HOME / $HOME and
            // writes temporary files to $TMPDIR; none of the Unix defaults
            // (/tmp, an unset HOME) are writable on Android.
            Os.setenv("HOME", files.getAbsolutePath(), true);
            Os.setenv("XDG_CONFIG_HOME", files.getAbsolutePath(), true);
            Os.setenv("TMPDIR", getCacheDir().getAbsolutePath(), true);
        } catch (ErrnoException e) {
            Log.e(TAG, "failed to set up environment", e);
        }

        // The emulated screen takes whatever the keyboard leaves. In portrait
        // that is the classic 4:3-above-keys layout; in landscape the keyboard
        // caps itself at half the window.
        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);

        FrameLayout screen = new FrameLayout(this);
        screen.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        screen.addView(buildMenuButton());

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xff000000);
        layout.addView(screen, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        layout.addView(new SpectrumKeyboardView(this),
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(layout);

        layout.setFocusableInTouchMode(true);
        layout.requestFocus();

        getWindow().setDecorFitsSystemWindows(false);

        handleViewIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    /** Opens media handed to us by a file manager, or by `am start -a VIEW`. */
    private void handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;

        Uri uri = intent.getData();
        if (uri == null) return;

        // Safe before Fuse has started: the command simply waits in the queue
        // until the emulation thread drains it.
        new Thread(() -> stageAndOpen(uri)).start();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (preferences.getBoolean(SettingsActivity.KEY_KEEP_SCREEN_ON, true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            WindowInsetsController insets = getWindow().getInsetsController();
            if (insets != null) {
                insets.hide(WindowInsets.Type.systemBars());
                insets.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    // --- menu -----------------------------------------------------------

    private Button buildMenuButton() {
        Button button = new Button(this);
        button.setText("\u2630");
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(0x66000000);
        button.setOnClickListener(v -> showMenu());

        int size = Math.round(48 * getResources().getDisplayMetrics().density);
        int margin = size / 4;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        params.setMargins(margin, margin, margin, margin);
        button.setLayoutParams(params);

        return button;
    }

    private void showMenu() {
        String[] items = {
            getString(R.string.menu_open),
            getString(R.string.menu_save_state),
            getString(R.string.menu_load_state),
            getString(R.string.menu_settings),
            getString(R.string.menu_machine),
            getString(R.string.menu_reset),
            getString(R.string.menu_nmi),
        };

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: pickFile(); break;
                        case 1: showStateDialog(true); break;
                        case 2: showStateDialog(false); break;
                        case 3: startActivity(new Intent(this, SettingsActivity.class)); break;
                        case 4: showMachineDialog(); break;
                        case 5: confirmReset(); break;
                        case 6: FuseNative.nmi(); break;
                    }
                })
                .show();
    }

    // --- save states ----------------------------------------------------

    /**
     * Slots are plain .szx files in app storage. SZX is libspectrum's own
     * format and the only one that can hold every machine we emulate.
     */
    private File stateFile(int slot) {
        return new File(new File(getFilesDir(), STATE_DIR), "slot" + slot + ".szx");
    }

    private void showStateDialog(boolean saving) {
        String[] slots = new String[STATE_SLOTS];
        DateFormat when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

        for (int i = 0; i < STATE_SLOTS; i++) {
            File file = stateFile(i + 1);
            slots[i] = file.exists()
                    ? getString(R.string.state_used, i + 1, when.format(new Date(file.lastModified())))
                    : getString(R.string.state_empty, i + 1);
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(saving ? R.string.menu_save_state : R.string.menu_load_state)
                .setItems(slots, (dialog, which) -> useSlot(which + 1, saving))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void useSlot(int slot, boolean saving) {
        File file = stateFile(slot);

        if (saving) {
            File directory = file.getParentFile();
            if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
                Toast.makeText(this, R.string.state_failed, Toast.LENGTH_LONG).show();
                return;
            }
            FuseNative.saveSnapshot(file.getAbsolutePath());
            Toast.makeText(this, getString(R.string.state_saved, slot),
                    Toast.LENGTH_SHORT).show();
        } else {
            if (!file.exists()) {
                Toast.makeText(this, getString(R.string.state_empty, slot),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            FuseNative.loadSnapshot(file.getAbsolutePath());
            Toast.makeText(this, getString(R.string.state_loaded, slot),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // --- opening media --------------------------------------------------

    private void pickFile() {
        // Spectrum media has no registered MIME types, so anything goes and
        // Fuse decides what it is by content.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        try {
            startActivityForResult(intent, REQUEST_OPEN_FILE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);

        if (request != REQUEST_OPEN_FILE || result != RESULT_OK || data == null) return;

        Uri uri = data.getData();
        if (uri != null) new Thread(() -> stageAndOpen(uri)).start();
    }

    /**
     * Fuse opens files by path, so the picked document is copied into the
     * cache first. The original name is kept because libspectrum uses the
     * extension as a hint when identifying the file.
     */
    private void stageAndOpen(Uri uri) {
        File dir = new File(getCacheDir(), MEDIA_DIR);
        File staged = new File(dir, displayName(uri));

        if (!dir.isDirectory() && !dir.mkdirs()) {
            reportOpenFailed();
            return;
        }

        // Only the current file is of interest; anything Fuse still needs it
        // has already read into memory.
        File[] previous = dir.listFiles();
        if (previous != null) {
            for (File file : previous) {
                if (!file.equals(staged)) file.delete();
            }
        }

        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(staged)) {
            if (in == null) throw new IOException("cannot read " + uri);

            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "failed to stage " + uri, e);
            reportOpenFailed();
            return;
        }

        FuseNative.openFile(staged.getAbsolutePath());
    }

    private void reportOpenFailed() {
        runOnUiThread(() ->
                Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show());
    }

    /** The document's own name, reduced to something safe to write. */
    private String displayName(Uri uri) {
        String name = null;

        try (Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                name = cursor.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot read the name of " + uri, e);
        }

        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "spectrum.tap";

        name = name.replace('/', '_').replace('\\', '_');
        return name.isEmpty() ? "spectrum.tap" : name;
    }

    private void confirmReset() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(R.string.reset_confirm)
                .setPositiveButton(R.string.menu_reset, (dialog, which) -> FuseNative.reset())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showMachineDialog() {
        String[] names = FuseNative.machineNames();
        if (names.length == 0) return;   // Fuse has not finished starting

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.machine_title)
                .setSingleChoiceItems(names, FuseNative.currentMachine(),
                        (dialog, which) -> {
                            selectMachine(which);
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void selectMachine(int index) {
        String[] names = FuseNative.machineNames();

        FuseNative.selectMachine(index);

        // The change happens on the emulation thread, and it can fail: Fuse
        // falls back to 48K when a machine's ROMs are missing (Pentagon and
        // Scorpion need ROMs that are not redistributable). Check what
        // actually ended up running rather than assuming we got it.
        getWindow().getDecorView().postDelayed(() -> {
            if (FuseNative.currentMachine() != index && index < names.length) {
                Toast.makeText(this, getString(R.string.machine_unavailable,
                        names[index]), Toast.LENGTH_LONG).show();
            }
            rememberMachine();
        }, MACHINE_SETTLE_MS);
    }

    /** Persists whichever machine is really running, for the next launch. */
    private void rememberMachine() {
        int current = FuseNative.currentMachine();
        String[] ids = FuseNative.machineIds();

        if (current >= 0 && current < ids.length) {
            preferences.edit().putString(PREF_MACHINE, ids[current]).apply();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        rememberMachine();
    }

    // --- surface lifecycle ---------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // surfaceChanged() always follows, and that is where the surface is
        // handed over.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        FuseNative.surfaceChanged(holder.getSurface());

        if (!started) {
            started = true;
            FuseNative.start(startArguments());
        }
    }

    /**
     * Options are passed on the command line rather than queued, so they are
     * in force before Fuse finishes starting - a file handed to us by an
     * intent can be loading before the queue is first drained.
     */
    private String[] startArguments() {
        List<String> arguments = new ArrayList<>();

        arguments.add("fuse");
        arguments.add("--machine");
        arguments.add(preferences.getString(PREF_MACHINE, DEFAULT_MACHINE));

        if (!preferences.getBoolean(SettingsActivity.KEY_FAST_TAPE, true)) {
            arguments.add("--no-traps");
            arguments.add("--no-fastload");
            arguments.add("--no-accelerate-loader");
        }

        flag(arguments, SettingsActivity.KEY_TAPE_SOUND, true, "loading-sound");
        flag(arguments, SettingsActivity.KEY_AUTOLOAD, true, "auto-load");
        flag(arguments, SettingsActivity.KEY_ISSUE2, false, "issue2");
        flag(arguments, SettingsActivity.KEY_BW_TV, false, "bw-tv");
        flag(arguments, SettingsActivity.KEY_SOUND, true, "sound");

        value(arguments, SettingsActivity.KEY_SPEED, 100, "speed");
        value(arguments, SettingsActivity.KEY_AY_VOLUME, 100, "volume-ay");
        value(arguments, SettingsActivity.KEY_BEEPER_VOLUME, 100, "volume-beeper");

        return arguments.toArray(new String[0]);
    }

    /** Fuse generates --x / --no-x for every boolean setting. */
    private void flag(List<String> arguments, String key, boolean fallback, String option) {
        boolean on = preferences.getBoolean(key, fallback);
        arguments.add(on ? "--" + option : "--no-" + option);
    }

    private void value(List<String> arguments, String key, int fallback, String option) {
        String stored = preferences.getString(key, String.valueOf(fallback));
        arguments.add("--" + option);
        arguments.add(stored);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        FuseNative.surfaceDestroyed();
    }

    // --- input ----------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event);
        FuseNative.key(keyCode, true);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event);
        FuseNative.key(keyCode, false);
        return true;
    }

    // --- assets -----------------------------------------------------------

    /**
     * Copies an assets directory into internal storage. Fuse opens these with
     * plain stdio, so they cannot stay inside the APK.
     */
    private void installAssets(String assetDir, File target) throws IOException {
        String[] entries = getAssets().list(assetDir);
        if (entries == null || entries.length == 0) return;

        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("cannot create " + target);
        }

        for (String entry : entries) {
            String assetPath = assetDir + "/" + entry;
            File out = new File(target, entry);

            String[] children = getAssets().list(assetPath);
            if (children != null && children.length > 0) {
                installAssets(assetPath, out);
                continue;
            }

            // Data files are read-only and versioned with the APK, so an
            // existing copy of the right size is always up to date.
            try (InputStream in = getAssets().open(assetPath)) {
                if (out.exists() && out.length() == in.available()) continue;
            }

            try (InputStream in = getAssets().open(assetPath);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        }
    }
}
