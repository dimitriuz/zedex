package dev.ldlab.zedex.welcome;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

/**
 * One page of the wizard.
 *
 * <b>Skip writes nothing.</b> A page writes as it is answered - tapping a
 * language sets the language - so a page that is skipped, and a page that is
 * never reached because it does not apply, leave exactly the same state: the
 * app's own defaults. Nothing downstream has to know how far the wizard got.
 * {@link #apply} exists for the one page that has something to settle on the
 * way out rather than as it is touched; most implementations do nothing.
 */
public interface Step {

    /** The heading, as a string resource. */
    int title();

    /** The one line under it. */
    int blurb();

    /** The page's own controls. Built once, when the page is shown. */
    View body(Context context, SharedPreferences preferences);

    /** Called when the page is left forwards. Most pages need nothing here. */
    default void apply(SharedPreferences preferences) {
    }
}
