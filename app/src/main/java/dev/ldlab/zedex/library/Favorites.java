package dev.ldlab.zedex.library;

import dev.ldlab.zedex.storage.Storage;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Favourite games: the key that finds one again, and the md5 once it has
 * actually been played.
 *
 * One JSON file, {@code favorites.json}, read whole and written whole - the
 * same choice {@code storage.Recents} makes for the same reason: ten
 * favourites or ten thousand is still a small file, and nothing here does a
 * query a database would earn its keep on. It lives in the app's data folder
 * ({@link Storage#root}) rather than beside the games, because the content
 * folder can be read-only - a shared drive, a card - and a library that
 * cannot remember what was favourited is worse than one that does not
 * travel.
 *
 * A missing, unreadable or corrupt file reads as no favourites at all - logged,
 * and never thrown. Losing the list is a nuisance; a crash on every launch
 * afterwards would be a worse one.
 */
public final class Favorites {

    private static final String TAG = "Zedex";
    private static final String FILE = "favorites.json";

    private Favorites() {
    }

    /** Whether a key is already a favourite. */
    public static boolean has(Context context, String key) {
        for (JSONObject entry : read(context)) {
            if (key.equals(entry.optString("key", null))) return true;
        }
        return false;
    }

    /** Adds an entry, or moves it to the front if it was already one. */
    public static void add(Context context, Entry entry) {
        String key = entry.key();
        JSONObject added = new JSONObject();

        try {
            added.put("key", key);
            added.put("name", entry.name);
            added.put("uri", entry.uri.toString());
            added.put("inside", entry.inside);
            added.put("added", System.currentTimeMillis());
        } catch (JSONException e) {
            Log.w(TAG, "cannot record favourite " + key, e);
            return;
        }

        List<JSONObject> kept = new ArrayList<>();
        kept.add(added);
        for (JSONObject existing : read(context)) {
            if (!key.equals(existing.optString("key", null))) kept.add(existing);
        }

        write(context, kept);
    }

    /** Drops a favourite; harmless if it was not one. */
    public static void remove(Context context, String key) {
        List<JSONObject> kept = new ArrayList<>();

        for (JSONObject existing : read(context)) {
            if (!key.equals(existing.optString("key", null))) kept.add(existing);
        }

        write(context, kept);
    }

    /**
     * Every favourite, newest first.
     *
     * Reconstructed with only what was stored - the size and modified time a
     * listing would know are not among the fields written, so both come back
     * unknown. That is enough to open the game again, which is all a
     * favourite is kept for.
     */
    public static List<Entry> all(Context context) {
        List<Entry> entries = new ArrayList<>();

        for (JSONObject object : read(context)) {
            String uri = object.optString("uri", "");
            String name = object.optString("name", "");
            if (uri.isEmpty() || name.isEmpty()) continue;

            String inside = object.isNull("inside") ? null : object.optString("inside", null);
            Entry.Kind kind = inside != null ? Entry.Kind.ARCHIVE : Entry.Kind.FILE;

            entries.add(new Entry(kind, name, Uri.parse(uri), inside, -1, 0));
        }

        return entries;
    }

    private static File file(Context context) {
        return new File(Storage.root(context), FILE);
    }

    /** The stored objects, oldest write order preserved; empty on any failure. */
    private static List<JSONObject> read(Context context) {
        List<JSONObject> entries = new ArrayList<>();
        File file = file(context);
        if (!file.isFile()) return entries;

        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            int got;
            while (offset < bytes.length
                    && (got = in.read(bytes, offset, bytes.length - offset)) != -1) {
                offset += got;
            }

            JSONArray array = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                entries.add(array.getJSONObject(i));
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "unreadable favourites list", e);
        }

        return entries;
    }

    /**
     * Writes the whole list back, sorted newest first by {@code added} - the
     * only sort this file ever needs, done here once so every mutation keeps
     * the invariant {@link #all} relies on rather than sorting on every read.
     */
    private static void write(Context context, List<JSONObject> entries) {
        entries.sort((a, b) -> Long.compare(b.optLong("added", 0), a.optLong("added", 0)));

        JSONArray array = new JSONArray();
        for (JSONObject entry : entries) array.put(entry);

        File file = file(context);
        File directory = file.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            Log.w(TAG, "cannot make " + directory);
            return;
        }

        try (OutputStream out = new FileOutputStream(file)) {
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "cannot write favourites", e);
        }
    }
}
