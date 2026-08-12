package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.storage.Prefs;
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
 * The other screen of a two-screen handheld: finding a display, putting a
 * {@link SecondScreen} on it, taking it down, and the rule about which display
 * the app's own screens open on. SecondScreen is what the panel looks like.
 *
 * Three of Android's corners live here, each of which cost an afternoon.
 *
 * <ul>
 * <li>A task lives on one display, so launching the settings normally took the
 *     machine to the panel and left the first screen empty. {@link #openOwnScreen}
 *     starts a task of its own.</li>
 * <li>A presentation draws above the activity windows on its display, so a screen
 *     opened on the panel would sit behind the keyboard. The panel steps aside
 *     while any other screen of ours is up, counted through the application's
 *     lifecycle callbacks because a new task cannot return a result.</li>
 * <li>Android reports the activity as being on the panel in passing, while an
 *     input method is sorted out. Acting on that reading once took the panel down
 *     with nothing to put it back, so {@link #apply} closes only a panel that is
 *     unwanted or whose display has really gone.</li>
 * <li>A manual opened from the panel's own game info is put on the panel's
 *     display too - see {@code Manuals.open} - and the same first corner above
 *     applies to it: it would sit behind the panel exactly as one of the app's
 *     own screens would. But it is a <em>foreign</em> activity, and one never
 *     reaches {@link #lifecycle}'s callbacks, which only ever see activities of
 *     ours - so nothing here would notice it open, and nothing would notice it
 *     close either, a foreign activity finishing giving this app no callback at
 *     all. {@code SecondScreen} tells {@link #foreignScreenOpened} the moment
 *     one actually lands on the display; coming back uses the activity's own
 *     {@code onTopResumedActivityChanged} instead of a matching "closed" signal
 *     that does not exist, because that is the nearest thing to one - confirmed
 *     on the device that launching another app's activity onto the panel's
 *     display, exactly like the corner above, leaves this activity itself
 *     resumed the whole time, so {@code onResume} never runs again to hook.
 *     {@link #updateStepAside} is the one place that decides whether the panel
 *     should be up, from both this and the app's own screens together, so the
 *     two can never disagree about it.</li>
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

        /**
         * Back, pressed on the panel rather than on the machine's own screen.
         *
         * The activity's own, and the same one its own screen's Back reaches:
         * the ☰ sheet is <em>on</em> the panel while there is one, so Back
         * there means what it means anywhere else in this app - up a page of
         * the sheet, out of fullscreen, or the menu - and never the desktop.
         * See {@code SecondScreen.setOnBack}.
         */
        void back();
    }

    private final Activity activity;
    private final SharedPreferences preferences;
    private final Host host;

    /** The panel, or null while the controls are on the machine's own screen. */
    private SecondScreen panel;

    /** How many other screens of ours are up; the panel hides while any is. */
    private int ownScreens;

    /** Whether a manual is up on the panel's own display right now - see
     *  the class comment's fourth corner. Combined with {@link #ownScreens}
     *  by {@link #updateStepAside}, never acted on by itself, so the two
     *  can never leave the panel in a state neither of them actually
     *  wanted. */
    private boolean foreignScreenUp;

    /** Whatever {@link #setGameInfo} was last told - the library's own
     *  relative path for the game now loaded, and its name, or both null
     *  when there is nothing the store could name for it. Kept here as well
     *  as on the panel itself, so a panel that appears later - the setting
     *  turned on, or the display reconnecting - opens already knowing what
     *  to show rather than blank until the next game loads. */
    private String infoPath;
    private String infoName;

    /** What the panel's own switch was last left at - kept here rather than
     *  only on the panel because {@link #close} throws the panel away and a
     *  fresh one built by a later {@link #apply} starts this over from
     *  {@link SecondScreen}'s own default otherwise; see {@link
     *  SecondScreen#setPreferInfo}. Starting a game is choosing to play it,
     *  so a panel that has never had its switch touched opens on the
     *  controls - false is that default. */
    private boolean preferInfo;

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
                Prefs.KEY_SECOND_SCREEN, false);

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

        // Whatever game is loaded now, if the store knows it - see
        // setGameInfo - so a panel appearing after the game already started
        // opens already knowing what to show rather than blank until the
        // next load. And whichever side its own switch was last left
        // showing, which this fresh instance has no memory of by itself -
        // see preferInfo's own comment - plus a listener so the next time
        // it is used updates that memory in turn.
        panel.setGameInfo(infoPath, infoName);
        panel.setPreferInfo(preferInfo);
        panel.setOnModeChanged(info -> preferInfo = info);
        panel.setOnForeignScreen(this::foreignScreenOpened);
        panel.setOnBack(host::back);

        // A fresh panel always shows itself first - see Presentation's own
        // show() - so if either reason to step aside is somehow already
        // true at this exact moment (an own screen open while a display was
        // replugged, say) it has to be undone straight away rather than
        // left for whatever tells updateStepAside next.
        updateStepAside();

        host.panelChanged();
    }

    public void close() {
        if (panel == null) return;

        SecondScreen going = panel;
        panel = null;

        // Whatever this panel was waiting to come back from does not carry
        // over to whatever replaces it - see foreignScreenUp's own comment.
        foreignScreenUp = false;

        going.dismiss();
        host.layout().setLentAway(false);

        host.panelChanged();
    }

    /**
     * Told by {@code SecondScreen} the moment a manual actually lands on
     * the panel's own display - see the class comment's fourth corner.
     */
    private void foreignScreenOpened() {
        foreignScreenUp = true;
        updateStepAside();
    }

    /**
     * The activity's own {@code onTopResumedActivityChanged(true)} - the
     * nearest signal available for a manual's viewer being dismissed, which
     * gives this app no callback of its own; see the class comment's fourth
     * corner. Cheap to call every time the activity is the focused one
     * again, own screens and manuals both, since it does nothing unless a
     * manual was actually the reason the panel stepped aside.
     */
    public void topFocusReturned() {
        if (!foreignScreenUp) return;
        foreignScreenUp = false;
        updateStepAside();
    }

    /**
     * The one place {@link SecondScreen#hide} and {@link SecondScreen#show}
     * are called for either reason a screen not our own has covered this
     * one - one of the app's own, counted in {@link #ownScreens}, or a
     * manual on this same display, {@link #foreignScreenUp}. Worked out
     * fresh from both every time rather than toggled by whichever changed,
     * the same reasoning {@link #apply} itself follows, so the two can
     * never disagree about whether the panel should be up.
     */
    private void updateStepAside() {
        if (panel == null) return;

        if (ownScreens > 0 || foreignScreenUp) panel.hide();
        else panel.show();
    }

    /**
     * Told whenever the game changes - see {@code EmulatorActivity
     * .handleViewIntent}, the one place that calls this. Threaded straight to
     * the panel if one is up; kept here as well so a panel that appears
     * later already knows - see {@link #apply}.
     *
     * {@code relativePath} is null for a game with nothing the library's own
     * store could look up: a file manager's hand-over, ES-DE's {@code
     * %ROMPROVIDER%}, <em>Open recent…</em>, or an entry inside a zip. {@link
     * SecondScreen#setGameInfo} reads that as "no switch to offer", not "an
     * empty info panel to show".
     */
    public void setGameInfo(String relativePath, String name) {
        infoPath = relativePath;
        infoName = name;
        if (panel != null) panel.setGameInfo(infoPath, infoName);
    }

    /**
     * Stops a video on the panel's own info side without taking the panel
     * down - called from {@code onPause}, which is one of the moments a
     * video must not be left running that has nothing to do with the
     * selection or the panel itself. {@link #unwatch} already stops
     * following the display; this is the other half of the same pause.
     */
    public void pauseVideo() {
        if (panel != null) panel.pauseVideo();
    }

    /**
     * A display worth putting controls on: Android's own definition, which is
     * the displays that are not the one the activity is on and are meant to be
     * presented to. The last is taken, since that is the one most recently
     * attached.
     */
    private Display free() {
        return free(activity);
    }

    /**
     * {@link #free()}, shared: {@code LibraryPanel} wants the exact same
     * rule for its own panel, and duplicating it risked drifting from
     * whichever afternoon-costing corner this one was tuned against - see
     * the class comment.
     */
    static Display free(Activity activity) {
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
            updateStepAside();
        }

        @Override
        public void onActivityStopped(Activity stopped) {
            if (stopped == activity || ownScreens == 0) return;

            ownScreens--;
            updateStepAside();
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
