package dev.ldlab.zedex.feedback;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Keeps the last crash, so the next start can offer to send it.
 *
 * Until this existed a crash left nothing behind. Play reports the ones from
 * Play installs and says nothing about the APK from Releases, which is the build
 * most likely to be running something unfinished — so the reports that would be
 * most use were exactly the ones nobody could see.
 *
 * One file, in the app's own storage, holding the stack trace and the same
 * diagnostics {@link Diagnostics} builds. It is written when the process is
 * already dying, so it does as little as possible and hands straight on to the
 * handler that was there before: whatever Android was going to do about the crash
 * still happens, and the report is a side effect rather than an interception.
 *
 * Nothing is sent. The file sits there until the user is asked and says yes, or
 * says no, at which point it is deleted; see {@link Feedback}.
 */
public final class Crashes {

    private static final String TAG = "Zedex";

    /** One file, overwritten: the newest crash is the one worth reading. */
    private static final String FILE = "crash.txt";

    private Crashes() {
    }

    /**
     * Installs the handler. Process-wide, so it covers every activity and thread,
     * and calling it more than once is harmless.
     */
    public static void watch(Context context) {
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        // Ours already, from an earlier activity in the same process.
        if (previous instanceof Handler) return;

        Thread.setDefaultUncaughtExceptionHandler(new Handler(app, previous));
    }

    private static final class Handler implements Thread.UncaughtExceptionHandler {

        private final Context context;
        private final Thread.UncaughtExceptionHandler previous;

        Handler(Context context, Thread.UncaughtExceptionHandler previous) {
            this.context = context;
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable problem) {
            try {
                save(context, thread, problem);
            } catch (Throwable ignored) {
                // A failure in here must not replace the crash being reported.
            }

            if (previous != null) previous.uncaughtException(thread, problem);
        }
    }

    private static void save(Context context, Thread thread, Throwable problem) {
        StringWriter trace = new StringWriter();
        problem.printStackTrace(new PrintWriter(trace));

        String text = "thread=" + thread.getName() + '\n'
                    + Diagnostics.report(context) + '\n'
                    + trace;

        try {
            Files.write(file(context).toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "cannot write the crash report", e);
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    /** The last crash, or null. Read once, at startup. */
    public static String pending(Context context) {
        File file = file(context);
        if (!file.isFile()) return null;

        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "cannot read the crash report", e);
            forget(context);
            return null;
        }
    }

    /**
     * Throws it away, whether it was sent or refused.
     *
     * Asked once and only once: a report the user has declined is not a better
     * question the next time the app starts.
     */
    public static void forget(Context context) {
        File file = file(context);
        if (file.isFile() && !file.delete()) {
            Log.w(TAG, "cannot delete " + file);
        }
    }
}
