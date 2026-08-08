package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * "How this list is shown": the sort field, its direction, and list-or-grid,
 * all five options in one place - see {@code LibraryActivity}, the only
 * thing that builds one. Reached by Select or the right stick's own click,
 * since a pad has no room for two buttons over two halves of one question;
 * the toolbar's own sort and view buttons are untouched and stay the
 * discoverable path for a finger, which does not need a modal dialog to
 * reach either.
 *
 * A real {@link Dialog} rather than a popup, because modal is the point:
 * nothing behind it is reachable, on a pad or otherwise, for as long as it
 * shows - which is also why its own {@link GamepadCursor} is a second
 * instance rather than the screen's own. The screen's does not stop
 * listening because something else claimed the window; a genuinely modal
 * dialog does that for it, the same way it already stops touches reaching
 * anything underneath. See {@link #show}.
 *
 * A change applies immediately and the dialog stays open - {@link #refresh}
 * is how {@code LibraryActivity} tells this class what actually took effect,
 * so trying three sorts and a view costs one open rather than four.
 */
public final class OptionsDialog {

    /** What choosing a row does - called on a touch tap and the pad's A
     *  alike, so there is one answer to "what does choosing this do"
     *  regardless of how it was chosen. {@code LibraryActivity} is the only
     *  implementation. */
    public interface Callbacks {
        /** 0 = name, 1 = date, 2 = size. The same field twice reverses the
         *  direction - the caller's business, not this dialog's, which only
         *  reports which field was chosen. */
        void onSortField(int index);
        /** {@code false} for list, {@code true} for grid. */
        void onViewMode(boolean grid);
    }

    // Matches LibraryActivity's own palette - duplicated rather than shared,
    // since a colour is not logic and widening a handful of private
    // constants to public just for this would cost more than it buys.
    private static final int BACKING = 0xff14151a;
    private static final int TEXT = 0xffededf2;
    private static final int MUTED = 0xff8b8b99;
    private static final int ACTIVE = 0xff00b0c8;

    /** The tint a row gets while the pad's own cursor is on it - the same
     *  value the list's own selected row is tinted with. */
    private static final int CURSOR_BACKGROUND = 0x3300b0c8;

    private final Activity activity;
    private final Callbacks callbacks;

    private final List<TextView> rows = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();

    /** The three sort rows, kept apart from {@link #rows} as well as in it,
     *  so {@link #refresh} can redraw the arrow without walking past the two
     *  view rows to find them. */
    private final List<TextView> sortRows = new ArrayList<>();
    private TextView listRow;
    private TextView gridRow;

    private Dialog dialog;
    private GamepadCursor cursor;

    /** Which of {@link #rows} the pad's own cursor is on - not necessarily
     *  the row a setting is currently showing, which {@link #sortIndex} and
     *  {@link #grid} track separately; a pad arriving here lands on whatever
     *  is already set, but can move away from it without changing anything. */
    private int cursorRow;

    private int sortIndex;
    private boolean descending;
    private boolean grid;

    public OptionsDialog(Activity activity, Callbacks callbacks) {
        this.activity = activity;
        this.callbacks = callbacks;
    }

    /**
     * Builds and shows the dialog, starting the pad's cursor on whichever
     * field is already sorting - the same row a touch tap would already be
     * looking at. Does nothing if one is already up.
     */
    public void show(int sortIndex, boolean descending, boolean grid) {
        if (dialog != null) return;

        this.sortIndex = sortIndex;
        this.descending = descending;
        this.grid = grid;
        this.cursorRow = sortIndex;

        rows.clear();
        actions.clear();
        sortRows.clear();

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BACKING);
        column.setPadding(pixels(4), pixels(4), pixels(4), pixels(4));

        TextView title = new TextView(activity);
        title.setText(R.string.library_options_title);
        title.setTextColor(MUTED);
        title.setTextSize(13);
        title.setPadding(pixels(16), pixels(12), pixels(16), pixels(8));
        column.addView(title);

        for (int i = 0; i < 3; i++) {
            int index = i;
            TextView row = addRow(column, sortLabel(i), () -> callbacks.onSortField(index));
            sortRows.add(row);
        }

        column.addView(divider());

        listRow = addRow(column, activity.getString(R.string.library_view_list),
                () -> callbacks.onViewMode(false));
        gridRow = addRow(column, activity.getString(R.string.library_view_grid),
                () -> callbacks.onViewMode(true));

        buildCursor();
        paint();

        dialog = new Dialog(activity) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (cursor.key(event)) return true;
                return super.dispatchKeyEvent(event);
            }

            @Override
            public boolean onGenericMotionEvent(MotionEvent event) {
                if (cursor.motion(event)) return true;
                return super.onGenericMotionEvent(event);
            }
        };
        dialog.setContentView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setOnDismissListener(d -> {
            cursor.release();
            cursor = null;
            dialog = null;
        });

        dialog.show();
    }

    /** Closes the dialog if one is showing - the screen going to the
     *  background while a stick is held over it would otherwise leave its
     *  own repeat running, same as the screen's own would without {@code
     *  GamepadCursor.release}; see {@code LibraryActivity.onPause}. Safe to
     *  call whether or not the dialog is currently showing. */
    public void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    /**
     * Called after {@code LibraryActivity} applies a change, so the
     * highlight and the arrow agree with what actually took effect - without
     * this, choosing something from the pad would look like it did nothing
     * until the dialog was closed and reopened. Safe to call whether or not
     * the dialog is currently showing.
     */
    public void refresh(int sortIndex, boolean descending, boolean grid) {
        this.sortIndex = sortIndex;
        this.descending = descending;
        this.grid = grid;

        if (dialog != null) paint();
    }

    private GamepadCursor.Nav buildCursorNav() {
        return new GamepadCursor.Nav() {
            @Override
            public void move(int dx, int dy) {
                if (dy == 0 || rows.isEmpty()) return;
                cursorRow = Math.max(0, Math.min(rows.size() - 1, cursorRow + dy));
                paint();
            }

            @Override
            public void page(int rows) {
                // Five rows have no screenful to speak of.
            }

            @Override
            public void activate() {
                if (cursorRow >= 0 && cursorRow < actions.size()) {
                    actions.get(cursorRow).run();
                }
            }

            @Override
            public void back() {
                dialog.dismiss();
            }

            @Override
            public void toggleFavorite() {
                // Nothing here is a favourite.
            }

            @Override
            public void tab(int delta) {
                // Nothing here is a tab.
            }

            @Override
            public void search() {
                // This dialog has no search field of its own.
            }

            @Override
            public void options() {
                // Already open - Select does not open a second one.
            }
        };
    }

    private void buildCursor() {
        cursor = new GamepadCursor(buildCursorNav());
    }

    private TextView addRow(LinearLayout column, String label, Runnable action) {
        TextView row = new TextView(activity);
        row.setText(label);
        row.setTextColor(TEXT);
        row.setTextSize(15);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pixels(16), pixels(12), pixels(16), pixels(12));
        // The background is repainted by paint() - the cursor's own tint,
        // or nothing. The foreground is this app's own ripple, never
        // android.R.drawable.list_selector_background: on this device's
        // theme that drawable's own state came out fully opaque and hid the
        // row's text under it entirely, not merely clashing with it - see
        // Ripple.
        row.setBackgroundColor(0x00000000);
        row.setForeground(Ripple.make(
                activity.getResources().getDisplayMetrics().density));
        row.setClickable(true);
        row.setFocusable(true);

        int position = rows.size();
        row.setOnClickListener(v -> {
            cursorRow = position;
            action.run();
        });

        rows.add(row);
        actions.add(action);
        column.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private View divider() {
        View line = new View(activity);
        line.setBackgroundColor(0x33ffffff);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, pixels(1));
        params.topMargin = params.bottomMargin = pixels(4);
        line.setLayoutParams(params);

        return line;
    }

    /** "Name", "Name ▲" or "Name ▼" - an arrow only for whichever field is
     *  actually sorting, so the others never claim a direction that is not
     *  theirs. */
    private String sortLabel(int index) {
        String[] names = { activity.getString(R.string.library_sort_name),
                            activity.getString(R.string.library_sort_date),
                            activity.getString(R.string.library_sort_size) };

        if (index != sortIndex) return names[index];
        return names[index] + (descending ? " ▼" : " ▲");
    }

    /**
     * Redraws every row: the text colour says which field and which view are
     * actually set, the arrow on the sorting field says which way, and a
     * tint says where the pad's own cursor is - three separate signals,
     * since the cursor need not be on the row that is set and touch already
     * proved a plain highlight alone is not enough once a pad can move
     * without choosing.
     */
    private void paint() {
        for (int i = 0; i < sortRows.size(); i++) {
            TextView row = sortRows.get(i);
            row.setText(sortLabel(i));
            row.setTextColor(i == sortIndex ? ACTIVE : TEXT);
        }

        listRow.setTextColor(!grid ? ACTIVE : TEXT);
        gridRow.setTextColor(grid ? ACTIVE : TEXT);

        for (int i = 0; i < rows.size(); i++) {
            boolean here = i == cursorRow;

            if (here) {
                rows.get(i).setBackground(Selection.background(
                        rows.get(i).getResources().getDisplayMetrics().density));
            } else {
                rows.get(i).setBackground(null);
            }

            rows.get(i).setSelected(here);
        }
    }

    private int pixels(int dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }
}
