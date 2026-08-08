package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Facets;
import dev.ldlab.zedex.library.Filters;

import android.app.Activity;
import android.app.Dialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "How this list is shown": the sort field, its direction, list-or-grid, and
 * now the filter sheet too - see {@code LibraryActivity}, the only thing that
 * builds one. Reached by Select or the right stick's own click, since a pad
 * has no room for two buttons over two halves of one question; the toolbar's
 * own sort and view buttons are untouched and stay the discoverable path for
 * a finger, which does not need a modal dialog to reach either. The filter
 * sheet is the exception: touch has no other way in, so {@code
 * LibraryActivity}'s own Filter button opens {@link #showFilters} directly,
 * on the very same dialog a pad reaches through {@link #show} - one widget,
 * two doors.
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
 * so trying three sorts and a view costs one open rather than four. The
 * filter sheet follows the same rule for a different reason: CLAUDE.md's own
 * "a row that was a dialog is a page now" - there is no OK button anywhere in
 * here, on either page, and every tap commits by its own name.
 *
 * This class has three pages now rather than one - see {@link Page} - which
 * is the "notion of depth" the filter sheet needed and the plain sort-and-view
 * column never did. {@link #rebuild} is the one place that throws away
 * whatever rows are showing and builds the ones the current page wants,
 * whether that is the dialog's first paint or a page changing under it.
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
        /** The filter changed; the library re-lists. */
        void onFiltersChanged();
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

    /** Beside a picked value, on the filter sheet's own rows - a plain glyph
     *  rather than a drawable, since a row here is a {@link TextView} and
     *  nothing else. */
    private static final String TICK = "✓";

    /** Past this many values a field's own list grows a search box - see
     *  {@link #buildValuesPage}. 277 developers is not a list to scroll
     *  past, and neither format nor rating ever reaches this on any
     *  collection, so it only ever fires for genre, developer or publisher. */
    private static final int SEARCH_THRESHOLD = 20;

    /**
     * Which of the dialog's own pages is showing.
     *
     * OPTIONS is {@link #show}'s page, unchanged since before the filter
     * sheet existed. FILTER and VALUES are that sheet: FILTER lists the five
     * fields, {@link #showFilters} is what enters it, and VALUES is one field's
     * own values, one level deeper. Nothing here is a stack more than two
     * deep - VALUES always goes back to FILTER, and FILTER, for now, always
     * dismisses the dialog rather than going back further, since nothing yet
     * opens FILTER from anywhere but {@link #showFilters} itself. Task 6's
     * own menu of View, Sort and Filter is what gives FILTER's back a second
     * place to go instead of the door.
     */
    private enum Page { OPTIONS, FILTER, VALUES }

    /**
     * The five rows the filter page offers. Four of them are {@link
     * Filters.Field}; the rating threshold is not one of those - see {@link
     * Filters#minStars} - but it answers the same question a person is
     * asking on this page, so it is a fifth row here rather than a case
     * handled separately everywhere a filter row is drawn. {@link #fieldOf}
     * is the one place that translates between the two, and answers {@code
     * null} for {@link #RATING}.
     */
    private enum FilterRow { FORMAT, GENRE, RATING, DEVELOPER, PUBLISHER }

    private final Activity activity;
    private final Callbacks callbacks;

    private final List<TextView> rows = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();

    /** The three sort rows, kept apart from {@link #rows} as well as in it,
     *  so {@link #paint} can redraw the arrow without walking past the two
     *  view rows to find them. Only ever populated on {@link Page#OPTIONS}. */
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

    private Page page = Page.OPTIONS;

    // --- the filter sheet's own state - null/empty until showFilters() ------

    /** The library's own {@link Filters}, the same instance {@code
     *  LibraryActivity} holds - toggling a value here mutates it directly,
     *  which is what lets both doors into this dialog answer to one state,
     *  the way the sort field already answers to the popup and the pad
     *  dialog alike. */
    private Filters filters;

    /** Genre, developer and publisher, with a count for each - {@link
     *  Filters.Field#FORMAT} is not a key here; see {@link #formats}. */
    private Map<Filters.Field, List<Facets.Value>> values;

    /** The formats present in the collection - a property of the filename,
     *  not the store, so {@link Facets} hands these back separately from
     *  {@link #values}. */
    private List<Facets.Value> formats;

    /** Which field {@link Page#VALUES} is currently showing the values of. */
    private FilterRow openRow;

    /** What the search box above a long value list currently reads, already
     *  lower-cased - "" when there is no box, or nothing has been typed into
     *  it yet. Reset whenever {@link #openValues} enters a field afresh. */
    private String valueSearch = "";

    /** The values {@link Page#VALUES} is currently listing, before {@link
     *  #valueSearch} narrows them - {@link #values}.get(field) or {@link
     *  #formats}, whichever {@link #openRow} names. */
    private List<Facets.Value> valueSource = Collections.emptyList();

    /** The scrollable column {@link #refreshValueRows} redraws into - see
     *  that method for why a keystroke touches this and nothing above it. */
    private LinearLayout valueList;

    /** Where in {@link #rows}/{@link #actions} the value rows start, on
     *  {@link Page#VALUES} - everything before this index is the back row,
     *  the search box's own row placeholder and "Clear all", none of which
     *  {@link #refreshValueRows} is allowed to touch. */
    private int valueRowsStart;

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
        this.page = Page.OPTIONS;

        rebuild();
    }

    /**
     * The filter sheet: five fields, each opening the values the collection
     * actually has.
     *
     * The same row-building code the sort page uses, entered here rather than
     * at the top - one widget with two starting points, not a second list that
     * could drift from the first about, say, whether genres are comma-split.
     * Reached straight from {@code LibraryActivity}'s own Filter button,
     * never through {@link #show}: touch has no other door into this dialog
     * at all, so this one has to stand on its own rather than assume {@link
     * #show} ran first.
     */
    public void showFilters(Filters filters,
                            Map<Filters.Field, List<Facets.Value>> values,
                            List<Facets.Value> formats) {
        this.filters = filters;
        this.values = values;
        this.formats = formats;

        page = Page.FILTER;
        cursorRow = 0;
        rebuild();
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
     * Called after {@code LibraryActivity} applies a sort or view change, so
     * the highlight and the arrow agree with what actually took effect -
     * without this, choosing something from the pad would look like it did
     * nothing until the dialog was closed and reopened. Safe to call whether
     * or not the dialog is currently showing; a no-op unless {@link #page} is
     * {@link Page#OPTIONS}, since nothing else calls this.
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
                // No page here has a screenful to speak of - even 277
                // developers scroll a row at a time under a pad's own D-pad
                // repeat rather than a page at once.
            }

            @Override
            public void activate() {
                if (cursorRow >= 0 && cursorRow < actions.size()) {
                    actions.get(cursorRow).run();
                }
            }

            @Override
            public void back() {
                // One level of depth: VALUES goes back to the field list it
                // came from, and everything else - OPTIONS, and FILTER until
                // something else opens it - dismisses outright, since FILTER
                // has nowhere else to go yet. See Page's own comment.
                if (page == Page.VALUES) {
                    page = Page.FILTER;
                    cursorRow = 0;
                    rebuild();
                } else {
                    dialog.dismiss();
                }
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
                // A pad has no easy way to type into the value list's own
                // search box, so this stays a no-op, the same as before the
                // box existed.
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

    /**
     * Throws away whatever rows the dialog is showing and builds the ones
     * {@link #page} wants now - the dialog's first paint and a page changing
     * under it are the same operation, since both start from nothing.
     *
     * Opens the {@link Dialog} on the first call; every later call swaps its
     * content view in place instead, the same dialog and the same {@link
     * GamepadCursor} throughout, so neither picking a filter value nor
     * stepping between pages ever looks like the sheet closing and
     * reopening - it does not, and a pad's own repeat held into the moment a
     * page changes must keep landing on the same live cursor.
     */
    private void rebuild() {
        rows.clear();
        actions.clear();
        sortRows.clear();
        listRow = null;
        gridRow = null;

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BACKING);
        column.setPadding(pixels(4), pixels(4), pixels(4), pixels(4));

        switch (page) {
            case FILTER:
                buildFilterPage(column);
                break;
            case VALUES:
                buildValuesPage(column);
                break;
            case OPTIONS:
            default:
                buildOptionsPage(column);
                break;
        }

        if (dialog == null) {
            buildCursor();

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
            dialog.setOnDismissListener(d -> {
                cursor.release();
                cursor = null;
                dialog = null;
            });
            dialog.setContentView(column, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.show();
        } else {
            dialog.setContentView(column, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        paint();
    }

    /** The sort fields and List/Grid - exactly what {@link #show} always
     *  built, moved here unchanged so {@link #rebuild} can reach it through
     *  the same switch every other page goes through. */
    private void buildOptionsPage(LinearLayout column) {
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
    }

    /**
     * The filter sheet's own top: five rows, one per {@link FilterRow}, each
     * showing the field's name alone when it is not narrowed or "Genre ·
     * Platform, Racing" when it is - see {@link #filterRowLabel}. "Clear all"
     * sits above them, only while {@link Filters#activeFieldCount} says
     * something is actually set.
     */
    private void buildFilterPage(LinearLayout column) {
        TextView title = new TextView(activity);
        title.setText(R.string.library_filter);
        title.setTextColor(MUTED);
        title.setTextSize(13);
        title.setPadding(pixels(16), pixels(12), pixels(16), pixels(8));
        column.addView(title);

        if (filters.activeFieldCount() > 0) {
            addRow(column, activity.getString(R.string.library_filter_clear), () -> {
                filters.clearAll();
                callbacks.onFiltersChanged();
                rebuild();
            });
        }

        for (FilterRow row : FilterRow.values()) {
            addRow(column, filterRowLabel(row), () -> openValues(row));
        }
    }

    /** Enters {@link Page#VALUES} for one field - the one level of depth
     *  this dialog gained for the filter sheet. */
    private void openValues(FilterRow row) {
        openRow = row;
        valueSearch = "";
        page = Page.VALUES;
        cursorRow = 0;
        rebuild();
    }

    /**
     * One field's own values: a row back to {@link #buildFilterPage}, "Clear
     * all" for this field alone while anything of it is picked, a search box
     * once there are more than {@link #SEARCH_THRESHOLD} of them, and the
     * values themselves - {@link #buildRatingRows} instead, for the one field
     * that is a threshold rather than a list.
     */
    private void buildValuesPage(LinearLayout column) {
        addRow(column, "‹ " + activity.getString(fieldNameRes(openRow)), () -> {
            page = Page.FILTER;
            cursorRow = 0;
            rebuild();
        });
        column.addView(divider());

        if (openRow == FilterRow.RATING) {
            buildRatingRows(column);
            return;
        }

        Filters.Field field = fieldOf(openRow);
        valueSource = field == Filters.Field.FORMAT ? formats : values.get(field);
        if (valueSource == null) valueSource = Collections.emptyList();

        if (!filters.chosen(field).isEmpty()) {
            addRow(column, activity.getString(R.string.library_filter_clear), () -> {
                filters.clear(field);
                callbacks.onFiltersChanged();
                rebuild();
            });
        }

        if (valueSource.size() > SEARCH_THRESHOLD) {
            EditText search = new EditText(activity);
            search.setHint(R.string.library_search);
            search.setSingleLine();
            search.setTextColor(TEXT);
            search.setHintTextColor(MUTED);
            search.setBackground(null);
            search.setPadding(pixels(16), pixels(8), pixels(16), pixels(8));
            search.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) { }

                @Override
                public void afterTextChanged(Editable s) {
                    // refreshValueRows only, never rebuild - a rebuild would
                    // throw this very EditText away and take the keyboard
                    // with it, mid-word, the same trap LibraryActivity's own
                    // search field avoids by never touching itself either.
                    valueSearch = s.toString().toLowerCase(Locale.ROOT);
                    refreshValueRows();
                }
            });
            column.addView(search);
        }

        ScrollView scroll = cappedScroll();
        valueList = new LinearLayout(activity);
        valueList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(valueList);
        column.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Everything from here down is what refreshValueRows tears down and
        // rebuilds on every keystroke; the back row, the search box and
        // "Clear all" above it never are - see that method's own comment.
        valueRowsStart = rows.size();
        refreshValueRows();
    }

    /** Rating's own five rows - a single choice rather than {@link
     *  #refreshValueRows}'s multi-select, so this never needs a "Clear all"
     *  of its own: "Any rating" already is one. */
    private void buildRatingRows(LinearLayout column) {
        float[] thresholds = { 0f, 3f, 3.5f, 4f, 4.5f };

        for (float threshold : thresholds) {
            String label = threshold <= 0f
                    ? activity.getString(R.string.library_filter_any_rating)
                    : ratingLabel(threshold);

            // == rather than a tolerance: every threshold this row can ever
            // set is one of these five literals, so what minStars() reads
            // back is always the exact value one of them wrote.
            boolean picked = filters.minStars() == threshold;
            String text = (picked ? TICK + " " : "") + label;

            addRow(column, text, () -> {
                filters.setMinStars(threshold);
                callbacks.onFiltersChanged();
                rebuild();
            });
        }
    }

    /**
     * Redraws only the value rows below {@link #valueRowsStart} - never the
     * back row, the search box or "Clear all" above them, which is what lets
     * a keystroke narrow the list without the search box itself being torn
     * down and rebuilt, taking the keyboard and the caret with it.
     *
     * A tap that actually changes {@link Filters} still goes through {@link
     * #rebuild} rather than this - "Clear all" can appear or disappear as a
     * result, and only a full rebuild notices that; this is for a search
     * query narrowing what is offered, which changes nothing {@link Filters}
     * itself knows about.
     */
    private void refreshValueRows() {
        while (rows.size() > valueRowsStart) {
            rows.remove(rows.size() - 1);
            actions.remove(actions.size() - 1);
        }
        valueList.removeAllViews();

        Filters.Field field = fieldOf(openRow);
        Set<String> chosen = filters.chosen(field);

        for (Facets.Value value : valueSource) {
            if (!valueSearch.isEmpty()
                    && !value.name.toLowerCase(Locale.ROOT).contains(valueSearch)) {
                continue;
            }

            boolean picked = chosen.contains(value.name);
            String text = (picked ? TICK + " " : "") + value.name + "  " + value.count;

            addRow(valueList, text, () -> {
                filters.toggle(field, value.name);
                callbacks.onFiltersChanged();
                rebuild();
            });
        }

        cursorRow = rows.isEmpty() ? 0 : Math.min(cursorRow, rows.size() - 1);
        paint();
    }

    /**
     * A {@link ScrollView} that never grows taller than a share of the
     * display - plain {@code WRAP_CONTENT} would let 277 developers push the
     * dialog's window off the bottom of the screen with nothing left to
     * scroll it back into view, since a {@code ScrollView} does not cap its
     * own measured height on its own; this overrides that measurement
     * instead of trusting a layout param the view does not have.
     */
    private ScrollView cappedScroll() {
        int cap = Math.round(
                activity.getResources().getDisplayMetrics().heightPixels * 0.55f);

        return new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec,
                        View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
            }
        };
    }

    /** "Genre" alone when nothing of it is picked, or "Genre · Platform,
     *  Racing" - joined in whatever order they were picked in, which is not
     *  worth sorting for a row that exists to say "something is set", not to
     *  enumerate it precisely. */
    private String filterRowLabel(FilterRow row) {
        String name = activity.getString(fieldNameRes(row));

        if (row == FilterRow.RATING) {
            return filters.minStars() <= 0f
                    ? name : name + " · " + ratingLabel(filters.minStars());
        }

        Set<String> chosen = filters.chosen(fieldOf(row));
        return chosen.isEmpty() ? name : name + " · " + String.join(", ", chosen);
    }

    private static int fieldNameRes(FilterRow row) {
        switch (row) {
            case FORMAT: return R.string.library_filter_format;
            case GENRE: return R.string.library_filter_genre;
            case RATING: return R.string.library_filter_rating;
            case DEVELOPER: return R.string.library_filter_developer;
            default: return R.string.library_filter_publisher;
        }
    }

    /** {@code null} for {@link FilterRow#RATING}, which is not one of {@link
     *  Filters.Field} - see that enum's own comment. */
    private static Filters.Field fieldOf(FilterRow row) {
        switch (row) {
            case FORMAT: return Filters.Field.FORMAT;
            case GENRE: return Filters.Field.GENRE;
            case DEVELOPER: return Filters.Field.DEVELOPER;
            case PUBLISHER: return Filters.Field.PUBLISHER;
            default: return null;
        }
    }

    /** "3+" or "3.5+" - {@code float}'s own {@code toString} is exactly this
     *  for every threshold {@link #buildRatingRows} ever passes it, an
     *  integer or one decimal place, never scientific notation or a
     *  trailing zero. */
    private static String ratingLabel(float stars) {
        String number = stars == Math.rint(stars)
                ? String.valueOf((int) stars)
                : String.valueOf(stars);
        return number + "+";
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
     *  theirs.
     *
     * These three rows are {@code Sorting.FIELDS[0..2]} - name, size,
     * released - matching {@code LibraryActivity.sortFieldIndex} exactly;
     * format and rating are not offered here yet, only from the toolbar's
     * own popup once it gains them, until Task 6 wraps this page into the
     * three-way menu the design spec describes. Deliberately not "date",
     * which named this row before {@code library_sort_date} existed at all -
     * see CLAUDE.md on a stored sort field this build no longer reads. */
    private String sortLabel(int index) {
        String[] names = { activity.getString(R.string.library_sort_name),
                            activity.getString(R.string.library_sort_size),
                            activity.getString(R.string.library_sort_released) };

        if (index != sortIndex) return names[index];
        return names[index] + (descending ? " ▼" : " ▲");
    }

    /**
     * Redraws every row: on {@link Page#OPTIONS}, the text colour says which
     * field and which view are actually set and the arrow on the sorting
     * field says which way; on every page, a tint says where the pad's own
     * cursor is - a separate signal from the text colour, since the cursor
     * need not be on the row that is set and touch already proved a plain
     * highlight alone is not enough once a pad can move without choosing.
     * The filter sheet's own rows carry what is picked baked into their text
     * already - see {@link #filterRowLabel} and {@link #refreshValueRows} -
     * so this has nothing page-specific left to do for them beyond the tint
     * every page gets.
     */
    private void paint() {
        if (page == Page.OPTIONS) {
            for (int i = 0; i < sortRows.size(); i++) {
                TextView row = sortRows.get(i);
                row.setText(sortLabel(i));
                row.setTextColor(i == sortIndex ? ACTIVE : TEXT);
            }

            listRow.setTextColor(!grid ? ACTIVE : TEXT);
            gridRow.setTextColor(grid ? ACTIVE : TEXT);
        }

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
