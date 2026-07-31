package dev.ldlab.zedex.storage;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.screen.StatesActivity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Saved states: the files, and what can be done to them.
 *
 * Named files rather than numbered slots, so there can be as many as wanted and
 * each says what it is. A state is a snapshot plus a thumbnail sharing its base
 * name; the snapshot's extension decides its format, so a state written before
 * the format setting changed still loads.
 *
 * A class of its own because two screens need it: the emulator, for the
 * controller's quick save and quick load, and {@link StatesActivity}, which is
 * the list. Neither owns the files, so neither should own the arithmetic.
 */
public final class States {

    private static final String TAG = "Zedex";

    /** Everything that counts as a state, best first. */
    public static final String[] FORMATS = { "szx", "z80", "sna" };

    /** One saved state: the snapshot on disk and the name it goes by. */
    public static final class Saved {
        public final File snapshot;
        public final String name;

        Saved(File snapshot, String name) {
            this.snapshot = snapshot;
            this.name = name;
        }

        public String format() {
            String file = snapshot.getName();
            return file.substring(file.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        }
    }

    /**
     * What a new state is called by default: the media that is loaded. Written
     * by whoever loads something and read by whoever names a state, which is
     * two screens now, so the key lives with the states.
     */
    public static final String KEY_MEDIA_NAME = "mediaName";

    private States() { }

    public static File directory(Context context) {
        return Storage.statesDirectory(context);
    }

    public static File thumbnailFor(Context context, String name) {
        return new File(directory(context), name + ".thumb");
    }

    /** Newest first, which is nearly always the one wanted. */
    public static List<Saved> all(Context context) {
        List<Saved> states = new ArrayList<>();
        File[] files = directory(context).listFiles();
        if (files == null) return states;

        for (File file : files) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) continue;

            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (Arrays.asList(FORMATS).contains(extension)) {
                states.add(new Saved(file, name.substring(0, dot)));
            }
        }

        states.sort((a, b) -> Long.compare(b.snapshot.lastModified(),
                                           a.snapshot.lastModified()));
        return states;
    }

    /** Whichever format carries this name, or null where none does. */
    public static File find(Context context, String name) {
        for (String format : FORMATS) {
            File file = new File(directory(context), name + "." + format);
            if (file.exists()) return file;
        }
        return null;
    }

    /**
     * Writes the machine as it is. False only where the folder cannot be made;
     * the writing itself is queued to the emulation thread like everything
     * else, and reports through Fuse's own error path if it fails.
     */
    public static boolean save(Context context, SharedPreferences preferences, String name) {
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) return false;

        String format = preferences.getString(
                SettingsActivity.KEY_SNAPSHOT_FORMAT, FORMATS[0]);

        // One snapshot per name, whatever it was saved as before.
        for (String other : FORMATS) {
            if (!other.equals(format)) new File(directory, name + "." + other).delete();
        }

        FuseNative.saveSnapshot(new File(directory, name + "." + format)
                                        .getAbsolutePath());
        FuseNative.saveThumbnail(thumbnailFor(context, name).getAbsolutePath());
        return true;
    }

    /**
     * What to call a new state: whatever is loaded, which is nearly always what
     * it is a state of, with a number where that name is taken.
     *
     * A reset or a machine change empties the machine, so there is nothing left
     * to name a state after and they are simply numbered instead.
     */
    public static String suggest(Context context, SharedPreferences preferences) {
        String media = preferences.getString(KEY_MEDIA_NAME, null);

        if (media == null || media.isEmpty()) {
            for (int n = 1; n < 1000; n++) {
                String numbered = context.getString(R.string.state_default_name, n);
                if (find(context, numbered) == null) return numbered;
            }
            return context.getString(R.string.state_default_name, 1);
        }

        if (find(context, media) == null) return media;

        for (int n = 2; n < 1000; n++) {
            if (find(context, media + " " + n) == null) return media + " " + n;
        }
        return media;
    }

    public static void load(Saved state) {
        FuseNative.loadSnapshot(state.snapshot.getAbsolutePath());
    }

    public static void delete(Context context, Saved state) {
        state.snapshot.delete();
        thumbnailFor(context, state.name).delete();
    }

    /**
     * A state is two files sharing a base name, so both move or the row loses
     * its picture. The snapshot keeps its extension: the format it was saved in
     * is the format that will load it, whatever the setting says now.
     */
    public static boolean rename(Context context, Saved state, String name) {
        String file = state.snapshot.getName();
        String extension = file.substring(file.lastIndexOf('.'));

        if (!state.snapshot.renameTo(new File(directory(context), name + extension))) {
            return false;
        }

        File thumbnail = thumbnailFor(context, state.name);
        if (thumbnail.exists()) thumbnail.renameTo(thumbnailFor(context, name));

        return true;
    }

    /** Decodes what {@link FuseNative#saveThumbnail} wrote. */
    public static Bitmap thumbnail(File file) {
        if (!file.exists()) return null;

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {

            byte[] header = new byte[8];
            in.readFully(header);
            ByteBuffer numbers = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int width = numbers.getInt();
            int height = numbers.getInt();

            if (width <= 0 || height <= 0 || width > 2048 || height > 2048) return null;

            byte[] pixels = new byte[width * height * 4];
            in.readFully(pixels);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
            return bitmap;
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "cannot read thumbnail " + file, e);
            return null;
        }
    }
}
