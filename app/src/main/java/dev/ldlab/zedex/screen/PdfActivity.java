package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.ui.ZoomableImageView;
import dev.ldlab.zedex.view.Palette;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A manual's PDF, read here rather than handed to another app.
 *
 * <b>Why this exists at all.</b> Handing a manual to whatever PDF viewer the
 * phone has is one line and worked well enough on one screen. On a two-screen
 * handheld it is the fault this app kept meeting: a {@code Presentation} draws
 * above every activity window on its display, so the panel has to step aside
 * for the viewer - and nothing in Android reports another app's activity
 * closing, so it never learned when to come back. A viewer of our own is
 * <em>our</em> activity: the same lifecycle callbacks that already make
 * Settings and the artwork viewer behave on the panel make this behave, with
 * no guessing anywhere.
 *
 * The other app is still one tap away - see {@link #handOver}. This replaces
 * the default, not the choice.
 *
 * <b>One page at a time, swiped sideways.</b> The same shape the artwork
 * gallery has, and for the same two reasons: {@link ZoomableImageView} already
 * knows how to pinch and pan a picture inside a pager, and a page is what a
 * manual is made of. A continuously scrolling column would be a second way of
 * looking at pictures for this app to keep working.
 *
 * <b>{@code PdfRenderer} is single-threaded and one page at a time.</b> Only
 * one {@code Page} may be open at once, and nothing may touch the renderer
 * from two threads - so every render goes through one executor, which is also
 * what keeps a fling from starting thirty of them.
 */
public final class PdfActivity extends ZedexActivity {

    private static final String TAG = "Zedex";

    /** The document, as whatever {@code Artwork.manual} resolved - a {@code
     *  file://} with All files access, a {@code content://} through a tree
     *  grant. Both open through the resolver. */
    public static final String EXTRA_FILE = "dev.ldlab.zedex.extra.PDF";

    /**
     * How much of a page is rendered, as a share of its own width in pixels.
     *
     * A page rendered at the view's width is legible and cheap; rendered at
     * twice that it survives being zoomed into without going soft, which is
     * the whole reason the pages can be pinched. Beyond that a big manual
     * starts costing tens of megabytes for pages nobody is looking at, which
     * is what the cache below is sized against.
     */
    private static final float RENDER_SCALE = 2f;

    /** How many rendered pages are kept. Three is the one on screen and its
     *  two neighbours, which is what a swipe in either direction needs to
     *  land instantly. */
    private static final int KEEP_PAGES = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** One thread, because the renderer allows exactly one - see the class
     *  comment - and because a fling must not queue a render per page it
     *  passes. */
    private final ExecutorService rendering =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "zedex-pdf");
                thread.setDaemon(true);
                return thread;
            });

    private ParcelFileDescriptor file;
    private PdfRenderer document;

    private LruCache<Integer, Bitmap> pages;

    /** Kept so {@link #handOver} can offer the document to another app, and
     *  so the title can say which manual this is. */
    private Uri source;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // The manifest's label resolves in the phone's language rather than
        // this screen's; see Language. The same title the text manual uses -
        // both are the manual, and which format it happens to be in is not
        // something a title should say.
        setTitle(R.string.instructions_title);

        String given = getIntent().getStringExtra(EXTRA_FILE);
        source = given == null ? null : Uri.parse(given);

        if (!open()) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        pages = new LruCache<Integer, Bitmap>(KEEP_PAGES) {
            @Override
            protected void entryRemoved(boolean evicted, Integer key,
                                        Bitmap oldValue, Bitmap newValue) {
                // Recycled on the way out rather than left to the collector:
                // a page at twice the screen's width is megabytes, and three
                // of them plus whatever has not been collected yet is what
                // makes a long manual feel like a leak.
                if (oldValue != null && oldValue != newValue) oldValue.recycle();
            }
        };

        setContentView(buildPage());
    }

    /**
     * The document, or false if it cannot be read.
     *
     * A PDF this app cannot open is an ordinary thing to meet - an encrypted
     * one, or a file that is not really a PDF - and the answer is to say so
     * and let the ordinary hand-over have it, which is what {@code
     * Manuals.open} does when this screen refuses.
     */
    private boolean open() {
        if (source == null) return false;

        try {
            file = getContentResolver().openFileDescriptor(source, "r");
            if (file == null) return false;

            document = new PdfRenderer(file);
            return document.getPageCount() > 0;
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "cannot read " + source, e);
            close();
            return false;
        }
    }

    private View buildPage() {
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Palette.BACKING);

        RecyclerView pager = new RecyclerView(this);
        pager.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        pager.setAdapter(new Pages());

        // The same snapping the artwork gallery uses, so a swipe lands on a
        // page rather than between two.
        new PagerSnapHelper().attachToRecyclerView(pager);

        stage.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return stage;
    }

    /**
     * The document, offered to whatever the phone has for it.
     *
     * The affordance this screen must not take away: a real PDF app has
     * search, a table of contents and everything else, and somebody who wants
     * one should not have to go and find the file themselves. Reached from the
     * menu, and it is the same {@code ACTION_VIEW} hand-over this screen
     * replaced as the default.
     */
    private void handOver() {
        if (source == null) return;

        // Manuals' own, not a second one built here: this did build its own,
        // and did nothing at all on a device with All files access, where
        // Artwork.manual resolves a plain path and a file:// Uri handed to
        // another app has been refused since Android 7. That conversion, the
        // explicit grant to every resolver, and the <queries> entry that lets
        // any of them be seen are all over there and all load-bearing.
        dev.ldlab.zedex.library.ui.Manuals.handOver(
                this, source, "application/pdf", getDisplay(), null);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(getString(R.string.library_open))
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        handOver();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        rendering.shutdownNow();
        if (pages != null) pages.evictAll();
        close();
    }

    private void close() {
        try {
            if (document != null) document.close();
            if (file != null) file.close();
        } catch (Exception e) {
            Log.w(TAG, "cannot close " + source, e);
        }

        document = null;
        file = null;
    }

    /** One page per swipe, drawn as a picture that can be pinched. */
    private final class Pages extends RecyclerView.Adapter<Page> {

        @Override
        public Page onCreateViewHolder(ViewGroup parent, int kind) {
            ZoomableImageView view = new ZoomableImageView(PdfActivity.this);
            view.setZoomable(true);

            // As wide as the screen, not wholly inside it. A portrait page
            // fitted both ways in a landscape panel is scaled to the height,
            // which leaves a column of text a third of the screen wide - the
            // page is there and nobody can read it. Fitting the width fills
            // the screen and lets the page run off the bottom, which is what
            // reading is; a drag moves down it, and pinching still works from
            // there.
            view.setFitWidth(true);

            // The pager's own measured width, never MATCH_PARENT: in the
            // direction a list scrolls, MATCH_PARENT is measured UNSPECIFIED,
            // and a picture with no bitmap yet measures zero - which fills the
            // viewport with pages for ever. See CLAUDE.md.
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    parent.getMeasuredWidth(), ViewGroup.LayoutParams.MATCH_PARENT));

            return new Page(view);
        }

        @Override
        public void onBindViewHolder(Page holder, int at) {
            holder.show(at);
        }

        @Override
        public int getItemCount() {
            return document == null ? 0 : document.getPageCount();
        }
    }

    private final class Page extends RecyclerView.ViewHolder {

        private final ZoomableImageView view;

        /** Which page this holder is currently for, so a render that finishes
         *  after the holder has been reused is dropped rather than drawn onto
         *  somebody else's page. */
        private int showing = -1;

        Page(ZoomableImageView view) {
            super(view);
            this.view = view;
        }

        void show(int at) {
            showing = at;

            Bitmap had = pages.get(at);
            if (had != null && !had.isRecycled()) {
                view.setImageBitmap(had);
                return;
            }

            view.setImageDrawable(null);

            int width = Math.max(1, Math.round(view.getWidth() > 0
                    ? view.getWidth() * RENDER_SCALE
                    : getResources().getDisplayMetrics().widthPixels * RENDER_SCALE));

            rendering.execute(() -> {
                Bitmap drawn = render(at, width);
                if (drawn == null) return;

                handler.post(() -> {
                    if (showing != at) {
                        drawn.recycle();
                        return;
                    }

                    pages.put(at, drawn);
                    view.setImageBitmap(drawn);
                });
            });
        }
    }

    /**
     * One page, rendered.
     *
     * On the rendering thread and nowhere else: {@code PdfRenderer} allows one
     * open page at a time and is not safe to touch from two threads, so this
     * is the only place that opens one and it is reached from one executor.
     */
    private Bitmap render(int at, int width) {
        PdfRenderer open = document;
        if (open == null || at >= open.getPageCount()) return null;

        try (PdfRenderer.Page page = open.openPage(at)) {
            int height = Math.max(1, width * page.getHeight() / Math.max(1, page.getWidth()));

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            // White first: a PDF page is transparent where it has no ink, and
            // drawn straight onto this screen's own near-black backing that
            // is black text on black.
            bitmap.eraseColor(0xffffffff);

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } catch (Exception e) {
            // A page that will not render is one page, not the document: the
            // rest are still worth reading.
            Log.w(TAG, "cannot render page " + at + " of " + source, e);
            return null;
        }
    }
}
