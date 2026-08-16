package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.view.SafeArea;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * What every screen in this app owes, done once.
 *
 * Adding a screen meant remembering six things, and two of them fail
 * silently on the developer's own device - which is why CLAUDE.md has to
 * state them as rules in a document rather than the type system stating them
 * for us:
 *
 * <ul>
 *   <li>{@code attachBaseContext(Language.wrap(base))}, or the screen comes
 *       up in the phone's language rather than the chosen one - invisible to
 *       anyone whose phone is already in that language.</li>
 *   <li>{@code SafeArea.fit} on the content view, or the screen lays itself
 *       into the display cutout. API 35 does that unconditionally, and an
 *       AVD has no cutout, so it looks right until it is on a real phone.</li>
 *   <li>The title set in {@code onCreate} rather than left to
 *       {@code android:label}, which Android resolves in the phone's language
 *       and not the app's.</li>
 *   <li>The preferences, opened with the same name by everything that reads
 *       them.</li>
 * </ul>
 *
 * A screen extends this and says what it is called; the rest happens. What is
 * left to each screen is the only part that differs.
 *
 * {@code SettingsActivity} does not extend this either, and cannot: it is an
 * AppCompatActivity because PreferenceFragmentCompat refuses to attach to
 * anything else, and this is a plain Activity like every other screen here.
 * It keeps its own copies of the two lines, which is the price of that one
 * dependency.
 *
 * {@code EmulatorActivity} does not extend this and should not: it is not
 * laid out the way these are - {@code EmulatorLayout} handles the cutout
 * itself, through a rect it applies to every child, because it measures the
 * picture against the window rather than padding a view group - and it has no
 * title bar to name. Its {@code attachBaseContext} does the same thing for
 * the same reason, with the extra step of noticing a language change while it
 * is running, which none of these need.
 */
public abstract class ZedexActivity extends Activity {

    /** This app's settings, opened once for the screen that wants them. */
    protected SharedPreferences preferences;

    /**
     * The title, resolved in the app's language rather than the phone's.
     *
     * Zero for a screen with no bar to put one in - the media viewer, which
     * is a picture and nothing else.
     */
    protected int title() {
        return 0;
    }

    /** Every screen speaks the chosen language; see {@link Language}. */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Language.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        preferences = getSharedPreferences(Prefs.PREFS, MODE_PRIVATE);

        int title = title();
        if (title != 0) setTitle(title);

        claimBack();
    }

    /**
     * Back, claimed rather than left to the platform - which sounds like work
     * for nothing, since what this does is exactly what the platform's own
     * default does, and is.
     *
     * Measured on an AYN Thor Lite: that device drops the *system's* back
     * invocation and only the system's. In `logcat`, a press on a screen the
     * app has not claimed reads
     * {@code startBackNavigation ... mPriority=-1} and then nothing happens -
     * `PRIORITY_SYSTEM`, the platform's own default handling, thrown away
     * inside SystemUI before this process is asked. A press on a screen that
     * *has* claimed it reads {@code mPriority=0} and works. Settings is
     * unleavable on that handheld for the first reason and Firefox is fine for
     * the second, which is the whole difference between them.
     *
     * So every screen here claims it, and the ten that used to rely on the
     * default stop depending on a thing this device does not do. What it costs
     * is the system's predictive back animation - an app that claims back
     * cannot be previewed sliding off, because the platform no longer knows
     * where back goes. A working Back is worth more than an animation of one.
     *
     * The field is load-bearing, not tidiness: the platform holds a
     * {@code WeakReference} to the callback, so a lambda passed straight in is
     * collected and every later press lands on nothing -
     * "Trying to call onBackInvoked() on a null callback reference" is what
     * that looks like from the outside, and it was in this very investigation's
     * logs from another process that had made exactly this mistake.
     */
    private android.window.OnBackInvokedCallback backCallback;

    private void claimBack() {
        // 30 to 32 have no dispatcher and still deliver onBackPressed, whose
        // default is the same finish() - see CLAUDE.md on predictive back.
        if (android.os.Build.VERSION.SDK_INT
                < android.os.Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (!claimsBack()) return;

        backCallback = this::onBackWanted;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback);
    }

    /**
     * False for a screen that registers its own, so the two do not stack.
     * {@link LibraryActivity} is the only one: what back means there changes
     * with the tab and the stack, and at the Browse root it deliberately hands
     * back to the platform rather than claiming it.
     */
    protected boolean claimsBack() {
        return true;
    }

    /** What the platform's default would have done. */
    protected void onBackWanted() {
        finish();
    }

    /**
     * Back, on API 30 to 32 only. {@link #claimBack} returns before
     * registering anything below TIRAMISU - there is no
     * {@code OnBackInvokedDispatcher} to register on - so on those versions
     * Back arrives here, the platform's own pre-predictive-back path, and
     * never at {@link #onBackWanted} at all. Left undelegated, every screen's
     * override of {@code onBackWanted} was silently dead on a third of the
     * versions this app supports: Back fell to the platform's bare default of
     * {@code finish()} instead, which is wrong wherever a screen overrode
     * {@code onBackWanted} to do something else - {@code WelcomeActivity}'s
     * own {@link #onBackWanted} walking back a page is exactly such a case,
     * and it does not run at all on 30-32 without this.
     *
     * A no-op for every screen whose {@code onBackWanted} is still the
     * default {@code finish()}: this ends up calling that, which is the same
     * as what {@code super.onBackPressed()} would already have done.
     *
     * {@code GameInfoActivity} carried its own copy of exactly this pattern
     * before it was pulled up here, once, for every screen.
     */
    @Override
    public void onBackPressed() {
        if (claimsBack()) onBackWanted();
        else super.onBackPressed();
    }

    /**
     * Keeps the content out of the cutout.
     *
     * Called by the screen once it has set its content view, since that is
     * what there is to fit and only the screen knows when it exists. Named
     * rather than automatic for that reason: a version of this that hooked
     * setContentView would be doing it behind the caller's back and would
     * still be wrong for a screen that builds its view later.
     */
    protected void fitToSafeArea() {
        SafeArea.fit(findViewById(android.R.id.content));
    }
}
