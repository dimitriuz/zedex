package dev.ldlab.zedex.welcome;

import dev.ldlab.zedex.storage.Prefs;

import android.app.Activity;
import android.app.Presentation;
import android.content.SharedPreferences;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A named sequence of coach marks, and the flag that says it has been given.
 *
 * <b>Targets are suppliers, not views.</b> The view may not be laid out yet,
 * or may not exist in this configuration at all - and a target may live in a
 * different window from the one this activity owns: the quick bar is borrowed
 * by the second screen's panel when one is showing, and a mark ringing a bar
 * that is on another display would ring empty space. A mark names the window
 * its target lives in - see {@link #mark(Supplier, Supplier, int)} - and is
 * drawn there.
 *
 * <b>It declines quietly.</b> A missing target leaves the flag unset, so the
 * guide is given properly the next time rather than being spent on nothing.
 *
 * <b>And the flag is set at the end, never at the start.</b> A guide
 * interrupted by a rotation or a kill has not been given, and should not be
 * remembered as one that was.
 *
 * <b>The activity's own {@code onPause} must go through {@link
 * #dismiss}, never through {@code Coach.dismiss} directly.</b> A bar-agnostic
 * dismiss takes the mark down but has no way to know a {@link #hold} is in
 * effect, so it never runs {@link #release} - which is what re-arms whatever
 * the hold suspended. Interrupting a tour that way (Home, then back) left the
 * quick bar pinned open with its fade permanently unscheduled, since {@code
 * release} - the only thing that calls {@code postDelayed(fadeQuickBar, ...)}
 * again - never ran. {@link #dismiss} owns both halves: it always takes the
 * mark down, and runs {@link #release} exactly once per {@link #hold},
 * however the tour stops - the last mark answered, a target that vanished
 * mid-walk, or the activity backgrounding with one still up.
 */
public final class Tour {

    /** One mark: what to ring, and what to say about it. */
    private static final class Mark {
        final Supplier<View> target;

        /** The window this mark is drawn in - the panel's own when its target
         *  is borrowed there, and null for the activity's own window. */
        final Supplier<Presentation> panel;
        final int caption;

        Mark(Supplier<View> target, Supplier<Presentation> panel, int caption) {
            this.target = target;
            this.panel = panel;
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

    /** True from the moment {@link #hold} runs until {@link #release} does -
     *  the window in which {@link #dismiss} owes a release to whatever the
     *  hold suspended. */
    private boolean active;

    /** Where the mark now up was drawn - the panel's own window when its
     *  target is borrowed there, null for the activity's own. {@link
     *  #dismiss} takes the mark down where it actually lives. */
    private Presentation onPanel;

    private Tour(String flag) {
        this.flag = flag;
    }

    public static Tour of(String flag) {
        return new Tour(flag);
    }

    public Tour mark(Supplier<View> target, int caption) {
        return mark(target, () -> null, caption);
    }

    /**
     * A mark for a target that lives in a second screen's own window - the
     * machine's controls when they are borrowed by the panel. The overlay is
     * drawn there, where it can ring the target at all; see {@link
     * Coach#show(Activity, Presentation, View, CharSequence, boolean,
     * Runnable)}. A panel that answers null - gone before this mark is due -
     * declines the walk exactly as a missing target does.
     */
    public Tour mark(Supplier<View> target, Supplier<Presentation> panel, int caption) {
        marks.add(new Mark(target, panel, caption));
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

            active = true;
            if (hold != null) hold.run();
            showFrom(activity, preferences, 0);
        });
    }

    private void showFrom(Activity activity, SharedPreferences preferences,
                           int at) {
        if (at >= marks.size()) {
            preferences.edit().putBoolean(flag, true).apply();
            dismiss(activity);
            return;
        }

        Mark mark = marks.get(at);
        View target = mark.target.get();
        Presentation panel = mark.panel.get();

        // Gone since the check above - a bar that faded, a row recycled, or
        // the panel it lives in going down. Not a failure: stop, and leave
        // the flag so the guide is given next time.
        if (target == null || (panel != null && !panel.isShowing())) {
            dismiss(activity);
            return;
        }

        onPanel = panel;

        Coach.show(activity, panel, target, activity.getString(mark.caption),
                   at == marks.size() - 1,
                   () -> showFrom(activity, preferences, at + 1));
    }

    /**
     * Takes down whatever mark of this tour is up, and runs {@link #release}
     * if {@link #hold} is still owed one. Idempotent either way, so the
     * tour's own paths above (the last mark answered, a target vanishing
     * mid-walk) and the activity's {@code onPause} can all call this without
     * checking which of them got there first.
     *
     * <b>This is what {@code onPause} must call instead of {@code
     * Coach.dismiss}</b> - see the class comment. A tour interrupted mid-walk
     * has not been given (the flag is untouched), but whatever {@link #hold}
     * suspended is still released, exactly as it would be had the last mark
     * been answered.
     */
    public void dismiss(Activity activity) {
        if (onPanel != null && onPanel.isShowing()) Coach.dismiss(onPanel);
        else Coach.dismiss(activity);
        onPanel = null;

        if (active) {
            active = false;
            if (release != null) release.run();
        }
    }
}
