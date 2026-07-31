package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.input.Controls;
import dev.ldlab.zedex.media.Media;
import dev.ldlab.zedex.media.Recorder;
import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.view.MenuDrawer;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The emulated Spectrum: starting it, changing it, stopping it, ending it.
 *
 * Everything here goes one way — into Fuse — and nothing here draws. What comes
 * back out is read from {@link FuseNative}'s snapshots, which the emulation
 * thread publishes; there is no reply to a command, because the queue in
 * {@code android_bridge.c} has nowhere to put one.
 *
 * The two rules the rest of the app has to know about are both about time.
 * Options are passed on Fuse's <b>command line</b> rather than queued, so they
 * are in force before it finishes starting — a file handed to us by an intent
 * can be loading before the queue is first drained. Everything after that is
 * <b>queued</b>, which is safe to do before Fuse has started, since the commands
 * simply wait.
 */
public final class Machine {

    private static final String TAG = "Zedex";

    /**
     * Where Fuse's own data files go, under the app's files directory.
     *
     * argv[0] is never run - it is only read for the directory it names, which
     * is how Fuse is pointed at its font: {@code compat_get_next_path} looks in
     * {@code lib} beside the program before it falls back to the compile-time
     * FUSEDATADIR, and that is an absolute path with a package name in it.
     */
    private static final String DATA_DIR = "fuse";
    private static final String LIB_DIR = DATA_DIR + "/ui/widget";
    private static final String PROGRAM = DATA_DIR + "/fuse";

    public static final String PREF_MACHINE = SettingsActivity.KEY_MACHINE;
    private static final String DEFAULT_MACHINE = "128";

    /**
     * How long Fuse gets to publish a machine before its start is called
     * failed. Generous: this only runs while the screen is black anyway.
     */
    private static final long START_TIMEOUT_MS = 6000;
    private static final long START_POLL_MS = 500;

    /** How long a machine change gets to take effect before it is checked. */
    private static final long SETTLE_MS = 500;

    /** What holding fast forward runs at, in per cent of a real Spectrum. */
    private static final int FAST_FORWARD = 500;

    /**
     * How long to let the recorder finish its file before going anyway.
     *
     * {@link Recorder#stop} does not block - it cannot, being called from the UI
     * thread - so the encoder is still writing when it returns, and a process
     * that exits underneath it leaves a truncated film. A second is far more
     * than the queue takes to drain, and quitting is not the moment to be exact.
     */
    private static final long RECORDER_GRACE_MS = 1000;

    /** What the machine's side of the app has to lend this one. */
    public interface Host {
        void note(int message, Object... arguments);

        /** The sheet, for the two questions here that have to be asked. */
        MenuDrawer sheet();

        /** Nothing is loaded any more, so states stop being named after it. */
        void forgetMedia();

        /** The drives with changes nothing has written back, or null. */
        String modifiedDisks();

        /** Which interface the on-screen pad comes out as, for the start line. */
        int joystickType();

        /** Fuse never started: the ROMs are unusable and the panel says so. */
        void startFailed();
    }

    private final Activity activity;
    private final SharedPreferences preferences;
    private final Host host;

    private boolean fastForwarding;

    public Machine(Activity activity, SharedPreferences preferences, Host host) {
        this.activity = activity;
        this.preferences = preferences;
        this.host = host;
    }

    // --- before anything starts ----------------------------------------------

    /**
     * Puts Fuse's data files where it can open them and points its environment
     * at writable directories.
     *
     * Static, and called before the machine exists: Fuse opens these with plain
     * stdio so they cannot stay inside the APK, and it resolves its config
     * through $XDG_CONFIG_HOME and $HOME and writes temporary files to $TMPDIR -
     * none of the Unix defaults are writable on Android.
     */
    public static void prepare(Activity activity) {
        File files = activity.getFilesDir();

        try {
            installAssets(activity, DATA_DIR, new File(files, LIB_DIR));
        } catch (IOException e) {
            Log.e(TAG, "failed to unpack Fuse data files", e);
        }

        try {
            Os.setenv("HOME", files.getAbsolutePath(), true);
            Os.setenv("XDG_CONFIG_HOME", files.getAbsolutePath(), true);
            Os.setenv("TMPDIR", activity.getCacheDir().getAbsolutePath(), true);
        } catch (ErrnoException e) {
            Log.e(TAG, "failed to set up environment", e);
        }
    }

    /** Copies an assets directory into internal storage, skipping what is there. */
    private static void installAssets(Context context, String assetDir, File target)
            throws IOException {
        String[] entries = context.getAssets().list(assetDir);
        if (entries == null || entries.length == 0) return;

        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("cannot create " + target);
        }

        for (String entry : entries) {
            String assetPath = assetDir + "/" + entry;
            File out = new File(target, entry);

            String[] children = context.getAssets().list(assetPath);
            if (children != null && children.length > 0) {
                installAssets(context, assetPath, out);
                continue;
            }

            // Data files are read-only and versioned with the APK, so an
            // existing copy of the right size is always up to date.
            try (InputStream in = context.getAssets().open(assetPath)) {
                if (out.exists() && out.length() == in.available()) continue;
            }

            try (InputStream in = context.getAssets().open(assetPath);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) os.write(buffer, 0, read);
            }
        }
    }

    // --- starting ------------------------------------------------------------

    /** Hands Fuse its command line and lets it go. */
    public void start() {
        // Fuse searches the working directory for a ROM before anywhere else,
        // which is how it finds the user's.
        FuseNative.setWorkingDirectory(
                Storage.romsDirectory(activity).getAbsolutePath());
        FuseNative.start(arguments());
        watchForFailure(0);

        // Not Fuse's settings, so they cannot ride in on its command line: the
        // renderer has to be told.
        SettingsActivity.applyFilter(preferences);
        SettingsActivity.applyScale(activity, preferences);
        startDivmmc();
    }

    private String[] arguments() {
        List<String> arguments = new ArrayList<>();

        // Not the word "fuse": Fuse looks for its font in lib beside whatever
        // argv[0] names, and this is how it is pointed at ours.
        arguments.add(new File(activity.getFilesDir(), PROGRAM).getAbsolutePath());
        arguments.add("--machine");
        arguments.add(preferences.getString(PREF_MACHINE, DEFAULT_MACHINE));

        // Three of Fuse's settings in three combinations; see
        // OPTION_LOADER_ACCELERATION in android_bridge.c for why these three.
        int loader = SettingsActivity.loaderLevel(preferences);

        arguments.add(loader > 0 ? "--traps" : "--no-traps");
        arguments.add(loader > 0 ? "--fastload" : "--no-fastload");
        arguments.add(loader > 1 ? "--accelerate-loader" : "--no-accelerate-loader");

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
        int joystick = host.joystickType();
        if (joystick == Controls.JOYSTICK_KEYBOARD) joystick = Controls.JOYSTICK_NONE;

        arguments.add("--joystick-1-output");
        arguments.add(String.valueOf(joystick));
        arguments.add(joystick == Controls.JOYSTICK_KEMPSTON ? "--kempston"
                                                             : "--no-kempston");

        return arguments.toArray(new String[0]);
    }

    /** Fuse generates --x / --no-x for every boolean setting. */
    private void flag(List<String> arguments, String key, boolean fallback,
                      String option) {
        boolean on = preferences.getBoolean(key, fallback);
        arguments.add(on ? "--" + option : "--no-" + option);
    }

    private void value(List<String> arguments, String key, int fallback,
                       String option) {
        arguments.add("--" + option);
        arguments.add(preferences.getString(key, String.valueOf(fallback)));
    }

    /**
     * Fuse publishes a machine as soon as one is running, so no machine long
     * after the emulation thread should have got there means {@code main()}
     * returned instead - which is what an unusable ROM does. Nothing is drawn
     * in that case, so without this the screen simply stays black.
     */
    private void watchForFailure(long waited) {
        if (FuseNative.currentMachine() >= 0) return;

        if (waited >= START_TIMEOUT_MS) {
            Log.w(TAG, "no machine after " + waited + "ms; ROMs are unusable");
            host.startFailed();
            return;
        }

        activity.getWindow().getDecorView().postDelayed(
                () -> watchForFailure(waited + START_POLL_MS), START_POLL_MS);
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
        File firmware = Storage.divmmcFirmware(activity);
        if (!firmware.isFile()) return;

        FuseNative.loadDivmmcFirmware(firmware.getAbsolutePath());

        if (!preferences.getBoolean(SettingsActivity.KEY_DIVMMC, false)) return;

        FuseNative.setDivmmc(true);

        String card = preferences.getString(Media.PREF_CARD, null);
        if (card != null && new File(card).isFile()) FuseNative.insertCard(card);
    }

    // --- which machine -------------------------------------------------------

    /**
     * What the machine calls itself, or null before there is one.
     *
     * Asked of Fuse rather than read from the setting, because the two can
     * disagree: media brings its own machine with it - a .dsk switches to a +3 -
     * and Fuse falls back to 48K when the ROMs for the one that was asked for
     * are not there. What is running is the only answer worth showing.
     */
    public String name() {
        int current = FuseNative.currentMachine();
        String[] names = FuseNative.machineNames();

        return current >= 0 && current < names.length ? names[current] : null;
    }

    /** A menu label with the machine under it, where there is one. */
    public String withName(int label) {
        String name = name();

        return name == null ? activity.getString(label)
                            : activity.getString(label) + "\n" + name;
    }

    /** Persists whichever machine is really running, for the next launch. */
    public void remember() {
        int current = FuseNative.currentMachine();
        String[] ids = FuseNative.machineIds();

        if (current >= 0 && current < ids.length) {
            preferences.edit().putString(PREF_MACHINE, ids[current]).apply();
        }
    }

    public void showChooser() {
        String[] names = FuseNative.machineNames();
        if (names.length == 0) return;   // Fuse has not finished starting

        int current = FuseNative.currentMachine();

        host.sheet().go(activity.getString(R.string.machine_title), page -> {
            for (int i = 0; i < names.length; i++) {
                int which = i;
                page.addChoice(names[which], which == current,
                               () -> select(which));
            }
        });
    }

    private void select(int index) {
        String[] names = FuseNative.machineNames();

        FuseNative.selectMachine(index);
        host.forgetMedia();

        // The change happens on the emulation thread, and it can fail: Fuse
        // falls back to 48K when a machine's ROMs are missing. Check what
        // actually ended up running rather than assuming we got it.
        activity.getWindow().getDecorView().postDelayed(() -> {
            if (index >= names.length) return;

            if (FuseNative.currentMachine() != index) {
                Toast.makeText(activity,
                        activity.getString(R.string.machine_unavailable,
                                           names[index]),
                        Toast.LENGTH_LONG).show();
            } else {
                host.note(R.string.machine_selected, names[index]);
            }

            remember();
        }, SETTLE_MS);
    }

    /** Reset asks first: it throws away everything the machine is holding. */
    public MenuDrawer.Page resetPage() {
        return page -> {
            page.addNote(activity.getString(R.string.reset_confirm));
            page.addItem(activity.getString(R.string.menu_reset),
                         R.drawable.ic_reset, () -> {
                FuseNative.reset();
                host.forgetMedia();
                host.note(R.string.reset_done);
            });
        };
    }

    /** The magic button of the real hardware; what it does is the machine's. */
    public void nmi() {
        FuseNative.nmi();
        host.note(R.string.nmi_done);
    }

    // --- speed ---------------------------------------------------------------

    /**
     * Five hundred per cent while it is held, and back to the setting when it is
     * let go.
     *
     * The setting is not written to: this is a thing being done, not a preference
     * being changed, and a loading screen skipped at speed should not leave the
     * machine fast for the game afterwards.
     *
     * Guarded against being told the same thing twice, because it arrives from a
     * finger, a controller's trigger and that trigger's axis, and on some pads
     * two of those at once.
     */
    public void fastForward(boolean on) {
        if (fastForwarding == on) return;
        fastForwarding = on;

        FuseNative.setSpeed(on ? FAST_FORWARD : speed());
    }

    /** The next speed up or down the settings' own list. */
    public void stepSpeed(int direction) {
        String[] values = activity.getResources()
                .getStringArray(R.array.speed_values);
        String current = preferences.getString(SettingsActivity.KEY_SPEED, "100");

        int at = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) at = i;
        }

        int next = Math.max(0, Math.min(values.length - 1, at + direction));
        if (next == at) return;

        preferences.edit()
                .putString(SettingsActivity.KEY_SPEED, values[next]).apply();
        FuseNative.setSpeed(Integer.parseInt(values[next]));

        host.note(R.string.hotkey_speed, values[next]);
    }

    private int speed() {
        return SettingsActivity.SettingsFragment.number(
                preferences, SettingsActivity.KEY_SPEED, 100);
    }

    // --- ending it -----------------------------------------------------------

    /**
     * Closes the app rather than leaving it in the background.
     *
     * Back and Home only put a Spectrum away - the emulator pauses itself and
     * waits, which is what an emulator should do. This is for meaning it, and it
     * ends the <em>process</em>, not just the activity: the emulation thread is a
     * plain pthread inside Fuse's main loop, Fuse's globals cannot be initialised
     * twice, and the next launch has to be able to start it again. See
     * {@code Java_dev_ldlab_zedex_FuseNative_start}.
     *
     * Two things are worth a moment on the way out - a recording being written,
     * and a disk with changes nothing has written back - because both are work
     * the machine cannot get back for you.
     */
    public void quit() {
        String unsaved = host.modifiedDisks();

        if (unsaved == null) {
            quitNow();
            return;
        }

        host.sheet().go(activity.getString(R.string.quit_unsaved_title), page -> {
            page.addNote(activity.getString(R.string.quit_unsaved, unsaved));
            page.addItem(activity.getString(R.string.menu_quit),
                         R.drawable.ic_quit, this::quitNow);
        });
    }

    private void quitNow() {
        remember();

        if (Recorder.isRecording()) {
            Recorder.stop();
            Recorder.waitForFile(RECORDER_GRACE_MS);
        }

        // Off the recents list too: a task left there offers to resume a machine
        // whose process has gone, and Android would answer that by starting a
        // fresh one - which is what happens anyway, only having looked like the
        // old one was still there.
        activity.finishAndRemoveTask();
        Runtime.getRuntime().exit(0);
    }
}
