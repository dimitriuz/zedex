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
 * for the corners of Android this - and it - are built around, the fourth
 * of which ({@link #foreignScreenOpened}, {@link #topFocusReturned}) this
 * panel needs just as much as the emulator's own does, despite having no
 * {@code ownScreens} of its own: a manual is exactly as foreign to the
 * library as it is to the emulator.
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

    /** Whether a manual is up on this panel's own display right now - see
     *  {@link Panels}'s class comment, the fourth corner, which applies
     *  here identically even though this panel has no {@code ownScreens}
     *  of its own to combine it with: the library never opens one of its
     *  own screens onto this display, so a manual is the only thing that
     *  ever asks this panel to step aside. */
    private boolean foreignScreenUp;

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
        panel.setOnForeignScreen(this::foreignScreenOpened);
        host.panelChanged();
    }

    void close() {
        if (panel == null) return;

        SecondScreen going = panel;
        panel = null;

        // Whatever this panel was waiting to come back from does not carry
        // over to whatever replaces it - see foreignScreenUp's own comment.
        foreignScreenUp = false;

        going.dismiss();
        host.panelChanged();
    }

    /**
     * Told by {@code SecondScreen} the moment a manual actually lands on
     * this panel's own display - see {@link Panels}'s class comment, the
     * fourth corner.
     */
    private void foreignScreenOpened() {
        foreignScreenUp = true;
        if (panel != null) panel.hide();
    }

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
        if (!foreignScreenUp) return;

        foreignScreenUp = false;
        if (panel != null) panel.show();
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
