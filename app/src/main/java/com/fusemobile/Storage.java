package com.fusemobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
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
 * Two things cannot move. Fuse's own UI data, the widget font and the status
 * bitmaps, is unpacked to the {@code FUSEDATADIR} baked in when it was
 * compiled, and Android owns the preferences file.
 *
 * The folder to open files *from* has no such limit, because that goes through
 * the picker: any tree the user grants is fine.
 */
final class Storage {

    private static final String TAG = "FuseMobile";

    static final String KEY_STATES_ROOT = "statesRoot";
    static final String KEY_CONTENT_TREE = "contentTree";

    private static final String STATES = "states";
    private static final String ROMS = "roms";

    private Storage() {
    }

    /** Whether a folder anywhere on storage can be used. */
    static boolean canUseAnyFolder() {
        return Environment.isExternalStorageManager();
    }

    /**
     * The real path behind a document tree, or null if there is not one.
     *
     * Tree ids look like {@code primary:Games/Spectrum} or
     * {@code 0000-0000:Spectrum}: a volume and a path within it.
     */
    static File pathFor(Uri tree) {
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
    static boolean isWritable(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) return false;

        File probe = new File(directory, ".fusemobile");
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
    static List<File> roots(Context context) {
        List<File> roots = new ArrayList<>();
        roots.add(context.getFilesDir());

        for (File external : context.getExternalFilesDirs(null)) {
            if (external != null && !roots.contains(external)) roots.add(external);
        }

        return roots;
    }

    static String label(Context context, File root) {
        if (root.equals(context.getFilesDir())) return "Internal storage";

        try {
            if (Environment.isExternalStorageRemovable(root)) return "SD card";
        } catch (IllegalArgumentException e) {
            // Not a real external volume; fall through.
        }

        return "Shared storage";
    }

    /** Where save states live, falling back if the chosen root has gone away. */
    static File statesDirectory(Context context) {
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
    static File romsDirectory(Context context) {
        return new File(root(context), ROMS);
    }

    static boolean haveRoms(Context context) {
        File[] files = romsDirectory(context).listFiles();
        if (files == null) return false;

        for (File file : files) {
            if (file.getName().toLowerCase().endsWith(".rom")) return true;
        }
        return false;
    }

    /** The folder holding {@code roms} and {@code states}. */
    static File root(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);

        String chosen = preferences.getString(KEY_STATES_ROOT, null);
        if (chosen != null) {
            File root = new File(chosen);
            if (root.isDirectory()) return root;
        }

        return context.getFilesDir();
    }

    /** Both folders exist from the first run, empty if need be. */
    static void createFolders(Context context) {
        statesDirectory(context).mkdirs();
        romsDirectory(context).mkdirs();
    }

    /**
     * Moves a folder's contents. Small files, and only when the data folder
     * changes, so this is done in place rather than in the background.
     */
    static void move(Context context, File from, File to) {
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
    static Uri contentFolder(Context context) {
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

    /** Something readable for the settings screen, e.g. "primary:Download". */
    static String describe(String treeUri) {
        if (treeUri == null) return null;

        try {
            return DocumentsContract.getTreeDocumentId(Uri.parse(treeUri));
        } catch (Exception e) {
            return treeUri;
        }
    }
}
