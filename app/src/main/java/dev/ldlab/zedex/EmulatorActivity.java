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
import android.widget.ImageButton;
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
    private static final int REQUEST_LOAD_DISK = 4;

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

    /** Kempston's place in Fuse's {@code joystick_type_t}. */
    private static final int JOYSTICK_KEMPSTON = 2;

    /**
     * Fuse's own default is None, which would leave the on-screen pad with
     * nothing to do; Kempston is what most Spectrum games that take a joystick
     * at all expect.
     */
    private static final int DEFAULT_JOYSTICK_TYPE = JOYSTICK_KEMPSTON;

    private SharedPreferences preferences;
    private boolean started;

    /** Holds the screen and the keyboard, and decides how they share the window. */
    private EmulatorLayout layout;

    /** The ☰ sheet. Built once and slid in and out. */
    private MenuDrawer menu;

    /** The ☰ button itself, which fades out when it is not being used. */
    private QuickBar quickBar;

    /** The big play button over the picture, shown only while paused. */
    private ImageButton playButton;

    /** The bar's pause icon, which becomes play; see {@link #applyPause}. */
    private ImageButton pauseAction;

    /** ☰ itself, which is the one thing worth keeping with no machine. */
    private ImageButton menuAction;

    /**
     * Two reasons to be stopped, kept apart. The user's pause survives going
     * away and coming back; the automatic one does not, and must not undo the
     * user's when it lifts.
     */
    private boolean pausedByUser;
    private boolean pausedByAndroid;

    /** Asks for ROMs, and fetches them; shown when there is no machine. */
    private RomsPanel roms;

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
        // Both are siblings of the screen rather than children of it: the panel
        // takes the whole window, and the ☰ button has to stay on top of it.
        roms = new RomsPanel(this, romsHost);
        menu = buildMenu();
        quickBar = buildQuickBar();
        playButton = buildPlayButton();

        // A tap anywhere on the picture brings ☰ back; the sheet closing takes
        // it away again, so it is only ever over the screen while in use.
        screen.setOnClickListener(v -> revealQuickBar());
        menu.setOnClosed(fadeQuickBar);

        layout = new EmulatorLayout(this);
        layout.setChildren(screen, new SpectrumKeyboardView(this),
                           new JoystickView(this, JoystickView.Part.PAD),
                           new JoystickView(this, JoystickView.Part.FIRE),
                           new ActivityLights(this), playButton,
                           roms.view(), quickBar, menu);
        layout.setJoystickVisible(
                preferences.getBoolean(SettingsActivity.KEY_JOYSTICK, true));
        layout.setKeyboardVisible(
                preferences.getBoolean(SettingsActivity.KEY_KEYBOARD, true));
        layout.setLightsVisible(
                preferences.getBoolean(SettingsActivity.KEY_INDICATORS, true));

        revealQuickBar();
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

        // The settings screen can have changed these while we were away.
        layout.setTemplate(EmulatorLayout.Template.of(
                preferences.getString(SettingsActivity.KEY_LANDSCAPE_LAYOUT, null)));
        layout.setLightsVisible(
                preferences.getBoolean(SettingsActivity.KEY_INDICATORS, true));

        pausedByAndroid = false;
        applyPause();
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

    /**
     * The whole of what {@link RomsPanel} needs from here: try again once ROMs
     * have arrived, and keep the quick bar up while the panel covers the screen.
     */
    private final RomsPanel.Host romsHost = new RomsPanel.Host() {
        @Override
        public void onRomsChanged() {
            startEmulator();
        }

        @Override
        public boolean hasStarted() {
            return started;
        }

        @Override
        public void setTakeover(boolean covering) {
            pinMenuButton(covering);
        }
    };

    // --- menu -----------------------------------------------------------

    /**
     * The quick bar: the handful of things done often enough that going
     * through the sheet for them is a nuisance, plus \u2630 itself.
     *
     * \u2630 is last so it stays in the corner it has always been in. What sits
     * beside it is the short list of things wanted mid-game \u2014 somewhere to put
     * the machine down and pick it up again, a picture of it, and getting the
     * controls out of the way \u2014 while everything that is chosen rather than
     * reached for stays in the sheet.
     *
     * Where the bar goes is {@link EmulatorLayout}'s business: it follows the
     * screen, which moves with the template.
     */
    private QuickBar buildQuickBar() {
        QuickBar bar = new QuickBar(this);

        bar.addAction(R.drawable.ic_folder, getString(R.string.menu_open),
                      this::pickFile);
        bar.addAction(R.drawable.ic_save, getString(R.string.menu_save_state),
                      () -> showStateDialog(true));
        bar.addAction(R.drawable.ic_load, getString(R.string.menu_load_state),
                      () -> showStateDialog(false));

        pauseAction = bar.addAction(R.drawable.ic_pause,
                                    getString(R.string.pause_pause),
                                    () -> pause(!pausedByUser));

        bar.addGroup(R.drawable.ic_camera, getString(R.string.menu_capture),
                     this::fillCaptureBar);
        bar.addGroup(R.drawable.ic_controls, getString(R.string.menu_controls),
                     this::fillControlsBar);
        bar.addGroup(R.drawable.ic_machine, getString(R.string.menu_machine_group),
                     this::fillMachineBar);

        menuAction = bar.addAction(R.drawable.ic_menu, getString(R.string.menu_button),
                                   () -> menu.open());

        return bar;
    }

    /** Reset is here rather than on the bar itself: it asks first, and an icon
     *  that throws the game away wants a word beside it. */
    private void fillMachineBar(QuickBar bar) {
        bar.addToRow(R.drawable.ic_swap, getString(R.string.menu_machine),
                     this::showMachineDialog);
        bar.addToRow(R.drawable.ic_reset, getString(R.string.menu_reset),
                     this::confirmReset);
    }

    private void fillCaptureBar(QuickBar bar) {
        bar.addToRow(R.drawable.ic_camera, getString(R.string.capture_screenshot),
                     this::takeScreenshot);

        if (Recorder.isRecording()) {
            bar.addToRow(R.drawable.ic_stop, getString(R.string.capture_stop),
                         Recorder::stop);
        } else {
            bar.addToRow(R.drawable.ic_record, getString(R.string.capture_gif),
                         () -> startRecording(Recorder.Format.GIF));
            bar.addToRow(R.drawable.ic_film, getString(R.string.capture_mp4),
                         () -> startRecording(Recorder.Format.MP4));
        }
    }

    /**
     * The two toggles, named for what they would do rather than for what they
     * are, since an icon that means "joystick" cannot also say which way it is
     * about to go.
     */
    private void fillControlsBar(QuickBar bar) {
        boolean pad = layout.joystickVisible();
        boolean keys = layout.keyboardVisible();

        bar.addToRow(R.drawable.ic_joystick,
                     getString(pad ? R.string.quick_joystick_hide
                                   : R.string.quick_joystick_show),
                     () -> showJoystick(!pad));
        bar.addToRow(R.drawable.ic_keyboard,
                     getString(keys ? R.string.quick_keyboard_hide
                                    : R.string.quick_keyboard_show),
                     () -> showKeyboard(!keys));
    }

    /**
     * How long the quick bar stays up before fading out again.
     *
     * It sits over the emulated screen, in the corner of the picture, so it is
     * in the way of the thing it belongs to. Tapping the screen brings it back.
     * It starts visible rather than hidden: a button nobody knows is there is
     * worse than one briefly in the way.
     */
    private static final long BAR_LINGER_MS = 3000;

    /** Stays up as long as the ROMs panel does; see {@link #revealQuickBar}. */
    private boolean barPinned;

    /**
     * Whether the bar is meant to be up. The fade's end action has to ask,
     * because cancelling a ViewPropertyAnimator still runs it \u2014 so a reveal
     * arriving mid-fade would otherwise be undone a moment after it happened,
     * which is exactly what it did.
     */
    private boolean barWanted;

    private final Runnable fadeQuickBar = () -> {
        if (barPinned) return;

        barWanted = false;
        // A group left open would be waiting there on the way back, which is
        // not where the bar was left off.
        quickBar.collapse();
        quickBar.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (!barWanted) quickBar.setVisibility(View.GONE);
        });
    };

    /** Shows the bar and starts it fading again. Any tap on the screen does this. */
    private void revealQuickBar() {
        barWanted = true;

        quickBar.removeCallbacks(fadeQuickBar);
        quickBar.animate().cancel();
        quickBar.setAlpha(1f);
        quickBar.setVisibility(View.VISIBLE);

        if (!barPinned) {
            quickBar.postDelayed(fadeQuickBar, BAR_LINGER_MS);
        }
    }

    /**
     * Keeps the bar up for as long as there is no machine, and strips it to ☰.
     *
     * Up, because the ROMs panel covers the screen, so a tap lands on the panel
     * rather than on the thing that reveals the bar. Stripped, because with no
     * machine there is nothing for the actions to act on — no state to save,
     * nothing to pause, no picture to photograph.
     *
     * ☰ stays, and only ☰: the data folder is behind it, and a data folder
     * pointing somewhere with no ROMs in it is the likeliest reason for being
     * on this panel at all. Taking it away would stand the user in the one room
     * with no doors.
     */
    private void pinMenuButton(boolean pinned) {
        barPinned = pinned;
        quickBar.showOnly(pinned ? menuAction : null);
        revealQuickBar();
    }

    /**
     * The ☰ sheet.
     *
     * Six rows, not a dozen: everything at one level came to more than a
     * landscape window is tall, and a menu you have to scroll to read is a
     * menu that has stopped helping. What is left at the top is the one thing
     * done constantly — opening something — and five doors.
     *
     * The pages are functions rather than lists because most of them depend on
     * what is happening: which drives this machine has, whether something is
     * recording, which joystick is plugged in. They are called each time the
     * page is shown.
     *
     * Choosing one of a set stays an {@link AlertDialog} — a machine, a
     * joystick type, a layout — because a checked radio in a list is what says
     * "one of these, and this is the one", and a sheet of plain rows cannot.
     * So does anything that needs confirming.
     */
    private MenuDrawer buildMenu() {
        MenuDrawer menu = new MenuDrawer(this);

        menu.setRoot(sheet -> {
            sheet.addItem(getString(R.string.menu_open), R.drawable.ic_folder,
                          this::pickFile);
            sheet.addSubmenu(getString(R.string.menu_states), R.drawable.ic_bookmark,
                             this::fillStates);
            sheet.addSubmenu(getString(R.string.menu_media), R.drawable.ic_tape,
                             this::fillMedia);
            sheet.addSubmenu(getString(R.string.menu_capture), R.drawable.ic_camera,
                             this::fillCapture);

            sheet.addRule();
            sheet.addSubmenu(getString(R.string.menu_machine_group),
                             R.drawable.ic_machine, this::fillMachine);
            sheet.addSubmenu(getString(R.string.menu_controls),
                             R.drawable.ic_controls, this::fillControls);
            sheet.addItem(getString(R.string.menu_settings), R.drawable.ic_settings,
                    () -> startActivity(new Intent(this, SettingsActivity.class)));
        });

        return menu;
    }

    /**
     * The two things you play with. They are the same kind of thing — a set of
     * keys drawn on the glass — and each is a page because each can be put
     * away, and the keyboard also decides how the window is divided.
     */
    private void fillControls(MenuDrawer sheet) {
        sheet.addSubmenu(getString(R.string.menu_joystick), R.drawable.ic_joystick,
                         this::fillJoystick);
        sheet.addSubmenu(getString(R.string.menu_keyboard), R.drawable.ic_keyboard,
                         this::fillKeyboard);
    }

    private void fillKeyboard(MenuDrawer sheet) {
        boolean shown = layout.keyboardVisible();

        sheet.addItem(getString(shown ? R.string.control_hide
                                      : R.string.control_show),
                      shown ? R.drawable.ic_hide : R.drawable.ic_show,
                      () -> showKeyboard(!shown));
        sheet.addItem(getString(R.string.menu_layout), R.drawable.ic_layout,
                      this::showLayoutDialog);
    }

    private void showKeyboard(boolean shown) {
        layout.setKeyboardVisible(shown);
        preferences.edit().putBoolean(SettingsActivity.KEY_KEYBOARD, shown).apply();

        note(shown ? R.string.keyboard_shown : R.string.keyboard_hidden);
    }

    private void fillStates(MenuDrawer sheet) {
        sheet.addItem(getString(R.string.menu_save_state), R.drawable.ic_save,
                      () -> showStateDialog(true));
        sheet.addItem(getString(R.string.menu_load_state), R.drawable.ic_load,
                      () -> showStateDialog(false));
    }

    private void fillMachine(MenuDrawer sheet) {
        sheet.addItem(getString(R.string.menu_machine), R.drawable.ic_swap,
                      this::showMachineDialog);

        sheet.addRule();
        sheet.addItem(getString(pausedByUser ? R.string.pause_resume
                                             : R.string.pause_pause),
                      pausedByUser ? R.drawable.ic_play : R.drawable.ic_pause,
                      () -> pause(!pausedByUser));
        sheet.addItem(getString(R.string.menu_reset), R.drawable.ic_reset,
                      this::confirmReset);
        sheet.addItem(getString(R.string.menu_nmi), R.drawable.ic_bolt, () -> {
            FuseNative.nmi();
            note(R.string.nmi_done);
        });
    }

    // --- pause ---------------------------------------------------------------

    /**
     * The one control that is not in a menu or on the bar.
     *
     * A paused emulator looks exactly like a stopped one — the last frame is
     * still on the screen, because that is what a paused emulator has to keep
     * presenting — so it says so in the middle of the picture, and the thing
     * that says so is also the way out. Nothing else is needed: whatever else
     * you might want, unpausing comes first.
     */
    private ImageButton buildPlayButton() {
        ImageButton button = new ImageButton(this);
        android.graphics.drawable.GradientDrawable backing =
                new android.graphics.drawable.GradientDrawable();

        backing.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        backing.setColor(0xb3000000);

        button.setImageResource(R.drawable.ic_play);
        button.setColorFilter(Color.WHITE);
        button.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        button.setBackground(backing);
        button.setContentDescription(getString(R.string.pause_resume));
        button.setVisibility(View.GONE);
        button.setOnClickListener(v -> pause(false));

        return button;
    }

    private boolean isPaused() {
        return pausedByUser || pausedByAndroid;
    }

    /** The user's pause, from the menu or the bar. */
    private void pause(boolean paused) {
        pausedByUser = paused;
        applyPause();

        if (!paused) revealQuickBar();
    }

    /**
     * Tells the emulator, and shows or hides the way back. Called for either
     * reason, and what it sends is whether *anything* wants a pause.
     */
    private void applyPause() {
        boolean paused = isPaused();

        FuseNative.setPaused(paused);

        playButton.setVisibility(paused ? View.VISIBLE : View.GONE);

        if (pauseAction != null) {
            quickBar.setAction(pauseAction,
                    paused ? R.drawable.ic_play : R.drawable.ic_pause,
                    getString(paused ? R.string.pause_resume
                                     : R.string.pause_pause));
        }
    }

    // --- joystick -----------------------------------------------------------

    /**
     * Whether the pad is on screen, and which interface it comes out as.
     *
     * The two are kept apart on purpose: hiding the pad does not unplug the
     * joystick, since the interface is what a game reads and a physical
     * gamepad may want it later. The types are Fuse's own list, in Fuse's own
     * order, so the index is the value it takes.
     */
    private void fillJoystick(MenuDrawer sheet) {
        boolean shown = layout.joystickVisible();

        sheet.addItem(getString(shown ? R.string.control_hide
                                      : R.string.control_show),
                      shown ? R.drawable.ic_hide : R.drawable.ic_show,
                      () -> showJoystick(!shown));
        sheet.addItem(getString(R.string.joystick_type, joystickTypeName()),
                      R.drawable.ic_swap, this::showJoystickTypeDialog);
    }

    private void showJoystick(boolean shown) {
        layout.setJoystickVisible(shown);
        preferences.edit().putBoolean(SettingsActivity.KEY_JOYSTICK, shown).apply();

        note(shown ? R.string.joystick_shown : R.string.joystick_hidden);
    }

    private void showJoystickTypeDialog() {
        String[] names = FuseNative.joystickTypeNames();
        if (names.length == 0) return;

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.joystick_type_title)
                .setSingleChoiceItems(names, joystickType(), (dialog, which) -> {
                    preferences.edit()
                            .putInt(SettingsActivity.KEY_JOYSTICK_TYPE, which)
                            .apply();
                    FuseNative.setJoystickType(which);

                    dialog.dismiss();
                    note(R.string.joystick_type_set, names[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The stored type, or Kempston; never an index Fuse would not recognise. */
    private int joystickType() {
        int stored = preferences.getInt(SettingsActivity.KEY_JOYSTICK_TYPE,
                                        DEFAULT_JOYSTICK_TYPE);
        int count = FuseNative.joystickTypeNames().length;

        return stored >= 0 && (count == 0 || stored < count)
                ? stored : DEFAULT_JOYSTICK_TYPE;
    }

    private String joystickTypeName() {
        String[] names = FuseNative.joystickTypeNames();
        int type = joystickType();

        return type < names.length ? names[type] : "";
    }

    // --- tapes ------------------------------------------------------------

    /**
     * What is in the machine: its tape, and every drive it has.
     *
     * The two used to be separate menus, which put a tape and a disk at
     * different depths for no reason a user would recognise — they are the
     * same question. The machine can write to its tape as well as read from
     * it, since Fuse's tape traps catch the ROM's save routine and a BASIC
     * {@code SAVE "name"} appends to the tape held in memory; that is what
     * <em>Save tape…</em> writes out.
     */
    private void fillMedia(MenuDrawer sheet) {
        sheet.addItem(getString(R.string.tape_save), R.drawable.ic_save, this::saveTape);
        sheet.addItem(getString(R.string.tape_new), R.drawable.ic_plus,
                      this::confirmNewTape);

        sheet.addRule();
        sheet.addSection(getString(R.string.menu_disks_section));

        // The drives follow the machine rather than being a fixed A: to D:,
        // so they are asked for every time this page is shown.
        String[] details = FuseNative.driveDetails();
        int[] ids = FuseNative.driveIds();
        int count = Math.min(ids.length, details.length / 3);

        if (count == 0) {
            sheet.addNote(getString(R.string.disk_no_drives));
            return;
        }

        for (int i = 0; i < count; i++) {
            String name = details[i * 3];
            String disk = details[i * 3 + 1];
            boolean modified = "1".equals(details[i * 3 + 2]);
            int id = ids[i];

            String state = disk.isEmpty() ? getString(R.string.disk_empty)
                    : modified ? getString(R.string.disk_modified, disk) : disk;

            sheet.addSubmenu(name + "\n" + state, R.drawable.ic_disk,
                             page -> fillDrive(page, name, id, !disk.isEmpty()));
        }
    }

    private void fillDrive(MenuDrawer sheet, String name, int id, boolean loaded) {
        sheet.addItem(getString(R.string.disk_load), R.drawable.ic_folder,
                      () -> loadDiskInto(id));
        sheet.addItem(getString(R.string.disk_new), R.drawable.ic_plus,
                      () -> confirmNewDisk(name, id, loaded));

        if (loaded) {
            sheet.addItem(getString(R.string.disk_save_short), R.drawable.ic_save,
                          () -> saveDisk(name, id));
            sheet.addItem(getString(R.string.disk_eject), R.drawable.ic_eject,
                          () -> confirmEject(name, id));
        }
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
    private void fillCapture(MenuDrawer sheet) {
        sheet.addItem(getString(R.string.capture_screenshot), R.drawable.ic_camera,
                      this::takeScreenshot);

        // Built when the page opens, so it offers the one thing that makes
        // sense: there is nothing to stop until something is running.
        if (Recorder.isRecording()) {
            sheet.addItem(getString(R.string.capture_stop), R.drawable.ic_stop,
                          Recorder::stop);
        } else {
            sheet.addItem(getString(R.string.capture_gif), R.drawable.ic_record,
                          () -> startRecording(Recorder.Format.GIF));
            sheet.addItem(getString(R.string.capture_mp4), R.drawable.ic_record,
                          () -> startRecording(Recorder.Format.MP4));
        }

        sheet.addRule();
        sheet.addItem(getString(R.string.capture_open_folder), R.drawable.ic_folder,
                      this::openRecordingsFolder);
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

        // The ROM pickers are the panel's own; it says whether it took this.
        if (roms.onActivityResult(request, result, data)) return;

        if (result != RESULT_OK || data == null) return;

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
        File staged = new File(dir, Storage.displayName(this, uri));

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

    /**
     * Nothing to look at, nothing to run: a Spectrum in the background is a
     * Spectrum burning the battery. The automatic pause is kept apart from the
     * user's so that coming back does not undo one they asked for.
     */
    @Override
    protected void onPause() {
        super.onPause();
        rememberMachine();

        pausedByAndroid = true;
        applyPause();
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
            roms.show(false);
            return;
        }

        roms.hide();
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
            roms.show(true);
            return;
        }

        getWindow().getDecorView().postDelayed(
                () -> watchForStartFailure(waited + START_POLL_MS), START_POLL_MS);
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

        // Three of Fuse's settings in three combinations; see
        // OPTION_LOADER_ACCELERATION in android_bridge.c for why these three.
        int loader = SettingsActivity.loaderLevel(preferences);

        arguments.add(loader > 0 ? "--traps" : "--no-traps");
        arguments.add(loader > 0 ? "--fastload" : "--no-fastload");
        arguments.add(loader > 1 ? "--accelerate-loader"
                                 : "--no-accelerate-loader");

        flag(arguments, SettingsActivity.KEY_DETECT_LOADER, true, "detect-loader");

        flag(arguments, SettingsActivity.KEY_TAPE_SOUND, true, "loading-sound");
        flag(arguments, SettingsActivity.KEY_AUTOLOAD, true, "auto-load");
        flag(arguments, SettingsActivity.KEY_ISSUE2, false, "issue2");
        flag(arguments, SettingsActivity.KEY_BW_TV, false, "bw-tv");
        flag(arguments, SettingsActivity.KEY_SOUND, true, "sound");

        value(arguments, SettingsActivity.KEY_SPEED, 100, "speed");
        value(arguments, SettingsActivity.KEY_AY_VOLUME, 100, "volume-ay");
        value(arguments, SettingsActivity.KEY_BEEPER_VOLUME, 100, "volume-beeper");

        // The on-screen joystick is Fuse's joystick 1. Kempston is a type and
        // also a piece of hardware, and the port is only decoded when the
        // interface is there, so the two go together - see OPTION_JOYSTICK_TYPE
        // in android_bridge.c, which does the same when it is changed later.
        int joystick = joystickType();
        arguments.add("--joystick-1-output");
        arguments.add(String.valueOf(joystick));
        arguments.add(joystick == JOYSTICK_KEMPSTON ? "--kempston"
                                                    : "--no-kempston");

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

    /**
     * A key the machine can use is the machine's; everything else is the
     * phone's.
     *
     * Returning true from onKeyDown consumes the event, and this used to do it
     * for every key there was — so the volume buttons did nothing while the app
     * was in front, and neither did the media keys: the event was swallowed on
     * the way to Fuse, which then had no mapping for it and ignored it. Fuse's
     * own keysym table is the authority on what it can use, so ask that rather
     * than keep a second list here of what to let past.
     */
    private boolean forwardKey(int keyCode, boolean pressed) {
        if (!FuseNative.mapsKey(keyCode)) return false;

        FuseNative.key(keyCode, pressed);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // With the sheet open, back belongs to the sheet: up one page,
            // and out of the sheet altogether from the top of it.
            if (menu.back()) return true;

            return super.onKeyDown(keyCode, event);
        }

        return forwardKey(keyCode, true) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event);

        return forwardKey(keyCode, false) || super.onKeyUp(keyCode, event);
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
