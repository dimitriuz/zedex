package dev.ldlab.zedex.welcome;

import dev.ldlab.zedex.storage.Prefs;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A named sequence of coach marks, and the flag that says it has been given.
 *
 * <b>Targets are suppliers, not views.</b> The view may not be laid out yet,
 * or may not exist in this configuration at all - the quick bar is borrowed by
 * the second screen's panel when one is showing, and a mark ringing a bar that
 * is not in this window rings empty space.
 *
 * <b>It declines quietly.</b> A missing target leaves the flag unset, so the
 * guide is given properly the next time rather than being spent on nothing.
 *
 * <b>And the flag is set at the end, never at the start.</b> A guide
 * interrupted by a rotation or a kill has not been given, and should not be
 * remembered as one that was.
 */
public final class Tour {

    /** One mark: what to ring, and what to say about it. */
    private static final class Mark {
        final Supplier<View> target;
        final int caption;

        Mark(Supplier<View> target, int caption) {
            this.target = target;
            this.caption = caption;
        }
    }

    private final String flag;
    private final List<Mark> marks = new ArrayList<>();

    /** What to do while this is running - the machine's guide holds the quick
     *  bar up with it, or the bar fades out from under its own explanation.
     *  Null for a guide with nothing to hold. */
    private Runnable hold;
    private Runnable release;

    private Tour(String flag) {
        this.flag = flag;
    }

    public static Tour of(String flag) {
        return new Tour(flag);
    }

    public Tour mark(Supplier<View> target, int caption) {
        marks.add(new Mark(target, caption));
        return this;
    }

    /** @param hold    run when the first mark goes up
     *  @param release run when the last is dismissed, or the guide is skipped */
    public Tour holding(Runnable hold, Runnable release) {
        this.hold = hold;
        this.release = release;
        return this;
    }

    /**
     * Give the guide, if it has not been given and everything it points at is
     * there to point at.
     */
    public void arm(Activity activity) {
        SharedPreferences preferences = activity.getSharedPreferences(
                Prefs.PREFS, Activity.MODE_PRIVATE);

        if (preferences.getBoolean(flag, false)) return;

        // Posted: what is visible is what is laid out, and the targets are not
        // laid out yet. Inline, this asks about the previous layout pass and
        // declines on every first showing, for ever.
        activity.getWindow().getDecorView().post(() -> {
            for (Mark mark : marks) {
                if (mark.target.get() == null) return;   // declines, flag unset
            }

            if (hold != null) hold.run();
            showFrom(activity, preferences, 0);
        });
    }

    private void showFrom(Activity activity, SharedPreferences preferences,
                           int at) {
        if (at >= marks.size()) {
            done(preferences);
            return;
        }

        Mark mark = marks.get(at);
        View target = mark.target.get();

        // Gone since the check above - a bar that faded, a row recycled. Not a
        // failure: stop, and leave the flag so the guide is given next time.
        if (target == null) {
            Coach.dismiss(activity);
            if (release != null) release.run();
            return;
        }

        Coach.show(activity, target, activity.getString(mark.caption),
                   at == marks.size() - 1,
                   () -> showFrom(activity, preferences, at + 1));
    }

    private void done(SharedPreferences preferences) {
        preferences.edit().putBoolean(flag, true).apply();
        if (release != null) release.run();
    }
}
