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
 * The catalogue tab: the shelves you descend into, the titles you tap, and the
 * next page, fetched because you scrolled.
 *
 * <b>Nothing is fetched speculatively.</b> A page when the grid reaches it, a
 * cover when its row is on screen, and nothing at all until somebody opens a
 * shelf - which is the difference between a client and a crawler, and this
 * app's address has been blocked once already for looking like the second.
 * {@link Catalogue#shelves()} is the one call that makes no request, which is
 * what lets this build itself on the UI thread; everything else goes through
 * {@link Work#run}.
 *
 * <b>Where a person is</b> is a stack of shelves. The roots are the
 * catalogue's declared ways in; opening one pushes it, a sub-shelf that comes
 * back inside a page pushes again, and {@link #onBack()} pops. At the roots it
 * answers false, so Back means what it always meant and the activity - not
 * this view - decides to leave.
 *
 * <b>Two guards on paging, and both are load-bearing.</b> Without {@code
 * inFlight} one fling sends four identical requests; without {@code hasMore}
 * the end of a shelf asks for ever, one paced request per fling, against a
 * host that blocks on behaviour patterns rather than on a published limit.
 */
public final class CatalogueView extends FrameLayout {

    private static final String TAG = "Zedex";

    /** What a chosen title is handed to - {@code CataloguePane} in Task 11,
     *  and the activity's own business, not this view's. Deliberately one
     *  method: a {@code Host} wider than about four is a seam in the wrong
     *  place. */
    public interface Host {
        void chosen(Catalogue.Item item);
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
     * Compared on arrival, the same shape {@code LibraryActivity.load} uses
     * and for the same reason: somebody types a second search, or opens
     * another shelf, before the first has answered - and the older answer,
     * arriving late, would otherwise append a page of the wrong shelf to the
     * rows of the new one.
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
                CatalogueView.this.host.chosen(item);
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
                if (dy <= 0 || inFlight || !hasMore) return;

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

        addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        showRoots();
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
        fetchToken++;          // any answer still in flight is for nowhere now
        inFlight = false;
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

    /** Empties the list and asks for page zero of whatever is on top. */
    private void restart() {
        rows.clear();
        adapter.setRows(rows);

        page = 0;
        hasMore = false;
        header.setText(labelOf(stack.peek()));

        fetch();
    }

    private void nextPage() {
        fetch();
    }

    /**
     * The failed row, taken away, and the same page asked for again.
     *
     * {@link #page} was never incremented by the page that failed, so this
     * asks the same question rather than skipping the page that did not
     * arrive - which would leave a hole nobody could see.
     */
    private void retry() {
        rows.remove(CatalogueAdapter.Failed.ROW);
        adapter.setRows(rows);
        fetch();
    }

    /**
     * One page, off the UI thread.
     *
     * Both guards are checked here as well as in the scroll listener, since a
     * retry and a fling can arrive at this from two directions.
     */
    private void fetch() {
        Catalogue.Shelf shelf = stack.peek();
        if (shelf == null || inFlight) return;

        // hasMore is not consulted for page zero: it is what a page answers,
        // and no page has been answered yet.
        if (page > 0 && !hasMore) return;

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
    }

    /** What a shelf is handed. Each ignores what it does not use, which is
     *  why there is one of these rather than an argument per kind. */
    private Catalogue.Query queryFor(Catalogue.Shelf shelf) {
        if (shelf.accepts(Catalogue.Shelf.Accepts.TEXT)) return Catalogue.Query.text(typed);
        if (shelf.accepts(Catalogue.Shelf.Accepts.LETTER)) {
            return Catalogue.Query.letter(typed.isEmpty()
                    ? "a" : typed.substring(0, 1));
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
