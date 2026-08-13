package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.meta.Meta;

import android.content.Context;

/**
 * The bits of a facts line that more than one screen has to render the same
 * way.
 *
 * Three screens draw one: the library's pane, {@code GameInfoView} on a
 * second screen's panel, and {@code GameInfoActivity}. Each builds its own
 * line - they show different numbers of facts, on purpose, because a strip
 * beside a grid has less room than a whole screen - but a fact that appears on
 * more than one of them has to <em>read</em> the same on all of them, and
 * three copies of the wording is three places for it to drift. {@code
 * Filters.ratingLabel} exists for the same reason and could not be reused
 * here: that class is deliberately free of Android, and this needs a
 * translated word.
 */
public final class Facts {

    private Facts() {
    }

    /**
     * "Times played: 7", or null for a game nobody has opened yet.
     *
     * <b>A colon rather than a sentence, and no plural.</b> "Played 1 times"
     * is what a naive format gives, and the correct alternative is a {@code
     * <plurals>} in nine files with two to four forms each, in languages whose
     * rules differ - Russian and Polish need {@code few} and {@code many}. A
     * label and a number needs none of that and is what the row is: the
     * field's own name, which the editor already has translated, and its
     * value.
     *
     * Null rather than a zero, because a fact nobody has is not a fact. The
     * line is joined with separators, so an empty string here would leave a
     * dangling one.
     */
    public static String playedLabel(Context context, Meta meta) {
        if (meta == null || meta.plays() <= 0) return null;

        return context.getString(R.string.edit_metadata_play_count) + ": " + meta.plays();
    }
}
