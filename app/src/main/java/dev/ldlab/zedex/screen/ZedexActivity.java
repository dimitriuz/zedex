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
