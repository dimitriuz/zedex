package dev.ldlab.zedex.storage;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The last few files opened, so that the second time is one tap.
 *
 * A game is opened through Android's picker, which starts where it last was and
 * needs three taps and a good memory for filenames. Coming back to the game you
 * were playing yesterday is the commonest thing anyone does with an emulator,
 * and it should not cost that.
 *
 * <b>The grant is the hard part.</b> A picked document is a {@code content://}
 * URI the app may read until its task goes away, which is no use to a list that
 * outlives a launch — so opening one takes a <em>persistable</em> grant, and a
 * file falling off the end of the list gives it back. Android caps how many an
 * app may hold, and ten is nowhere near it.
 *
 * Some URIs cannot be persisted at all: a file handed over by another app
 * through ACTION_VIEW is often a one-off grant. Those are still remembered —
 * the name is worth showing — and simply fail to open later, which the panel
 * reports like any other unreadable file.
 *
 * <b>An entry inside a zip is remembered by the archive's own uri</b> plus the
 * path within it - see {@link #remember(ContentResolver, SharedPreferences,
 * Uri, String, String)} - since that path has no uri SAF can address on its
 * own; the grant taken is the archive's, which is the document Android
 * actually knows about. Two entries can therefore share a uri and still be
 * two different rows, which is why every place here that used to compare rows
 * by uri alone now compares by uri <em>and</em> entry, and why dropping one
 * entry must not take a grant a sibling entry still needs with it - see
 * {@link #referencedElsewhere}.
 *
 * Stored as JSON in the preferences, like the pokes and the key profiles. An
 * old list has no {@code inside} field on any of its entries, which reads
 * exactly like a plain file's own absent one - so nothing here needed a
 * migration.
 */
public final class Recents {

    private static final String TAG = "Zedex";

    public static final String KEY_RECENTS = "recentFiles";

    /** Ten: a screenful, and well inside what Android will let us hold. */
    public static final int LIMIT = 10;

    /** One file: what it is called, and what to open to get it back. */
    public static final class Item {
        public final String name;
        public final Uri uri;

        /**
         * The path within {@link #uri} when this is one entry of a zip
         * archive, or null for a plain file - which is what every entry
         * written before this field existed reads back as.
         */
        public final String inside;

        Item(String name, Uri uri, String inside) {
            this.name = name;
            this.uri = uri;
            this.inside = inside;
        }
    }

    private Recents() { }

    /** Newest first. */
    public static List<Item> all(SharedPreferences preferences) {
        List<Item> items = new ArrayList<>();
        String stored = preferences.getString(KEY_RECENTS, null);
        if (stored == null) return items;

        try {
            JSONArray array = new JSONArray(stored);

            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.getJSONObject(i);
                String uri = entry.optString("uri", "");
                String name = entry.optString("name", "");

                if (!uri.isEmpty() && !name.isEmpty()) {
                    // Missing on every row written before this field existed,
                    // which is exactly what a plain file's own absent one
                    // reads as - see Favorites, which reads the same way.
                    String inside = entry.isNull("inside")
                            ? null : entry.optString("inside", null);
                    items.add(new Item(name, Uri.parse(uri), inside));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "unreadable recent files list", e);
        }

        return items;
    }

    /**
     * Puts a file at the top of the list, and takes a grant that will outlive
     * this launch. Opening the same file again moves it up rather than adding
     * it twice.
     */
    public static void remember(ContentResolver resolver, SharedPreferences preferences,
                         Uri uri, String name) {
        remember(resolver, preferences, uri, name, null);
    }

    /**
     * The same, for a game that is one entry inside a zip archive rather than
     * a document of its own. {@code uri} is the <em>archive's</em> uri - the
     * document Android actually grants access to, a zip entry having none of
     * its own - and {@code inside} is the path within it, exactly what
     * {@code Entry.inside} carries.
     *
     * "The same file" means the same uri <em>and</em> the same entry within
     * it here, not the uri alone: two entries of one archive are two
     * different games, and must not collapse onto one row the way opening the
     * same plain file twice does.
     */
    public static void remember(ContentResolver resolver, SharedPreferences preferences,
                         Uri uri, String name, String inside) {
        List<Item> items = all(preferences);
        List<Item> kept = new ArrayList<>();

        kept.add(new Item(name, uri, inside));

        for (Item item : items) {
            if (!sameEntry(item, uri, inside)) kept.add(item);
        }

        // Whatever falls off the end gives its grant back, unless a sibling
        // entry of the same archive is still in the list and still needs it -
        // there is a limit on how many an app may hold and no reason to sit on
        // a grant we cannot reach, but taking away a grant a row still on the
        // list depends on would be worse than reaching the limit a step sooner.
        while (kept.size() > LIMIT) {
            Item dropped = kept.remove(kept.size() - 1);
            if (!referencedElsewhere(kept, dropped.uri)) release(resolver, dropped.uri);
        }

        try {
            resolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // A one-off grant - another app's ACTION_VIEW, usually. Worth
            // remembering by name; it will say so if it cannot be opened.
            Log.i(TAG, "cannot keep the grant for " + uri);
        }

        write(preferences, kept);
    }

    /** Drops one, for a file that turned out not to open. */
    public static void forget(ContentResolver resolver, SharedPreferences preferences,
                       Uri uri) {
        forget(resolver, preferences, uri, null);
    }

    /**
     * The same, for one entry of a zip archive - see
     * {@link #remember(ContentResolver, SharedPreferences, Uri, String, String)}.
     * Matched by the archive's uri <em>and</em> the entry within it, so a
     * different entry of the same archive that still opens fine is not
     * dropped along with the one that does not - and its grant is kept for
     * exactly the same reason; see {@link #referencedElsewhere}.
     */
    public static void forget(ContentResolver resolver, SharedPreferences preferences,
                       Uri uri, String inside) {
        List<Item> kept = new ArrayList<>();
        boolean dropped = false;

        for (Item item : all(preferences)) {
            if (sameEntry(item, uri, inside)) dropped = true;
            else kept.add(item);
        }

        if (dropped && !referencedElsewhere(kept, uri)) release(resolver, uri);

        write(preferences, kept);
    }

    /** Whether a row is the one named by {@code uri} and {@code inside}. */
    private static boolean sameEntry(Item item, Uri uri, String inside) {
        return item.uri.equals(uri) && Objects.equals(item.inside, inside);
    }

    /**
     * Whether some other row still needs the same document's grant.
     *
     * Two entries of one archive share a single uri, so dropping one - by
     * falling off the end of the list, or because it failed to reopen - must
     * not take the grant a sibling entry still on the list depends on.
     */
    private static boolean referencedElsewhere(List<Item> items, Uri uri) {
        for (Item item : items) {
            if (item.uri.equals(uri)) return true;
        }
        return false;
    }

    private static void release(ContentResolver resolver, Uri uri) {
        try {
            resolver.releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // Never held one, or it has gone already. Either is fine.
        }
    }

    private static void write(SharedPreferences preferences, List<Item> items) {
        JSONArray array = new JSONArray();

        for (Item item : items) {
            JSONObject entry = new JSONObject();

            try {
                entry.put("name", item.name);
                entry.put("uri", item.uri.toString());
                // JSONObject.put drops a null value rather than storing one,
                // so a plain file's absent "inside" here reads back exactly
                // like every row written before this field existed did.
                entry.put("inside", item.inside);
            } catch (JSONException e) {
                continue;
            }

            array.put(entry);
        }

        preferences.edit().putString(KEY_RECENTS, array.toString()).apply();
    }
}
