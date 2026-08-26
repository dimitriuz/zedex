package dev.ldlab.zedex;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * The one thing no single screen owns: which of this app's activities are
 * alive right now.
 *
 * <b>For leaving.</b> {@code ActivityManager.getAppTasks} answers only for
 * the tasks that are visible - a stopped one, like the library's while the
 * machine is in front of it, is not among them. Measured on API 36: quit from
 * the machine with the library behind left its task standing, and Android
 * restarted the process to draw it, so Quit read as "back to the library".
 * Leaving therefore finishes every live activity rather than only what
 * {@code getAppTasks} names - see {@code screen.Quit}.
 *
 * <b>Started, not created.</b> An activity finished from {@code onCreate}
 * never starts and goes straight to destroyed, and there is nothing to finish
 * for it. The set is touched on the main thread only: lifecycle callbacks run
 * there, and so does the one thing that reads it.
 */
public final class ZedexApplication extends Application {

    private final Set<Activity> live = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
                live.add(activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                live.remove(activity);
            }
        });
    }

    /**
     * Finishes every activity of this process - the stopped ones among them,
     * which {@code getAppTasks} never names.
     */
    public void finishEveryActivity() {
        for (Activity activity : new ArrayList<>(live)) {
            activity.finish();
        }
    }
}
