package dev.ldlab.zedex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
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
        applyScale();
        applyControls();
        applyFullscreen();

        revealQuickBar();
        layout.setTemplate(EmulatorLayout.Template.of(
                preferences.getString(SettingsActivity.KEY_LANDSCAPE_LAYOUT, null)));

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

            if (imeSeen && keyboardSkin() == SpectrumKeyboardView.Skin.SYSTEM
                    && layout.keyboardVisible() != visible) {
                preferences.edit()
                        .putBoolean(SettingsActivity.KEY_KEYBOARD, visible).apply();
                layout.setKeyboardVisible(visible);
            }

            return insets;
        });

        // Posted: there is no window to show an input method for until the
        // activity has one.
        layout.post(this::applySystemKeyboard);

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

    /**
     * Tells {@link Controls} which keys the profile holds and whether the pad is
     * one of Fuse's interfaces or a set of keys, then redraws the three buttons,
     * which are labelled with whatever they now send.
     *
     * Everything a control does goes through that one class, so this is the whole
     * of applying a profile - and the same call is what a physical gamepad will
     * need when there is one.
     */
    private void applyControls() {
        Controls.setProfile(ControlProfiles.current(preferences).keys);
        Controls.setPadSendsKeys(joystickType() == Controls.JOYSTICK_KEYBOARD);

        layout.refreshControls();
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
        applyScale();
        applyControls();
        applyMouse();

        // Connecting or disconnecting one while the app was away.
        InputManager input = getSystemService(InputManager.class);
        if (input != null) input.registerInputDeviceListener(devices, null);
        applyGamepad();

        pausedByAndroid = false;
        applyPause();

        // Coming back from somewhere else: if the device's keyboard is the one
        // chosen, it went away with the app and should come back with it.
        applySystemKeyboard();
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
            applySystemKeyboard();
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

        bar.addGroup(R.drawable.ic_folder, getString(R.string.menu_files),
                     this::fillFilesBar);
        // The machine second here as in the sheet, since it holds pause and the
        // two menus reading the same way is worth more than either order is.
        bar.addGroup(R.drawable.ic_chip, getString(R.string.menu_machine_group),
                     this::fillMachineBar);
        bar.addGroup(R.drawable.ic_camera, getString(R.string.menu_capture),
                     this::fillCaptureBar);
        // Display rather than Controls: the group is what is on the screen
        // beside the picture, and one of the three is not a control at all.
        bar.addGroup(R.drawable.ic_display, getString(R.string.menu_display),
                     this::fillControlsBar);

        bar.addHold(R.drawable.ic_fast_forward, getString(R.string.fast_forward),
                    () -> fastForward(true), () -> fastForward(false));

        fullscreenAction = bar.addAction(R.drawable.ic_fullscreen,
                                         getString(R.string.fullscreen_enter),
                                         () -> showFullscreen(!fullscreen()));

        bar.addAction(R.drawable.ic_menu, getString(R.string.menu_button),
                      () -> menu.open());

        return bar;
    }

    /** Reset is here rather than on the bar itself: it asks first, and an icon
     *  that throws the game away wants a word beside it. */
    /**
     * Everything that is a file: what to open, and the states either way.
     *
     * Grouped rather than three icons of their own because the bar is a column
     * sideways and a column has a height to fit into - and because a folder, a
     * disk going down and a disk coming up are three pictures that have to be
     * learned, while a list says which is which.
     */
    private void fillFilesBar(QuickBar bar) {
        bar.addToRow(R.drawable.ic_folder, getString(R.string.menu_open),
                     this::pickFile);
        bar.addToRow(R.drawable.ic_save, getString(R.string.menu_save_state),
                     () -> showStateDialog(true));
        bar.addToRow(R.drawable.ic_load, getString(R.string.menu_load_state),
                     () -> showStateDialog(false));
    }

    private void fillMachineBar(QuickBar bar) {
        boolean paused = isPaused();

        // Built when the group is opened, so it says which way round it is
        // without anything having to keep it up to date.
        bar.addToRow(paused ? R.drawable.ic_play : R.drawable.ic_pause,
                     getString(paused ? R.string.pause_resume
                                      : R.string.pause_pause),
                     () -> pause(!pausedByUser));
        bar.addToRow(R.drawable.ic_swap, withMachine(R.string.menu_machine),
                     this::showMachineDialog);
        bar.addToRow(R.drawable.ic_reset, getString(R.string.menu_reset),
                     this::confirmReset);
        // No confirming, unlike reset: the magic button interrupts the machine
        // rather than throwing its state away, and half of what it is for is
        // pressing it at a particular moment.
        bar.addToRow(R.drawable.ic_bolt, getString(R.string.menu_nmi), this::nmi);
    }

    /** The magic button of the real hardware; what it does is the machine's. */
    private void nmi() {
        FuseNative.nmi();
        note(R.string.nmi_done);
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
     * The three toggles, named for what they would do rather than for what they
     * are, since an icon that means "joystick" cannot also say which way it is
     * about to go.
     *
     * The lamps are here rather than only in the settings because whether they
     * are worth their strip is a decision of the moment - watching a tape load
     * wants them, playing the game afterwards does not - and it is the same kind
     * of decision as the other two. Both places write the same preference.
     */
    private void fillControlsBar(QuickBar bar) {
        boolean pad = layout.joystickVisible();
        boolean keys = layout.keyboardVisible();
        boolean lamps = layout.lightsVisible();

        bar.addToRow(R.drawable.ic_joystick,
                     getString(pad ? R.string.quick_joystick_hide
                                   : R.string.quick_joystick_show),
                     () -> showJoystick(!pad));
        bar.addToRow(R.drawable.ic_keyboard,
                     getString(keys ? R.string.quick_keyboard_hide
                                    : R.string.quick_keyboard_show),
                     () -> showKeyboard(!keys));
        bar.addToRow(R.drawable.ic_indicators,
                     getString(lamps ? R.string.quick_lights_hide
                                     : R.string.quick_lights_show),
                     () -> showLights(!lamps));

        // And what the picture itself looks like. The two switches are named for
        // what they would do and the two choosers for what is chosen, which is
        // the rule everywhere else: an icon cannot say which way it is going.
        boolean scanlines = preferences.getBoolean(SettingsActivity.KEY_SCANLINES,
                                                   false);
        boolean crt = preferences.getBoolean(SettingsActivity.KEY_CRT, false);

        bar.addToRow(R.drawable.ic_scanlines,
                     getString(scanlines ? R.string.quick_scanlines_off
                                         : R.string.quick_scanlines_on),
                     () -> switchFilter(SettingsActivity.KEY_SCANLINES, !scanlines,
                                        !scanlines ? R.string.quick_scanlines_on
                                                   : R.string.quick_scanlines_off));
        bar.addToRow(R.drawable.ic_crt,
                     getString(crt ? R.string.quick_crt_off : R.string.quick_crt_on),
                     () -> switchFilter(SettingsActivity.KEY_CRT, !crt,
                                        !crt ? R.string.quick_crt_on
                                             : R.string.quick_crt_off));
        bar.addToRow(R.drawable.ic_signal,
                     getString(R.string.quick_video, videoName()),
                     this::nextVideo);
        bar.addToRow(R.drawable.ic_border,
                     getString(R.string.quick_border,
                               getString(Border.of(preferences).title)),
                     this::nextBorder);
    }

    /** One of the two picture switches, written and pushed like the settings do. */
    private void switchFilter(String key, boolean on, int said) {
        preferences.edit().putBoolean(key, on).apply();
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
     * It fades in fullscreen and nowhere else. Everywhere else it has a place of
     * its own that is not over the picture - a strip at the top in portrait, the
     * black corner in landscape - so there is nothing to be gained by taking it
     * away and something to be lost: a control that is always there needs no
     * discovering. Any tap on the picture still calls this, which in fullscreen
     * is how the bar is got back.
     */
    private void revealQuickBar() {
        if (panelUp) return;

        barWanted = true;

        quickBar.removeCallbacks(fadeQuickBar);
        quickBar.animate().cancel();
        quickBar.setAlpha(1f);
        quickBar.setVisibility(View.VISIBLE);

        if (fullscreen()) quickBar.postDelayed(fadeQuickBar, BAR_LINGER_MS);
    }

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
     * The bar loses its strip and the keyboard goes away in landscape, where it
     * is worth nearly half the window; in portrait a 4:3 picture is limited by the
     * width, so the keyboard costs it nothing and stays. Getting out is the same
     * icon: a tap on the picture brings the bar back for three seconds, which is
     * long enough to press it.
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
            // The machine second: what is running is asked about more often than
            // anything filed away, and it holds pause.
            sheet.addSubmenu(withMachine(R.string.menu_machine_group),
                             R.drawable.ic_chip, this::fillMachine);
            sheet.addSubmenu(getString(R.string.menu_states), R.drawable.ic_bookmark,
                             this::fillStates);
            sheet.addSubmenu(getString(R.string.menu_pokes), R.drawable.ic_poke,
                             this::fillPokes);
            // The page's own heading, which sits over the tape rows: the
            // drives that follow have DRIVES of their own.
            sheet.addSubmenu(getString(R.string.menu_media),
                             getString(R.string.menu_tape_section),
                             R.drawable.ic_tape, this::fillMedia);
            sheet.addSubmenu(getString(R.string.menu_capture), R.drawable.ic_camera,
                             this::fillCapture);

            sheet.addRule();
            sheet.addSubmenu(getString(R.string.menu_controls),
                             R.drawable.ic_controls, this::fillControls);
            sheet.addItem(getString(R.string.menu_settings), R.drawable.ic_settings,
                    () -> startActivity(new Intent(this, SettingsActivity.class)));
            sheet.addItem(getString(R.string.menu_about, version()),
                          R.drawable.ic_info,
                          () -> startActivity(new Intent(this, AboutActivity.class)));

            sheet.addRule();
            sheet.addItem(getString(R.string.menu_quit), R.drawable.ic_quit,
                          this::quit);
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
        sheet.addSubmenu(getString(R.string.menu_mouse), R.drawable.ic_mouse,
                         this::fillMouse);
        // Not under Joystick…, which is about what the pad sends the *machine*.
        // These are what a controller asks of the app, and they work whether the
        // pad is a joystick or a set of keys.
        sheet.addItem(getString(R.string.menu_gamepad), R.drawable.ic_controls,
                      () -> GamepadActivity.open(this));
    }

    /**
     * The Kempston mouse, which is a mode rather than a peripheral you forget
     * about: while it is on the pad and the stick move the pointer instead of
     * the joystick, so the page says so rather than leaving it to be discovered.
     */
    private void fillMouse(MenuDrawer sheet) {
        boolean on = Mouse.enabled();

        sheet.addItem(getString(on ? R.string.mouse_off : R.string.mouse_on),
                      on ? R.drawable.ic_hide : R.drawable.ic_mouse,
                      () -> showMouse(!on));
        sheet.addItem(getString(R.string.mouse_sensitivity,
                                SettingsActivity.SettingsFragment.number(
                                        preferences,
                                        SettingsActivity.KEY_MOUSE_SENSITIVITY,
                                        100)),
                      R.drawable.ic_swap, this::showMouseSensitivityDialog);
        sheet.addNote(getString(R.string.mouse_explain));
    }

    private void showMouse(boolean on) {
        preferences.edit().putBoolean(SettingsActivity.KEY_MOUSE, on).apply();
        Mouse.setEnabled(on);

        note(on ? R.string.mouse_on_done : R.string.mouse_off_done);
    }

    private void showMouseSensitivityDialog() {
        String[] names = getResources().getStringArray(R.array.percent_names);
        String[] values = getResources().getStringArray(R.array.percent_values);

        int now = SettingsActivity.SettingsFragment.number(
                preferences, SettingsActivity.KEY_MOUSE_SENSITIVITY, 100);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (Integer.parseInt(values[i]) == now) checked = i;
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.mouse_sensitivity_title)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    preferences.edit()
                            .putString(SettingsActivity.KEY_MOUSE_SENSITIVITY,
                                       values[which])
                            .apply();
                    Mouse.apply(preferences);

                    dialog.dismiss();
                    note(R.string.mouse_sensitivity, Integer.parseInt(values[which]));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void fillKeyboard(MenuDrawer sheet) {
        boolean shown = layout.keyboardVisible();

        sheet.addItem(getString(shown ? R.string.control_hide
                                      : R.string.control_show),
                      shown ? R.drawable.ic_hide : R.drawable.ic_show,
                      () -> showKeyboard(!shown));
        sheet.addItem(getString(R.string.keyboard_skin, keyboardSkin().title),
                      R.drawable.ic_picture, this::showSkinDialog);
        sheet.addItem(getString(R.string.menu_layout), R.drawable.ic_layout,
                      this::showLayoutDialog);
    }

    /**
     * Brings the device's own keyboard up, or puts it away, to match the skin
     * and whether the keyboard is meant to be showing at all.
     *
     * The other two skins are drawn by the app and need none of this; this one is
     * Android's, so showing it is asking for it and hiding it is asking it to go.
     */
    private void applySystemKeyboard() {
        boolean wanted = keyboardSkin() == SpectrumKeyboardView.Skin.SYSTEM
                      && layout.keyboardVisible();

        if (wanted) layout.systemKeyboard().open();
        else layout.systemKeyboard().close();
    }

    private SpectrumKeyboardView.Skin keyboardSkin() {
        return SpectrumKeyboardView.Skin.of(
                preferences.getString(SettingsActivity.KEY_KEYBOARD_SKIN, null));
    }

    /**
     * Which machine's keyboard is drawn.
     *
     * A picture and where its keys are, nothing more: the 128K's plate has keys
     * the 48K's rubber one does not, and they reach the machine the way the real
     * ones did - TRUE VIDEO is CAPS SHIFT and 3, and most of the others turn out
     * to be single keys Fuse already knows.
     */
    private void showSkinDialog() {
        SpectrumKeyboardView.Skin[] skins = SpectrumKeyboardView.Skin.values();
        String[] names = new String[skins.length];
        int checked = 0;

        for (int i = 0; i < skins.length; i++) {
            names[i] = skins[i].title;
            if (skins[i] == keyboardSkin()) checked = i;
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.keyboard_skin_title)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    preferences.edit()
                            .putString(SettingsActivity.KEY_KEYBOARD_SKIN,
                                       skins[which].value)
                            .apply();
                    layout.setKeyboardSkin(skins[which]);
                    dialog.dismiss();

                    // After the dialog has gone, and posted: an input method is
                    // only shown for the window that has the focus, and while
                    // this dialog still had it the request was quietly dropped.
                    layout.post(this::applySystemKeyboard);
                    note(R.string.keyboard_skin_set, skins[which].title);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showKeyboard(boolean shown) {
        layout.setKeyboardVisible(shown);
        applySystemKeyboard();
        preferences.edit().putBoolean(SettingsActivity.KEY_KEYBOARD, shown).apply();

        note(shown ? R.string.keyboard_shown : R.string.keyboard_hidden);
    }

    /**
     * Pokes: one to try now, and the ones worth keeping.
     *
     * A stored poke is a thing to press - nothing is applied by being on the
     * list - so loading a game and pressing it is the whole flow, and the same
     * poke survives a reset without being typed again. Long press removes one,
     * because a row whose tap means "use this" cannot also mean "throw this
     * away".
     */
    private void fillPokes(MenuDrawer sheet) {
        sheet.addItem(getString(R.string.poke_quick), R.drawable.ic_poke,
                      this::showQuickPokeDialog);
        sheet.addItem(getString(R.string.poke_add), R.drawable.ic_plus,
                      this::showAddPokeDialog);

        List<Pokes.Poke> pokes = Pokes.all(preferences);

        sheet.addRule();
        sheet.addSection(getString(R.string.poke_stored));

        if (pokes.isEmpty()) {
            sheet.addNote(getString(R.string.poke_none));
            return;
        }

        for (int i = 0; i < pokes.size(); i++) {
            Pokes.Poke poke = pokes.get(i);
            int index = i;

            sheet.addItem(poke.name + "\n" + poke.numbers(), R.drawable.ic_poke,
                          () -> applyPoke(poke),
                          () -> confirmForgetPoke(index, poke));
        }

        sheet.addNote(getString(R.string.poke_hint));
    }

    private void applyPoke(Pokes.Poke poke) {
        FuseNative.poke(poke.address, poke.value);
        note(R.string.poke_done, poke.numbers());
    }

    /** Two numbers, applied and forgotten: for a poke being tried out. */
    private void showQuickPokeDialog() {
        EditText address = numberField(R.string.poke_address);
        EditText value = numberField(R.string.poke_value);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(pokePadding(), 0, pokePadding(), 0);
        fields.addView(address);
        fields.addView(value);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.poke_quick)
                .setMessage(R.string.poke_explain)
                .setView(fields)
                .setPositiveButton(R.string.poke_apply, (dialog, which) -> {
                    int where = Pokes.number(address.getText().toString(), 0xffff);
                    int what = Pokes.number(value.getText().toString(), 0xff);

                    if (where < 0 || what < 0) {
                        note(R.string.poke_bad);
                        return;
                    }

                    applyPoke(new Pokes.Poke("", where, what));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The same two numbers and a name, kept for next time. */
    private void showAddPokeDialog() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.poke_name);
        name.setText(preferences.getString(PREF_MEDIA_NAME, ""));
        name.setSelection(name.getText().length());

        EditText address = numberField(R.string.poke_address);
        EditText value = numberField(R.string.poke_value);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(pokePadding(), 0, pokePadding(), 0);
        fields.addView(name);
        fields.addView(address);
        fields.addView(value);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.poke_add)
                .setView(fields)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int where = Pokes.number(address.getText().toString(), 0xffff);
                    int what = Pokes.number(value.getText().toString(), 0xff);

                    if (where < 0 || what < 0) {
                        note(R.string.poke_bad);
                        return;
                    }

                    String called = sanitise(name.getText().toString());
                    if (called.isEmpty()) called = getString(R.string.poke_unnamed);

                    Pokes.add(preferences, called, where, what);
                    note(R.string.poke_stored_one, called);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmForgetPoke(int index, Pokes.Poke poke) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.poke_forget_ask, poke.name))
                .setMessage(poke.numbers())
                .setPositiveButton(R.string.poke_forget, (dialog, which) -> {
                    Pokes.remove(preferences, index);
                    note(R.string.poke_forgotten, poke.name);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Decimal by habit, hex if it is written as hex; see Pokes.number(). */
    private EditText numberField(int hint) {
        EditText field = new EditText(this);

        field.setSingleLine(true);
        field.setHint(hint);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT);

        return field;
    }

    private int pokePadding() {
        return Math.round(12 * getResources().getDisplayMetrics().density);
    }

    private void fillStates(MenuDrawer sheet) {
        sheet.addItem(getString(R.string.menu_save_state), R.drawable.ic_save,
                      () -> showStateDialog(true));
        sheet.addItem(getString(R.string.menu_load_state), R.drawable.ic_load,
                      () -> showStateDialog(false));
    }

    private void fillMachine(MenuDrawer sheet) {
        sheet.addItem(withMachine(R.string.menu_machine), R.drawable.ic_swap,
                      this::showMachineDialog);

        sheet.addRule();
        sheet.addItem(getString(pausedByUser ? R.string.pause_resume
                                             : R.string.pause_pause),
                      pausedByUser ? R.drawable.ic_play : R.drawable.ic_pause,
                      () -> pause(!pausedByUser));
        sheet.addItem(getString(R.string.menu_reset), R.drawable.ic_reset,
                      this::confirmReset);
        sheet.addItem(getString(R.string.menu_nmi), R.drawable.ic_bolt, this::nmi);
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
        sheet.addItem(getString(R.string.joystick_profile,
                                ControlProfiles.current(preferences).name),
                      R.drawable.ic_bookmark, this::showProfileDialog);
        sheet.addItem(getString(R.string.joystick_auto_hide,
                                getString(autoHide() ? R.string.on : R.string.off)),
                      R.drawable.ic_hide, () -> setAutoHide(!autoHide()));
    }

    /** Whether the on-screen pad steps aside for a real controller. */
    private boolean autoHide() {
        return preferences.getBoolean(SettingsActivity.KEY_JOYSTICK_AUTO_HIDE, true);
    }

    private void setAutoHide(boolean on) {
        preferences.edit()
                .putBoolean(SettingsActivity.KEY_JOYSTICK_AUTO_HIDE, on).apply();

        applyGamepad();
        note(on ? R.string.joystick_auto_hide_on : R.string.joystick_auto_hide_off);
    }

    /**
     * Takes the on-screen pad away while a controller is plugged in, if that is
     * wanted.
     *
     * Separate from the user's own Show on screen rather than writing to it: a
     * pad unplugged should bring the controls back, and it cannot if plugging one
     * in threw the setting away.
     */
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

    private void applyMouse() {
        Mouse.apply(preferences);
        Mouse.setEnabled(preferences.getBoolean(SettingsActivity.KEY_MOUSE, false));
    }

    private void applyGamepad() {
        layout.setJoystickSuppressed(autoHide() && Gamepad.connected());
        gamepad.setHotkeys(Hotkeys.load(preferences));
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

            case QUICK_SAVE: save(QUICK_STATE); break;
            case QUICK_LOAD: quickLoad(); break;
            case SAVE_STATE: showStateDialog(true); break;
            case LOAD_STATE: showStateDialog(false); break;

            case SPEED_UP: stepSpeed(1); break;
            case SPEED_DOWN: stepSpeed(-1); break;

            case FULLSCREEN: showFullscreen(!fullscreen()); break;
            case SCREENSHOT: takeScreenshot(); break;
            case RECORD:
                if (Recorder.isRecording()) Recorder.stop();
                else startRecording(Recorder.Format.GIF);
                break;

            case KEYBOARD: showKeyboard(!layout.keyboardVisible()); break;
            case JOYSTICK: showJoystick(!layout.joystickVisible()); break;
            case INDICATORS: showLights(!layout.lightsVisible()); break;
            case NEXT_PROFILE: nextKeyProfile(); break;
            case NEXT_JOYSTICK: nextJoystickType(); break;

            case MENU: menu.open(); break;
            case QUICK_BAR: revealQuickBar(); break;
            case SETTINGS: startActivity(new Intent(this, SettingsActivity.class)); break;

            default: break;
        }
    }

    /**
     * How far a finger may wander before it is a mouse drag rather than a tap,
     * in dp. Android's own touch slop is about eight; a finger aiming at a tap
     * moves further than that on a phone held in one hand.
     */
    private static final int MOUSE_SLOP = 10;

    /** The one state a hotkey writes, and writes over. */
    private static final String QUICK_STATE = "Quick";

    private void quickLoad() {
        for (SavedState state : savedStates()) {
            if (state.name.equals(QUICK_STATE)) {
                load(state);
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

    private void nextKeyProfile() {
        List<ControlProfiles.Profile> all = ControlProfiles.all(preferences);
        int next = (ControlProfiles.currentIndex(preferences) + 1) % all.size();

        ControlProfiles.store(preferences, all, next);
        applyControls();
        note(R.string.profile_set, all.get(next).name);
    }

    /**
     * Round Fuse's list of interfaces and then Keyboard, in the order the
     * chooser offers them - so the stored value has to be turned into that
     * position and back, since Keyboard is a number of our own rather than the
     * one after Fuse's last.
     */
    private void nextJoystickType() {
        String[] fuseTypes = FuseNative.joystickTypeNames();
        if (fuseTypes.length == 0) return;

        int type = joystickType();
        int at = type == Controls.JOYSTICK_KEYBOARD ? fuseTypes.length : type;
        int next = (at + 1) % (fuseTypes.length + 1);
        int chosen = next == fuseTypes.length ? Controls.JOYSTICK_KEYBOARD : next;

        preferences.edit().putInt(SettingsActivity.KEY_JOYSTICK_TYPE, chosen).apply();
        setJoystickType(chosen);

        note(R.string.joystick_type_set, next == fuseTypes.length
                ? getString(R.string.joystick_keyboard) : fuseTypes[next]);
    }

    /**
     * Which set of keys the controls send, and the way in to editing them.
     *
     * A list of profiles with the editor and New on the dialog's own buttons,
     * rather than as more rows: choosing a profile and changing one are
     * different kinds of thing, and a row that opens a screen sitting among rows
     * that switch a setting reads as another profile at a glance.
     */
    private void showProfileDialog() {
        List<ControlProfiles.Profile> profiles = ControlProfiles.all(preferences);
        String[] names = new String[profiles.size()];

        for (int i = 0; i < names.length; i++) names[i] = profiles.get(i).name;

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.profile_title)
                .setSingleChoiceItems(names, ControlProfiles.currentIndex(preferences),
                        (dialog, which) -> {
                            ControlProfiles.store(preferences, profiles, which);
                            applyControls();

                            dialog.dismiss();
                            note(R.string.profile_set, names[which]);
                        })
                .setPositiveButton(R.string.profile_edit,
                        (dialog, which) -> ProfileActivity.open(this))
                .setNeutralButton(R.string.profile_new,
                        (dialog, which) -> showNewProfileDialog())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A new profile starts as a copy of the one in use, and becomes the one in
     *  use: it is being made because the current keys are nearly right. */
    private void showNewProfileDialog() {
        EditText field = new EditText(this);
        field.setHint(R.string.profile_new_name);
        field.setSingleLine(true);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.profile_new_title)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) return;

                    List<ControlProfiles.Profile> profiles =
                            ControlProfiles.all(preferences);

                    profiles.add(new ControlProfiles.Profile(
                            name, ControlProfiles.current(preferences).keys));
                    ControlProfiles.store(preferences, profiles, profiles.size() - 1);

                    applyControls();
                    ProfileActivity.open(this);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showJoystick(boolean shown) {
        layout.setJoystickVisible(shown);
        preferences.edit().putBoolean(SettingsActivity.KEY_JOYSTICK, shown).apply();

        note(shown ? R.string.joystick_shown : R.string.joystick_hidden);
    }

    /**
     * Fuse's own interfaces, and Keyboard after them.
     *
     * Keyboard is not an interface at all: the machine has nothing plugged in
     * and the pad presses keys, which is how a great many games that predate
     * the joystick interfaces are played. It is offered in the same list because
     * from the pad's side it is the same choice.
     */
    private void showJoystickTypeDialog() {
        String[] fuseTypes = FuseNative.joystickTypeNames();
        if (fuseTypes.length == 0) return;

        String[] names = new String[fuseTypes.length + 1];
        System.arraycopy(fuseTypes, 0, names, 0, fuseTypes.length);
        names[fuseTypes.length] = getString(R.string.joystick_keyboard);

        int type = joystickType();
        int checked = type == Controls.JOYSTICK_KEYBOARD ? fuseTypes.length : type;

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.joystick_type_title)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    int chosen = which == fuseTypes.length
                            ? Controls.JOYSTICK_KEYBOARD : which;

                    preferences.edit()
                            .putInt(SettingsActivity.KEY_JOYSTICK_TYPE, chosen)
                            .apply();
                    setJoystickType(chosen);

                    dialog.dismiss();
                    note(R.string.joystick_type_set, names[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Nothing plugged in for Keyboard, since the pad sends keys instead. */
    private void setJoystickType(int type) {
        FuseNative.setJoystickType(type == Controls.JOYSTICK_KEYBOARD
                ? Controls.JOYSTICK_NONE : type);
        applyControls();
    }

    /** The stored type, or Kempston; never an index Fuse would not recognise. */
    private int joystickType() {
        int stored = preferences.getInt(SettingsActivity.KEY_JOYSTICK_TYPE,
                                        DEFAULT_JOYSTICK_TYPE);
        int count = FuseNative.joystickTypeNames().length;

        if (stored == Controls.JOYSTICK_KEYBOARD) return stored;

        return stored >= 0 && (count == 0 || stored < count)
                ? stored : DEFAULT_JOYSTICK_TYPE;
    }

    private String joystickTypeName() {
        int type = joystickType();
        if (type == Controls.JOYSTICK_KEYBOARD) {
            return getString(R.string.joystick_keyboard);
        }

        String[] names = FuseNative.joystickTypeNames();
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
    private void playTape(boolean playing) {
        FuseNative.tapePlay(playing);
        note(playing ? R.string.tape_playing : R.string.tape_stopped);
    }

    /**
     * The tape's blocks, and which one the deck is at.
     *
     * A single-choice list because that is what it is - the tape is at exactly
     * one block, and tapping another winds to it. Fuse's own browser is a
     * two-column table of type and details; libspectrum writes both into one
     * line here, which is what a phone-width row has room for.
     *
     * Built from the snapshot the emulation thread publishes, so a tape that is
     * playing does not have its list read out from under it.
     */
    private void showTapeBrowser() {
        String[] blocks = FuseNative.tapeBlocks();

        if (blocks == null || blocks.length == 0) {
            note(R.string.tape_no_blocks);
            return;
        }

        int current = Math.max(0, Math.min(blocks.length - 1, FuseNative.tapeBlock()));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.tape_browser_title)
                .setSingleChoiceItems(blocks, current, (dialog, which) -> {
                    FuseNative.tapeBlockSelect(which);
                    dialog.dismiss();
                    note(R.string.tape_wound, which + 1);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void rewindTape() {
        FuseNative.tapeRewind();
        note(R.string.tape_rewound);
    }

    private void fillMedia(MenuDrawer sheet) {
        // The deck's transport, and only while there is something on the tape:
        // Fuse's own play refuses an empty one, and a row that cannot do
        // anything is worse than no row.
        //
        // Stop rather than pause because that is what Fuse has, and it is the
        // same thing - the position is kept, so playing again carries on from
        // there. Rewind goes to the first block; there is no winding.
        boolean tape = FuseNative.hasTape();

        sheet.addItem(getString(R.string.tape_load), R.drawable.ic_folder,
                      this::pickFile);

        if (tape) {
            boolean playing = FuseNative.tapePlaying();

            sheet.addItem(getString(playing ? R.string.tape_stop
                                            : R.string.tape_play),
                          playing ? R.drawable.ic_stop : R.drawable.ic_play,
                          () -> playTape(!playing));
            sheet.addItem(getString(R.string.tape_rewind), R.drawable.ic_rewind,
                          this::rewindTape);
            sheet.addItem(getString(R.string.tape_browser), R.drawable.ic_tape,
                          this::showTapeBrowser);
            // Only with something on the tape: it used to be here always and
            // answered a tap with a toast saying there was nothing to write,
            // which is a row that exists to say it does not work.
            sheet.addItem(getString(R.string.tape_save), R.drawable.ic_save,
                          this::saveTape);
        }

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

    /**
     * Fuse picks the format from the extension, so choosing one is choosing
     * what to call the file. Typing .tap or .tzx yourself still wins; the
     * setting is only what happens when you do not.
     */
    private File tapeFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String wanted = preferences.getString(SettingsActivity.KEY_TAPE_FORMAT, "tap");
        String file = lower.endsWith(".tap") || lower.endsWith(".tzx")
                ? name : name + "." + wanted;

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
        String unsaved = modifiedDisks();

        if (unsaved == null) {
            quitNow();
            return;
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.quit_unsaved_title)
                .setMessage(getString(R.string.quit_unsaved, unsaved))
                .setPositiveButton(R.string.menu_quit, (dialog, which) -> quitNow())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
     * The drives holding changes that have not been written back, or null when
     * there are none. Fuse's own flag, asked the same way the Media page asks
     * it - the details arrive as name, disk, modified for each drive.
     */
    private String modifiedDisks() {
        String[] details = FuseNative.driveDetails();
        StringBuilder names = new StringBuilder();

        for (int i = 0; i + 2 < details.length; i += 3) {
            if (!"1".equals(details[i + 2])) continue;

            if (names.length() > 0) names.append(", ");
            names.append(details[i]);
        }

        return names.length() > 0 ? names.toString() : null;
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

        // A held direction has nobody to let go of it once we are not being sent
        // events any more.
        gamepad.releaseAll();

        pausedByAndroid = true;
        applyPause();
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
            applyGamepad();
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            gamepad.releaseAll();
            applyGamepad();
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
            applyGamepad();
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

        // Fuse's own word for it, passed straight through; see AY_STEREO.
        arguments.add("--separation");
        arguments.add(SettingsActivity.ayStereoName(preferences));
        value(arguments, SettingsActivity.KEY_BEEPER_VOLUME, 100, "volume-beeper");

        // The on-screen joystick is Fuse's joystick 1. Kempston is a type and
        // also a piece of hardware, and the port is only decoded when the
        // interface is there, so the two go together - see OPTION_JOYSTICK_TYPE
        // in android_bridge.c, which does the same when it is changed later.
        int joystick = joystickType();
        if (joystick == Controls.JOYSTICK_KEYBOARD) joystick = Controls.JOYSTICK_NONE;

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
        // A controller first: its buttons are not keys the machine has, and its
        // D-pad must not be mistaken for a keyboard's cursor keys, which the
        // machine does have.
        if (gamepad.key(event)) return true;

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
