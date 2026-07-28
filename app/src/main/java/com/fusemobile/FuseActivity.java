package com.fusemobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
    private static final int REQUEST_IMPORT_ROMS = 3;

    /** Where picked files are staged for Fuse to open. */
    private static final String MEDIA_DIR = "media";

    /** Base name for new states: whatever media was loaded last. */
    private static final String PREF_MEDIA_NAME = "mediaName";

    private SharedPreferences preferences;
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        FuseNative.attach(this);
        Storage.createFolders(this);

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
     * Saved states are named files rather than numbered slots, so there can
     * be as many as wanted and each says what it is. A state is a snapshot
     * plus a thumbnail sharing its base name; the snapshot's extension
     * decides its format, so a state written before the format setting
     * changed still loads.
     */
    private static final String[] FORMATS = { "szx", "z80", "sna" };

    private static final class SavedState {
        final File snapshot;
        final String name;

        SavedState(File snapshot, String name) {
            this.snapshot = snapshot;
            this.name = name;
        }

        String format() {
            String file = snapshot.getName();
            return file.substring(file.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        }
    }

    private File stateDirectory() {
        return Storage.statesDirectory(this);
    }

    private File thumbnailFor(String name) {
        return new File(stateDirectory(), name + ".thumb");
    }

    /** Newest first, which is nearly always the one wanted. */
    private List<SavedState> savedStates() {
        List<SavedState> states = new ArrayList<>();
        File[] files = stateDirectory().listFiles();
        if (files == null) return states;

        for (File file : files) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) continue;

            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (Arrays.asList(FORMATS).contains(extension)) {
                states.add(new SavedState(file, name.substring(0, dot)));
            }
        }

        states.sort((a, b) -> Long.compare(b.snapshot.lastModified(),
                                           a.snapshot.lastModified()));
        return states;
    }

    private void showStateDialog(boolean saving) {
        List<SavedState> states = savedStates();

        if (!saving && states.isEmpty()) {
            Toast.makeText(this, R.string.state_none, Toast.LENGTH_SHORT).show();
            return;
        }

        ListView list = new ListView(this);
        list.setAdapter(new StateAdapter(states, saving));

        AlertDialog dialog = new AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(saving ? R.string.menu_save_state : R.string.menu_load_state)
                .setView(list)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();

            if (saving && position == 0) {
                askNameAndSave();
            } else {
                SavedState state = states.get(saving ? position - 1 : position);
                if (saving) confirmOverwrite(state); else load(state);
            }
        });

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (saving && position == 0) return false;

            SavedState state = states.get(saving ? position - 1 : position);
            dialog.dismiss();
            confirmDelete(state);
            return true;
        });

        dialog.show();
    }

    /** Rows of saved states, with "add new" first when saving. */
    private final class StateAdapter extends BaseAdapter {

        private final List<SavedState> states;
        private final boolean saving;

        StateAdapter(List<SavedState> states, boolean saving) {
            this.states = states;
            this.saving = saving;
        }

        @Override
        public int getCount() {
            return states.size() + (saving ? 1 : 0);
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View reuse, ViewGroup parent) {
            View row = reuse != null ? reuse
                    : getLayoutInflater().inflate(R.layout.state_row, parent, false);

            TextView title = row.findViewById(R.id.title);
            TextView subtitle = row.findViewById(R.id.subtitle);
            ImageView thumbnail = row.findViewById(R.id.thumbnail);

            if (saving && position == 0) {
                title.setText(R.string.state_add);
                subtitle.setText(R.string.state_add_summary);
                thumbnail.setImageDrawable(null);
                return row;
            }

            SavedState state = states.get(saving ? position - 1 : position);
            DateFormat when = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT);

            title.setText(state.name);
            subtitle.setText(getString(R.string.state_details,
                    when.format(new Date(state.snapshot.lastModified())),
                    state.format()));
            thumbnail.setImageBitmap(readThumbnail(thumbnailFor(state.name)));

            return row;
        }
    }

    private void askNameAndSave() {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(suggestedName());
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.state_name)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = sanitise(input.getText().toString());
                    if (name.isEmpty()) name = "Snapshot";
                    save(name);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Names a new state after whatever is loaded, which is nearly always what
     * it is a state of, adding a number if that name is taken.
     *
     * A reset or a machine change empties the machine, so there is nothing to
     * name a state after and they are simply numbered instead.
     */
    private String suggestedName() {
        String media = preferences.getString(PREF_MEDIA_NAME, null);

        if (media == null || media.isEmpty()) {
            for (int n = 1; n < 1000; n++) {
                String numbered = getString(R.string.state_default_name, n);
                if (findState(numbered) == null) return numbered;
            }
            return getString(R.string.state_default_name, 1);
        }

        if (findState(media) == null) return media;

        for (int n = 2; n < 1000; n++) {
            if (findState(media + " " + n) == null) return media + " " + n;
        }
        return media;
    }

    private File findState(String name) {
        for (String format : FORMATS) {
            File file = new File(stateDirectory(), name + "." + format);
            if (file.exists()) return file;
        }
        return null;
    }

    /** Keeps names to something that is safe as a filename. */
    private static String sanitise(String name) {
        return name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
    }

    private void confirmOverwrite(SavedState state) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(getString(R.string.state_overwrite, state.name))
                .setPositiveButton(R.string.state_overwrite_confirm,
                        (dialog, which) -> save(state.name))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDelete(SavedState state) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(getString(R.string.state_delete, state.name))
                .setPositiveButton(R.string.state_delete_confirm, (dialog, which) -> {
                    state.snapshot.delete();
                    thumbnailFor(state.name).delete();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void save(String name) {
        File directory = stateDirectory();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Toast.makeText(this, R.string.state_failed, Toast.LENGTH_LONG).show();
            return;
        }

        String format = preferences.getString(
                SettingsActivity.KEY_SNAPSHOT_FORMAT, FORMATS[0]);

        // One snapshot per name, whatever it was saved as before.
        for (String other : FORMATS) {
            if (!other.equals(format)) new File(directory, name + "." + other).delete();
        }

        FuseNative.saveSnapshot(new File(directory, name + "." + format).getAbsolutePath());
        FuseNative.saveThumbnail(thumbnailFor(name).getAbsolutePath());
        Toast.makeText(this, getString(R.string.state_saved, name),
                Toast.LENGTH_SHORT).show();
    }

    private void load(SavedState state) {
        FuseNative.loadSnapshot(state.snapshot.getAbsolutePath());
        rememberMediaName(state.name);
        Toast.makeText(this, getString(R.string.state_loaded, state.name),
                Toast.LENGTH_SHORT).show();
    }

    /** Decodes what {@link FuseNative#saveThumbnail} wrote. */
    private static Bitmap readThumbnail(File file) {
        if (!file.exists()) return null;

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {

            byte[] header = new byte[8];
            in.readFully(header);
            ByteBuffer numbers = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int width = numbers.getInt();
            int height = numbers.getInt();

            if (width <= 0 || height <= 0 || width > 2048 || height > 2048) return null;

            byte[] pixels = new byte[width * height * 4];
            in.readFully(pixels);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
            return bitmap;
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "cannot read thumbnail " + file, e);
            return null;
        }
    }

    /** What a new state will be called: the media that is loaded. */
    private void rememberMediaName(String name) {
        preferences.edit().putString(PREF_MEDIA_NAME, name).apply();
    }

    /** Nothing is loaded any more, so new states go back to being numbered. */
    private void forgetMediaName() {
        preferences.edit().remove(PREF_MEDIA_NAME).apply();
    }

    // --- opening media --------------------------------------------------

    private void pickFile() {
        // Spectrum media has no registered MIME types, so anything goes and
        // Fuse decides what it is by content.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        Uri start = Storage.contentFolder(this);
        if (start != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);

        try {
            startActivityForResult(intent, REQUEST_OPEN_FILE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);

        if (result != RESULT_OK || data == null) return;

        if (request == REQUEST_IMPORT_ROMS) {
            List<Uri> sources = new ArrayList<>();

            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    sources.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                sources.add(data.getData());
            }

            if (!sources.isEmpty()) new Thread(() -> copyRoms(sources)).start();
            return;
        }

        if (request != REQUEST_OPEN_FILE) return;

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

        String name = staged.getName();
        int dot = name.lastIndexOf('.');
        rememberMediaName(sanitise(dot > 0 ? name.substring(0, dot) : name));
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
                .setPositiveButton(R.string.menu_reset, (dialog, which) -> {
                    FuseNative.reset();
                    forgetMediaName();
                })
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
        forgetMediaName();

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

        if (!started) startEmulator();
    }

    /**
     * No ROMs ship with the app, so the first thing to establish is whether
     * the user has provided any. Without them Fuse cannot even reach its
     * fallback 48K machine and gives up hard, so it is not started at all.
     */
    private void startEmulator() {
        if (!Storage.haveRoms(this)) {
            askForRoms();
            return;
        }

        started = true;

        // Fuse searches the working directory for a ROM before anywhere
        // else, which is how it finds the user's.
        FuseNative.setWorkingDirectory(Storage.romsDirectory(this).getAbsolutePath());
        FuseNative.start(startArguments());
    }

    /** Where a set of ROMs under the names Fuse expects can be found. */
    private static final String ROMS_URL =
            "https://archive.org/details/zx-roms-fuse-roms";

    private void askForRoms() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.roms_needed)
                .setMessage(getString(R.string.roms_needed_message,
                        Storage.romsDirectory(this).getAbsolutePath(), ROMS_URL))
                .setPositiveButton(R.string.roms_import, (dialog, which) -> importRoms())
                .setNeutralButton(R.string.roms_where, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ROMS_URL)));
                    } catch (android.content.ActivityNotFoundException e) {
                        Log.w(TAG, "no browser to open " + ROMS_URL, e);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void importRoms() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        Uri start = Storage.contentFolder(this);
        if (start != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);

        try {
            startActivityForResult(intent, REQUEST_IMPORT_ROMS);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Copies chosen files into the roms folder, then tries to start again. */
    private void copyRoms(List<Uri> sources) {
        File directory = Storage.romsDirectory(this);
        directory.mkdirs();

        int copied = 0;
        for (Uri source : sources) {
            File target = new File(directory, displayName(source));

            try (InputStream in = getContentResolver().openInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null) continue;

                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                copied++;
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "cannot import " + source, e);
            }
        }

        int total = copied;
        runOnUiThread(() -> {
            Toast.makeText(this, getString(R.string.roms_imported, total),
                    Toast.LENGTH_SHORT).show();
            if (!started) startEmulator();
        });
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
