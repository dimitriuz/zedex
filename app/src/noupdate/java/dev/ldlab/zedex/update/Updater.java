package dev.ldlab.zedex.update;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * The Play build's updater, which does nothing and says nothing.
 *
 * Play updates its own apps, and one from the store that downloaded an APK and
 * installed it would be against its policy — so the build that goes there does
 * not contain the code that could, and its manifest has
 * {@code REQUEST_INSTALL_PACKAGES} taken out. The switch in Settings hides
 * itself off the back of {@link #available}, so that build has no such setting
 * either.
 *
 * A stub in a source set of its own rather than a flag inside the real one:
 * a reviewer, or anybody reading the APK, finds nothing to ask about. The real
 * one is {@code src/sideload/java}, compiled into the debug and release builds;
 * see the {@code sourceSets} block in {@code app/build.gradle}.
 */
public final class Updater {

    /** Named the same as the real one so nothing else has to know which is here. */
    public static final String KEY_CHECK = "updateCheck";

    private Updater() {
    }

    public static boolean available(Context context) {
        return false;
    }

    public static void checkOnStart(Activity activity, SharedPreferences preferences) {
    }

    public static void resumeIfAllowed(Activity activity) {
    }
}
