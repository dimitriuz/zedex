package dev.ldlab.zedex;

import dev.ldlab.zedex.machine.Picture;
import dev.ldlab.zedex.machine.FuseSettings;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.work.Work;
import dev.ldlab.zedex.screen.Language;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.input.ControlProfiles;
import dev.ldlab.zedex.input.Gamepad;
import dev.ldlab.zedex.input.Hotkeys;
import dev.ldlab.zedex.input.Mouse;
import dev.ldlab.zedex.library.catalogue.Catalogues;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.machine.Video;
import dev.ldlab.zedex.machine.Border;
import dev.ldlab.zedex.machine.Filter;
import dev.ldlab.zedex.machine.Machine;
import dev.ldlab.zedex.media.Media;
import dev.ldlab.zedex.media.Recorder;
import dev.ldlab.zedex.menu.Capture;
import dev.ldlab.zedex.menu.ControlsUi;
import dev.ldlab.zedex.media.Music;
import dev.ldlab.zedex.menu.MusicUi;
import dev.ldlab.zedex.menu.SetupUi;
import dev.ldlab.zedex.menu.PokesUi;
import dev.ldlab.zedex.menu.StatesUi;
import dev.ldlab.zedex.screen.AboutActivity;
import dev.ldlab.zedex.screen.GameInfoActivity;
import dev.ldlab.zedex.screen.LibraryActivity;
import dev.ldlab.zedex.screen.Panels;
import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.screen.SecondScreen;
import dev.ldlab.zedex.screen.StartPanel;
import dev.ldlab.zedex.screen.StatesActivity;
import dev.ldlab.zedex.screen.WelcomeActivity;
import dev.ldlab.zedex.storage.Recents;
import dev.ldlab.zedex.storage.States;
import dev.ldlab.zedex.feedback.Crashes;
import dev.ldlab.zedex.feedback.Feedback;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.update.Updater;
import dev.ldlab.zedex.view.ActivityLights;
import dev.ldlab.zedex.view.EmulatorLayout;
import dev.ldlab.zedex.view.JoystickView;
import dev.ldlab.zedex.view.MenuDrawer;
import dev.ldlab.zedex.view.QuickBar;
import dev.ldlab.zedex.view.Rows;
import dev.ldlab.zedex.view.SpectrumKeyboardView;
import dev.ldlab.zedex.view.SystemKeyboardView;
import dev.ldlab.zedex.welcome.Tour;
import dev.ldlab.zedex.welcome.Coach;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Build;
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

    /** Every screen speaks the chosen language; see {@link Language}. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Language.wrap(base));
        language = Language.effectiveTag(this);
    }

    /**
     * What language {@link #attachBaseContext} built this screen with.
     *
     * The effective one, not the preference. The preference is empty whenever
     * the phone's own language is being followed, so comparing it saw no
     * change when the phone's language was what changed - and this screen is
     * not recreated for a locale either, since configChanges lists it. Every
     * other screen picked the new language up and this one kept the old words
     * until the process died.
     */
    private String language = "";

    private static final String PREFS = Prefs.PREFS;

    /**
     * Which entry of a zip to open, carried alongside the zip's own uri as
     * the intent's data - a zip entry has no uri of its own that SAF can
     * address, and a {@code file://} one is refused even to our own activity
     * with FileUriExposedException. See {@link Media#stageAndOpenEntry} and
     * docs/LIBRARY.md, "How a game is opened".
     */
    public static final String EXTRA_ZIP_ENTRY = "dev.ldlab.zedex.extra.ZIP_ENTRY";

    /**
     * The game's own path relative to the content tree, when the caller
     * already knows it - set only by {@link
     * dev.ldlab.zedex.screen.LibraryActivity#openGame}, and only when the
     * game came from the content tree directly rather than from inside a
     * zip, which has no such path of its own. A shortcut, not the only way
     * this is found: {@link #resolveLibraryPath} answers the same question
     * for every other way a game arrives here - a file manager's hand-over,
     * <em>Open recent…</em>, ES-DE's own {@code %ROMPROVIDER%} - by asking
     * {@code Metadata.resolve} instead, off the UI thread, since this extra
     * being absent means only "nobody has already done that work", not
     * "there is nothing to find". See that method's own comment for what it
     * hands to {@link #panels}.
     */
    public static final String EXTRA_LIBRARY_PATH = "dev.ldlab.zedex.extra.LIBRARY_PATH";

    /**
     * A game whose music to offer, by the store's own key.
     *
     * Sent by the library's own Music button. It carries no document, because
     * there is nothing to load: a tune is played by this screen, on this
     * machine, and whatever is already running is put aside for it - see
     * {@code media.Music}.
     */
    public static final String EXTRA_MUSIC = "dev.ldlab.zedex.extra.MUSIC";

    /**
     * Open the ☰ sheet on arrival.
     *
     * How the details screen's own menu icon works on a single screen: the
     * sheet is built over this activity's window and no second activity can
     * raise it, so that screen stands aside and asks for it instead. Removed
     * as it is read, like {@link #EXTRA_MUSIC} and for the same reason - this
     * activity is long-lived and the intent that started it is remembered, so
     * without that every return to the machine would reopen the menu.
     */
    public static final String EXTRA_OPEN_MENU = "dev.ldlab.zedex.extra.OPEN_MENU";


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

    /** Offers what a scraped record says about how to run a game. */
    private SetupUi setupUi;

    /** The game's own music, played by the machine itself. */
    private MusicUi music;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        // The other way into this app - a launcher shortcut, ES-DE, a file
        // handed over - so the one-time library migration is asked for here
        // too. Idempotent: a second call is one boolean read.
        SettingsActivity.migrateIfNeeded(this, preferences);

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
            public void opened(String name, Uri uri, String inside) {
                rememberMediaName(name);
                gameOpened(uri, inside);
            }
        });

        pokes = new PokesUi(this, preferences, new PokesUi.Host() {
            @Override
            public void note(int message, Object... arguments) {
                EmulatorActivity.this.note(message, arguments);
            }

            @Override
            public void noteText(String message) {
                EmulatorActivity.this.noteText(message);
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
                return Media.modifiedDisks();
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

        // In onCreate rather than as a field initialiser: those run first and
        // would be handed a null preferences. See CLAUDE.md.
        music = new MusicUi(this);

        setupUi = new SetupUi(this, new SetupUi.Host() {
            @Override
            public void reopenCurrentGame() {
                EmulatorActivity.this.reopenCurrentGame();
            }

            @Override
            public void note(int message, Object... arguments) {
                EmulatorActivity.this.note(message, arguments);
            }

            @Override
            public void chooseControl(int type,
                                      dev.ldlab.zedex.input.ControlProfiles.Profile layout) {
                controls.chooseControl(type, layout);
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

                // A panel that has gone takes the details side with it, and
                // the bar must not be left wearing four icons over a machine.
                applyBarMode();
            }

            @Override
            public void back() {
                // The same one the machine's own screen reaches, which is
                // safe to give the panel precisely because it never leaves:
                // see handleBack, which always consumes.
                handleBack();
            }
        });

        getApplication().registerActivityLifecycleCallbacks(panels.lifecycle());
        registerBackCallback();
        FuseNative.attach(this);

        // Before the folders are made, and before anything reads one: this
        // writes down where they are, so that granting a permission later
        // cannot move them out from under a hundred save states.
        Storage.pinRoot(this);
        Storage.createFolders(this);

        // The ROMs the app ships, before anything asks whether there are any -
        // except on the very first run, where the folder to put them in is the
        // question the panel is about to ask. Only ever the ones that are not
        // there already: the folder is the user's to fill as well as ours.
        // The demo, for an install that predates it. Once ever, so a tape
        // somebody has deleted stays deleted; and no offer, because
        // interrupting somebody who has been playing for a month to show them a
        // demo is not a welcome. Not on the very first run, where the folder to
        // put it in is the question the panel is about to ask.
        //
        // The ROMs are not unpacked here any more: startEmulator does it, on
        // every start, so that a data folder chosen at any point gets them.
        // Guarded on welcomeNeeded rather than unconditional: an install still
        // waiting on the wizard has not settled a folder yet, and installDemo
        // running here first would plant the tape in the pre-wizard default
        // root, mark KEY_DEMO_INSTALLED, and turn WelcomeActivity.finishSetup's
        // own installDemo into a no-op - so the tape sits somewhere other than
        // the folder DonePage just told the summary about. This is what the
        // old StartPanel.setupNeeded guard already did correctly, and calling
        // it unconditional here was a mistake this app made once.
        if (!Prefs.welcomeNeeded(this, preferences)) Storage.installDemo(this);

        // Asks GitHub whether there is a newer APK than this one, on a thread of
        // its own, and says nothing unless there is. Never for a Play install
        // and never for a debug build; see Updater.available.
        Updater.checkOnStart(this, preferences);

        // Process-wide, so it covers the other screens too. Nothing is sent: it
        // writes the last crash to a file, and the next start offers it.
        Crashes.watch(this);
        Feedback.offerLastCrash(this);

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
        machineTour = buildMachineTour();

        // The machine's own face, once there is a bar to put a face on. Not
        // up with the collaborators in onCreate, where this was first written
        // and crashed on every launch: the bar is built here, further down the
        // same method, and CLAUDE.md's rule about field initialisers is the
        // same rule one line finer - order inside onCreate counts too.
        applyBarMode();
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
        EmulatorLayout.Children children = new EmulatorLayout.Children();
        children.screen = screen;
        children.keyboard = new SpectrumKeyboardView(this);
        children.system = new SystemKeyboardView(this);
        children.pad = new JoystickView(this, JoystickView.Part.PAD);
        children.fire = new JoystickView(this, JoystickView.Part.FIRE);
        children.keys = keyButtons;
        children.overlay = overlayKeyboard();
        children.overlayOpen = overlayButton(R.drawable.ic_keyboard,
                                             R.string.overlay_open,
                                             () -> layout.setOverlayShown(true));
        children.overlayClose = overlayButton(R.drawable.ic_hide,
                                              R.string.overlay_close,
                                              () -> layout.setOverlayShown(false));
        children.lights = lights;
        children.play = playButton;
        children.panel = roms.view();
        children.bar = quickBar;
        children.drawer = menu;

        layout.setChildren(children);
        layout.setJoystickVisible(
                preferences.getBoolean(Prefs.KEY_JOYSTICK, true));
        layout.setKeyboardVisible(
                preferences.getBoolean(Prefs.KEY_KEYBOARD, true));
        layout.setLightsVisible(
                preferences.getBoolean(Prefs.KEY_INDICATORS, true));

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

            /*
             * What the window is allowed to use, which is not all of it.
             *
             * An app targeting API 35 is laid out into the display cutout
             * whether it asks or not: the mode that used to letterbox the
             * window away from a camera hole is read as "always" now, so the
             * quick bar's icons ended up underneath one. The bars are hidden
             * here and report nothing, which is what makes asking for both
             * safe - it comes out as the cutout alone, and as zero on a device
             * without one.
             */
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());

            layout.setInsets(
                    visible ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0,
                    insets.getInsets(WindowInsets.Type.mandatorySystemGestures())
                          .bottom,
                    safe.left, safe.top, safe.right, safe.bottom);

            if (visible) imeSeen = true;

            if (imeSeen && controls.keyboardSkin() == SpectrumKeyboardView.Skin.SYSTEM
                    && layout.keyboardVisible() != visible) {
                preferences.edit()
                        .putBoolean(Prefs.KEY_KEYBOARD, visible).apply();
                layout.setKeyboardVisible(visible);
            }

            return insets;
        });

        // Posted: there is no window to show an input method for until the
        // activity has one.
        layout.post(controls::applySystemKeyboard);

        handleViewIntent(getIntent());
        handleMusicIntent(getIntent());
        handleMenuIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
        handleMusicIntent(intent);
        handleMenuIntent(intent);
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
        Picture.applyBorder(preferences);

        layout.setScale(Picture.scale(preferences, false),
                        Picture.scale(preferences, true));
        Picture.applyScale(this, preferences);
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
     * Opens media handed to us by a file manager, by `am start -a VIEW`, or by
     * the library - see {@link dev.ldlab.zedex.screen.LibraryActivity}.
     *
     * A plain file is the data alone, exactly as it has always worked. An
     * entry inside a zip carries {@link #EXTRA_ZIP_ENTRY} beside it, since the
     * data is the archive's own uri and the extra is the one thing it cannot
     * say by itself: which entry, of however many, is the one to load.
     */
    private void handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;

        Uri uri = intent.getData();
        if (uri == null) return;

        String inside = intent.getStringExtra(EXTRA_ZIP_ENTRY);

        // Whatever the panel was showing belonged to the last game; it is
        // filled in again from gameOpened once this one is actually open.
        panels.setGameInfo(null, null);

        // Safe before Fuse has started: the command simply waits in the queue
        // until the emulation thread drains it.
        if (inside != null) {
            Work.run("open-entry", () -> media.stageAndOpenEntry(uri, inside));
        } else {
            Work.run("open", () -> media.stageAndOpen(uri));
        }
    }

    /**
     * The library asking for a game's music.
     *
     * Opens the sheet on the tune list rather than playing anything: a file
     * usually holds several and the person tapped "music", not a tune. Posted
     * because the sheet cannot be shown before the window it lives in has
     * been laid out, which on a cold start is after this runs.
     */
    /** See {@link #EXTRA_OPEN_MENU}. Posted for the same reason the music
     *  sheet is: on a cold start the window it lives in has not been laid out
     *  when this runs. */
    private void handleMenuIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_MENU, false)) return;

        intent.removeExtra(EXTRA_OPEN_MENU);
        layout.post(() -> menu.open());
    }

    private void handleMusicIntent(Intent intent) {
        if (intent == null) return;

        String path = intent.getStringExtra(EXTRA_MUSIC);
        if (path == null) return;

        // Once only: this activity is long-lived and the intent that started
        // it is remembered, so without this every return to the screen would
        // reopen the menu over whatever the person was doing.
        intent.removeExtra(EXTRA_MUSIC);

        music.forGame(path);
        layout.post(() -> menu.go(getString(R.string.music_title), music.page()));
    }

    /**
     * A game is open, whichever way in it came.
     *
     * <b>The one place all of them meet.</b> A file manager's hand-over, the
     * library, ES-DE, the picker, <em>Open recent…</em> - four routes and only
     * one of them is an {@code ACTION_VIEW} intent. The setup question used to
     * hang off the intent path alone, so opening the same game from the recent
     * list was never asked about; and both halves of what is known here - what
     * the panel shows and what the question is asked about - are the same
     * answer to the same question, so they are worked out once.
     *
     * Called from the staging thread, after the file has actually been opened
     * rather than while it is on its way: {@link #queryDisplayName} is a
     * provider round trip and {@code Metadata.resolve} is a file parse,
     * neither safe on the UI thread, and a game that failed to open should not
     * be asked about.
     *
     * A zip entry has no path of its own in the store, so nothing is looked up
     * for one - the library's own rows offer no details for an entry either.
     * A miss is the ordinary answer besides: most of what opens this app has
     * nothing to do with the library, and most of a collection is unscraped.
     *
     * @param uri    the document opened, which for an entry is its archive
     * @param inside the entry within it, or null for a plain file
     */
    private void gameOpened(Uri uri, String inside) {
        // Kept so that SetupUi can open the same thing again: applying a
        // machine from a scraped record resets Fuse, which throws away
        // whatever was loaded. See SetupUi.apply.
        openedUri = uri;
        openedInside = inside;

        // Cleared rather than left. An entry inside a zip returns below
        // without ever working a path out, so both of these would otherwise
        // still describe the game before it - which is what the details bar
        // is told (setGameInfo(null, null)) and what reopenOnNewMachine would
        // name the wrong game by.
        openedPath = null;
        hasManual = false;

        if (inside != null) {
            runOnUiThread(() -> panels.setGameInfo(null, null));
            return;
        }

        // The store does not read itself: every other screen that needs the
        // facts asks for them on a background thread, and this one never did
        // - so a game opened without the library having run first found an
        // empty store, and the setup question had nothing to ask about. It
        // looked exactly like an unscraped game. Here rather than in SetupUi
        // because this is the background thread; the parse must not happen on
        // the UI one.
        Context app = getApplicationContext();
        Metadata.ensureLoaded(app);

        String known = libraryPathFor(uri);
        String path = known != null ? known
                : Metadata.resolve(app, uri, queryDisplayName(uri));
        String shown = path == null ? null : filenameOf(path);

        // One more start, counted here because here is where all four ways in
        // meet - the library, a file manager's hand-over, ES-DE, and Open
        // recent - and because this is already the background thread the store
        // has to be written from. A game with no path in the store is a game
        // inside a zip or one the tree cannot name, and there is nothing to
        // count it against.
        if (path != null) Metadata.played(app, path);

        // Kept for the details bar, and resolved here because here is the
        // background thread: Artwork.manual walks the documents provider.
        openedPath = path;
        hasManual = path != null
                && dev.ldlab.zedex.library.meta.Artwork.manual(app, path) != null;

        runOnUiThread(() -> {
            panels.setGameInfo(path, shown);

            // The details icon appears now, or does not - see applyBarMode.
            applyBarMode();

            // The cheats page reads a scraped .pok beside the game, which it
            // can only find by the store's own key - see PokesUi.forGame.
            pokes.forGame(path);
            music.forGame(path);

            // Whatever was being listened to belonged to the last game, and
            // the machine it was saved from has just been replaced by this
            // one - so there is nothing to go back to any more.
            Music.forget(EmulatorActivity.this);

            setupUi.offer(path);
        });
    }

    /**
     * The path the library already knew, when this game is the one it started
     * us with.
     *
     * The library sends {@link #EXTRA_LIBRARY_PATH} beside the document
     * because it has the answer in hand, and asking the store again would be
     * a file parse for something already known. Matched on the document
     * rather than remembered in a field: the intent is the only thing that
     * says which game the extra belongs to, and by the time a second game has
     * been opened from the recent list it belongs to neither.
     */
    private String libraryPathFor(Uri uri) {
        Intent intent = getIntent();

        return intent != null && uri.equals(intent.getData())
                ? intent.getStringExtra(EXTRA_LIBRARY_PATH) : null;
    }

    /**
     * What is loaded, so it can be loaded again.
     *
     * Only {@link SetupUi} needs this, and only because changing the machine
     * resets the emulator: the file that was just opened has to be opened
     * again behind it or it silently disappears.
     *
     * Volatile because {@link #gameOpened} writes them on the staging thread
     * and the dialog reads them on the UI thread.
     */
    private volatile Uri openedUri;
    private volatile String openedInside;

    /**
     * Puts the loaded game back on the machine that has just been chosen by
     * hand.
     *
     * Changing machines resets Fuse, and the file that was loaded does not
     * survive it in any useful state: a disk stays in its drive but is never
     * booted, because Fuse only autoboots one at the moment it is inserted
     * (see {@code ui_drive_autoload}). So choosing Pentagon for a TR-DOS game
     * left the machine sitting at its own boot menu with the disk in the
     * drive and nothing happening, which reads as the machine change having
     * failed rather than as the game needing to be started again.
     *
     * Told not to ask on the way back: the answer remembered for this game is
     * what a hand choice is overriding, and replaying it would undo the
     * choice within the second.
     */
    private void reopenOnNewMachine() {
        setupUi.notAskingAbout(openedPath);
        reopenCurrentGame();
    }

    /** Opens whatever is loaded, again, exactly as it was opened. */
    private void reopenCurrentGame() {
        Uri uri = openedUri;
        if (uri == null) return;

        String inside = openedInside;

        if (inside != null) {
            Work.run("reopen-entry", () -> media.stageAndOpenEntry(uri, inside));
        } else {
            Work.run("reopen", () -> media.stageAndOpen(uri));
        }
    }

    /** The fallback shown before the store answers with a scraped name, or
     *  forever when it never does - see {@code GameInfoView.showEntry}. The
     *  path already carries it, as the segment after its last slash. */
    private static String filenameOf(String relativePath) {
        return dev.ldlab.zedex.storage.Storage.filename(relativePath);
    }

    /**
     * {@code document}'s own display name, from whichever provider gave it
     * to us - {@code Metadata.resolve}'s own fallback has nothing to match
     * against without it. Not every provider answers this column, or
     * answers at all, which is read the same as a miss rather than a crash.
     */
    private String queryDisplayName(Uri document) {
        try (Cursor cursor = getContentResolver().query(document, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception e) {
            // Nothing to show for it either way.
        }
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();

        // While this screen is up it is what Fuse's "the disk has been
        // modified" question is asked on. Registered here and dropped in
        // onPause, so a question raised with nothing on screen is answered
        // with cancel rather than left waiting - see FuseNative.onConfirmSave,
        // which is blocking the emulation thread while it waits.
        FuseNative.setConfirmer(this::askAboutSavingDisk);

        // Back from Settings, which may have changed the language. This screen
        // handles locale changes itself rather than being recreated for them
        // - see android:configChanges in the manifest - and its menus and
        // buttons were built with the words of the language that was chosen
        // when it opened, so the only way to change them is to build it again.
        if (!language.equals(Language.effectiveTag(this))) {
            recreate();
            return;
        }

        // Coming back from the page that allows this app to install packages.
        // Nothing happens unless the user went there for an update and has now
        // allowed it; see Updater.resumeIfAllowed.
        Updater.resumeIfAllowed(this);

        if (preferences.getBoolean(Prefs.KEY_KEEP_SCREEN_ON, true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // The wizard's own retry: this activity is singleInstance and stays
        // alive behind WelcomeActivity while it is up, and coming back to it
        // is a resume rather than a result. The same guard surfaceChanged
        // uses, for the same reason - started only ever flips true right
        // before machine.start(), so whichever of the two runs first wins and
        // the other's own call is a no-op.
        if (!started) startEmulator();

        // The settings screen can have changed these while we were away.
        layout.setLightsVisible(
                preferences.getBoolean(Prefs.KEY_INDICATORS, true));
        applyScale();
        controls.applyKeyboard();
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

        // The details side, and the way back from it. Two icons rather than
        // one that changes, because only one of them is ever on screen: the
        // bar wears one face over the machine and another over the details -
        // see QuickBar.showOnly and applyBarMode - so a single toggling button
        // would be a button whose meaning depended on a state the bar already
        // says out loud by which icons it is showing.
        detailsAction = bar.addAction(R.drawable.ic_info, getString(R.string.library_info),
                                      () -> showDetails(true));

        machineAction = bar.addAction(R.drawable.ic_chip, getString(R.string.library_machine),
                                      () -> showDetails(false));

        // The manual, which used to be a button floating in the corner of the
        // artwork - see GameInfoView, where it no longer is. It belongs on the
        // bar with everything else that opens something.
        manualAction = bar.addAction(R.drawable.ic_manual, getString(R.string.library_manual),
                                     this::openTheManual);

        // And the way out of the game altogether, which the sheet has always
        // had as a row and the details bar needs as an icon: there is no
        // keyboard on that side to reach the sheet from.
        // A cross, not the library's three books: what this does is close the
        // game. Where it puts you afterwards is the library, but the promise
        // the icon makes is the one the button keeps - the machine stops
        // either way.
        libraryAction = bar.addAction(R.drawable.ic_close, getString(R.string.library_title),
                                      this::openLibrary);

        menuAction = bar.addAction(R.drawable.ic_menu, getString(R.string.menu_button),
                                   () -> menu.open());

        return bar;
    }

    /**
     * The four the bar keeps over the game's details, and everything over the
     * machine.
     *
     * Kept as fields because the bar is built once and asked to change face
     * many times; {@code QuickBar.showOnly} is told which, since what belongs
     * on a details bar is a question about this app's screens rather than
     * about a bar.
     */
    private ImageButton detailsAction;
    private ImageButton machineAction;
    private ImageButton manualAction;
    private ImageButton libraryAction;
    private ImageButton menuAction;

    /**
     * The machine's own guide: the picture, the bar, and ☰.
     *
     * <b>The bar is rung whole rather than an icon at a time.</b> There are
     * nine of them, and a mark each would be a ten-step tour on the first
     * launch somebody ever makes - which is a thing people abandon rather
     * than read. It would also be nine ways for the guide to decline: {@link
     * QuickBar#setCompact} drops icons when the bar is narrow, and {@link
     * Tour#arm} refuses the <em>whole</em> guide when any one target answers
     * null, so on a narrow phone a per-icon tour would silently never fire.
     * The bar itself is always there when the bar is there.
     *
     * So the caption names what the icons open and the mark rings all of
     * them. What is left is the two things the icons cannot say themselves:
     * that a tap on the picture brings the bar back once it has faded, and
     * that ☰ holds everything the bar has no room for.
     *
     * <b>The bar's marks follow the bar to whichever window it is in.</b>
     * {@link #menuAction} is on the quick bar, and the bar is <em>borrowed</em>
     * by the second screen's panel when one is showing - {@link
     * EmulatorLayout#setLentAway} detaches it from {@link #layout} and a fresh
     * {@code SecondScreen} reparents it onto its own window. An overlay in
     * this activity's content cannot ring a view on another display, so each
     * of those two marks names the window it must be drawn in - {@link
     * #guidePanel}, the panel's own while the bar is borrowed there and null
     * while the bar is on the machine's screen - and {@link Tour} draws it
     * there. A bar with no parent at all, the instant between detach and
     * reattach, answers null like any other missing target, and the tour
     * declines quietly for next time.
     *
     * The picture itself never moves - detaching the {@code SurfaceView}
     * would destroy the surface Fuse draws into - so {@link #layout} itself
     * is always a fine target for it, in this window.
     *
     * <b>Built in {@link #buildMachineTour}, called from {@link #onCreate},
     * not a field initialiser.</b> This one would run before {@code
     * fadeQuickBar} is declared and referencing it from an initialiser is an
     * illegal forward reference - the same rule this project keeps about
     * collaborators and field initialisers, one line finer.
     */
    private Tour machineTour;

    private Tour buildMachineTour() {
        return Tour.of(Prefs.KEY_GUIDE_MACHINE)
                .mark(() -> layout, R.string.guide_picture)
                .mark(() -> quickBar.getParent() != null ? quickBar : null,
                      this::guidePanel, R.string.guide_bar)
                .mark(() -> quickBar.getParent() != null ? menuAction : null,
                      this::guidePanel, R.string.guide_menu)
                // Or the bar fades out from under its own explanation.
                .holding(() -> quickBar.removeCallbacks(fadeQuickBar),
                         this::revealQuickBar);
    }

    /**
     * The window the bar's own marks must be drawn in: the panel's, while the
     * bar is borrowed by it, and null while the bar is on the machine's own
     * screen - a mark is only ever as far away as its target.
     */
    private Presentation guidePanel() {
        SecondScreen panel = panels.panel();
        return (panel != null && quickBar.getParent() != layout) ? panel : null;
    }

    /**
     * Shows the game's details, or the machine again.
     *
     * <b>Two arrangements, one button.</b> With a panel the details are the
     * panel's other side, so this turns it over and the borrowed bar - which is
     * on the panel with it - changes face. With one screen there is nowhere to
     * put them beside the picture, so the details are their own screen and this
     * opens it; its action row sits at the foot of the description, built by
     * the shared GameInfoView, carrying five icons: back to the machine,
     * manual, music, ☰ and close.
     */
    private void showDetails(boolean details) {
        if (panels.inUse()) {
            panels.showInfo(details);
            applyBarMode();
            return;
        }

        if (!details) return;   // one screen: the machine is what is showing

        panels.openOwnScreen(new Intent(this, GameInfoActivity.class)
                .putExtra(GameInfoActivity.EXTRA_PATH, openedPath));
    }

    /**
     * The manual for whatever is loaded, or a word saying there is none.
     *
     * The path is the store's key for the game now open - {@code gameOpened}
     * works it out and keeps it here, since the bar can be tapped long after
     * that and a second resolve would be the same answer at the cost of
     * another walk of the documents provider.
     */
    private void openTheManual() {
        android.net.Uri manual = openedPath == null ? null
                : dev.ldlab.zedex.library.meta.Artwork.manual(this, openedPath);

        if (manual == null) return;   // the button is not offered without one

        panels.openManual(manual);
    }

    /**
     * Which face the bar is wearing, worked out from where the details are.
     *
     * Called wherever that can change - the button above, and a panel arriving
     * or going, since a panel that goes takes the details side with it and the
     * bar must not be left showing four icons over a machine.
     */
    private void applyBarMode() {
        if (!panels.showingInfo()) {
            // Everything except the three that only mean something over the
            // details: there is no machine to go back to while it is showing,
            // the sheet already carries the library as a row, and the manual
            // belongs beside the artwork it explains.
            //
            // And not the details icon either for a game the store cannot
            // name - a tape from a file manager, an entry inside a zip. There
            // are no details to show, and the corner switch this replaces
            // appeared only when there were: an icon that opens an empty page
            // is the same fault in a new place.
            if (openedPath == null) {
                quickBar.showAllExcept(machineAction, manualAction, libraryAction,
                                       detailsAction);
            } else {
                quickBar.showAllExcept(machineAction, manualAction, libraryAction);
            }
            return;
        }

        // Without a manual it is three icons, not four greyed ones: the
        // details side has no manual to open for most games, and an icon that
        // does nothing is the fault this app refuses elsewhere - see
        // GameInfoView, whose own corner button appeared only once one had
        // actually been resolved.
        if (hasManual) {
            quickBar.showOnly(machineAction, manualAction, menuAction, libraryAction);
        } else {
            quickBar.showOnly(machineAction, menuAction, libraryAction);
        }
    }

    /**
     * The store's key for whatever is loaded, and whether it has a manual.
     *
     * Both worked out by {@code gameOpened} on the thread it already runs on -
     * resolving a manual walks the documents provider, which is not something
     * to do when a bar is tapped - and read by the details bar afterwards.
     * Null and false for a game the store cannot name: an entry inside a zip,
     * or a file handed over from somewhere the content tree does not reach.
     */
    private volatile String openedPath;
    private volatile boolean hasManual;

    /**
     * The keyboard that lies over the picture.
     *
     * It wears whichever skin is chosen, like every other keyboard in the app -
     * the view reads that for itself, so there is nothing to pass in. The
     * layout keeps it in step, and substitutes a drawn one where the choice is
     * Android's own keyboard, which is not ours to paint over a game.
     */
    private SpectrumKeyboardView overlayKeyboard() {
        SpectrumKeyboardView keys = new SpectrumKeyboardView(this);

        keys.setBottomAligned(true);
        keys.setVisibility(View.GONE);

        return keys;
    }

    private View overlayButton(int icon, int name, Runnable action) {
        ImageButton button = new ImageButton(this);

        button.setImageResource(icon);
        button.setContentDescription(getString(name));
        button.setScaleType(ImageButton.ScaleType.FIT_CENTER);

        // The joystick's own face and legend colour, since this sits beside it:
        // its own shape made a solid dark disc among controls that are barely
        // there, which over a picture reads as a hole rather than a button.
        // The in-a-bar palette to begin with; EmulatorLayout swaps both for the
        // opaque set whenever the controls end up floating over the picture,
        // where the translucent one cannot be seen at all.
        button.setColorFilter(JoystickView.markColour(false));
        button.setBackground(JoystickView.disc(
                getResources().getDisplayMetrics().density, false));

        // Its own padding, equal on all four sides. An ImageButton takes its
        // from the style otherwise, and that one is not symmetrical: the glyph
        // came out low and to the left of a circle it was meant to be in the
        // middle of.
        int inset = Math.round(11 * getResources().getDisplayMetrics().density);
        button.setPadding(inset, inset, inset, inset);

        button.setVisibility(View.GONE);
        button.setOnClickListener(v -> action.run());

        return button;
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
     *
     * An entry of a zip carries {@link Recents.Item#inside}, and goes back
     * through {@link Media#stageAndOpenEntry} exactly the way it was opened
     * the first time - {@code item.uri} is the archive there, not the game,
     * and staging it directly would try to load the zip itself as if it were
     * one. A plain file has no such entry and takes the path this always did.
     */
    private void openRecent(Recents.Item item) {
        if (item.inside != null) {
            Work.run("open-recent", () -> media.stageAndOpenEntry(item.uri, item.inside));
            return;
        }

        Work.run("stage-recent", () -> {
            File staged = media.stage(item.uri);

            if (staged == null) {
                Recents.forget(getContentResolver(), preferences, item.uri);
                return;
            }

            FuseNative.openFile(staged.getAbsolutePath());
            note(R.string.file_opened, staged.getName());

            rememberMediaName(Storage.withoutExtension(staged.getName()));
            gameOpened(item.uri, null);
        });
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
                  () -> machine.showChooser(this::reopenOnNewMachine));
        // Reset asks first, and asking is a sheet page - so the bar's row opens
        // the sheet on it rather than the two surfaces doing it differently.
        rows.item(R.drawable.ic_reset, getString(R.string.menu_reset),
                  () -> menu.go(getString(R.string.menu_reset), machine.resetPage()));
        // No confirming, unlike reset: the magic button interrupts the machine
        // rather than throwing its state away, and half of what it is for is
        // pressing it at a particular moment.
        rows.item(R.drawable.ic_bolt, getString(R.string.menu_nmi), machine::nmi);

        // Only where the machine could have had one. A row that does nothing at
        // all on a 48K would be worse than no row: the setting stays on the
        // settings page, where its summary can say which machines it is for.
        // Asked of Fuse rather than of a list of machine names kept here.
        if (FuseNative.canTurbo()) {
            boolean turbo = preferences.getBoolean(Prefs.KEY_TURBO,
                                                   false);
            rows.rule();
            rows.item(R.drawable.ic_turbo,
                      getString(turbo ? R.string.quick_turbo_on
                                      : R.string.quick_turbo_off),
                      () -> setTurbo(!turbo));
        }
    }

    /**
     * The processor at 7MHz or 3.5, from the bar rather than the settings.
     *
     * Both places write the same preference and both push it into Fuse, since
     * the settings screen only listens to its own changes while it is open. It
     * takes effect between one frame and the next, so there is nothing to
     * restart and nothing to confirm.
     */
    private void setTurbo(boolean on) {
        preferences.edit().putBoolean(Prefs.KEY_TURBO, on).apply();
        FuseNative.setTurbo(on);

        note(on ? R.string.turbo_on : R.string.turbo_off);
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
        if (!fullscreen()) {
            rows.item(R.drawable.ic_keyboard,
                      getString(keys ? R.string.quick_keyboard_hide
                                     : R.string.quick_keyboard_show),
                      () -> controls.showKeyboard(!keys));
        }

        // And the four settings worth changing without leaving the game. Each
        // says what it is now and opens the sheet's own list of the rest, the
        // way the machine row does: the bar has no room for eight joystick
        // interfaces or five keyboards, and one list per thing beats two places
        // that both know how to choose one and can disagree about it.
        rows.rule();

        rows.item(R.drawable.ic_joystick,
                  getString(R.string.quick_joystick_type,
                            controls.joystickTypeName()),
                  () -> menu.go(getString(R.string.joystick_type_title),
                                controls.joystickTypePage()));

        rows.item(R.drawable.ic_bookmark,
                  getString(R.string.quick_key_profile, controls.keyProfileName()),
                  () -> menu.go(getString(R.string.profile_title),
                                controls.keyProfilePage()));

        rows.item(R.drawable.ic_keyboard,
                  getString(R.string.quick_keyboard_skin,
                            controls.keyboardSkinName()),
                  () -> menu.go(getString(R.string.keyboard_skin_title),
                                controls.keyboardSkinPage()));

        rows.item(R.drawable.ic_mouse,
                  getString(controls.mouseOn() ? R.string.quick_mouse_on
                                               : R.string.quick_mouse_off),
                  () -> menu.go(getString(R.string.menu_mouse),
                                controls.mousePage()));
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
        FuseSettings.applyFilter(preferences);

        note(said);
    }

    /** The signal, in the one word a row has room for. */
    private String videoName() {
        return getString(Video.of(preferences).shortTitle);
    }

    /**
     * Round the three signals, and round the three borders.
     *
     * Stepping rather than offering a list: there are three of each, the row
     * says which one is showing, and a chooser is what the settings screen is
     * for. Both write the same preference the settings do, so the two agree.
     */
    private void nextVideo() {
        Video next = Video.of(preferences).next();

        preferences.edit()
                .putString(Prefs.KEY_VIDEO, String.valueOf(next.value))
                .apply();
        FuseSettings.applyFilter(preferences);

        note(R.string.quick_video, videoName());
    }

    private void nextBorder() {
        Border next = Border.of(preferences).next();

        preferences.edit()
                .putString(Prefs.KEY_BORDER, next.value)
                .apply();
        applyScale();

        note(R.string.quick_border, getString(next.title));
    }

    /**
     * Whether the quick bar should stay where it is rather than fading.
     *
     * Two ways to say so, and either is enough: the setting, for somebody who
     * simply prefers it, and touch exploration being on, because the fade is
     * unusable then whatever the setting says. Asked each time rather than
     * cached - a screen reader can be turned on while the app is running, and
     * the answer has to change with it.
     */
    private boolean keepBarUp() {
        android.view.accessibility.AccessibilityManager accessibility =
                (android.view.accessibility.AccessibilityManager)
                        getSystemService(ACCESSIBILITY_SERVICE);

        return preferences.getBoolean(Prefs.KEY_KEEP_BAR, false)
                || (accessibility != null && accessibility.isTouchExplorationEnabled());
    }

    private void showLights(boolean shown) {
        layout.setLightsVisible(shown);
        preferences.edit().putBoolean(Prefs.KEY_INDICATORS, shown).apply();

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

    /* A method rather than a lambda, because it puts itself back on the queue
       and a field cannot refer to itself while it is being made. */
    private final Runnable fadeQuickBar = this::fadeQuickBarNow;

    private void fadeQuickBarNow() {
        // On a second screen the bar has a panel of its own and is never over
        // the picture, so there is nothing to fade out of the way of.
        if (panels.inUse()) return;

        // Three seconds is not a length of time somebody exploring by touch
        // can finish nine icons in - WCAG calls this Timing Adjustable, and a
        // control that removes itself while you are still finding it is the
        // clearest case of it. Kept up outright with a screen reader on, and
        // by a switch for anyone else who wants it that way.
        if (keepBarUp()) return;

        // An open group is somebody reading it. Three seconds is long enough to
        // notice the bar and nothing like long enough to decide which of eight
        // machines to switch to, and taking the list away while a finger is on
        // its way to it is the fade doing the opposite of its job. So the clock
        // starts again, and only a bar with nothing open fades.
        if (quickBar.isOpen()) {
            quickBar.postDelayed(fadeQuickBar, BAR_LINGER_MS);
            return;
        }

        barWanted = false;
        quickBar.collapse();
        quickBar.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (!barWanted) quickBar.setVisibility(View.GONE);
        });
    }

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
        return preferences.getBoolean(Prefs.KEY_FULLSCREEN, false);
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
                .putBoolean(Prefs.KEY_FULLSCREEN, on).apply();

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
            // Navigation, and the one row here that leaves this screen rather
            // than acting on the machine or opening a page of it - so it
            // goes first, above even Open file…, rather than at the foot
            // among the things reached for rarely. Only when there is a
            // library to go back to. The two are separate tasks whichever
            // way round you read it - this activity is the singleInstance
            // one, which is what stops a second game standing up a second
            // Fuse core, and a singleInstance activity is always alone in a
            // task of its own; see Quit, which is what that costs. Back here
            // is already
            // spoken for - opening this menu is what it does, since leaving
            // the app any other way loses the machine's RAM. So the only way
            // across is an explicit one, and only worth offering to somebody
            // the library would actually show something to - libraryExists,
            // not startsInLibrary: that one also asks the "library" switch,
            // which says where the app opens and nothing about whether the
            // library exists. Reading it here too once made turning the
            // switch off take this row out of the menu as well, with no way
            // back short of Settings; see SettingsActivity.libraryExists and
            // docs/LIBRARY.md.
            if (SettingsActivity.libraryExists(preferences)) {
                sheet.addItem(getString(R.string.library_title), R.drawable.ic_library,
                              this::openLibrary);
            }

            // Beside it for the same reason and gated the same way: nowhere
            // to land is worse than a row that could only fail, and {@link
            // Catalogues#any} is the one question that answers whether there
            // is a shelf here at all - the same gate the library's own rail
            // button for this tab rests on. Its own word, not the tab's own
            // "Catalogue" - see menu_online_browser's own comment for why a
            // row reached from the machine needs to say "online" itself.
            if (Catalogues.any(this)) {
                sheet.addItem(getString(R.string.menu_online_browser),
                              R.drawable.ic_catalogue, this::openOnlineCatalogue);
            }

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

            // Only for a game a tune was fetched for, which is a small
            // minority - a row that is nearly always an empty page is worse
            // than no row.
            if (music.anything()) {
                sheet.addSubmenu(getString(R.string.music_title),
                                 R.drawable.ic_music, music.page());
            }
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

    /**
     * Brings the library's own task to the front instead of this one's.
     *
     * {@code REORDER_TO_FRONT} rather than starting a fresh instance: the
     * library is already running in its own task - it is what started this
     * activity in the first place, or is still sitting where the machine left
     * it - and reordering brings that forward with whatever folder it was in
     * still on screen, rather than standing up a second one on top of it.
     * {@code NEW_TASK} is required to reorder a task this activity is not
     * itself part of. The machine is untouched: {@link #onPause} pauses it as
     * it always does when the window is lost, and it is exactly as it was
     * left when Back reaches it again.
     *
     * That task does not always exist, though: with the switch off, the
     * launcher's own instance of {@code LibraryActivity} hands straight back
     * here and finishes itself, leaving nothing to reorder - so this row can
     * just as well create a fresh instance. {@code EXTRA_FROM_MENU} is what
     * tells that instance it was reached deliberately, from this row, rather
     * than by the launcher: see its own comment on {@code LibraryActivity}
     * for why the two must not ask the same question twice.
     */
    private void openLibrary() {
        Intent intent = new Intent(this, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                       | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /**
     * The same door as {@link #openLibrary}, one extra turned on: {@link
     * LibraryActivity#EXTRA_OPEN_CATALOGUE} asks that instance to land on
     * its Catalogue tab rather than wherever Browse was left. Everything
     * else - the reordered task, the untouched machine - is identical, and
     * deliberately so: see that method's own comment.
     */
    private void openOnlineCatalogue() {
        Intent intent = new Intent(this, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.putExtra(LibraryActivity.EXTRA_OPEN_CATALOGUE, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                       | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
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
        noteText(getString(message, arguments));
    }

    /** For a string that has already been formatted - a plural, which cannot
     *  be handed over as a resource id because choosing its form is what
     *  resolves it. */
    private void noteText(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
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

        // Whatever Fuse is waiting to be told, it is not going to be told it
        // here - and the emulation thread is blocked until somebody says so.
        FuseNative.setConfirmer(null);

        InputManager input = getSystemService(InputManager.class);
        if (input != null) input.unregisterInputDeviceListener(devices);

        panels.unwatch();

        // The panel itself survives an ordinary pause - see onStop for when
        // it actually comes down - but a video on its info side must not run
        // on behind a machine nobody is looking at either.
        panels.pauseVideo();

        // Through the tour, not Coach.dismiss directly - Tour.dismiss also
        // runs the tour's own release when one is owed, which is what
        // re-arms the quick bar's fade. Coach.dismiss alone would take the
        // mark down and leave the fade unscheduled until some unrelated tap
        // called revealQuickBar() again - see Tour's own class comment.
        machineTour.dismiss(this);

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

        // Somebody who has left the app has finished with the music, not with
        // the game - so the machine comes back now rather than being left as a
        // tune nobody is listening to, and coming back to a game that is
        // where it was is the whole point of putting it aside. Does nothing
        // when no music was playing.
        Music.stop(this);
    }

    /**
     * The one signal available for a manual's own viewer being dismissed,
     * which gives this activity no callback of its own - see {@link
     * Panels#topFocusReturned}. Confirmed on the device: opening the viewer
     * onto the panel's display, real hardware or the emulator's second
     * screen alike, leaves this activity itself resumed throughout, so
     * {@link #onResume} never runs again to hook - this is the one that
     * does, the moment the front of the screen is ours again, by a touch on
     * the machine's own screen or the viewer going away with nothing else
     * claiming focus behind it.
     */
    @Override
    public void onTopResumedActivityChanged(boolean isTopResumedActivity) {
        super.onTopResumedActivityChanged(isTopResumedActivity);
        if (isTopResumedActivity) panels.topFocusReturned();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getApplication().unregisterActivityLifecycleCallbacks(panels.lifecycle());

        // The version check as well as the null one. Nothing below 33 ever
        // sets backCallback - registerBackCallback returns early - so the null
        // test alone is correct today, but it is an invariant two methods
        // apart rather than something this line says, and lint reads it as an
        // API 33 call on a minSdk 30 build because that is what it is.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }

        // Recorder's listeners are statics, and a listener here holds this
        // activity. The recording path lets go of its own once the encoder has
        // it; a screenshot asked for and never answered - the machine paused
        // between the ask and the next frame, or the surface went - has
        // nothing else to clear it. This activity is normally
        // process-lifetime, so it only shows when it is recreated, which a
        // language change deliberately does.
        Recorder.forgetPendingScreenshot();
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
        // comes before the machine. This activity is singleInstance and stays
        // alive behind the wizard, so it keeps whatever file it was opened
        // with and onResume tries again when the wizard is done.
        if (Prefs.welcomeNeeded(this, preferences)) {
            WelcomeActivity.start(this, true);
            return;
        }

        // The ROMs the app ships, into whatever folder is current. Not only on
        // the first run: the folder can be chosen after the last unpack, and a
        // folder with no ROMs in it is no reason to go asking for ROMs that are
        // already in the APK. Adds only what is missing, so a set of the user's
        // own is left alone.
        Storage.installRoms(this);

        if (!Storage.haveRoms(this)) {
            roms.show(false);
            return;
        }

        roms.hide();
        started = true;

        machine.start();

        // The tour follows the bar to whichever window it is in - with a
        // second screen the quick bar is borrowed by a Presentation on the
        // other display, and its marks are drawn there rather than declined;
        // see machineTour's own comment.
        machineTour.arm(this);
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

    /**
     * Whether a guide mark is up on the panel's own window right now.
     *
     * The machine's tour draws its bar and menu marks there while the controls
     * are borrowed by a second screen, and while one is up the keys that
     * arrive through that window belong to the mark rather than to the game -
     * see {@link #onKeyDown} for the half of this rule that lives here.
     */
    private boolean guideOnPanel() {
        SecondScreen panel = panels.panel();
        return panel != null && Coach.isShowing(panel);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // A guide mark up on the panel's own window owns the keys that arrive
        // through it - SecondScreen hands every non-Back key to this activity
        // first, and a mark is supposed to be the only thing in front. Back
        // takes its own path into handleBack, which swallows for a mark itself;
        // the system keys keep theirs, or a volume press would die in the
        // panel's view tree instead of taking its ordinary path.
        if (guideOnPanel() && keyCode != KeyEvent.KEYCODE_BACK
                && !Coach.isSystemKey(keyCode)) {
            return false;
        }

        // A controller first: its buttons are not keys the machine has, and its
        // D-pad must not be mistaken for a keyboard's cursor keys, which the
        // machine does have.
        if (gamepad.key(event)) return true;

        // API 30 to 32 only. From 33 the manifest's
        // enableOnBackInvokedCallback is honoured and back arrives at
        // handleBack() through the dispatcher instead, never here.
        if (keyCode == KeyEvent.KEYCODE_BACK
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            handleBack();
            return true;
        }

        return forwardKey(keyCode, true) || super.onKeyDown(keyCode, event);
    }

    /**
     * Back, on API 33 and later, where {@code onKeyDown} no longer sees it.
     *
     * The manifest opts in with {@code enableOnBackInvokedCallback}, which
     * matters more than it looks: at {@code targetSdk 36} that becomes the
     * default whether or not it is asked for, and the {@code KEYCODE_BACK}
     * branch in {@code onKeyDown} would simply stop being reached. Three
     * behaviours would go at once, silently, on a bump of a number - the
     * sheet's page-by-page back, leaving fullscreen, and back opening the menu
     * instead of quitting, which is the only thing standing between a slip of
     * the thumb and a Spectrum's RAM. Opting in now means the bump changes
     * nothing, and the path is exercised on every device that has it rather
     * than on the first one after the number moved.
     *
     * Registered for the life of the activity because {@link #handleBack}
     * always consumes; there is no state in which this screen wants the system
     * to take back away from it. That is also why nothing here enables and
     * disables the callback as the sheet opens.
     *
     * API 30 to 32 have no dispatcher at all - {@code minSdk} is 30 - so
     * {@code onKeyDown} remains the path there. The two are exclusive: below
     * 33 nothing registers, and from 33 the key never arrives.
     */
    private android.window.OnBackInvokedCallback backCallback;

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;

        backCallback = this::handleBack;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }

    /**
     * What back means here, whichever way it arrived.
     *
     * Always consumed, never passed on: there is no arrangement of this screen
     * where back leaves the app. That is deliberate, and it is why the
     * registration below is unconditional rather than being enabled and
     * disabled as the sheet opens and closes.
     */
    private void handleBack() {
        android.util.Log.i("Zedex", "back: EmulatorActivity.handleBack");

        // A guide mark up on either screen swallows Back, however it arrived.
        // The panel answers every Back of its own through this very method -
        // the API 30 to 32 key and the 33+ callback both route into its
        // backPressed, which runs the host's own handler - so this is where a
        // mark on that window swallows it; see Coach.show's panel overload for
        // why it claims no callback of its own.
        SecondScreen panel = panels.panel();
        if (Coach.isShowing(this) || (panel != null && Coach.isShowing(panel))) return;

        // With the sheet open, back belongs to the sheet: up one page,
        // and out of the sheet altogether from the top of it.
        if (menu.back()) return;

        // Then fullscreen, which is the other thing back means "out of" -
        // and the one that has taken the way out off the screen, since the
        // bar it lives on has faded.
        if (fullscreen()) {
            showFullscreen(false);
            return;
        }

        // And otherwise the menu, rather than the desktop. A tap outside
        // it or back again is the way out, so nothing is trapped; leaving
        // is Quit, which asks about unsaved disks first. A machine is not
        // a page to be backed out of - and a Spectrum put away by accident
        // is a Spectrum whose RAM has gone.
        menu.open();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // The other half of onKeyDown's guard - a press without its release
        // would leave the game half-keyed.
        if (guideOnPanel() && keyCode != KeyEvent.KEYCODE_BACK
                && !Coach.isSystemKey(keyCode)) {
            return false;
        }

        if (gamepad.key(event)) return true;
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event);

        return forwardKey(keyCode, false) || super.onKeyUp(keyCode, event);
    }

    /** A controller's stick and hat, which arrive as axes rather than as keys. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        // A stick or hat arriving while a mark is up on the panel would move
        // the emulated joystick behind it - the same leak onKeyDown guards.
        if (guideOnPanel()) return false;

        return gamepad.motion(event) || super.onGenericMotionEvent(event);
    }

    /**
     * Fuse asking whether to save something it is about to throw away.
     *
     * On the UI thread, with the emulation thread stopped inside
     * {@code FuseNative.onConfirmSave} until this answers - so every path out
     * of here has to answer, including the ones nobody presses a button on.
     * Dismissing with Back, or a tap outside, is a cancel, which is the reply
     * that leaves the disk in the drive with its changes.
     *
     * The message is Fuse's own and already says which drive and what happened
     * ("Beta disk A: has been modified. Do you want to save it?"); it arrives
     * in English because it is Fuse's string rather than ours, and it is shown
     * as the body under a title of ours. Rewriting it here would mean parsing
     * a sentence for a drive name, which is a worse bargain than one English
     * line inside a dialog whose buttons are translated.
     */
    private void askAboutSavingDisk(String message, FuseNative.Answer answer) {
        if (isFinishing() || isDestroyed()) {
            answer.is(FuseNative.CONFIRM_CANCEL);
            return;
        }

        // A single boolean rather than a dismiss listener doing the work: the
        // buttons dismiss the dialog too, so a listener would answer twice -
        // harmlessly, but only because the latch ignores the second.
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.disk_modified_title)
                .setMessage(message)
                .setPositiveButton(R.string.disk_modified_save,
                                   (dialog, which) -> answer.is(FuseNative.SAVE))
                .setNegativeButton(R.string.disk_modified_discard,
                                   (dialog, which) -> answer.is(FuseNative.DONT_SAVE))
                .setNeutralButton(android.R.string.cancel,
                                  (dialog, which) -> answer.is(FuseNative.CONFIRM_CANCEL))
                .setOnCancelListener(dialog -> answer.is(FuseNative.CONFIRM_CANCEL))
                .show();
    }
}
