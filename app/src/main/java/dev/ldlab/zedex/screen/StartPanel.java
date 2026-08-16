package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.work.Work;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.storage.Storage;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The screen the app shows when there are no ROMs to run a machine with.
 *
 * The app ships them, but a data folder can be pointed somewhere they are not,
 * and a folder full of the wrong ones is a machine that will not start:
 * without a ROM Fuse gives up hard, drawing nothing, so the screen would
 * simply stay black with no way out of it. This is the way out — what is
 * missing, and three routes to fixing it.
 *
 * ROMs arrive by one of three routes and all three are kept, because Android
 * refuses to grant a document tree on {@code Download} — where a downloaded set
 * usually lands — while the file picker opens it without complaint.
 *
 * The first run's two folders used to be shown here too, before there was
 * anywhere else for them; that half now lives in {@code WelcomeActivity}'s
 * own {@code FoldersPage}.
 */
public final class StartPanel {

    /** What this needs of the activity, and nothing more. */
    public interface Host {
        /** ROMs may now exist: try starting the emulator again. */
        void onRomsChanged();

        /** Whether the emulation thread has been started already. */
        boolean hasStarted();

        /**
         * Whether the panel is covering the screen. The quick bar has to stay
         * up while it is: the panel swallows the tap that would reveal it,
         * which would leave settings unreachable exactly when a wrong data
         * folder is the likely cause.
         */
        void setTakeover(boolean covering);
    }

    private static final String TAG = "Zedex";

    /** What a ROM is called, whatever else is in the folder beside it. */
    private static final String ROM_SUFFIX = ".rom";

    /** A downloaded set arrives as one of these, so it is unpacked in place. */
    private static final String ZIP_SUFFIX = ".zip";

    /** A downloaded set unpacks into a folder or two, not a deep tree. */
    private static final int ROM_SEARCH_DEPTH = 3;

    /** Enough for every machine Fuse knows, with room to spare. */
    private static final int ROM_SEARCH_LIMIT = 256;

    /** Ours to answer; the activity forwards anything with these codes. */
    public static final int REQUEST_IMPORT_ROMS = 3;
    public static final int REQUEST_IMPORT_ROMS_TREE = 5;

    private final Activity activity;
    private final Host host;

    private final View panel;
    private TextView title;
    private TextView message;
    /** The three ways of getting ROMs, hidden while one of them is running. */
    private final List<View> choices = new ArrayList<>();

    /**
     * The one button that gets the machine going: "Run anyway" when ROMs are
     * missing, "Restart" when Fuse has already given up on the ones there are.
     * Hidden the rest of the time.
     */
    private View run;

    /**
     * The one that asks Android rather than the user for something.
     *
     * Shown only where it is the answer: a data folder outside the app's own
     * storage, and no All files access to read it with. That case used to show
     * the same three rows as an empty folder, all of them offering to find ROMs
     * that were already sitting in the folder unread.
     */
    private View grant;

    public StartPanel(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.panel = buildPanel();
    }

    /** The panel itself, for the layout to place. */
    public View view() {
        return panel;
    }

    /** A toast, for the things that finish with nothing to show. */
    private void toast(int text, Object... arguments) {
        Toast.makeText(activity, activity.getString(text, arguments),
                       Toast.LENGTH_LONG).show();
    }

    /**
     * Results for the two pickers. Answers whether it was one of ours, so the
     * activity can go on to its own.
     */
    public boolean onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_IMPORT_ROMS && request != REQUEST_IMPORT_ROMS_TREE) {
            return false;
        }

        if (result != Activity.RESULT_OK || data == null) return true;

        if (request == REQUEST_IMPORT_ROMS_TREE) {
            Uri tree = data.getData();
            if (tree != null) {
                toast(R.string.roms_searching);
                Work.alone("roms-copy", () -> copyRomsFromTree(tree));
            }
            return true;
        }

        List<Uri> sources = new ArrayList<>();

        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                sources.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            sources.add(data.getData());
        }

        if (!sources.isEmpty()) Work.alone("roms-import", () -> copyRoms(sources));
        return true;
    }

    /** Where a set of ROMs under the names Fuse expects can be found. */
    private static final String ROMS_URL =
            "https://archive.org/details/zx-roms-fuse-roms";

    /**
     * What the screen shows when no machine is running, in place of the black
     * that a missing ROM used to leave behind with no way out of it.
     */
    private View buildPanel() {
        int pad = Cards.unit(activity, 6);

        LinearLayout content = Cards.column(activity, 150);
        content.setPadding(pad, Cards.unit(activity, 10), pad, pad);

        title = new TextView(activity);
        title.setTextSize(26);
        title.setTextColor(Palette.TEXT);
        title.setLetterSpacing(-0.01f);
        content.addView(title);

        message = new TextView(activity);
        message.setTextSize(15);
        message.setTextColor(Palette.MUTED);
        message.setLineSpacing(Cards.unit(activity, 1), 1f);
        message.setPadding(0, Cards.unit(activity, 2), 0, Cards.unit(activity, 5));
        content.addView(message);

        // Above the three ways of finding ROMs, because where this one applies
        // the ROMs have already been found: they are in the folder, and Android
        // is not letting the app look. Downloading a second set into a folder
        // it still cannot read would not help.
        grant = Cards.choice(activity, R.string.roms_grant, R.string.roms_grant_hint,
                            v -> askForAllFiles(R.string.roms_grant_ask), false);
        grant.setVisibility(View.GONE);
        content.addView(grant);

        // Downloading first: it is the one that needs nothing of the user.
        choices.add(Cards.choice(activity, R.string.roms_where,
                R.string.roms_where_hint, v -> offerRomsDownload(), false));
        choices.add(Cards.choice(activity, R.string.roms_folder,
                R.string.roms_folder_hint, v -> importRomsFolder(), false));
        choices.add(Cards.choice(activity, R.string.roms_files,
                R.string.roms_files_hint, v -> importRomFiles(), false));

        for (View choice : choices) content.addView(choice);

        run = Cards.choice(activity, R.string.roms_run, R.string.roms_run_hint,
                           v -> runNow(), false);
        run.setVisibility(View.GONE);
        content.addView(run);

        // Landscape leaves little height, and the message is not short.
        //
        // Centred, because the column stops at a readable width and the rest of
        // a tablet is margin: left where it fell, the whole screen read as one
        // corner of a page with nothing on the other two thirds of it.
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Cards.BACK);

        FrameLayout.LayoutParams middle = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        middle.gravity = android.view.Gravity.CENTER_HORIZONTAL;

        scroll.addView(content, middle);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        scroll.setVisibility(View.GONE);

        return scroll;
    }

    /**
     * @param startFailed whether Fuse tried and gave up, which needs a new
     *                    process rather than merely more ROMs.
     */
    public void show(boolean startFailed) {
        String path = Storage.romsDirectory(activity).getAbsolutePath();

        // Whether the folder is empty or merely out of reach. They look the
        // same from here - no ROMs either way - and the answers are opposite:
        // one is "find some ROMs", the other "the ROMs are there, let the app
        // see them".
        //
        // Asked of the folder's whereabouts rather than of the filesystem: with
        // the permission missing, the folder can be stated and written to and
        // still lists as empty, so every test that touches it says the ROMs are
        // not there rather than that they cannot be seen.
        // Only a build that can be granted the permission may offer it. A Play
        // install restored from a backup of one that could carries a folder it
        // will never reach, and the answer for it is a different folder, not a
        // grant button that opens an empty settings page.
        boolean blocked = !startFailed && Storage.needsAllFiles(activity)
                && Storage.canAskForAnyFolder(activity);

        title.setText(blocked ? R.string.roms_blocked
                    : startFailed ? R.string.roms_start_failed
                                  : R.string.roms_needed);
        message.setText(activity.getString(
                blocked ? R.string.roms_blocked_message
                        : startFailed ? R.string.roms_start_failed_message
                                      : R.string.roms_needed_message, path));

        grant.setVisibility(blocked ? View.VISIBLE : View.GONE);
        for (View choice : choices) choice.setVisibility(View.VISIBLE);
        run.setVisibility(startFailed ? View.VISIBLE : View.GONE);
        panel.setVisibility(View.VISIBLE);
        host.setTakeover(true);
    }

    /**
     * @param why what the permission is for, which is not the same each time:
     *            one caller is about to choose a folder, the other has one
     *            already and cannot read it.
     */
    private void askForAllFiles(int why) {
        // Nothing to grant in a build that does not declare it, and the
        // settings page would open empty. Every caller checks first; this is
        // the backstop.
        if (!Storage.canAskForAnyFolder(activity)) {
            toast(R.string.settings_folder_unusable);
            return;
        }

        new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(why)
                .setPositiveButton(R.string.settings_grant, (dialog, which) ->
                        activity.startActivity(new Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + activity.getPackageName()))))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public void hide() {
        panel.setVisibility(View.GONE);
        host.setTakeover(false);
    }

    /**
     * Something is happening and there is nothing to press.
     *
     * A download of a third of a megabyte is quick on a desk and not always
     * quick on a train, and until this existed the panel said exactly what it
     * had said before, with no sign that a tap had done anything at all. Which
     * is what a report of "I pressed download and nothing happened" looks like
     * from the inside.
     */
    private void busy(int heading, String detail) {
        activity.runOnUiThread(() -> {
            title.setText(heading);
            message.setText(detail);

            for (View choice : choices) choice.setVisibility(View.GONE);
            grant.setVisibility(View.GONE);
            run.setVisibility(View.GONE);

            panel.setVisibility(View.VISIBLE);
            host.setTakeover(true);
        });
    }

    /**
     * ROMs arrived, but not all of them. Which machines are short is the useful
     * thing to say, and running with what there is has to be one tap away: a set
     * missing the +3's four files still runs every other machine, and being told
     * to go and find them first would be nonsense.
     */
    private void showMissing(List<String> missing) {
        StringBuilder names = new StringBuilder();
        int shown = Math.min(missing.size(), 10);

        for (int i = 0; i < shown; i++) {
            names.append(i > 0 ? ", " : "").append(missing.get(i));
        }
        if (missing.size() > shown) {
            names.append(activity.getString(R.string.roms_missing_more,
                                            missing.size() - shown));
        }

        title.setText(R.string.roms_missing);
        message.setText(activity.getString(R.string.roms_missing_message,
                                           missing.size(), names.toString()));

        for (View choice : choices) choice.setVisibility(View.VISIBLE);
        grant.setVisibility(View.GONE);
        run.setVisibility(View.VISIBLE);

        panel.setVisibility(View.VISIBLE);
        host.setTakeover(true);
    }

    /**
     * Gets a machine going with whatever ROMs are now there.
     *
     * Two cases, and only one of them is a restart. If Fuse was never started -
     * the usual one, since with no ROMs it is not started at all - it can simply
     * be started now. If it *was* started and gave up, it cannot be started
     * again in this process: its init is not re-entrant, which is why the panel
     * used to offer a Restart button and wait to be pressed. It no longer waits.
     */
    private void runNow() {
        if (!host.hasStarted()) {
            hide();
            host.onRomsChanged();
            return;
        }

        Toast.makeText(activity, R.string.roms_restarting, Toast.LENGTH_SHORT).show();
        restartForRoms();
    }

    private void openRomsPage() {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ROMS_URL)));
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "no browser to open " + ROMS_URL, e);
        }
    }

    /** The one file that archive.org item holds: 47 ROMs, about 350 kB. */
    private static final String ROMS_ZIP_URL =
            "https://archive.org/download/zx-roms-fuse-roms/zx%20roms.zip";

    /**
     * Offers to fetch the set rather than making the user find a browser, an
     * extractor and a file manager first.
     *
     * Asks before doing it, because the ROMs are somebody else's copyright and
     * whether they may be downloaded is not the same everywhere.
     */
    private void offerRomsDownload() {
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.roms_download_title)
                .setMessage(R.string.roms_download_warning)
                .setPositiveButton(R.string.roms_download, (dialog, which) -> {
                    toast(R.string.roms_downloading);
                    Work.alone("roms-download", this::downloadRoms);
                })
                .setNeutralButton(R.string.roms_open_page, (dialog, which) -> openRomsPage())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Fetches the set and unpacks it. To a cache file first rather than
     * straight through the unpacker, so a connection that dies half way
     * leaves the ROMs folder as it was instead of half filled.
     */
    private void downloadRoms() {
        File directory = Storage.romsDirectory(activity);
        directory.mkdirs();

        busy(R.string.roms_downloading,
             activity.getString(R.string.roms_downloading_message, ROMS_ZIP_URL));

        File zip = new File(activity.getCacheDir(), "roms-download.zip");
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(ROMS_ZIP_URL).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);

            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(zip)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "cannot download " + ROMS_ZIP_URL, e);
            zip.delete();
            failed(e.getMessage());
            return;
        } finally {
            if (connection != null) connection.disconnect();
        }

        // The count is kept outside the try, so a failure part way through
        // still knows how far it got. It used to be the return value, which
        // an exception never delivers: twenty ROMs could be on disk and the
        // user was told the archive held none - blaming archive.org for a
        // full disk, and retrying the download instead of freeing space. The
        // real cause was in logcat and nowhere else.
        int[] copied = { 0 };
        IOException failure = null;

        try (InputStream in = new FileInputStream(zip)) {
            unpackRoms(in, directory, copied);
        } catch (IOException e) {
            failure = e;
            Log.e(TAG, "cannot unpack the downloaded set", e);
        }
        zip.delete();

        if (failure != null) {
            failed(activity.getString(R.string.roms_unpack_failed, copied[0],
                                      reason(failure)));
            return;
        }

        if (copied[0] == 0) {
            Log.w(TAG, "nothing in the downloaded zip looked like a ROM");
            failed(activity.getString(R.string.roms_download_empty));
            return;
        }

        reportRoms(copied[0]);
    }

    /**
     * The download did not work. Says so where the user is looking, rather than
     * in a toast they may have already turned away from, and puts the three
     * choices back so that another one can be tried.
     */
    private void failed(String reason) {
        activity.runOnUiThread(() -> {
            title.setText(R.string.roms_download_did_not);
            message.setText(activity.getString(R.string.roms_download_failed_message,
                                               activity.getString(R.string.roms_download_failed),
                                               reason == null ? "" : reason));

            for (View choice : choices) choice.setVisibility(View.VISIBLE);
            grant.setVisibility(View.GONE);
            run.setVisibility(Storage.haveRoms(activity) ? View.VISIBLE : View.GONE);

            panel.setVisibility(View.VISIBLE);
            host.setTakeover(true);
        });
    }

    /** A zip of ROMs, as archive.org hands them out, opened from a document. */
    private int unpackRoms(Uri source, File directory) {
        int[] copied = { 0 };

        try (InputStream in = activity.getContentResolver().openInputStream(source)) {
            if (in == null) return 0;
            unpackRoms(in, directory, copied);
        } catch (IOException | SecurityException e) {
            // Whatever landed before this stays: the caller reports the count,
            // and a half-unpacked set is still worth more than none.
            Log.e(TAG, "cannot unpack " + source, e);
        }

        return copied[0];
    }

    /** What to put in front of a person when an exception is all there is. */
    private static String reason(Throwable e) {
        String message = e.getMessage();
        return message != null && !message.isEmpty() ? message : e.toString();
    }

    /**
     * Takes the ROMs out of a zip and ignores everything else in it.
     *
     * {@code copied[0]} counts as it goes rather than being returned, so a
     * caller that catches out of here still knows how many arrived.
     */
    private int unpackRoms(InputStream source, File directory, int[] copied)
            throws IOException {

        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                // The last component only. Entries carry directories, and a
                // crafted one can carry ../ as easily.
                String name = new File(entry.getName()).getName();
                if (!name.toLowerCase(Locale.ROOT).endsWith(ROM_SUFFIX)) continue;

                try (OutputStream out = new FileOutputStream(new File(directory, name))) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                copied[0]++;
            }
        }

        return copied[0];
    }

    /**
     * The emulation thread cannot be started twice in one process, so trying
     * again after Fuse has given up means a new one.
     */
    private void restartForRoms() {
        Intent intent = new Intent(activity, EmulatorActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
        Runtime.getRuntime().exit(0);
    }

    /** A whole folder of ROMs, which is how a downloaded set arrives. */
    private void importRomsFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

        Uri start = Storage.contentFolder(activity);
        if (start != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);

        try {
            activity.startActivityForResult(intent, REQUEST_IMPORT_ROMS_TREE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Individual files, kept alongside the folder picker because Android will
     * not grant a tree on Download - where a downloaded set most often lands -
     * while the file picker can open it perfectly well.
     */
    private void importRomFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        Uri start = Storage.contentFolder(activity);
        if (start != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);

        try {
            activity.startActivityForResult(intent, REQUEST_IMPORT_ROMS);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Everything that looks like a ROM under a granted tree, then copied. */
    private void copyRomsFromTree(Uri tree) {
        List<Uri> roms = new ArrayList<>();

        try {
            collectRoms(tree, DocumentsContract.getTreeDocumentId(tree), roms, 0);
        } catch (Exception e) {
            Log.w(TAG, "cannot read the folder " + tree, e);
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    R.string.roms_folder_unreadable, Toast.LENGTH_LONG).show());
            return;
        }

        if (roms.isEmpty()) {
            // Saying nothing here reads as the picker having done nothing.
            activity.runOnUiThread(() -> Toast.makeText(activity, R.string.roms_none_found,
                                               Toast.LENGTH_LONG).show());
            return;
        }

        copyRoms(roms);
    }

    /**
     * Depth-limited walk of a document tree. Subfolders are followed because
     * sets unpack into one, but not far: the tree is whatever was granted, and
     * could be the whole of shared storage.
     */
    private void collectRoms(Uri tree, String parentId, List<Uri> found, int depth) {
        if (depth > ROM_SEARCH_DEPTH || found.size() >= ROM_SEARCH_LIMIT) return;

        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);

        try (Cursor cursor = activity.getContentResolver().query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        }, null, null, null)) {
            if (cursor == null) return;

            while (cursor.moveToNext() && found.size() < ROM_SEARCH_LIMIT) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collectRoms(tree, id, found, depth + 1);
                    continue;
                }

                if (name == null) continue;

                // A zip counts: a set downloaded and left where it landed is
                // still a folder of ROMs as far as the user is concerned. Only
                // its .rom entries are taken, so an unrelated zip costs
                // nothing but the time to look inside it.
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(ROM_SUFFIX) || lower.endsWith(ZIP_SUFFIX)) {
                    found.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                }
            }
        } catch (Exception e) {
            // One unreadable subfolder should not lose the rest.
            Log.w(TAG, "cannot list " + parentId, e);
        }
    }

    /** Copies chosen files into the roms folder, then tries to start again. */
    private void copyRoms(List<Uri> sources) {
        File directory = Storage.romsDirectory(activity);
        directory.mkdirs();

        int copied = 0;
        for (Uri source : sources) {
            String name = Storage.displayName(activity, source);

            // A set downloaded from archive.org is a zip; unpack it rather
            // than making the user find an extractor first.
            if (name.toLowerCase(Locale.ROOT).endsWith(ZIP_SUFFIX)) {
                copied += unpackRoms(source, directory);
                continue;
            }

            File target = new File(directory, name);

            try (InputStream in = activity.getContentResolver().openInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null) continue;

                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                copied++;
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "cannot import " + source, e);
            }
        }

        reportRoms(copied);
    }

    /**
     * Says what arrived and gets a machine going, or says what is still missing
     * and offers to go anyway.
     *
     */
    private void reportRoms(int copied) {
        List<String> missing = Storage.missingRoms(activity);

        activity.runOnUiThread(() -> {
            Toast.makeText(activity, activity.getResources().getQuantityString(
                                   R.plurals.roms_imported, copied, copied),
                    Toast.LENGTH_SHORT).show();

            if (missing.isEmpty()) runNow();
            else showMissing(missing);
        });
    }
}
