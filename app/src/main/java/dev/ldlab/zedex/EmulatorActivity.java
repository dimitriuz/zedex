package dev.ldlab.zedex;

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
import android.widget.ScrollView;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Hosts the emulator.
 *
 * Fuse runs unmodified on its own thread behind {@link FuseNative}, drawing
 * into this activity's {@link SurfaceView} through GLES. All this class does
 * is prepare the Unix environment Fuse expects, hand it a surface, and
 * forward input.
 */
public class EmulatorActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "Zedex";

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
    private static final int REQUEST_LOAD_DISK = 4;
    private static final int REQUEST_IMPORT_ROMS_TREE = 5;

    /** What a ROM is called, whatever else is in the folder beside it. */
    private static final String ROM_SUFFIX = ".rom";

    /** A downloaded set arrives as one of these, so it is unpacked in place. */
    private static final String ZIP_SUFFIX = ".zip";

    /** A downloaded set unpacks into a folder or two, not a deep tree. */
    private static final int ROM_SEARCH_DEPTH = 3;

    /** Enough for every machine Fuse knows, with room to spare. */
    private static final int ROM_SEARCH_LIMIT = 256;

    /**
     * How long Fuse gets to publish a machine before its start is called
     * failed. Generous: this only runs while the screen is black anyway.
     */
    private static final long START_TIMEOUT_MS = 6000;
    private static final long START_POLL_MS = 500;

    /** Where picked files are staged for Fuse to open. */
    private static final String MEDIA_DIR = "media";

    /** Base name for new states: whatever media was loaded last. */
    private static final String PREF_MEDIA_NAME = "mediaName";

    private SharedPreferences preferences;
    private boolean started;

    /** Holds the screen and the keyboard, and decides how they share the window. */
    private EmulatorLayout layout;

    /** Shown in place of the emulated screen when there is no machine. */
    private View romsPanel;
    private TextView romsTitle;
    private TextView romsMessage;
    /** The Restart button and its caption, hidden unless Fuse gave up. */
    private View romsRestart;

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
        // Under the menu button, so ☰ stays reachable over the top of it.
        romsPanel = buildRomsPanel();
        screen.addView(romsPanel);
        screen.addView(buildMenuButton());

        layout = new EmulatorLayout(this);
        layout.setChildren(screen, new SpectrumKeyboardView(this));
        layout.setTemplate(EmulatorLayout.Template.of(
                preferences.getString(SettingsActivity.KEY_LANDSCAPE_LAYOUT, null)));

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

        // The settings screen can have changed it while we were away.
        layout.setTemplate(EmulatorLayout.Template.of(
                preferences.getString(SettingsActivity.KEY_LANDSCAPE_LAYOUT, null)));
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
        // The only way a test - or TalkBack - can name this.
        button.setContentDescription(getString(R.string.menu_button));
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
            getString(R.string.menu_media),
            getString(R.string.menu_disks),
            getString(R.string.menu_capture),
            getString(R.string.menu_layout),
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
                        case 3: showMediaMenu(); break;
                        case 4: showDiskMenu(); break;
                        case 5: showCaptureMenu(); break;
                        case 6: showLayoutDialog(); break;
                        case 7: startActivity(new Intent(this, SettingsActivity.class)); break;
                        case 8: showMachineDialog(); break;
                        case 9: confirmReset(); break;
                        case 10:
                            FuseNative.nmi();
                            note(R.string.nmi_done);
                            break;
                    }
                })
                .show();
    }

    // --- tapes ------------------------------------------------------------

    /**
     * The machine can write to its tape as well as read from it: Fuse's tape
     * traps catch the ROM's save routine, so a BASIC {@code SAVE "name"}
     * appends to the tape held in memory, and this is how that tape reaches
     * a file.
     */
    private void showMediaMenu() {
        String[] items = {
            getString(R.string.tape_save),
            getString(R.string.tape_new),
        };

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.menu_media)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) saveTape(); else confirmNewTape();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- screenshots and recording -----------------------------------------

    /**
     * A picture of the emulated screen, or a film of it.
     *
     * The two formats are offered rather than settled in settings because
     * they are for different things: a GIF drops straight into a forum post
     * and keeps the palette exactly, an MP4 is smaller and takes sound
     * eventually.
     */
    private void showCaptureMenu() {
        boolean recording = Recorder.isRecording();

        String[] items = recording
                ? new String[] {
                    getString(R.string.capture_screenshot),
                    getString(R.string.capture_stop),
                    getString(R.string.capture_open_folder),
                  }
                : new String[] {
                    getString(R.string.capture_screenshot),
                    getString(R.string.capture_gif),
                    getString(R.string.capture_mp4),
                    getString(R.string.capture_open_folder),
                  };

        int openFolder = items.length - 1;

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.menu_capture)
                .setItems(items, (dialog, which) -> {
                    if (which == openFolder) {
                        openRecordingsFolder();
                    } else if (which == 0) {
                        takeScreenshot();
                    } else if (recording) {
                        Recorder.stop();
                    } else {
                        startRecording(which == 1 ? Recorder.Format.GIF
                                                  : Recorder.Format.MP4);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Hands the folder to whatever browses files on this device.
     *
     * There are two ways to ask and neither is guaranteed: viewing the
     * folder as a document is what the Files app understands, and the
     * document picker opened at that folder is the fallback. If the data
     * folder is the app's own, no intent can reach it and the path is all
     * there is to offer.
     */
    private void openRecordingsFolder() {
        File folder = Storage.recordingsDirectory(this);
        folder.mkdirs();

        Uri uri = Storage.documentUriFor(folder);

        if (uri != null) {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // This activity is singleInstance, so without a task of its own
            // the file manager is handed the intent in the background and
            // never comes forward.
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            if (start(view)) return;

            Intent browse = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            browse.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri);
            browse.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (start(browse)) return;
        }

        Toast.makeText(this, getString(R.string.capture_no_browser,
                                       folder.getAbsolutePath()),
                       Toast.LENGTH_LONG).show();
    }

    private boolean start(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            return false;
        }
    }

    private void takeScreenshot() {
        File target = captureFile(Storage.screenshotsDirectory(this), "png");
        if (target == null) return;

        Recorder.screenshotTo(target, this::reportCapture);
    }

    private void startRecording(Recorder.Format format) {
        File target = captureFile(Storage.recordingsDirectory(this),
                                  format.extension);
        if (target == null) return;

        if (!Recorder.start(target, format, this::reportCapture)) {
            Toast.makeText(this, R.string.capture_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        note(R.string.capture_recording, target.getName());
    }

    /** Reported when the file is really written, not when it was asked for. */
    private void reportCapture(File file, String error) {
        if (error == null) {
            Toast.makeText(this, getString(R.string.capture_saved, file.getName()),
                           Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, getString(R.string.capture_failed,
                                           file.getName(), error),
                           Toast.LENGTH_LONG).show();
        }
    }

    /** Named after whatever is loaded, numbered so nothing is overwritten. */
    private File captureFile(File directory, String extension) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Toast.makeText(this, R.string.state_failed, Toast.LENGTH_LONG).show();
            return null;
        }

        String base = preferences.getString(PREF_MEDIA_NAME, null);
        if (base == null || base.isEmpty()) base = "Spectrum";

        File target = new File(directory, base + "." + extension);
        for (int n = 2; target.exists() && n < 10000; n++) {
            target = new File(directory, base + " " + n + "." + extension);
        }

        return target;
    }

    // --- disks ------------------------------------------------------------

    /** Which drive a pending "load disk" belongs to. */
    private int pendingDrive = -1;

    /**
     * Every drive the running machine has, with whatever is in it. The
     * drives depend on the machine and its interfaces, so the list comes
     * from Fuse rather than being a fixed A: to D:.
     */
    private void showDiskMenu() {
        String[] details = FuseNative.driveDetails();
        int[] ids = FuseNative.driveIds();

        if (details.length == 0 || ids.length == 0) {
            Toast.makeText(this, R.string.disk_no_drives, Toast.LENGTH_LONG).show();
            return;
        }

        int count = Math.min(ids.length, details.length / 3);
        String[] items = new String[count];

        for (int i = 0; i < count; i++) {
            String disk = details[i * 3 + 1];
            boolean modified = "1".equals(details[i * 3 + 2]);

            String state = disk.isEmpty() ? getString(R.string.disk_empty)
                    : modified ? getString(R.string.disk_modified, disk) : disk;

            items[i] = details[i * 3] + "\n" + state;
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.menu_disks)
                .setItems(items, (dialog, which) ->
                        showDriveActions(details[which * 3], ids[which],
                                         !details[which * 3 + 1].isEmpty()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDriveActions(String name, int id, boolean loaded) {
        List<String> actions = new ArrayList<>();
        actions.add(getString(R.string.disk_load));
        actions.add(getString(R.string.disk_new));
        if (loaded) {
            actions.add(getString(R.string.disk_save_short));
            actions.add(getString(R.string.disk_eject));
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(name)
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    switch (which) {
                        case 0: loadDiskInto(id); break;
                        case 1: confirmNewDisk(name, id, loaded); break;
                        case 2: saveDisk(name, id); break;
                        default: confirmEject(name, id); break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void loadDiskInto(int id) {
        pendingDrive = id;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        Uri start = Storage.contentFolder(this);
        if (start != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);

        try {
            startActivityForResult(intent, REQUEST_LOAD_DISK);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmNewDisk(String name, int id, boolean loaded) {
        if (!loaded) {
            newDisk(name, id);
            return;
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(getString(R.string.disk_replace, name))
                .setPositiveButton(R.string.disk_new, (dialog, which) ->
                        newDisk(name, id))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void newDisk(String name, int id) {
        FuseNative.newDisk(id >> 8, id & 0xff);
        note(R.string.disk_new_done, name);
    }

    private void confirmEject(String name, int id) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(getString(R.string.disk_eject_confirm, name))
                .setPositiveButton(R.string.disk_eject, (dialog, which) -> {
                    FuseNative.ejectDisk(id >> 8, id & 0xff);
                    note(R.string.disk_ejected, name);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Writes what is in a drive back out. Fuse picks the format from the
     * extension, and not every format it reads can be written - an .scl in
     * particular has to come back as a .trd - so the interface decides the
     * default.
     */
    private void saveDisk(String drive, int id) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(suggestedDiskName(drive, id));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.disk_save, drive))
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        writeDisk(id, sanitise(input.getText().toString())))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** What each disk interface writes by default. */
    private static String extensionFor(int id) {
        switch (id >> 8) {
            case 1: return ".trd";      // Beta 128, TR-DOS
            case 2:                     // +D
            case 5: return ".mgt";      // DISCiPLE
            case 4: return ".opd";      // Opus Discovery
            case 6: return ".d80";      // Didaktik 80
            default: return ".dsk";     // +3, and anything unexpected
        }
    }

    private String suggestedDiskName(String drive, int id) {
        String base = preferences.getString(PREF_MEDIA_NAME, null);
        if (base == null || base.isEmpty()) base = sanitise(drive);

        if (!diskFile(base, id).exists()) return base;

        for (int n = 2; n < 1000; n++) {
            if (!diskFile(base + " " + n, id).exists()) return base + " " + n;
        }
        return base;
    }

    private File diskFile(String name, int id) {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean hasExtension = lower.endsWith(".dsk") || lower.endsWith(".trd")
                || lower.endsWith(".mgt") || lower.endsWith(".opd")
                || lower.endsWith(".img") || lower.endsWith(".udi")
                || lower.endsWith(".fdi") || lower.endsWith(".d80")
                || lower.endsWith(".d40") || lower.endsWith(".sad");

        return new File(Storage.disksDirectory(this),
                        hasExtension ? name : name + extensionFor(id));
    }

    private void writeDisk(int id, String name) {
        if (name.isEmpty()) name = "Disk";

        File directory = Storage.disksDirectory(this);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Toast.makeText(this, R.string.state_failed, Toast.LENGTH_LONG).show();
            return;
        }

        File target = diskFile(name, id);
        FuseNative.writeDisk(id >> 8, id & 0xff, target.getAbsolutePath());
        Toast.makeText(this, getString(R.string.tape_saved, target.getName()),
                Toast.LENGTH_LONG).show();
    }

    private void saveTape() {
        if (!FuseNative.hasTape()) {
            Toast.makeText(this, R.string.tape_empty, Toast.LENGTH_LONG).show();
            return;
        }

        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(suggestedTapeName());
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.tape_save)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        writeTape(sanitise(input.getText().toString())))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String suggestedTapeName() {
        String base = preferences.getString(PREF_MEDIA_NAME, null);
        if (base != null && !base.isEmpty() && !tapeFile(base).exists()) return base;

        for (int n = 1; n < 1000; n++) {
            String numbered = getString(R.string.tape_default_name, n);
            if (!tapeFile(numbered).exists()) return numbered;
        }
        return getString(R.string.tape_default_name, 1);
    }

    /** Fuse picks the format from the extension; anything else means TZX. */
    private File tapeFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String file = lower.endsWith(".tap") || lower.endsWith(".tzx")
                ? name : name + ".tap";

        return new File(Storage.tapesDirectory(this), file);
    }

    private void writeTape(String name) {
        if (name.isEmpty()) name = getString(R.string.tape_default_name, 1);

        File directory = Storage.tapesDirectory(this);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Toast.makeText(this, R.string.state_failed, Toast.LENGTH_LONG).show();
            return;
        }

        File target = tapeFile(name);
        FuseNative.writeTape(target.getAbsolutePath());
        Toast.makeText(this, getString(R.string.tape_saved, target.getName()),
                Toast.LENGTH_LONG).show();
    }

    private void confirmNewTape() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(R.string.tape_new_confirm)
                .setPositiveButton(R.string.tape_new, (dialog, which) -> {
                    FuseNative.newTape();
                    note(R.string.tape_new_done);
                })
                .setNegativeButton(android.R.string.cancel, null)
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

        if (request == REQUEST_IMPORT_ROMS_TREE) {
            Uri tree = data.getData();
            if (tree != null) {
                note(R.string.roms_searching);
                new Thread(() -> copyRomsFromTree(tree)).start();
            }
            return;
        }

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

        if (request == REQUEST_LOAD_DISK) {
            Uri uri = data.getData();
            int drive = pendingDrive;
            pendingDrive = -1;

            if (uri != null && drive >= 0) {
                new Thread(() -> {
                    File staged = stage(uri);
                    if (staged == null) return;
                    FuseNative.insertDisk(drive >> 8, drive & 0xff,
                                          staged.getAbsolutePath());
                    note(R.string.disk_inserted, staged.getName());
                }).start();
            }
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
        File staged = stage(uri);
        if (staged == null) return;

        FuseNative.openFile(staged.getAbsolutePath());
        note(R.string.file_opened, staged.getName());

        String name = staged.getName();
        int dot = name.lastIndexOf('.');
        rememberMediaName(sanitise(dot > 0 ? name.substring(0, dot) : name));
    }

    /** Copies a picked document somewhere Fuse can open by path. */
    private File stage(Uri uri) {
        File dir = new File(getCacheDir(), MEDIA_DIR);
        File staged = new File(dir, displayName(uri));

        if (!dir.isDirectory() && !dir.mkdirs()) {
            reportOpenFailed();
            return null;
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
            return null;
        }

        return staged;
    }

    /** Says an action happened. Fuse itself is silent about most of them. */
    private void note(int message, Object... arguments) {
        runOnUiThread(() -> Toast.makeText(this, getString(message, arguments),
                                           Toast.LENGTH_SHORT).show());
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
                    note(R.string.reset_done);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Picks how the screen and the keyboard share a landscape window. Offered
     * in portrait too — it takes effect on the next rotation, and hiding it
     * would be a menu item that comes and goes.
     */
    private void showLayoutDialog() {
        EmulatorLayout.Template[] templates = EmulatorLayout.Template.values();
        String[] names = getResources().getStringArray(R.array.layout_names);

        int current = 0;
        for (int i = 0; i < templates.length; i++) {
            if (templates[i] == layout.template()) current = i;
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.layout_title)
                .setSingleChoiceItems(names, current, (dialog, which) -> {
                    EmulatorLayout.Template chosen = templates[which];

                    preferences.edit()
                            .putString(SettingsActivity.KEY_LANDSCAPE_LAYOUT, chosen.value)
                            .apply();
                    layout.setTemplate(chosen);

                    dialog.dismiss();
                    if (getResources().getConfiguration().orientation
                            != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                        note(R.string.layout_portrait_note);
                    }
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
            } else if (index < names.length) {
                note(R.string.machine_selected, names[index]);
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
            showRomsPanel(false);
            return;
        }

        hideRomsPanel();
        started = true;

        // Fuse searches the working directory for a ROM before anywhere
        // else, which is how it finds the user's.
        FuseNative.setWorkingDirectory(Storage.romsDirectory(this).getAbsolutePath());
        FuseNative.start(startArguments());
        watchForStartFailure(0);
    }

    /**
     * Fuse publishes a machine as soon as one is running, so no machine long
     * after the emulation thread should have got there means {@code main()}
     * returned instead - which is what an unusable ROM does. Nothing is drawn
     * in that case, so without this the screen simply stays black.
     */
    private void watchForStartFailure(long waited) {
        if (FuseNative.currentMachine() >= 0) return;

        if (waited >= START_TIMEOUT_MS) {
            Log.w(TAG, "no machine after " + waited + "ms; ROMs are unusable");
            showRomsPanel(true);
            return;
        }

        getWindow().getDecorView().postDelayed(
                () -> watchForStartFailure(waited + START_POLL_MS), START_POLL_MS);
    }

    /** Where a set of ROMs under the names Fuse expects can be found. */
    private static final String ROMS_URL =
            "https://archive.org/details/zx-roms-fuse-roms";

    /**
     * What the screen shows when no machine is running, in place of the black
     * that a missing ROM used to leave behind with no way out of it.
     */
    private View buildRomsPanel() {
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        romsTitle = new TextView(this);
        romsTitle.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large);
        romsTitle.setTextColor(Color.WHITE);
        content.addView(romsTitle);

        romsMessage = new TextView(this);
        romsMessage.setTextColor(0xffbbbbbb);
        romsMessage.setPadding(0, pad / 2, 0, pad / 2);
        content.addView(romsMessage);

        // Downloading first: it is the one that needs nothing of the user.
        content.addView(panelChoice(R.string.roms_where,
                R.string.roms_where_hint, v -> offerRomsDownload()));
        content.addView(panelChoice(R.string.roms_folder,
                R.string.roms_folder_hint, v -> importRomsFolder()));
        content.addView(panelChoice(R.string.roms_files,
                R.string.roms_files_hint, v -> importRomFiles()));

        romsRestart = panelChoice(R.string.roms_restart,
                R.string.roms_restart_hint, v -> restartForRoms());
        romsRestart.setVisibility(View.GONE);
        content.addView(romsRestart);

        // Landscape leaves little height, and the message is not short.
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xff000000);
        scroll.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        scroll.setVisibility(View.GONE);

        return scroll;
    }

    /**
     * A button with a line under it saying what it does, since "Choose
     * folder" and "Choose files" are not self-explaining on their own.
     */
    private View panelChoice(int label, int description, View.OnClickListener action) {
        int unit = Math.round(4 * getResources().getDisplayMetrics().density);

        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);

        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(action);
        group.addView(button);

        TextView caption = new TextView(this);
        caption.setText(description);
        caption.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small);
        caption.setTextColor(0xff999999);
        caption.setPadding(unit * 2, unit, unit * 2, unit * 4);
        group.addView(caption);

        return group;
    }

    /**
     * @param startFailed whether Fuse tried and gave up, which needs a new
     *                    process rather than merely more ROMs.
     */
    private void showRomsPanel(boolean startFailed) {
        String path = Storage.romsDirectory(this).getAbsolutePath();

        romsTitle.setText(startFailed ? R.string.roms_start_failed
                                      : R.string.roms_needed);
        romsMessage.setText(startFailed
                ? getString(R.string.roms_start_failed_message, path)
                : getString(R.string.roms_needed_message, path));
        romsRestart.setVisibility(startFailed ? View.VISIBLE : View.GONE);
        romsPanel.setVisibility(View.VISIBLE);
    }

    private void hideRomsPanel() {
        romsPanel.setVisibility(View.GONE);
    }

    private void openRomsPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ROMS_URL)));
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "no browser to open " + ROMS_URL, e);
        }
    }

    /** The one file that archive.org item holds: 47 ROMs, about 350 kB. */
    private static final String ROMS_ZIP_URL =
            "https://archive.org/download/zx-roms-fuse-roms/zx%20roms.zip";

    /**
     * Offers to fetch the set rather than making the user find a browser, an
     * extractor and a file manager first.
     *
     * Asks before doing it, because the ROMs are somebody else's copyright and
     * whether they may be downloaded is not the same everywhere.
     */
    private void offerRomsDownload() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.roms_download_title)
                .setMessage(R.string.roms_download_warning)
                .setPositiveButton(R.string.roms_download, (dialog, which) -> {
                    note(R.string.roms_downloading);
                    new Thread(this::downloadRoms).start();
                })
                .setNeutralButton(R.string.roms_open_page, (dialog, which) -> openRomsPage())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Fetches the set and unpacks it. To a cache file first rather than
     * straight through the unpacker, so a connection that dies half way
     * leaves the ROMs folder as it was instead of half filled.
     */
    private void downloadRoms() {
        File directory = Storage.romsDirectory(this);
        directory.mkdirs();

        File zip = new File(getCacheDir(), "roms-download.zip");
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(ROMS_ZIP_URL).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);

            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(zip)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "cannot download " + ROMS_ZIP_URL, e);
            zip.delete();
            runOnUiThread(() -> Toast.makeText(this, R.string.roms_download_failed,
                                               Toast.LENGTH_LONG).show());
            return;
        } finally {
            if (connection != null) connection.disconnect();
        }

        int copied = 0;
        try (InputStream in = new FileInputStream(zip)) {
            copied = unpackRoms(in, directory);
        } catch (IOException e) {
            Log.e(TAG, "cannot unpack the downloaded set", e);
        }
        zip.delete();

        if (copied == 0) {
            Log.w(TAG, "nothing in the downloaded zip looked like a ROM");
            runOnUiThread(() -> Toast.makeText(this, R.string.roms_download_failed,
                                               Toast.LENGTH_LONG).show());
            return;
        }

        reportRoms(copied);
    }

    /** A zip of ROMs, as archive.org hands them out, opened from a document. */
    private int unpackRoms(Uri source, File directory) {
        try (InputStream in = getContentResolver().openInputStream(source)) {
            if (in == null) return 0;
            return unpackRoms(in, directory);
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "cannot unpack " + source, e);
            return 0;
        }
    }

    /** Takes the ROMs out of a zip and ignores everything else in it. */
    private int unpackRoms(InputStream source, File directory) throws IOException {
        int copied = 0;

        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                // The last component only. Entries carry directories, and a
                // crafted one can carry ../ as easily.
                String name = new File(entry.getName()).getName();
                if (!name.toLowerCase(Locale.ROOT).endsWith(ROM_SUFFIX)) continue;

                try (OutputStream out = new FileOutputStream(new File(directory, name))) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                copied++;
            }
        }

        return copied;
    }

    /**
     * The emulation thread cannot be started twice in one process, so trying
     * again after Fuse has given up means a new one.
     */
    private void restartForRoms() {
        Intent intent = new Intent(this, EmulatorActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
        Runtime.getRuntime().exit(0);
    }

    /** A whole folder of ROMs, which is how a downloaded set arrives. */
    private void importRomsFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

        Uri start = Storage.contentFolder(this);
        if (start != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);

        try {
            startActivityForResult(intent, REQUEST_IMPORT_ROMS_TREE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Individual files, kept alongside the folder picker because Android will
     * not grant a tree on Download - where a downloaded set most often lands -
     * while the file picker can open it perfectly well.
     */
    private void importRomFiles() {
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

    /** Everything that looks like a ROM under a granted tree, then copied. */
    private void copyRomsFromTree(Uri tree) {
        List<Uri> roms = new ArrayList<>();

        try {
            collectRoms(tree, DocumentsContract.getTreeDocumentId(tree), roms, 0);
        } catch (Exception e) {
            Log.w(TAG, "cannot read the folder " + tree, e);
            runOnUiThread(() -> Toast.makeText(this,
                    R.string.roms_folder_unreadable, Toast.LENGTH_LONG).show());
            return;
        }

        if (roms.isEmpty()) {
            // Saying nothing here reads as the picker having done nothing.
            runOnUiThread(() -> Toast.makeText(this, R.string.roms_none_found,
                                               Toast.LENGTH_LONG).show());
            return;
        }

        copyRoms(roms);
    }

    /**
     * Depth-limited walk of a document tree. Subfolders are followed because
     * sets unpack into one, but not far: the tree is whatever was granted, and
     * could be the whole of shared storage.
     */
    private void collectRoms(Uri tree, String parentId, List<Uri> found, int depth) {
        if (depth > ROM_SEARCH_DEPTH || found.size() >= ROM_SEARCH_LIMIT) return;

        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);

        try (Cursor cursor = getContentResolver().query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        }, null, null, null)) {
            if (cursor == null) return;

            while (cursor.moveToNext() && found.size() < ROM_SEARCH_LIMIT) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collectRoms(tree, id, found, depth + 1);
                    continue;
                }

                if (name == null) continue;

                // A zip counts: a set downloaded and left where it landed is
                // still a folder of ROMs as far as the user is concerned. Only
                // its .rom entries are taken, so an unrelated zip costs
                // nothing but the time to look inside it.
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(ROM_SUFFIX) || lower.endsWith(ZIP_SUFFIX)) {
                    found.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                }
            }
        } catch (Exception e) {
            // One unreadable subfolder should not lose the rest.
            Log.w(TAG, "cannot list " + parentId, e);
        }
    }

    /** Copies chosen files into the roms folder, then tries to start again. */
    private void copyRoms(List<Uri> sources) {
        File directory = Storage.romsDirectory(this);
        directory.mkdirs();

        int copied = 0;
        for (Uri source : sources) {
            String name = displayName(source);

            // A set downloaded from archive.org is a zip; unpack it rather
            // than making the user find an extractor first.
            if (name.toLowerCase(Locale.ROOT).endsWith(ZIP_SUFFIX)) {
                copied += unpackRoms(source, directory);
                continue;
            }

            File target = new File(directory, name);

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

        reportRoms(copied);
    }

    /** Says what arrived, and gets a machine going if one can now run. */
    private void reportRoms(int copied) {
        runOnUiThread(() -> {
            Toast.makeText(this, getString(R.string.roms_imported, copied),
                    Toast.LENGTH_SHORT).show();

            if (!started) {
                startEmulator();
            } else if (FuseNative.currentMachine() < 0) {
                // Fuse already tried and gave up; more ROMs cannot reach it.
                showRomsPanel(true);
            }
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
