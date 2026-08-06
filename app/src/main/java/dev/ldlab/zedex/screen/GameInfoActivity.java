package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.view.SafeArea;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;

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

    private static final int TEXT = 0xffededf2;
    private static final int MUTED = 0xff9a9aa5;
    private static final int BACKING = 0xff0f0e13;

    /** Roughly what the artwork is drawn at here - a whole screen's worth,
     *  where the pane wanted a thumbnail. */
    private static final int ARTWORK_TARGET_DP = 360;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ImageView artwork;
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

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);

        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(BACKING);
        page.addView(content(name));
        setContentView(page);

        SafeArea.fit(findViewById(android.R.id.content));

        if (path != null) load(path);
    }

    private View content(String name) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pixels(24), pixels(24), pixels(24), pixels(24));

        // CENTER_INSIDE, as the pane's own picture is: box art cropped to fit
        // a box is the thing this screen exists to show properly.
        artwork = new ImageView(this);
        artwork.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        artwork.setVisibility(View.GONE);
        artwork.setContentDescription(null);
        column.addView(artwork, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, artworkHeight()));

        // The filename until the store answers with a scraped name, exactly
        // as a row does - this screen is never blank while it waits.
        title = new TextView(this);
        title.setTextColor(TEXT);
        title.setTextSize(22);
        title.setPadding(0, pixels(16), 0, 0);
        title.setText(name);
        column.addView(title, wrap());

        filename = new TextView(this);
        filename.setTextColor(MUTED);
        filename.setTextSize(13);
        filename.setPadding(0, pixels(4), 0, 0);
        filename.setVisibility(View.GONE);
        column.addView(filename, wrap());

        facts = new TextView(this);
        facts.setTextColor(MUTED);
        facts.setTextSize(14);
        facts.setPadding(0, pixels(12), 0, 0);
        facts.setVisibility(View.GONE);
        column.addView(facts, wrap());

        // No placeholder: a collection like this one is mostly unscraped, and
        // an empty screen that says the filename is a truthful answer to
        // "what is known about this?" - "nothing more" needs no label.
        description = new TextView(this);
        description.setTextColor(TEXT);
        description.setTextSize(15);
        description.setLineSpacing(pixels(4), 1f);
        description.setPadding(0, pixels(20), 0, 0);
        description.setVisibility(View.GONE);
        column.addView(description, wrap());

        return column;
    }

    /**
     * The store and the artwork, both off the UI thread and both landing
     * through the same post so a screen that has gone away draws nothing.
     */
    private void load(String path) {
        new Thread(() -> {
            Meta meta = Metadata.forPath(this, path);
            Bitmap picture = decode(Artwork.picture(this, path));

            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                show(meta, picture);
            });
        }).start();
    }

    private void show(Meta meta, Bitmap picture) {
        if (picture != null) {
            artwork.setImageBitmap(picture);
            artwork.setVisibility(View.VISIBLE);
        }

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
     * Decoded at roughly {@link #ARTWORK_TARGET_DP}, the same two-pass way
     * the library's rows decode theirs: a scraped cover can be far larger
     * than any screen wants, and the whole file is not worth holding to draw
     * a fraction of it.
     */
    private Bitmap decode(Uri picture) {
        if (picture == null) return null;

        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;

            try (InputStream probe = getContentResolver().openInputStream(picture)) {
                if (probe == null) return null;
                BitmapFactory.decodeStream(probe, null, bounds);
            }

            int target = pixels(ARTWORK_TARGET_DP);
            int longest = Math.max(bounds.outWidth, bounds.outHeight);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, longest / Math.max(1, target));

            try (InputStream in = getContentResolver().openInputStream(picture)) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Exception e) {
            // A picture that will not read is no picture; the rest of the
            // screen is still worth showing.
            return null;
        }
    }

    /**
     * How tall the artwork is allowed to be: {@link #ARTWORK_TARGET_DP}, but
     * never more than a bit under half the window.
     *
     * The cap is the whole of it. 360dp of picture is most of a landscape
     * phone's height, so the first version of this filled the screen with box
     * art and put every fact below the fold - a details screen showing no
     * details until you scrolled. A fraction of the window is what keeps the
     * name and the facts on the first screenful in both orientations, and the
     * dp figure is what stops the picture growing silly on a tablet.
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
