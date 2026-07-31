package dev.ldlab.zedex;

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
 * Stored as JSON in the preferences, like the pokes and the key profiles.
 */
final class Recents {

    private static final String TAG = "Zedex";

    static final String KEY_RECENTS = "recentFiles";

    /** Ten: a screenful, and well inside what Android will let us hold. */
    static final int LIMIT = 10;

    /** One file: what it is called, and what to open to get it back. */
    static final class Item {
        final String name;
        final Uri uri;

        Item(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    private Recents() { }

    /** Newest first. */
    static List<Item> all(SharedPreferences preferences) {
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
                    items.add(new Item(name, Uri.parse(uri)));
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
    static void remember(ContentResolver resolver, SharedPreferences preferences,
                         Uri uri, String name) {
        List<Item> items = all(preferences);
        List<Item> kept = new ArrayList<>();

        kept.add(new Item(name, uri));

        for (Item item : items) {
            if (!item.uri.equals(uri)) kept.add(item);
        }

        // Whatever falls off the end gives its grant back; there is a limit on
        // how many an app may hold and no reason to sit on one we cannot reach.
        while (kept.size() > LIMIT) release(resolver, kept.remove(kept.size() - 1));

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
    static void forget(ContentResolver resolver, SharedPreferences preferences,
                       Uri uri) {
        List<Item> kept = new ArrayList<>();

        for (Item item : all(preferences)) {
            if (item.uri.equals(uri)) release(resolver, item);
            else kept.add(item);
        }

        write(preferences, kept);
    }

    private static void release(ContentResolver resolver, Item item) {
        try {
            resolver.releasePersistableUriPermission(
                    item.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
            } catch (JSONException e) {
                continue;
            }

            array.put(entry);
        }

        preferences.edit().putString(KEY_RECENTS, array.toString()).apply();
    }
}
