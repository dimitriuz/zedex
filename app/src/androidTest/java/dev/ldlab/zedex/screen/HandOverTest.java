package dev.ldlab.zedex.screen;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.Screen;
import dev.ldlab.zedex.storage.Prefs;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The launch that does not stay in the library.
 *
 * {@code LibraryActivity} is the launcher, and it decides for itself whether
 * it belongs on screen: with the library switched off, or with no content
 * folder to browse, it starts the machine and finishes from {@code onCreate}.
 * An activity finished there is never started - it goes straight to {@code
 * onDestroy} - so that method alone runs against a half-built activity, and
 * anything onCreate had not reached yet is null in it.
 *
 * That is a whole branch the rest of the suite cannot see. Every other test of
 * the library screen asks for it with {@code EXTRA_FROM_MENU}, precisely so it
 * appears whatever the setting says - which is right for what those tests are
 * about and means not one of them takes this path. It shipped a crash on every
 * launch for anybody who starts in the machine, and the suite was green.
 *
 * The assertion is thin on purpose: the machine coming up is the whole of what
 * is being claimed. The crash it guards needs no assertion at all - the
 * instrumentation runs inside the app's own process, so a fatal exception on
 * the main thread takes this test down with it and the run says so loudly.
 */
@RunWith(AndroidJUnit4.class)
public class HandOverTest {

    /** Long enough for a cold start of the machine on a slow emulator. */
    private static final long ARRIVES = 20_000;

    private SharedPreferences preferences;
    private boolean libraryWas;

    /**
     * Put back on the way out - it is the user's own device and the setting
     * they left on is the one they were using.
     *
     * Only on the way out, though: should the crash this guards ever come
     * back, the process dies here and nothing restores anything. A bench left
     * starting in the machine is a small price against a test that could not
     * fail.
     */
    @After
    public void putTheSettingBack() {
        if (preferences != null) {
            preferences.edit().putBoolean(Prefs.KEY_LIBRARY, libraryWas).commit();
        }
    }

    @Test
    public void alaunchThatHandsOverToTheMachineSurvivesItsOwnDestroy() {
        Context context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);

        libraryWas = preferences.getBoolean(Prefs.KEY_LIBRARY, true);

        // commit, not apply: the activity below reads this on the way up, and
        // it is starting now.
        preferences.edit().putBoolean(Prefs.KEY_LIBRARY, false).commit();

        Intent library = new Intent(context, LibraryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(library, Screen.here());

        assertTrue("the machine never came up, so the launcher screen either"
                   + " stayed where it was or took the app down on its way"
                   + " out - see this class's own comment for which of those"
                   + " has happened before",
                   waitForTheMachine());
    }

    private static boolean waitForTheMachine() {
        long until = SystemClock.uptimeMillis() + ARRIVES;

        while (SystemClock.uptimeMillis() < until) {
            if (machineIsUp()) return true;
            SystemClock.sleep(200);
        }

        return machineIsUp();
    }

    /** Asked on the main thread, which is the only one the registry answers
     *  on. */
    private static boolean machineIsUp() {
        boolean[] up = { false };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (activity instanceof EmulatorActivity) up[0] = true;
            }
        });

        return up[0];
    }
}
