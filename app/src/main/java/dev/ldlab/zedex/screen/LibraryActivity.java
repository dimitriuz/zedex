package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.Favorites;
import dev.ldlab.zedex.library.Listing;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.library.ui.EntryAdapter;
import dev.ldlab.zedex.library.ui.Gallery;
import dev.ldlab.zedex.library.ui.GamepadCursor;
import dev.ldlab.zedex.library.ui.Manuals;
import dev.ldlab.zedex.library.ui.OptionsDialog;
import dev.ldlab.zedex.library.ui.Ripple;
import dev.ldlab.zedex.storage.Recents;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.view.SafeArea;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
 * {@link SettingsActivity#startsInLibrary} is asked before anything else, but
 * only when this is the launcher path: with the switch off, or with no content
 * folder granted, this activity's whole job is to hand straight over to
 * {@link EmulatorActivity} and get out of the way, so the app opens on the
 * machine exactly as it always has. {@link #EXTRA_FROM_MENU} is what tells the
 * two paths apart - see its own comment and {@link #onCreate}, which is the
 * only place either question is asked.
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

    /**
     * Set only by {@code EmulatorActivity.openLibrary}: this instance was
     * reached by a deliberate tap on ☰ Library rather than by whatever
     * starts the launcher's own task.
     *
     * The two ask a different question in {@link #onCreate}. The launcher's
     * is "should the app open here at all", which {@link
     * SettingsActivity#startsInLibrary} answers and can send this activity
     * straight back to {@link EmulatorActivity} for - the switch, or no
     * folder, means the machine is what a bare launch should show. A ☰
     * Library tap has already answered that question: somebody asked for
     * this screen by name, and {@code EmulatorActivity} only offers the row
     * at all once {@link SettingsActivity#libraryExists} is true - see that
     * row's own comment. Asking {@code startsInLibrary} again here, which
     * also reads the switch, made the row silently hand back to the machine
     * whenever the switch was off, indistinguishable from the tap doing
     * nothing.
     *
     * With no content folder at all - a grant lost between the row being
     * built and being tapped - this still does not bounce: {@link #onCreate}
     * carries on and {@link #load} finds nothing to browse the ordinary way,
     * which is what shows the "choose a content folder" view rather than a
     * silent trip back to the machine. A deliberate tap earns an answer, not
     * a bounce.
     */
    public static final String EXTRA_FROM_MENU = "dev.ldlab.zedex.extra.LIBRARY_FROM_MENU";

    private static final int REQUEST_CONTENT_TREE = 1;

    /** {@code onSaveInstanceState}'s own bundle key for {@link
     *  #pendingSelectionKey} - deliberately not named {@code KEY_*}: that
     *  prefix is what {@code scripts/check-prefs.py} watches for to cross
     *  check a {@code SharedPreferences} read against its write, and a
     *  {@code Bundle} answers to the same method names that class does
     *  without being one. */
    private static final String STATE_SELECTED_KEY = "selectedKey";

    /** "list" or "grid"; read with getString always - see CLAUDE.md. */
    private static final String KEY_VIEW = "libraryView";
    private static final String VIEW_LIST = "list";
    private static final String VIEW_GRID = "grid";

    /** "name", "date" or "size"; read with getString always. */
    private static final String KEY_SORT = "librarySort";
    private static final String SORT_NAME = "name";
    private static final String SORT_DATE = "date";
    private static final String SORT_SIZE = "size";

    /** {@link #SORT_NAME}, {@link #SORT_DATE} and {@link #SORT_SIZE}, in the
     *  order {@link OptionsDialog} shows them - its rows speak by index
     *  rather than by this class's own field names, so {@link
     *  #sortFieldIndex} and this array are the only place the two are tied
     *  together. */
    private static final String[] SORT_FIELDS = { SORT_NAME, SORT_DATE, SORT_SIZE };

    /** Reverses whichever field {@link #KEY_SORT} names; read with
     *  getBoolean always. Ascending - name A-Z, oldest first, smallest first
     *  - is the default for every field alike, so one flag serves all three. */
    private static final String KEY_SORT_DESC = "librarySortDescending";

    /** Whether a row with a scraped name shows it instead of the filename -
     *  written by the settings screen's own Library tab, defaulting to true;
     *  read with getBoolean always. Not this screen's to write. */
    private static final String KEY_LIBRARY_NAMES = "libraryNames";

    /** Whether {@link #advanceToPaneVideo}'s own three-second wait runs at
     *  all - written by the same settings tab, defaulting to true, which is
     *  what this screen always did before the switch existed; read with
     *  getBoolean always. Not this screen's to write. */
    private static final String KEY_LIBRARY_VIDEO_AUTOPLAY = "libraryVideoAutoplay";

    private static final int BACKING = 0xff14151a;
    private static final int TEXT = 0xffededf2;
    private static final int MUTED = 0xff8b8b99;
    private static final int ACTIVE = 0xff00b0c8;
    private static final int DIVIDER = 0x33ffffff;

    /** Behind the active tab - the same low-alpha cyan {@code
     *  EntryAdapter.SELECTED_BACKGROUND} tints the list's own selected row
     *  with, so "this is the current one" reads the same way everywhere on
     *  this screen. */
    private static final int TAB_ACTIVE_BACKGROUND = 0x3300b0c8;

    /** The rail's own width, and the side of every square button in it -
     *  comfortably above the 48dp a touch target is asked to be, in dp
     *  rather than pixels since it is combined with {@link #pixels} at every
     *  use, never on its own. */
    private static final int RAIL_SIZE_DP = 56;

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

    /** The key {@link #onSaveInstanceState} saved, waiting for the first
     *  listing this activity loads after being recreated - a rotation, most
     *  likely, since nothing else about this screen is rebuilt from
     *  scratch. Not a {@code SharedPreferences} key and never confused for
     *  one - see {@link #STATE_SELECTED_KEY}. Consumed by {@link
     *  #applyFilterSort} whether or not it finds a match. */
    private String pendingSelectionKey;

    /**
     * Guards a slow load against answering after a newer one was asked for.
     * Walking into a folder and straight back out again is quicker than a
     * content provider on a slow SD card, and without this the first
     * request's answer could land after the second's and show the wrong
     * folder.
     */
    private int loadToken;

    /**
     * Set by {@link #finishLoad}, right where {@link #loaded} is actually
     * assigned from a completed query - never by anything that calls {@link
     * #applyFilterSort} without a load behind it, which happens earlier than
     * it looks like it should: {@code show}'s own {@link #clearSearch} sets
     * the search field's text to {@code ""}, and setting an
     * {@code EditText}'s text fires its {@code TextWatcher} synchronously,
     * so {@code applyFilterSort} runs - with {@link #loaded} still at
     * whatever it was before, {@code onCreate}'s own field initialiser on a
     * fresh instance - moments into {@code onCreate}, well before the
     * background thread {@link #load} started has had any chance to answer.
     * {@link #applyFilterSort} reads this to tell that call apart from the
     * real one that follows once the folder actually answers - an empty
     * {@code shown} on its own cannot tell the two apart, since a genuinely
     * empty folder or a search that matched nothing looks exactly the same
     * as a load that has simply not finished yet, and only this flag says
     * which one it is.
     */
    private boolean loadCompleted;

    private RecyclerView recycler;
    private EntryAdapter adapter;
    private GamepadCursor padCursor;

    /**
     * How many columns the grid shape has - recomputed from the recycler's
     * own measured width by {@link #updateGridSpanCount}, never from the
     * display's.
     *
     * Two until that first happens, which is the fewest the grid ever has:
     * {@code onCreate} builds the {@code GridLayoutManager} before anything
     * has been measured, and a first frame at two columns that corrects
     * itself a moment later is better than one computed from a width the
     * recycler does not have. See {@link #updateGridSpanCount} for what
     * asking the display instead cost.
     */
    private int gridSpanCount = 2;

    /**
     * Roughly how wide a grid column should be, in dp.
     *
     * Not 100, which is what it was while the tile's picture was a fixed
     * 100dp tall whatever its width: a tile is 3:4 now, so a 100dp column is
     * a 133dp tall picture and the grid lost a column and gained height in
     * the same change - three tiles across a portrait phone where there had
     * been four, against a screen this user had already asked to be made
     * more compact. 85 puts the fourth column back and keeps a tile about
     * the height the old one was.
     */
    private static final int GRID_COLUMN_DP = 85;

    /** Select or the right stick's own click: the sort field, its direction
     *  and list-or-grid, all in one modal dialog a pad can actually use - see
     *  {@link OptionsDialog}. The toolbar's own sort popup and view button
     *  are untouched and stay the discoverable path for a finger. */
    private OptionsDialog optionsDialog;

    /** {@code Metadata.lastLinked}'s answer as of the last time it was
     *  checked - see {@link #onResume}. {@code -1} rather than 0 so the very
     *  first check never mistakes "never linked" for "already known", even
     *  though the two answer the same either way: a mismatch on a freshly
     *  built adapter clears a cache that has nothing in it yet. */
    private long lastLinkedAt = -1;

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

    /** The pictures and, last, the video - swiped between, zoomed to {@link
     *  MediaViewerActivity} on a tap, and faded in over the empty box's own
     *  background as each resolves. Empty, showing the plain box underneath,
     *  for anything unscraped; see {@link #updatePane}. */
    private Gallery paneGallery;

    /** Set once a person has swiped {@link #paneGallery} for whatever is
     *  currently selected - cleared by every {@link #updatePane}, since it
     *  answers "has this selection's own gallery been swiped", not "has
     *  anyone ever swiped anything". {@link #advanceToPaneVideo} reads this
     *  so the three-second timer that would otherwise carry a person to the
     *  video never overrides a page they chose for themselves. */
    private boolean paneUserSwiped;

    /** Whether {@link #updatePane} schedules {@link #advanceToPaneVideo} at
     *  all - re-read every {@link #onResume}, the same as {@link
     *  #KEY_LIBRARY_NAMES} beside it, since the settings screen's own Library
     *  tab is exactly as liable to have changed since last time as the sort
     *  or the folder is. */
    private boolean videoAutoplay = true;

    private TextView paneTitle;

    /** The filename, under {@link #paneTitle} - shown only when that title
     *  is a scraped name rather than the filename itself, so "look closely
     *  and the disk's own name is still there" has somewhere to say it. */
    private TextView paneFilename;

    /** Developer, publisher and the release year, whichever of the three
     *  {@link Meta} actually has, one line, joined by the same separator
     *  {@link EntryAdapter#detail} already uses for size and date. Gone
     *  rather than empty when none of the three is known. */
    private TextView paneFacts;

    private TextView paneSubtitle;

    /** Scrollable on its own, inside a fixed share of the pane's own height -
     *  see {@link #buildPane} - so a description that runs to three
     *  paragraphs, which at least one game's does, scrolls rather than
     *  pushing {@link #paneActionButton} off the bottom of the screen. Empty
     *  rather than gone when there is nothing scraped, so the space it holds
     *  still keeps the button pinned to the foot of the pane exactly as it
     *  already was before any of this existed. */
    private ImageButton paneInfoButton;

    /** Beside {@link #paneInfoButton} - shown only once {@link
     *  #updatePane}'s own call to {@code Scraped#loadManual} answers that
     *  this selection has one; see {@link #buildPane}. */
    private ImageButton paneManualButton;

    /** Plays a file, or opens a folder or an archive - see {@link
     *  #updatePane}, which is the one place that decides which. */
    private Button paneActionButton;

    /** Bumped on every {@link #updatePane} call, before anything
     *  asynchronous is asked for - the same shape {@code EntryAdapter}'s own
     *  {@code bindToken} is, for the same reason: the facts a background
     *  thread resolves, or the three-second timer that would bring the
     *  video forward, must be told when the selection has already moved on
     *  by the time either is ready to act. {@link #paneGallery} keeps its
     *  own token for the pictures and the video themselves - see {@link
     *  Gallery#load}. */
    private int paneToken;

    /** Where {@link #advanceToPaneVideo}'s own three-second wait is
     *  scheduled, and where it is cancelled from - see {@link #updatePane}. */
    private final Handler paneHandler = new Handler(Looper.getMainLooper());

    /** Tags every {@link Handler#postDelayed} this screen schedules for the
     *  pane's video, so {@link #updatePane} can cancel whichever one is
     *  pending without needing to keep the exact {@link Runnable} it was
     *  scheduled with. */
    private final Object paneVideoToken = new Object();

    /** How long the cursor has to rest on a row before its video starts -
     *  long enough that walking through a list does not start a dozen of
     *  them, short enough to feel like an answer to stopping; see
     *  docs/LIBRARY.md. */
    private static final int PANE_VIDEO_DELAY_MS = 3000;

    /** Roughly what the pane's own cover box actually draws, in dp - bigger
     *  than a row's or a tile's, since the box itself is, but still a
     *  fraction of a full-size cover; see {@code Scraped#load}'s own note on
     *  why decoding to a target rather than a picture's full resolution is
     *  what keeps this from being needlessly slow. */
    private static final int PANE_TARGET_DP = 240;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        preferences = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);

        // The one decision this screen makes before drawing anything: whether
        // it should be here at all - and only the launcher path asks it. Off,
        // or with no folder granted, and the app opens on the machine exactly
        // as it always has - see startsInLibrary and docs/LIBRARY.md, "A
        // content folder is the gate". A ☰ Library tap skips this outright;
        // see EXTRA_FROM_MENU's own comment for why asking again here was a
        // silently dead menu row.
        boolean fromMenu = getIntent().getBooleanExtra(EXTRA_FROM_MENU, false);

        if (!fromMenu && !SettingsActivity.startsInLibrary(this, preferences)) {
            startActivity(new Intent(this, EmulatorActivity.class));
            finish();
            return;
        }

        // The manifest label is resolved in the phone's language rather than
        // this screen's, so the title is set here; see Language. Still set
        // even though the bar it would show in is hidden below - the task
        // switcher reads it from here, not from the bar.
        setTitle(R.string.library_title);

        sort = preferences.getString(KEY_SORT, SORT_NAME);
        sortDescending = preferences.getBoolean(KEY_SORT_DESC, false);
        grid = VIEW_GRID.equals(preferences.getString(KEY_VIEW, VIEW_LIST));

        setContentView(buildPage());

        // "Library" over a screen that is obviously the library earns
        // nothing - the breadcrumb says where you are - and the bar cost
        // close to an eighth of a 1080p landscape phone's height.
        if (getActionBar() != null) getActionBar().hide();

        // Nothing of ours under the status bar or the camera; see SafeArea.
        SafeArea.fit(findViewById(android.R.id.content));

        padNav = buildPadNav();
        padCursor = new GamepadCursor(padNav);

        optionsDialog = new OptionsDialog(this, new OptionsDialog.Callbacks() {
            @Override
            public void onSortField(int index) {
                chooseSortField(SORT_FIELDS[index]);
                optionsDialog.refresh(sortFieldIndex(sort), sortDescending, grid);
            }

            @Override
            public void onViewMode(boolean wantGrid) {
                applyViewMode(wantGrid);
                optionsDialog.refresh(sortFieldIndex(sort), sortDescending, grid);
            }
        });

        // A rotation recreates this activity outright - see buildPage's own
        // note on why - and with everything else the pane now shows, losing
        // the selection to that is a real loss of place rather than the
        // minor thing it was when the pane held only a filename and a
        // button. Restored once the first listing loads; see
        // applyFilterSort.
        if (state != null) {
            pendingSelectionKey = state.getString(STATE_SELECTED_KEY);
        }

        pushRoot();
        show(Tab.BROWSE);
    }

    /**
     * Saves the selection's own key, if there is one, so a rotation can put
     * it back - see {@link #pendingSelectionKey} and {@link
     * #applyFilterSort}. Nothing else about where this screen was - which
     * tab, which folder - is saved here; {@link #onCreate} always rebuilds
     * those from the root, same as it already did before any of this.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (selected != null) outState.putString(STATE_SELECTED_KEY, selected.key());
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Cheap - a file stat behind Metadata's own cache - and the one
        // signal available that a link actually changed something: pressing
        // Link again is what moves this, never simply coming back here, so
        // an ordinary return from a game does not pay for a cache it never
        // needed to lose. A mismatch means every cached name and picture may
        // now be answering a question that has a different answer - see
        // EntryAdapter.clearScraped.
        long linkedAt = Metadata.lastLinked(this);
        if (linkedAt != lastLinkedAt) {
            lastLinkedAt = linkedAt;
            adapter.clearScraped();
        }

        // Whether a scraped name replaces the filename - the settings
        // screen's own Library tab is the only thing that writes this, and
        // it is exactly as liable to have changed since last time as the
        // folder or the sort is.
        adapter.setShowScrapedNames(preferences.getBoolean(KEY_LIBRARY_NAMES, true));

        // Same reasoning: whether the pane's own three-second wait ever
        // fires - see updatePane - is exactly as liable to have changed in
        // Settings as the names switch beside it.
        videoAutoplay = preferences.getBoolean(KEY_LIBRARY_VIDEO_AUTOPLAY, true);

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

        // Same reasoning, for the dialog's own second GamepadCursor - closing
        // it releases that repeat too, via its own onDismiss.
        optionsDialog.dismiss();

        // A player left running behind the screen is a real leak, and this
        // screen is exactly the one people leave running - going to the
        // background is not "the selection changed", but it is still one of
        // the three times a video must not be left playing.
        paneGallery.release();
    }

    /** Browse's own way up, and the app's own way out from its root. */
    @Override
    public void onBackPressed() {
        if (popStack()) return;
        super.onBackPressed();
    }

    // --- gamepad ---------------------------------------------------------

    /**
     * The eight things a pad can do here, wired to exactly what touch already
     * does: {@link #select} is the same one {@link EntryAdapter.Callbacks}
     * calls, {@link #popStack} is the same one Back and the chevron share,
     * {@link #toggleFavorite} is the same one a long press performs, and
     * {@link #chooseSortField} and {@link #applyViewMode} are what the
     * toolbar's own sort popup and view button already call. Built once, by
     * {@link #buildPadNav}, and handed to {@link GamepadCursor}, which is the
     * one place that turns a stick, a hat, a D-pad and six buttons into calls
     * to these eight - the one place, still, once the search field or the
     * options dialog is involved: both are handled here, in this same
     * implementation, rather than by a second thing that also gets to decide
     * what a button means on this screen.
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
                // The D-pad and the stick belong to the field while it has
                // focus, not to the list underneath it - a real keyboard's
                // arrow keys still reach it the ordinary way, since a
                // gamepad's own are the only ones intercepted before it; see
                // dispatchKeyEvent.
                if (searchField.isFocused()) return;
                moveCursor(dx, dy);
            }

            @Override
            public void page(int rows) {
                if (searchField.isFocused()) return;
                pageCursor(rows);
            }

            @Override
            public void activate() {
                if (selected == null) return;
                if (isContainer(selected)) enter(selected); else openGame(selected);
            }

            @Override
            public void back() {
                // The same button everywhere else on this screen is the way
                // out, so it is the way out of the search field too - closing
                // the keyboard and dropping focus back to the list, rather
                // than also popping Browse's stack on the same press.
                if (searchField.isFocused()) {
                    dismissKeyboard();
                    return;
                }
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

            @Override
            public void search() {
                focusSearchField();
            }

            @Override
            public void options() {
                optionsDialog.show(sortFieldIndex(sort), sortDescending, grid);
            }
        };
    }

    /**
     * Focuses the search field and asks for the keyboard, for X - the touch
     * path never needed this, since tapping the field already does both.
     */
    private void focusSearchField() {
        searchField.requestFocus();

        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(searchField,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /**
     * Gamepad input is claimed here, before the view tree ever sees it -
     * {@code onKeyDown}/{@code onKeyUp} would only see what a focused view
     * left unconsumed, and the search field, focused, would otherwise answer
     * a D-pad itself the way a text field always does, which is exactly the
     * "sometimes in the list, sometimes not" split this screen does not
     * want. {@link GamepadCursor#key} already refuses anything not from a
     * pad, so a real keyboard's own arrow keys reach the field exactly as
     * they always did.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (padCursor.key(event)) return true;
        return super.dispatchKeyEvent(event);
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
     * exactly as it does by touch.
     *
     * @param dx within a row: {@code -1} or {@code 1}, never both nonzero
     *           with {@code dy} at once - see {@link GamepadCursor.Nav#move}.
     * @param dy by a row, scaled by however many columns the grid has;
     *           {@code 1} in list mode, where a row is one entry.
     */
    private void moveCursor(int dx, int dy) {
        int span = grid ? gridSpanCount : 1;
        moveCursorBy(dx != 0 ? dx : dy * span);
    }

    /**
     * Pages the cursor up or down by a full screenful of rows - the same
     * move a step at a time is, just a bigger step, so it goes through {@link
     * #moveCursorBy} rather than repeating what that already does.
     *
     * @param rows {@code -1} or {@code 1} - see {@link GamepadCursor.Nav#page}.
     */
    private void pageCursor(int rows) {
        moveCursorBy(rows * pageSize());
    }

    /**
     * How many rows the list is showing right now, from the layout manager's
     * own first and last visible positions rather than a guess - a screenful,
     * which changes with the window and the sort of device this is. Already
     * the right answer in grid mode too, and for the same reason nothing
     * here asks {@link #gridSpanCount} at all: the first and last visible
     * <em>positions</em> are indices into the flat list every row's worth of
     * columns sits in, so the gap between them already counts a screenful of
     * rows times however many columns each one has.
     */
    private int pageSize() {
        RecyclerView.LayoutManager manager = recycler.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) return 1;

        LinearLayoutManager linear = (LinearLayoutManager) manager;
        int first = linear.findFirstVisibleItemPosition();
        int last = linear.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return 1;

        return Math.max(1, last - first + 1);
    }

    /**
     * The one place a step or a page actually moves the cursor: applies
     * {@code delta} to wherever it is now, clamped at both ends rather than
     * wrapping - a list of thousands wrapping from the last row to the first
     * on one more push would look like a jump to a random row, not like
     * reaching an end - and selects whatever it lands on.
     *
     * Nothing is selected yet on a fresh tab or a folder just walked into -
     * {@link #show} and {@link #enter} both clear it - so the first push in
     * any direction, a page included, lands on the first row rather than
     * nowhere.
     */
    private void moveCursorBy(int delta) {
        int count = adapter.getItemCount();
        if (count == 0) return;

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
     * The rail, full height, beside everything else - which still splits
     * into the main column and the pane exactly as it always did, side by
     * side in landscape and stacked in portrait. The rail does not: one
     * narrow strip down the left edge, the same in both, is the entire point
     * of moving the tabs into it - see {@link #buildRail}.
     *
     * Not handled by {@code onConfigurationChanged}: this activity declares
     * no {@code configChanges} of its own, so a rotation recreates it and
     * this is read fresh every time, the same as every screen but the
     * emulator's own.
     */
    private View buildPage() {
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BACKING);

        root.addView(buildRail(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        View railDivider = new View(this);
        railDivider.setBackgroundColor(DIVIDER);
        root.addView(railDivider, new LinearLayout.LayoutParams(
                pixels(1), LinearLayout.LayoutParams.MATCH_PARENT));

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

        outer.addView(buildPane(landscape), new LinearLayout.LayoutParams(
                landscape ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                landscape ? LinearLayout.LayoutParams.MATCH_PARENT : 0, 1f));

        root.addView(outer, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        return root;
    }

    private View buildMainColumn() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // The only row above the list now that the tabs moved into the rail
        // beside it - see buildRail. The breadcrumb used to be a row of its
        // own above this one too; see buildToolbar, which is where it lives
        // now and why.
        root.addView(buildToolbar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout content = new FrameLayout(this);

        recycler = new RecyclerView(this);
        recycler.setLayoutManager(grid ? new GridLayoutManager(this, gridSpanCount)
                                       : new LinearLayoutManager(this));

        // The recycler has no width yet - the rail and the pane have not
        // been measured against it either - so gridSpanCount stays at its
        // safe default until this fires with a real one, and again whenever
        // it changes; see updateGridSpanCount.
        recycler.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) updateGridSpanCount();
        });

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
     * The rail: three tab icons down the left edge, the machine button at
     * the foot of it. Icons rather than the labelled strip this replaced,
     * and a rail rather than a band, for the same reason - a label is what
     * was truncating in landscape, and the fix that removes the words
     * outright is also the one that costs the screen nothing but width,
     * which portrait can spare far more easily than a whole band of height.
     * One layout now, not one per orientation - see {@link #buildPage}.
     *
     * The machine button sits here rather than in the toolbar for the same
     * reason it sat beside the old strip: it is navigation, the same level as
     * Browse, Favourites and Recent, not something done to whichever list is
     * showing. At the foot rather than beside the three, with a rule between
     * them, because leaving the library is not a fourth tab.
     */
    private View buildRail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);

        for (Tab candidate : Tab.values()) {
            View button = buildTab(candidate);
            tabViews.add(button);
            rail.addView(button);
        }

        // Pushes the machine button to the foot of the rail, however tall
        // the window is.
        View spacer = new View(this);
        rail.addView(spacer, new LinearLayout.LayoutParams(
                pixels(RAIL_SIZE_DP), 0, 1f));

        View divider = new View(this);
        divider.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                Math.round(pixels(RAIL_SIZE_DP) * 0.6f), pixels(1));
        dividerParams.topMargin = dividerParams.bottomMargin = pixels(8);
        rail.addView(divider, dividerParams);

        ImageButton machineButton = railButton(R.drawable.ic_chip,
                getString(R.string.library_machine));
        machineButton.setOnClickListener(v -> openMachine());
        rail.addView(machineButton);

        return rail;
    }

    /**
     * One icon, square, {@link #RAIL_SIZE_DP} on a side - comfortably above
     * the platform's own 48dp touch target minimum, since a rail costs
     * nothing by being a little wider than it strictly needs to be. The
     * label is gone, but its words are not: {@code contentDescription} still
     * carries them, set once here and never again, which is what CLAUDE.md's
     * own rule against a description that changes asks for.
     */
    private View buildTab(Tab which) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(TAB_ICONS[which.ordinal()]);
        button.setContentDescription(getString(TAB_LABELS[which.ordinal()]));

        // The background is this app's own tint for "the active tab" - see
        // paintTabs - set to nothing here and painted there instead. The
        // ripple sits in the foreground over it, in this app's own colour,
        // so a touch still shows feedback without replacing the tint or
        // reaching for android.R.drawable.list_selector_background, whose
        // pressed and focused states answer to the device theme's own
        // accent rather than anything chosen here - see Ripple.
        button.setBackgroundColor(0x00000000);
        button.setForeground(Ripple.make());
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setOnClickListener(v -> show(which));
        button.setLayoutParams(new LinearLayout.LayoutParams(
                pixels(RAIL_SIZE_DP), pixels(RAIL_SIZE_DP)));

        return button;
    }

    private void paintTabs() {
        for (Tab candidate : Tab.values()) {
            ImageButton button = (ImageButton) tabViews.get(candidate.ordinal());
            boolean active = candidate == tab;

            button.setColorFilter(active ? ACTIVE : MUTED);
            button.setBackgroundColor(active ? TAB_ACTIVE_BACKGROUND : 0x00000000);
        }
    }

    /** {@link #toolbarButton}, but square at {@link #RAIL_SIZE_DP} rather
     *  than 44dp - the rail's own width, not the toolbar's row height, is
     *  what decides how big a button fits here. */
    private ImageButton railButton(int icon, String description) {
        ImageButton button = toolbarButton(icon, description);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                pixels(RAIL_SIZE_DP), pixels(RAIL_SIZE_DP)));
        return button;
    }

    /**
     * The breadcrumb, search, sort and the list/grid switch - the only row
     * above the list now that the tabs moved into the rail: the chevron and
     * the path on the left, search taking whatever room the path does not
     * want, sort and view on the right, and the full width of the column to
     * spread across, tabs no longer sharing it in either orientation.
     *
     * The way back to the machine lives in the rail instead, not here - see
     * {@link #buildRail}, since it is navigation and none of these four is.
     */
    private View buildToolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pixels(4), pixels(2), pixels(4), pixels(2));

        // Only when there is somewhere to go: hidden at the root, and on
        // either of the other two tabs, which have no stack of their own.
        // Back already pops the stack; this is the same action put where a
        // touchscreen can reach it, since nothing else on screen offered it.
        upButton = toolbarButton(R.drawable.ic_chevron_left, getString(R.string.library_up));
        upButton.setOnClickListener(v -> popStack());
        row.addView(upButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        pathLabel = new TextView(this);
        pathLabel.setTextColor(MUTED);
        pathLabel.setTextSize(12);
        pathLabel.setSingleLine();
        // The start is cut, not the end: the folder you are in is the part
        // of the path worth keeping on screen, and it is the last segment.
        pathLabel.setEllipsize(TextUtils.TruncateAt.START);
        pathLabel.setPadding(pixels(4), 0, pixels(8), 0);
        // The path itself goes up a level too, so the whole label is one
        // target and not only the chevron; harmless at the root, where there
        // is nowhere to go and this simply does nothing.
        pathLabel.setOnClickListener(v -> popStack());
        row.addView(pathLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

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

    /**
     * Every icon button in the toolbar and, via {@link #railButton}, the
     * machine button too - so the fix in {@link Ripple} for one is the fix
     * for all of them, rather than something to remember to repeat.
     */
    private ImageButton toolbarButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        float density = getResources().getDisplayMetrics().density;

        button.setImageResource(icon);
        button.setColorFilter(TEXT);
        button.setContentDescription(description);
        button.setBackgroundColor(0x00000000);
        button.setForeground(Ripple.make());
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
     * whole point of shipping the container before anything fills it, and
     * "linking to ES-DE" for what actually fills it now.
     *
     * The cover box holds {@link #paneGallery} over a plain background - see
     * {@link #updatePane} for what fills it.
     *
     * {@code details} itself is the one thing that differs by shape, and only
     * in which way it stacks two pieces that are otherwise identical either
     * way - the cover box, and everything {@link #addPaneDetailViews} adds.
     * Landscape keeps the tall narrow column this always was: the box on top
     * at a fixed height, the rest below it. Portrait cannot afford that -
     * height is the scarce thing there, not width - so the box sits beside
     * the text instead, both weighted against the pane's own width, 2 to 3,
     * rather than a dp fixed at design time or a size derived from the
     * pane's height, which on a strip far wider than it is tall left next to
     * nothing for the text: a portrait pane is wide, not tall, and it is the
     * width the box and the text column split, not the height. Either way,
     * {@link #addPaneDetailViews} puts exactly one weighted child - the
     * description - among the rest at their natural height, so the
     * description is what yields to a short pane and {@link
     * #paneActionButton} is always laid out, never squeezed out of it.
     */
    private View buildPane(boolean landscape) {
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

        FrameLayout coverBox = new FrameLayout(this);
        coverBox.setBackgroundColor(0x14ffffff);

        // Fitting the whole picture inside Gallery's own picture pages is
        // where the grid's tiles crop instead: the pane is the one place a
        // person looks at the picture rather than past it, and the box here
        // is nothing like the shape of box art - 322x640 in portrait,
        // against a cover's own 3:4 - so CENTER_CROP once threw away a third
        // of Ms. Pac-Man's width and cut "FROM ATARISOFT" off the bottom,
        // while the tile above it in the grid showed the same cover whole.
        // See Gallery's own comment on exactly this, and on why FIT_CENTER
        // rather than CENTER_INSIDE is what fits it now.
        paneGallery = new Gallery(this);
        paneGallery.setPictureTargetPx(pixels(PANE_TARGET_DP));
        paneGallery.setOnPageTapped(this::openViewerFromPane);
        paneGallery.setOnUserSwipe(() -> paneUserSwiped = true);
        coverBox.addView(paneGallery, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout details = new LinearLayout(this);
        details.setPadding(pixels(16), pixels(16), pixels(16), pixels(16));

        if (landscape) {
            details.setOrientation(LinearLayout.VERTICAL);
            details.addView(coverBox, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, pixels(160)));
            addPaneDetailViews(details);
        } else {
            details.setOrientation(LinearLayout.HORIZONTAL);

            // 2 against 3 of the pane's own width - about 40% for the box,
            // 60% for the text - rather than either a fixed dp or a size
            // derived from the pane's height: a portrait pane is wide, not
            // tall, so it is the width the two split, and a weight adapts to
            // whatever that width actually is without measuring anything.
            LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 2f);
            coverParams.rightMargin = pixels(16);
            details.addView(coverBox, coverParams);

            LinearLayout textColumn = new LinearLayout(this);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            addPaneDetailViews(textColumn);
            details.addView(textColumn, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 3f));
        }

        paneDetails = details;
        frame.addView(paneDetails, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        updatePane();

        return frame;
    }

    /**
     * The title, the filename, the facts line, the size and date, the
     * description and the action button - the same six views regardless of
     * shape, added straight into {@code column}: {@code details} itself in
     * landscape, since that is already the vertical stack that wants them;
     * a narrower column beside the cover box in portrait, which needs the
     * exact same weighted description to resolve against its own height
     * rather than the whole row's. The description is the one weighted
     * child among these six - see its own comment - so it is always this,
     * never the button beneath it, that gives way to a short column.
     */
    private void addPaneDetailViews(LinearLayout column) {
        paneTitle = new TextView(this);
        paneTitle.setTextColor(TEXT);
        paneTitle.setTextSize(16);
        paneTitle.setMaxLines(3);
        paneTitle.setEllipsize(TextUtils.TruncateAt.END);
        // In landscape this is the gap below the cover box sitting above it;
        // in portrait, beside it, it is just breathing room at the top of
        // the column - harmless either way.
        paneTitle.setPadding(0, pixels(12), 0, 0);
        column.addView(paneTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Gone rather than empty when the title is already the filename -
        // see updatePane - so "look closely and the disk's own name is
        // still there" costs no blank line when there is nothing to add to
        // what the title already says.
        paneFilename = new TextView(this);
        paneFilename.setTextColor(MUTED);
        paneFilename.setTextSize(12);
        paneFilename.setMaxLines(1);
        paneFilename.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        paneFilename.setPadding(0, pixels(2), 0, 0);
        paneFilename.setVisibility(View.GONE);
        column.addView(paneFilename, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Developer, publisher, year - gone rather than empty when none of
        // the three is known, which is most of this collection even linked;
        // see factsLine.
        paneFacts = new TextView(this);
        paneFacts.setTextColor(MUTED);
        paneFacts.setTextSize(13);
        paneFacts.setPadding(0, pixels(6), 0, 0);
        paneFacts.setVisibility(View.GONE);
        column.addView(paneFacts, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // The size and the date - unconditional, exactly as before any of
        // this existed.
        paneSubtitle = new TextView(this);
        paneSubtitle.setTextColor(MUTED);
        paneSubtitle.setTextSize(13);
        paneSubtitle.setPadding(0, pixels(4), 0, 0);
        column.addView(paneSubtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Room for a description that can run long - one game's here runs to
        // three paragraphs - scrolling inside this fixed share of the
        // column's height rather than pushing the Play button off the
        // bottom of the screen. Empty rather than gone when there is nothing
        // scraped, so this keeps taking up the same space the plain spacer
        // it replaces always did, and the button stays exactly where it
        // always was - the one weighted child here, everything else at its
        // own natural height, so this is what yields on a short pane and
        // never the button beneath it.
        // Whatever is left over, and nothing in it. The pane says the few
        // facts that fit on one line each; a description does not fit on one
        // line, and the version of this that tried ran out of room in
        // landscape and squeezed itself down to 26px - a scroll bar with no
        // room to scroll in. GameInfoActivity is where the long text lives
        // now, and this spacer is what keeps the buttons at the foot of the
        // pane rather than floating under the facts.
        column.addView(new View(this), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        // The label is set by updatePane, per selection - a folder or an
        // archive is what is on screen here, not only a game, so there is
        // nothing this button can say once and for all. Always laid out at
        // its own natural height, whatever above it was squeezed to get
        // there.
        paneActionButton = new Button(this);
        paneActionButton.setOnClickListener(v -> {
            if (selected == null) return;
            if (isContainer(selected)) enter(selected); else openGame(selected);
        });
        actions.addView(paneActionButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Beside Play rather than anywhere in the facts, because it is about
        // the same game Play is about. Hidden for a folder or an archive,
        // which have nothing to tell.
        paneInfoButton = new ImageButton(this);
        paneInfoButton.setImageResource(R.drawable.ic_zoom);
        paneInfoButton.setColorFilter(MUTED);
        paneInfoButton.setBackground(Ripple.make());
        paneInfoButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        paneInfoButton.setContentDescription(getString(R.string.library_info));
        paneInfoButton.setOnClickListener(v -> showGameInfo());
        actions.addView(paneInfoButton, new LinearLayout.LayoutParams(
                pixels(48), LinearLayout.LayoutParams.MATCH_PARENT));

        // Beside the magnifier rather than in the gallery it used to be a
        // page of - see docs/LIBRARY.md-equivalent reasoning in Gallery's own
        // class comment. Starts hidden, same as paneInfoButton does for a
        // folder or an archive; updatePane brings it back once (and only if)
        // Scraped#loadManual answers off the UI thread that there is one -
        // that round trip is a SAF query, never safe to make just to decide
        // whether to draw a button.
        paneManualButton = new ImageButton(this);
        paneManualButton.setImageResource(R.drawable.ic_manual);
        paneManualButton.setColorFilter(MUTED);
        paneManualButton.setBackground(Ripple.make());
        paneManualButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        paneManualButton.setContentDescription(getString(R.string.library_manual));
        paneManualButton.setVisibility(View.GONE);
        actions.addView(paneManualButton, new LinearLayout.LayoutParams(
                pixels(48), LinearLayout.LayoutParams.MATCH_PARENT));

        column.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /**
     * Opens {@link GameInfoActivity} for whatever is selected, carrying the
     * two things it needs to find everything else: the file's own name, and
     * its path relative to the content tree, which is the key both the
     * metadata store and the artwork are addressed by. Nothing else travels -
     * the store is a lookup away on the other side, and passing a parsed
     * {@link Meta} through an Intent would be a second copy able to go stale.
     */
    private void showGameInfo() {
        if (selected == null || isContainer(selected)) return;

        String relativePath = Metadata.relativePath(this, selected.uri);
        if (relativePath == null) return;

        startActivity(new Intent(this, GameInfoActivity.class)
                .putExtra(GameInfoActivity.EXTRA_PATH, relativePath)
                .putExtra(GameInfoActivity.EXTRA_NAME, selected.name));
    }

    /**
     * Opens {@link MediaViewerActivity} at whatever page of {@link
     * #paneGallery} was tapped - the pane's own zoom, for the same reason
     * {@link #showGameInfo} takes a path rather than carrying anything
     * through the Intent: the gallery is a lookup away on the other side.
     */
    private void openViewerFromPane(int index) {
        if (selected == null || isContainer(selected) || selected.inside != null) return;

        String relativePath = Metadata.relativePath(this, selected.uri);
        if (relativePath == null) return;

        startActivity(new Intent(this, MediaViewerActivity.class)
                .putExtra(MediaViewerActivity.EXTRA_PATH, relativePath)
                .putExtra(MediaViewerActivity.EXTRA_INDEX, index));
    }

    /**
     * Shows what is known about the selection, or says there is none - the
     * one place that fills the pane, called from every route to a selection
     * there is: a tap, the pad's cursor landing on a row as it moves, a
     * folder or an archive restoring the row it was entered from, and a tab
     * switch clearing it. That has to be true here rather than in whichever
     * of those prompted the call: with a gamepad a folder or an archive is a
     * routine stop for the cursor, not a rare one reached only by coming back
     * out of it, so a label decided anywhere else would be showing whatever
     * the previous row happened to be by the time this one is looked at.
     *
     * The gallery always stops here, immediately - "stopping when the
     * selection moves on" is not something to wait three seconds for, only
     * bringing the video forward is - and a fresh three-second wait is
     * scheduled for whatever is selected now, {@link #paneToken} telling a
     * timer that fires after the selection has moved on again not to act.
     * {@link #paneUserSwiped} is cleared here too: it answers for this
     * selection's own gallery, not for the pane in general.
     */
    private void updatePane() {
        int token = ++paneToken;

        paneHandler.removeCallbacksAndMessages(paneVideoToken);
        paneUserSwiped = false;

        // Hidden until (and unless) the async check below answers yes for
        // this selection - covers every early return below the same way
        // paneGallery.clear() does, without repeating it at each one.
        paneManualButton.setVisibility(View.GONE);

        boolean have = selected != null;

        paneEmpty.setVisibility(have ? View.GONE : View.VISIBLE);
        paneDetails.setVisibility(have ? View.VISIBLE : View.GONE);

        if (!have) {
            paneGallery.clear();
            return;
        }

        paneTitle.setText(selected.name);
        paneFilename.setVisibility(View.GONE);
        paneFacts.setVisibility(View.GONE);
        paneSubtitle.setText(EntryAdapter.detail(this, selected));
        paneActionButton.setText(isContainer(selected) ? R.string.library_open
                                                        : R.string.library_play);

        // A folder or an archive has nothing an information screen could say,
        // and neither has an entry inside a zip, which has no path of its own
        // for the store to have matched - the same test showGameInfo makes
        // before it does anything.
        paneInfoButton.setVisibility(
                !isContainer(selected) && selected.inside == null ? View.VISIBLE : View.GONE);

        // A folder, an archive, or a file reached from inside a zip has no
        // path of its own to have been scraped by - see EntryAdapter's own
        // note on exactly this, which this mirrors.
        if (isContainer(selected) || selected.inside != null) {
            paneGallery.clear();
            return;
        }

        String relativePath = Metadata.relativePath(this, selected.uri);
        if (relativePath == null) {
            paneGallery.clear();
            return;
        }

        // The gallery resolves and shows its own pictures and video now -
        // see Gallery.load - so this call is left only with the words to
        // ask the store for; the picture Scraped#load also decodes here is
        // never assigned anywhere, kept only because this is the same
        // resolve every row's own thumbnail shares the cache of.
        adapter.scraped().load(this, relativePath, pixels(PANE_TARGET_DP), (meta, picture) -> {
            if (token != paneToken) return; // the selection moved on
            applyPaneMeta(meta);
        });

        // Beside Play and the magnifier, but only once this answers - see
        // paneManualButton's own comment for why the round trip has to
        // happen off the UI thread rather than deciding this up front.
        adapter.scraped().loadManual(this, relativePath, manual -> {
            if (token != paneToken) return; // the selection moved on
            paneManualButton.setVisibility(manual != null ? View.VISIBLE : View.GONE);
            paneManualButton.setOnClickListener(
                    manual != null ? v -> Manuals.open(this, manual) : null);
        });

        paneGallery.load(relativePath);

        // Off, the video is still there to swipe to and still plays once
        // swiped to - see docs/LIBRARY.md and KEY_LIBRARY_VIDEO_AUTOPLAY's
        // own comment - only this automatic move to it is what the setting
        // turns off, so a timer with nothing to do is not even scheduled.
        if (videoAutoplay) {
            paneHandler.postDelayed(() -> advanceToPaneVideo(token),
                    paneVideoToken, PANE_VIDEO_DELAY_MS);
        }
    }

    /**
     * The name, the filename beneath it when the name replaced it, and
     * developer/publisher/year - everything {@link Scraped#load}'s own
     * answer carries besides the picture this no longer uses, applied by
     * {@link #updatePane} itself since it alone decides whether the answer
     * arrived in time to matter.
     */
    private void applyPaneMeta(Meta meta) {
        if (meta != null && meta.name != null && !meta.name.isEmpty()) {
            paneTitle.setText(meta.name);
            paneFilename.setText(selected.name);
            paneFilename.setVisibility(View.VISIBLE);
        }

        String facts = factsLine(meta);
        if (facts != null) {
            paneFacts.setText(facts);
            paneFacts.setVisibility(View.VISIBLE);
        }

    }

    /**
     * Developer, publisher and the release year, joined the same way size
     * and date already are - {@link EntryAdapter#detail} - skipping whatever
     * of the three is not known rather than printing an empty label for it.
     * Null, not empty, when none of the three is - the difference between
     * "nothing to show" and "a blank line to show" that {@link #paneFacts}'s
     * own visibility depends on.
     */
    private String factsLine(Meta meta) {
        if (meta == null) return null;

        StringBuilder line = new StringBuilder();
        appendFact(line, meta.developer);
        appendFact(line, meta.publisher);
        appendFact(line, yearOf(meta.released));

        return line.length() > 0 ? line.toString() : null;
    }

    private static void appendFact(StringBuilder line, String fact) {
        if (fact == null || fact.isEmpty()) return;
        if (line.length() > 0) line.append(" · ");
        line.append(fact);
    }

    /**
     * ES-DE writes a release date as {@code "20201218T000000"} - the year
     * alone, the first four digits, is the only part of that worth showing
     * here, and a value that does not start with four digits is not
     * something to guess the rest of; nothing is shown for it rather than a
     * wrong or half-parsed date.
     */
    private static String yearOf(String released) {
        if (released == null || released.length() < 4) return null;

        String year = released.substring(0, 4);
        for (int i = 0; i < 4; i++) {
            if (!Character.isDigit(year.charAt(i))) return null;
        }
        return year;
    }

    /**
     * The far side of {@link #updatePane}'s own three-second wait: carries
     * the gallery to its own video page, exactly as a swipe would, unless
     * either reason to leave it alone applies - the selection has moved on
     * since the wait was scheduled, or {@link #paneUserSwiped} says a person
     * already chose a page of their own for this one. {@link Gallery#showPage}
     * moves the pager without telling that listener about it, so a wait that
     * does win the race never looks like the swipe it is not.
     */
    private void advanceToPaneVideo(int token) {
        if (token != paneToken || paneUserSwiped) return;

        int index = paneGallery.videoIndex();
        if (index >= 0) paneGallery.showPage(index);
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
     * goes on to ask of it. A picture still resolving for a row from
     * whatever folder was granted before this call is a different one,
     * though, and {@link EntryAdapter#forgetPending} is what stops its
     * answer from landing in a row it no longer has anything to do with.
     */
    private void pushRoot() {
        stack.clear();
        adapter.forgetPending();

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
        loadCompleted = true;
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
        } else if (pendingSelectionKey != null && loadCompleted) {
            // A rotation - see onSaveInstanceState - restored against the
            // first listing loaded afterwards, and only that one: matching
            // against a later reload too would silently select whatever
            // survived some unrelated change, which is exactly the guessing
            // at a neighbour this is not meant to do. Consumed whether or
            // not it found anything, so a folder that no longer has this
            // row - a favourite removed, a folder changed underneath -
            // leaves nothing selected rather than trying again forever.
            //
            // loadCompleted, not "shown is empty", is what tells that case
            // apart from this one still waiting to happen: applyFilterSort
            // runs once already, synchronously, before any of that - show's
            // own clearSearch sets the search field's text to "", which
            // fires its TextWatcher on the spot, which calls this - moments
            // into onCreate and well before the background thread load
            // started has had any chance to answer. shown is empty on that
            // call too, for a reason that has nothing to do with the row
            // being gone, and consuming the key against it - as an earlier
            // version of this did - throws it away before the real listing
            // ever arrives. See loadCompleted's own comment.
            Entry match = findByKey(shown, pendingSelectionKey);
            pendingSelectionKey = null;
            if (match != null) select(match);
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
        applyViewMode(!grid);
    }

    /**
     * The whole of what choosing list or grid does, whichever asked -
     * {@link #toggleView} flips to the other one; {@link OptionsDialog}'s own
     * callback names the one it wants, since a dialog with both written out
     * has no "other one" to flip to. One method either way, so the two never
     * drift apart on what actually changing the view involves.
     */
    private void applyViewMode(boolean wantGrid) {
        grid = wantGrid;
        preferences.edit().putString(KEY_VIEW, grid ? VIEW_GRID : VIEW_LIST).apply();

        updateViewToggle();
        recycler.setLayoutManager(grid ? new GridLayoutManager(this, gridSpanCount)
                                       : new LinearLayoutManager(this));
        adapter.setGrid(grid);
    }

    /**
     * Recomputes {@link #gridSpanCount} from the recycler's own measured
     * width, as many columns as fit at {@link #GRID_COLUMN_DP} each, never
     * fewer than two. Not the display's width: the recycler never gets the whole
     * display, since the rail and the pane both take their own share of it
     * first, and asking the display for it was exactly the bug this
     * replaced - measured on one landscape device, the display's own 914dp
     * produced 9 columns for a recycler that was actually 535dp wide, tiles
     * at 59dp against the 100dp the comment always promised.
     *
     * Called from the layout change listener {@link #buildMainColumn}
     * registers, which is the only thing that knows when the recycler
     * actually has a width to measure - {@code onCreate} does not, and
     * building the {@code GridLayoutManager} there uses whatever this field
     * already holds, correcting itself the moment the real width is known.
     */
    private void updateGridSpanCount() {
        int widthPx = recycler.getWidth();
        if (widthPx <= 0) return;

        int widthDp = Math.round(widthPx / getResources().getDisplayMetrics().density);
        int spanCount = Math.max(2, widthDp / GRID_COLUMN_DP);

        if (spanCount == gridSpanCount) return;
        gridSpanCount = spanCount;

        if (!grid) return;

        // Posted, and setSpanCount rather than a whole new layout manager,
        // because this is called from a layout change listener - which runs
        // *during* the recycler's own layout. Swapping the manager there
        // detaches every view and asks for a layout that the pass already
        // under way never delivers, so the grid came up empty on a cold
        // start and stayed empty: the width becomes known, the span changes
        // two to four, and the recycler is torn down mid-layout with nothing
        // to put it back. It looked like a loading failure and was not one -
        // switching to the list and back rebuilt the manager from an
        // ordinary callback and everything reappeared, which is what made it
        // look intermittent.
        RecyclerView.LayoutManager manager = recycler.getLayoutManager();

        if (manager instanceof GridLayoutManager) {
            recycler.post(() -> ((GridLayoutManager) manager).setSpanCount(gridSpanCount));
        }
    }

    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, R.string.library_sort_name);
        menu.getMenu().add(0, 1, 1, R.string.library_sort_date);
        menu.getMenu().add(0, 2, 2, R.string.library_sort_size);

        menu.setOnMenuItemClickListener(item -> {
            chooseSortField(item.getItemId() == 1 ? SORT_DATE
                           : item.getItemId() == 2 ? SORT_SIZE : SORT_NAME);
            return true;
        });

        menu.show();
    }

    /**
     * Picking the field already showing reverses it; picking a different one
     * switches to it and keeps whichever direction was showing - the one
     * thing already in view before the menu opened, and so the one thing
     * neither choice should reset without being asked to. Shared by the
     * toolbar's own popup and {@link OptionsDialog}'s callback, so there is
     * one answer to what choosing a field does regardless of which asked.
     */
    private void chooseSortField(String field) {
        if (field.equals(sort)) {
            sortDescending = !sortDescending;
        } else {
            sort = field;
        }

        preferences.edit()
                .putString(KEY_SORT, sort)
                .putBoolean(KEY_SORT_DESC, sortDescending)
                .apply();

        updateSortButton();
        applyFilterSort();
    }

    /** {@code field}'s position in {@link #SORT_FIELDS} - what {@link
     *  OptionsDialog} indexes its own rows by. */
    private int sortFieldIndex(String field) {
        for (int i = 0; i < SORT_FIELDS.length; i++) {
            if (SORT_FIELDS[i].equals(field)) return i;
        }
        return 0;
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
