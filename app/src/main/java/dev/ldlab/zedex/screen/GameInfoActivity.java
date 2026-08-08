package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.R;
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
public final class GameInfoActivity extends Activity {

    /** The game's path relative to the content tree - {@link Metadata#relativePath}. */
    public static final String EXTRA_PATH = "dev.ldlab.zedex.extra.GAME_PATH";

    /** The file's own name, which is all this screen has to show until the store answers. */
    public static final String EXTRA_NAME = "dev.ldlab.zedex.extra.GAME_NAME";


    /** Roughly what the artwork is drawn at here - a whole screen's worth,
     *  where the pane wanted a thumbnail. */
    private static final int ARTWORK_TARGET_DP = 360;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Gallery gallery;

    /** Beside {@link #gallery}, over its top corner - see {@link #media}.
     *  Shown only once {@link #loadManualButton} answers this game has one. */
    private ImageButton manualButton;

    /** The path this screen was opened with - kept so a tap on a page can
     *  open {@link MediaViewerActivity} against the same game, rather than
     *  the intent extra being read a second time. */
    private String path;

    private TextView title;
    private TextView filename;
    private TextView facts;
    private TextView description;

    /** Every screen speaks the chosen language; see {@link Language}. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Language.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The manifest label is resolved in the phone's language rather than
        // this screen's, so the title is set here; see Language.
        setTitle(R.string.library_info);

        // The way back to the library. This screen is reached from a button
        // in the pane rather than from a list, so the system Back gesture is
        // the only other way out and not everyone uses it.
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);

        setContentView(page(name));
        SafeArea.fit(findViewById(android.R.id.content));

        if (path != null) {
            load(path);
            gallery.load(path);
            loadManualButton(path);
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
        new Thread(() -> {
            Uri manual;
            try {
                manual = Artwork.manual(this, path);
            } catch (Exception e) {
                manual = null;
            }

            Uri result = manual;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == null) return;

                manualButton.setVisibility(View.VISIBLE);
                manualButton.setOnClickListener(v -> Manuals.open(this, result));
            });
        }).start();
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

        return column;
    }

    /**
     * The store alone - {@link Gallery#load} is what resolves the pictures
     * and the video now, on a thread of its own, so this only has the words
     * left to ask for. Still off the UI thread and still landing through the
     * same post-and-check a screen that has gone away is guarded by.
     */
    private void load(String path) {
        new Thread(() -> {
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
        }).start();
    }

    private void show(Meta meta) {
        if (meta == null) return;

        if (meta.name != null && !meta.name.isEmpty()) {
            filename.setText(title.getText());
            filename.setVisibility(View.VISIBLE);
            title.setText(meta.name);
        }

        String line = factsLine(meta);
        if (line != null) {
            facts.setText(line);
            facts.setVisibility(View.VISIBLE);
        }

        if (meta.desc != null && !meta.desc.isEmpty()) {
            description.setText(meta.desc.trim());
            description.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Developer, publisher, year, genre and players, joined the same way the
     * pane's own line is and skipping whatever is not known - which, in a
     * collection scraped by ES-DE, is usually most of it.
     */
    private static String factsLine(Meta meta) {
        StringBuilder text = new StringBuilder();

        append(text, meta.developer);
        append(text, meta.publisher);
        append(text, meta.year());
        append(text, meta.genre);
        append(text, meta.players);

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
