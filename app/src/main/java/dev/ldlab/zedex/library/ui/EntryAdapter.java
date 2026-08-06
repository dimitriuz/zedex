package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.Types;

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

    private final Context context;
    private final Callbacks callbacks;
    private final List<Entry> entries = new ArrayList<>();
    private boolean grid;

    /** The key of the selected row, or null when nothing is - see
     *  {@link Entry#key()} and {@link dev.ldlab.zedex.screen.LibraryActivity}. */
    private String selectedKey;

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

    /** Replaces every row at once; the caller has already sorted and filtered. */
    public void setEntries(List<Entry> replacement) {
        entries.clear();
        entries.addAll(replacement);
        notifyDataSetChanged();
    }

    /**
     * Which row, if any, is selected - tinted so the pane's own answer to
     * "what is this about" is legible on the list behind it too. Redrawn
     * whenever it changes; harmless when it does not, since the caller does
     * not have to know that.
     */
    public void setSelectedKey(String key) {
        if (java.util.Objects.equals(selectedKey, key)) return;
        selectedKey = key;
        notifyDataSetChanged();
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

        holder.icon.setImageResource(iconFor(entry));
        holder.title.setText(entry.name);

        // Null in the list shape, which dropped its own second line - the
        // pane already says the size and the date for whatever is selected -
        // and kept only in the grid tile, which still has the room for one.
        if (holder.subtitle != null) holder.subtitle.setText(detail(context, entry));

        // A tint rather than a state-list drawable, so it sits underneath the
        // ripple - see the layout's own android:foreground - instead of
        // replacing it. Reset for a row a recycled holder is about to become,
        // not only set for the one it is about to be.
        boolean selected = selectedKey != null && selectedKey.equals(entry.key());
        holder.itemView.setBackgroundColor(selected ? SELECTED_BACKGROUND : 0x00000000);

        holder.itemView.setOnClickListener(v -> callbacks.onOpen(entry));
        holder.itemView.setOnLongClickListener(v -> {
            callbacks.onLongPress(entry);
            return true;
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
        return R.drawable.ic_file;
    }

    private static boolean isOneOf(String value, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) return true;
        }
        return false;
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

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;

        /** Null for the list shape, which has no {@code R.id.subtitle} any
         *  more; present for the grid tile, which still does. */
        final TextView subtitle;

        Holder(View view) {
            super(view);
            icon = view.findViewById(R.id.icon);
            title = view.findViewById(R.id.title);
            subtitle = view.findViewById(R.id.subtitle);
        }
    }
}
