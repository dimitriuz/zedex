package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.view.EmulatorLayout;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * The other screen, on a handheld built with two.
 *
 * A device like the AYN Thor has a second landscape panel under the first, and
 * the arrangement that suits it is the machine alone on top and everything you
 * touch below: the keyboard, the joystick, the lamps and the quick bar. The
 * views themselves move rather than a second set being built — see
 * {@link EmulatorLayout#setLentAway} — so a latched shift and an open bar group
 * survive the trip, and every caller that already talks to them goes on working.
 *
 * This class is the <em>window</em> half of that, which is the part that is
 * about Android rather than about the Spectrum: finding a display worth using,
 * putting a {@link SecondScreen} on it, taking it down again, noticing panels
 * being plugged in, and the rule about which display the app's own screens open
 * on. {@link SecondScreen} is what the panel looks like.
 *
 * Three of Android's awkward corners live here, and each cost an afternoon:
 *
 * <ul>
 * <li><b>A task lives on one display.</b> Launching the settings screen normally
 *     took the machine to the panel with it and left the first screen empty, so
 *     {@link #openOwnScreen} starts a task of its own.</li>
 * <li><b>A presentation is drawn above the activity windows on its display.</b>
 *     A screen opened on the panel would be behind the keyboard, so the panel
 *     steps aside while any other screen of ours is up. There is no result to
 *     wait for — a new task cannot return one — so it is counted through the
 *     application's lifecycle callbacks, which also covers a screen dismissed
 *     some way of Android's own.</li>
 * <li><b>Android reports odd things in passing.</b> The activity briefly claims
 *     to be on the panel itself while an input method is being sorted out. Taking
 *     the panel down on that reading once left nothing to put it back, so
 *     {@link #apply} only closes a panel that is genuinely unwanted or whose
 *     display has really gone.</li>
 * </ul>
 */
public final class Panels {

    private static final String TAG = "Zedex";

    /** What the machine's side of the app has to lend this one. */
    public interface Host {
        /** The layout whose views the panel borrows. */
        EmulatorLayout layout();

        /**
         * The panel went up or came down. Three things follow from it and they
         * are all the activity's: the bar stops fading, the fullscreen button
         * comes and goes, and the on-screen pad steps aside for the real one.
         */
        void panelChanged();
    }

    private final Activity activity;
    private final SharedPreferences preferences;
    private final Host host;

    /** The panel, or null while the controls are on the machine's own screen. */
    private SecondScreen panel;

    /** How many other screens of ours are up; the panel hides while any is. */
    private int ownScreens;

    public Panels(Activity activity, SharedPreferences preferences, Host host) {
        this.activity = activity;
        this.preferences = preferences;
        this.host = host;
    }

    /** Whether the controls are over there. */
    public boolean inUse() {
        return panel != null;
    }

    // --- coming and going ----------------------------------------------------

    /** Starts watching for panels; the activity calls this from onResume. */
    public void watch() {
        DisplayManager displays = activity.getSystemService(DisplayManager.class);
        if (displays != null) displays.registerDisplayListener(listener, null);

        apply();
    }

    /** Stops watching; from onPause. */
    public void unwatch() {
        DisplayManager displays = activity.getSystemService(DisplayManager.class);
        if (displays != null) displays.unregisterDisplayListener(listener);
    }

    /** The application-wide callbacks that step the panel aside. */
    public Application.ActivityLifecycleCallbacks lifecycle() {
        return others;
    }

    /**
     * Puts the keyboard, the lamps and the bar on a second screen, or brings
     * them back.
     *
     * Called from every place the answer can change — the setting, coming back
     * to the front, a panel being plugged in or unplugged — and works out the
     * whole answer each time rather than tracking what changed, since there are
     * only two states and the cost is a comparison.
     */
    public void apply() {
        boolean wanted = preferences.getBoolean(
                SettingsActivity.KEY_SECOND_SCREEN, false);

        // A panel that is up stays up unless it is not wanted any more or the
        // display it is on has really gone. Nothing else is a reason; see the
        // class comment about what Android reports in passing.
        if (panel != null) {
            Display showing = panel.getDisplay();
            DisplayManager displays =
                    activity.getSystemService(DisplayManager.class);

            boolean gone = showing == null || displays == null
                    || displays.getDisplay(showing.getDisplayId()) == null;

            if (wanted && !gone) return;
            close();
        }

        if (!wanted) return;

        Display display = free();
        if (display == null) return;

        // Lending first: the views have to be parentless before another window
        // can adopt them.
        host.layout().setLentAway(true);
        panel = new SecondScreen(activity, display, host.layout().lendable());

        try {
            panel.show();
        } catch (WindowManager.InvalidDisplayException e) {
            // The panel went away between being listed and being shown.
            Log.w(TAG, "second screen vanished", e);
            close();
            return;
        }

        host.panelChanged();
    }

    public void close() {
        if (panel == null) return;

        SecondScreen going = panel;
        panel = null;

        going.dismiss();
        host.layout().setLentAway(false);

        host.panelChanged();
    }

    /**
     * A display worth putting controls on: Android's own definition, which is
     * the displays that are not the one the activity is on and are meant to be
     * presented to. The last is taken, since that is the one most recently
     * attached.
     */
    private Display free() {
        DisplayManager displays = activity.getSystemService(DisplayManager.class);
        if (displays == null) return null;

        Display[] found = displays.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Display here = activity.getDisplay();

        // The last one attached, but never the one we are on ourselves: Android
        // can put the activity on the panel - it launches an app where the last
        // touch was - and a window over the machine's own screen would take the
        // picture away rather than the furniture.
        for (int i = found.length - 1; i >= 0; i--) {
            if (here == null || found[i].getDisplayId() != here.getDisplayId()) {
                return found[i];
            }
        }

        return null;
    }

    // --- opening the app's other screens -------------------------------------

    /**
     * Opens one of the app's own screens — settings, the hotkeys, a profile —
     * where the controls are.
     *
     * With a panel in use that is the panel: a screen asked for by a thumb on
     * one display should not appear on the other, and the machine's screen is
     * the machine's.
     */
    public void openOwnScreen(Intent intent) {
        Display display = panel == null ? null : panel.getDisplay();

        if (display == null) {
            activity.startActivity(intent);
            return;
        }

        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(display.getDisplayId());

        // A task of its own, because a task lives on one display: launched into
        // ours, the settings screen took the machine with it to the panel and
        // left the first screen empty.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            activity.startActivity(intent, options.toBundle());
        } catch (RuntimeException e) {
            // A display that will not host activities - then it goes where
            // everything else does.
            Log.w(TAG, "cannot open on the second screen", e);
            activity.startActivity(new Intent(intent).setFlags(0));
        }
    }

    /** A panel appearing or going away: all three do the same thing, look again. */
    private final DisplayManager.DisplayListener listener =
            new DisplayManager.DisplayListener() {

        @Override
        public void onDisplayAdded(int displayId) {
            apply();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            apply();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            apply();
        }
    };

    /** Every other screen of ours steps the panel aside while it is up. */
    private final Application.ActivityLifecycleCallbacks others =
            new Application.ActivityLifecycleCallbacks() {

        @Override
        public void onActivityStarted(Activity started) {
            if (started == activity) return;

            ownScreens++;
            if (panel != null) panel.hide();
        }

        @Override
        public void onActivityStopped(Activity stopped) {
            if (stopped == activity || ownScreens == 0) return;

            if (--ownScreens == 0 && panel != null) panel.show();
        }

        @Override
        public void onActivityCreated(Activity created, Bundle state) { }

        @Override
        public void onActivityResumed(Activity resumed) { }

        @Override
        public void onActivityPaused(Activity paused) { }

        @Override
        public void onActivitySaveInstanceState(Activity a, Bundle state) { }

        @Override
        public void onActivityDestroyed(Activity destroyed) { }
    };
}
