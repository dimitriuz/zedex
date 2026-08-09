package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.Listing;
import dev.ldlab.zedex.library.scrape.Candidate;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.Provider;
import dev.ldlab.zedex.library.scrape.Quota;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.library.scrape.Scrapers;
import dev.ldlab.zedex.library.scrape.Sweep;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.library.ui.Ripple;
import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.work.Work;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A whole collection at a time, with the parts that need a person.
 *
 * {@code Sweep} is the run itself and has no screen in it. This is the three
 * decisions taken before the run starts - which games, which of them to
 * bother with, and what to do about the ones the provider is unsure of - and
 * then the twenty minutes of watching.
 *
 * <b>Three decisions once, rather than one decision three hundred times.</b>
 * That is the whole difference between this and doing it a game at a time from
 * the library's own popup, and it is why they are on a page with the
 * arithmetic in front of them rather than in a dialog: committing to eight
 * hundred requests deserves to be told it is eight hundred requests.
 *
 * A screen rather than a service. Leaving the app stops the run, which is
 * survivable only because there is no progress to lose: "not scraped yet",
 * chosen again tomorrow, is exactly the games this run did not reach. That
 * trade is the reason there is no notification permission, no foreground
 * service type and no stored state anywhere in this feature.
 *
 * The activity is handed a folder and works out the games itself. Three
 * hundred {@link Entry} objects would not survive a Binder transaction, and
 * the walk is the slow part - it belongs on the screen that is already showing
 * something rather than on the one being left.
 */
public final class ScrapeManyActivity extends ZedexActivity {

    private static final String TAG = "Zedex";

    /** The folder the library was standing in, as a tree document uri. */
    public static final String EXTRA_FOLDER = "dev.ldlab.zedex.extra.SCRAPE_FOLDER";

    /** Its display name, for the "this folder" label - a person choosing a
     *  scope should see which folder they are choosing between. */
    public static final String EXTRA_FOLDER_NAME = "dev.ldlab.zedex.extra.SCRAPE_FOLDER_NAME";

    /** How much of the collection a run is about. */
    private enum Scope { FOLDER, BELOW, LIBRARY }

    private Uri folder;
    private String folderName;

    private Scope scope = Scope.BELOW;
    private Sweep.Only only = Sweep.Only.NOT_SCRAPED;
    private Sweep.Conflicts conflicts = Sweep.Conflicts.SKIP;

    /**
     * One provider for the life of the screen, not one per game.
     *
     * The quota counters live on the instance - every reply updates them - so
     * a fresh provider per request would forget the day's count between games
     * and the pacing would have nothing to pace against.
     */
    private Provider provider;

    /** What each scope came to, so that flipping between filters does not
     *  walk the tree again. The walk is the slow part; {@code Sweep.select}
     *  is local and cheap. */
    private final Map<Scope, List<Entry>> walked = new EnumMap<>(Scope.class);

    /** What Start would run, as last counted. */
    private List<Entry> chosen = Collections.emptyList();

    /** Bumped whenever a setting changes, so a count that finishes after the
     *  setting moved on is discarded rather than shown - the same shape the
     *  library's own filter sheet uses. */
    private int countToken;

    private volatile boolean cancelled;
    private boolean running;

    /**
     * The eight, and their folders, in the order the settings list uses.
     *
     * Read from resources rather than written out, so the two lists cannot
     * drift: the array is the same one the Library tab's own
     * MultiSelectListPreference is built from.
     */
    private String[] MEDIA_FOLDERS;
    private int[] MEDIA_LABELS;

    private TextView fetching;
    private TextView estimate;
    private TextView progressLine;
    private TextView quotaLine;
    private ProgressBar bar;
    private Button start;
    private LinearLayout settings;

    @Override
    protected int title() {
        return R.string.scrape_many_title;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        provider = Scrapers.withAccount(this);

        String uri = getIntent().getStringExtra(EXTRA_FOLDER);
        folder = uri == null ? null : Uri.parse(uri);
        folderName = getIntent().getStringExtra(EXTRA_FOLDER_NAME);

        if (provider == null || folder == null) {
            // Only reachable from an Intent built somewhere other than the row
            // that offers this, which checks both.
            finish();
            return;
        }

        MEDIA_FOLDERS = getResources().getStringArray(R.array.scrape_media_folders);
        MEDIA_LABELS = labelsFor(R.array.scrape_media_entries);

        setContentView(buildPage());
        fitToSafeArea();

        recount();
    }

    /**
     * Cancels rather than leaving a thread scraping for a screen that has
     * gone - it would go on spending the day's allowance with nobody to see
     * the result.
     *
     * Which is also why the manifest gives this activity {@code
     * configChanges}: without it, turning the phone over recreates the
     * activity, which lands here, and a twenty-minute run ends because
     * somebody put their phone down sideways. The views are built in code and
     * re-lay out by themselves, and {@code SafeArea.fit} installs a listener
     * rather than padding once, so the cutout still comes out right on the
     * new orientation. A locale change is deliberately <em>not</em> in that
     * list: that one has to recreate, or the screen keeps the old language.
     */
    @Override
    protected void onDestroy() {
        cancelled = true;
        super.onDestroy();
    }

    // --- the page --------------------------------------------------------------------

    private View buildPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Palette.BACKING);

        settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(pixels(20), pixels(16), pixels(20), pixels(8));

        settings.addView(scopeChoice());
        settings.addView(onlyChoice());
        settings.addView(conflictChoice());
        settings.addView(fetchingRow());

        ScrollView scroller = new ScrollView(this);
        scroller.addView(settings, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        page.addView(footer());
        return page;
    }

    private View scopeChoice() {
        String here = folderName == null || folderName.isEmpty()
                ? getString(R.string.scrape_many_scope_here)
                : getString(R.string.scrape_many_scope_named, folderName);

        return choice(R.string.scrape_many_scope,
                      new String[] { here,
                                     getString(R.string.scrape_many_scope_below),
                                     getString(R.string.scrape_many_scope_library) },
                      scope.ordinal(),
                      picked -> {
                          scope = Scope.values()[picked];
                          recount();
                      });
    }

    private View onlyChoice() {
        return choice(R.string.scrape_many_only,
                      new String[] { getString(R.string.scrape_many_only_unscraped),
                                     getString(R.string.scrape_many_only_no_picture),
                                     getString(R.string.scrape_many_only_everything) },
                      only.ordinal(),
                      picked -> {
                          only = Sweep.Only.values()[picked];
                          recount();
                      });
    }

    /**
     * What to do about the games the provider is unsure of.
     *
     * "Take the best match" carries its warning in the row itself rather than
     * behind a confirmation. It is the provider's own first answer and nothing
     * cleverer, and on a Spectrum collection that will sometimes be the wrong
     * game - somebody choosing it should be told plainly, once, and then
     * believed.
     */
    private View conflictChoice() {
        return choice(R.string.scrape_many_conflicts,
                      new String[] { getString(R.string.scrape_many_conflicts_skip),
                                     getString(R.string.scrape_many_conflicts_best),
                                     getString(R.string.scrape_many_conflicts_ask) },
                      conflicts.ordinal(),
                      picked -> conflicts = Sweep.Conflicts.values()[picked]);
    }

    /**
     * What a run will fetch, and a way to change it here.
     *
     * Not a fourth setting of its own: this writes {@code
     * Prefs.KEY_SCRAPE_MEDIA}, the same key the Library tab's own row writes
     * and the same one the popup's one-game scrape reads. Two places to edit
     * one stored answer, rather than two answers - which is the distinction
     * CLAUDE.md's rule about one predicate and two questions is really about.
     *
     * It is here because this is the screen where the cost is being decided.
     * Realising you wanted the manuals too, at the moment you are about to
     * commit to twenty minutes, should not mean going to Settings and coming
     * back.
     */
    private View fetchingRow() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, 0, 0, pixels(18));

        TextView label = new TextView(this);
        label.setText(R.string.scrape_many_fetching);
        label.setTextColor(Palette.MUTED);
        label.setTextSize(12);
        block.addView(label);

        fetching = new TextView(this);
        fetching.setTextColor(Palette.TEXT);
        fetching.setTextSize(15);
        fetching.setMinHeight(pixels(48));
        fetching.setGravity(android.view.Gravity.CENTER_VERTICAL);
        fetching.setOnClickListener(v -> chooseMedia());
        fetching.setBackground(
                Ripple.make(getResources().getDisplayMetrics().density));
        block.addView(fetching, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        showFetching();
        return block;
    }

    private void showFetching() {
        Set<String> chosen = Scrapers.wanted(this).folders();

        if (chosen.isEmpty()) {
            fetching.setText(R.string.scrape_media_none);
            return;
        }

        List<String> names = new ArrayList<>();
        for (int at = 0; at < MEDIA_FOLDERS.length; at++) {
            if (chosen.contains(MEDIA_FOLDERS[at])) names.add(getString(MEDIA_LABELS[at]));
        }
        fetching.setText(android.text.TextUtils.join(", ", names));
    }

    /** The same eight, ticked - and then the estimate is re-stated, since
     *  what was just changed is precisely what the estimate multiplies by. */
    private void chooseMedia() {
        boolean[] ticked = new boolean[MEDIA_FOLDERS.length];
        Set<String> chosen = new java.util.LinkedHashSet<>(Scrapers.wanted(this).folders());

        String[] labels = new String[MEDIA_FOLDERS.length];
        for (int at = 0; at < MEDIA_FOLDERS.length; at++) {
            labels[at] = getString(MEDIA_LABELS[at]);
            ticked[at] = chosen.contains(MEDIA_FOLDERS[at]);
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.settings_scrape_media)
                .setMultiChoiceItems(labels, ticked, (dialog, which, isChecked) -> {
                    if (isChecked) chosen.add(MEDIA_FOLDERS[which]);
                    else chosen.remove(MEDIA_FOLDERS[which]);
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    preferences.edit()
                            .putStringSet(Prefs.KEY_SCRAPE_MEDIA, chosen)
                            .apply();
                    showFetching();
                    showEstimate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface Picked {
        void at(int index);
    }

    /** A heading and a set of radio rows under it. */
    private View choice(int heading, String[] labels, int selected, Picked picked) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, 0, 0, pixels(18));

        TextView label = new TextView(this);
        label.setText(heading);
        label.setTextColor(Palette.MUTED);
        label.setTextSize(12);
        block.addView(label);

        RadioGroup group = new RadioGroup(this);

        for (String text : labels) {
            RadioButton button = new RadioButton(this);
            // Generated rather than counted from zero: three of these groups
            // share one hierarchy, and ids repeated across them collide on
            // anything that addresses a view by id - state restore included.
            button.setId(View.generateViewId());
            button.setText(text);
            button.setTextColor(Palette.TEXT);
            button.setTextSize(15);
            button.setMinHeight(pixels(48));
            group.addView(button);
        }

        group.check(group.getChildAt(selected).getId());
        group.setOnCheckedChangeListener(
                (g, id) -> picked.at(g.indexOfChild(g.findViewById(id))));

        block.addView(group);
        return block;
    }

    /** The count, the cost and the button - the part that stays put while the
     *  settings above it scroll. */
    private View footer() {
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setPadding(pixels(20), pixels(8), pixels(20), pixels(16));

        estimate = new TextView(this);
        estimate.setTextColor(Palette.MUTED);
        estimate.setTextSize(13);
        estimate.setText(R.string.scrape_many_counting);
        foot.addView(estimate);

        quotaLine = new TextView(this);
        quotaLine.setTextColor(Palette.MUTED);
        quotaLine.setTextSize(13);
        quotaLine.setVisibility(View.GONE);
        foot.addView(quotaLine);

        progressLine = new TextView(this);
        progressLine.setTextColor(Palette.TEXT);
        progressLine.setTextSize(15);
        progressLine.setPadding(0, pixels(8), 0, 0);
        progressLine.setVisibility(View.GONE);
        foot.addView(progressLine);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setVisibility(View.GONE);
        foot.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        start = new Button(this);
        start.setText(R.string.scrape_many_start);
        start.setEnabled(false);
        start.setOnClickListener(v -> {
            if (running) stop();
            else begin();
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = pixels(8);
        foot.addView(start, params);

        return foot;
    }

    // --- counting --------------------------------------------------------------------

    /**
     * How many games the current settings come to, and what they will cost.
     *
     * Off the UI thread because the first ask for a scope walks the whole tree
     * - a second and a half for 259 files, measured, and much worse over a
     * provider having a slow day - and because {@code Sweep.select} reads the
     * store and the media folder for every row.
     */
    private void recount() {
        final int token = ++countToken;

        estimate.setText(R.string.scrape_many_counting);
        start.setEnabled(false);

        Work.alone("scrape-count", () -> {
            List<Entry> entries;

            try {
                entries = entriesFor(scope);
            } catch (IOException e) {
                Log.w(TAG, "cannot list the games to scrape", e);
                runOnUiThread(() -> {
                    if (token != countToken) return;
                    estimate.setText(R.string.scrape_many_cannot_list);
                });
                return;
            }

            List<Entry> selected = Sweep.select(this, entries, only);

            runOnUiThread(() -> {
                // A count that finished after somebody moved the setting on is
                // an answer to a question nobody is asking any more.
                if (token != countToken) return;

                chosen = selected;
                showEstimate();
            });
        });
    }

    private void showEstimate() {
        int games = chosen.size();
        int requests = games * (1 + Scrapers.wanted(this).requests());

        estimate.setText(getResources().getQuantityString(
                R.plurals.scrape_many_estimate, games, games, requests));
        start.setEnabled(games > 0);

        showQuota();
    }

    /**
     * What is left of the day, when the service has said.
     *
     * It has not before the first reply of the session - the counters arrive
     * with an answer, not before one - so this is hidden rather than showing
     * an honest but useless "unknown", and appears once the run is under way.
     */
    private void showQuota() {
        Quota quota = provider.quota();
        int left = quota == null ? -1 : quota.left();

        if (left < 0) {
            quotaLine.setVisibility(View.GONE);
            return;
        }

        quotaLine.setText(getString(R.string.scrape_many_quota, left));
        quotaLine.setVisibility(View.VISIBLE);
    }

    private List<Entry> entriesFor(Scope scope) throws IOException {
        List<Entry> cached = walked.get(scope);
        if (cached != null) return cached;

        List<Entry> found;

        switch (scope) {
            case FOLDER:
                found = Listing.folder(getContentResolver(), folder);
                break;

            case LIBRARY:
                Uri root = Storage.contentFolder(this);
                if (root == null) throw new IOException("no content folder is granted");
                found = Listing.everythingUnder(getContentResolver(), root);
                break;

            case BELOW:
            default:
                found = Listing.everythingUnder(getContentResolver(), folder);
                break;
        }

        walked.put(scope, found);
        return found;
    }

    // --- the run ---------------------------------------------------------------------

    private void begin() {
        running = true;
        cancelled = false;

        settings.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        bar.setMax(chosen.size());
        bar.setProgress(0);
        progressLine.setVisibility(View.VISIBLE);
        progressLine.setText(R.string.scrape_many_working);
        start.setText(android.R.string.cancel);

        List<Entry> entries = chosen;

        // Work.alone rather than the pool: this is twenty minutes and would
        // hold a lane the short work wants.
        Work.alone("scrape-many", () -> {
            Sweep.Tally tally = Sweep.run(this, provider, new Http.Real(), entries,
                                          Scrapers.wanted(this), conflicts, watcher);

            runOnUiThread(() -> finished(tally));
        });
    }

    private void stop() {
        cancelled = true;
        start.setEnabled(false);
        progressLine.setText(R.string.scrape_many_stopping);
    }

    private final Sweep.Watcher watcher = new Sweep.Watcher() {

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public void at(int done, int total, String game) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                bar.setProgress(done);
                progressLine.setText(getString(R.string.scrape_many_at, done + 1, total, game));
                showQuota();
            });
        }

        @Override
        public Sweep.Choice chooseFrom(List<Candidate> found, String game) {
            return ask(found, game);
        }
    };

    /**
     * The chooser, from the sweep thread.
     *
     * Called <b>off</b> the UI thread and blocks until somebody answers: the
     * dialog is posted to the main thread and the sweep waits on a queue of
     * one. Deliberately without a timeout - a run that skipped a game because
     * nobody was looking would be worse than one that waits, and Cancel is on
     * screen behind the dialog.
     *
     * A queue rather than a latch and a field, because it carries the answer
     * as well as the fact of one and needs no synchronisation of its own.
     */
    private Sweep.Choice ask(List<Candidate> found, String game) {
        BlockingQueue<Sweep.Choice> answer = new ArrayBlockingQueue<>(1);

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                answer.offer(Sweep.Choice.skipTheRest());
                return;
            }
            chooser(found, game, answer).show();
        });

        try {
            // Polled rather than taken, and the difference is a hung thread:
            // an activity destroyed while the dialog is up never answers, and
            // a plain take() would leave the sweep waiting for ever with the
            // screen already gone. There is still no timeout anybody can
            // reach - this only gives up once the run has been cancelled,
            // which onDestroy does.
            while (true) {
                Sweep.Choice choice = answer.poll(CHOICE_POLL_MS, TimeUnit.MILLISECONDS);
                if (choice != null) return choice;
                if (cancelled) return Sweep.Choice.skipTheRest();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Sweep.Choice.skipTheRest();
        }
    }

    /** How often the wait for an answer looks up to see whether the run was
     *  cancelled underneath it. */
    private static final long CHOICE_POLL_MS = 200;

    private AlertDialog chooser(List<Candidate> found, String game,
                                BlockingQueue<Sweep.Choice> answer) {
        List<String> labels = new ArrayList<>();
        for (Candidate candidate : found) labels.add(candidate.describe());

        AlertDialog dialog = new AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(game)
                .setItems(labels.toArray(new String[0]),
                          (d, which) -> answer.offer(Sweep.Choice.of(found.get(which))))
                .setNegativeButton(R.string.scrape_many_skip_one,
                                   (d, which) -> answer.offer(Sweep.Choice.skip()))
                // The way out of a long tail of conflicts. Without it the only
                // escape from thirty of these is cancelling the whole run and
                // losing the games still ahead of it.
                .setPositiveButton(R.string.scrape_many_skip_rest,
                                   (d, which) -> answer.offer(Sweep.Choice.skipTheRest()))
                .setCancelable(false)
                .create();

        // Dismissed some other way - the back gesture, the activity going -
        // still has to answer, or the sweep thread waits for ever.
        dialog.setOnDismissListener(d -> answer.offer(Sweep.Choice.skip()));
        return dialog;
    }

    // --- what it came to ---------------------------------------------------------------

    private void finished(Sweep.Tally tally) {
        if (isFinishing() || isDestroyed()) return;

        running = false;

        bar.setProgress(tally.done);
        progressLine.setText(summary(tally));
        showQuota();

        start.setText(R.string.scrape_many_close);
        start.setEnabled(true);
        start.setOnClickListener(v -> finish());
    }

    /**
     * The five numbers, and why it ended if it did not simply end.
     *
     * Five rather than done-and-not-done because five different things happen
     * to a collection: a hundred unknown games is the service's coverage, a
     * hundred ambiguous ones is an afternoon with the chooser, and a hundred
     * failures is something wrong. Lumping them together would hide which.
     */
    private String summary(Sweep.Tally tally) {
        StringBuilder text = new StringBuilder();

        if (tally.stopped != null) {
            text.append(reasonFor(tally.stopped)).append("\n\n");
        } else if (tally.cancelled) {
            text.append(getString(R.string.scrape_many_cancelled)).append("\n\n");
        }

        text.append(counted(R.plurals.scrape_many_scraped, tally.scraped));

        // The four below appear only when they happened. A run that scraped
        // everything cleanly should say one thing, not five things four of
        // which are zero.
        if (tally.media > 0) line(text, R.plurals.scrape_many_media, tally.media);
        if (tally.ambiguous > 0) line(text, R.plurals.scrape_many_ambiguous, tally.ambiguous);
        if (tally.unknown > 0) line(text, R.plurals.scrape_many_unknown, tally.unknown);
        if (tally.yours > 0) line(text, R.plurals.scrape_many_yours, tally.yours);
        if (tally.failed > 0) line(text, R.plurals.scrape_many_failed, tally.failed);

        // Anything left undone is found again by "not scraped yet", which is
        // the whole of this feature's resume story and is worth saying rather
        // than leaving somebody to work out.
        if (!tally.complete() || tally.ambiguous > 0) {
            text.append("\n\n").append(getString(R.string.scrape_many_resume));
        }

        return text.toString();
    }

    private void line(StringBuilder text, int plural, int count) {
        text.append('\n').append(counted(plural, count));
    }

    private String counted(int plural, int count) {
        return getResources().getQuantityString(plural, count, count);
    }

    /** Per kind, so that a spent allowance does not read as "something went
     *  wrong" - the same wording the one-game path uses. */
    private String reasonFor(ScrapeException e) {
        Log.w(TAG, "the sweep stopped: " + e.kind, e);

        switch (e.kind) {
            case QUOTA_EXCEEDED:  return getString(R.string.scrape_failed_quota);
            case BAD_CREDENTIALS: return getString(R.string.scrape_failed_login);
            case CLOSED:          return getString(R.string.scrape_failed_closed);
            default:              return getString(R.string.scrape_failed_network);
        }
    }

    /** The entries array holds string references, so it has to be read as a
     *  typed array to get the ids rather than the resolved text - the text is
     *  wanted one at a time, in whatever language is current. */
    private int[] labelsFor(int array) {
        android.content.res.TypedArray typed = getResources().obtainTypedArray(array);
        int[] ids = new int[typed.length()];

        for (int at = 0; at < ids.length; at++) ids[at] = typed.getResourceId(at, 0);
        typed.recycle();

        return ids;
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
