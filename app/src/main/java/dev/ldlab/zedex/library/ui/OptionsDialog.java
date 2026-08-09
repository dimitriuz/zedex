package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Facets;
import dev.ldlab.zedex.library.Filters;
import dev.ldlab.zedex.library.Sorting;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Rect;
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
 * the filter sheet too - see {@code LibraryActivity}, the only thing that
 * builds one. Reached by Select or the right stick's own click, and by the
 * toolbar's own Options button as well - one dialog, one door
 * regardless of which asked, rather than the three separate toolbar buttons
 * and this dialog's own menu that used to answer the same three questions
 * twice over and could drift apart; see {@code
 * LibraryActivity.buildToolbar}. Filtering is Browse's own - see {@link
 * Callbacks#filteringAllowed()} - so MENU's own Filter row is the thing that
 * goes missing outside Browse, not the button that opens this dialog: Sort
 * and View still apply everywhere.
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
 * This class has four pages now rather than one flat column - see {@link
 * Page}. A three-row menu, {@link Page#MENU}, sits above what used to
 * be the whole dialog. View stayed a row on that menu rather than gaining a
 * page of its own: flipping List and Grid is a flick, not a considered
 * choice, and a page behind it would have cost three presses for something
 * that used to cost one - so choosing it flips the mode on the spot, the
 * same way a filter row commits by its own name. Sort did gain a page,
 * since five fields and a direction is a considered choice; so did the
 * filter sheet, reached the same way Sort's page is - through
 * MENU's own row, {@link Callbacks#openFilters} - now that the
 * toolbar's own Filter button is gone, which used to open the sheet a second
 * way, straight from {@link #show}'s own door with no menu behind it at all.
 * {@link #rebuild} is still the one place that throws away whatever rows are
 * showing and builds the ones the current page wants, whether that is the
 * dialog's first paint or a page changing under it.
 */
public final class OptionsDialog {

    /** What choosing a row does - called on a touch tap and the pad's A
     *  alike, so there is one answer to "what does choosing this do"
     *  regardless of how it was chosen. {@code LibraryActivity} is the only
     *  implementation. */
    public interface Callbacks {
        /** 0 = name, 1 = size, 2 = released, 3 = format, 4 = rating - {@code
         *  Sorting.FIELDS}'s own order. The same field twice reverses the
         *  direction - the caller's business, not this dialog's, which only
         *  reports which field was chosen. */
        void onSortField(int index);
        /** {@code false} for list, {@code true} for grid. */
        void onViewMode(boolean grid);
        /** The filter changed; the library re-lists. */
        void onFiltersChanged();
        /** MENU's own Filter row was chosen. Unlike the other two this
         *  cannot finish here - the values a field can be narrowed to are not
         *  known until {@code Facets} walks the whole store, off the UI
         *  thread - so this only asks; {@code LibraryActivity} is expected to
         *  do that work and then call {@link #enterFiltersFromMenu} with the
         *  answer, passing {@code requestToken} straight back so that method
         *  can tell whether anyone still wants it - see that method's own
         *  comment. Never called while {@link #filteringAllowed} answers
         *  false - MENU has no Filter row to choose in that case. */
        void openFilters(int requestToken);
        /** Whether MENU should offer a Filter row at all - false outside
         *  Browse, where {@code LibraryActivity.filtering()} already answers
         *  no: Favourites and Recent are answers to a question of their own,
         *  and a filter that narrowed neither would be worse than none.
         *  Asked fresh every time {@link #buildMenuPage} runs rather than
         *  carried by {@link #show}'s own three arguments, since a tab
         *  switch can happen while this dialog is not up to be told about it
         *  - there would be nowhere to hand a fourth argument to. Sort and
         *  View have no such question: both apply to every tab, which is why
         *  only this one row asks it. */
        boolean filteringAllowed();

        /**
         * Whether there is a game selected whose metadata can be edited.
         *
         * Its own question, deliberately not folded into {@link
         * #filteringAllowed}: that one asks whether the current tab answers to
         * a filter, this one asks whether one row is selected and has a path
         * of its own for the store to key by - a folder, an archive and a game
         * inside a zip all have none, which is the same test the pane's
         * magnifier makes. CLAUDE.md's "one predicate must not answer two
         * questions" was written about this file's neighbour.
         *
         * Asked fresh every time {@link #buildMenuPage} runs, for the same
         * reason the one above is: the selection moves while this dialog is
         * not up to be told.
         */
        boolean editingAllowed();

        /** MENU's own Edit row was chosen - open the editor for whatever is
         *  selected. The dialog closes itself first; a screen opening behind
         *  it would be reached by dismissing something the person did not put
         *  there. */
        void editMetadata();

        /**
         * Whether anything can be scraped from at all.
         *
         * A build with no provider credentials cannot, which is the ordinary
         * state of a source download - see {@code Scrapers}. Its own question
         * again rather than folded into {@link #editingAllowed}: editing by
         * hand needs a selected game and nothing else, and scraping needs a
         * selected game <em>and</em> somewhere to ask.
         */
        boolean scrapingAllowed();

        /** MENU's own Scrape row was chosen. */
        void scrapeSelected();
    }

    // Matches LibraryActivity's own palette - duplicated rather than shared,
    // since a colour is not logic and widening a handful of private
    // constants to public just for this would cost more than it buys.
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
     * MENU is {@link #show}'s own page now: View, Sort and, in Browse,
     * Filter, each naming what it is currently set to so checking costs a
     * glance rather than a page. View has no page of its own - activating
     * that row flips List and Grid on the spot, the way a filter row commits
     * by its own name - so MENU is as deep as that subject ever goes. SORT
     * and FILTER are both one subject deep and reachable from nowhere but
     * MENU, so both their own backs always return there - see {@link
     * #goBack}. VALUES is one field's own values, one level under FILTER.
     */
    private enum Page { MENU, SORT, FILTER, VALUES }

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

    /** Where View, Sort and Filter sit among {@link #rows} on {@link
     *  Page#MENU} - {@link #show} starts the cursor on MENU_VIEW, and {@link
     *  #goBack} puts it back on MENU_SORT or MENU_FILTER for whichever of
     *  those two led to the page it is leaving; View has no page to leave, so
     *  nothing ever needs to land back on it that way. */
    private static final int MENU_VIEW = 0, MENU_SORT = 1, MENU_FILTER = 2;

    private final Activity activity;
    private final Callbacks callbacks;

    private final List<TextView> rows = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();

    /** The five sort rows, kept apart from {@link #rows} as well as in it,
     *  so {@link #paint} can redraw the arrow without walking past whatever
     *  else the page has to find them. Only ever populated on {@link
     *  Page#SORT}. */
    private final List<TextView> sortRows = new ArrayList<>();

    /** MENU's own View row, kept apart from {@link #rows} the same way {@link
     *  #sortRows} is - so {@link #paint} can update its summary after a
     *  toggle without searching {@link #rows} for it. Only ever populated on
     *  {@link Page#MENU}. */
    private TextView viewRow;

    private Dialog dialog;

    /** The dialog's own corner, dp - the same shape the quick bar's buttons
     *  and the first-run panel's bands already use. */
    private static final int CORNER_DP = 12;
    private GamepadCursor cursor;

    /** Which of {@link #rows} the pad's own cursor is on - not necessarily
     *  the row a setting is currently showing, which {@link #sortIndex} and
     *  {@link #grid} track separately; a pad arriving here lands on whatever
     *  is already set, but can move away from it without changing anything. */
    private int cursorRow;

    private int sortIndex;
    private boolean descending;
    private boolean grid;

    private Page page = Page.MENU;

    /** Bumped every time MENU's own Filter row asks {@code LibraryActivity}
     *  to walk the store, and again every time MENU is left for somewhere
     *  else - Sort's own row, the one other thing MENU can do while that walk
     *  might still be running - while the answer is in flight. Either way,
     *  the value {@link Callbacks#openFilters} was handed stops matching this
     *  field, which is how {@link #enterFiltersFromMenu} tells "this is the
     *  answer to the request still on offer" from "this is the answer to a
     *  request nobody standing here asked any more" - a page has no business
     *  yanking itself back to FILTER because of a tap that happened before
     *  someone moved on to Sort. */
    private int filterRequestToken;

    /** The library's own {@link Filters}, the same instance {@code
     *  LibraryActivity} holds - toggling a value here mutates it directly,
     *  which is what lets the toolbar's Options button and the pad's Select
     *  answer to one state. Set once, by the constructor: {@link
     *  Page#MENU}'s own Filter row has to say how many fields are set before
     *  the filter sheet has ever been opened, so this cannot wait for {@link
     *  #enterFiltersFromMenu} the way {@link #values} and {@link #formats}
     *  still do. */
    private final Filters filters;

    // --- the rest of the filter sheet's own state - null/empty until
    // enterFiltersFromMenu() runs -------------------------------------------

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

    public OptionsDialog(Activity activity, Filters filters, Callbacks callbacks) {
        this.activity = activity;
        this.filters = filters;
        this.callbacks = callbacks;
    }

    /**
     * Builds and shows the dialog, opening on {@link Page#MENU} - the
     * three-row menu that sits above what {@code sortIndex}, {@code
     * descending} and {@code grid} used to open directly. The field that is
     * sorting is a row inside {@link Page#SORT} now, not on the page that
     * opens, so the cursor starts on MENU's own first row rather than on it;
     * a person who wants to check the sort field is one press away either
     * way. Does nothing if one is already up.
     */
    public void show(int sortIndex, boolean descending, boolean grid) {
        if (dialog != null) return;

        // Bumped here too, not just when Filter or Sort is tapped from MENU -
        // see enterFiltersFromMenu's own comment on filterRequestToken. A
        // Facets walk kicked off from before a dismiss can still be in
        // flight when this reopens the dialog fresh; without this, that
        // stale answer would match the request token a brand new MENU page
        // never asked for and force it straight to FILTER.
        filterRequestToken++;

        this.sortIndex = sortIndex;
        this.descending = descending;
        this.grid = grid;
        this.cursorRow = MENU_VIEW;
        this.page = Page.MENU;

        rebuild();
    }

    /**
     * The filter sheet: five fields, each opening the values the collection
     * actually has. The only door in now that the toolbar's own Filter
     * button is gone - reached from {@link Page#MENU}'s own Filter row by
     * way of {@link Callbacks#openFilters}, once {@code LibraryActivity} has
     * walked the store for the values a field can be narrowed to; that walk
     * is why this cannot finish inside {@link Callbacks#openFilters} itself
     * the way every other row's action does, and needs a method of its own
     * for the answer to land in once it is ready.
     *
     * Ignored if the dialog is no longer up: a person is free to press B and
     * close the whole thing while that walk is still running, and proceeding
     * anyway would reopen a dialog they already dismissed.
     *
     * Also ignored if {@code requestToken} no longer matches {@link
     * #filterRequestToken} - the walk can just as easily finish after
     * someone presses B once (landing back on MENU, not dismissing) and then
     * taps Sort instead, and forcing {@link Page#FILTER} on them at that
     * point would yank away the page they had actually moved to. A stale
     * answer is simply dropped, the same as a dismissed dialog's.
     */
    public void enterFiltersFromMenu(int requestToken,
                                     Map<Filters.Field, List<Facets.Value>> values,
                                     List<Facets.Value> formats) {
        if (dialog == null || requestToken != filterRequestToken) return;

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
     * the highlight and the arrow - or, on MENU, the View row's own summary -
     * agree with what actually took effect, without this, choosing something
     * from the pad would look like it did nothing until the dialog was closed
     * and reopened. Safe to call whether or not the dialog is currently
     * showing; a no-op unless {@link #page} is {@link Page#SORT} or {@link
     * Page#MENU}, since nothing else calls this.
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
                scrollCursorIntoView();
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
                goBack();
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
     * One level up - B's own job, and a page's own back row's, so both call
     * this rather than each carrying a copy of what "up" means for it.
     *
     * VALUES always returns to FILTER. SORT and FILTER both always return to
     * MENU, since neither is reachable any other way - View has no page of
     * its own to return from at all, see {@link Page}'s own comment. MENU
     * itself is as far up as this dialog goes from wherever it was reached,
     * so back there dismisses.
     */
    private void goBack() {
        switch (page) {
            case VALUES:
                page = Page.FILTER;
                cursorRow = 0;
                rebuild();
                break;

            case SORT:
                page = Page.MENU;
                cursorRow = MENU_SORT;
                rebuild();
                break;

            case FILTER:
                page = Page.MENU;
                cursorRow = MENU_FILTER;
                rebuild();
                break;

            case MENU:
            default:
                dialog.dismiss();
                break;
        }
    }

    /**
     * Brings the cursor's own row into view inside whichever {@link
     * ScrollView} it happens to sit in - {@link Page#VALUES} on Developer or
     * Publisher, 277 and 196 values respectively on a real collection, is the
     * only page long enough for this to matter, but nothing here needs to
     * know that: {@link View#requestRectangleOnScreen} walks up through
     * whatever ancestors a row actually has, and is a harmless no-op on
     * every page whose rows are not inside a {@code ScrollView} at all.
     * {@code ScrollView.smoothScrollTo} would have needed this method to
     * compute the row's own offset by hand and would have scrolled it flush
     * with an edge on every step; this only scrolls the amount actually
     * needed, which is nothing at all most of the time a pad's cursor is
     * already inside the visible page.
     *
     * Called from {@link GamepadCursor.Nav#move}, moving the cursor a row at
     * a time, and from {@link #rebuild} whenever it stays on {@link
     * Page#VALUES} - the same row-relative idea covers both "the cursor
     * stepped past the edge" and "the whole ScrollView was just rebuilt at
     * offset 0 out from under a row that was not at the top".
     */
    private void scrollCursorIntoView() {
        if (cursorRow < 0 || cursorRow >= rows.size()) return;

        View row = rows.get(cursorRow);
        row.requestRectangleOnScreen(new Rect(0, 0, row.getWidth(), row.getHeight()), false);
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
        viewRow = null;

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);

        // The dialog itself, drawn by this view rather than by the platform's
        // dialog background. That one is a rounded panel with a minimum width
        // of its own, and this column is narrower than that minimum - so what
        // showed was a small dark square sitting inside a much larger pale
        // rounded box, empty to the right of the rows and below them. The
        // window is transparent now and this is the only thing drawn, so the
        // dialog is exactly the size of what is in it.
        android.graphics.drawable.GradientDrawable panel =
                new android.graphics.drawable.GradientDrawable();
        panel.setColor(Palette.BACKING);
        panel.setCornerRadius(pixels(CORNER_DP));
        column.setBackground(panel);

        column.setPadding(pixels(8), pixels(8), pixels(8), pixels(8));

        switch (page) {
            case SORT:
                buildSortPage(column);
                break;
            case FILTER:
                buildFilterPage(column);
                break;
            case VALUES:
                buildValuesPage(column);
                break;
            case MENU:
            default:
                buildMenuPage(column);
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

                // Same reasoning as show()'s own bump: a Facets walk asked
                // for from this very dialog can still be in flight after it
                // is dismissed, and a stale answer landing on a freshly
                // reopened one must not match filterRequestToken and force
                // it to FILTER.
                filterRequestToken++;
            });
            // Before setContentView, both of them: requestFeature throws
            // once a window has content, and the background has to be in
            // place before the decor is measured. Nothing of the platform's
            // own is wanted here - no title strip above the first row, and no
            // rounded panel behind a column that does not fill it - while the
            // dim behind stays, which is what says this is modal.
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.requestFeature(android.view.Window.FEATURE_NO_TITLE);
                window.setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(0x00000000));
            }

            dialog.setContentView(column, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT,
                                 ViewGroup.LayoutParams.WRAP_CONTENT);
            }

            dialog.show();
        } else {
            dialog.setContentView(column, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        paint();

        // buildValuesPage's own ScrollView is rebuilt from nothing every time
        // - fine for a fresh field, since openValues resets cursorRow to 0
        // too and this then lands exactly on the top row that should be
        // showing, but toggling a value below the fold rebuilds this same
        // page and would otherwise snap back to the top with no sign of what
        // was just picked. Reusing scrollCursorIntoView rather than saving
        // and restoring a raw pixel offset keeps one mechanism for "keep the
        // cursor row visible" instead of two that could disagree about where
        // that is. Posted rather than called straight away: immediately
        // after setContentView the new column has not been measured yet, so
        // every row's width and height still read zero and there is nothing
        // for requestRectangleOnScreen to scroll to - post() runs this once
        // the pending layout has actually happened.
        if (page == Page.VALUES) column.post(this::scrollCursorIntoView);
    }

    /**
     * The rows Select or the right stick's own click opens onto now - View
     * and Sort always, Filter too outside the tabs {@link
     * Callbacks#filteringAllowed} says it answers nothing for - each naming
     * what it is currently set to so checking without changing costs one
     * glance rather than one press.
     *
     * View activates on the spot rather than opening anything: flipping List
     * and Grid is a flick, not a considered choice, and a page behind it
     * would have cost three presses for what used to cost one - so choosing
     * this row calls {@link Callbacks#onViewMode} immediately, the same way
     * a filter row commits by its own name, and {@link #paint} is what keeps
     * its own summary honest afterwards since nothing here rebuilds for it.
     * Sort and Filter are genuinely considered choices - several fields, a
     * direction, five filterable fields of their own - so they still open a
     * page: {@link #buildSortPage} is what the old flat column's first half
     * became, and {@link #buildFilterPage} is the filter sheet, reached
     * here through {@link Callbacks#openFilters} rather than built directly,
     * since the values a field can be narrowed to are not known yet - see
     * that callback's own comment.
     */
    private void buildMenuPage(LinearLayout column) {
        TextView title = new TextView(activity);
        title.setText(R.string.library_options_title);
        title.setTextColor(Palette.MUTED);
        title.setTextSize(13);
        title.setPadding(pixels(16), pixels(12), pixels(16), pixels(8));
        column.addView(title);

        viewRow = addRow(column, menuRow(R.string.library_view, viewSummary()),
                () -> callbacks.onViewMode(!grid));

        addRow(column, menuRow(R.string.library_sort, sortLabel(sortIndex)), () -> {
            // Bumped here too, not just when Filter is tapped: this is the
            // one other thing MENU can do while a Filter answer might still
            // be in flight, and leaving for Sort is exactly the case
            // enterFiltersFromMenu's own comment on filterRequestToken
            // guards against.
            filterRequestToken++;
            page = Page.SORT;
            // Row 0 on SORT is its own back row - see buildSortPage - so the
            // sorting field is one past where its own index would put it.
            cursorRow = sortIndex + 1;
            rebuild();
        });

        // Left off the menu entirely outside Browse, rather than shown
        // disabled: Favourites and Recent are already answers to a question
        // of their own, and a row that could only ever do nothing there is
        // worse than one that is simply not offered - the same choice
        // LibraryActivity made for the toolbar's own Filter button before
        // it was folded into this menu.
        if (callbacks.filteringAllowed()) {
            addRow(column, menuRow(R.string.library_filter, filterSummary()),
                    () -> callbacks.openFilters(++filterRequestToken));
        }

        // Last, and only with a game selected: unlike the three above it, this
        // row is about one row of the list rather than about the list. Left
        // off entirely rather than disabled, the same choice Filter makes just
        // above - a row that could only ever do nothing is worse than one that
        // is not offered.
        if (callbacks.editingAllowed()) {
            addRow(column, activity.getString(R.string.edit_metadata_menu), () -> {
                dismiss();
                callbacks.editMetadata();
            });
        }

        // Beside it, and on the same selected-game condition plus somewhere to
        // ask: the two are the same subject from opposite ends - correcting a
        // game by hand, and fetching what somebody else already wrote down.
        if (callbacks.editingAllowed() && callbacks.scrapingAllowed()) {
            addRow(column, activity.getString(R.string.scrape_menu), () -> {
                dismiss();
                callbacks.scrapeSelected();
            });
        }
    }

    /** "View · List" or "Sort · Rating ▼" - the label a MENU row always
     *  carries, plus whatever currently answers "what is this set to", joined
     *  the same way {@link #filterRowLabel} already joins a filter field's
     *  own name to what is picked of it. */
    private String menuRow(int labelRes, String summary) {
        return activity.getString(labelRes) + " · " + summary;
    }

    private String viewSummary() {
        return activity.getString(grid ? R.string.library_view_grid : R.string.library_view_list);
    }

    /** {@link R.string#library_filter_none} with nothing set, or a count -
     *  never the fields themselves, which is what {@link #buildFilterPage}'s
     *  own rows are for; see {@link Filters#activeFieldCount}'s own comment,
     *  written for exactly this row. */
    private String filterSummary() {
        int count = filters.activeFieldCount();
        return count == 0
                ? activity.getString(R.string.library_filter_none)
                : activity.getResources().getQuantityString(
                        R.plurals.library_filter_count, count, count);
    }

    /** {@link Page#SORT}: a back row to MENU, then all five of {@code
     *  Sorting.FIELDS} - unlike the flat column this replaced, every field
     *  is offered here now, not just the first three; the direction stays
     *  what it always was, an arrow on whichever field is already sorting,
     *  toggled by choosing that same field again - see {@link #sortLabel}. */
    private void buildSortPage(LinearLayout column) {
        addRow(column, "‹ " + activity.getString(R.string.library_sort), this::goBack);
        column.addView(divider());

        for (int i = 0; i < Sorting.FIELDS.length; i++) {
            int index = i;
            TextView row = addRow(column, sortLabel(i), () -> callbacks.onSortField(index));
            sortRows.add(row);
        }
    }

    /**
     * The filter sheet's own top: five rows, one per {@link FilterRow}, each
     * showing the field's name alone when it is not narrowed or "Genre ·
     * Platform, Racing" when it is - see {@link #filterRowLabel}. "Clear all"
     * sits above them, only while {@link Filters#activeFieldCount} says
     * something is actually set.
     *
     * The very top row is a clickable "‹ Filter", the same shape {@link
     * #buildValuesPage}'s own back row is - MENU is the only door in now
     * that the toolbar's own Filter button is gone, so this page
     * always has somewhere to go back to.
     */
    private void buildFilterPage(LinearLayout column) {
        addRow(column, "‹ " + activity.getString(R.string.library_filter), this::goBack);
        column.addView(divider());

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
            search.setTextColor(Palette.TEXT);
            search.setHintTextColor(Palette.MUTED);
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
                    : Filters.ratingLabel(threshold);

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
                    ? name : name + " · " + Filters.ratingLabel(filters.minStars());
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

    private TextView addRow(LinearLayout column, String label, Runnable action) {
        TextView row = new TextView(activity);
        row.setText(label);
        row.setTextColor(Palette.TEXT);
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
     * These five rows are {@code Sorting.FIELDS} in its own order - name,
     * size, released, format, rating - matching {@code
     * LibraryActivity.sortFieldIndex} exactly, all five offered here since
     * the sort fields gained a page of their own with room for them.
     * Deliberately not "date", which named this row before {@code
     * library_sort_date} existed at all - see CLAUDE.md on a stored sort
     * field this build no longer reads. Also used, with {@code index ==
     * sortIndex} always true, to summarise the current field on MENU's own
     * Sort row - see {@link #buildMenuPage}. */
    private String sortLabel(int index) {
        String[] names = { activity.getString(R.string.library_sort_name),
                            activity.getString(R.string.library_sort_size),
                            activity.getString(R.string.library_sort_released),
                            activity.getString(R.string.library_sort_format),
                            activity.getString(R.string.library_sort_rating) };

        if (index != sortIndex) return names[index];
        return names[index] + (descending ? " ▼" : " ▲");
    }

    /**
     * Redraws every row: on {@link Page#SORT}, the text colour says which
     * field is actually sorting and the arrow on it says which way; on
     * {@link Page#MENU}, the View row's own text is refreshed to match
     * whatever {@link #grid} now is, since choosing it calls {@link
     * Callbacks#onViewMode} directly rather than rebuilding the page the way
     * every other row-with-a-summary does - see {@link #buildMenuPage}. On
     * every page, a tint says where the pad's own cursor is - a separate
     * signal from the text colour, since the cursor need not be on the row
     * that is set and touch already proved a plain highlight alone is not
     * enough once a pad can move without choosing. MENU's own Sort and
     * Filter rows and the filter sheet's own carry what is set baked into
     * their text already, fresh on every {@link #rebuild} - see {@link
     * #filterRowLabel} and {@link #refreshValueRows} - so this has nothing
     * further to do for them beyond the tint every page gets.
     */
    private void paint() {
        if (page == Page.SORT) {
            for (int i = 0; i < sortRows.size(); i++) {
                TextView row = sortRows.get(i);
                row.setText(sortLabel(i));
                row.setTextColor(i == sortIndex ? ACTIVE : Palette.TEXT);
            }
        }

        if (page == Page.MENU) {
            viewRow.setText(menuRow(R.string.library_view, viewSummary()));
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
