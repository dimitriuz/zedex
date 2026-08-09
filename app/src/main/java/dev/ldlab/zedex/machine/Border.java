package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.R;
import android.content.SharedPreferences;

/**
 * How much of the Spectrum's border to show.
 *
 * The machine draws 256x192 pixels of picture inside a border 32 wide and 24
 * deep, which is why Fuse's frame is 320x240. The border is not decoration — a
 * game that flashes it, or loads with stripes down it, is using it — but it is
 * also a fifth of the width of a phone screen given to something that is mostly
 * one flat colour, and on a 4:3 picture in a tall window that fifth is the
 * scarce direction.
 *
 * So: all of it, a quarter of it, or none.
 *
 * <b>A quarter rather than the fifth that was asked for</b>, because a quarter is
 * the widest thin border that lands on whole pixels on both axes and stays
 * exactly 4:3 — 8 of 32 across and 6 of 24 down. A fifth would be 6.4 and 4.8,
 * and a source frame of fractional pixels makes a nonsense of asking for a whole
 * number of screen pixels per emulated one.
 *
 * The three sizes are all 4:3, so nothing about fitting the picture changes;
 * what changes is how many emulated pixels there are to scale, which is why the
 * scale list and the renderer both have to be told.
 */
public enum Border {

    /** 320x240: the frame as Fuse draws it. */
    FULL("full", R.string.border_full, 320, 240),

    /** 272x204: a quarter of the border, top and bottom, left and right. */
    SLIM("slim", R.string.border_slim, 272, 204),

    /** 256x192: the picture and nothing else. */
    NONE("none", R.string.border_none, 256, 192);

    /** What is stored, and what the renderer is sent. */
    public final String value;

    /** One word, for a menu row that has no space for the explanation. */
    public final int title;

    /** The visible frame, in emulated pixels. */
    public final int width;
    public final int height;

    Border(String value, int title, int width, int height) {
        this.value = value;
        this.title = title;
        this.width = width;
        this.height = height;
    }

    /** The next one round, for a quick action that steps through them. */
    public Border next() {
        return values()[ ( ordinal() + 1 ) % values().length ];
    }

    public static Border of(SharedPreferences preferences) {
        return of(preferences.getString(Prefs.KEY_BORDER, null));
    }

    /** The stored value, or {@link #FULL} for anything unrecognised. */
    public static Border of(String stored) {
        if (stored != null) {
            for (Border border : values()) {
                if (border.value.equals(stored)) return border;
            }
        }
        return FULL;
    }
}
