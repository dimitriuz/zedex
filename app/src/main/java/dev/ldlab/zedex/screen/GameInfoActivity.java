package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.view.SafeArea;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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

    private static final int TEXT = 0xffededf2;
    private static final int MUTED = 0xff9a9aa5;
    private static final int BACKING = 0xff0f0e13;

    /** The dots under a gallery of more than one: the page you are on, and the rest. */
    private static final int DOT_ON = 0xffededf2;
    private static final int DOT_OFF = 0x40ededf2;

    /** Roughly what the artwork is drawn at here - a whole screen's worth,
     *  where the pane wanted a thumbnail. */
    private static final int ARTWORK_TARGET_DP = 360;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private RecyclerView gallery;
    private GalleryAdapter galleryAdapter;
    private LinearLayout dots;

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

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);

        setContentView(page(name));
        SafeArea.fit(findViewById(android.R.id.content));

        if (path != null) load(path);
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
        root.setBackgroundColor(BACKING);
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
     * The pictures, one per page, swiped between - a {@link PagerSnapHelper}
     * on a horizontal recycler, which is a pager without a dependency for it,
     * and the recycler is already here for the library's own list.
     *
     * The dots appear only when there is more than one; a single cover with a
     * single dot under it says nothing except that somebody wrote a widget.
     */
    private View media() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        gallery = new RecyclerView(this);
        gallery.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        galleryAdapter = new GalleryAdapter();
        gallery.setAdapter(galleryAdapter);
        new PagerSnapHelper().attachToRecyclerView(gallery);

        gallery.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView view, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) markDots();
            }
        });

        box.addView(gallery, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, pixels(8), 0, pixels(8));
        dots.setVisibility(View.GONE);
        box.addView(dots, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return box;
    }

    private View words(String name) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pixels(24), pixels(24), pixels(24), pixels(24));

        // The filename until the store answers with a scraped name, exactly
        // as a row does - this screen is never blank while it waits.
        title = new TextView(this);
        title.setTextColor(TEXT);
        title.setTextSize(22);
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
     * The store and the pictures, both off the UI thread and both landing
     * through the same post so a screen that has gone away draws nothing.
     * Only the list of pictures is resolved here; each one is decoded as its
     * page is bound, so a game with four does not pay for four decodes to
     * show the one that is on screen.
     */
    private void load(String path) {
        new Thread(() -> {
            Meta meta = Metadata.forPath(this, path);
            List<Uri> pictures = new ArrayList<>(Artwork.pictures(this, path));

            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                show(meta, pictures);
            });
        }).start();
    }

    private void show(Meta meta, List<Uri> pictures) {
        galleryAdapter.setPictures(pictures);
        buildDots(pictures.size());

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

    private void buildDots(int count) {
        dots.removeAllViews();
        dots.setVisibility(count > 1 ? View.VISIBLE : View.GONE);

        if (count <= 1) return;

        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(pixels(7), pixels(7));
            params.leftMargin = params.rightMargin = pixels(4);
            dots.addView(dot, params);
        }

        markDots();
    }

    /** Fills the dot for whichever page the pager has settled on. */
    private void markDots() {
        int current = currentPage();

        for (int i = 0; i < dots.getChildCount(); i++) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(i == current ? DOT_ON : DOT_OFF);
            dots.getChildAt(i).setBackground(circle);
        }
    }

    private int currentPage() {
        RecyclerView.LayoutManager manager = gallery.getLayoutManager();

        return manager instanceof LinearLayoutManager
                ? ((LinearLayoutManager) manager).findFirstVisibleItemPosition() : 0;
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

    /** One picture per page, decoded off the UI thread as the page is bound. */
    private final class GalleryAdapter extends RecyclerView.Adapter<PageHolder> {

        private final List<Uri> pictures = new ArrayList<>();

        void setPictures(List<Uri> found) {
            pictures.clear();
            pictures.addAll(found);
            notifyDataSetChanged();
        }

        @Override
        public PageHolder onCreateViewHolder(ViewGroup parent, int type) {
            ImageView view = new ImageView(GameInfoActivity.this);

            // CENTER_INSIDE, as the pane's own picture is: box art cropped to
            // fit a box is the thing this screen exists to show properly.
            view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            view.setContentDescription(null);
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            return new PageHolder(view);
        }

        @Override
        public void onBindViewHolder(PageHolder holder, int position) {
            Uri picture = pictures.get(position);
            int token = ++holder.bindToken;

            holder.image.setImageDrawable(null);
            holder.image.setOnClickListener(v -> showFullSize(picture));

            new Thread(() -> {
                Bitmap decoded = decode(picture);

                handler.post(() -> {
                    if (holder.bindToken != token) return; // recycled meanwhile
                    if (isFinishing() || isDestroyed()) return;
                    holder.image.setImageBitmap(decoded);
                });
            }).start();
        }

        @Override
        public int getItemCount() {
            return pictures.size();
        }
    }

    /**
     * The picture on its own, as large as the screen will draw it - a tap on
     * a page, and a tap anywhere to put it away again.
     *
     * A dialog rather than another activity: there is nothing here to come
     * back to, no state worth a place in the back stack, and dismissing it
     * leaves the gallery exactly where it was, on the page that was tapped.
     * Decoded afresh at the screen's own size rather than reusing the page's
     * bitmap, which was sampled down for a box a third the size and would
     * show it.
     */
    private void showFullSize(Uri picture) {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setBackgroundColor(0xff000000);
        view.setContentDescription(null);

        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(view);
        view.setOnClickListener(v -> dialog.dismiss());
        dialog.show();

        int target = Math.max(getResources().getDisplayMetrics().widthPixels,
                              getResources().getDisplayMetrics().heightPixels);

        new Thread(() -> {
            Bitmap full = decode(picture, target);

            handler.post(() -> {
                if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                view.setImageBitmap(full);
            });
        }).start();
    }

    static final class PageHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        int bindToken;

        PageHolder(ImageView view) {
            super(view);
            image = view;
        }
    }

    /**
     * Decoded at roughly {@link #ARTWORK_TARGET_DP}, the same two-pass way
     * the library's rows decode theirs: a scraped cover can be far larger
     * than any screen wants, and the whole file is not worth holding to draw
     * a fraction of it.
     */
    private Bitmap decode(Uri picture) {
        return decode(picture, pixels(ARTWORK_TARGET_DP));
    }

    /** @param target roughly how many pixels the longest side is wanted at. */
    private Bitmap decode(Uri picture, int target) {
        if (picture == null) return null;

        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;

            try (InputStream probe = getContentResolver().openInputStream(picture)) {
                if (probe == null) return null;
                BitmapFactory.decodeStream(probe, null, bounds);
            }

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
}
