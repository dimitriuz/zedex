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

    /**
     * Queues a typed character rather than a key.
     *
     * The system keyboard commits text instead of pressing keys, and a character
     * needs no translation on the way in: Fuse's own key values are ASCII for
     * everything printable and its table turns one into the Spectrum keys it
     * takes, so a colon arrives as SYMBOL SHIFT and Z without anything here
     * knowing that.
     */
    static native void character(int character, boolean pressed);

    /**
     * Whether the Spectrum has any use for this key at all.
     *
     * Asked before a key event is swallowed: the volume and media keys belong
     * to the phone, and consuming one so that Fuse can ignore it is how the
     * volume buttons stopped working. Safe to call at any time, machine or no
     * machine — it reads Fuse's static keysym table and nothing else.
     */
    static native boolean mapsKey(int keycode);

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

    // --- what the machine is busy with -------------------------------------

    /*
     * Bits of {@link #activity}, in step with ACTIVITY_* in
     * native/ui/android/android_status.c. The tape and the disks are what Fuse
     * reports through its own status bar; the AY is read off its registers; the
     * last two are ports the machine has read since the previous frame.
     */
    static final int ACTIVITY_TAPE = 1;
    static final int ACTIVITY_DISK = 1 << 1;
    static final int ACTIVITY_AY = 1 << 2;
    static final int ACTIVITY_KEYBOARD = 1 << 3;
    static final int ACTIVITY_JOYSTICK = 1 << 4;
    static final int ACTIVITY_MOUSE = 1 << 5;
    static final int ACTIVITY_CARD = 1 << 6;

    /**
     * The same five bits again, this far up, mean "and it is writing rather
     * than reading". Only some of them can say: a keyboard is only ever read,
     * and what the AY does is sound on its way out.
     */
    static final int ACTIVITY_WRITING = 8;

    /**
     * What the machine is doing right now, as ACTIVITY_* bits.
     *
     * One word the emulation thread publishes at the end of every frame, so
     * this neither queues nor blocks and is safe to poll while a frame is
     * being drawn. Zero before Fuse has started.
     */
    static native int activity();

    /**
     * How loud the AY's three channels are, 0 to 15 each, as three bytes: A in
     * the bottom, then B, then C.
     *
     * A channel counts only while the mixer has not switched off both its tone
     * and its noise, so a game that stops using one and leaves its amplitude
     * behind does not hold the meter up. A channel following the envelope
     * generator reads as full: where the envelope has got to is not something
     * the registers say.
     */
    static native int ayLevels();

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

    /**
     * The tape deck's transport. Stop keeps the position, so it is also the
     * pause Fuse does not have separately, and rewind goes back to block zero
     * rather than winding.
     */
    static native void tapePlay(boolean playing);

    static native void tapeRewind();

    /** Whether the deck is running, from the once-a-frame snapshot. */
    static native boolean tapePlaying();

    /**
     * The tape's blocks as the browser lists them, already numbered and
     * described by libspectrum - "3. Program: Tujad" - and capped at a hundred
     * and twenty-eight, since a TZX can carry thousands of pulse blocks.
     */
    static native String[] tapeBlocks();

    /** Which of them the deck is at, or -1 with no tape. */
    static native int tapeBlock();

    /** Winds to one of them. */
    static native void tapeBlockSelect(int block);

    /**
     * Writes one byte into the sixteen bit address space as it is paged now,
     * which is what POKE means. Queued like every other input, so it lands
     * between frames rather than under the Z80's feet.
     */
    static native void poke(int address, int value);

    /**
     * The Kempston mouse.
     *
     * Only plugged in while it is asked for: it answers three ports - 0xfadf,
     * 0xfbdf and 0xffdf - that a game might read for something else, and Fuse
     * has to be told through periph_update() either way.
     *
     * The movement is <b>relative</b>, in the mouse's own units and in screen
     * terms - down is positive - because that is what a Kempston mouse is: a
     * pair of counters the program does its own arithmetic on, with no notion of
     * where any pointer is.
     */
    static native void setKempstonMouse(boolean on);

    static native void mouseMove(int dx, int dy);

    /** 0 is the left button, 1 the right. */
    static native void mouseButton(int which, boolean down);

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

    // --- the DivMMC --------------------------------------------------------

    /*
     * A memory card interface, and the way to run esxDOS: it brings its own
     * 8K firmware in an EPROM, its own 128K of RAM, and a card holding an
     * ordinary FAT filesystem full of games.
     *
     * The firmware is the user's to supply - esxDOS is not ours to ship - and
     * it has to be in hand before the interface goes in, because a DivMMC with
     * a blank EPROM pages itself into the machine's reset and hangs it. See
     * native/ui/android/android_card.c, which does the flashing the firmware's
     * own installer tape would do.
     */

    /** Plugs the interface in or takes it out. Hard resets the machine. */
    static native void setDivmmc(boolean on);

    /** Whether Fuse has the interface right now, from the frame's snapshot. */
    static native boolean hasDivmmc();

    /** Reads an 8K firmware image and writes it into the EPROM. */
    static native void loadDivmmcFirmware(String path);

    /**
     * Puts a card in. The image has to be an HDF - that is the only format
     * libspectrum's IDE code reads - and it is written to in place, so it
     * belongs somewhere permanent rather than in the cache. See
     * {@link CardImage}.
     */
    static native void insertCard(String path);

    /** Writes the machine's changes back into the image. */
    static native void commitCard();

    /** Commits, then takes the card out. */
    static native void ejectCard();

    /** The card in the slot, or "" when there is none. */
    static native String cardName();

    /** Index of the running machine, or -1 if Fuse has not started yet. */
    static native int currentMachine();

    /** Queues a machine change; {@code index} indexes {@link #machineNames}. */
    static native void selectMachine(int index);

    /** Queues a machine reset. */
    static native void reset();

    /**
     * Stops the machine, or lets it go again.
     *
     * Not queued, unlike everything else here: the emulation thread has to see
     * it while it is sitting in the paused loop, and a queued command is only
     * read between frames.
     */
    static native void setPaused(boolean paused);

    /** Queues a non-maskable interrupt - the "magic button" on real hardware. */
    static native void nmi();

    /**
     * How hard to push a tape: 0 real time, 1 the ROM's loaders, 2 custom
     * loaders too. Three of Fuse's settings in the three combinations that are
     * worth having — see OPTION_LOADER_ACCELERATION in android_bridge.c.
     */
    static native void setLoaderAcceleration(int level);

    /** Whether Fuse starts and stops the tape when it spots a loader. */
    static native void setDetectLoader(boolean on);

    /** Turns the tape loading noise on or off; only audible without fast loading. */
    static native void setTapeSound(boolean on);

    /** Whether inserting a tape types LOAD for you. */
    static native void setAutoLoad(boolean on);

    /** Issue 2 keyboard behaviour, which a few early 48K games depend on. */
    static native void setIssue2(boolean on);

    // --- the picture filter ------------------------------------------------

    /*
     * Which number {@link #setFilter} is setting. In step with
     * OPTION_FILTER_SCANLINES and the six after it in android_bridge.c: the
     * native side adds the index to the first of them, so the order here is the
     * order there.
     *
     * Scanlines and the CRT glass are two switches rather than one choice,
     * because a tube has both and either can be had without the other. A dot
     * matrix is a third: it is a different panel, not a different tube.
     */
    static final int FILTER_SCANLINES = 0;
    static final int FILTER_CRT = 1;
    static final int FILTER_VIDEO = 2;
    static final int FILTER_SHARPNESS = 3;
    static final int FILTER_SCANLINE = 4;
    static final int FILTER_CURVE = 5;
    static final int FILTER_MASK = 6;
    static final int FILTER_GLOW = 7;
    static final int FILTER_BLEED = 8;
    static final int FILTER_NOISE = 9;
    static final int FILTER_DOTS = 10;
    static final int FILTER_GAP = 11;
    static final int FILTER_BACKLIGHT = 12;

    /** Values of FILTER_VIDEO: how the picture left the machine. */
    static final int VIDEO_RGB = 0;
    static final int VIDEO_COMPOSITE = 1;
    static final int VIDEO_RF = 2;

    /**
     * Sets one of the filters' numbers: {@code which} is a FILTER_* index.
     * The two switches take 0 or 1, FILTER_VIDEO a VIDEO_* value, and the
     * strengths 0 to 100.
     */
    static native void setFilter(int which, int value);

    /**
     * How big the picture is drawn: {@code pixels} device pixels for every
     * emulated one, or {@link #SCALE_FIT} to fill the space.
     *
     * One number rather than one per orientation, because which of the two
     * applies is a question about the device that the renderer cannot answer -
     * the box it draws into is wider than it is tall in portrait too. Push this
     * again when the orientation changes.
     *
     * A scale too big for the window is reduced until it fits, so this can be
     * set from a list built for the whole display and still be right in a
     * layout that only gives the screen half of it.
     */
    static native void setScale(int pixels);

    /**
     * How much of the Spectrum's border to show: 0 all of it, 1 a quarter,
     * 2 none. Cropping it gives the picture the rest of the window, and all
     * three are exactly 4:3, so nothing about fitting it changes - only how
     * many emulated pixels there are to scale.
     */
    static native void setBorder(int border);

    /** The scale that fits the picture to the space, whatever size that is. */
    static final int SCALE_FIT = 0;

    /** Renders with Fuse's monochrome palette. */
    static native void setBlackAndWhite(boolean on);

    /** Sound on or off; restarts Fuse's sound subsystem. */
    static native void setSound(boolean on);

    /** Emulation speed as a percentage; 100 is a real Spectrum. */
    static native void setSpeed(int percent);

    /** AY volume, 0 to 100; restarts Fuse's sound subsystem. */
    static native void setAyVolume(int volume);

    /**
     * AY stereo separation: 0 mono, 1 ACB, 2 ABC.
     *
     * ACB puts channel A left, C in the middle and B right; ABC puts A left, B
     * in the middle and C right. Either makes Fuse's output two channels
     * instead of one. Restarts the sound subsystem, which is what reads it.
     */
    static native void setAyStereo(int separation);

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
