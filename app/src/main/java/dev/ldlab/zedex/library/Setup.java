package dev.ldlab.zedex.library;

import dev.ldlab.zedex.storage.Storage;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * What somebody decided about how one game should run.
 *
 * A scraped record can say a game wants a 128K and speaks Kempston; only a
 * person can say whether to act on that. This remembers the answer, so the
 * question is asked once per game rather than every time it is opened.
 *
 * <b>Deliberately not in {@code Meta}.</b> That is scraped data and a re-scrape
 * rewrites it wholesale - a decision stored there would vanish the next time
 * the collection was swept, silently, and the game would start asking again
 * with no indication why. This is the user's answer, so it lives with the
 * other things the user decided: its own small file in the data folder, the
 * same shape {@code Favorites} uses.
 *
 * A missing, unreadable or corrupt file reads as nobody having decided
 * anything - logged, never thrown. Losing these means being asked again, which
 * is a nuisance; a crash on opening a game would be a great deal worse.
 */
public final class Setup {

    private static final String TAG = "Zedex";
    private static final String FILE = "setup.json";

    private Setup() {
    }

    /**
     * What was decided for this game, or null if nobody has been asked yet.
     *
     * The two states that are not null are both meaningful: an {@link Answer}
     * with something in it is applied without asking, and one that {@link
     * Answer#skip}s means the question was declined and should stay declined.
     */
    public static Answer remembered(Context context, String path) {
        if (path == null) return null;

        JSONObject games = read(context).optJSONObject("games");
        JSONObject one = games == null ? null : games.optJSONObject(path);
        if (one == null) return null;

        return new Answer(one.optBoolean("skip", false),
                          one.isNull("machine") ? null : one.optString("machine", null),
                          one.isNull("joystick") ? null : one.optString("joystick", null));
    }

    /** Remembers an answer, replacing whatever was there. */
    public static synchronized void remember(Context context, String path, Answer answer) {
        if (path == null || answer == null) return;

        JSONObject all = read(context);

        try {
            JSONObject games = all.optJSONObject("games");
            if (games == null) {
                games = new JSONObject();
                all.put("games", games);
            }

            JSONObject one = new JSONObject();
            if (answer.skip) one.put("skip", true);
            if (answer.machine != null) one.put("machine", answer.machine);
            if (answer.joystick != null) one.put("joystick", answer.joystick);

            games.put(path, one);
        } catch (JSONException e) {
            Log.w(TAG, "cannot record a setup answer", e);
            return;
        }

        write(context, all);
    }

    /** Forgets one game's answer, so it is asked about again. */
    public static synchronized void forget(Context context, String path) {
        JSONObject all = read(context);
        JSONObject games = all.optJSONObject("games");

        if (games == null || !games.has(path)) return;

        games.remove(path);
        write(context, all);
    }

    /**
     * One game's answer.
     *
     * <b>Stored by name, not by index.</b> The machine is Fuse's own id and
     * the joystick its own interface name, because an index into either list
     * is only meaningful against the build that wrote it - a machine added to
     * Fuse would silently repoint every remembered answer at its neighbour,
     * and nobody would connect the two.
     */
    public static final class Answer {

        /** The question was declined, and should not be asked again. */
        public final boolean skip;

        /** A Fuse machine id, or null to leave the machine alone. */
        public final String machine;

        /** A Fuse joystick interface name, or null to leave it alone. */
        public final String joystick;

        public Answer(boolean skip, String machine, String joystick) {
            this.skip = skip;
            this.machine = machine;
            this.joystick = joystick;
        }

        /** Whether applying this would do anything at all. */
        public boolean anything() {
            return !skip && (machine != null || joystick != null);
        }
    }

    // --- the file ------------------------------------------------------------------

    private static File file(Context context) {
        return new File(Storage.root(context), FILE);
    }

    private static JSONObject read(Context context) {
        File file = file(context);
        if (!file.isFile()) return new JSONObject();

        try {
            String text = new String(Files.readAllBytes(file.toPath()),
                                     StandardCharsets.UTF_8);
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "cannot read " + file + "; nobody has decided anything", e);
            return new JSONObject();
        }
    }

    private static void write(Context context, JSONObject all) {
        File file = file(context);
        File directory = file.getParentFile();

        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            Log.w(TAG, "cannot make " + directory);
            return;
        }

        // A temporary file first, the same as the metadata store: a write that
        // fails partway must not leave this one unreadable, which would throw
        // away every answer rather than the one being written.
        File temp = new File(directory, FILE + ".tmp");

        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(all.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "cannot write " + file, e);
            temp.delete();
            return;
        }

        if (!temp.renameTo(file)) {
            Log.w(TAG, "cannot replace " + file);
            temp.delete();
        }
    }
}
