package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.Types;
import dev.ldlab.zedex.library.meta.Metadata;

import android.content.Context;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Rows for the library's three tabs, in either the list or the grid shape.
 *
 * One adapter for both rather than two, because they differ only in which
 * layout a row inflates - what a tap and a hold do is exactly the same
 * regardless of shape. {@link Callbacks#onOpen} is a folder walked into, a
 * zip walked into, or a game loaded into the machine; {@link
 * Callbacks#onLongPress} is the whole of how a favourite is made or unmade -
 * see docs/LIBRARY.md.
 *
 * The list handed to {@link #setEntries} is expected already sorted and
 * already filtered by whatever is in the search box: this class only draws
 * rows, and {@link dev.ldlab.zedex.screen.LibraryActivity} is the one place
 * that knows which tab is showing and what order it asked for.
 */
public final class EntryAdapter extends RecyclerView.Adapter<EntryAdapter.Holder> {

    /** What tapping and holding a row does; the activity owns both. */
    public interface Callbacks {
        void onOpen(Entry entry);
        void onLongPress(Entry entry);
    }

    private static final int TYPE_LIST = 0;
    private static final int TYPE_GRID = 1;

    /** A quiet tint over the row that is selected - nothing else here changes
     *  colour, so this is the one thing that says which row the pane is
     *  about. */
    private static final int SELECTED_BACKGROUND = 0x3300b0c8;

    /** The type icon's own tint - applied and cleared in code now rather
     *  than fixed in the layout, since the same {@code ImageView} shows a
     *  real picture once ES-DE has scraped one for a row, in the list
     *  shape, and a picture must not be tinted the way a plain vector icon
     *  is. */
    private static final int ICON_TINT = 0xff8b8b99;

    /** Roughly what is actually drawn, in dp - a small thumbnail in the list
     *  shape, a tile-filling picture in the grid one. See {@link
     *  Scraped#load} for why decoding to this rather than a cover's full
     *  resolution is what keeps a scroll from stuttering; the grid figure
     *  matches the tile's own image area in {@code library_entry_grid.xml}.
     *
     *  That area is 3:4 now rather than a fixed 100dp - see AspectFrame - so
     *  the grid figure is its taller side, not its width: columns are laid
     *  out at roughly 100dp, which leaves a tile about 98dp wide and 131dp
     *  tall once its own padding is off, and decoding to the width would
     *  hand a picture short of what the box draws. Over-decoding costs a
     *  little memory; under-decoding is visibly soft. */
    private static final int LIST_TARGET_DP = 20;
    private static final int GRID_TARGET_DP = 140;

    private final Context context;
    private final Callbacks callbacks;
    private final List<Entry> entries = new ArrayList<>();
    private boolean grid;

    /** {@code libraryNames}, handed here rather than read a second time from
     *  a second copy of the preferences - {@code LibraryActivity} already
     *  holds the one that matters. Defaults to the same true the preference
     *  itself defaults to, so a holder bound before {@link
     *  #setShowScrapedNames} is first called - never, in practice, but
     *  nothing here should depend on that - still shows a name rather than
     *  silently disagreeing with what the switch says once it is read. */
    private boolean showScrapedNames = true;

    /** The key of the selected row, or null when nothing is - see
     *  {@link Entry#key()} and {@link dev.ldlab.zedex.screen.LibraryActivity}. */
    private String selectedKey;

    /** Off the UI thread and cached, hit and miss alike - see its own class
     *  comment. One instance for the adapter's whole life, so scrolling
     *  back to a row already resolved answers from the cache rather than
     *  asking the store and decoding a picture all over again. */
    private final Scraped scraped = new Scraped();

    public EntryAdapter(Context context, Callbacks callbacks) {
        this.context = context;
        this.callbacks = callbacks;
    }

    /** Whether rows are drawn as a grid rather than a list. */
    public void setGrid(boolean grid) {
        if (this.grid == grid) return;
        this.grid = grid;
        notifyDataSetChanged();
    }

    /** Whether a row with a scraped name shows it instead of the filename -
     *  the {@code libraryNames} preference. Read and handed here by {@code
     *  LibraryActivity}, which owns the preferences; this class only ever
     *  reads what it is told. */
    public void setShowScrapedNames(boolean show) {
        if (showScrapedNames == show) return;
        showScrapedNames = show;
        notifyDataSetChanged();
    }

    /** Replaces every row at once; the caller has already sorted and filtered. */
    public void setEntries(List<Entry> replacement) {
        entries.clear();
        entries.addAll(replacement);
        notifyDataSetChanged();
    }

    /**
     * The content folder changed under this adapter - see {@link
     * Scraped#forget}, which this is the whole of. Walking into or out of a
     * folder within the same content tree is not this: a row's path is
     * still meaningful there, so nothing needs forgetting on the way.
     */
    public void forgetPending() {
        scraped.forget();
    }

    /**
     * A link replaced the metadata store - see {@link Scraped#clear}, which
     * this is the whole of. Every cached name and picture, hit and miss
     * alike, is now answering a question that may have a different answer.
     */
    public void clearScraped() {
        scraped.clear();
    }

    /**
     * The one cache this screen uses for names and pictures - rows, tiles
     * and the pane alike, so a folder change or a link only ever has to be
     * told once. {@code LibraryActivity}'s own pane reads this directly for
     * the selected row's larger picture and its video, neither of which a
     * row or a tile ever asks for.
     */
    public Scraped scraped() {
        return scraped;
    }

    /**
     * Which row, if any, is selected - tinted so the pane's own answer to
     * "what is this about" is legible on the list behind it too. Redrawn
     * whenever it changes; harmless when it does not, since the caller does
     * not have to know that.
     */
    public void setSelectedKey(String key) {
        if (java.util.Objects.equals(selectedKey, key)) return;

        String was = selectedKey;
        selectedKey = key;

        // The two rows that changed, not the whole list. A held direction on
        // a pad repeats every 130ms, and notifyDataSetChanged rebinds every
        // visible holder each time - about twenty tiles on a grid, so a
        // hundred and fifty binds a second, each of them re-deriving the
        // entry's relative path and allocating its listeners, for a tint on
        // two of them. Gallery already says as much for its own sibling case.
        notifyRowChanged(was);
        notifyRowChanged(key);
    }

    /** Redraws whichever row carries this key, if it is in the list. */
    private void notifyRowChanged(String key) {
        if (key == null) return;

        for (int i = 0; i < entries.size(); i++) {
            if (key.equals(entries.get(i).key())) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    /** The row at this position - what a pad's cursor moves through and
     *  selects; touch never needs a position, only a key, so nothing else
     *  here calls this. */
    public Entry entryAt(int position) {
        return entries.get(position);
    }

    @Override
    public int getItemViewType(int position) {
        return grid ? TYPE_GRID : TYPE_LIST;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_GRID
                ? R.layout.library_entry_grid : R.layout.library_entry_list;

        return new Holder(LayoutInflater.from(context).inflate(layout, parent, false));
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        Entry entry = entries.get(position);

        // Bumped before anything asynchronous is asked for, so a decode
        // that lands after this holder has been recycled to a different
        // row - a fast scroll, most often - can tell its own answer is
        // stale and discard it rather than draw a picture into a row it no
        // longer belongs to.
        int token = ++holder.bindToken;

        // The fallback, exactly as it has always looked - everything below
        // this may replace it once a picture or a name actually resolves,
        // but nothing here waits for that, since most rows in a collection
        // like this one have nothing to resolve to.
        holder.icon.setVisibility(View.VISIBLE);
        holder.icon.setColorFilter(ICON_TINT);
        holder.icon.setImageResource(iconFor(entry));
        holder.title.setText(entry.name);
        if (holder.cover != null) holder.cover.setVisibility(View.GONE);

        // Both shapes now: under the name in a tile, at the end of the row in
        // a list. The format alone until a scrape answers with a year to put
        // in front of it, which is what the callback below does; most rows
        // never get one, and the format on its own is a complete answer
        // rather than a half-drawn one waiting to be finished.
        if (holder.subtitle != null) holder.subtitle.setText(rowDetail(entry, null));

        // A tint rather than a state-list drawable, so it sits underneath the
        // ripple - see the layout's own android:foreground - instead of
        // replacing it. Reset for a row a recycled holder is about to become,
        // not only set for the one it is about to be.
        boolean selected = selectedKey != null && selectedKey.equals(entry.key());
        // Selection.background rather than a colour: the wash alone was
        // 1.37:1 against an untinted row, which is not a signal.
        if (selected) {
            holder.itemView.setBackground(Selection.background(
                    holder.itemView.getResources().getDisplayMetrics().density));
        } else {
            holder.itemView.setBackground(null);
        }

        // TalkBack appends "selected" for free once the view says so, and
        // nothing said so anywhere in this app before.
        holder.itemView.setSelected(selected);

        holder.itemView.setOnClickListener(v -> callbacks.onOpen(entry));
        holder.itemView.setOnLongClickListener(v -> {
            callbacks.onLongPress(entry);
            return true;
        });

        // Only a real file, in this content tree, is worth asking ES-DE's
        // own store about - a folder or an archive to walk into is not a
        // game, and an entry reached from inside a zip (Favourites and
        // Browse's own archive listing both hand these back) has no path of
        // its own for a gamelist to have matched by relative path; see
        // docs/LIBRARY.md, "matching is by path". Metadata.relativePath
        // answers null for the same reason for anything outside the
        // content tree, Favourites and Recents included.
        if (entry.kind != Entry.Kind.FILE || entry.inside != null) return;

        String relativePath = Metadata.relativePath(context, entry.uri);
        if (relativePath == null) return;

        int targetPx = pixels(grid ? GRID_TARGET_DP : LIST_TARGET_DP);

        // The words are set here and now. Metadata is a map in memory once it
        // has been read - see Metadata.store - so asking it what this game is
        // called costs a hash lookup, and sending that question to a worker
        // meant the row was drawn with its filename and then rewritten a
        // moment later with its name. That flicker is the whole of what a
        // person sees while scrolling: every row twice, the second time
        // saying something different.
        //
        // Only the picture goes to a thread, because only the picture has to
        // be decoded.
        applyMeta(holder, entry, Metadata.forPath(context, relativePath));

        scraped.picture(context, relativePath, targetPx, picture -> {
            if (holder.bindToken != token) return; // this holder moved on
            if (picture == null) return;

            if (holder.cover != null) {
                // The grid tile: the fallback icon steps aside rather than
                // being drawn under a picture that would hide it anyway, and
                // the picture fills the tile around it - see
                // library_entry_grid.xml.
                holder.icon.setVisibility(View.GONE);
                holder.cover.setImageBitmap(picture);
                holder.cover.setVisibility(View.VISIBLE);
            } else {
                // The list row: the same view the type icon used, its tint
                // cleared so a real picture is not muted the way a vector
                // icon is.
                holder.icon.clearColorFilter();
                holder.icon.setImageBitmap(picture);
            }
        });
    }

    /**
     * What kind of thing a row is, by an icon already in the app for it - a
     * tape for a tape, a disk for a disk - rather than one blank file icon for
     * everything the emulator can load.
     *
     * {@link Entry#kind} decides folders and archives to walk into; everything
     * else is judged by its own extension, whether it is a plain file, an
     * entry read out of a zip by {@link dev.ldlab.zedex.library.Listing#archive},
     * or one out of {@link dev.ldlab.zedex.library.Favorites} or {@code
     * Recents} - all three hand back {@code Entry.Kind.FILE} for a game, and a
     * game's own format is worth more here than where the entry came from.
     */
    private int iconFor(Entry entry) {
        switch (entry.kind) {
            case FOLDER: return R.drawable.ic_folder;
            // A zip to walk into in Browse, or - see Favorites.all - a
            // favourite that is an entry inside one. Either way, a game living
            // in an archive is worth saying so.
            case ARCHIVE: return R.drawable.ic_archive;
            default: break;
        }

        String extension = Types.extension(entry.name);
        if (isOneOf(extension, "tap", "tzx")) return R.drawable.ic_tape;
        if (isOneOf(extension, "dsk", "mgt", "img", "scl", "trd", "udi")) {
            return R.drawable.ic_disk;
        }
        // A music or screenshot import - see Types.external for the list.
        // Tapping either hands it to another app rather than the machine, so
        // the icon says that before the tap does.
        if (isOneOf(extension, "ogg")) return R.drawable.ic_music;
        if (isOneOf(extension, "png", "jpg", "jpeg", "gif")) return R.drawable.ic_picture;
        return R.drawable.ic_file;
    }

    private static boolean isOneOf(String value, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) return true;
        }
        return false;
    }

    /**
     * The scraped name and year on a row, from metadata already in memory.
     *
     * Nothing here touches a file or a thread: it is the same two fields the
     * picture's callback used to set, set before the row is first shown
     * rather than a moment after it.
     */
    private void applyMeta(Holder holder, Entry entry, Meta meta) {
        if (meta == null) return;

        String name = meta.name;
        if (showScrapedNames && name != null && !name.isEmpty()) {
            holder.title.setText(name);
        }

        String year = meta.year();
        if (holder.subtitle != null && year != null) {
            holder.subtitle.setText(rowDetail(entry, year));
        }
    }

    /**
     * What a row says under its name: the release year and the file's own
     * format, {@code "1987 · TZX"}.
     *
     * Not the size and the date, which is what it used to be and what {@link
     * #detail} still gives the pane. A tile is browsing, and neither of those
     * two helps anyone choose a game - every Spectrum file is small, and the
     * date is when it happened to be copied onto this phone, which says
     * nothing about the game at all. The year does, and the format is the one
     * thing about the file worth knowing at a glance: a {@code .tap} and a
     * {@code .trd} are different machines' worth of trouble.
     *
     * @param year the scraped year, or null before one has resolved and for
     *             the greater part of any collection, which has none.
     */
    static String rowDetail(Entry entry, String year) {
        if (entry.kind == Entry.Kind.FOLDER) return "";

        String format = Types.extension(entry.name).toUpperCase(Locale.ROOT);

        if (year == null) return format;
        if (format.isEmpty()) return year;

        return year + " · " + format;
    }

    /**
     * Size and date, whichever of them is known; empty when neither is.
     *
     * Not a folder's size: {@code DocumentsContract} hands one back for a
     * directory row same as for a file, and it is the size of the directory
     * entry itself, not of what is inside it - a number that reads as
     * meaningful and is not. An archive's own size is the real byte count of
     * the zip on disk, and an entry's is what it unpacks to, so both of those
     * stay.
     *
     * Static and public so {@link dev.ldlab.zedex.screen.LibraryActivity}'s
     * pane can say the same thing about the selected row that the row itself
     * already says, rather than a second copy of this that could drift.
     */
    public static String detail(Context context, Entry entry) {
        StringBuilder text = new StringBuilder();

        if (entry.size >= 0 && entry.kind != Entry.Kind.FOLDER) {
            text.append(Formatter.formatShortFileSize(context, entry.size));
        }
        if (entry.modified > 0) {
            if (text.length() > 0) text.append(" · ");
            text.append(DateFormat.getDateFormat(context).format(new Date(entry.modified)));
        }

        return text.toString();
    }

    private int pixels(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;

        /** The year and the format. Present in both shapes - beneath the
         *  name in a tile, at the far end of the row in a list - but still
         *  null-checked at every use, since a layout is free to drop it. */
        final TextView subtitle;

        /** Null for the list shape, which shows a scraped picture in {@link
         *  #icon}'s own slot instead - see onBindViewHolder. Present for the
         *  grid tile, which needs a picture to fill the tile around a
         *  fallback icon that must keep its own size, not stretch to it. */
        final ImageView cover;

        /** Bumped on every bind, so a decode still resolving for whatever
         *  this holder used to show can tell it no longer applies - see
         *  onBindViewHolder. */
        int bindToken;

        Holder(View view) {
            super(view);
            icon = view.findViewById(R.id.icon);
            title = view.findViewById(R.id.title);
            subtitle = view.findViewById(R.id.subtitle);
            cover = view.findViewById(R.id.cover);
        }
    }
}
