package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.R;

import android.content.SharedPreferences;

/**
 * How the picture is imagined to reach the television.
 *
 * Beside {@link Border}, and for the same reason. This was three values
 * defined in five places that had to agree with each other and nothing made
 * them: the numbers in {@code FuseNative}, the labels in {@code arrays.xml},
 * a second short-label array for the quick bar, the rules about which filters
 * each one enables in {@code SettingsActivity}, and a {@code nextVideo} that
 * cycled by taking the modulus of an array's length - so the quick bar knew
 * how many signals exist only by counting strings, and adding a fourth meant
 * finding all five.
 *
 * A signal is one thing now: its stored value, the two ways it is named, and
 * what it does to the filters that depend on it.
 */
public enum Video {

    RGB(FuseNative.VIDEO_RGB, R.string.video_rgb, R.string.video_rgb_short),
    COMPOSITE(FuseNative.VIDEO_COMPOSITE, R.string.video_composite,
              R.string.video_composite_short),
    RF(FuseNative.VIDEO_RF, R.string.video_rf, R.string.video_rf_short);

    /** What Fuse's renderer knows this signal by, and what the preference
     *  stores - as a String, since a ListPreference writes one. */
    public final int value;

    /** The full sentence, for the settings list. */
    public final int title;

    /** The word alone, for a quick bar row that has no room for the rest. */
    public final int shortTitle;

    Video(int value, int title, int shortTitle) {
        this.value = value;
        this.title = title;
        this.shortTitle = shortTitle;
    }

    /** The next signal round, which is what the quick bar's row does. */
    public Video next() {
        return values()[ ( ordinal() + 1 ) % values().length ];
    }

    /**
     * Whether the bleed filter means anything for this signal.
     *
     * RGB is the wire straight from the machine: there is nothing for colour
     * to bleed through, so the setting is disabled rather than left offering
     * an effect it cannot produce.
     */
    public boolean bleeds() {
        return this != RGB;
    }

    /** Only an aerial socket puts noise on the picture. */
    public boolean hasNoise() {
        return this == RF;
    }

    /** What the preference says, or {@link #RGB} for anything unreadable -
     *  the same fallback the renderer's own default is. */
    public static Video of(SharedPreferences preferences) {
        int stored = Prefs.number(
                preferences, Prefs.KEY_VIDEO, RGB.value);

        for (Video video : values()) {
            if (video.value == stored) return video;
        }
        return RGB;
    }
}
