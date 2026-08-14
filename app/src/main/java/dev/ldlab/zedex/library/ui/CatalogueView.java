package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Types;
import dev.ldlab.zedex.library.catalogue.Catalogue;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.work.Work;

import android.content.Context;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Deque;
import java.util.List;

/**
 * The catalogue tab: the shelves you descend into, the titles you tap, the pane
 * that opens on one, and the next page, fetched because you scrolled.
 *
 * <b>Nothing is fetched speculatively.</b> A page when the grid reaches it, a
 * cover when its row is on screen, and nothing at all until somebody opens a
 * shelf - which is the difference between a client and a crawler, and this
 * app's address has been blocked once already for looking like the second.
 * {@link Catalogue#shelves()} is the one call that makes no request, which is
 * what lets this build itself on the UI thread; everything else goes through
 * {@link Work#run}.
 *
 * <b>The pane is part of this view, not of the screen holding it.</b> A tapped
 * title opens {@link CataloguePane} in the third of the window {@code
 * DetailPane} takes on the library's other three tabs, so a hosting activity
 * decides only which of the two views is showing - and Back closes the pane
 * before it starts popping shelves, since the pane is what is in front.
 *
 * <b>Where a person is</b> is a stack of shelves. The roots are the
 * catalogue's declared ways in; opening one pushes it, a sub-shelf that comes
 * back inside a page pushes again, and {@link #onBack()} pops. At the roots it
 * answers false, so Back means what it always meant and the activity - not
 * this view - decides to leave.
 *
 * <b>Three guards on paging, and all three are load-bearing.</b> Without
 * {@code inFlight} one fling sends four identical requests; without {@code
 * hasMore} the end of a shelf asks for ever; without {@code failed} a page
 * that did not arrive is asked for again on every downward scroll, since the
 * row it leaves behind sits exactly where the prefetch trigger fires. All
 * three are one paced request per fling against a host that blocks on
 * behaviour patterns rather than on a published limit.
 */
public final class CatalogueView extends FrameLayout {

    private static final String TAG = "Zedex";

    /** The same hairline {@code LibraryActivity} draws between its own list and
     *  its pane, so the two screens' seams match. */
    private static final int DIVIDER = 0x33ffffff;

    /**
     * The one thing this view cannot answer for itself.
     *
     * <b>A chosen title does not come through here.</b> It used to be declared
     * that way - "handed to {@code CataloguePane}... the activity's own
     * business" - and that was written before the pane existed. The pane sits
     * inside this view, in the third of the window {@code DetailPane} occupies
     * on the library's own three tabs, so choosing a title is entirely this
     * view's business now and nothing crosses the boundary for it.
     *
     * What does cross is an import: a file has appeared in somebody's folder
     * and whatever else is holding a listing of that folder - Browse, and the
     * metadata store's own caches - is keyed by path and knows nothing about a
     * file it did not list. Still deliberately one method: a {@code Host} wider
     * than about four is a seam in the wrong place.
     */
    public interface Host {
        void imported();
    }

    private final Catalogue catalogue;
    private final Host host;

    /**
     * Where a cover comes from.
     *
     * Built here rather than passed in, because the constructor this view is
     * specified with takes the catalogue and the host and nothing else - and
     * {@link Http.Real} needs only a {@link Context}, which every view has. It
     * carries the identity header for the same reason everything else in this
     * app does: an address that arrives without one is treated as a crawler.
     */
    private final Http http;

    private final EditText searchField;
    private final TextView header;

    /** "Format · No filter", and the way to change it - see {@link
     *  #chooseFormat}. */
    private final TextView formatRow;
    private final TextView emptyLabel;
    private final ProgressBar spinner;
    private final RecyclerView recycler;
    private final LinearLayoutManager manager;
    private final CatalogueAdapter adapter;

    /**
     * One title in full, and the button that brings it in.
     *
     * Inside this view rather than up in the activity, which is what lets a
     * hosting screen stay at "which of the two is showing" - see {@link Host}.
     * It takes the same third of the window {@code DetailPane} does, and the
     * same side of it: beside the list in landscape, beneath it in portrait.
     */
    private final CataloguePane pane;

    /** The hairline between the list and {@link #pane}, hidden and shown with
     *  it - a divider with nothing on the far side of it is a line across the
     *  screen for no reason. */
    private final View paneDivider;

    /**
     * Which format is wanted, or null for all of them.
     *
     * <b>Filtered here and not by the service.</b> ZXInfo's search has no such
     * parameter - {@code format}, {@code filetype} and {@code downloadtype}
     * are every one of them silently ignored, measured against a nonsense
     * parameter as the control - so this keeps what arrives instead. It costs
     * no extra request: a row already carries its own files, which is what
     * {@code ZxInfoCatalogue.itemFrom} was changed to keep.
     *
     * What it does cost is pages. An rzx is on about 13.5% of entries, so a
     * screenful is three or four pages rather than one, and {@link #deliver}
     * keeps asking until there is a screenful or two below what is showing -
     * see {@link #fillIfShortOfSlack}, {@link #LOOKAHEAD} and {@link #SCAN}.
     */
    private String format;

    /** Where a person is. Empty means the roots, which is also what makes
     *  {@link #onBack()} able to say "not mine". */
    private final Deque<Catalogue.Shelf> stack = new ArrayDeque<>();

    /** Everything on screen, in order: shelves, then items, then at most one
     *  failed-page row at the bottom. */
    private final List<Object> rows = new ArrayList<>();

    /** What was last typed, handed to whichever shelf is open - a shelf that
     *  does not use it ignores it, which is exactly what {@link
     *  Catalogue.Query} is for. */
    private String typed = "";

    /** The next page to ask for. Not incremented by a page that failed, so
     *  {@link #retry()} asks for the same one again. */
    private int page;

    private boolean hasMore;
    private boolean inFlight;

    /**
     * A page of the shelf now open did not arrive, and has not been asked for
     * again.
     *
     * <b>Scrolling must stop asking once this is set.</b> The failed row is
     * appended at the bottom, which is precisely where the prefetch trigger
     * fires, so without this every further downward scroll re-sends the same
     * request - one per fling, with nobody having asked for anything, against
     * the address this app has already had blocked once for behaviour
     * patterns. The retry button is the way back, and that is why it exists.
     *
     * Separate from {@link #hasMore}, which is the shelf's own answer about
     * whether there are more pages and stays true across a failure - clearing
     * that instead would silence the scroll and stop {@link #retry()} dead at
     * the same guard.
     */
    private boolean failed;

    /**
     * Compared on arrival, the same shape {@code LibraryActivity.load} uses
     * and for the same reason: somebody types a second search, or opens
     * another shelf, before the first has answered - and the older answer,
     * arriving late, would otherwise append a page of the wrong shelf to the
     * rows of the new one.
     *
     * <b>The token alone does not do it, and for a while this comment claimed
     * it did.</b> {@link #fetch()} takes its token <em>after</em> the {@code
     * inFlight} guard, so a shelf opened while a page was still coming made no
     * request at all - and then the earlier answer landed carrying a token
     * that still matched and was appended to the rows of the shelf it was not
     * from, under its header, with {@code page} and {@code hasMore} now
     * describing something else. Invalidate first, then decide whether to
     * ask: every path that changes what is on screen goes through {@link
     * #abandon()} before it goes anywhere near {@link #fetch()}.
     */
    private int fetchToken;

    public CatalogueView(Context context, Catalogue catalogue, Host host) {
        super(context);

        this.catalogue = catalogue;
        this.host = host;
        this.http = new Http.Real(context);

        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * density);

        setBackgroundColor(Palette.BACKING);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        searchField = new EditText(context);
        searchField.setHint(R.string.catalogue_search_hint);
        // Described as well as hinted. An empty EditText's hint is not
        // reliably what accessibility reports as the node's text, so anything
        // looking for this field by name - a screen reader, or the device
        // test - would be looking for something that is only sometimes there.
        // Set once and never changed, which is what CLAUDE.md's own rule
        // against a description that moves asks for.
        searchField.setContentDescription(context.getString(R.string.catalogue_search_hint));
        searchField.setSingleLine();
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setTextColor(Palette.TEXT);
        searchField.setHintTextColor(Palette.MUTED);
        searchField.setPadding(pad, pad, pad, pad);
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            // Enter from a hardware keyboard arrives as a key event with no
            // action id, which is how the device test types; the IME's own
            // Search key arrives as the action. Both mean the same thing.
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_NULL) {
                submitSearch();
                return true;
            }
            return false;
        });
        column.addView(searchField, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        header = new TextView(context);
        header.setTextColor(Palette.MUTED);
        header.setTextSize(13);
        header.setPadding(pad, 0, pad, Math.round(6 * density));
        column.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // The one control the catalogue has of its own, and it is a row rather
        // than a chip because it has to say what it is set to as well as offer
        // to change it - the same shape the library's own Filter row has, and
        // the same two strings, so this adds no words to translate.
        formatRow = new TextView(context);
        formatRow.setTextColor(Palette.MUTED);
        formatRow.setTextSize(13);
        formatRow.setPadding(pad, 0, pad, Math.round(6 * density));
        formatRow.setOnClickListener(v -> chooseFormat());
        column.addView(formatRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        showFormat();

        // Hidden rather than disabled: a catalogue whose rows do not know
        // their own formats cannot honour this filter at all - see
        // Catalogue.knowsFormats's own javadoc - and a control that is
        // present and does nothing is worse than one that is absent, the
        // same reasoning that already hides a shelf nothing can build.
        formatRow.setVisibility(catalogue.knowsFormats() ? View.VISIBLE : View.GONE);

        FrameLayout content = new FrameLayout(context);

        recycler = new RecyclerView(context);
        manager = new LinearLayoutManager(context);
        recycler.setLayoutManager(manager);

        adapter = new CatalogueAdapter(context, http, new CatalogueAdapter.Callbacks() {
            @Override
            public void openShelf(Catalogue.Shelf shelf) {
                open(shelf);
            }

            @Override
            public void openItem(Catalogue.Item item) {
                showPane(item);
            }

            @Override
            public void retry() {
                CatalogueView.this.retry();
            }
        });
        recycler.setAdapter(adapter);
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView view, int dx, int dy) {
                if (dy <= 0 || inFlight || failed || !hasMore) return;

                if (!enoughBelow()) nextPage();
            }
        });
        content.addView(recycler, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        emptyLabel = new TextView(context);
        emptyLabel.setText(R.string.catalogue_empty);
        emptyLabel.setTextColor(Palette.MUTED);
        emptyLabel.setTextSize(15);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyLabel.setVisibility(View.GONE);
        content.addView(emptyLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        spinner = new ProgressBar(context);
        spinner.setVisibility(View.GONE);
        content.addView(spinner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        column.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // The list and the pane, two against one, split the way LibraryActivity
        // splits its own list and DetailPane - beside each other in landscape,
        // stacked in portrait, since height is the scarce thing in the first and
        // width in the second. Read once, here, and handed to the pane rather
        // than asked again inside it: two reads of one question are two chances
        // to disagree.
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout outer = new LinearLayout(context);
        outer.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        outer.addView(column, new LinearLayout.LayoutParams(
                landscape ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                landscape ? LinearLayout.LayoutParams.MATCH_PARENT : 0, 2f));

        paneDivider = new View(context);
        paneDivider.setBackgroundColor(DIVIDER);
        paneDivider.setVisibility(View.GONE);
        outer.addView(paneDivider, landscape
                ? new LinearLayout.LayoutParams(Math.round(density), LinearLayout.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(density)));

        // Hidden, and taking no room while it is: a GONE child of a weighted
        // LinearLayout is not laid out at all, so the list has the whole window
        // until somebody taps a title. That is the one place this differs from
        // DetailPane, which is present whether or not anything is selected -
        // see CataloguePane's own class comment for why.
        // Two methods now, so no longer a method reference: the pane can also
        // hand back a shelf to descend into, which is this view's business
        // because the stack of shelves and Back are.
        pane = new CataloguePane(context, landscape, catalogue, http,
                new CataloguePane.Host() {
                    @Override
                    public void imported() {
                        host.imported();
                    }

                    @Override
                    public void openShelf(Catalogue.Shelf shelf) {
                        open(shelf);
                    }
                });
        pane.setVisibility(View.GONE);
        outer.addView(pane, new LinearLayout.LayoutParams(
                landscape ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                landscape ? LinearLayout.LayoutParams.MATCH_PARENT : 0, 1f));

        addView(outer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        showRoots();
    }

    // --- the pane -------------------------------------------------------------------

    /**
     * A title was tapped: the pane opens on it and asks the catalogue for its
     * versions and files.
     *
     * Nothing is fetched for a row that was only drawn - see {@link
     * CataloguePane#show}, and {@link Catalogue#item}'s own note on what makes
     * that call the expensive one.
     */
    private void showPane(Catalogue.Item item) {
        paneDivider.setVisibility(View.VISIBLE);
        pane.setVisibility(View.VISIBLE);
        pane.show(item);
    }

    /**
     * Closes it, and says whether there was one to close.
     *
     * Not {@code show(null)}: there is nothing for an empty pane to say here
     * that the list behind it does not say better, and the third of the window
     * it occupies is worth giving back.
     */
    private boolean hidePane() {
        if (pane.getVisibility() != View.VISIBLE) return false;

        pane.setVisibility(View.GONE);
        paneDivider.setVisibility(View.GONE);
        return true;
    }

    /**
     * The folder picker's answer, on its way to the pane - forwarded by
     * whichever activity is holding this view, since a view has no {@code
     * onActivityResult} of its own.
     *
     * The pane asks for a writable content folder at the moment somebody
     * imports, and only then; see {@link CataloguePane#REQUEST_WRITABLE_TREE}.
     *
     * @return whether it was the pane's, so a host can go on to its own.
     */
    public boolean onActivityResult(int request, int result, android.content.Intent data) {
        return pane.onActivityResult(request, result, data);
    }

    // --- a controller ---------------------------------------------------------------

    /**
     * A pad's direction, as a move of the framework's own focus.
     *
     * <b>Focus, not a cursor of this view's own.</b> Every row here is
     * clickable and therefore focusable, {@link Ripple} already draws the
     * focused one with a cyan ring, and {@link RecyclerView} already scrolls a
     * list to reveal the row a focus search ran off the end of - so a second
     * idea of "where the pad is", with its own highlight and its own scrolling,
     * would be three things to keep in step where the platform offers one.
     * That is also why this is not {@code LibraryActivity}'s own {@code
     * moveCursor}: that one moves a <em>selection</em>, which is a different
     * fact - a selected row fills a pane - and this view has no selection.
     *
     * <b>Why the pad reaches this at all, rather than falling through to the
     * view tree.</b> {@code LibraryActivity} claims pad input before any view
     * sees it, in two places, and it has to: a literal {@code KeyEvent} is
     * taken by {@code dispatchKeyEvent} through {@code GamepadCursor.key},
     * and on many pads a D-pad push does not arrive as a key at all but as a
     * hat <em>axis</em>, which reaches {@code onGenericMotionEvent} and
     * {@code GamepadCursor.motion} - and which Android turns into no focus
     * move whatever. Letting the keys through would leave those pads dead on
     * this screen while working on others, which is the worst of the three
     * outcomes.
     *
     * @param direction one of {@code View.FOCUS_UP}, {@code DOWN}, {@code
     *                  LEFT}, {@code RIGHT}.
     * @return whether the focus actually moved.
     */
    public boolean moveFocus(int direction) {
        View focused = findFocus();

        if (focused == null) {
            // Nothing has it yet - the first row on screen, rather than the
            // first focusable, which is the search field: a pad pressing down
            // means the list.
            View first = recycler.getChildCount() > 0 ? recycler.getChildAt(0) : null;
            return first != null && first.requestFocus();
        }

        // focusSearch and not FocusFinder over this view: it goes up through
        // RecyclerView's own override, which is what scrolls the list and lays
        // out one more row when the search runs off the end of what is on
        // screen. FocusFinder within this view would simply answer null there,
        // and a long shelf would be unwalkable past its first screenful.
        View next = focused.focusSearch(direction);

        // That same search does not stop at this view's edge, so it can answer
        // with the rail beside us - focus leaving the catalogue for the tab
        // buttons on a press meant for the list. Refused here rather than
        // relied on not to happen.
        if (next == null || next == focused || !holds(next)) return false;

        return next.requestFocus(direction);
    }

    /** The focused row, pressed - A on a pad. The framework's own click, so a
     *  row, a shelf, the retry button and the pane's buttons all answer to it
     *  without this knowing which is which. */
    public boolean activateFocused() {
        View focused = findFocus();

        return focused != null && holds(focused) && focused.performClick();
    }

    /**
     * B, with the search field focused: the keyboard goes and the focus drops
     * back to the list, without leaving the shelf.
     *
     * The same answer {@code LibraryActivity} gives for its own search field,
     * and asked before Back means anything else - typing and then pressing B
     * to get out of the field must not also pop a shelf on the same press.
     *
     * @return whether the field had the focus, so a caller can go on to its own
     *         idea of Back.
     */
    public boolean releaseSearchField() {
        if (!searchField.hasFocus()) return false;

        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);

        searchField.clearFocus();

        View first = recycler.getChildCount() > 0 ? recycler.getChildAt(0) : null;
        if (first != null) first.requestFocus();

        return true;
    }

    /** X: the search field, focused, with the keyboard up - the same thing the
     *  button does on the library's own three tabs, aimed at this view's field
     *  rather than at the one hidden behind it. */
    public void focusSearchField() {
        searchField.requestFocus();

        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(searchField,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /** Whether a view is inside this one - the bound on where focus may go. */
    private boolean holds(View view) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == this) return true;
        }
        return false;
    }

    // --- where a person is ---------------------------------------------------------

    /**
     * The declared ways in, and no request made to draw them.
     *
     * A catalogue whose shelves depend on something fetched declares one shelf
     * that yields the rest when it is opened - see {@link Catalogue#shelves()}
     * - so this is safe on the UI thread and stays safe as catalogues are
     * added.
     */
    private void showRoots() {
        stack.clear();
        abandon();

        // Whatever the pane was about is not on this list any more. Both paths
        // that replace what is on screen do this - see restart().
        hidePane();
        hasMore = false;
        page = 0;

        rows.clear();
        rows.addAll(catalogue.shelves());
        adapter.setRows(rows);

        header.setText(catalogue.name());
        updateState();
    }

    /** Descends: pushes the shelf and asks for its first page. */
    private void open(Catalogue.Shelf shelf) {
        stack.push(shelf);
        restart();
    }

    /**
     * The format filter, chosen from what this app can actually do something
     * with.
     *
     * <b>The list is {@code Types.OPENABLE} and nothing invented here.</b> A
     * filter offering a format the app cannot open would be a search whose
     * every result is a dead end, which is the same fault as a chooser that
     * changes nothing; and a list of its own would be a second place to keep
     * in step with the first. {@code gz} is left out for the one reason it is
     * left out of {@code Pick.PREFERENCE} too - it is a wrapper rather than a
     * format, and nothing is catalogued as one.
     *
     * Shown upper-cased, the way the row's own badge shows it, and reusing the
     * library's own two words - Format, No filter - so this adds nothing to
     * translate.
     */
    private void chooseFormat() {
        List<String> formats = new ArrayList<>();
        for (String openable : Types.openable()) {
            if (!"gz".equals(openable)) formats.add(openable);
        }

        String[] labels = new String[formats.size() + 1];
        labels[0] = getContext().getString(R.string.library_filter_none);
        for (int at = 0; at < formats.size(); at++) {
            labels[at + 1] = formats.get(at).toUpperCase(Locale.ROOT);
        }

        new android.app.AlertDialog.Builder(getContext())
                .setTitle(R.string.library_filter_format)
                .setItems(labels, (dialog, which) -> {
                    setFormat(which == 0 ? null : formats.get(which - 1));
                })
                .show();
    }

    /** The filter, and the shelf read again from its first page: a filter
     *  applied to what is already on screen would show whichever rows this
     *  view happens to have fetched rather than what the shelf holds. */
    private void setFormat(String wanted) {
        if (format == null ? wanted == null : format.equals(wanted)) return;

        format = wanted;
        showFormat();

        if (stack.isEmpty()) return;   // the roots are shelves, not rows
        restart();
    }

    private void showFormat() {
        String value = format == null
                ? getContext().getString(R.string.library_filter_none)
                : format.toUpperCase(Locale.ROOT);

        formatRow.setText(getContext().getString(R.string.library_filter_format)
                          + " · " + value);
    }

    /**
     * Back out of one shelf.
     *
     * @return false at the roots, so Back keeps meaning what it means
     *         everywhere else and the activity is the one that decides to
     *         leave. True when this view handled it.
     */
    public boolean onBack() {
        // The pane first, and before the stack is touched: it is what is in
        // front, so it is what Back is about. Closing it leaves the list exactly
        // where it was, which is where somebody tapped from.
        if (hidePane()) return true;

        if (stack.isEmpty()) return false;

        stack.pop();

        if (stack.isEmpty()) {
            // Back to the roots also clears what was typed: the text applies
            // to a shelf, and there is no shelf open to apply it to.
            typed = "";
            searchField.setText("");
            showRoots();
        } else {
            // The shelf underneath is asked again rather than remembered.
            // Keeping every page of every shelf on the way down is a cache
            // with an eviction policy nobody has needed yet; one request on
            // the way back up is the cheaper thing to be wrong about.
            restart();
        }
        return true;
    }

    /**
     * What was typed, applied.
     *
     * Text always means the catalogue's own text shelf, whatever else is
     * open: a search box that narrowed whichever shelf happened to be showing
     * would be a different question on every shelf, and one of them - "A-Z" -
     * takes a letter rather than a phrase. Emptying the box goes back to the
     * roots, which is the only reading of "no search" that leaves somewhere
     * to be.
     */
    private void submitSearch() {
        typed = searchField.getText().toString().trim();

        if (typed.isEmpty()) {
            showRoots();
            return;
        }

        Catalogue.Shelf text = shelfThatTakesText();
        if (text == null) return;   // a catalogue with no search; nothing to do

        stack.clear();
        stack.push(text);
        restart();
    }

    private Catalogue.Shelf shelfThatTakesText() {
        for (Catalogue.Shelf shelf : catalogue.shelves()) {
            if (shelf.accepts(Catalogue.Shelf.Accepts.TEXT)) return shelf;
        }
        return null;
    }

    // --- pages ---------------------------------------------------------------------

    /**
     * Whatever is on its way is for somewhere nobody is any more.
     *
     * <b>Invalidate first, then decide whether to ask.</b> Both halves are
     * needed and neither is enough alone: bumping the token is what makes the
     * older answer undeliverable, and clearing {@code inFlight} is what lets
     * the new question actually go out - {@link #fetch()} returns at its
     * {@code inFlight} guard before it takes a token, so without this a search
     * typed while a page was still coming made no request at all and then wore
     * the previous shelf's page as its results. {@code failed} goes too: the
     * failure belonged to the shelf being left.
     *
     * The request already in flight is not cancelled, only disowned. It is one
     * request that has already been paid for; racing it would need a
     * cancellable {@code Http} this app does not have, and its answer is
     * dropped on arrival.
     */
    private void abandon() {
        fetchToken++;
        inFlight = false;
        failed = false;
    }

    /** Empties the list and asks for page zero of whatever is on top. */
    private void restart() {
        abandon();

        // A pane open over a shelf nobody is on any more, with an Import button
        // for a title that is no longer on the list behind it.
        hidePane();

        rows.clear();
        adapter.setRows(rows);

        page = 0;
        hasMore = false;

        // A fresh shelf gets the whole budget. Without this, a shelf whose
        // fill gave up leaves the count spent, and the next one - Back into
        // the shelf underneath, a second search, a different letter - is
        // allowed no fill at all and shows whatever its first page happened to
        // hold. Set here rather than only in setFormat, which is one of four
        // ways a new shelf is put on screen.
        scanned = 0;

        header.setText(labelOf(stack.peek()));

        fetch();
    }

    /**
     * The next page, because somebody scrolled towards it.
     *
     * Refused after a failure. The failed row is at the bottom, which is where
     * the prefetch trigger fires, so this is the path that would re-send the
     * same request on every fling with nobody having asked for anything. See
     * {@link #failed}; {@link #retry()} is the way back.
     *
     * A scroll is a person asking for more, so the fill's budget starts again
     * here: {@link #SCAN} bounds what this view reads on its own, and this is
     * the one path where somebody said to.
     */
    private void nextPage() {
        if (failed) return;

        scanned = 0;
        fetch();
    }

    /**
     * The failed row, taken away, and the same page asked for again.
     *
     * {@link #page} was never incremented by the page that failed, so this
     * asks the same question rather than skipping the page that did not
     * arrive - which would leave a hole nobody could see.
     *
     * <b>The row goes only if a request actually went out.</b> {@link #fetch()}
     * can decline - something else is already in flight, or there is no shelf
     * open - and removing the row first left somebody looking at a page that
     * had not come back, with nothing coming and no way left to ask.
     */
    private void retry() {
        boolean was = failed;
        failed = false;

        if (!fetch()) {
            failed = was;
            return;
        }

        rows.remove(CatalogueAdapter.Failed.ROW);
        adapter.setRows(rows);
        updateState();
    }

    /**
     * One page, off the UI thread.
     *
     * The guards are checked here as well as in the scroll listener, since a
     * retry and a fling can arrive at this from two directions.
     *
     * @return whether a request actually went out, since a caller that is
     *         about to change what is on screen on the strength of one needs
     *         to know - see {@link #retry()}.
     */
    private boolean fetch() {
        Catalogue.Shelf shelf = stack.peek();
        if (shelf == null || inFlight) return false;

        // hasMore is not consulted for page zero: it is what a page answers,
        // and no page has been answered yet.
        if (page > 0 && !hasMore) return false;

        inFlight = true;
        updateState();

        int token = ++fetchToken;
        int wanted = page;
        Catalogue.Query query = queryFor(shelf);

        Work.run("catalogue", () -> {
            Catalogue.Page result = null;
            Throwable failure = null;

            try {
                result = catalogue.open(shelf, query, wanted);
            } catch (ScrapeException e) {
                failure = e;
            } catch (RuntimeException e) {
                // Not expected, and not worth taking the app down for: a
                // catalogue is somebody else's JSON, and the honest answer to
                // an unreadable one is the same row a refusal gets.
                failure = e;
            }

            Catalogue.Page answered = result;
            Throwable why = failure;

            Work.onMain(() -> deliver(token, answered, why));
        });

        return true;
    }

    /**
     * What a shelf is handed. Each ignores what it does not use, which is why
     * there is one of these rather than an argument per kind.
     *
     * <b>No catalogue this app ships asks for a letter this way.</b> ZXInfo's
     * A-Z opens onto twenty-seven sub-shelves and carries the letter in each
     * shelf's own id, so nothing here has to know that letters exist - which
     * is the right shape, and why this screen grew no letter picker. The
     * branch stays because the seam declares the kind: a catalogue that asks
     * for its letter through the query gets the first character of whatever is
     * in the search box. That path is unexercised, and it is written down here
     * rather than left to be discovered.
     */
    private Catalogue.Query queryFor(Catalogue.Shelf shelf) {
        Catalogue.Query query = Catalogue.Query.none();

        if (shelf.accepts(Catalogue.Shelf.Accepts.TEXT)) {
            query = Catalogue.Query.text(typed);
        } else if (shelf.accepts(Catalogue.Shelf.Accepts.LETTER) && !typed.isEmpty()) {
            query = Catalogue.Query.letter(typed.substring(0, 1));
        }

        // A filter set here means most of what comes back is dropped here too,
        // and a catalogue that can answer with more rows at once should be told
        // so - it is the round trip and the pacing that a sifting shelf spends,
        // not the bytes. Only while there is a filter, and only for a catalogue
        // that can actually apply one: knowsFormats() false means a filter is
        // never being read on the far side of this shelf, so the hint would buy
        // bigger pages for nothing kept - see
        // ZxartCatalogueTest.aZxartQueryIsNeverSifting.
        return format == null || !catalogue.knowsFormats() ? query : query.sifting();
    }

    private void deliver(int token, Catalogue.Page result, Throwable failure) {
        // Superseded - another shelf was opened, or another search typed,
        // while this was on its way. Appending it now would put one shelf's
        // page under another shelf's rows.
        if (token != fetchToken) return;

        inFlight = false;

        if (failure != null) {
            Log.w(TAG, "a page of " + labelOf(stack.peek()) + " did not arrive", failure);

            // Automatic paging stops here. hasMore is left alone - it is the
            // shelf's own answer and retry() has to get past it - so this is
            // what keeps the row that failure leaves at the bottom of the list
            // from re-sending the same request on every scroll.
            failed = true;

            // Appended, so whatever already arrived stays on screen. An
            // emptied grid is indistinguishable from a catalogue with nothing
            // in it.
            if (!rows.contains(CatalogueAdapter.Failed.ROW)) {
                rows.add(CatalogueAdapter.Failed.ROW);
            }
            adapter.setRows(rows);
            updateState();
            return;
        }

        rows.addAll(result.shelves());

        int before = rows.size();
        for (Catalogue.Item item : result.items()) {
            if (wanted(item)) rows.add(item);
        }
        boolean addedSomething = rows.size() > before;

        adapter.setRows(rows);

        page++;
        hasMore = result.hasMore();

        // What this page cost, which is what the fill is bounded by: the rows
        // the catalogue read, not the few that got past the filter.
        scanned += result.items().size();

        String count = CatalogueAdapter.countLabel(result.total());
        header.setText(count == null ? labelOf(stack.peek())
                                     : labelOf(stack.peek()) + " · " + count);

        updateState();

        // A filtered page can add nothing at all and still not be the end of
        // the shelf, and nothing would ask again: the scroll listener fires on
        // scrolling, and a list that gained no rows cannot be scrolled. So the
        // chase continues here, while pages keep arriving empty-handed.
        //
        // Kept synchronous for this case, rather than folded into the one
        // below: a page that added no row cannot have changed what is below
        // what is showing, so there is nothing to wait for a layout to say -
        // and going round through post() would leave `inFlight` false with no
        // rows for a frame, which is the state updateState() draws "Nothing
        // here." in. That would flash the empty label between every pair of
        // pages of a chase.
        if (!addedSomething && hasMore && scanned < SCAN) {
            fetch();
            return;
        }

        // ...and a page that added one row and left the list short stalls in
        // exactly the same way, which is the half this used to miss. Stopping
        // as soon as a page added anything left four rows on screen against a
        // live RZX filter - page two of ten never asked for, six further
        // matches in the pages it stopped short of - and no way at all to ask
        // for more, because the only thing that asks is a scroll and there was
        // nothing to scroll. See fillIfShortOfSlack.
        fillIfShortOfSlack(token);
    }

    /**
     * Another page, while there is not enough list below what is showing.
     *
     * <b>Filling until the list can merely be scrolled is the minimum, and the
     * minimum is a treadmill.</b> That was the first cut of this and it left a
     * filtered shelf sitting exactly one screen deep: the first flick reached
     * the bottom, bought one page, and waited for it. Measured on a live TRD
     * filter - 4.3% of entries, so 1.3 rows per thirty-row page, at about half
     * a second a page - that is four rows and a wait, over and over, however
     * hard somebody scrolls. So the target is {@link #LOOKAHEAD} screenfuls
     * below the last row showing, and the fill runs on until it has them.
     *
     * <b>Asked after a layout, because before one the question has no
     * answer.</b> What is visible is what is laid out, and the rows just handed
     * to the adapter are not - so this is posted, which puts it after the
     * traversal that {@code setRows} asked for: a layout pass raises a sync
     * barrier on the queue, and an ordinary posted message cannot overtake it.
     * Reading it inline instead answers about the previous page every time,
     * which is one page late for ever.
     */
    private void fillIfShortOfSlack(int token) {
        recycler.post(() -> {
            // The shelf under this list has been replaced - another search,
            // another shelf, the roots. Its page is nothing to do with what is
            // on screen now, and abandon() has already invalidated the token.
            if (token != fetchToken || failed || !hasMore) return;

            if (enoughBelow()) return;
            if (scanned >= SCAN) return;

            fetch();
        });
    }

    /**
     * Whether there is enough list below what is showing to scroll into.
     *
     * One question, asked by both the things that fetch: the scroll listener,
     * so a flick never lands on the end of the list while a request is still
     * out, and {@link #fillIfShortOfSlack}, so a shelf whose filter keeps one
     * row in thirty gets there without being scrolled at. They used to be two
     * rules with two constants, and the fill's was the strictest reading there
     * is - "can it be scrolled at all" - which is how a shelf came to be
     * exactly one screen deep for ever.
     *
     * <b>Measured from the layout manager, never from a constant.</b> How many
     * rows a screen holds is a fact about this device's height and this row's,
     * not a number somebody can choose correctly for both.
     */
    private boolean enoughBelow() {
        int last = manager.findLastVisibleItemPosition();

        // Nothing is laid out yet, so there is nothing below anything.
        if (last == RecyclerView.NO_POSITION) return false;

        int first = manager.findFirstVisibleItemPosition();
        int visible = first == RecyclerView.NO_POSITION ? 1 : Math.max(1, last - first + 1);

        return manager.getItemCount() - 1 - last >= visible * LOOKAHEAD;
    }

    /** Screenfuls to keep below the last row showing. Two rather than one
     *  because one is what a flick crosses. */
    private static final int LOOKAHEAD = 2;

    /**
     * How many of the catalogue's own rows one unattended fill may read
     * before it gives up.
     *
     * The alternative is a filter nobody has anything for walking a
     * ten-thousand-row shelf a page at a time. Three hundred entries is about
     * two and a half seconds at the pacing this app keeps, and then it stops
     * and says the shelf is empty - which for that format and that shelf it may
     * as well be. Scrolling starts it again, and so does every fresh shelf -
     * see {@link #restart()}.
     *
     * <b>Counted in entries read and not in pages asked for</b>, because a
     * sifting shelf asks for a bigger page - see {@code Catalogue.Query} - and
     * a bound in pages would quietly triple what a fill costs the moment that
     * page size changed. What this bounds is the traffic, so it is measured in
     * the thing that makes the traffic.
     *
     * <b>And it is not reset by a page that brought something.</b> A filter
     * that matches one row in thirty would otherwise be allowed a fresh three
     * hundred per row it found. A scroll is a person asking, and that is what
     * starts a new one - see {@link #nextPage()}.
     */
    private static final int SCAN = 300;

    private int scanned;

    /** Whether this row is one the filter wants. Everything, when there is no
     *  filter - which is the ordinary case and costs a null check. */
    private boolean wanted(Catalogue.Item item) {
        return format == null || item.formats().contains(format);
    }

    private String labelOf(Catalogue.Shelf shelf) {
        return shelf == null ? catalogue.name() : shelf.label();
    }

    /** The spinner while something is coming, the two words when nothing did.
     *  Never both, and never either while there are rows to read. */
    private void updateState() {
        spinner.setVisibility(inFlight && rows.isEmpty() ? View.VISIBLE : View.GONE);
        emptyLabel.setVisibility(!inFlight && rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * How many rows are on screen - what the device test reads, since a count
     * built by scrolling and counting titles is off by one or two on repeated
     * runs against the very same list. See {@code FilterTest.rowCount}, which
     * reaches {@code LibraryActivity}'s own adapter for the same reason.
     */
    public int rowCount() {
        return adapter.getItemCount();
    }
}
