package dev.ldlab.zedex;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.widget.Toast;

/**
 * The native side of the emulator.
 *
 * Everything here except {@link #start} is called from the Android UI thread
 * and is queued for the emulation thread, because Fuse's core is single
 * threaded and must only be entered from the thread running its main loop.
 */
final class FuseNative {

    static {
        System.loadLibrary("fuse");
    }

    private FuseNative() {
    }

    private static Context context;
    private static Handler ui;

    /** Lets Fuse's errors reach the screen. Call once, from the activity. */
    static void attach(Context activityContext) {
        context = activityContext.getApplicationContext();
        ui = new Handler(Looper.getMainLooper());
    }

    /**
     * One of Fuse's errors, called from the emulation thread.
     *
     * Fuse would otherwise draw a modal into the emulated screen that only
     * Enter or Escape dismisses, and block whatever raised it until then.
     */
    static void onError(int severity, String message) {
        Context target = context;
        Handler handler = ui;
        if (target == null || handler == null || message == null) return;

        int length = severity >= 2 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT;
        handler.post(() -> Toast.makeText(target, message, length).show());
    }

    /**
     * Points the process at the folder Fuse should look in for ROMs, which it
     * searches before anywhere else. Call before {@link #start}.
     */
    static native boolean setWorkingDirectory(String path);

    /** Starts Fuse's main loop on its own thread. Returns immediately. */
    static native void start(String[] args);

    /** Hands the emulator a drawing surface, replacing any previous one. */
    static native void surfaceChanged(Surface surface);

    /** Blocks until the emulation thread has stopped using the surface. */
    static native void surfaceDestroyed();

    /** Queues a key event; {@code keycode} is an Android {@code KEYCODE_*}. */
    static native void key(int keycode, boolean pressed);

    // --- joystick ----------------------------------------------------------

    /*
     * The on-screen joystick is Fuse's joystick 1. Which Spectrum interface
     * it comes out as is a setting, so one pad covers Kempston, Cursor,
     * Sinclair, Timex and Fuller without knowing anything about them.
     */

    /** Fuse's {@code joystick_button}, and the order the pad reports them in. */
    static final int JOYSTICK_LEFT = 0;
    static final int JOYSTICK_RIGHT = 1;
    static final int JOYSTICK_UP = 2;
    static final int JOYSTICK_DOWN = 3;
    static final int JOYSTICK_FIRE = 4;

    /** Queues a joystick direction or fire; {@code button} is one of the above. */
    static native void joystick(int button, boolean pressed);

    /**
     * The interfaces Fuse can pretend to be, in its own words and its own
     * order — "None", "Cursor", "Kempston", … The index is the value
     * {@link #setJoystickType} takes. Available before Fuse has started.
     */
    static native String[] joystickTypeNames();

    /** Which interface the joystick appears as; an index into the above. */
    static native void setJoystickType(int type);

    /** Machine names for display, in Fuse's own order. Empty until Fuse has started. */
    static native String[] machineNames();

    /** Fuse's short machine ids ({@code "48"}, {@code "128"}, ...), parallel to
     *  {@link #machineNames}. These are what {@code --machine} accepts. */
    static native String[] machineIds();

    /** Whether there is anything on the tape - loaded, or SAVEd by the machine. */
    static native boolean hasTape();

    /** Writes the tape; the extension picks the format, .tap or .tzx. */
    static native void writeTape(String path);

    /** Throws the current tape away and starts an empty one. */
    static native void newTape();

    /** Names of the drives that currently have a disk in them. */
    static native String[] driveNames();

    /** Ids for {@link #driveNames}: controller in the high byte, drive in the low. */
    static native int[] driveIds();

    /** Writes a drive's disk; the extension picks the format. */
    static native void writeDisk(int controller, int drive, String path);

    /** Three strings per drive: its name, the disk in it, and "1" if modified. */
    static native String[] driveDetails();

    /** Puts a disk image into a particular drive. */
    static native void insertDisk(int controller, int drive, String path);

    /** Puts a blank formatted disk into a drive. */
    static native void newDisk(int controller, int drive);

    static native void ejectDisk(int controller, int drive);

    /** Index of the running machine, or -1 if Fuse has not started yet. */
    static native int currentMachine();

    /** Queues a machine change; {@code index} indexes {@link #machineNames}. */
    static native void selectMachine(int index);

    /** Queues a machine reset. */
    static native void reset();

    /** Queues a non-maskable interrupt - the "magic button" on real hardware. */
    static native void nmi();

    /** Turns Fuse's tape traps, fast loading and loader acceleration on or off. */
    static native void setFastTape(boolean fast);

    /** Turns the tape loading noise on or off; only audible without fast loading. */
    static native void setTapeSound(boolean on);

    /** Whether inserting a tape types LOAD for you. */
    static native void setAutoLoad(boolean on);

    /** Issue 2 keyboard behaviour, which a few early 48K games depend on. */
    static native void setIssue2(boolean on);

    /** Renders with Fuse's monochrome palette. */
    static native void setBlackAndWhite(boolean on);

    /** Sound on or off; restarts Fuse's sound subsystem. */
    static native void setSound(boolean on);

    /** Emulation speed as a percentage; 100 is a real Spectrum. */
    static native void setSpeed(int percent);

    /** AY volume, 0 to 100; restarts Fuse's sound subsystem. */
    static native void setAyVolume(int volume);

    /** Beeper volume, 0 to 100; restarts Fuse's sound subsystem. */
    static native void setBeeperVolume(int volume);

    /** Writes the machine's state; libspectrum picks the format by extension. */
    static native void saveSnapshot(String path);

    /** Restores a state written by {@link #saveSnapshot}. */
    static native void loadSnapshot(String path);

    /**
     * Writes the last frame at half size: two little endian 32-bit integers
     * of width and height, then RGBA rows.
     */
    static native void saveThumbnail(String path);

    // --- screenshots and recording ---------------------------------------

    /**
     * The frame as palette indices, wrapped without a copy.
     *
     * Only valid inside {@link #onFrame} and {@link #onScreenshot}: the
     * emulation thread is inside the callback then, so it cannot be part way
     * through drawing the next one. Rows are {@link #frameStride()} apart,
     * which is wider than the frame on machines that do not use it all.
     */
    static native java.nio.ByteBuffer frameBuffer();

    static native int frameStride();

    /** The sixteen colours the indices mean, 0xAABBGGRR. */
    static native int[] palette();

    /** Whether {@link #onFrame} is called for every frame drawn. */
    static native void setRecording(boolean on);

    /** Asks for {@link #onScreenshot} on the next frame. */
    static native void captureScreenshot();

    /** One frame, on the emulation thread. Must return promptly. */
    static void onFrame(int width, int height) {
        Recorder.frame(width, height);
    }

    /** The frame a screenshot was asked for, on the emulation thread. */
    static void onScreenshot(int width, int height) {
        Recorder.screenshot(width, height);
    }

    /**
     * Queues a file for Fuse to open. Fuse identifies it itself, so this takes
     * snapshots, tapes, disks, cartridges, microdrive images and RZX
     * recordings alike. Must be a real filesystem path, not a content URI.
     */
    static native void openFile(String path);
}
