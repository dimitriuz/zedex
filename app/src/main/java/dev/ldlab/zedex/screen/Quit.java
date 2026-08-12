package dev.ldlab.zedex.screen;

import android.app.Activity;
import android.app.ActivityManager;

/**
 * Leaving the app, which is more than one task's worth of leaving.
 *
 * <b>{@code finishAndRemoveTask} removes one task, and this app has two.</b>
 * {@code EmulatorActivity} is {@code launchMode="singleInstance"} - so that a
 * second game cannot stand up a second Fuse core - and a singleInstance
 * activity is always alone in a task of its own, whatever launched it. Start
 * in the library and open a game and there are two tasks: the library's and
 * the machine's. Quit finished the machine's and killed the process, and
 * Android did exactly what it should with a task still sitting there visible:
 * started the app again to draw it. The machine went, the library came back,
 * and Quit looked broken - which it was, from the only place it was reachable
 * from.
 *
 * So every task of ours goes, not just this one. {@code getAppTasks} answers
 * for this app alone and needs no permission, and each is removed the same way
 * the single one used to be: off the recents list too, because a task left
 * there offers to resume a machine whose process has gone.
 *
 * The process last, and by {@code exit} rather than by letting the activities
 * finish: Fuse's core is C with global state and no shutdown path worth
 * trusting, and the one thing "Quit" has to mean is that nothing of it is
 * still running.
 */
public final class Quit {

    private Quit() {
    }

    /** Every task, and then the process. Never returns. */
    public static void everything(Activity activity) {
        ActivityManager manager = activity.getSystemService(ActivityManager.class);

        if (manager != null) {
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                task.finishAndRemoveTask();
            }
        } else {
            // Nothing in the docs says this can be null, and one activity's
            // task going is still better than none.
            activity.finishAndRemoveTask();
        }

        Runtime.getRuntime().exit(0);
    }
}
