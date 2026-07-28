package com.fusemobile;

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

    /**
     * Queues a file for Fuse to open. Fuse identifies it itself, so this takes
     * snapshots, tapes, disks, cartridges, microdrive images and RZX
     * recordings alike. Must be a real filesystem path, not a content URI.
     */
    static native void openFile(String path);
}
