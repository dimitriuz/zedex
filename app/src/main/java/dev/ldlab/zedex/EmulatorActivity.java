package dev.ldlab.zedex;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.input.ControlProfiles;
import dev.ldlab.zedex.input.Gamepad;
import dev.ldlab.zedex.input.Hotkeys;
import dev.ldlab.zedex.input.Mouse;
import dev.ldlab.zedex.machine.Border;
import dev.ldlab.zedex.machine.Filter;
import dev.ldlab.zedex.machine.Machine;
import dev.ldlab.zedex.media.Media;
import dev.ldlab.zedex.menu.Capture;
import dev.ldlab.zedex.menu.ControlsUi;
import dev.ldlab.zedex.menu.PokesUi;
import dev.ldlab.zedex.menu.StatesUi;
import dev.ldlab.zedex.screen.AboutActivity;
import dev.ldlab.zedex.screen.Panels;
import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.screen.StartPanel;
import dev.ldlab.zedex.screen.StatesActivity;
import dev.ldlab.zedex.storage.Recents;
import dev.ldlab.zedex.storage.States;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.view.ActivityLights;
import dev.ldlab.zedex.view.EmulatorLayout;
import dev.ldlab.zedex.view.JoystickView;
import dev.ldlab.zedex.view.MenuDrawer;
import dev.ldlab.zedex.view.QuickBar;
import dev.ldlab.zedex.view.Rows;
import dev.ldlab.zedex.view.SpectrumKeyboardView;
import dev.ldlab.zedex.view.SystemKeyboardView;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
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
import java.net.HttpURLConnection;
import java.net.URL;
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

    private static final String PREFS = SettingsActivity.PREFS;


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
     * The other screen of a two-screen handheld, and the rule about which
     * display the app's own screens open on; see {@link Panels}. Null-safe to
     * ask at any time - it simply reports that there is no panel.
     */
    private Panels panels;

    /**
     * The emulated Spectrum: starting it, changing it, its speed, and ending
     * the process; see {@link Machine}. Built in onCreate.
     */
    private Machine machine;

    /** Screenshots and recording; see {@link Capture}. */
    private Capture capture;

    /** Save states, and the quick pair; see {@link StatesUi}. */
    private StatesUi states;

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

        capture = new Capture(this, preferences);

        states = new StatesUi(this, preferences, new StatesUi.Host() {
            @Override
            public void note(int message, Object... arguments) {
                EmulatorActivity.this.note(message, arguments);
            }

            @Override
            public void openList(boolean saving) {
                panels.openOwnScreen(StatesActivity.intent(EmulatorActivity.this,
                                                           saving));
            }
        });

        machine = new Machine(this, preferences, new Machine.Host() {
            @Override
            public void note(int message, Object... arguments) {
                EmulatorActivity.this.note(message, arguments);
            }

            @Override
            public MenuDrawer sheet() {
                return menu;
            }

            @Override
            public void forgetMedia() {
                forgetMediaName();
            }

            @Override
            public String modifiedDisks() {
                return media.modifiedDisks();
            }

            @Override
            public int joystickType() {
                return controls.joystickType();
            }

            @Override
            public void startFailed() {
                roms.show(true);
            }
        });

        panels = new Panels(this, preferences, new Panels.Host() {
            @Override
            public EmulatorLayout layout() {
                return layout;
            }

            @Override
            public void panelChanged() {
                // Three things follow from the panel coming or going, and all
                // three are this activity's: the bar stops fading over there,
                // the fullscreen button has nothing to clear away, and the
                // on-screen pad steps aside for the handheld's real one.
                revealQuickBar();
                applyFullscreen();
                controls.applyGamepad();
            }
        });

        getApplication().registerActivityLifecycleCallbacks(panels.lifecycle());
        FuseNative.attach(this);
        Storage.createFolders(this);

        // The ROMs the app ships, before anything asks whether there are any -
        // except on the very first run, where the folder to put them in is the
        // question the panel is about to ask. Only ever the ones that are not
        // there already: the folder is the user's to fill as well as ours.
        if (!StartPanel.setupNeeded(this)) Storage.installRoms(this);

        Machine.prepare(this);

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
                return panels.inUse();
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
         * The picture moves out of its way, and the menu has to agree with it:
         * the keyboard can be dismissed from the keyboard, so without this the
         * app went on offering to hide something already gone.
         *
         * Two traps. It records only - asking the keyboard to appear happens
         * elsewhere - so noticing cannot turn into asking again. And it ignores
         * everything until the keyboard has been seen once: the insets arrive
         * before it does, so the first thing heard at startup was "not visible",
         * which the app took as "not wanted" and used to close the keyboard it
         * had just asked for.
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
        panels.watch();

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
     * Where it goes is {@link EmulatorLayout}'s business: a strip across the top
     * of the window, with the picture starting below it.
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
                     states::fill);
        // The machine here as in the sheet, since it holds pause and the two
        // menus reading the same way is worth more than either order is.
        bar.addGroup(R.drawable.ic_chip, getString(R.string.menu_machine_group),
                     this::fillMachine);
        bar.addGroup(R.drawable.ic_camera, getString(R.string.menu_capture),
                     capture::fill);
        bar.addGroup(R.drawable.ic_controls, getString(R.string.menu_on_screen),
                     this::fillOnScreen);
        bar.addGroup(R.drawable.ic_display, getString(R.string.menu_display),
                     this::fillDisplay);

        bar.addHold(R.drawable.ic_fast_forward, getString(R.string.fast_forward),
                    () -> machine.fastForward(true), () -> machine.fastForward(false));

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
        rows.item(R.drawable.ic_swap, machine.withName(R.string.menu_machine),
                  machine::showChooser);
        // Reset asks first, and asking is a sheet page - so the bar's row opens
        // the sheet on it rather than the two surfaces doing it differently.
        rows.item(R.drawable.ic_reset, getString(R.string.menu_reset),
                  () -> menu.go(getString(R.string.menu_reset), machine.resetPage()));
        // No confirming, unlike reset: the magic button interrupts the machine
        // rather than throwing its state away, and half of what it is for is
        // pressing it at a particular moment.
        rows.item(R.drawable.ic_bolt, getString(R.string.menu_nmi), machine::nmi);
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
     * What the picture looks like: the two switches named for what they would do,
     * the two choosers for what is chosen.
     *
     * Scanlines and CRT are two rows here and one choice of four in the settings,
     * because turning scanlines off to read something is a decision of the moment.
     * {@link Filter} is what stops them treading on each other.
     *
     * The lamps are under a line at the foot: they are read rather than played
     * with, so they belong with the picture and not with the joystick.
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
        if (panels.inUse()) return;

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

        if (fullscreen() && !panels.inUse()) {
            quickBar.postDelayed(fadeQuickBar, BAR_LINGER_MS);
        }
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
            fullscreenAction.setVisibility(!panels.inUse() ? View.VISIBLE
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
     * With no machine there is nothing for it to do - no state to save, nothing
     * to pause, no picture to photograph, no drives to look in - and a bar of
     * actions that cannot act is worse than no bar.
     *
     * The flag is needed because a tap on the panel is not the only thing that
     * would bring the bar back: startup and the sheet closing both ask too.
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
     * The ☰ sheet: opening something at the top, then the doors, then the way out.
     *
     * The pages are functions and not lists, because most depend on what is
     * happening — which drives this machine has, whether something is recording —
     * so each is called when its page is shown.
     *
     * Choosing one of a set is a page of ticked rows; a question is a page with
     * the question on it; a name or a number is a page with a line to type into.
     * None of it is a dialog, because a dialog belongs to the activity's window
     * and so always appears on the machine's screen — which on a handheld is the
     * screen the question was not asked on.
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
            sheet.addSubmenu(machine.withName(R.string.menu_machine_group),
                             R.drawable.ic_chip, this::fillMachine);
            sheet.addSubmenu(getString(R.string.menu_states), R.drawable.ic_bookmark,
                             states::fill);
            sheet.addSubmenu(getString(R.string.menu_pokes), R.drawable.ic_poke,
                             pokes::fill);
            // The page's own heading, which sits over the tape rows: the
            // drives that follow have DRIVES of their own.
            sheet.addSubmenu(getString(R.string.menu_media),
                             getString(R.string.menu_tape_section),
                             R.drawable.ic_tape, media::fill);
            sheet.addSubmenu(getString(R.string.menu_capture), R.drawable.ic_camera,
                             capture::fill);

            sheet.addRule();
            sheet.addSubmenu(getString(R.string.menu_controls),
                             R.drawable.ic_controls, controls::fill);
            sheet.addItem(getString(R.string.menu_settings), R.drawable.ic_settings,
                    () -> panels.openOwnScreen(new Intent(this, SettingsActivity.class)));
            sheet.addItem(getString(R.string.menu_about, version()),
                          R.drawable.ic_info,
                          () -> panels.openOwnScreen(new Intent(this, AboutActivity.class)));

            sheet.addRule();
            sheet.addItem(getString(R.string.menu_quit), R.drawable.ic_quit,
                          machine::quit);
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
     * Every one is a thing a menu already does, called the way the menu calls it.
     * That is the point of the list being closed: nothing here can only be
     * reached from a controller, and there is no second implementation to keep in
     * step.
     *
     * {@code pressed} is false only for the held kind, and only
     * {@link Hotkeys.Action#FAST_FORWARD} is that.
     */
    private void runHotkey(Hotkeys.Action action, boolean pressed) {
        switch (action) {
            case FAST_FORWARD: machine.fastForward(pressed); return;
            default: break;
        }

        if (!pressed) return;

        switch (action) {
            case PAUSE: pause(!isPaused()); break;
            // No confirming: a hotkey behind a modifier is deliberate enough,
            // and a dialog is the one thing a pad in a stand cannot dismiss.
            case RESET: FuseNative.reset(); note(R.string.hotkey_reset_done); break;
            case NMI: machine.nmi(); break;
            case QUIT: machine.quit(); break;

            case QUICK_SAVE: states.quickSave(); break;
            case QUICK_LOAD: states.quickLoad(); break;
            case SAVE_STATE: states.openList(true); break;
            case LOAD_STATE: states.openList(false); break;

            case SPEED_UP: machine.stepSpeed(1); break;
            case SPEED_DOWN: machine.stepSpeed(-1); break;

            case FULLSCREEN: showFullscreen(!fullscreen()); break;
            case SCREENSHOT: capture.screenshot(); break;
            case RECORD: capture.toggleRecording(); break;

            case KEYBOARD: controls.showKeyboard(!layout.keyboardVisible()); break;
            case JOYSTICK: controls.showJoystick(!layout.joystickVisible()); break;
            case INDICATORS: showLights(!layout.lightsVisible()); break;
            case NEXT_PROFILE: controls.nextKeyProfile(); break;
            case NEXT_JOYSTICK: controls.nextJoystickType(); break;

            case MENU: menu.open(); break;
            case QUICK_BAR: revealQuickBar(); break;
            case SETTINGS: panels.openOwnScreen(new Intent(this, SettingsActivity.class)); break;

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
    // --- save states ----------------------------------------------------

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

    /**
     * Nothing to look at, nothing to run: a Spectrum in the background is a
     * Spectrum burning the battery. The automatic pause is kept apart from the
     * user's so that coming back does not undo one they asked for.
     */
    @Override
    protected void onPause() {
        super.onPause();
        machine.remember();

        InputManager input = getSystemService(InputManager.class);
        if (input != null) input.unregisterInputDeviceListener(devices);

        panels.unwatch();

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
        panels.close();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getApplication().unregisterActivityLifecycleCallbacks(panels.lifecycle());
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

        machine.start();
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

}
