package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
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

                int count = manager.getItemCount();
                int last = manager.findLastVisibleItemPosition();
                if (last == RecyclerView.NO_POSITION) return;

                // One screen's warning, not one row's - fetching at the very
                // last row means the grid stops dead while the request goes
                // out, which reads as a catalogue that has ended. Measured
                // from the layout manager rather than guessed at, because how
                // many rows a screen holds is a fact about this device's
                // screen and this row's height, not a constant somebody can
                // choose correctly for both.
                int first = manager.findFirstVisibleItemPosition();
                int ahead = first == RecyclerView.NO_POSITION ? 1 : Math.max(1, last - first + 1);

                if (last >= count - ahead) nextPage();
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
     */
    private void nextPage() {
        if (failed) return;

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
     * A-Z opens onto twenty-six sub-shelves and carries the letter in each
     * shelf's own id, so nothing here has to know that letters exist - which
     * is the right shape, and why this screen grew no letter picker. The
     * branch stays because the seam declares the kind: a catalogue that asks
     * for its letter through the query gets the first character of whatever is
     * in the search box. That path is unexercised, and it is written down here
     * rather than left to be discovered.
     */
    private Catalogue.Query queryFor(Catalogue.Shelf shelf) {
        if (shelf.accepts(Catalogue.Shelf.Accepts.TEXT)) return Catalogue.Query.text(typed);
        if (shelf.accepts(Catalogue.Shelf.Accepts.LETTER) && !typed.isEmpty()) {
            return Catalogue.Query.letter(typed.substring(0, 1));
        }
        return Catalogue.Query.none();
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
        rows.addAll(result.items());
        adapter.setRows(rows);

        page++;
        hasMore = result.hasMore();

        String count = CatalogueAdapter.countLabel(result.total());
        header.setText(count == null ? labelOf(stack.peek())
                                     : labelOf(stack.peek()) + " · " + count);

        updateState();
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
