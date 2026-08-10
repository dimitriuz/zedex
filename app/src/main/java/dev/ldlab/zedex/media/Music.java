package dev.ldlab.zedex.media;

import dev.ldlab.zedex.FuseNative;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Playing a game's music without losing the game.
 *
 * <b>There is one machine.</b> A tune is played by the Spectrum running the
 * game's own driver - see {@link AySnapshot} - so starting one is loading a
 * snapshot, and loading a snapshot is the end of whatever was running. A
 * second emulator is not available to put it in: Fuse is one machine on one
 * thread, and the whole app is built around that.
 *
 * So the machine is put back afterwards. The state is written out before the
 * tune starts and read in again when it stops, which is the same pair of calls
 * the save states use - so "listen to the music" costs a player nothing but
 * the time, and the game is where they left it, mid-level and mid-jump.
 *
 * The saved state lives in the cache and is not a save state: it is a machine
 * put down for a minute, and must never be offered as one. Whether there is a
 * machine to put back is held in memory rather than read off the disk, which
 * is what stops a file left by a process that has since died being restored
 * over somebody's actual game.
 */
public final class Music {

    private static final String TAG = "Zedex";

    /**
     * Where the interrupted machine waits, and in what.
     *
     * {@code .szx} because it is the format that loses least - the save states
     * use it for the same reason - and because what is being kept here is not
     * a game somebody chose to save but everything about a machine that was
     * running a moment ago.
     */
    private static final String RESUME = "before-music.szx";

    /**
     * Whether a machine is waiting to be put back.
     *
     * <b>In memory, deliberately.</b> Asking the disk would answer yes for a
     * file left behind by a process that has since been killed - and
     * restoring that over whatever is running now is the one way this feature
     * could do real harm. A machine put aside belongs to the run that put it
     * there.
     */
    private static volatile boolean interrupted;

    private Music() {
    }

    /**
     * Starts a tune, putting the machine aside first.
     *
     * The two commands are queued and drained in order on the emulation
     * thread, so the state is written before the tune replaces it - which is
     * the whole of what makes this safe, and is not obvious from here.
     *
     * @return false when the song could not be turned into a machine at all,
     *         in which case nothing has been touched
     */
    public static boolean play(Context context, AyFile.Song song) {
        byte[] snapshot = AySnapshot.of(song);
        if (snapshot == null) return false;

        File tune = new File(context.getCacheDir(), "tune.z80");

        try (FileOutputStream out = new FileOutputStream(tune)) {
            out.write(snapshot);
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + tune, e);
            return false;
        }

        FuseNative.saveSnapshot(resume(context).getAbsolutePath());
        FuseNative.openFile(tune.getAbsolutePath());
        interrupted = true;

        return true;
    }

    /**
     * Stops, and puts the machine back where it was.
     *
     * Silent when there is nothing to put back - stopping music that was
     * never started is not a mistake worth reporting, and a player who
     * pressed stop twice should not be told off for it.
     */
    public static void stop(Context context) {
        if (!interrupted) return;

        interrupted = false;
        FuseNative.loadSnapshot(resume(context).getAbsolutePath());

        // <b>The file stays.</b> The load is a queued command carrying a path,
        // and the emulation thread reads it when it gets there - which is
        // after this method has returned. Deleting it here was the first
        // attempt and left the machine black: the restore found no file.
        // It is overwritten by the next tune and thrown away by forget.
    }

    /** Whether a machine is waiting to be put back. */
    public static boolean interrupted(Context context) {
        return interrupted;
    }

    /**
     * Throws the kept machine away.
     *
     * For the cases where putting it back would be wrong rather than
     * unwanted: the player has opened something else since, or the app is
     * starting and whatever this holds is from a process that is gone.
     */
    public static void forget(Context context) {
        interrupted = false;
        File resume = resume(context);

        if (resume.isFile() && !resume.delete()) {
            Log.w(TAG, "cannot delete " + resume);
        }
    }

    private static File resume(Context context) {
        return new File(context.getCacheDir(), RESUME);
    }
}
