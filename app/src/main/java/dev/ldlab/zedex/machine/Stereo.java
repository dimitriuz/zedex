package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.storage.Prefs;

import android.content.SharedPreferences;

/**
 * AY stereo separation.
 *
 * The names are Fuse's own words rather than this app's, because
 * {@code --separation} takes them verbatim and Fuse compares them with
 * strcmp - so what is stored is what is passed. That is also why they are not
 * translated: they are not language, they are an argument.
 *
 * An enum rather than an array and an index into it, which is what this was:
 * the number {@link FuseNative#setAyStereo} wants and the word the command
 * line wants were an ordinal and a lookup, in two methods, on the settings
 * screen - which {@link Machine} then reached up into for the second one.
 */
public enum Stereo {

    NONE("None"),
    ACB("ACB"),
    ABC("ABC");

    /** What Fuse's command line takes, verbatim. */
    public final String option;

    /** What {@link FuseNative#setAyStereo} takes - the position in this list,
     *  which is the order Fuse itself uses. */
    public final int value;

    Stereo(String option) {
        this.option = option;
        this.value = ordinal();
    }

    /** What is stored, or {@link #NONE} for anything unrecognised. */
    public static Stereo of(SharedPreferences preferences) {
        String stored = preferences.getString(Prefs.KEY_AY_STEREO, NONE.option);

        for (Stereo stereo : values()) {
            if (stereo.option.equals(stored)) return stereo;
        }
        return NONE;
    }
}
