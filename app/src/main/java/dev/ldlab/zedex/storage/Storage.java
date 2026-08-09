package dev.ldlab.zedex.storage;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.media.Media;
import dev.ldlab.zedex.screen.StartPanel;
import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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

    /**
     * The data folder whose ROM folder could not be used, if there was one; see
     * {@link #romsDirectory}. Not a boolean, so that choosing another folder
     * needs nothing cleared.
     */
    private static final String KEY_ROMS_ELSEWHERE = "romsElsewhere";

    /** The demo, staged into the assets from {@code demo/} by the build. */
    private static final String DEMO = "zedex.tap";

    private static final String STATES = "states";
    private static final String ROMS = "roms";
    private static final String TAPES = "tapes";
    private static final String DISKS = "disks";
    /** Public because a data-folder move has to find the card again by
     *  name; see SettingsActivity.repointCard. */
    public static final String CARDS = "cards";

    /** The scraped metadata store's folder; see {@code library/meta/Metadata}. */
    private static final String LIBRARY = "library";

    /**
     * Artwork, video and manuals this app fetched for itself.
     *
     * Kept apart from ES-DE's own {@code downloaded_media}, which the app has
     * only ever read: writing into another app's data at the scale of a whole
     * collection would leave no way to tell what we fetched from what they
     * did, and would need ES-DE installed and a writable grant besides. See
     * {@code Artwork}, which reads both and prefers this one.
     */
    private static final String MEDIA = "media";
    private static final String SHOTS = "screenshots";

    /** What a folder is called when it tells the media scanner to walk past. */
    private static final String NOMEDIA = ".nomedia";

    /**
     * What the app calls its own folder, wherever that folder is.
     *
     * A resource rather than a constant, because the debug build must not share
     * one with the release build: different packages are different uids, and
     * scoped storage hands an app only what it wrote itself, so the second one
     * installed would find an empty folder whose filenames were all taken by
     * files it cannot see. See {@code src/debug/res/values/strings.xml}.
     */
    private static String folderName(Context context) {
        return context.getString(R.string.data_folder);
    }

    /**
     * Whether this build declares All files access, cached because the answer
     * cannot change while the process lives.
     */
    private static Boolean declaresAllFiles;

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
     * Whether this build can even ask for All files access.
     *
     * The Play build does not declare {@code MANAGE_EXTERNAL_STORAGE}: Play
     * judges an app by what its manifest asks for rather than by what it does
     * with it, and broad access is only for apps that cannot work without it -
     * which this one can, since {@link #defaultRoot} needs no permission at
     * all. So that build has no permission to be granted, and the settings
     * page the grant button opens would have nothing on it.
     *
     * Asked of the manifest rather than of {@code BuildConfig}, so it is right
     * in every variant with nothing to keep in step.
     */
    public static boolean canAskForAnyFolder(Context context) {
        if (declaresAllFiles == null) {
            declaresAllFiles = false;
            try {
                String[] asked = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(),
                                        PackageManager.GET_PERMISSIONS)
                        .requestedPermissions;

                if (asked != null) {
                    for (String permission : asked) {
                        if (Manifest.permission.MANAGE_EXTERNAL_STORAGE.equals(permission)) {
                            declaresAllFiles = true;
                            break;
                        }
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "cannot read our own manifest", e);
            }
        }

        return declaresAllFiles;
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
        return needsAllFilesFor(context, root(context));
    }

    /** The same question about a folder that has not been chosen yet. */
    public static boolean needsAllFilesFor(Context context, File folder) {
        if (canUseAnyFolder()) return false;

        String chosen = folder.getAbsolutePath();

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

    /**
     * Proves a folder is really writable rather than merely plausible.
     *
     * The probe is a folder, not a file, and that is the whole subtlety: a media
     * collection takes only what belongs in it, so creating {@code .zedex} inside
     * {@code Pictures/} fails with "Operation not permitted" for having no
     * extension MediaProvider recognises - on a device where every screenshot
     * the app writes there works perfectly. It said the captures folder was
     * unusable and put the first screenshot after an install somewhere else.
     * A directory has no type to disagree with, and mkdir is refused by exactly
     * the permission this is asking about.
     */
    public static boolean isWritable(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return refused(directory, "not a folder and cannot be made one");
        }

        // A name of its own each time. The old fixed .zedex could be refused for
        // a file that was not there: MediaProvider keeps a row per path, and a
        // stale one - a probe written while the app held All files access, whose
        // row outlived the file - makes the name taken to createNewFile and
        // absent to exists(), which is a silent no. That is what refused a
        // tablet's Documents folder, and what made it work again an hour later
        // once something else had cleared the row.
        File probe = new File(directory, ".zedex-" + System.nanoTime());
        try {
            if (!probe.mkdir()) {
                return refused(directory, "cannot make " + probe.getName());
            }
            probe.delete();
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "cannot write to " + directory, e);
            return false;
        }
    }

    /**
     * Says why a folder was refused, and never refuses in silence.
     *
     * The refusal a user reported - "that folder cannot be written to directly"
     * for a folder plainly there and plainly theirs - left nothing whatever in
     * the log, so the whole of the diagnosis was guesswork about which of two
     * silent returns it had been.
     */
    private static boolean refused(File directory, String why) {
        Log.w(TAG, "cannot use " + directory + ": " + why
                + " (isDirectory=" + directory.isDirectory()
                + " exists=" + directory.exists()
                + " canRead=" + directory.canRead()
                + " canWrite=" + directory.canWrite() + ")");
        return false;
    }

    /**
     * Directories the app can write to without holding any permission.
     *
     * The folder in Documents comes first because it is the only one of them a
     * person can open in a file manager; see {@link #documentsRoot}.
     */
    public static List<File> roots(Context context) {
        List<File> roots = new ArrayList<>();
        roots.add(documentsRoot(context));
        roots.add(context.getFilesDir());

        for (File external : context.getExternalFilesDirs(null)) {
            if (external != null && !roots.contains(external)) roots.add(external);
        }

        return roots;
    }

    public static String label(Context context, File root) {
        if (root.equals(context.getFilesDir()))
            return context.getString(R.string.storage_internal);
        if (root.equals(documentsRoot(context)))
            return context.getString(R.string.storage_documents);

        try {
            if (Environment.isExternalStorageRemovable(root))
                return context.getString(R.string.storage_sd_card);
        } catch (IllegalArgumentException e) {
            // Not a real external volume; fall through.
        }

        return context.getString(R.string.storage_shared);
    }

    /** Where save states live, falling back if the chosen root has gone away. */
    public static File statesDirectory(Context context) {
        SharedPreferences preferences = prefs(context);

        String chosen = preferences.getString(KEY_STATES_ROOT, null);
        if (chosen != null && !new File(chosen).isDirectory()) {
            // An SD card can be pulled out between runs.
            Log.w(TAG, "folder " + chosen + " is gone; using internal storage");
        }

        return new File(root(context), STATES);
    }

    /**
     * Where Fuse looks for ROMs: in the data folder, or in the app's own storage
     * when that one turned out to be unusable.
     *
     * The fallback is remembered against the root it applies to rather than as a
     * plain flag, so pointing the app at a different data folder heals it with
     * nothing to reset: a root that is not the one that failed is simply tried.
     */
    public static File romsDirectory(Context context) {
        File shared = new File(root(context), ROMS);

        SharedPreferences preferences = prefs(context);
        String failed = preferences.getString(KEY_ROMS_ELSEWHERE, null);

        return failed != null && failed.equals(root(context).getAbsolutePath())
                ? privateRoms(context) : shared;
    }

    /** The app's own ROM folder, which no other install can spoil. */
    private static File privateRoms(Context context) {
        return new File(context.getFilesDir(), ROMS);
    }

    private static void usePrivateRoms(Context context) {
        prefs(context)
               .edit()
               .putString(KEY_ROMS_ELSEWHERE, root(context).getAbsolutePath())
               .apply();
    }

    /** Forgets a fallback that is no longer needed - the folder may be fixed. */
    private static void useSharedRoms(Context context) {
        SharedPreferences preferences = prefs(context);

        if (preferences.contains(KEY_ROMS_ELSEWHERE)) {
            preferences.edit().remove(KEY_ROMS_ELSEWHERE).apply();
        }
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
        if (unpackRoms(context, new File(root(context), ROMS))) {
            useSharedRoms(context);
            return;
        }

        /*
         * The data folder's ROM folder cannot be used, so the ROMs go in the
         * app's own storage instead and Fuse is pointed there.
         *
         * This is not a folder that is merely full: it is one holding files
         * whose names are the ones needed and whose contents cannot be read.
         * Scoped storage does that whenever the folder outlived the install that
         * made it - a reinstall gets a new uid, and MediaProvider clears the
         * ownership of what the old one wrote, so twenty-nine ROMs are suddenly
         * twenty-nine names in the way. It happens between the debug build and
         * the release one too.
         *
         * The old behaviour was the ROMs panel, offering to download a set into
         * the same unusable folder. Every machine the app ships a ROM for can
         * start regardless, so it starts; the panel is left for the machines
         * whose ROMs really are missing.
         */
        Log.w(TAG, "the data folder's ROMs cannot be used; keeping them privately");
        usePrivateRoms(context);
        unpackRoms(context, privateRoms(context));
    }

    /**
     * Puts every shipped ROM into {@code directory}.
     *
     * @return whether the folder can hold them: false the moment one cannot be
     *         written and is not already there and readable, which is what the
     *         caller falls back on. A ROM of the user's own with the same name
     *         is theirs and counts as readable, so it is left exactly as it was.
     */
    private static boolean unpackRoms(Context context, File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) return false;

        String[] shipped;
        try {
            shipped = context.getAssets().list(ROM_ASSETS);
        } catch (IOException e) {
            Log.e(TAG, "cannot list the ROMs in the app", e);
            return false;
        }

        if (shipped == null) return false;

        for (String name : shipped) {
            // ROMs only: anything else that ends up in there is ours to keep
            // in the app, not to leave in a folder the user browses.
            if (!name.toLowerCase(Locale.ROOT).endsWith(".rom")) continue;

            File target = new File(directory, name);

            // exists() is not enough. A file left by an install that is gone
            // exists, has the right length, and cannot be opened - and the old
            // code took that for "already there" and skipped all of them.
            if (target.canRead() && target.length() > 0) continue;

            // Something is in the way. Ours to clear if Android will let us;
            // if it will not, this folder is no use for ROMs.
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "cannot replace " + target);
                return false;
            }

            try (InputStream in = context.getAssets().open(name);
                 OutputStream out = new FileOutputStream(target)) {

                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            } catch (IOException e) {
                Log.e(TAG, "cannot unpack " + name, e);
                target.delete();
                return false;
            }
        }

        return true;
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
        SharedPreferences preferences = prefs(context);

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
        SharedPreferences preferences = prefs(context);

        String chosen = preferences.getString(KEY_STATES_ROOT, null);
        if (chosen != null) {
            File root = new File(chosen);
            if (root.isDirectory()) return root;

            // Falling back, having been told otherwise - and this is worth a
            // line in the log every time, because the app carries on as though
            // nothing happened while every path it hands out has changed
            // underneath whatever wrote the last one.
            //
            // isDirectory() is not only "was it deleted": a folder in shared
            // storage answers false while All files access is not held, so this
            // fires on a permission that has been revoked, and on any moment
            // early enough that one has not been granted yet. Whatever is
            // written in that window goes to the default root and is invisible
            // afterwards - which is how a linked library came to report itself
            // as never linked, with its gamelist.xml sitting in the folder the
            // app used to think was its own.
            Log.w(TAG, "data folder " + chosen + " is not readable; using "
                       + defaultRoot(context) + " instead. Anything written now"
                       + " will not be found once it is readable again.");
        }

        return defaultRoot(context);
    }

    /**
     * {@code /storage/emulated/0/Zedex} - reachable by anything, and needing
     * All files access to make, so only the builds that declare it can offer
     * this. See {@link #canAskForAnyFolder}.
     */
    public static File sharedRoot(Context context) {
        return new File(Environment.getExternalStorageDirectory(),
                        folderName(context));
    }

    /**
     * {@code /storage/emulated/0/Documents/Zedex} - where the files go if
     * nobody says otherwise.
     *
     * Scoped storage lets an app make a folder of its own inside a public
     * collection and use it by path, with no permission whatsoever, which is
     * exactly what Fuse's stdio needs. A folder at the *root* of shared storage
     * is the one thing it will not allow: {@code mkdirs} there returns false
     * and the write fails with ENOENT. Measured on API 36 rather than read
     * somewhere.
     *
     * What this does not get is any file the app did not write itself. A tape
     * copied in from a computer is owned by nobody the app knows and
     * {@code .tap} is not media, so opening it fails with EACCES and - worse -
     * it does not appear in a listing at all: the folder reads as empty. Those
     * arrive through the picker instead, which stages them; see
     * {@link Media#stage}.
     *
     * The alternatives are both worse. Internal storage is not somewhere a file
     * manager will go, and neither is {@code Android/data} - closed to browsers
     * since Android 11 and hidden outright since 13 - so a hundred save states
     * and every screenshot end up where the person who made them cannot open
     * them.
     */
    public static File documentsRoot(Context context) {
        File documents = new File(Environment.getExternalStorageDirectory(),
                                  Environment.DIRECTORY_DOCUMENTS);
        return new File(documents, folderName(context));
    }

    /**
     * Where the files go when nobody has chosen: the folder in Documents, or
     * the app's own storage on a device that will not have it.
     *
     * Deliberately the same answer whether or not All files access is held, so
     * that granting it later does not move anybody's files. It widens what
     * {@link #roots} will accept; it does not change where things start.
     */
    public static File defaultRoot(Context context) {
        if (isWritable(documentsRoot(context))) return documentsRoot(context);

        for (File external : context.getExternalFilesDirs(null)) {
            if (external != null && isWritable(external)) return external;
        }

        return context.getFilesDir();
    }

    /**
     * Writes down where the files are, so that the answer cannot change under
     * somebody later.
     *
     * {@link #root} falls back to {@link #defaultRoot} when nothing is stored,
     * and what that returns depends on a permission - so an install that took
     * the old default and was granted All files access for some other reason
     * would silently start looking in a different folder, and every state ever
     * saved would appear to have gone. Pinning the answer the first time it is
     * asked for makes the preference the only thing that decides.
     */
    public static void pinRoot(Context context) {
        SharedPreferences preferences = prefs(context);

        if (preferences.contains(KEY_STATES_ROOT)) return;

        // Not on the very first run, which has not chosen yet and is detected
        // by the preferences being empty - writing one here told the panel it
        // had already been through setup, and the first thing a new install
        // saw was the machine rather than the question. The first run pins its
        // own answer when it finishes.
        if (preferences.getAll().isEmpty()) return;

        preferences.edit()
                .putString(KEY_STATES_ROOT, root(context).getAbsolutePath())
                .apply();
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

    /**
     * Where screenshots and recordings go: {@code Pictures/Zedex}.
     *
     * Not in the data folder with everything else, and for one reason - a
     * screenshot that cannot be found in the gallery afterwards is a screenshot
     * taken for nothing. An image written anywhere under {@code Documents} is
     * not in {@code MediaStore.Images} at all, so no gallery shows it.
     * {@code Pictures} is a collection the gallery reads, and one an app may
     * write to with no permission, exactly like the data folder.
     *
     * All three kinds together, in {@code Pictures} rather than the MP4s going
     * to {@code Movies} where they would seem to belong, because a public
     * collection refuses what does not match it. Measured with the permission
     * denied, which is how the Play build always runs:
     *
     * <pre>
     *   Pictures/  png ok    gif ok     mp4 ok     tap EPERM
     *   Movies/    png EPERM gif EPERM  mp4 ok     tap EPERM
     * </pre>
     *
     * A GIF is an image, and the GIF is the recording the app makes by default -
     * so splitting them would have failed on the commonest recording there is,
     * and only on the build nobody tests by hand. One folder takes all three,
     * MediaStore still files each by its own type, and the menu row that opens
     * the folder has one folder to open.
     */
    public static File capturesDirectory(Context context) {
        File ours = new File(
                new File(Environment.getExternalStorageDirectory(),
                         Environment.DIRECTORY_PICTURES),
                folderName(context));

        // The cheap test first: this is asked for on every screenshot, and once
        // the folder is there it is only a stat.
        if (ours.isDirectory() || isWritable(ours)) return ours;

        Log.w(TAG, "cannot use " + ours + "; keeping captures in the data folder");
        return new File(root(context), SHOTS);
    }

    /** Where the scraped metadata store lives, inside the data folder. */
    public static File libraryDirectory(Context context) {
        return new File(root(context), LIBRARY);
    }

    /**
     * Where this app keeps artwork it fetched itself - see {@link #MEDIA}.
     *
     * In {@link #dataFolders} with the rest, deliberately: that is what makes
     * a data-folder change carry the artwork along and what puts a {@code
     * .nomedia} in it, without which a scraped collection turns up in the
     * phone's photo gallery.
     */
    public static File mediaDirectory(Context context) {
        return new File(root(context), MEDIA);
    }

    /**
     * Every folder that belongs to the data folder, in one place.
     *
     * There were three hand-kept copies of this list - createFolders,
     * hideFromGallery and SettingsActivity's moveData - and they disagreed.
     * moveData was missing cards, which orphans a DivMMC image and the game
     * saves on it, and all three were missing library, which is where the
     * scraped metadata store goes.
     *
     * That last one is worth the sentence it costs. Changing the data folder
     * left gamelist.xml behind, and Metadata reads a missing file as an *empty*
     * store rather than an error - so the app answered "no games known" and
     * "never linked" while artwork, which comes from ES-DE's media folder and
     * not from the store, went on working. The link looked like it had
     * succeeded and quietly had nothing behind it.
     *
     * Captures are deliberately not here: they go to Pictures/Zedex, not into
     * the data folder. See {@link #capturesDirectory}.
     */
    public static File[] dataFolders(Context context) {
        return new File[] {
            statesDirectory(context), romsDirectory(context),
            tapesDirectory(context), disksDirectory(context),
            cardsDirectory(context), libraryDirectory(context),
            mediaDirectory(context),
        };
    }

    /** The names of those folders, for moving a data folder somewhere else. */
    public static String[] dataFolderNames() {
        return new String[] { STATES, ROMS, TAPES, DISKS, CARDS, LIBRARY, MEDIA };
    }

    /** The folders exist from the first run, empty if need be. */
    public static void createFolders(Context context) {
        for (File folder : dataFolders(context)) folder.mkdirs();

        // Not the captures. Those are in the gallery's own collections now, and
        // an empty Zedex folder in somebody's Pictures before they have taken a
        // screenshot is litter. Capture makes the folder when it needs it.

        hideFromGallery(context);
    }

    /**
     * Keeps what is ours out of the phone's photos.
     *
     * A save state carries a thumbnail of the screen it was taken from, and the
     * data folder is somewhere shared if it is worth anything - so a hundred
     * saves are a hundred pictures of a Spectrum in among the family
     * photographs. The machine's own files are no better as gallery entries.
     *
     * {@code .nomedia} is how a folder says so: the media scanner skips it and
     * everything inside it. Written on every start rather than only when the
     * folders are made, so a folder that predates this, or one the user has
     * just pointed the app at, is covered too.
     *
     * Every folder in the data folder is on this list, because the two that are
     * meant for the gallery are not in it: screenshots and recordings live in
     * {@code Pictures/Zedex} and {@code Movies/Zedex}. See
     * {@link #capturesDirectory}.
     */
    private static void hideFromGallery(Context context) {
        for (File folder : dataFolders(context)) {
            File marker = new File(folder, NOMEDIA);
            if (!folder.isDirectory() || marker.exists()) continue;

            try {
                marker.createNewFile();
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "cannot hide " + folder + " from the gallery", e);
            }
        }
    }

    /**
     * Moves a folder's contents, and says whether all of it arrived.
     *
     * This said "small files, and only when the data folder changes, so this
     * is done in place rather than in the background", and both halves of
     * that were wrong. The captures move through here too - MP4s and GIFs,
     * not small - and while {@code renameTo} is instant, it only works
     * <em>within</em> a volume; the whole point of changing the data folder is
     * usually to put it on an SD card, and across a volume boundary every one
     * of those files goes through {@link #copy} a 64 KB block at a time. It is
     * called from a worker now - see {@code SettingsActivity.moveData}.
     *
     * Not atomic, and cannot be made so across volumes: a file that will not
     * move is logged and the rest carry on, which leaves the folder split. The
     * boolean is so the caller can say that happened rather than reporting
     * success either way, which is what it used to do.
     */
    public static boolean move(Context context, File from, File to) {
        File[] files = from.listFiles();

        // Null for a folder that is not there, which is the ordinary case for
        // most of the ones a data-folder change walks: nothing failed, there
        // was simply nothing in it.
        if (files == null || files.length == 0) return true;

        if (!to.isDirectory() && !to.mkdirs()) {
            Log.w(TAG, "cannot create " + to);
            return false;
        }

        boolean whole = true;

        for (File file : files) {
            File target = new File(to, file.getName());
            if (!file.renameTo(target) && !copy(file, target)) {
                Log.w(TAG, "cannot move " + file + " to " + target);
                whole = false;
                continue;
            }
            file.delete();
        }

        return whole;
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

    /**
     * Keeps a picked folder past the activity that picked it, where the
     * provider allows it.
     *
     * {@code takePersistableUriPermission} throws {@code SecurityException} for
     * a grant that was not marked persistable, and that is not a hypothetical:
     * {@code ACTION_OPEN_DOCUMENT_TREE} against Android's own
     * {@code externalstorage} provider always is, but a third-party
     * {@code DocumentsProvider} — a cloud client, a USB-OTG or MTP browser —
     * need not be. Called bare, the app died the instant the picker returned,
     * with the user having done nothing stranger than keeping their games on a
     * cloud drive.
     *
     * Survivable rather than fatal, because the one-shot grant works for as
     * long as this launch: the folder they just chose does what they expect
     * now, and is asked for again next time. {@code Recents.remember} has taken
     * the same view of the same call since it was written; this is that,
     * everywhere else it is done.
     */
    public static void keepAccessTo(Context context, Uri tree, int flags) {
        try {
            context.getContentResolver().takePersistableUriPermission(tree, flags);
        } catch (SecurityException e) {
            Log.i(TAG, "cannot keep the grant for " + tree
                       + "; it will last only this launch", e);
        }
    }

    /** The folder the file picker should open in, or null for wherever it likes. */
    /**
     * What was built last, and what it was built from.
     *
     * One object rather than two fields, so a reader gets a matched pair or
     * nothing: two would let a thread see the new string beside the old Uri.
     * Keyed on the stored string, so choosing a different folder invalidates
     * it without anybody having to remember to.
     */
    private static final class ContentFolder {
        final String stored;
        final Uri folder;

        ContentFolder(String stored, Uri folder) {
            this.stored = stored;
            this.folder = folder;
        }
    }

    private static final java.util.concurrent.atomic.AtomicReference<ContentFolder>
            contentFolderCache = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Cached because it is asked for per row, per bind, and the answer cannot
     * change while the string it is derived from does not. Parsing a Uri and
     * rebuilding a document Uri from its tree id is eight to twelve
     * allocations and several string scans - small until a held pad direction
     * asks for it a hundred and fifty times a second.
     */
    public static Uri contentFolder(Context context) {
        SharedPreferences preferences = prefs(context);

        String stored = preferences.getString(KEY_CONTENT_TREE, null);
        if (stored == null) return null;

        ContentFolder cached = contentFolderCache.get();
        if (cached != null && stored.equals(cached.stored)) return cached.folder;

        try {
            Uri tree = Uri.parse(stored);
            Uri folder = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));

            contentFolderCache.set(new ContentFolder(stored, folder));
            return folder;
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

    /** This app's settings. Eight places here wanted them; the two-line
     *  incantation was written out at each one. */
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Prefs.PREFS,
                                            Context.MODE_PRIVATE);
    }

    /**
     * The last component of a path, which is the file's own name.
     *
     * Here rather than in any of the five places that used to spell out
     * {@code substring(lastIndexOf('/') + 1)} - one of them named, four of
     * them not. Works on a document path, a zip entry and a plain filename
     * alike: no separator means the whole string is already the name.
     */
    public static String filename(String path) {
        return path == null ? null : path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * A filename with its extension taken off, which is what media is named
     * after: "Tujad.z80" is a game called Tujad.
     *
     * Note that this sanitises as well, so it is not a drop-in for the bare
     * extension strips elsewhere in the tree - those keep whatever characters
     * they were given, deliberately.
     */
    public static String withoutExtension(String name) {
        int dot = name.lastIndexOf('.');

        return sanitise(dot > 0 ? name.substring(0, dot) : name);
    }
}
