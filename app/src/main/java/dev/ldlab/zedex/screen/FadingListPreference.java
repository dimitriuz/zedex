package dev.ldlab.zedex.screen;

import android.content.Context;
import android.preference.ListPreference;
import android.util.AttributeSet;
import android.view.View;

/**
 * A list that looks disabled when it is.
 *
 * {@code Theme.DeviceDefault.Settings} does not dim a disabled preference at
 * all. Measured on an API 36 device, the darkest pixel of the title is
 * (48, 50, 59) whether the row is enabled or not — so a setting that could do
 * nothing looked exactly like one that could, and every
 * {@code android:dependency} in the app was decorative.
 *
 * Fading the whole row is the fix rather than colouring the text, for two
 * reasons: it takes the summary and the widget with it, and it needs no opinion
 * about what colour "dim" is. A hardcoded grey would be wrong on a dark theme
 * and this cannot be, since it is a fraction of whatever is already there.
 */
public class FadingListPreference extends ListPreference {

    /** Faint enough to read as unavailable, solid enough to still read. */
    private static final float FADED = 0.38f;

    public FadingListPreference(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    public FadingListPreference(Context context) {
        super(context);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        // Set on every bind, not once: these are recycled as the list scrolls,
        // so a faded row's view will otherwise turn up under an enabled one.
        view.setAlpha(isEnabled() ? 1f : FADED);
    }
}
