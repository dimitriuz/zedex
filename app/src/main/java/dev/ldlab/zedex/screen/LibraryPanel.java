package dev.ldlab.zedex.screen;

import android.app.Activity;
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
 * for the three corners of Android this - and it - are built around.
 */
final class LibraryPanel {

    private static final String TAG = "Zedex";

    /** What the library has to hand this: nothing but the fact that the
     *  panel appeared or went, since there is no layout to lend here and no
     *  fullscreen button or on-screen pad of the library's own to answer for. */
    interface Host {
        void panelChanged();
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

        apply();
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
                SettingsActivity.KEY_SECOND_SCREEN, false);

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
        host.panelChanged();
    }

    void close() {
        if (panel == null) return;

        SecondScreen going = panel;
        panel = null;

        going.dismiss();
        host.panelChanged();
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
