package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.storage.Prefs;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.os.Bundle;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

/**
 * The library's own side of the two-screen handheld: finding a display and
 * putting a {@link SecondScreen} on it showing nothing but the selected
 * game's own path. Not {@link Panels} widened to cover this too - that class
 * asks its {@code Host} for an {@code EmulatorLayout} to lend, and {@code
 * LibraryActivity} has none; see CLAUDE.md, "A Host interface wider than
 * about four methods means the seam is wrong". A second small owner is what
 * this is instead, sharing only {@link Panels#free} with the emulator's own -
 * the one piece both truly need identically, and the one worth not
 * duplicating: see that method's own comment for why.
 *
 * Otherwise this mirrors {@link Panels} closely, on purpose - the same
 * watch/apply/close shape, and the same "look again, work out the whole
 * answer each time" {@link #apply}. See {@link Panels}'s own class comment
 * for the corners of Android this - and it - are built around, the fourth
 * of which ({@link #foreignScreenOpened}, {@link #topFocusReturned}) this
 * panel needs just as much as the emulator's own does: a manual is exactly as
 * foreign to the library as it is to the emulator, and both ask {@link
 * StepAside} the same question about their own display.
 */
final class LibraryPanel {

    private static final String TAG = "Zedex";

    /** What the library has to hand this: the fact that the panel appeared
     *  or went, since there is no layout to lend here and no fullscreen
     *  button or on-screen pad of the library's own to answer for - and a
     *  way to play whatever the panel is showing, which is the one thing
     *  this class has no business doing itself. {@link #apply} installs
     *  {@link #play} on the panel's own Play button; {@code Panels} has no
     *  such method to give its own panel, which is the whole of why Play
     *  never appears there - see {@code GameInfoView#setOnPlay}. */
    interface Host {
        void panelChanged();

        void play();

        /**
         * Back, pressed on the panel rather than on the library's own screen.
         *
         * Not this activity's own Back, which finishes at its root - on the
         * panel that is the app leaving both screens at once for a press on
         * the one that is only showing a game's details. What the library
         * gives here is everything its own Back means except that last step;
         * see {@code LibraryActivity.panelBack} and {@code
         * SecondScreen.setOnBack}.
         */
        void back();
    }

    private final Activity activity;
    private final SharedPreferences preferences;
    private final Host host;

    /** The panel, or null while there is none - either because it is not
     *  wanted, or because there is nowhere to put it. */
    private SecondScreen panel;

    /** Whatever {@link #setGameInfo} was last told, kept here as well as on
     *  the panel so one that appears later already knows; see {@link
     *  Panels}'s own {@code infoPath} for the identical reasoning. */
    private String infoPath;
    private String infoName;

    LibraryPanel(Activity activity, SharedPreferences preferences, Host host) {
        this.activity = activity;
        this.preferences = preferences;
        this.host = host;
    }

    /** Whether the game's details are over there rather than in the pane. */
    boolean inUse() {
        return panel != null;
    }

    void watch() {
        DisplayManager displays = activity.getSystemService(DisplayManager.class);
        if (displays != null) displays.registerDisplayListener(listener, null);

        // See Panels.watch: coming back to the front is what clears the
        // foreign latch, since nothing reports a foreign window closing.
        stepAside.hostResumed();

        apply();
        updateStepAside();
    }

    void unwatch() {
        DisplayManager displays = activity.getSystemService(DisplayManager.class);
        if (displays != null) displays.unregisterDisplayListener(listener);
    }

    /**
     * Puts the panel up or takes it down - see {@link Panels#apply}, which
     * this is the library's own version of, corner cases and all: a panel
     * that is up stays up unless it is not wanted any more or its display
     * has really gone.
     */
    void apply() {
        boolean wanted = preferences.getBoolean(
                Prefs.KEY_SECOND_SCREEN, false);

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

        Display display = Panels.free(activity);
        if (display == null) return;

        // No views to lend - see the class comment - so an empty array
        // rather than anything borrowed from this activity's own layout.
        panel = new SecondScreen(activity, display, new View[0]);

        try {
            panel.show();
        } catch (WindowManager.InvalidDisplayException e) {
            // The panel went away between being listed and being shown.
            Log.w(TAG, "second screen vanished", e);
            close();
            return;
        }

        panel.setGameInfo(infoPath, infoName);
        panel.setOnForeignScreen(this::foreignScreenOpened);
        panel.setOnPlay(host::play);
        panel.setOnBack(host::back);

        // A fresh panel always shows itself first, so a reason to be out of
        // the way that is already true has to be applied straight away -
        // a display replugged while one of our own screens is on it, say.
        // The same line {@code Panels.apply} ends with, and missing here.
        updateStepAside();

        host.panelChanged();
    }

    void close() {
        if (panel == null) return;

        SecondScreen going = panel;
        panel = null;

        // Whatever this panel was waiting to come back from does not carry
        // over to whatever replaces it - see StepAside.panelClosed.
        stepAside.panelClosed();

        going.dismiss();
        host.panelChanged();
    }

    /**
     * One of the app's own screens, on the panel when there is one.
     *
     * The same thing {@code Panels.openOwnScreen} does for the emulator, and
     * here for the same reason: the library opened Settings with a plain
     * {@code startActivity}, so it landed on the main screen, on top of this
     * activity, in this activity's own task - and there was no way back from
     * it. Opened from the emulator, where it already went through {@code
     * Panels}, the same screen opens on the panel and Back works.
     *
     * A task of its own, because a task lives on one display: launched into
     * the library's, the settings screen would take the library with it to the
     * panel and leave the first screen empty - which is exactly the note
     * {@code Panels.openOwnScreen} carries about the machine.
     *
     * The panel steps aside for it through the application's lifecycle
     * callbacks, the same as for any other screen of ours.
     */
    void openOwnScreen(Intent intent) {
        Display display = panel == null ? null : panel.getDisplay();

        if (display == null) {
            activity.startActivity(intent);
            return;
        }

        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(display.getDisplayId());
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

    /**
     * Told by {@code SecondScreen} the moment a manual actually lands on
     * this panel's own display - see {@link Panels}'s class comment, the
     * fourth corner.
     */
    private void foreignScreenOpened() {
        stepAside.foreignOpened();
        updateStepAside();
    }

    /**
     * Whether this panel has to be out of the way, and what is asking - the
     * app's own screens on its display, and a manual's viewer, which reaches
     * no lifecycle callback of ours at all.
     *
     * The same object the emulator's panel uses for the same question, and
     * that is the point of it being one: this panel learned the first half
     * the hard way - Settings and the full-screen viewer both open in a task
     * of their own over there, and when one finishes there is nothing left on
     * that display but Android's own launcher, which this never used to put
     * itself back after. {@link StepAside} carries both that and the fault
     * the other half of the rule caused, so neither panel can hold only one
     * of them.
     */
    private final StepAside stepAside = new StepAside();

    /**
     * The one place the panel is hidden or shown for something covering it,
     * from both reasons together - the same shape as {@code
     * Panels.updateStepAside}, so the two can never disagree about whether the
     * panel should be up.
     */
    private void updateStepAside() {
        if (panel == null) return;

        if (stepAside.hidden()) panel.hide();
        else panel.show();
    }

    /**
     * Every other screen of ours steps the panel aside while it is up, and
     * puts it back when it goes.
     *
     * Registered by {@code LibraryActivity} for as long as it is running - see
     * its onCreate and onDestroy, which is where {@code EmulatorActivity} does
     * the same for the emulator's panel.
     */
    Application.ActivityLifecycleCallbacks lifecycle() {
        return others;
    }


    /**
     * Whether one of our own screens is on the panel's display rather than
     * somewhere else.
     *
     * <b>Alive is not the same as in the way.</b> Screens are added when they
     * start and removed only when they are destroyed - because a foreign
     * window covering one merely stops it, and the picker taught us what
     * treating that as gone costs. But a screen that is stopped and on
     * <em>another</em> display is not over this panel at all, and the machine
     * is exactly that: {@code EmulatorActivity} is launchMode singleInstance
     * and lives as long as the process, so once a game had been played the
     * library's panel counted it for ever and never showed again. That is
     * "closing a game closes the second screen too".
     *
     * A display is what an activity is on, asked of the activity itself. Null
     * before it has a window, which cannot be one of ours on this panel yet.
     */
    private boolean onThePanel(Activity other) {
        if (panel == null) return false;

        Display mine = panel.getDisplay();
        Display theirs = other.getDisplay();

        return mine != null && theirs != null
               && mine.getDisplayId() == theirs.getDisplayId();
    }

    private final Application.ActivityLifecycleCallbacks others =
            new Application.ActivityLifecycleCallbacks() {

        @Override
        public void onActivityStarted(Activity started) {
            if (started == activity) return;
            if (!onThePanel(started)) return;

            stepAside.opened(started);
            updateStepAside();
        }

        @Override
        public void onActivityDestroyed(Activity destroyed) {
            if (destroyed == activity) return;

            stepAside.closed(destroyed);
            updateStepAside();
        }

        @Override public void onActivityCreated(Activity created, Bundle state) { }
        @Override public void onActivityResumed(Activity resumed) { }
        @Override public void onActivityPaused(Activity paused) { }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle out) { }

        // Not the pair of onActivityStarted, deliberately - see StepAside.
        @Override public void onActivityStopped(Activity stopped) { }
    };

    /**
     * {@code LibraryActivity}'s own {@code onTopResumedActivityChanged(true)}
     * - the nearest signal available for a manual's viewer being dismissed,
     * which gives this app no callback of its own; see {@link Panels}'s
     * class comment, the fourth corner, and its identical {@code
     * topFocusReturned} for why this and not {@code onResume} is the hook.
     * Cheap to call every time the activity is the focused one again, since
     * it does nothing unless a manual was actually the reason the panel
     * stepped aside.
     */
    void topFocusReturned() {
        stepAside.foreignClosed();
        updateStepAside();
    }

    /**
     * Told whenever the selection changes - see {@code
     * LibraryActivity.updatePane}. Null for both when nothing is selected,
     * or the selection has no path the store could look up: a folder, an
     * archive, or a file reached from inside one.
     */
    void setGameInfo(String relativePath, String name) {
        infoPath = relativePath;
        infoName = name;
        if (panel != null) panel.setGameInfo(infoPath, infoName);
    }

    /** Stops the panel's own video without taking the panel down - see
     *  {@code LibraryActivity.onPause}, one of the moments a video must not
     *  be left running that has nothing to do with the selection. */
    void release() {
        if (panel != null) panel.pauseVideo();
    }

    /** A panel appearing or going away: all three do the same thing, look
     *  again - see {@link Panels}'s own identical listener. */
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
}
