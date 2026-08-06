package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.Favorites;
import dev.ldlab.zedex.library.Listing;
import dev.ldlab.zedex.library.ui.EntryAdapter;
import dev.ldlab.zedex.library.ui.GamepadCursor;
import dev.ldlab.zedex.storage.Recents;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.view.SafeArea;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The screen the app can open on: the content folder, browsable, with folders
 * and {@code .zip} archives to walk into and a game at the end of it -
 * Favourites and Recent sit beside Browse as two more views of things the app
 * already keeps. See docs/LIBRARY.md for the design and why each choice in
 * here was made.
 *
 * {@link SettingsActivity#startsInLibrary} is asked before anything else: with
 * the switch off, or with no content folder granted, this activity's whole job
 * is to hand straight over to {@link EmulatorActivity} and get out of the way,
 * so the app opens on the machine exactly as it always has.
 *
 * Everything {@link Listing} does is a round trip to another app's content
 * provider, or a stream read from a zip - never safe to call from this
 * thread - so every load happens on a thread of its own; see {@link #load}.
 * That is the one requirement a folder of a few thousand tapes puts on this
 * screen, and the reason it exists at all.
 */
public final class LibraryActivity extends Activity {

    /** Every screen speaks the chosen language; see {@link Language}. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Language.wrap(base));
    }

    private static final String TAG = "Zedex";

    private static final int REQUEST_CONTENT_TREE = 1;

    /** "list" or "grid"; read with getString always - see CLAUDE.md. */
    private static final String KEY_VIEW = "libraryView";
    private static final String VIEW_LIST = "list";
    private static final String VIEW_GRID = "grid";

    /** "name", "date" or "size"; read with getString always. */
    private static final String KEY_SORT = "librarySort";
    private static final String SORT_NAME = "name";
    private static final String SORT_DATE = "date";
    private static final String SORT_SIZE = "size";

    /** Reverses whichever field {@link #KEY_SORT} names; read with
     *  getBoolean always. Ascending - name A-Z, oldest first, smallest first
     *  - is the default for every field alike, so one flag serves all three. */
    private static final String KEY_SORT_DESC = "librarySortDescending";

    private static final int BACKING = 0xff14151a;
    private static final int TEXT = 0xffededf2;
    private static final int MUTED = 0xff8b8b99;
    private static final int ACTIVE = 0xff00b0c8;
    private static final int DIVIDER = 0x33ffffff;

    private static final int[] TAB_LABELS = {
        R.string.library_tab_browse, R.string.library_tab_favorites,
        R.string.library_tab_recents,
    };
    private static final int[] TAB_ICONS = {
        R.drawable.ic_folder, R.drawable.ic_bookmark, R.drawable.ic_history,
    };

    private enum Tab { BROWSE, FAVORITES, RECENTS }

    /**
     * One level of Browse's own back stack: the folder or zip it is showing,
     * and whether it is a zip - which decides whether {@link #load} asks
     * {@link Listing#folder} or {@link Listing#archive} for its children.
     *
     * Also where this listing was left, and what was entered to leave it -
     * set by {@link #enter} on the level being left, just before a new one is
     * pushed on top of it, and read back by {@link #popStack} once this level
     * is on top again. Not final, and not known at construction: a level
     * carries this about itself only from the moment something below it is
     * walked into, which for most levels on the stack is never.
     */
    private static final class Level {
        final Uri uri;
        final boolean archive;
        final String name;

        /** The row of this listing that was walked into, so returning here
         *  can select it again - see {@link Entry#key}. Null until then. */
        String returnKey;

        /** Where the list was scrolled to when {@code returnKey} was walked
         *  into: the first visible row and its pixel offset, exactly what
         *  {@link androidx.recyclerview.widget.LinearLayoutManager
         *  #scrollToPositionWithOffset} wants back. {@link
         *  androidx.recyclerview.widget.RecyclerView#NO_POSITION} until set. */
        int scrollPosition = RecyclerView.NO_POSITION;
        int scrollOffset;

        Level(Uri uri, boolean archive, String name) {
            this.uri = uri;
            this.archive = archive;
            this.name = name;
        }
    }

    private SharedPreferences preferences;
    private Tab tab = Tab.BROWSE;

    /**
     * Browse's own back stack, root first. Never touched by the other two
     * tabs, which are flat lists with nothing to walk into.
     */
    private final List<Level> stack = new ArrayList<>();

    /**
     * Set by {@link #popStack} just before its own {@link #load}, and read
     * and cleared by {@link #applyFilterSort} once that load's rows are in
     * the adapter - the one moment {@link Level#scrollPosition} and {@link
     * Level#returnKey} can actually be acted on. False for every other
     * reload - a fresh {@link #enter}, a sort, a search, {@link #onResume} -
     * so none of those re-applies a scroll a pop already spent.
     */
    private boolean restoringPosition;

    private String sort = SORT_NAME;
    private boolean sortDescending;
    private String query = "";
    private boolean grid;

    /** What the last load produced, before the search box narrows it down -
     *  kept so that typing does not mean asking the folder again. */
    private List<Entry> loaded = new ArrayList<>();

    /**
     * The row the pane is about, or null when nothing is selected. A file is
     * selected rather than opened by a tap - see {@link #isContainer} - and
     * the pane's own Play button is what actually starts it; only a folder or
     * a zip still acts immediately, by being walked into.
     */
    private Entry selected;

    /**
     * Guards a slow load against answering after a newer one was asked for.
     * Walking into a folder and straight back out again is quicker than a
     * content provider on a slow SD card, and without this the first
     * request's answer could land after the second's and show the wrong
     * folder.
     */
    private int loadToken;

    private RecyclerView recycler;
    private EntryAdapter adapter;
    private GamepadCursor padCursor;
    private TextView pathLabel;
    private ImageButton upButton;
    private TextView emptyLabel;
    private View noFolderView;
    private ProgressBar spinner;
    private EditText searchField;
    private ImageButton sortButton;
    private ImageButton viewToggle;
    private final List<View> tabViews = new ArrayList<>();

    // The pane: always present - see docs/LIBRARY.md and the second pull
    // request it describes - and either showing the selected row or saying
    // there is none.
    private View paneEmpty;
    private View paneDetails;
    private TextView paneTitle;
    private TextView paneSubtitle;
    private Button panePlayButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        preferences = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);

        // The one decision this screen makes before drawing anything: whether
        // it should be here at all. Off, or with no folder granted, and the
        // app opens on the machine exactly as it always has - see
        // startsInLibrary and docs/LIBRARY.md, "A content folder is the gate".
        if (!SettingsActivity.startsInLibrary(this, preferences)) {
            startActivity(new Intent(this, EmulatorActivity.class));
            finish();
            return;
        }

        // The manifest label is resolved in the phone's language rather than
        // this screen's, so the title is set here; see Language.
        setTitle(R.string.library_title);

        sort = preferences.getString(KEY_SORT, SORT_NAME);
        sortDescending = preferences.getBoolean(KEY_SORT_DESC, false);
        grid = VIEW_GRID.equals(preferences.getString(KEY_VIEW, VIEW_LIST));

        setContentView(buildPage());

        // Nothing of ours under the status bar or the camera; see SafeArea.
        SafeArea.fit(findViewById(android.R.id.content));

        padNav = buildPadNav();
        padCursor = new GamepadCursor(padNav);

        pushRoot();
        show(Tab.BROWSE);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Coming back from a game, from Settings, or from granting the
        // content folder: whichever tab is showing may be stale, and asking
        // again is cheap next to what it would cost to show it wrong.
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();

        // A repeat left running - the stick held over as the screen goes to
        // the background - would otherwise go on moving a cursor nobody can
        // see, and be moving it still when the screen comes back.
        padCursor.release();
    }

    /** Browse's own way up, and the app's own way out from its root. */
    @Override
    public void onBackPressed() {
        if (popStack()) return;
        super.onBackPressed();
    }

    // --- gamepad ---------------------------------------------------------

    /**
     * The five things a pad can do here, wired to exactly what touch already
     * does: {@link #select} is the same one {@link EntryAdapter.Callbacks}
     * calls, {@link #popStack} is the same one Back and the chevron share, and
     * {@link #toggleFavorite} is the same one a long press performs. Built
     * once, by {@link #buildPadNav}, and handed to {@link GamepadCursor},
     * which is the one place that turns a stick, a hat, a D-pad and four
     * buttons into calls to these five.
     */
    private GamepadCursor.Nav padNav;

    /** Built in {@link #onCreate}, like every collaborator here - see
     *  CLAUDE.md, "Build collaborators in onCreate, never as field
     *  initialisers": a field initialiser runs before onCreate does, and this
     *  one closes over instance methods that are not safe to call that early. */
    private GamepadCursor.Nav buildPadNav() {
        return new GamepadCursor.Nav() {
            @Override
            public void move(int dx, int dy) {
                moveCursor(dx, dy);
            }

            @Override
            public void activate() {
                if (selected == null) return;
                if (isContainer(selected)) enter(selected); else openGame(selected);
            }

            @Override
            public void back() {
                popStack();
            }

            @Override
            public void toggleFavorite() {
                // Qualified: this method's own name shadows the outer one
                // that takes the row to act on.
                if (selected != null) LibraryActivity.this.toggleFavorite(selected);
            }

            @Override
            public void tab(int delta) {
                Tab[] values = Tab.values();
                int index = Math.max(0, Math.min(values.length - 1, tab.ordinal() + delta));
                show(values[index]);
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (padCursor.key(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (padCursor.key(event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    /** A controller's stick and hat, which arrive as axes rather than as
     *  keys - see {@link GamepadCursor}. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (padCursor.motion(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    /**
     * Moves the cursor by whole cells and selects whatever it lands on -
     * {@link #select}, the same one a tap on a file uses, so the pane fills
     * exactly as it does by touch. Clamped at both ends rather than wrapping:
     * a list of thousands wrapping from the last row to the first on one more
     * push down would look like a jump to a random row, not like reaching an
     * end.
     *
     * Nothing is selected yet on a fresh tab or a folder just walked into -
     * {@link #show} and {@link #enter} both clear it - so the first push in
     * any direction lands on the first row rather than nowhere.
     *
     * @param dx within a row: {@code -1} or {@code 1}, never both nonzero
     *           with {@code dy} at once - see {@link GamepadCursor.Nav#move}.
     * @param dy by a row, scaled by however many columns the grid has;
     *           {@code 1} in list mode, where a row is one entry.
     */
    private void moveCursor(int dx, int dy) {
        int count = adapter.getItemCount();
        if (count == 0) return;

        int span = grid ? gridSpanCount() : 1;
        int delta = dx != 0 ? dx : dy * span;

        int index = selected == null ? -1 : indexOfSelected();
        int next = index < 0 ? 0 : Math.max(0, Math.min(count - 1, index + delta));

        select(adapter.entryAt(next));
        recycler.scrollToPosition(next);
    }

    /** Where the selected row sits in what is currently shown, or -1 when
     *  nothing is selected or it has scrolled out of what the search box or a
     *  reload still shows - {@link #applyFilterSort} already clears {@link
     *  #selected} in that case, so this is mostly belt and braces. */
    private int indexOfSelected() {
        if (selected == null) return -1;

        String key = selected.key();
        for (int i = 0; i < adapter.getItemCount(); i++) {
            if (adapter.entryAt(i).key().equals(key)) return i;
        }
        return -1;
    }

    /**
     * Goes up one level of Browse's stack, exactly as Back does - shared with
     * the chevron beside the breadcrumb, since a touchscreen has no Back key
     * of its own to reach for and popping the stack was otherwise invisible.
     *
     * @return whether there was a level to pop. False at the root, or on
     *         either of the other two tabs, which have no stack to speak of.
     */
    private boolean popStack() {
        if (tab != Tab.BROWSE || stack.size() <= 1) return false;

        stack.remove(stack.size() - 1);
        clearSelection();
        clearSearch();
        dismissKeyboard();

        // The level now on top is the one just left from - see enter() and
        // captureScroll - so this load's own applyFilterSort should put it
        // back where it was rather than at the top, once its rows are in.
        restoringPosition = true;
        load();
        return true;
    }

    // --- building the page ---------------------------------------------------

    /**
     * The main column beside the pane in landscape, above it in portrait -
     * see docs/LIBRARY.md's second pull request, "always there" being the
     * whole point of it. Weighted 2:1 against {@link #buildPane}, which is
     * roughly the third of the window it asks for.
     *
     * Not handled by {@code onConfigurationChanged}: this activity declares
     * no {@code configChanges} of its own, so a rotation recreates it and
     * this is read fresh every time, the same as every screen but the
     * emulator's own.
     */
    private View buildPage() {
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        outer.setBackgroundColor(BACKING);

        View divider = new View(this);
        divider.setBackgroundColor(DIVIDER);

        outer.addView(buildMainColumn(), new LinearLayout.LayoutParams(
                landscape ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                landscape ? LinearLayout.LayoutParams.MATCH_PARENT : 0, 2f));

        outer.addView(divider, landscape
                ? new LinearLayout.LayoutParams(pixels(1), LinearLayout.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, pixels(1)));

        outer.addView(buildPane(), new LinearLayout.LayoutParams(
                landscape ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                landscape ? LinearLayout.LayoutParams.MATCH_PARENT : 0, 1f));

        return outer;
    }

    private View buildMainColumn() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(buildTabStrip(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout pathRow = new LinearLayout(this);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);

        // Only when there is somewhere to go: hidden at the root, and on
        // either of the other two tabs, which have no stack of their own.
        // Back already pops the stack; this is the same action put where a
        // touchscreen can reach it, since nothing else on screen offered it.
        upButton = toolbarButton(R.drawable.ic_chevron_left, getString(R.string.library_up));
        upButton.setOnClickListener(v -> popStack());
        pathRow.addView(upButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        pathLabel = new TextView(this);
        pathLabel.setTextColor(MUTED);
        pathLabel.setTextSize(12);
        pathLabel.setSingleLine();
        // The start is cut, not the end: the folder you are in is the part
        // of the path worth keeping on screen, and it is the last segment.
        pathLabel.setEllipsize(TextUtils.TruncateAt.START);
        pathLabel.setPadding(pixels(4), pixels(6), pixels(16), pixels(2));
        // The path itself goes up a level too, so the whole row is one target
        // and not only the chevron; harmless at the root, where there is
        // nowhere to go and this simply does nothing.
        pathLabel.setOnClickListener(v -> popStack());
        pathRow.addView(pathLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(pathRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(buildToolbar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout content = new FrameLayout(this);

        recycler = new RecyclerView(this);
        recycler.setLayoutManager(grid ? new GridLayoutManager(this, gridSpanCount())
                                       : new LinearLayoutManager(this));

        adapter = new EntryAdapter(this, new EntryAdapter.Callbacks() {
            @Override
            public void onOpen(Entry entry) {
                if (isContainer(entry)) enter(entry); else select(entry);
            }

            @Override
            public void onLongPress(Entry entry) {
                toggleFavorite(entry);
            }
        });
        adapter.setGrid(grid);
        recycler.setAdapter(adapter);

        content.addView(recycler, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        emptyLabel = new TextView(this);
        emptyLabel.setTextColor(MUTED);
        emptyLabel.setTextSize(15);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyLabel.setPadding(pixels(32), pixels(32), pixels(32), pixels(32));
        emptyLabel.setVisibility(View.GONE);
        content.addView(emptyLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        noFolderView = buildNoFolderView();
        noFolderView.setVisibility(View.GONE);
        content.addView(noFolderView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        content.addView(spinner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    /**
     * Hand built, like the settings tabs and the ☰ sheet: three icons is not
     * worth a ViewPager2 and a TabLayout dependency.
     *
     * The Machine button lives here rather than in the toolbar: it is
     * navigation, the same level as Browse, Favourites and Recent, not
     * something done to whichever list is showing - so it belongs beside the
     * tabs, set apart from them by a divider and by never taking their
     * selected colour, since it leaves the library rather than switching
     * within it.
     */
    private View buildTabStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);

        for (Tab candidate : Tab.values()) {
            View view = buildTab(candidate);
            tabViews.add(view);
            strip.addView(view, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        View divider = new View(this);
        divider.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                pixels(1), pixels(32));
        dividerParams.leftMargin = dividerParams.rightMargin = pixels(4);
        strip.addView(divider, dividerParams);

        ImageButton machineButton = toolbarButton(R.drawable.ic_chip,
                getString(R.string.library_machine));
        machineButton.setOnClickListener(v -> openMachine());
        strip.addView(machineButton);

        return strip;
    }

    private View buildTab(Tab which) {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(10 * density);

        LinearLayout holder = new LinearLayout(this);
        ImageView icon = new ImageView(this);
        TextView label = new TextView(this);

        icon.setImageResource(TAB_ICONS[which.ordinal()]);
        icon.setLayoutParams(new LinearLayout.LayoutParams(
                Math.round(24 * density), Math.round(24 * density)));

        label.setText(TAB_LABELS[which.ordinal()]);
        label.setTextSize(12);
        label.setSingleLine();
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, Math.round(4 * density), 0, 0);

        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);
        holder.setPadding(0, pad, 0, pad);
        holder.setClickable(true);
        holder.setFocusable(true);
        holder.setBackgroundResource(android.R.drawable.list_selector_background);
        holder.setContentDescription(getString(TAB_LABELS[which.ordinal()]));
        holder.setOnClickListener(v -> show(which));

        holder.addView(icon);
        holder.addView(label);

        return holder;
    }

    private void paintTabs() {
        for (Tab candidate : Tab.values()) {
            LinearLayout holder = (LinearLayout) tabViews.get(candidate.ordinal());
            int colour = candidate == tab ? ACTIVE : MUTED;

            ((ImageView) holder.getChildAt(0)).setColorFilter(colour);
            ((TextView) holder.getChildAt(1)).setTextColor(colour);
        }
    }

    /**
     * Search, sort and the list/grid switch, in one row: all three apply to
     * whichever tab is showing, and none of them is worth a row of its own.
     * The way back to the machine used to live here too, until it turned out
     * to belong beside the tabs instead - see {@link #buildTabStrip}, since
     * it is navigation and these three are not.
     */
    private View buildToolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pixels(12), pixels(2), pixels(4), pixels(2));

        searchField = new EditText(this);
        searchField.setHint(R.string.library_search);
        searchField.setSingleLine();
        searchField.setTextColor(TEXT);
        searchField.setHintTextColor(MUTED);
        searchField.setBackground(null);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                applyFilterSort();
            }
        });
        row.addView(searchField, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        sortButton = toolbarButton(R.drawable.ic_sort, getString(R.string.library_sort));
        sortButton.setOnClickListener(this::showSortMenu);
        row.addView(sortButton);
        updateSortButton();

        // Named for what it would do rather than what it is, like the bar's
        // own fullscreen button: it shows the shape you would switch to, not
        // the one you are looking at.
        viewToggle = toolbarButton(R.drawable.ic_grid,
                getString(R.string.library_view_grid));
        viewToggle.setOnClickListener(v -> toggleView());
        row.addView(viewToggle);

        updateViewToggle();

        return row;
    }

    /**
     * Goes to the machine without loading anything.
     *
     * {@link EmulatorActivity} is {@code singleInstance}, so starting it by
     * component name - no action, no data - brings its existing task forward
     * with the game exactly as it was left, rather than an {@code ACTION_VIEW}
     * that would load whatever this row happened to be pointed at. If it has
     * never been started this session, this simply starts it, which is a
     * reasonable thing for the button to do.
     *
     * Never finishes this activity: it is the launcher and the task's root,
     * and finishing it would leave the machine as the only thing left in the
     * app. No pause of its own either - {@code EmulatorActivity.onPause}
     * already stops the machine the moment this activity takes the window,
     * and {@code onResume} there starts it again when this one loses it;
     * pausing it again here would leave it stopped when somebody returns.
     */
    private void openMachine() {
        Intent intent = new Intent(this, EmulatorActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                       | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private ImageButton toolbarButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        float density = getResources().getDisplayMetrics().density;

        button.setImageResource(icon);
        button.setColorFilter(TEXT);
        button.setContentDescription(description);
        button.setBackgroundResource(android.R.drawable.list_selector_background);
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                Math.round(44 * density), Math.round(44 * density)));

        return button;
    }

    private View buildNoFolderView() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(pixels(32), pixels(32), pixels(32), pixels(32));

        TextView text = new TextView(this);
        text.setText(R.string.library_no_folder);
        text.setTextColor(MUTED);
        text.setTextSize(15);
        text.setGravity(Gravity.CENTER);
        column.addView(text);

        Button choose = new Button(this);
        choose.setText(R.string.library_choose_folder);
        choose.setOnClickListener(v -> chooseFolder());

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = pixels(16);
        column.addView(choose, params);

        return column;
    }

    // --- the pane -------------------------------------------------------------

    /**
     * Metadata and artwork for whatever is selected - always here, whether or
     * not anything is: a side pane in landscape, a panel across the bottom in
     * portrait, both a third of the window against the other two thirds
     * {@link #buildMainColumn} takes. See docs/LIBRARY.md's second pull
     * request, "reserved... whether or not anything is selected" being the
     * whole point of shipping the container before anything fills it.
     *
     * PR 1 knows a name, a size and a date; the cover, the developer and year,
     * and the description are ES-DE's own {@code gamelist.xml} and
     * {@code downloaded_media}, scraped in the pull request after this one.
     * The shapes are built now so that pull request only has to fill them:
     * a picture area of a sensible aspect for a cover, a title, a subtitle
     * line - developer and year there, size and date here, since that is
     * what is known now - and room below for a description that can run long.
     */
    private View buildPane() {
        FrameLayout frame = new FrameLayout(this);

        TextView empty = new TextView(this);
        empty.setText(R.string.library_nothing_selected);
        empty.setTextColor(MUTED);
        empty.setTextSize(14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(pixels(24), pixels(24), pixels(24), pixels(24));
        paneEmpty = empty;
        frame.addView(paneEmpty, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(pixels(16), pixels(16), pixels(16), pixels(16));

        // The cover: empty for now, and a fixed height rather than a measured
        // aspect ratio, since there is nothing yet whose aspect it would need
        // to keep. A sensible size for a box's worth of art all the same, so
        // the next pull request drops a picture in rather than a layout.
        View cover = new View(this);
        cover.setBackgroundColor(0x14ffffff);
        details.addView(cover, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, pixels(160)));

        paneTitle = new TextView(this);
        paneTitle.setTextColor(TEXT);
        paneTitle.setTextSize(16);
        paneTitle.setMaxLines(3);
        paneTitle.setEllipsize(TextUtils.TruncateAt.END);
        paneTitle.setPadding(0, pixels(12), 0, 0);
        details.addView(paneTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // The developer and the year, once the next pull request knows them;
        // the size and the date, which is what this one does.
        paneSubtitle = new TextView(this);
        paneSubtitle.setTextColor(MUTED);
        paneSubtitle.setTextSize(13);
        paneSubtitle.setPadding(0, pixels(4), 0, 0);
        details.addView(paneSubtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Room for a description that can run long - Dizzy's runs to three
        // paragraphs - pushing the Play button to the foot of the pane rather
        // than leaving it crowding the subtitle. Empty until the next pull
        // request has something to put in it.
        View descriptionSpace = new View(this);
        details.addView(descriptionSpace, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        panePlayButton = new Button(this);
        panePlayButton.setText(R.string.library_play);
        panePlayButton.setOnClickListener(v -> {
            if (selected != null) openGame(selected);
        });
        details.addView(panePlayButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        paneDetails = details;
        frame.addView(paneDetails, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        updatePane();

        return frame;
    }

    /** Shows what is known about the selection, or says there is none. */
    private void updatePane() {
        boolean have = selected != null;

        paneEmpty.setVisibility(have ? View.GONE : View.VISIBLE);
        paneDetails.setVisibility(have ? View.VISIBLE : View.GONE);

        if (!have) return;

        paneTitle.setText(selected.name);
        paneSubtitle.setText(EntryAdapter.detail(this, selected));
    }

    // --- tabs and Browse's stack ---------------------------------------------

    private void show(Tab which) {
        tab = which;
        paintTabs();

        boolean browsing = tab == Tab.BROWSE;
        pathLabel.setVisibility(browsing ? View.VISIBLE : View.GONE);
        upButton.setVisibility(browsing && stack.size() > 1 ? View.VISIBLE : View.GONE);
        clearSearch();
        clearSelection();
        dismissKeyboard();

        load();
    }

    /**
     * Rebuilds Browse's stack down to a single root level, from whatever
     * content folder is currently granted - called at startup and again
     * whenever the folder is (re)chosen, from {@link #onActivityResult}.
     *
     * Nothing here does any I/O: {@link Listing#root} only builds a Uri, so
     * this is safe on the calling thread, unlike everything {@link #load}
     * goes on to ask of it.
     */
    private void pushRoot() {
        stack.clear();

        String stored = preferences.getString(Storage.KEY_CONTENT_TREE, null);
        if (stored == null) return;

        try {
            stack.add(new Level(Listing.root(Uri.parse(stored)), false, null));
        } catch (Exception e) {
            Log.w(TAG, "cannot use content folder " + stored, e);
        }
    }

    // --- loading --------------------------------------------------------------

    /**
     * Asks for whatever the current tab shows, on a thread of its own -
     * {@link Listing} is a round trip to another app's content provider or a
     * stream read from a zip, and none of it may run here.
     */
    private void load() {
        int token = ++loadToken;
        setLoading(true);

        if (tab == Tab.FAVORITES) {
            new Thread(() -> {
                List<Entry> result = Favorites.all(this);
                runOnUiThread(() -> finishLoad(token, result, null));
            }).start();
            return;
        }

        if (tab == Tab.RECENTS) {
            new Thread(() -> {
                List<Entry> result = recentsAsEntries();
                runOnUiThread(() -> finishLoad(token, result, null));
            }).start();
            return;
        }

        if (stack.isEmpty()) {
            // No content folder granted at all - pushRoot left it empty.
            finishLoad(token, null, null);
            return;
        }

        Level level = stack.get(stack.size() - 1);
        pathLabel.setText(pathText());
        upButton.setVisibility(stack.size() > 1 ? View.VISIBLE : View.GONE);

        new Thread(() -> {
            List<Entry> result = null;
            IOException failure = null;

            try {
                result = level.archive
                        ? Listing.archive(getContentResolver(), level.uri)
                        : Listing.folder(getContentResolver(), level.uri);
            } catch (IOException e) {
                failure = e;
            }

            List<Entry> finalResult = result;
            IOException finalFailure = failure;
            runOnUiThread(() -> finishLoad(token, finalResult, finalFailure));
        }).start();
    }

    private List<Entry> recentsAsEntries() {
        List<Entry> result = new ArrayList<>();

        for (Recents.Item item : Recents.all(preferences)) {
            // item.inside carries which entry of a zip this was, exactly as
            // Entry.inside does - see Recents.Item and docs/LIBRARY.md, "How
            // a game is opened". Null for a plain file, which is what it has
            // always been for a Recents.Item written before that field
            // existed.
            result.add(new Entry(Entry.Kind.FILE, item.name, item.uri, item.inside, -1, 0));
        }

        return result;
    }

    /**
     * @param result  what was read, or null on a failure worth explaining
     *                rather than a folder that happens to be empty.
     * @param failure why, when {@code result} is null and it is not simply
     *                that there is no content folder at all.
     */
    private void finishLoad(int token, List<Entry> result, IOException failure) {
        // Superseded by a newer request - walking into a folder and straight
        // back out again, most often - so this answer is not the one wanted
        // any more and showing it would be showing the wrong folder.
        if (token != loadToken) return;

        setLoading(false);

        if (result == null) {
            if (failure != null) {
                Log.w(TAG, "cannot list the current folder", failure);
            }

            // Nothing to browse: no folder chosen, or the root itself could
            // not be read - a lost grant, most likely, and re-choosing is the
            // recovery for both. A failure deeper in the stack is treated as
            // an empty folder instead, since Back is already the way out of it.
            if (tab == Tab.BROWSE && stack.size() <= 1) {
                showNoFolder();
                return;
            }

            result = new ArrayList<>();
        }

        loaded = result;
        applyFilterSort();
    }

    /** The search box and the sort order, applied to what was last loaded -
     *  neither one is worth asking the folder again for. */
    private void applyFilterSort() {
        noFolderView.setVisibility(View.GONE);

        List<Entry> shown = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);

        for (Entry entry : loaded) {
            if (needle.isEmpty() || entry.name.toLowerCase(Locale.ROOT).contains(needle)) {
                shown.add(entry);
            }
        }

        sortEntries(shown);
        adapter.setEntries(shown);

        // Right here, and nowhere later: the adapter has this load's rows
        // now, which is what scrollToPositionWithOffset needs before it can
        // find anything to scroll to, and calling it any earlier - before
        // setEntries, or on an adapter that has not bound them yet - would
        // scroll a list that still has none. See popStack and captureScroll.
        if (restoringPosition) {
            restoringPosition = false;
            restorePosition(shown);
        }

        // The selection survives a re-sort or the search box narrowing the
        // list, as long as the row it names is still among what is shown -
        // typing a letter that hides it is "the folder changing" in every
        // way that matters to the pane, so it is cleared exactly as
        // navigating away would clear it. By key rather than by object,
        // since a reload - onResume's, or a favourite just added or removed -
        // hands back a freshly parsed Entry for the same file, never the same
        // instance; the fresh one replaces the stale one so the pane's own
        // numbers are not left reporting what a previous read saw.
        if (selected != null) {
            Entry match = findByKey(shown, selected.key());

            if (match == null) {
                clearSelection();
            } else if (match != selected) {
                selected = match;
                updatePane();
            }
        }

        boolean empty = shown.isEmpty();
        recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyLabel.setVisibility(empty ? View.VISIBLE : View.GONE);

        if (empty) {
            // A search that matched nothing says so, whatever the tab -
            // it is why the list is empty, and library_empty and its two
            // siblings are all claims about the folder itself having
            // nothing in it, which would be false here.
            emptyLabel.setText(!query.isEmpty() ? R.string.library_empty_search
                              : tab == Tab.FAVORITES ? R.string.library_empty_favorites
                              : tab == Tab.RECENTS ? R.string.library_empty_recents
                              : R.string.library_empty);
        }
    }

    /**
     * Folders stay first and alphabetical whatever the sort says - the same
     * rule {@link Listing#folder} itself sorts by - since they are what Browse
     * is walked through rather than a game to weigh by size or date. A no-op
     * split for Favourites and Recents, which are never folders.
     */
    private void sortEntries(List<Entry> list) {
        List<Entry> folders = new ArrayList<>();
        List<Entry> rest = new ArrayList<>();

        for (Entry entry : list) {
            (entry.kind == Entry.Kind.FOLDER ? folders : rest).add(entry);
        }

        folders.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        rest.sort(comparatorFor(sort, sortDescending));

        list.clear();
        list.addAll(folders);
        list.addAll(rest);
    }

    /**
     * Ascending unless {@code descending} says otherwise - name A-Z, oldest
     * first, smallest first - which is the one direction every field had
     * before there was a choice about it for name, and the plainer of the two
     * to default to for date and size as well.
     */
    private Comparator<Entry> comparatorFor(String key, boolean descending) {
        Comparator<Entry> ascending;

        switch (key) {
            case SORT_DATE: ascending = Comparator.comparingLong(e -> e.modified); break;
            case SORT_SIZE: ascending = Comparator.comparingLong(e -> e.size); break;
            default: ascending = (a, b) -> a.name.compareToIgnoreCase(b.name);
        }

        return descending ? ascending.reversed() : ascending;
    }

    /** The row named by {@code key} in {@code list}, or null if it is not
     *  there - used to carry the selection across a reload by identity of
     *  the game rather than of the object. */
    private static Entry findByKey(List<Entry> list, String key) {
        for (Entry entry : list) {
            if (entry.key().equals(key)) return entry;
        }
        return null;
    }

    private String pathText() {
        StringBuilder path = new StringBuilder("/");

        for (int i = 1; i < stack.size(); i++) {
            if (i > 1) path.append('/');
            path.append(stack.get(i).name);
        }

        return path.toString();
    }

    /**
     * Shows the spinner and hides everything else while a load is in flight;
     * clearing it leaves the choice of what to show next to whichever of
     * {@link #showNoFolder} or {@link #applyFilterSort} runs afterwards.
     */
    private void setLoading(boolean loading) {
        spinner.setVisibility(loading ? View.VISIBLE : View.GONE);

        if (loading) {
            recycler.setVisibility(View.GONE);
            emptyLabel.setVisibility(View.GONE);
            noFolderView.setVisibility(View.GONE);
        }
    }

    // --- opening things --------------------------------------------------------

    /**
     * Whether a row is a folder or a zip to walk into, rather than a game to
     * select.
     *
     * Decided by {@code inside} rather than by {@link Entry#kind} alone, and
     * not by which tab this is either: {@link Favorites#all} hands back
     * {@code Kind.ARCHIVE} for a favourite that merely <em>lives</em> inside a
     * zip - it has nothing else to call an entry it cannot enter - but such an
     * entry always carries its path within the archive, and a real container
     * never does. Checking {@code inside} rather than the tab makes this true
     * everywhere at once, Browse included, rather than everywhere the tab
     * happens to agree with it.
     */
    private boolean isContainer(Entry entry) {
        return entry.inside == null
                && (entry.kind == Entry.Kind.FOLDER || entry.kind == Entry.Kind.ARCHIVE);
    }

    /**
     * Walks into a folder or a zip immediately - navigation, not a selection,
     * and so not something a Play button gates.
     *
     * The search is scoped to one listing and must not outlive it: without
     * clearing it, the text that found the zip goes on filtering what is
     * inside it, and a zip full of games reads as empty - the very thing this
     * screen exists to avoid saying about a folder that is not.
     *
     * The level being left - still the top of {@link #stack} at this point -
     * remembers {@code entry} and where the list was scrolled to, so {@link
     * #popStack} can put it back exactly as it was rather than wherever a
     * folder of hundreds happens to open.
     */
    private void enter(Entry entry) {
        captureScroll(stack.get(stack.size() - 1), entry.key());

        stack.add(new Level(entry.uri, entry.kind == Entry.Kind.ARCHIVE, entry.name));
        clearSelection();
        clearSearch();
        dismissKeyboard();
        load();
    }

    /**
     * Remembers, on {@code level}, the row named by {@code returnKey} and the
     * list's current scroll - the first visible row and its own pixel offset,
     * which is what {@link LinearLayoutManager#scrollToPositionWithOffset}
     * takes back. Read from the layout manager rather than tracked as the
     * user scrolls, since nothing needs it until the moment a level is left;
     * {@code GridLayoutManager} answers the same calls {@code
     * LinearLayoutManager} does, span count already folded in, which is why
     * an index and an offset are enough - a pixel scroll position on its own
     * would not be, list and grid disagreeing on how tall a row is.
     */
    private void captureScroll(Level level, String returnKey) {
        level.returnKey = returnKey;
        level.scrollPosition = RecyclerView.NO_POSITION;
        level.scrollOffset = 0;

        RecyclerView.LayoutManager manager = recycler.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) return;

        LinearLayoutManager linear = (LinearLayoutManager) manager;
        int position = linear.findFirstVisibleItemPosition();
        if (position == RecyclerView.NO_POSITION) return;

        View firstView = linear.findViewByPosition(position);
        level.scrollPosition = position;
        level.scrollOffset = firstView != null ? firstView.getTop() : 0;
    }

    /**
     * The other half of {@link #captureScroll}: selects the row that was
     * walked into last time this level was left, so the pane shows the very
     * zip or folder just come out of and a pad's cursor carries on from
     * there rather than from nothing, and puts the list back where it was
     * scrolled to - both only if the level is still showing what it did, so
     * a row that moved or vanished since is not selected by mistake.
     *
     * @param shown this load's rows, already filtered and sorted - the same
     *              list {@link #applyFilterSort} just handed the adapter.
     */
    private void restorePosition(List<Entry> shown) {
        if (stack.isEmpty()) return;
        Level level = stack.get(stack.size() - 1);

        Entry match = level.returnKey != null ? findByKey(shown, level.returnKey) : null;
        if (match != null) select(match);

        if (level.scrollPosition == RecyclerView.NO_POSITION) return;

        // The row's own position in what is showing now, rather than the raw
        // index captureScroll saw - the search box is already empty again by
        // this point (popStack clears it same as enter does), so a capture
        // taken while a search narrowed the list would otherwise scroll to
        // the wrong row of the fuller one now showing. Falls back to the raw
        // index only if the row itself cannot be found any more - renamed or
        // removed while this level was not on top.
        int position = match != null ? shown.indexOf(match) : level.scrollPosition;

        RecyclerView.LayoutManager manager = recycler.getLayoutManager();
        if (manager instanceof LinearLayoutManager) {
            ((LinearLayoutManager) manager).scrollToPositionWithOffset(position, level.scrollOffset);
        }
    }

    /**
     * Selects a game rather than loading it - the pane fills with what is
     * known and its own Play button is what actually starts it. One extra tap
     * over loading on the first touch, and exactly right for a d-pad, where
     * moving focus is what this already is and one button should be what
     * launches.
     *
     * Not the search: selecting a row does not change which folder is shown,
     * so unlike {@link #enter} this leaves it exactly as it was - but the
     * keyboard is in the way of the row just tapped either way, so it goes.
     */
    private void select(Entry entry) {
        selected = entry;
        adapter.setSelectedKey(entry.key());
        updatePane();
        dismissKeyboard();
    }

    /** Empties the search box, and with it whatever it was filtering to -
     *  see {@link #enter} and {@link #popStack}. */
    private void clearSearch() {
        searchField.setText("");
    }

    /**
     * Takes the keyboard away and the focus with it.
     *
     * Typing leaves the search box focused, and the keyboard it then asked
     * for does not give the focus back by itself - so it sat over the list
     * and the toolbar, the Up chevron included, until something explicitly
     * asked for it to go. Called on every tap that acts on a row: selecting,
     * walking into a folder or a zip, or going up.
     */
    private void dismissKeyboard() {
        searchField.clearFocus();

        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        android.os.IBinder token = searchField.getWindowToken();

        // Null before the view is attached to a window - true of every call
        // this makes from onCreate, since show(Tab.BROWSE) runs there before
        // the first frame - and hideSoftInputFromWindow has nothing to do
        // with no window to find.
        if (imm != null && token != null) imm.hideSoftInputFromWindow(token, 0);
    }

    private void clearSelection() {
        if (selected == null) return;

        selected = null;
        adapter.setSelectedKey(null);
        updatePane();
    }

    /**
     * Hands a game to the machine: {@code ACTION_VIEW} with the document as
     * the data, exactly as a file manager's hand-over already works - see
     * {@code EmulatorActivity.handleViewIntent}. An entry from inside a zip
     * carries the archive's own uri as the data and the path within it as an
     * extra, never a {@code file://} uri of its own: that throws even to our
     * own activity, which is the whole reason for the extra rather than the
     * extracted path. See docs/LIBRARY.md, "How a game is opened".
     *
     * Called only by the pane's own Play button now - a tap on the row
     * selects rather than loads; see {@link #select}.
     */
    private void openGame(Entry entry) {
        Intent intent = new Intent(Intent.ACTION_VIEW, entry.uri, this, EmulatorActivity.class);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (entry.inside != null) {
            intent.putExtra(EmulatorActivity.EXTRA_ZIP_ENTRY, entry.inside);
        }

        startActivity(intent);
    }

    /**
     * The whole of how a favourite is made or unmade: hold a row down, in any
     * tab - selecting a row is a different gesture and does not touch this.
     * {@link Favorites} does its own small file read and write, so this goes
     * through a thread like every other listing here, however little there
     * usually is in it.
     */
    private void toggleFavorite(Entry entry) {
        new Thread(() -> {
            String key = entry.key();
            boolean has = Favorites.has(this, key);

            if (has) Favorites.remove(this, key);
            else Favorites.add(this, entry);

            runOnUiThread(() -> {
                Toast.makeText(this, has ? R.string.library_favorite_remove
                                        : R.string.library_favorite_add,
                        Toast.LENGTH_SHORT).show();

                // The row just left or joined that list; Browse and Recents
                // show a favourite exactly as they always did, since neither
                // reads Favorites at all.
                if (tab == Tab.FAVORITES) load();
            });
        }).start();
    }

    // --- choosing a content folder ---------------------------------------------

    /**
     * Opens the folder picker and persists the grant exactly as Settings does
     * for the same preference - see {@code SettingsActivity.pickContentFolder}
     * and {@code onActivityResult} there, which this mirrors.
     */
    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

        try {
            startActivityForResult(intent, REQUEST_CONTENT_TREE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);

        if (request != REQUEST_CONTENT_TREE || result != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }

        Uri tree = data.getData();

        // Without this the grant dies with this activity, exactly as
        // SettingsActivity's own picker takes care to avoid.
        getContentResolver().takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);

        preferences.edit().putString(Storage.KEY_CONTENT_TREE, tree.toString()).apply();

        pushRoot();
        clearSelection();
        clearSearch();
        if (tab == Tab.BROWSE) load();
    }

    // --- list and grid, sort ----------------------------------------------------

    private void showNoFolder() {
        recycler.setVisibility(View.GONE);
        emptyLabel.setVisibility(View.GONE);
        noFolderView.setVisibility(View.VISIBLE);
        pathLabel.setText("");
        upButton.setVisibility(View.GONE);
    }

    private void updateViewToggle() {
        viewToggle.setImageResource(grid ? R.drawable.ic_list : R.drawable.ic_grid);
        viewToggle.setContentDescription(getString(grid ? R.string.library_view_list
                                                        : R.string.library_view_grid));
    }

    private void toggleView() {
        grid = !grid;
        preferences.edit().putString(KEY_VIEW, grid ? VIEW_GRID : VIEW_LIST).apply();

        updateViewToggle();
        recycler.setLayoutManager(grid ? new GridLayoutManager(this, gridSpanCount())
                                       : new LinearLayoutManager(this));
        adapter.setGrid(grid);
    }

    /** As many columns as fit at roughly 100dp each, never fewer than two. */
    private int gridSpanCount() {
        float density = getResources().getDisplayMetrics().density;
        int widthDp = Math.round(getResources().getDisplayMetrics().widthPixels / density);

        return Math.max(2, widthDp / 100);
    }

    /**
     * Picking the field already showing reverses it; picking a different one
     * switches to it and keeps whichever direction was showing - the one
     * thing already in view before the menu opened, and so the one thing
     * neither choice should reset without being asked to.
     */
    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, R.string.library_sort_name);
        menu.getMenu().add(0, 1, 1, R.string.library_sort_date);
        menu.getMenu().add(0, 2, 2, R.string.library_sort_size);

        menu.setOnMenuItemClickListener(item -> {
            String chosen = item.getItemId() == 1 ? SORT_DATE
                          : item.getItemId() == 2 ? SORT_SIZE : SORT_NAME;

            if (chosen.equals(sort)) {
                sortDescending = !sortDescending;
            } else {
                sort = chosen;
            }

            preferences.edit()
                    .putString(KEY_SORT, sort)
                    .putBoolean(KEY_SORT_DESC, sortDescending)
                    .apply();

            updateSortButton();
            applyFilterSort();
            return true;
        });

        menu.show();
    }

    /**
     * Turns the sort icon over for descending, so which way the list runs is
     * something to see rather than something to remember having chosen - the
     * bars already read as a wedge narrowing one way, and turning it upside
     * down says the opposite without a second drawable to keep in step with
     * the first.
     */
    private void updateSortButton() {
        sortButton.setRotation(sortDescending ? 180f : 0f);
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
