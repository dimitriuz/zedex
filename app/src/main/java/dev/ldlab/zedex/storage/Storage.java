package dev.ldlab.zedex.storage;

import dev.ldlab.zedex.media.Media;
import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.screen.StartPanel;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.Locale;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the app keeps things, and where it looks for things.
 *
 * Save states and ROMs live under a root the user chooses. Fuse reaches both
 * by path with plain stdio, so the root has to be a directory the app can
 * write to without holding a permission — internal storage, or one of the
 * app-specific external ones — rather than an arbitrary folder granted
 * through the document picker.
 *
 * One thing cannot move: Android owns the preferences file. Fuse's own UI data —
 * the widget font, the status bitmaps — goes under {@code getFilesDir()} and is
 * found through argv[0]; see {@code Machine}.
 *
 * The folder to open files *from* has no such limit, because that goes through
 * the picker: any tree the user grants is fine.
 */
public final class Storage {

    private static final String TAG = "Zedex";

    public static final String KEY_STATES_ROOT = "statesRoot";
    public static final String KEY_CONTENT_TREE = "contentTree";
    /**
     * Whether the first run has been through. Not "have the folders been
     * chosen": leaving both where they are is an answer, and one nobody should
     * be asked for twice. See {@link StartPanel#showSetup}.
     */
    public static final String KEY_SETUP_DONE = "setupDone";
    /**
     * Whether the demo tape has been put in the tapes folder. Recorded so that
     * it is put there exactly once, ever: a tape that came back every launch
     * would be a file the user cannot delete, and it is theirs to delete.
     */
    public static final String KEY_DEMO_INSTALLED = "demoInstalled";

    /** The demo, staged into the assets from {@code demo/} by the build. */
    private static final String DEMO = "zedex.tap";

    private static final String STATES = "states";
    private static final String ROMS = "roms";
    private static final String TAPES = "tapes";
    private static final String DISKS = "disks";
    private static final String CARDS = "cards";
    private static final String SHOTS = "screenshots";
    private static final String FILMS = "recordings";

    /**
     * The DivMMC's firmware, which is not a machine ROM and not ours to ship.
     *
     * In the ROM folder because that is where the machine's own firmware goes
     * and where the user is already looking, but deliberately not named
     * {@code .rom}: {@link #haveRoms} takes any {@code .rom} as proof that the
     * emulator can start, and a folder holding this and nothing else would
     * start it into a machine with no ROM at all.
     */
    private static final String FIRMWARE = "divmmc.bin";

    private Storage() {
    }

    /** Whether a folder anywhere on storage can be used. */
    public static boolean canUseAnyFolder() {
        return Environment.isExternalStorageManager();
    }

    /**
     * Whether the data folder is one Android will not let the app at.
     *
     * Anywhere outside the app's own storage needs All files access, and
     * without it the folder does not fail cleanly: it can be stated, it can
     * often be written to, and listing it comes back empty rather than
     * refused. So a folder full of ROMs reads as a folder with none, and every
     * answer the app can offer - download a set, import a set - puts more
     * files somewhere it still cannot read.
     *
     * Asked of the roots rather than of the filesystem, because those are
     * exactly the places the app can use with no permission at all.
     */
    public static boolean needsAllFiles(Context context) {
        if (canUseAnyFolder()) return false;

        String chosen = root(context).getAbsolutePath();

        for (File usable : roots(context)) {
            if (chosen.equals(usable.getAbsolutePath())
                    || chosen.startsWith(usable.getAbsolutePath() + "/")) {
                return false;
            }
        }

        return true;
    }

    /**
     * The real path behind a document tree, or null if there is not one.
     *
     * Tree ids look like {@code primary:Games/Spectrum} or
     * {@code 0000-0000:Spectrum}: a volume and a path within it.
     */
    public static File pathFor(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            String[] parts = id.split(":", 2);
            String relative = parts.length > 1 ? parts[1] : "";

            File volume = "primary".equalsIgnoreCase(parts[0])
                    ? Environment.getExternalStorageDirectory()
                    : new File("/storage/" + parts[0]);

            return relative.isEmpty() ? volume : new File(volume, relative);
        } catch (Exception e) {
            Log.w(TAG, "no path behind " + tree, e);
            return null;
        }
    }

    /** Proves a folder is really writable rather than merely plausible. */
    public static boolean isWritable(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) return false;

        File probe = new File(directory, ".zedex");
        try {
            if (!probe.createNewFile() && !probe.exists()) return false;
            probe.delete();
            return true;
        } catch (java.io.IOException | SecurityException e) {
            Log.w(TAG, "cannot write to " + directory, e);
            return false;
        }
    }

    /** Directories the app can write to without holding any permission. */
    public static List<File> roots(Context context) {
        List<File> roots = new ArrayList<>();
        roots.add(context.getFilesDir());

        for (File external : context.getExternalFilesDirs(null)) {
            if (external != null && !roots.contains(external)) roots.add(external);
        }

        return roots;
    }

    public static String label(Context context, File root) {
        if (root.equals(context.getFilesDir())) return "Internal storage";

        try {
            if (Environment.isExternalStorageRemovable(root)) return "SD card";
        } catch (IllegalArgumentException e) {
            // Not a real external volume; fall through.
        }

        return "Shared storage";
    }

    /** Where save states live, falling back if the chosen root has gone away. */
    public static File statesDirectory(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);

        String chosen = preferences.getString(KEY_STATES_ROOT, null);
        if (chosen != null && !new File(chosen).isDirectory()) {
            // An SD card can be pulled out between runs.
            Log.w(TAG, "folder " + chosen + " is gone; using internal storage");
        }

        return new File(root(context), STATES);
    }

    /**
     * Where Fuse looks for ROMs. None are shipped, so this is the user's to
     * fill; it is created empty so there is somewhere obvious to put them.
     */
    public static File romsDirectory(Context context) {
        return new File(root(context), ROMS);
    }

    /**
     * The ROMs Fuse's machines need, from its own settings defaults in
     * `vendor/fuse-1.9.0/settings.c`.
     *
     * Machines only. Fuse names thirty-eight ROMs in all, but most of them are
     * peripherals - Multiface, the disk interfaces, the speech chips - and a
     * missing one of those costs a device nobody asked for, while a missing
     * machine ROM is a machine that will not boot. Listing all thirty-eight as
     * "missing" would bury the ones that matter.
     */
    private static final String[] MACHINE_ROMS = {
        "48.rom",
        "128-0.rom", "128-1.rom",
        "128p-0.rom", "128p-1.rom",
        "plus2-0.rom", "plus2-1.rom",
        "plus3-0.rom", "plus3-1.rom", "plus3-2.rom", "plus3-3.rom",
        "plus3e-0.rom", "plus3e-1.rom", "plus3e-2.rom", "plus3e-3.rom",
        "se-0.rom", "se-1.rom",
        "tc2048.rom", "tc2068-0.rom", "tc2068-1.rom",
        "256s-0.rom", "256s-1.rom", "256s-2.rom", "256s-3.rom",
        "trdos.rom", "gluck.rom",
    };

    /** Which of those are not in the ROM folder, in Fuse's own order. */
    public static List<String> missingRoms(Context context) {
        File directory = romsDirectory(context);
        Set<String> present = new HashSet<>();
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                present.add(file.getName().toLowerCase(Locale.ROOT));
            }
        }

        List<String> missing = new ArrayList<>();
        for (String rom : MACHINE_ROMS) {
            if (!present.contains(rom)) missing.add(rom);
        }
        return missing;
    }

    /**
     * Where the ROMs the app ships live inside the APK: the root of the
     * assets, because the folder at the root of the repository is merged in
     * there. {@code ""} is that root, and only what ends in .rom is a ROM.
     */
    private static final String ROM_ASSETS = "";

    /**
     * Puts the ROMs the app ships into the ROM folder, and leaves alone
     * anything already there.
     *
     * Fuse's own set, which Amstrad and the other holders allow to be
     * redistributed - see docs/ROMS.md. They are copied out rather than read
     * from the APK because Fuse opens ROMs by path, and into the ROM folder
     * rather than somewhere private because that folder is the one thing the
     * user is invited to fill themselves: a ROM of theirs with the same name
     * is theirs, and stays.
     *
     * Which is also why this only ever adds. The machines nobody may
     * redistribute a ROM for - Pentagon, Scorpion, the Spanish 128 - are still
     * missing afterwards, and the panel that says so is still how they arrive.
     */
    public static void installRoms(Context context) {
        File directory = romsDirectory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) return;

        String[] shipped;
        try {
            shipped = context.getAssets().list(ROM_ASSETS);
        } catch (IOException e) {
            Log.e(TAG, "cannot list the ROMs in the app", e);
            return;
        }

        if (shipped == null) return;

        for (String name : shipped) {
            // ROMs only: anything else that ends up in there is ours to keep
            // in the app, not to leave in a folder the user browses.
            if (!name.toLowerCase(Locale.ROOT).endsWith(".rom")) continue;

            File target = new File(directory, name);
            if (target.exists()) continue;

            try (InputStream in = context.getAssets().open(name);
                 OutputStream out = new FileOutputStream(target)) {

                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            } catch (IOException e) {
                Log.e(TAG, "cannot unpack " + name, e);
                target.delete();
            }
        }
    }

    /** The demo tape, wherever the tapes folder is, whether or not it is there. */
    public static File demoTape(Context context) {
        return new File(tapesDirectory(context), DEMO);
    }

    /**
     * Puts the demo in the tapes folder, once.
     *
     * Copied out of the APK for the same reason the ROMs are - Fuse opens files
     * by path - and into the tapes folder rather than somewhere private, so that
     * it is a tape like any other: openable from the recents list, deletable,
     * and there when somebody goes looking for something to load.
     *
     * Once and never again, recorded in a preference rather than by whether the
     * file is there. Restoring it on every launch would make it undeletable, and
     * a demo that will not go away is worse than no demo.
     *
     * @return the tape, or null if it is not there and could not be put there.
     */
    public static File installDemo(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);

        File target = demoTape(context);
        if (preferences.getBoolean(KEY_DEMO_INSTALLED, false)) {
            return target.isFile() ? target : null;
        }

        File directory = target.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            return null;
        }

        if (!target.exists()) {
            try (InputStream in = context.getAssets().open(DEMO);
                 OutputStream out = new FileOutputStream(target)) {

                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            } catch (IOException e) {
                Log.e(TAG, "cannot unpack the demo", e);
                target.delete();
                return null;
            }
        }

        preferences.edit().putBoolean(KEY_DEMO_INSTALLED, true).apply();
        return target;
    }

    public static boolean haveRoms(Context context) {
        File[] files = romsDirectory(context).listFiles();
        if (files == null) return false;

        for (File file : files) {
            if (file.getName().toLowerCase().endsWith(".rom")) return true;
        }
        return false;
    }

    /** The folder holding {@code roms} and {@code states}. */
    public static File root(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);

        String chosen = preferences.getString(KEY_STATES_ROOT, null);
        if (chosen != null) {
            File root = new File(chosen);
            if (root.isDirectory()) return root;
        }

        return context.getFilesDir();
    }

    /** Where tapes the machine SAVEs to are written. */
    public static File tapesDirectory(Context context) {
        return new File(root(context), TAPES);
    }

    /** Where disks written back out are kept. */
    public static File disksDirectory(Context context) {
        return new File(root(context), DISKS);
    }

    /**
     * Where DivMMC card images are kept.
     *
     * Their own folder rather than living with the disks, because they are not
     * disks in any sense the rest of the app means it: one is tens of megabytes
     * of filesystem, it is written to in place while the machine runs, and it
     * has to stay put - a card in the cache would be swept away with the game
     * saves on it.
     */
    public static File cardsDirectory(Context context) {
        return new File(root(context), CARDS);
    }

    /** The DivMMC firmware, whether or not it is there yet. */
    public static File divmmcFirmware(Context context) {
        return new File(romsDirectory(context), FIRMWARE);
    }

    /** Where screenshots are written. */
    public static File screenshotsDirectory(Context context) {
        return new File(root(context), SHOTS);
    }

    /** Where recordings are written. */
    public static File recordingsDirectory(Context context) {
        return new File(root(context), FILMS);
    }

    /** The folders exist from the first run, empty if need be. */
    public static void createFolders(Context context) {
        statesDirectory(context).mkdirs();
        romsDirectory(context).mkdirs();
        tapesDirectory(context).mkdirs();
        disksDirectory(context).mkdirs();
        cardsDirectory(context).mkdirs();
        screenshotsDirectory(context).mkdirs();
        recordingsDirectory(context).mkdirs();
    }

    /**
     * Moves a folder's contents. Small files, and only when the data folder
     * changes, so this is done in place rather than in the background.
     */
    public static void move(Context context, File from, File to) {
        File[] files = from.listFiles();
        if (files == null || files.length == 0) return;

        if (!to.isDirectory() && !to.mkdirs()) {
            Log.w(TAG, "cannot create " + to);
            return;
        }

        for (File file : files) {
            File target = new File(to, file.getName());
            if (!file.renameTo(target) && !copy(file, target)) {
                Log.w(TAG, "cannot move " + file + " to " + target);
                continue;
            }
            file.delete();
        }
    }

    /** renameTo does not work across volumes. */
    private static boolean copy(File from, File to) {
        try (java.io.InputStream in = new java.io.FileInputStream(from);
             java.io.OutputStream out = new java.io.FileOutputStream(to)) {

            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return true;
        } catch (java.io.IOException e) {
            Log.w(TAG, "cannot copy " + from, e);
            return false;
        }
    }

    /** The folder the file picker should open in, or null for wherever it likes. */
    public static Uri contentFolder(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);

        String stored = preferences.getString(KEY_CONTENT_TREE, null);
        if (stored == null) return null;

        try {
            Uri tree = Uri.parse(stored);
            return DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
        } catch (Exception e) {
            Log.w(TAG, "cannot use content folder " + stored, e);
            return null;
        }
    }

    /**
     * The same folder as a document URI, so a file manager can be pointed at
     * it, or null when there is no such thing.
     *
     * Only shared storage has one. A data folder inside the app's own
     * directory is invisible to the rest of the system by design, and no
     * intent will open it.
     */
    public static Uri documentUriFor(File folder) {
        try {
            // Through the canonical path, because /sdcard, /storage/self/
            // primary and /storage/emulated/0 are all the same place and
            // only the last of them is what getExternalStorageDirectory
            // answers with.
            String path = folder.getCanonicalPath();
            String root = Environment.getExternalStorageDirectory()
                                     .getCanonicalPath();

            if (!path.startsWith(root + "/")) return null;

            return DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:" + path.substring(root.length() + 1));
        } catch (Exception e) {
            Log.w(TAG, "no document uri for " + folder, e);
            return null;
        }
    }

    /** Something readable for the settings screen, e.g. "primary:Download". */
    public static String describe(String treeUri) {
        if (treeUri == null) return null;

        try {
            return DocumentsContract.getTreeDocumentId(Uri.parse(treeUri));
        } catch (Exception e) {
            return treeUri;
        }
    }

    /**
     * What a picked document is called, sanitised into a filename.
     *
     * libspectrum uses the extension as a hint about the format, so a staged
     * copy has to keep the name it arrived with. Both the ROM importer and the
     * media staging want this, which is why it lives here.
     */
    public static String displayName(Context context, Uri uri) {
        String name = null;

        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[] { android.provider.OpenableColumns.DISPLAY_NAME },
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                name = cursor.getString(0);
            }
        } catch (Exception e) {
            android.util.Log.w("Zedex", "cannot read the name of " + uri, e);
        }

        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "spectrum.tap";

        name = name.replace('/', '_').replace('\\', '_');
        return name.isEmpty() ? "spectrum.tap" : name;
    }

    /**
     * Keeps a name to something that is safe as a filename.
     *
     * Here because three places want it and each had written its own: the
     * emulator names a state after what is loaded, the states screen takes a
     * name that was typed, and {@link Media} names a tape or a disk. Three
     * copies of one regular expression is three chances for them to disagree
     * about what is safe.
     */
    public static String sanitise(String name) {
        return name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
    }

    /**
     * A filename with its extension taken off, which is what media is named
     * after: "Tujad.z80" is a game called Tujad.
     */
    public static String withoutExtension(String name) {
        int dot = name.lastIndexOf('.');

        return sanitise(dot > 0 ? name.substring(0, dot) : name);
    }
}
