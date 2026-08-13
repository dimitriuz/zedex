package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.work.Work;
import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.view.QuickBar;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.library.ui.Gallery;
import dev.ldlab.zedex.library.ui.Manuals;
import dev.ldlab.zedex.view.SafeArea;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Everything known about one game, on a screen of its own.
 *
 * The library's pane is a strip beside a grid: it has room for a few facts,
 * one line each, and it earns that room by not trying to show a description
 * as well. A scraped description runs to three paragraphs often enough that
 * the pane's own version of it was squeezed to 26px in landscape - a scroll
 * bar with nothing to scroll in - which is what this screen exists to fix.
 * The magnifier in the pane opens it; see {@code LibraryActivity.showGameInfo}.
 *
 * The media is <em>fixed</em> and the words scroll under it, in both
 * orientations: the picture is what identifies the game, so it is the last
 * thing that should slide away while somebody reads. Landscape puts the two
 * side by side and portrait stacks them, which is the same split the library
 * itself makes.
 *
 * Addressed by the game's path relative to the content tree, not by a parsed
 * {@link Meta}: that path is the key both the metadata store and the artwork
 * are found by, and looking both up here rather than carrying a copy through
 * an Intent means this screen cannot be showing something the store no longer
 * says. Both lookups are a read of another app's storage, so both happen off
 * the UI thread.
 */
public final class GameInfoActivity extends ZedexActivity {

    /** The game's path relative to the content tree - {@link Metadata#relativePath}. */
    public static final String EXTRA_PATH = "dev.ldlab.zedex.extra.GAME_PATH";

    /** The file's own name, which is all this screen has to show until the store answers. */
    public static final String EXTRA_NAME = "dev.ldlab.zedex.extra.GAME_NAME";

    /**
     * The game's own file, when this screen was opened from the library.
     *
     * <b>Which bar this screen wears turns on it.</b> There are two ways in
     * and they are two different states: from the machine's own ⓘ, where a
     * game is running and what is wanted is the way back to it - the quick
     * bar; and from the library's pane, where nothing is running and what is
     * wanted is Play. Absent means the first, which is also what makes the
     * machine's own call need no change.
     *
     * The rule underneath, which the panel follows too: the bar reflects
     * whether there is a machine behind this screen.
     */
    public static final String EXTRA_URI = "dev.ldlab.zedex.extra.GAME_URI";


    /** Roughly what the artwork is drawn at here - a whole screen's worth,
     *  where the pane wanted a thumbnail. */
    private static final int ARTWORK_TARGET_DP = 360;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Gallery gallery;

    /** Beside {@link #gallery}, over its top corner - see {@link #media}.
     *  Shown only once {@link #loadManualButton} answers this game has one. */
    private ImageButton manualButton;

    /** Under it, and only for a game a tune was fetched for. */
    private ImageButton musicButton;

    /** The path this screen was opened with - kept so a tap on a page can
     *  open {@link MediaViewerActivity} against the same game, rather than
     *  the intent extra being read a second time. */
    private String path;

    private TextView title;
    private TextView filename;
    private TextView facts;
    private TextView description;

    /** The rows under the description: credits, price, series, compilations.
     *  A column of its own because it is rebuilt whenever the store answers. */
    private LinearLayout extras;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The manifest label is resolved in the phone's language rather than
        // this screen's, so the title is set here; see Language. Still set
        // although the bar below replaces the title strip: the task switcher
        // reads it from here.
        setTitle(R.string.library_info);

        // <b>A quick bar where the title was.</b> On a handheld the panel
        // shows these details beside the machine and carries the same four
        // icons - see EmulatorActivity.applyBarMode - and a title saying
        // "Game details" over a screen that obviously is the game's details
        // earns nothing next to four things you can do. The same four, in the
        // same order, so the two arrangements are one screen in two places
        // rather than two screens.
        if (getActionBar() != null) getActionBar().hide();

        path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);

        setContentView(withBar(page(name)));
        fitToSafeArea();

        if (path != null) {
            load(path);
            gallery.load(path);
            loadManualButton(path);
        loadMusicButton(path);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // One of the three times a video must not be left running - see
        // CLAUDE.md - and now this screen's own gallery can hold one, not
        // only the pane's.
        gallery.release();
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Media on one side and the words on the other - beside in landscape,
     * above in portrait - with only the words in a {@link ScrollView}. The
     * media takes a fixed share and stays where it is.
     */
    /**
     * The page under a bar of its own.
     *
     * The bar is built here rather than borrowed: {@code EmulatorActivity}'s
     * belongs to that activity and is lent to the panel by a {@code
     * Presentation} that lives on its window, and there is no lending across
     * two activities. Four buttons is a small thing to build twice; a shared
     * one would be a seam between two screens for the sake of four lines.
     *
     * What each does is what the panel's own four do, from a screen rather
     * than from beside the machine - see the comments on each.
     */
    private View withBar(View page) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Palette.BACKING);

        String file = getIntent().getStringExtra(EXTRA_URI);

        // No machine behind this screen: the game's own actions instead of
        // the machine's. Play and the manual, which is the pane's own row
        // without its Details button - this is the details.
        if (file != null) {
            column.addView(actionRow(Uri.parse(file)), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            column.addView(page, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            return column;
        }

        QuickBar bar = new QuickBar(this);

        // Back to the machine, which for a screen means going away: this was
        // opened from the machine's own bar and the machine is behind it.
        bar.addAction(R.drawable.ic_chip, getString(R.string.library_machine),
                      this::finish);

        // The manual, if there is one. Added always and hidden until it
        // resolves - see loadManualButton, which already knows how to answer
        // that question off the UI thread.
        barManual = bar.addAction(R.drawable.ic_manual, getString(R.string.library_manual),
                                  this::openTheManual);
        barManual.setVisibility(View.GONE);

        // The machine's own menu, which only the machine can open: this screen
        // stands aside and asks for it, since a sheet built over another
        // activity's window is not something a second activity can raise.
        bar.addAction(R.drawable.ic_menu, getString(R.string.menu_button), () -> {
            startActivity(new Intent(this, EmulatorActivity.class)
                    .putExtra(EmulatorActivity.EXTRA_OPEN_MENU, true));
            finish();
        });

        // And out of the game altogether, the same cross the panel's bar
        // carries: what it does is close the content, and where it leaves you
        // is the library.
        bar.addAction(R.drawable.ic_close, getString(R.string.library_title), () -> {
            startActivity(new Intent(this, LibraryActivity.class)
                    .putExtra(LibraryActivity.EXTRA_FROM_MENU, true)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                              | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });

        column.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        column.addView(page, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return column;
    }

    /**
     * Play and the manual, side by side - the pane's own row, without the
     * Details button it does not need here.
     *
     * Text rather than icons, because that is what the pane uses and this is
     * the same row in another place; the quick bar's icons are for the
     * machine's controls, which these are not.
     */
    private View actionRow(Uri file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(pixels(12), pixels(8), pixels(12), pixels(8));

        android.widget.Button play = new android.widget.Button(this);
        play.setText(R.string.library_play);
        play.setOnClickListener(v -> {
            // The same hand-over a row in the library makes - see
            // LibraryActivity.openGame, whose own comment explains why the
            // grant travels with it.
            startActivity(new Intent(Intent.ACTION_VIEW, file, this, EmulatorActivity.class)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(EmulatorActivity.EXTRA_LIBRARY_PATH, path));
            finish();
        });

        row.addView(play, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        rowManual = new android.widget.Button(this);
        rowManual.setText(R.string.library_manual);
        rowManual.setOnClickListener(v -> openTheManual());
        rowManual.setVisibility(View.GONE);

        row.addView(rowManual, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // And the way out, which this row has to carry because nothing else
        // on the screen does any more.
        //
        // <b>The title strip it replaced had the Up chevron.</b> Taking the
        // strip away took that with it, and left the system's own Back as the
        // only way off this screen - which on a two-screen handheld is not a
        // way at all: measured on an AYN Thor Lite with the panel switched
        // off, the second display's launcher is the top-focused one and Back
        // goes there, so it does nothing whatever is on the first screen.
        // Every other version of this screen already has its own way out -
        // the quick bar's cross - and this one had none.
        android.widget.Button close = new android.widget.Button(this);
        close.setText(R.string.library_title);
        close.setOnClickListener(v -> finish());

        row.addView(close, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        return row;
    }

    /** The action row's own manual button - the same answer reveals it as
     *  reveals the bar's. */
    private android.widget.Button rowManual;

    /** The bar's own manual button, revealed by {@link #loadManualButton}
     *  along with the one in the corner of the artwork. */
    private ImageButton barManual;

    /** Whatever {@link #loadManualButton} resolved, or null - the bar's icon
     *  is built before that answer can arrive. */
    private Uri manual;

    /** What the bar's manual icon does - the same hand-over the corner button
     *  already makes, on this screen's own display. */
    private void openTheManual() {
        if (manual != null) Manuals.open(this, manual, getDisplay());
    }

    private View page(String name) {
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Palette.BACKING);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(words(name));

        if (landscape) {
            // Words left, media right, as asked - and the media a shade
            // under half, so a description still gets the wider column.
            root.addView(scroller, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 3f));
            root.addView(media(), new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 2f));
        } else {
            root.addView(media(), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, artworkHeight()));
            root.addView(scroller, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        return root;
    }

    /**
     * The pictures, one per page, swiped between, and the video last if
     * there is one - see {@link Gallery}, which this used to build by hand
     * before the pane and {@code MediaViewerActivity} both wanted the same
     * pager and a second copy stopped being worth it. The manual button
     * floats over its top corner, the same way {@code
     * MediaViewerActivity}'s own sound button does over its gallery - there
     * is no toolbar here for either to sit in.
     *
     * A tap on a picture or the video opens {@link MediaViewerActivity} at
     * whichever page was tapped, in place of the {@code Dialog} this screen
     * used to open itself - the viewer is swipeable across the rest of the
     * gallery, which a dialog showing one bitmap never was. The manual is
     * not a page of the gallery any more, so a tap on its own button opens
     * it directly through {@link Manuals#open} instead.
     */
    private View media() {
        FrameLayout box = new FrameLayout(this);

        gallery = new Gallery(this);
        gallery.setPictureTargetPx(pixels(ARTWORK_TARGET_DP));
        gallery.setOnPageTapped(this::openViewer);
        box.addView(gallery, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        manualButton = new ImageButton(this);
        manualButton.setImageResource(R.drawable.ic_manual);
        manualButton.setBackgroundColor(0x80000000);
        manualButton.setColorFilter(0xffffffff);
        manualButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        manualButton.setContentDescription(getString(R.string.library_manual));
        manualButton.setVisibility(View.GONE);

        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                pixels(48), pixels(48), Gravity.TOP | Gravity.END);
        buttonParams.topMargin = pixels(16);
        buttonParams.rightMargin = pixels(16);
        box.addView(manualButton, buttonParams);

        musicButton = new ImageButton(this);
        musicButton.setImageResource(R.drawable.ic_music);
        musicButton.setBackgroundColor(0x80000000);
        musicButton.setColorFilter(0xffffffff);
        musicButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        musicButton.setContentDescription(getString(R.string.music_title));
        musicButton.setVisibility(View.GONE);

        // Under the manual, as in the pane: two buttons across the top of a
        // cover crowd the artwork somebody came to look at.
        FrameLayout.LayoutParams musicParams = new FrameLayout.LayoutParams(
                pixels(48), pixels(48), Gravity.TOP | Gravity.END);
        musicParams.topMargin = pixels(16) + pixels(48) + pixels(8);
        musicParams.rightMargin = pixels(16);
        box.addView(musicButton, musicParams);

        return box;
    }

    private void openViewer(int index) {
        if (path == null) return;

        startActivity(new Intent(this, MediaViewerActivity.class)
                .putExtra(MediaViewerActivity.EXTRA_PATH, path)
                .putExtra(MediaViewerActivity.EXTRA_INDEX, index));
    }

    /**
     * Whether this game has a manual - {@link Artwork#manual} is a SAF
     * query, the same round trip {@link #load} makes for the words, so it
     * gets the same treatment: a thread of its own, and an answer only
     * applied if this screen is still here to receive it.
     */
    private void loadManualButton(String path) {
        Work.run("manual", () -> {
            Uri found;
            try {
                found = Artwork.manual(this, path);
            } catch (Exception e) {
                found = null;
            }

            Uri result = found;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == null) return;

                // Remembered for the bar as well as wired to the corner
                // button: the bar's icon is added before this answer arrives -
                // it has to be, the bar is built with the page - so it is
                // hidden until there is something for it to open.
                manual = result;

                // The bar's icon, and not the one in the corner of the
                // artwork: the same button twice on one screen is what the
                // corner button was taken off the panel for, and this screen
                // now has the same bar. The corner one is left built and
                // hidden rather than deleted - the layout it sits in is the
                // same one the pane and the panel share.
                if (barManual != null) barManual.setVisibility(View.VISIBLE);
                if (rowManual != null) rowManual.setVisibility(View.VISIBLE);
            });
        });
    }

    /**
     * Whether this game has music, and where tapping it goes.
     *
     * The emulator screen, because a tune is the Spectrum running the game's
     * own driver and that is where the Spectrum is - see {@code media.Music},
     * which puts whatever was loaded there aside and gives it back.
     */
    private void loadMusicButton(String path) {
        Work.run("music", () -> {
            java.io.File tune;
            try {
                tune = Artwork.music(this, path);
            } catch (Exception e) {
                tune = null;
            }

            boolean any = tune != null;
            handler.post(() -> {
                if (isFinishing() || isDestroyed() || !any) return;

                musicButton.setVisibility(View.VISIBLE);
                musicButton.setOnClickListener(v -> startActivity(
                        new Intent(this, dev.ldlab.zedex.EmulatorActivity.class)
                                .putExtra(dev.ldlab.zedex.EmulatorActivity.EXTRA_MUSIC, path)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP)));
            });
        });
    }

    private View words(String name) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pixels(24), pixels(24), pixels(24), pixels(24));

        // The filename until the store answers with a scraped name, exactly
        // as a row does - this screen is never blank while it waits.
        title = new TextView(this);
        title.setTextColor(Palette.TEXT);
        title.setTextSize(22);
        title.setText(name);
        column.addView(title, wrap());

        filename = new TextView(this);
        filename.setTextColor(Palette.MUTED);
        filename.setTextSize(13);
        filename.setPadding(0, pixels(4), 0, 0);
        filename.setVisibility(View.GONE);
        column.addView(filename, wrap());

        facts = new TextView(this);
        facts.setTextColor(Palette.MUTED);
        facts.setTextSize(14);
        facts.setPadding(0, pixels(12), 0, 0);
        facts.setVisibility(View.GONE);
        column.addView(facts, wrap());

        // No placeholder: a collection like this one is mostly unscraped, and
        // an empty screen that says the filename is a truthful answer to
        // "what is known about this?" - "nothing more" needs no label.
        description = new TextView(this);
        description.setTextColor(Palette.TEXT);
        description.setTextSize(15);
        description.setLineSpacing(pixels(4), 1f);
        description.setPadding(0, pixels(20), 0, 0);
        description.setVisibility(View.GONE);
        column.addView(description, wrap());

        // Under the description, because these are the long tail: a quarter of
        // entries have a price, six per cent a series, and a row that is
        // usually absent belongs below the one thing somebody came to read.
        extras = new LinearLayout(this);
        extras.setOrientation(LinearLayout.VERTICAL);
        extras.setPadding(0, pixels(20), 0, 0);
        column.addView(extras, wrap());

        return column;
    }

    /**
     * A labelled fact, or nothing at all.
     *
     * Nothing at all is the common case - see {@link #extras} - and an empty
     * row with a heading over it would claim the database was asked and had
     * no answer, when mostly it was never asked.
     */
    private void extra(int label, String value) {
        if (value == null || value.trim().isEmpty()) return;

        TextView heading = new TextView(this);
        heading.setText(label);
        heading.setTextColor(Palette.MUTED);
        heading.setTextSize(12);
        heading.setPadding(0, pixels(12), 0, 0);
        extras.addView(heading, wrap());

        TextView text = new TextView(this);
        text.setText(value.trim());
        text.setTextColor(Palette.TEXT);
        text.setTextSize(15);
        text.setLineSpacing(pixels(3), 1f);
        extras.addView(text, wrap());
    }

    /** The titles of other entries, comma separated. The ids travel with them
     *  in the store and nothing reads them yet - see {@code Meta.Link}. */
    private static String titlesOf(java.util.List<Meta.Link> links) {
        if (links == null || links.isEmpty()) return null;

        StringBuilder text = new StringBuilder();

        for (Meta.Link link : links) {
            if (text.length() > 0) text.append(", ");
            text.append(link.title);
        }

        return text.toString();
    }

    /**
     * The store alone - {@link Gallery#load} is what resolves the pictures
     * and the video now, on a thread of its own, so this only has the words
     * left to ask for. Still off the UI thread and still landing through the
     * same post-and-check a screen that has gone away is guarded by.
     */
    private void load(String path) {
        Work.run("game-info", () -> {
            // Asked for, and waited for: forPath answers from memory and
            // never parses, so a screen opened before the store has been read
            // - straight from ES-DE, most often - would otherwise show a game
            // about which nothing is known. This is already a thread of its
            // own, which is the only place waiting is allowed.
            Metadata.ensureLoaded(getApplicationContext());

            Meta meta = Metadata.forPath(this, path);

            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                show(meta);
            });
        });
    }

    private void show(Meta meta) {
        if (meta == null) return;

        if (meta.name != null && !meta.name.isEmpty()) {
            filename.setText(title.getText());
            filename.setVisibility(View.VISIBLE);
            title.setText(meta.name);
        }

        String line = factsLine(this, meta);
        if (line != null) {
            facts.setText(line);
            facts.setVisibility(View.VISIBLE);
        }

        if (meta.desc != null && !meta.desc.isEmpty()) {
            description.setText(meta.desc.trim());
            description.setVisibility(View.VISIBLE);
        }

        extras.removeAllViews();
        extra(R.string.info_authors, String.join(", ", meta.authors));
        extra(R.string.info_price, meta.price);
        extra(R.string.info_series, seriesLine(meta));
        extra(R.string.info_compilations, titlesOf(meta.compilations));
        extra(R.string.info_contents, titlesOf(meta.contents));
    }

    /** The series' name, and the rest of it after a dash where the record
     *  names any - "Chaos — Lords of Chaos". */
    private static String seriesLine(Meta meta) {
        String rest = titlesOf(meta.seriesGames);

        if (meta.series == null) return rest;
        return rest == null ? meta.series : meta.series + " — " + rest;
    }

    /**
     * Developer, publisher, year, genre and players, joined the same way the
     * pane's own line is and skipping whatever is not known - which, in a
     * collection scraped by ES-DE, is usually most of it.
     */
    private static String factsLine(android.content.Context context, Meta meta) {
        StringBuilder text = new StringBuilder();

        append(text, meta.developer);
        append(text, meta.publisher);
        append(text, meta.year());
        append(text, meta.genre);
        append(text, meta.players);

        // Last, after the facts about the game: this one is the reader's own
        // history. See Facts.playedLabel, which the pane and the panel's own
        // game info both render through too, so the three lines cannot come to
        // word it differently.
        append(text, dev.ldlab.zedex.library.ui.Facts.playedLabel(context, meta));

        return text.length() > 0 ? text.toString() : null;
    }

    private static void append(StringBuilder text, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (text.length() > 0) text.append(" · ");
        text.append(value.trim());
    }

    /**
     * How tall the artwork is allowed to be in portrait: {@link
     * #ARTWORK_TARGET_DP}, but never more than a bit under half the window.
     *
     * The cap is the whole of it. 360dp of picture is most of a landscape
     * phone's height, so the first version of this filled the screen with box
     * art and put every fact below the fold - a details screen showing no
     * details until you scrolled. A fraction of the window is what keeps the
     * name and the facts on the first screenful, and the dp figure is what
     * stops the picture growing silly on a tablet.
     */
    private int artworkHeight() {
        int window = getResources().getDisplayMetrics().heightPixels;
        return Math.min(pixels(ARTWORK_TARGET_DP), Math.round(window * 0.45f));
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
