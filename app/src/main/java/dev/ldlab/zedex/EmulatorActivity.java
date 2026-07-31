package dev.ldlab.zedex;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Where Fuse's own data files go, and how it is told to find them.
     *
     * {@code getFilesDir()/fuse/ui/widget}, because that is the second place
     * Fuse looks for them: {@code compat_get_next_path()} tries the working
     * directory, then a directory beside the program named in argv[0] - lib,
     * roms or ui/widget, by what kind of file is wanted - and only then the
     * FUSEDATADIR baked in at compile time. Everything the app ships is read as
     * a widget file, fuse.font included, so ui/widget is where they go.
     *
     * Naming argv[0] as a path inside our own files is what makes the second
     * one land here, and that is what frees the app from the third: FUSEDATADIR
     * is an absolute path with the package name in it, and could only ever be
     * right for one build of the app.
     */
    private static final String DATA_DIR = "fuse";
    private static final String LIB_DIR = DATA_DIR + "/ui/widget";

    /** argv[0]: never run, only read for the directory it names. */
    private static final String PROGRAM = DATA_DIR + "/fuse";

    private static final String PREFS = SettingsActivity.PREFS;

    /** Fuse's short id for the machine to boot, e.g. "48" or "128". */
    private static final String PREF_MACHINE = SettingsActivity.KEY_MACHINE;
    private static final String DEFAULT_MACHINE = "128";

    /** How long to give the emulation thread to act on a machine change. */
    private static final long MACHINE_SETTLE_MS = 500;

    /**
     * How long Fuse gets to publish a machine before its start is called
     * failed. Generous: this only runs while the screen is black anyway.
     */
    private static final long START_TIMEOUT_MS = 6000;
    private static final long START_POLL_MS = 500;


    private SharedPreferences preferences;
    private JoystickView[] keyButtons = new JoystickView[0];

    /**
     * Whether the system keyboard has been up since this activity was created.
     * Until it has, a report of "not visible" says nothing.
     */
    private boolean imeSeen;

    /** A physical controller, when there is one; harmless when there is not. */
    private final Gamepad gamepad = new Gamepad(this::runHotkey);
    private boolean started;

    /** Holds the screen and the keyboard, and decides how they share the window. */
    private EmulatorLayout layout;

    /** The ☰ sheet. Built once and slid in and out. */
    private MenuDrawer menu;

    /** The ☰ button itself, which fades out when it is not being used. */
    private QuickBar quickBar;

    /**
     * The window on the other panel, while there is one; null the rest of the
     * time, which is also how everything else asks whether the controls are
     * here or over there. See {@link #applySecondScreen}.
     */
    private SecondScreen secondScreen;

    /** The big play button over the picture, shown only while paused. */
    private ImageButton playButton;

    /** The bar's fullscreen icon, which becomes its own way out. */
    private ImageButton fullscreenAction;

    /**
     * Two reasons to be stopped, kept apart. The user's pause survives going
     * away and coming back; the automatic one does not, and must not undo the
     * user's when it lifts.
     */
    private boolean pausedByUser;
    private boolean pausedByAndroid;

    /** Asks for ROMs, and fetches them; shown when there is no machine. */
    private StartPanel roms;

    /**
     * The tape deck, the drives and the card slot; see {@link Media}. It owns
     * the staging, the picker and the write-back, and asks this activity only
     * for somewhere to put a message, the sheet, and the fact of a load.
     *
     * Built in {@link #onCreate} and not here: a field initialiser runs before
     * onCreate does, so it would be handed a null preferences.
     */
    private Media media;

    /**
     * The cheats, shipped and typed; see {@link PokesUi}. Built in onCreate
     * beside Media and for the same reason.
     */
    private PokesUi pokes;

    /**
     * The joystick, the keyboard and the mouse; see {@link ControlsUi}. It is
     * handed the layout, because whether a control is on screen is the layout's
     * own state and going through a host method for each would be an interface
     * as long as the class.
     *
     * Built once the layout exists, which is later than Media and the cheats.
     */
    private ControlsUi controls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        media = new Media(this, preferences, new Media.Host() {
            @Override
            public void note(int message, Object... arguments) {
                EmulatorActivity.this.note(message, arguments);
            }

            @Override
            public MenuDrawer sheet() {
                return menu;
            }

            @Override
            public void opened(String name) {
                rememberMediaName(name);
            }
        });

        pokes = new PokesUi(this, preferences, new PokesUi.Host() {
            @Override
            public void note(int message, Object... arguments) {
                EmulatorActivity.this.note(message, arguments);
            }

            @Override
            public MenuDrawer sheet() {
                return menu;
            }

            @Override
            public byte[] fingerprint() {
                return media.hash();
            }
        });

        // Before anything reads the filter, which is now one setting where it
        // used to be two booleans.
        Filter.migrate(preferences);

        getApplication().registerActivityLifecycleCallbacks(screensOfOurs);
        FuseNative.attach(this);
        Storage.createFolders(this);

        // The ROMs the app ships, before anything asks whether there are any -
        // except on the very first run, where the folder to put them in is the
        // question the panel is about to ask. Only ever the ones that are not
        // there already: the folder is the user's to fill as well as ours.
        if (!StartPanel.setupNeeded(this)) Storage.installRoms(this);

        File files = getFilesDir();
        try {
            installAssets(DATA_DIR, new File(files, LIB_DIR));
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
        roms = new StartPanel(this, romsHost);
        menu = buildMenu();
        quickBar = buildQuickBar();
        playButton = buildPlayButton();

        // A tap anywhere on the picture brings ☰ back; the sheet closing takes
        // it away again, so it is only ever over the screen while in use.
        screen.setOnClickListener(v -> revealQuickBar());

        // And a drag across it is the Kempston mouse, while that mode is on. A
        // drag rather than a tap on purpose: a tap has a job already, and the
        // click listener above still gets it, because a touch that never moves
        // beyond the slop is not a drag and is not consumed here.
        screen.setOnTouchListener(new View.OnTouchListener() {
            private final float slop = MOUSE_SLOP
                    * getResources().getDisplayMetrics().density;
            private float lastX, lastY;
            private boolean dragging;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (!Mouse.enabled()) return false;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getX();
                        lastY = event.getY();
                        dragging = false;
                        return false;

                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;

                        if (!dragging
                                && Math.hypot(dx, dy) < slop) {
                            return false;
                        }

                        dragging = true;
                        lastX = event.getX();
                        lastY = event.getY();
                        Mouse.drag(dx, dy);
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // Consumed only if it was a drag, so a tap still reveals
                        // the bar through the click listener.
                        return dragging;

                    default:
                        return false;
                }
            }
        });
        // Closing the sheet takes the bar with it only where the bar fades at
        // all; otherwise it has a place of its own and stays in it.
        menu.setOnClosed(() -> {
            if (fullscreen()) fadeQuickBar.run();
        });

        keyButtons = new JoystickView[] {
            new JoystickView(this, ControlProfiles.BUTTON_1),
            new JoystickView(this, ControlProfiles.BUTTON_2),
            new JoystickView(this, ControlProfiles.BUTTON_3),
        };

        ActivityLights lights = new ActivityLights(this);
        lights.setWants(this::mouseWanted);

        layout = new EmulatorLayout(this);
        layout.setChildren(screen, new SpectrumKeyboardView(this),
                           new SystemKeyboardView(this),
                           new JoystickView(this, JoystickView.Part.PAD),
                           new JoystickView(this, JoystickView.Part.FIRE),
                           keyButtons,
                           lights, playButton,
                           roms.view(), quickBar, menu);
        layout.setJoystickVisible(
                preferences.getBoolean(SettingsActivity.KEY_JOYSTICK, true));
        layout.setKeyboardVisible(
                preferences.getBoolean(SettingsActivity.KEY_KEYBOARD, true));
        layout.setLightsVisible(
                preferences.getBoolean(SettingsActivity.KEY_INDICATORS, true));

        // Here rather than beside Media and the cheats: it is handed the layout,
        // so it cannot exist until there is one.
        controls = new ControlsUi(this, preferences, layout, gamepad,
                                  new ControlsUi.Host() {
            @Override
            public void note(int message, Object... arguments) {
                EmulatorActivity.this.note(message, arguments);
            }

            @Override
            public MenuDrawer sheet() {
                return menu;
            }

            @Override
            public boolean fullscreen() {
                return EmulatorActivity.this.fullscreen();
            }

            @Override
            public boolean onSecondScreen() {
                return secondScreen != null;
            }
        });

        applyScale();
        controls.applyControls();
        applyFullscreen();

        revealQuickBar();

        setContentView(layout);

        layout.setFocusableInTouchMode(true);
        layout.requestFocus();

        getWindow().setDecorFitsSystemWindows(false);

        /*
         * The system keyboard's own comings and goings.
         *
         * Two things depend on them. The picture moves out of its way - see
         * EmulatorLayout's NONE branch - and the menu has to agree with it: the
         * keyboard can be dismissed from the keyboard, with its own key or a
         * back gesture, and until this listener existed the app went on offering
         * to hide something that had already gone.
         *
         * One way only. Asking the keyboard to appear is done elsewhere; this
         * merely records what happened, so that noticing it cannot turn into
         * asking for it again.
         *
         * And only once the keyboard has actually been seen. The insets arrive
         * before it does, so at startup the first thing this heard was "not
         * visible" - which it dutifully recorded, whereupon the app decided the
         * keyboard was not wanted and closed the one it had just asked for. A
         * dismissal can only follow an appearance.
         */
        layout.setOnApplyWindowInsetsListener((ignored, insets) -> {
            boolean visible = insets.isVisible(WindowInsets.Type.ime());

            layout.setInsets(
                    visible ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0,
                    insets.getInsets(WindowInsets.Type.mandatorySystemGestures())
                          .bottom);

            if (visible) imeSeen = true;

            if (imeSeen && controls.keyboardSkin() == SpectrumKeyboardView.Skin.SYSTEM
                    && layout.keyboardVisible() != visible) {
                preferences.edit()
                        .putBoolean(SettingsActivity.KEY_KEYBOARD, visible).apply();
                layout.setKeyboardVisible(visible);
            }

            return insets;
        });

        // Posted: there is no window to show an input method for until the
        // activity has one.
        layout.post(controls::applySystemKeyboard);

        handleViewIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    /**
     * Tells the layout how big the picture will be drawn. The renderer is told
     * separately, by the settings screen and at startup; this is the other half,
     * so that the lamps and the joystick sit against the picture's real edge
     * rather than where a fitted one would have been.
     */
    private void applyScale() {
        // The border first: it decides how many emulated pixels there are, which
        // is what a whole-pixel scale is a multiple of, and the layout has to
        // agree with the renderer about that or the joystick sits on the picture.
        Border border = Border.of(preferences);

        layout.setBorder(border);
        SettingsActivity.applyBorder(preferences);

        layout.setScale(SettingsActivity.scale(preferences, false),
                        SettingsActivity.scale(preferences, true));
        SettingsActivity.applyScale(this, preferences);
    }

    /**
     * The scale is per orientation and the renderer is told only the one that
     * applies, so turning the device has to say so again. The window is handled
     * here rather than being recreated - see the manifest's configChanges - so
     * this is the only notice of a rotation there is.
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        applyScale();

        // Fullscreen hides the keyboard in landscape and not in portrait, so
        // turning the device is a change of that answer.
        applyFullscreen();
    }

    /** Opens media handed to us by a file manager, or by `am start -a VIEW`. */
    private void handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;

        Uri uri = intent.getData();
        if (uri == null) return;

        // Safe before Fuse has started: the command simply waits in the queue
        // until the emulation thread drains it.
        new Thread(() -> media.stageAndOpen(uri)).start();
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
        layout.setLightsVisible(
                preferences.getBoolean(SettingsActivity.KEY_INDICATORS, true));
        applyScale();
        controls.applyControls();
        controls.applyMouse();

        // Connecting or disconnecting one while the app was away.
        InputManager input = getSystemService(InputManager.class);
        if (input != null) input.registerInputDeviceListener(devices, null);
        controls.applyGamepad();

        // The same for a second panel, and for the setting that wants one.
        DisplayManager displays = getSystemService(DisplayManager.class);
        if (displays != null) displays.registerDisplayListener(screens, null);
        applySecondScreen();

        pausedByAndroid = false;
        applyPause();

        // Coming back from somewhere else: if the device's keyboard is the one
        // chosen, it went away with the app and should come back with it.
        controls.applySystemKeyboard();
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

            // An input method is only shown for a window that has the focus, so
            // this is where asking for one belongs: at startup the request beat
            // the window to it and was quietly dropped.
            controls.applySystemKeyboard();
        }
    }

    /**
     * The whole of what {@link StartPanel} needs from here: try again once ROMs
     * have arrived, and keep the quick bar up while the panel covers the screen.
     */
    private final StartPanel.Host romsHost = new StartPanel.Host() {
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
            hideBarForPanel(covering);
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

        // Six groups where there were four. Files used to carry the save
        // states as well as the files, and Display used to carry the three
        // show/hide toggles as well as the picture - each of them two lists
        // that happened to share an icon, so the icon could not say what was
        // behind it and the list had to be read to the end.
        bar.addGroup(R.drawable.ic_folder, getString(R.string.menu_files),
                     this::fillFiles);
        bar.addGroup(R.drawable.ic_bookmark, getString(R.string.menu_states),
                     this::fillStates);
        // The machine here as in the sheet, since it holds pause and the two
        // menus reading the same way is worth more than either order is.
        bar.addGroup(R.drawable.ic_chip, getString(R.string.menu_machine_group),
                     this::fillMachine);
        bar.addGroup(R.drawable.ic_camera, getString(R.string.menu_capture),
                     this::fillCapture);
        bar.addGroup(R.drawable.ic_controls, getString(R.string.menu_on_screen),
                     this::fillOnScreen);
        bar.addGroup(R.drawable.ic_display, getString(R.string.menu_display),
                     this::fillDisplay);

        bar.addHold(R.drawable.ic_fast_forward, getString(R.string.fast_forward),
                    () -> fastForward(true), () -> fastForward(false));

        fullscreenAction = bar.addAction(R.drawable.ic_fullscreen,
                                         getString(R.string.fullscreen_enter),
                                         () -> showFullscreen(!fullscreen()));

        bar.addAction(R.drawable.ic_menu, getString(R.string.menu_button),
                      () -> menu.open());

        return bar;
    }

    /**
     * Opening something: the picker, and then what was opened before.
     *
     * The save states used to be in here as well, which made the folder icon
     * mean "files and states" - two lists sharing one picture, so the picture
     * said neither and the list had to be read to the end to find out which
     * half you were in. They have a group of their own now.
     *
     * The names go last and under a line, because the picker above them is what
     * the group is for and ten filenames in front of it would be ten things to
     * read past every time.
     */
    private void fillFiles(Rows rows) {
        rows.item(R.drawable.ic_folder, getString(R.string.menu_open),
                  media::pick);

        List<Recents.Item> recent = Recents.all(preferences);
        if (recent.isEmpty()) return;

        rows.rule();
        fillRecent(rows);
    }

    /**
     * The last ten files, newest first.
     *
     * Coming back to yesterday's game is the commonest thing anyone does with
     * an emulator, and through the picker it costs three taps and remembering
     * what the file was called.
     */
    private void fillRecent(Rows rows) {
        for (Recents.Item item : Recents.all(preferences)) {
            rows.item(R.drawable.ic_file, item.name, () -> openRecent(item));
        }
    }

    /**
     * Opens one again. The grant may have gone - a document can be moved,
     * deleted or handed over for one launch only - and then it is dropped from
     * the list rather than sitting there failing.
     */
    private void openRecent(Recents.Item item) {
        new Thread(() -> {
            File staged = media.stage(item.uri);

            if (staged == null) {
                Recents.forget(getContentResolver(), preferences, item.uri);
                return;
            }

            FuseNative.openFile(staged.getAbsolutePath());
            note(R.string.file_opened, staged.getName());

            String name = staged.getName();
            int dot = name.lastIndexOf('.');
            rememberMediaName(Storage.withoutExtension(name));
        }).start();
    }

    /**
     * What is running: stop it, swap it, start it over, interrupt it.
     *
     * Built when the page is opened, so pause says which way round it is
     * without anything having to keep it up to date. It reads
     * {@link #pausedByUser} and not {@link #isPaused}: the row toggles the
     * user's pause, so it has to say what the user's pause is - Android's own,
     * which is the other half of isPaused, is never on while a menu is being
     * looked at anyway.
     */
    private void fillMachine(Rows rows) {
        rows.item(pausedByUser ? R.drawable.ic_play : R.drawable.ic_pause,
                  getString(pausedByUser ? R.string.pause_resume
                                         : R.string.pause_pause),
                  () -> pause(!pausedByUser));

        rows.rule();
        rows.item(R.drawable.ic_swap, withMachine(R.string.menu_machine),
                  this::showMachineDialog);
        // Reset asks first, and asking is a sheet page - so the bar's row opens
        // the sheet on it rather than the two surfaces doing it differently.
        rows.item(R.drawable.ic_reset, getString(R.string.menu_reset),
                  () -> menu.go(getString(R.string.menu_reset), resetMachine()));
        // No confirming, unlike reset: the magic button interrupts the machine
        // rather than throwing its state away, and half of what it is for is
        // pressing it at a particular moment.
        rows.item(R.drawable.ic_bolt, getString(R.string.menu_nmi), this::nmi);
    }

    /** The magic button of the real hardware; what it does is the machine's. */
    private void nmi() {
        FuseNative.nmi();
        note(R.string.nmi_done);
    }


    /**
     * What is on the glass beside the picture, and whether it is there.
     *
     * Named for what they would do rather than for what they are, since an icon
     * that means "joystick" cannot also say which way it is about to go.
     *
     * The lamps are here rather than only in the settings because whether they
     * are worth their strip is a decision of the moment - watching a tape load
     * wants them, playing the game afterwards does not - and it is the same kind
     * of decision as the other two. Both places write the same preference.
     */
    private void fillOnScreen(Rows rows) {
        boolean pad = layout.joystickVisible();
        boolean keys = layout.keyboardVisible();

        rows.item(R.drawable.ic_joystick,
                  getString(pad ? R.string.quick_joystick_hide
                                : R.string.quick_joystick_show),
                  () -> controls.showJoystick(!pad));

        // Not the keyboard while fullscreen: it is away whatever this says, so
        // a row offering to hide it does nothing and a row offering to show it
        // is a promise the layout will not keep. The joystick stays, because
        // fullscreen leaves that where it is.
        if (fullscreen()) return;

        rows.item(R.drawable.ic_keyboard,
                  getString(keys ? R.string.quick_keyboard_hide
                                 : R.string.quick_keyboard_show),
                  () -> controls.showKeyboard(!keys));
    }

    /**
     * What the picture itself looks like.
     *
     * It shared a group with the three toggles above, under the display icon,
     * which made that icon mean "the screen furniture and also the screen" -
     * seven rows to read for one of them. Two switches, named for what they
     * would do, and two choosers, named for what is chosen: an icon cannot say
     * which way it is going, but a chooser can say where it is.
     *
     * Scanlines and CRT are two rows here and one choice of four in the
     * settings, because turning scanlines off to read something is a decision
     * of the moment and should not cost a trip through a list. {@link Filter}
     * is what keeps them from treading on each other: each row changes its own
     * half and leaves the other alone.
     *
     * The lamps are here rather than with the joystick and the keyboard. They
     * are not something you play with - they are drawn beside the picture and
     * they are read - so they belong with what the picture looks like. Under a
     * line, since the four above are the picture itself.
     */
    private void fillDisplay(Rows rows) {
        Filter filter = Filter.of(preferences);

        rows.item(R.drawable.ic_scanlines,
                  getString(filter.scanlines ? R.string.quick_scanlines_off
                                             : R.string.quick_scanlines_on),
                  () -> switchFilter(filter.withScanlines(!filter.scanlines),
                                     filter.scanlines
                                             ? R.string.quick_scanlines_off
                                             : R.string.quick_scanlines_on));
        rows.item(R.drawable.ic_crt,
                  getString(filter.crt ? R.string.quick_crt_off
                                       : R.string.quick_crt_on),
                  () -> switchFilter(filter.withCrt(!filter.crt),
                                     filter.crt ? R.string.quick_crt_off
                                                : R.string.quick_crt_on));
        rows.item(R.drawable.ic_signal,
                  getString(R.string.quick_video, videoName()),
                  this::nextVideo);
        rows.item(R.drawable.ic_border,
                  getString(R.string.quick_border,
                            getString(Border.of(preferences).title)),
                  this::nextBorder);

        // Away in fullscreen whatever this says, like the keyboard, so it is
        // not offered there.
        if (fullscreen()) return;

        boolean lamps = layout.lightsVisible();

        rows.rule();
        rows.item(R.drawable.ic_indicators,
                  getString(lamps ? R.string.quick_lights_hide
                                  : R.string.quick_lights_show),
                  () -> showLights(!lamps));
    }

    /** Either of the picture switches, written and pushed like the settings do. */
    private void switchFilter(Filter filter, int said) {
        filter.store(preferences);
        SettingsActivity.applyFilter(preferences);

        note(said);
    }

    /** The signal, in the one word a row has room for. */
    private String videoName() {
        String[] names = getResources().getStringArray(R.array.video_short_names);
        int now = SettingsActivity.SettingsFragment.number(
                preferences, SettingsActivity.KEY_VIDEO, 0);

        return names[ Math.max(0, Math.min(names.length - 1, now)) ];
    }

    /**
     * Round the three signals, and round the three borders.
     *
     * Stepping rather than offering a list: there are three of each, the row
     * says which one is showing, and a chooser is what the settings screen is
     * for. Both write the same preference the settings do, so the two agree.
     */
    private void nextVideo() {
        int count = getResources().getStringArray(R.array.video_short_names).length;
        int next = ( SettingsActivity.SettingsFragment.number(
                preferences, SettingsActivity.KEY_VIDEO, 0) + 1 ) % count;

        preferences.edit()
                .putString(SettingsActivity.KEY_VIDEO, String.valueOf(next))
                .apply();
        SettingsActivity.applyFilter(preferences);

        note(R.string.quick_video, videoName());
    }

    private void nextBorder() {
        Border next = Border.of(preferences).next();

        preferences.edit()
                .putString(SettingsActivity.KEY_BORDER, next.value)
                .apply();
        applyScale();

        note(R.string.quick_border, getString(next.title));
    }

    private void showLights(boolean shown) {
        layout.setLightsVisible(shown);
        preferences.edit().putBoolean(SettingsActivity.KEY_INDICATORS, shown).apply();

        note(shown ? R.string.lights_shown : R.string.lights_hidden);
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

    /**
     * Set while the ROMs panel is covering everything: the bar is gone and
     * nothing brings it back. See {@link #hideBarForPanel}.
     */
    private boolean panelUp;

    /**
     * Whether the bar is meant to be up. The fade's end action has to ask,
     * because cancelling a ViewPropertyAnimator still runs it \u2014 so a reveal
     * arriving mid-fade would otherwise be undone a moment after it happened,
     * which is exactly what it did.
     */
    private boolean barWanted;

    private final Runnable fadeQuickBar = () -> {
        // On a second screen the bar has a panel of its own and is never over
        // the picture, so there is nothing to fade out of the way of.
        if (secondScreen != null) return;

        barWanted = false;
        // A group left open would be waiting there on the way back, which is
        // not where the bar was left off.
        quickBar.collapse();
        quickBar.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (!barWanted) quickBar.setVisibility(View.GONE);
        });
    };

    /**
     * Shows the bar, and starts it fading only if it is meant to fade.
     *
     * It fades in fullscreen and nowhere else. Everywhere else it has a strip of
     * its own across the top of the window, whichever way up the device is, so
     * there is nothing to be gained by taking it away and something to be lost:
     * a control that is always there needs no discovering. Any tap on the
     * picture still calls this, which in fullscreen is how the bar is got back.
     */
    private void revealQuickBar() {
        if (panelUp) return;

        barWanted = true;

        quickBar.removeCallbacks(fadeQuickBar);
        quickBar.animate().cancel();
        quickBar.setAlpha(1f);
        quickBar.setVisibility(View.VISIBLE);

        if (fullscreen() && secondScreen == null) {
            quickBar.postDelayed(fadeQuickBar, BAR_LINGER_MS);
        }
    }

    // --- a second screen ----------------------------------------------------

    /**
     * Puts the keyboard, the lamps and the bar on a second screen, or brings
     * them back.
     *
     * Called from every place the answer can change - the setting, coming back
     * to the front, a panel being plugged in or unplugged - and works out the
     * whole answer each time rather than tracking what changed, since there are
     * only two states and the cost is a comparison.
     */
    private void applySecondScreen() {
        boolean wanted = preferences.getBoolean(
                SettingsActivity.KEY_SECOND_SCREEN, false);

        // A panel that is up stays up unless it is not wanted any more or the
        // display it is on has really gone. Nothing else is a reason: this runs
        // on every display event, and Android reports odd things in passing -
        // the activity briefly claiming to be on the panel itself while an
        // input method is being sorted out, which once took the panel down and
        // left nothing to put it back.
        if (secondScreen != null) {
            Display showing = secondScreen.getDisplay();
            DisplayManager displays = getSystemService(DisplayManager.class);

            boolean gone = showing == null || displays == null
                    || displays.getDisplay(showing.getDisplayId()) == null;

            if (wanted && !gone) return;
            closeSecondScreen();
        }

        if (!wanted) return;

        Display display = secondDisplay();
        if (display == null) return;

        // Lending first: the views have to be parentless before another window
        // can adopt them.
        layout.setLentAway(true);
        secondScreen = new SecondScreen(this, display, layout.lendable());

        try {
            secondScreen.show();
        } catch (WindowManager.InvalidDisplayException e) {
            // The panel went away between being listed and being shown.
            Log.w(TAG, "second screen vanished", e);
            closeSecondScreen();
            return;
        }

        revealQuickBar();
        applyFullscreen();
        controls.applyGamepad();
    }

    /**
     * Opens one of the app's own screens - settings, the hotkeys, a profile -
     * where the controls are.
     *
     * With a panel in use that is the panel: a screen asked for by a thumb on
     * one display should not appear on the other, and the machine's screen is
     * the machine's. The panel's window steps aside while it is up, because a
     * presentation sits above activity windows and would otherwise hide the
     * very thing it just opened; the result coming back is what puts it up
     * again, and covers the screen being finished any way at all.
     */
    void openOwnScreen(Intent intent) {
        Display panel = secondScreen == null ? null : secondScreen.getDisplay();

        if (panel == null) {
            startActivity(intent);
            return;
        }

        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(panel.getDisplayId());

        // A task of its own, because a task lives on one display: launched into
        // ours, the settings screen took the machine with it to the panel and
        // left the first screen empty.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(intent, options.toBundle());
        } catch (RuntimeException e) {
            // A display that will not host activities - then it goes where
            // everything else does.
            Log.w(TAG, "cannot open on the second screen", e);
            startActivity(new Intent(intent).setFlags(0));
        }
    }

    /**
     * Every other screen of ours steps the panel aside while it is up.
     *
     * A presentation is drawn above the activity windows on its display, so a
     * screen opened on the panel would be behind the keyboard without this. A
     * result would have been a tidier signal, but a new task cannot return one
     * - and this way the panel also comes back from a screen that was never
     * opened by us, or dismissed some way of Android's own.
     */
    private int ownScreens;

    private final Application.ActivityLifecycleCallbacks screensOfOurs =
            new Application.ActivityLifecycleCallbacks() {

        @Override
        public void onActivityStarted(Activity activity) {
            if (activity == EmulatorActivity.this) return;

            ownScreens++;
            if (secondScreen != null) secondScreen.hide();
        }

        @Override
        public void onActivityStopped(Activity activity) {
            if (activity == EmulatorActivity.this || ownScreens == 0) return;

            if (--ownScreens == 0 && secondScreen != null) secondScreen.show();
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle state) { }

        @Override
        public void onActivityResumed(Activity activity) { }

        @Override
        public void onActivityPaused(Activity activity) { }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

        @Override
        public void onActivityDestroyed(Activity activity) { }
    };

    private void closeSecondScreen() {
        if (secondScreen == null) return;

        SecondScreen going = secondScreen;
        secondScreen = null;

        going.dismiss();
        layout.setLentAway(false);

        applyFullscreen();
        controls.applyGamepad();
        revealQuickBar();
    }

    /**
     * A display worth putting controls on: Android's own definition, which is
     * the displays that are not the one the activity is on and are meant to be
     * presented to. The last is taken, since that is the one most recently
     * attached.
     */
    private Display secondDisplay() {
        DisplayManager displays = getSystemService(DisplayManager.class);
        if (displays == null) return null;

        Display[] found = displays.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Display here = getDisplay();

        // The last one attached, but never the one we are on ourselves: Android
        // can put the activity on the panel - it launches an app where the last
        // touch was - and a window over the machine's own screen would take the
        // picture away rather than the furniture.
        for (int i = found.length - 1; i >= 0; i--) {
            if (here == null || found[i].getDisplayId() != here.getDisplayId()) {
                return found[i];
            }
        }

        return null;
    }

    /**
     * A panel appearing or going away. Like the controller listener, all three
     * do the same thing: look again.
     */
    private final DisplayManager.DisplayListener screens =
            new DisplayManager.DisplayListener() {

        @Override
        public void onDisplayAdded(int displayId) {
            applySecondScreen();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            applySecondScreen();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            applySecondScreen();
        }
    };

    /** What holding fast forward runs at, in per cent of a real Spectrum. */
    private static final int FAST_FORWARD = 500;

    private boolean fastForwarding;

    /**
     * Five hundred per cent while it is held, and back to the setting when it is
     * let go.
     *
     * The setting is not written to: this is a thing being done, not a preference
     * being changed, and a loading screen skipped at speed should not leave the
     * machine fast for the game afterwards. Reading the stored value back is what
     * restores it, so whatever the user chose is what returns - 25% included.
     *
     * Guarded against being told the same thing twice, because it arrives from a
     * finger, a controller's trigger and that trigger's axis, and on some pads
     * two of those at once.
     */
    private void fastForward(boolean on) {
        if (fastForwarding == on) return;

        fastForwarding = on;
        FuseNative.setSpeed(on ? FAST_FORWARD
                               : SettingsActivity.SettingsFragment.number(
                                       preferences, SettingsActivity.KEY_SPEED, 100));
    }

    private boolean fullscreen() {
        return preferences.getBoolean(SettingsActivity.KEY_FULLSCREEN, false);
    }

    /**
     * Gives the picture the whole window, or gives the furniture back.
     *
     * The bar loses its strip and the keyboard and the lamps go away, whichever
     * way up the device is. Getting out is the same icon: a tap on the picture
     * brings the bar back for three seconds, which is long enough to press it -
     * and so does Back, for anyone who does not know that.
     */
    private void showFullscreen(boolean on) {
        preferences.edit()
                .putBoolean(SettingsActivity.KEY_FULLSCREEN, on).apply();

        applyFullscreen();
        revealQuickBar();
        note(on ? R.string.fullscreen_on : R.string.fullscreen_off);
    }

    private void applyFullscreen() {
        boolean on = fullscreen();

        layout.setFullscreen(on);

        if (fullscreenAction != null) {
            // Not offered while the controls are on a panel: this screen is
            // already the picture and nothing else, so there is nothing for
            // the button to clear away and nothing for it to give back.
            fullscreenAction.setVisibility(secondScreen == null ? View.VISIBLE
                                                                : View.GONE);

            quickBar.setAction(fullscreenAction,
                               on ? R.drawable.ic_fullscreen_exit
                                  : R.drawable.ic_fullscreen,
                               getString(on ? R.string.fullscreen_leave
                                            : R.string.fullscreen_enter));
        }
    }

    /**
     * Takes the bar away entirely while the ROMs panel is covering everything,
     * and puts it back when a machine is running.
     *
     * There is nothing for it to do with no machine: no state to save, nothing
     * to pause, no picture to photograph, no drives to look in. It kept ☰ for a
     * while so that the data folder stayed reachable, but the panel's own three
     * options are the doors out of it - download a set, import a folder, import
     * files - and each of them puts ROMs where this needs them. A bar of
     * actions that cannot act is worse than no bar.
     *
     * Nothing reveals it while the panel is up: the panel covers the screen, so
     * a tap lands on the panel rather than on the picture, but startup and the
     * sheet closing both ask as well.
     */
    private void hideBarForPanel(boolean covering) {
        panelUp = covering;

        if (!covering) {
            revealQuickBar();
            return;
        }

        barWanted = false;
        quickBar.removeCallbacks(fadeQuickBar);
        quickBar.animate().cancel();
        quickBar.collapse();
        quickBar.setVisibility(View.GONE);
    }

    /**
     * What is installed, asked of the package manager rather than compiled in.
     *
     * The build takes the number from version.properties, and this reads back
     * what actually ended up in the APK - so a version on screen is the version
     * running, not one a stale constant remembers.
     */
    private String version() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
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
     * Choosing one of a set is a page of ticked rows, anything that needs
     * confirming is a page with the question on it, and anything that needs a
     * name or a number is a page with a line to type into. None of it is a
     * dialog any more: a dialog belongs to the activity's window and so always
     * appears on the machine's screen, which on a handheld is the screen the
     * question was not asked on.
     */
    private MenuDrawer buildMenu() {
        MenuDrawer menu = new MenuDrawer(this);

        menu.setRoot(sheet -> {
            sheet.addItem(getString(R.string.menu_open), R.drawable.ic_folder,
                          media::pick);

            // Only with something on it: a list of nothing is a row that
            // teaches you not to press it.
            if (!Recents.all(preferences).isEmpty()) {
                sheet.addSubmenu(getString(R.string.menu_recent),
                                 R.drawable.ic_file, this::fillRecent);
            }
            // The machine second: what is running is asked about more often than
            // anything filed away, and it holds pause.
            sheet.addSubmenu(withMachine(R.string.menu_machine_group),
                             R.drawable.ic_chip, this::fillMachine);
            sheet.addSubmenu(getString(R.string.menu_states), R.drawable.ic_bookmark,
                             this::fillStates);
            sheet.addSubmenu(getString(R.string.menu_pokes), R.drawable.ic_poke,
                             pokes::fill);
            // The page's own heading, which sits over the tape rows: the
            // drives that follow have DRIVES of their own.
            sheet.addSubmenu(getString(R.string.menu_media),
                             getString(R.string.menu_tape_section),
                             R.drawable.ic_tape, media::fill);
            sheet.addSubmenu(getString(R.string.menu_capture), R.drawable.ic_camera,
                             this::fillCapture);

            sheet.addRule();
            sheet.addSubmenu(getString(R.string.menu_controls),
                             R.drawable.ic_controls, controls::fill);
            sheet.addItem(getString(R.string.menu_settings), R.drawable.ic_settings,
                    () -> openOwnScreen(new Intent(this, SettingsActivity.class)));
            sheet.addItem(getString(R.string.menu_about, version()),
                          R.drawable.ic_info,
                          () -> openOwnScreen(new Intent(this, AboutActivity.class)));

            sheet.addRule();
            sheet.addItem(getString(R.string.menu_quit), R.drawable.ic_quit,
                          this::quit);
        });

        return menu;
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
    }

    // --- joystick -----------------------------------------------------------

    /**
     * Said once, the first time a game reaches for a mouse that is not there.
     *
     * The lamp lights whether or not the mouse is plugged in, because reading
     * the ports is the interesting part - but a lit lamp and a cursor that will
     * not move is a puzzle, and this is the answer to it. Once per run: it is
     * help, not a warning, and a game polls those ports fifty times a second.
     */
    private boolean saidAboutMouse;

    private void mouseWanted() {
        if (saidAboutMouse || Mouse.enabled()) return;

        saidAboutMouse = true;
        Toast.makeText(this, R.string.mouse_wanted, Toast.LENGTH_LONG).show();
    }

    /**
     * One of the controller's hotkeys, on the UI thread.
     *
     * Every one of them is a thing a menu already does, called the same way the
     * menu calls it - which is the point of the list being closed rather than
     * open: a hotkey is a shortcut to something that exists, so there is nothing
     * here that can only be reached from a controller and nothing to keep in step
     * with a second implementation.
     *
     * {@code pressed} is false only for the held kind, and only
     * {@link Hotkeys.Action#FAST_FORWARD} is that.
     */
    private void runHotkey(Hotkeys.Action action, boolean pressed) {
        switch (action) {
            case FAST_FORWARD: fastForward(pressed); return;
            default: break;
        }

        if (!pressed) return;

        switch (action) {
            case PAUSE: pause(!isPaused()); break;
            // No confirming: a hotkey behind a modifier is deliberate enough,
            // and a dialog is the one thing a pad in a stand cannot dismiss.
            case RESET: FuseNative.reset(); note(R.string.hotkey_reset_done); break;
            case NMI: FuseNative.nmi(); break;
            case QUIT: quit(); break;

            case QUICK_SAVE: quickSave(); break;
            case QUICK_LOAD: quickLoad(); break;
            case SAVE_STATE: showStates(true); break;
            case LOAD_STATE: showStates(false); break;

            case SPEED_UP: stepSpeed(1); break;
            case SPEED_DOWN: stepSpeed(-1); break;

            case FULLSCREEN: showFullscreen(!fullscreen()); break;
            case SCREENSHOT: takeScreenshot(); break;
            case RECORD:
                if (Recorder.isRecording()) Recorder.stop();
                else startRecording(Recorder.Format.GIF);
                break;

            case KEYBOARD: controls.showKeyboard(!layout.keyboardVisible()); break;
            case JOYSTICK: controls.showJoystick(!layout.joystickVisible()); break;
            case INDICATORS: showLights(!layout.lightsVisible()); break;
            case NEXT_PROFILE: controls.nextKeyProfile(); break;
            case NEXT_JOYSTICK: controls.nextJoystickType(); break;

            case MENU: menu.open(); break;
            case QUICK_BAR: revealQuickBar(); break;
            case SETTINGS: openOwnScreen(new Intent(this, SettingsActivity.class)); break;

            default: break;
        }
    }

    /**
     * How far a finger may wander before it is a mouse drag rather than a tap,
     * in dp. Android's own touch slop is about eight; a finger aiming at a tap
     * moves further than that on a phone held in one hand.
     */
    private static final int MOUSE_SLOP = 10;


    /** What a hotkey's state is called, after whatever it is a state of. */
    private static final String QUICK_STATE = "Quick";

    /**
     * One quick save per game, named after it: <i>Tujad Quick</i>.
     *
     * A single slot was one game's save until the next game overwrote it, which
     * is the wrong way round for the thing meant to be pressed without
     * thinking. Named after the media that is loaded, every game keeps its own
     * and a hotkey means "mine".
     *
     * With nothing loaded - a machine sitting at BASIC, a reset - there is no
     * name to borrow and it is plain <i>Quick</i>, which is what it always was.
     */
    private String quickStateName() {
        String media = preferences.getString(States.KEY_MEDIA_NAME, null);

        if (media == null || media.isEmpty()) return QUICK_STATE;

        // Loading a state makes it the media name, so a quick load followed by
        // a quick save must not end up at "Tujad Quick Quick".
        if (media.equals(QUICK_STATE) || media.endsWith(" " + QUICK_STATE)) {
            return media;
        }

        return media + " " + QUICK_STATE;
    }

    /**
     * What the quick pair are named after - the file that is open - or null
     * when nothing is, where they are just "Quick save" and "Quick load".
     *
     * The stored name may already carry the suffix, since loading a state sets
     * it; {@link #quickStateName} tolerates that and so does this, from the
     * other end.
     */
    private String quickSubject() {
        String media = preferences.getString(States.KEY_MEDIA_NAME, null);

        if (media == null || media.isEmpty() || media.equals(QUICK_STATE)) {
            return null;
        }

        return media.endsWith(" " + QUICK_STATE)
                ? media.substring(0, media.length() - QUICK_STATE.length() - 1)
                : media;
    }

    private void quickSave() {
        String name = quickStateName();

        if (!States.save(this, preferences, name)) {
            note(R.string.state_failed);
            return;
        }

        note(R.string.state_saved, name);
    }

    private void quickLoad() {
        String name = quickStateName();

        for (States.Saved state : States.all(this)) {
            if (state.name.equals(name)) {
                // The media name is left as it is: what is loaded is still the
                // game, and calling it "Tujad Quick" from here would name the
                // next save after the save rather than after the game.
                States.load(state);
                note(R.string.state_loaded, state.name);
                return;
            }
        }

        note(R.string.hotkey_no_quick_save);
    }

    /**
     * The next speed up or down the settings' own list, so a hotkey and the
     * setting cannot disagree about what the speeds are.
     */
    private void stepSpeed(int direction) {
        String[] values = getResources().getStringArray(R.array.speed_values);
        String current = preferences.getString(SettingsActivity.KEY_SPEED, "100");

        int at = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) at = i;
        }

        int next = Math.max(0, Math.min(values.length - 1, at + direction));
        if (next == at) return;

        preferences.edit().putString(SettingsActivity.KEY_SPEED, values[next]).apply();
        FuseNative.setSpeed(Integer.parseInt(values[next]));
        note(R.string.hotkey_speed, values[next]);
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
    /**
     * A picture of the machine, or a film of it.
     *
     * Built when the page is opened, so it offers the one thing that makes
     * sense: there is nothing to stop until something is running.
     */
    private void fillCapture(Rows rows) {
        rows.item(R.drawable.ic_camera, getString(R.string.capture_screenshot),
                  this::takeScreenshot);

        if (Recorder.isRecording()) {
            rows.item(R.drawable.ic_stop, getString(R.string.capture_stop),
                      Recorder::stop);
        } else {
            rows.item(R.drawable.ic_record, getString(R.string.capture_gif),
                      () -> startRecording(Recorder.Format.GIF));
            rows.item(R.drawable.ic_film, getString(R.string.capture_mp4),
                      () -> startRecording(Recorder.Format.MP4));
        }

        rows.rule();
        rows.item(R.drawable.ic_folder, getString(R.string.capture_open_folder),
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

        String base = preferences.getString(States.KEY_MEDIA_NAME, null);
        if (base == null || base.isEmpty()) base = "Spectrum";

        File target = new File(directory, base + "." + extension);
        for (int n = 2; target.exists() && n < 10000; n++) {
            target = new File(directory, base + " " + n + "." + extension);
        }

        return target;
    }

    // --- save states ----------------------------------------------------

    /**
     * The list of saved states is a screen of its own - see
     * {@link StatesActivity}. A state is a picture, and a picture wants more
     * room than a three-hundred-dp sheet can give it; the screen also opens on
     * whichever display the controls are on, which is the whole point of
     * {@link #openOwnScreen}.
     *
     * What is left here is what the machine's own side needs: the two hotkeys,
     * which write and read one state without asking anything.
     */
    private void showStates(boolean saving) {
        openOwnScreen(StatesActivity.intent(this, saving));
    }

    /**
     * Saving and loading, both ways round: the list, which asks for a name and
     * shows the pictures, and the one state a hotkey writes without asking.
     *
     * A group of its own on the bar. It used to be three rows under the folder
     * icon along with the picker and the recent files, which meant a folder
     * standing for "files and states" - and the quick pair, which are the two
     * most reached-for things in the app, were on a hotkey and nowhere else.
     * Anyone without a controller could not reach them at all.
     */
    private void fillStates(Rows rows) {
        rows.item(R.drawable.ic_save, getString(R.string.menu_save_state),
                  () -> showStates(true));
        rows.item(R.drawable.ic_load, getString(R.string.menu_load_state),
                  () -> showStates(false));

        // Named after what is running rather than after the state: the state is
        // called "Tujad Quick", and a row reading "Quick save - Tujad Quick"
        // says quick twice and tells you nothing the first one did not.
        String subject = quickSubject();

        rows.rule();
        rows.item(R.drawable.ic_save,
                  subject == null ? getString(R.string.hotkey_quick_save)
                                  : getString(R.string.quick_save, subject),
                  this::quickSave);
        rows.item(R.drawable.ic_load,
                  subject == null ? getString(R.string.hotkey_quick_load)
                                  : getString(R.string.quick_load, subject),
                  this::quickLoad);
    }

    /** What a new state will be called: the media that is loaded. */
    private void rememberMediaName(String name) {
        preferences.edit().putString(States.KEY_MEDIA_NAME, name).apply();
    }

    /** Nothing is loaded any more, so new states go back to being numbered. */
    private void forgetMediaName() {
        preferences.edit().remove(States.KEY_MEDIA_NAME).apply();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);

        // Each collaborator says whether the result was one of its own. The
        // ROM pickers belong to the panel and the three media pickers to
        // Media; nothing else here asks for a result.
        if (roms.onActivityResult(request, result, data)) return;

        media.onActivityResult(request, result, data);
    }

    /** Says an action happened. Fuse itself is silent about most of them. */
    private void note(int message, Object... arguments) {
        runOnUiThread(() -> Toast.makeText(this, getString(message, arguments),
                                           Toast.LENGTH_SHORT).show());
    }

    private MenuDrawer.Page resetMachine() {
        return page -> {
            page.addNote(getString(R.string.reset_confirm));
            page.addItem(getString(R.string.menu_reset), R.drawable.ic_reset, () -> {
                FuseNative.reset();
                forgetMediaName();
                note(R.string.reset_done);
            });
        };
    }


    private void showMachineDialog() {
        String[] names = FuseNative.machineNames();
        if (names.length == 0) return;   // Fuse has not finished starting

        int current = FuseNative.currentMachine();

        menu.go(getString(R.string.machine_title), page -> {
            for (int i = 0; i < names.length; i++) {
                int which = i;
                page.addChoice(names[which], which == current,
                               () -> selectMachine(which));
            }
        });
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
    /**
     * How long to let the recorder finish its file before going anyway.
     *
     * {@link Recorder#stop} does not block - it cannot, being called from the UI
     * thread - so the encoder is still writing when it returns, and a process
     * that exits underneath it leaves a truncated film. A second is far more
     * than the queue takes to drain, and quitting is not the moment to be exact.
     */
    private static final long RECORDER_GRACE_MS = 1000;

    /**
     * Closes the app rather than leaving it in the background.
     *
     * Back and Home only put a Spectrum away - the emulator pauses itself and
     * waits, which is what an emulator should do. This is for meaning it, and it
     * ends the *process*, not just the activity: the emulation thread is a plain
     * pthread inside Fuse's main loop, Fuse's globals cannot be initialised
     * twice, and the next launch has to be able to start it again. See
     * {@code Java_dev_ldlab_zedex_FuseNative_start}.
     *
     * Two things are worth a moment on the way out - a recording being written,
     * and a disk with changes nothing has written back - because both are work
     * the machine cannot get back for you.
     */
    private void quit() {
        String unsaved = media.modifiedDisks();

        if (unsaved == null) {
            quitNow();
            return;
        }

        menu.go(getString(R.string.quit_unsaved_title), page -> {
            page.addNote(getString(R.string.quit_unsaved, unsaved));
            page.addItem(getString(R.string.menu_quit), R.drawable.ic_quit,
                         this::quitNow);
        });
    }

    private void quitNow() {
        rememberMachine();

        if (Recorder.isRecording()) {
            Recorder.stop();
            Recorder.waitForFile(RECORDER_GRACE_MS);
        }

        // Off the recents list too: a task left there offers to resume a machine
        // whose process has gone, and Android would answer that by starting a
        // fresh one - which is what happens anyway, only having looked like the
        // old one was still there.
        finishAndRemoveTask();
        Runtime.getRuntime().exit(0);
    }

    /**
     * What the machine calls itself, or nothing at all before there is one.
     *
     * Asked of Fuse rather than read from the setting, because the two can
     * disagree: media brings its own machine with it - a .dsk switches to a +3 -
     * and Fuse falls back to 48K when the ROMs for the one that was asked for are
     * not there. What is running is the only answer worth showing.
     */
    private String machineName() {
        int current = FuseNative.currentMachine();
        String[] names = FuseNative.machineNames();

        return current >= 0 && current < names.length ? names[current] : null;
    }

    /** A menu label with the machine under it, where there is one. */
    private String withMachine(int label) {
        String name = machineName();
        return name == null ? getString(label) : getString(label) + "\n" + name;
    }

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

        InputManager input = getSystemService(InputManager.class);
        if (input != null) input.unregisterInputDeviceListener(devices);

        DisplayManager displays = getSystemService(DisplayManager.class);
        if (displays != null) displays.unregisterDisplayListener(screens);

        // A held direction has nobody to let go of it once we are not being sent
        // events any more.
        gamepad.releaseAll();

        pausedByAndroid = true;
        applyPause();
    }

    /**
     * The second screen goes when the app does. It is a window this activity
     * owns, so leaving it up would leak it, and there is nothing to control
     * while the machine is paused anyway; {@link #onResume} puts it back.
     */
    @Override
    protected void onStop() {
        super.onStop();
        closeSecondScreen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getApplication().unregisterActivityLifecycleCallbacks(screensOfOurs);
    }

    /**
     * A controller appearing or going away.
     *
     * Android has no broadcast for "a gamepad is connected"; the device list is
     * the only answer, and this is how to know it has changed. Every one of the
     * three does the same thing - look again - since the question is only ever
     * whether there is one now.
     */
    private final InputManager.InputDeviceListener devices =
            new InputManager.InputDeviceListener() {

        @Override
        public void onInputDeviceAdded(int deviceId) {
            controls.applyGamepad();
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            gamepad.releaseAll();
            controls.applyGamepad();
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
            controls.applyGamepad();
        }
    };

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
        // The first run asks where things are kept before anything is kept
        // anywhere: the ROMs are unpacked into the answer, so the question
        // comes before the machine.
        if (StartPanel.setupNeeded(this)) {
            roms.showSetup();
            return;
        }

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

        // Not Fuse's settings, so they cannot ride in on its command line: the
        // renderer has to be told. Queued, so arriving before Fuse has finished
        // starting is safe.
        SettingsActivity.applyFilter(preferences);
        SettingsActivity.applyScale(this, preferences);
        startDivmmc();
    }

    /**
     * The DivMMC, in the order it has to happen: the firmware, then the
     * interface, then whatever card was in it.
     *
     * Queued rather than passed on the command line, and deliberately so.
     * {@code --divmmc} would have Fuse plug the interface in during its own
     * startup, before anything here could put firmware in the EPROM, and a
     * DivMMC with a blank EPROM pages itself into the machine's reset and hangs
     * it - a black screen before the first frame. The queue keeps these three in
     * order, so the firmware is always in place first.
     */
    private void startDivmmc() {
        File firmware = Storage.divmmcFirmware(this);

        if (!firmware.isFile()) return;

        FuseNative.loadDivmmcFirmware(firmware.getAbsolutePath());

        if (!preferences.getBoolean(SettingsActivity.KEY_DIVMMC, false)) return;

        FuseNative.setDivmmc(true);

        String card = preferences.getString(Media.PREF_CARD, null);
        if (card != null && new File(card).isFile()) FuseNative.insertCard(card);
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

        // Not the word "fuse": Fuse looks for its font in lib beside whatever
        // argv[0] names, and this is how it is pointed at ours.
        arguments.add(new File(getFilesDir(), PROGRAM).getAbsolutePath());
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

        // Fuse's own word for it, passed straight through; see AY_STEREO.
        arguments.add("--separation");
        arguments.add(SettingsActivity.ayStereoName(preferences));
        value(arguments, SettingsActivity.KEY_BEEPER_VOLUME, 100, "volume-beeper");

        // The on-screen joystick is Fuse's joystick 1. Kempston is a type and
        // also a piece of hardware, and the port is only decoded when the
        // interface is there, so the two go together - see OPTION_JOYSTICK_TYPE
        // in android_bridge.c, which does the same when it is changed later.
        int joystick = controls.joystickType();
        if (joystick == Controls.JOYSTICK_KEYBOARD) joystick = Controls.JOYSTICK_NONE;

        arguments.add("--joystick-1-output");
        arguments.add(String.valueOf(joystick));
        arguments.add(joystick == Controls.JOYSTICK_KEMPSTON ? "--kempston"
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
        // A controller first: its buttons are not keys the machine has, and its
        // D-pad must not be mistaken for a keyboard's cursor keys, which the
        // machine does have.
        if (gamepad.key(event)) return true;

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // With the sheet open, back belongs to the sheet: up one page,
            // and out of the sheet altogether from the top of it.
            if (menu.back()) return true;

            // Then fullscreen, which is the other thing back means "out of" -
            // and the one that has taken the way out off the screen, since the
            // bar it lives on has faded.
            if (fullscreen()) {
                showFullscreen(false);
                return true;
            }

            // And otherwise the menu, rather than the desktop. A tap outside
            // it or back again is the way out, so nothing is trapped; leaving
            // is Quit, which asks about unsaved disks first. A machine is not
            // a page to be backed out of - and a Spectrum put away by accident
            // is a Spectrum whose RAM has gone.
            menu.open();
            return true;
        }

        return forwardKey(keyCode, true) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (gamepad.key(event)) return true;
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event);

        return forwardKey(keyCode, false) || super.onKeyUp(keyCode, event);
    }

    /** A controller's stick and hat, which arrive as axes rather than as keys. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return gamepad.motion(event) || super.onGenericMotionEvent(event);
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
