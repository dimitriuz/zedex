package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.catalogue.Catalogue;
import dev.ldlab.zedex.library.catalogue.Thumbnails;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.work.Work;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Rows for the catalogue tab: a shelf to descend into, a title to look at, and
 * the row a failed page leaves behind.
 *
 * Its own class rather than more branches in {@link EntryAdapter}, which is
 * written throughout in terms of an {@code Entry} - a file with a {@code Uri},
 * a size and a modified time. A catalogue item is none of those: it is an id on
 * somebody else's service, and forcing one into the other means four fields
 * that lie.
 *
 * <b>One size of row.</b> {@link Thumbnails#get} keys its cache on the url
 * alone, so there is exactly one decoded bitmap per cover and it is decoded for
 * {@link #ROW_DP} - the same 140dp {@code EntryAdapter.GRID_TARGET_DP} uses.
 * That is the whole reason this tab has no list/grid toggle: a second row size
 * would hand a 140dp bitmap to a 20dp row and call it a hit. If a later change
 * wants one, {@code Thumbnails} needs a {@code targetPx} parameter <em>and its
 * cache key must include it</em>, the way {@link PictureCache} keys on {@code
 * uri@targetPx}.
 *
 * <b>Nothing here fetches speculatively.</b> A cover is asked for when its row
 * binds, which is when it is on screen, and never before.
 */
public final class CatalogueAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** What a tap on each of the three kinds of row does; {@link CatalogueView}
     *  owns all three. */
    public interface Callbacks {
        void openShelf(Catalogue.Shelf shelf);
        void openItem(Catalogue.Item item);
        void retry();
    }

    /**
     * The row a failed page leaves behind, as a value rather than a null.
     *
     * <b>It is appended, never a replacement.</b> Whatever already arrived
     * stays above it: an emptied grid is indistinguishable from a catalogue
     * that has nothing in it, and the one thing somebody needs after a page
     * fails is the page before it, still there, with a way to ask again.
     */
    public static final class Failed {

        /** Only ever this one - it carries no state, and a second instance
         *  would only invite a caller to think it did. */
        public static final Failed ROW = new Failed();

        private Failed() {
        }
    }

    private static final int TYPE_SHELF = 0;
    private static final int TYPE_ITEM = 1;
    private static final int TYPE_FAILED = 2;

    /**
     * What a cover is drawn at, in dp - and so what {@link Thumbnails}
     * decodes to, since the two must agree. See the class comment.
     */
    static final int ROW_DP = 140;

    /**
     * A total of exactly this is Elasticsearch's counting cap, not a count.
     *
     * Measured in Task 5 against the live service: an unfiltered search over
     * a database of about 39,666 entries answers {@code total=10000}, because
     * ten thousand is at once the default limit on counting and about as deep
     * as a paged search may go. It is the right number for {@code
     * Page.hasMore} to page against and a lie to show somebody - "10,000
     * results" for a search that matched four times that many reads as a
     * broken filter. A shelf narrow enough to have a real total gets one: the
     * search that proved {@code ZxInfoCatalogue} answered 153.
     */
    private static final int COUNTING_CAP = 10_000;

    /** The greyed row's alpha. Still tappable at it - see {@link #greyed}. */
    private static final float UNAVAILABLE_ALPHA = 0.5f;

    /** The same tint {@link EntryAdapter} gives a vector icon standing in for
     *  a picture, so the two tabs' placeholders match. */
    private static final int ICON_TINT = 0xff8b8b99;

    private final Context context;
    private final Http http;
    private final Callbacks callbacks;

    /** Shelves, items and at most one {@link Failed#ROW}, in the order they
     *  are drawn. {@code Object} because those three have nothing in common
     *  worth inventing a base type for. */
    private final List<Object> rows = new ArrayList<>();

    /**
     * @param http where a cover is fetched from - handed in rather than built
     *             here, the same way {@code Imports} and {@code CataloguePane}
     *             are handed theirs, so a test can put a canned one in its
     *             place without reaching the network.
     */
    public CatalogueAdapter(Context context, Http http, Callbacks callbacks) {
        this.context = context;
        this.http = http;
        this.callbacks = callbacks;
    }

    /** Replaces every row at once; the caller owns what the list contains and
     *  in what order. */
    public void setRows(List<Object> replacement) {
        rows.clear();
        if (replacement != null) rows.addAll(replacement);
        notifyDataSetChanged();
    }

    /**
     * The catalogue's own count of a shelf, or null when there is nothing
     * honest to say.
     *
     * Null for {@link Catalogue.Page#UNKNOWN_TOTAL} - a shelf that cannot
     * count - and null at {@link #COUNTING_CAP}, which is a cap wearing a
     * count's clothes. A bare numeral rather than a sentence: the words
     * around it would be a tenth string in nine files for something the shelf
     * label beside it already explains.
     */
    public static String countLabel(int total) {
        if (total < 0 || total >= COUNTING_CAP) return null;

        return java.text.NumberFormat.getIntegerInstance().format(total);
    }

    /**
     * Whether a row is drawn as unavailable.
     *
     * <b>Stated and not available</b>, which is a different question from
     * {@link Catalogue.Item#available()}. That one answers "is there
     * definitely something to download", so it reads an absent {@code
     * availability} as false - correct there, and wrong here: measured on a
     * live ZXInfo reply during Task 5, one row of three omitted the field
     * entirely and it was a 2024 release. Greying it would tell somebody a
     * game they can have is missing, and give no reason, because a field was
     * absent. Two questions, two predicates.
     */
    static boolean greyed(Catalogue.Item item) {
        return item.availability() != null && !item.available();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public int getItemViewType(int position) {
        Object row = rows.get(position);

        if (row instanceof Catalogue.Shelf) return TYPE_SHELF;
        if (row instanceof Catalogue.Item) return TYPE_ITEM;
        return TYPE_FAILED;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        switch (viewType) {
            case TYPE_SHELF: return new ShelfHolder(context);
            case TYPE_ITEM: return new ItemHolder(context);
            default: return new FailedHolder(context, callbacks);
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);

        if (holder instanceof ShelfHolder) {
            bindShelf((ShelfHolder) holder, (Catalogue.Shelf) row);
        } else if (holder instanceof ItemHolder) {
            bindItem((ItemHolder) holder, (Catalogue.Item) row);
        }
        // A FailedHolder has nothing to bind: its two words and its button are
        // the same whichever page failed, and its listener was set once when
        // the view was made.
    }

    private void bindShelf(ShelfHolder holder, Catalogue.Shelf shelf) {
        // The service's own word for itself - "Newest", "Surprise me" - which
        // is why it is not a string resource. See Catalogue.Shelf.
        holder.label.setText(shelf.label());
        holder.itemView.setContentDescription(shelf.label());
        holder.itemView.setOnClickListener(v -> callbacks.openShelf(shelf));
    }

    private void bindItem(ItemHolder holder, Catalogue.Item item) {
        // Bumped before anything asynchronous is asked for. Thumbnails.forget
        // deliberately leaves a fetch already in flight running - clearing it
        // is what would strand its listeners - so a cover can land after this
        // holder has been recycled onto a different game, and this is what
        // makes that answer discardable rather than one game's cover on
        // another game's row.
        int token = ++holder.bindToken;

        holder.title.setText(item.title());

        boolean unavailable = greyed(item);

        // The catalogue's own word for why, in place of the facts line: a game
        // announced and cancelled is a real thing to find, and the reason is
        // worth more on that row than the year and the publisher.
        holder.detail.setText(unavailable ? item.availability() : item.describe());

        // Reset, not merely set: a recycled holder arrives carrying whatever
        // the row before it was drawn at.
        holder.itemView.setAlpha(unavailable ? UNAVAILABLE_ALPHA : 1f);

        // Greyed and still tappable. The reason is worth reading, and Task 11's
        // pane is where it is read.
        holder.itemView.setOnClickListener(v -> callbacks.openItem(item));

        String url = item.pictureUrl();

        // A text row, not a placeholder waiting for something that will never
        // come - exactly as an unscraped local game already is in the grid
        // next door. The box goes away and the words take the width.
        if (url == null || url.isEmpty()) {
            holder.cover.setVisibility(View.GONE);
            return;
        }

        holder.cover.setVisibility(View.VISIBLE);

        Bitmap have = Thumbnails.get(url);
        if (have != null) {
            holder.cover.clearColorFilter();
            holder.cover.setImageBitmap(have);
            return;
        }

        holder.cover.setColorFilter(ICON_TINT);
        holder.cover.setImageResource(R.drawable.ic_file);

        Thumbnails.load(context, http, url, (fetched, picture) -> {
            if (holder.bindToken != token) return; // this holder moved on

            // Nothing arrived: a 404, a timeout, a format BitmapFactory does
            // not know. The placeholder stays, and - this matters - no redraw
            // is asked for, because a rebind would call load() again, be
            // answered from the remembered-failure list *synchronously*, and
            // land right back here while the recycler is laying out.
            if (picture == null) return;

            int at = holder.getBindingAdapterPosition();
            if (at == RecyclerView.NO_POSITION) return;

            // Posted rather than called. A cache hit answers on the calling
            // thread, and this listener can be reached that way: another
            // row's fetch may file this very url between the get() above and
            // the load() below it, in which case we are still inside
            // onBindViewHolder and notifying from there throws. One trip
            // through the looper costs a frame and cannot be inside a layout
            // pass. A position that has gone stale by then only rebinds an
            // innocent row, which is idempotent.
            Work.onMain(() -> notifyItemChanged(at));
        });
    }

    // --- the three rows ------------------------------------------------------------

    /**
     * A way in: one line and a chevron, the height of an ordinary list row.
     *
     * Explicitly sized, like everything else here. In the direction a list
     * scrolls, {@code MATCH_PARENT} is handed to a child as {@code
     * UNSPECIFIED} - this codebase lost 1.9 GB and 663 threads to a row that
     * measured zero and a layout manager that filled the viewport for ever.
     */
    private static final class ShelfHolder extends RecyclerView.ViewHolder {

        private static final int HEIGHT_DP = 56;

        final TextView label;

        ShelfHolder(Context context) {
            super(build(context));

            LinearLayout row = (LinearLayout) itemView;
            label = (TextView) row.getChildAt(0);
        }

        private static View build(Context context) {
            float density = context.getResources().getDisplayMetrics().density;
            int pad = Math.round(12 * density);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, 0, pad, 0);
            row.setBackground(Ripple.make(density));
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(HEIGHT_DP * density)));

            TextView label = new TextView(context);
            label.setTextColor(Palette.TEXT);
            label.setTextSize(16);
            label.setSingleLine();
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView chevron = new TextView(context);
            chevron.setText("›");
            chevron.setTextColor(Palette.MUTED);
            chevron.setTextSize(18);
            row.addView(chevron, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            return row;
        }
    }

    /**
     * One title: its cover at {@link #ROW_DP}, its name, and one line of
     * facts.
     *
     * The picture's box is a fixed square rather than anything derived from
     * the row - see {@link ShelfHolder} for why nothing here may be sized by
     * the scroll direction. Square and {@code FIT_CENTER} because these are
     * not all box art: a ZXDB record's picture is as often a 4:3 loading
     * screen as a 3:4 cover, and a box that fits either is worth more than one
     * that crops one of them.
     */
    private static final class ItemHolder extends RecyclerView.ViewHolder {

        final ImageView cover;
        final TextView title;
        final TextView detail;

        /** Bumped on every bind, so a cover still being fetched for whatever
         *  this holder used to show can tell it no longer applies. */
        int bindToken;

        ItemHolder(Context context) {
            super(build(context));

            LinearLayout row = (LinearLayout) itemView;
            cover = (ImageView) row.getChildAt(0);

            LinearLayout words = (LinearLayout) row.getChildAt(1);
            title = (TextView) words.getChildAt(0);
            detail = (TextView) words.getChildAt(1);
        }

        private static View build(Context context) {
            float density = context.getResources().getDisplayMetrics().density;
            int pad = Math.round(12 * density);
            int box = Math.round(ROW_DP * density);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, Math.round(8 * density), pad, Math.round(8 * density));
            row.setBackground(Ripple.make(density));
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // A floor rather than a height, so a row whose picture is gone -
            // a title with none - is still the same size as the ones around
            // it, and a two-line name is still allowed to be taller.
            row.setMinimumHeight(box + Math.round(16 * density));

            ImageView cover = new ImageView(context);
            cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cover.setContentDescription(null);
            row.addView(cover, new LinearLayout.LayoutParams(box, box));

            LinearLayout words = new LinearLayout(context);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(pad, 0, 0, 0);

            TextView title = new TextView(context);
            title.setTextColor(Palette.TEXT);
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView detail = new TextView(context);
            detail.setTextColor(Palette.MUTED);
            detail.setTextSize(13);
            detail.setMaxLines(3);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(detail, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            row.addView(words, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            return row;
        }
    }

    /** The page that did not arrive, and the one thing worth offering about
     *  it. Both words are fixed, so this binds nothing. */
    private static final class FailedHolder extends RecyclerView.ViewHolder {

        FailedHolder(Context context, Callbacks callbacks) {
            super(build(context, callbacks));
        }

        private static View build(Context context, Callbacks callbacks) {
            float density = context.getResources().getDisplayMetrics().density;
            int pad = Math.round(16 * density);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            row.setPadding(pad, pad, pad, pad);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView words = new TextView(context);
            words.setText(R.string.catalogue_failed);
            words.setTextColor(Palette.MUTED);
            words.setTextSize(15);
            words.setGravity(Gravity.CENTER);
            row.addView(words, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            Button again = new Button(context);
            again.setText(R.string.catalogue_retry);
            again.setOnClickListener(v -> callbacks.retry());
            row.addView(again, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            return row;
        }
    }
}
