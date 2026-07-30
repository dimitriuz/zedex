package dev.ldlab.zedex;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The cheats that came with the app: which game is loaded, and what to poke.
 *
 * Three and a half thousand games with pokes, and thirty four thousand file
 * fingerprints to find them by — built by {@code scripts/build-poke-db.py} out of
 * ZX Pokemaster's database, which in turn holds The Tipshop's collection. About
 * a megabyte in the APK.
 *
 * <b>Games are matched by the md5 of the file, not by its name.</b> A name is
 * whatever the person who dumped it felt like typing, while a hash is the same
 * everywhere — and measured against a real 10,794 file collection, hashing
 * recognised 73% of it and found pokes for a third. What it cannot do is match a
 * save state or a very new release, which is why there is a search by name as
 * well.
 *
 * SQLite because it is already in Android: the alternative was a packed binary
 * with a sorted index, which is half the size and several hundred lines. It has
 * to be copied out of the assets first, since a database inside an APK is inside
 * a zip and cannot be opened in place.
 */
final class PokeDatabase {

    private static final String TAG = "Zedex";

    private static final String ASSET = "pokes.db";

    /** Bumped when the asset changes, so the copy is replaced rather than kept. */
    private static final int VERSION = 1;

    /** One cheat: a name, and the bytes it writes. */
    static final class Trainer {
        final String name;
        final List<Poke> pokes = new ArrayList<>();

        Trainer(String name) {
            this.name = name;
        }

        /** Whether any of its pokes wants a value from the user. */
        boolean asks() {
            for (Poke poke : pokes) {
                if (poke.asks()) return true;
            }
            return false;
        }
    }

    /** One poke of a trainer, as the .pok format has it. */
    static final class Poke {
        final int bank;
        final int address;
        final int value;

        Poke(int bank, int address, int value) {
            this.bank = bank;
            this.address = address;
            this.value = value;
        }

        /**
         * 256 is the format's way of saying "ask" - a number of lives, usually.
         * The app has to put a number in its place before poking.
         */
        boolean asks() {
            return value > 0xff;
        }

        /**
         * Whether this is a plain write to the sixteen bit space, which is what
         * the app can do. Bank 8 means "wherever the machine is paged now"; a
         * numbered bank means a particular RAM page, which needs plumbing this
         * app has not got. Twenty two pokes out of seventy two thousand.
         */
        boolean plain() {
            return bank == 8;
        }
    }

    /** A game with cheats: its name, and the .pok text they are written in. */
    static final class Game {
        final int id;
        final String name;
        final String pokes;

        Game(int id, String name, String pokes) {
            this.id = id;
            this.name = name;
            this.pokes = pokes;
        }

        List<Trainer> trainers() {
            return parse(pokes);
        }
    }

    private final SQLiteDatabase database;

    private PokeDatabase(SQLiteDatabase database) {
        this.database = database;
    }

    /**
     * Opens it, copying the asset out on the first run or after an update.
     *
     * Returns null rather than throwing: a missing cheat database is a feature
     * that is not there, not a reason for the emulator to stop.
     */
    static PokeDatabase open(Context context) {
        File file = new File(context.getFilesDir(), ASSET);
        File stamp = new File(context.getFilesDir(), ASSET + "." + VERSION);

        if (!file.exists() || !stamp.exists()) {
            if (!copy(context, file, stamp)) return null;
        }

        try {
            return new PokeDatabase(SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY));
        } catch (SQLiteException e) {
            Log.e(TAG, "cannot open the poke database", e);
            return null;
        }
    }

    private static boolean copy(Context context, File file, File stamp) {
        try (InputStream in = context.getAssets().open(ASSET);
             OutputStream out = new FileOutputStream(file)) {

            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Log.e(TAG, "cannot unpack the poke database", e);
            return false;
        }

        // The stamp is written last, so an interrupted copy is done again rather
        // than opened as though it were whole.
        try {
            for (File old : context.getFilesDir().listFiles(
                    (dir, name) -> name.startsWith(ASSET + "."))) {
                old.delete();
            }
            return stamp.createNewFile();
        } catch (IOException | NullPointerException e) {
            return false;
        }
    }

    /**
     * The game a file belongs to, by the sixteen bytes of its md5, or null.
     *
     * In two steps, because of how Android's SQLite binds things:
     * {@code rawQuery} takes its arguments as strings and a hash is not a string,
     * while a compiled statement can {@code bindBlob} but can only return one
     * value. So the hash finds the id, and the id finds the game. Both are
     * indexed lookups, and the alternative - keeping the hashes as hex text - is
     * a megabyte more asset for nothing.
     */
    Game forHash(byte[] md5) {
        if (md5 == null || md5.length != 16) return null;

        long id;

        try (SQLiteStatement statement = database.compileStatement(
                "select game from file where md5 = ?")) {
            statement.bindBlob(1, md5);
            id = statement.simpleQueryForLong();
        } catch (SQLiteDoneException e) {
            return null;                    // no row: an unknown file
        } catch (SQLiteException e) {
            Log.e(TAG, "poke lookup failed", e);
            return null;
        }

        try (Cursor cursor = database.rawQuery(
                "select id, name, pokes from game where id = ?",
                new String[] { String.valueOf(id) })) {

            return cursor.moveToNext()
                    ? new Game(cursor.getInt(0), cursor.getString(1),
                               cursor.getString(2))
                    : null;
        } catch (SQLiteException e) {
            return null;
        }
    }

    /** Games whose name contains this, in name order; never null. */
    List<Game> search(String text, int limit) {
        List<Game> found = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return found;

        String like = "%" + text.trim() + "%";

        try (Cursor cursor = database.rawQuery(
                "select id, name, pokes from game where name like ?"
                + " order by name limit " + limit, new String[] { like })) {

            while (cursor.moveToNext()) {
                found.add(new Game(cursor.getInt(0), cursor.getString(1),
                                   cursor.getString(2)));
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "poke search failed", e);
        }

        return found;
    }

    /** Something from the meta table - where the data came from, how much. */
    String meta(String key) {
        try (Cursor cursor = database.rawQuery(
                "select value from meta where key = ?", new String[] { key })) {
            return cursor.moveToNext() ? cursor.getString(0) : null;
        } catch (SQLiteException e) {
            return null;
        }
    }

    void close() {
        database.close();
    }

    /**
     * The .pok format: {@code N} names a trainer, {@code M} and {@code Z} are its
     * pokes with {@code Z} the last of them, {@code Y} ends the file. Anything
     * else is ignored rather than refused - the collection is thirty years of
     * other people's typing.
     */
    static List<Trainer> parse(String text) {
        List<Trainer> trainers = new ArrayList<>();
        if (text == null) return trainers;

        Trainer current = null;

        for (String line : text.split("\r?\n")) {
            if (line.isEmpty()) continue;

            char kind = line.charAt(0);

            if (kind == 'N') {
                current = new Trainer(line.substring(1).trim());
                trainers.add(current);
            } else if ((kind == 'M' || kind == 'Z') && current != null) {
                String[] fields = line.substring(1).trim().split("\\s+");
                if (fields.length < 3) continue;

                try {
                    current.pokes.add(new Poke(Integer.parseInt(fields[0]),
                                               Integer.parseInt(fields[1]),
                                               Integer.parseInt(fields[2])));
                } catch (NumberFormatException e) {
                    // A malformed line is one poke lost, not a file rejected.
                }
            }
        }

        // A trainer with nothing in it is a heading for pokes that would not
        // parse; showing it would be offering to do nothing.
        trainers.removeIf(trainer -> trainer.pokes.isEmpty());

        return trainers;
    }
}
