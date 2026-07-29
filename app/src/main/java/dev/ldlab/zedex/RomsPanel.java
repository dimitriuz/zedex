package dev.ldlab.zedex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
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
 * Getting ROMs onto the device, and the panel that asks for them.
 *
 * No ROMs ship with the app, and without one Fuse cannot even reach the 48K
 * machine it falls back to — it gives up hard, drawing nothing, so the screen
 * would simply stay black with no way out of it. This is the way out: a
 * takeover panel saying what is missing, and three routes to fixing it.
 *
 * Its own class because it is a feature and not a part of hosting an emulator.
 * It arrived inside {@link EmulatorActivity}'s surface lifecycle, where nearly
 * four hundred lines of panel, HTTP download, zip extraction and document-tree
 * walking sat among four lines of surface callbacks. The whole of what it needs
 * from the activity is {@link Host}.
 *
 * ROMs arrive by one of three routes, and all three are kept because Android
 * refuses to grant a document tree on {@code Download}, where a downloaded set
 * usually lands, while the file picker opens it without complaint.
 */
final class RomsPanel {

    /** What this needs of the activity, and nothing more. */
    interface Host {
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
    static final int REQUEST_IMPORT_ROMS = 3;
    static final int REQUEST_IMPORT_ROMS_TREE = 5;

    private final Activity activity;
    private final Host host;

    private final View panel;
    private TextView title;
    private TextView message;
    /** The Restart button and its caption, hidden unless Fuse gave up. */
    private View restart;

    RomsPanel(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.panel = buildPanel();
    }

    /** The panel itself, for the layout to place. */
    View view() {
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
    boolean onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_IMPORT_ROMS && request != REQUEST_IMPORT_ROMS_TREE) {
            return false;
        }

        if (result != Activity.RESULT_OK || data == null) return true;

        if (request == REQUEST_IMPORT_ROMS_TREE) {
            Uri tree = data.getData();
            if (tree != null) {
                toast(R.string.roms_searching);
                new Thread(() -> copyRomsFromTree(tree)).start();
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

        if (!sources.isEmpty()) new Thread(() -> copyRoms(sources)).start();
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
        int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        title = new TextView(activity);
        title.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large);
        title.setTextColor(Color.WHITE);
        content.addView(title);

        message = new TextView(activity);
        message.setTextColor(0xffbbbbbb);
        message.setPadding(0, pad / 2, 0, pad / 2);
        content.addView(message);

        // Downloading first: it is the one that needs nothing of the user.
        content.addView(panelChoice(R.string.roms_where,
                R.string.roms_where_hint, v -> offerRomsDownload()));
        content.addView(panelChoice(R.string.roms_folder,
                R.string.roms_folder_hint, v -> importRomsFolder()));
        content.addView(panelChoice(R.string.roms_files,
                R.string.roms_files_hint, v -> importRomFiles()));

        restart = panelChoice(R.string.roms_restart,
                R.string.roms_restart_hint, v -> restartForRoms());
        restart.setVisibility(View.GONE);
        content.addView(restart);

        // Landscape leaves little height, and the message is not short.
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(0xff000000);
        scroll.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        scroll.setVisibility(View.GONE);

        return scroll;
    }

    /**
     * A button with a line under it saying what it does, since "Choose
     * folder" and "Choose files" are not self-explaining on their own.
     */
    private View panelChoice(int label, int description, View.OnClickListener action) {
        int unit = Math.round(4 * activity.getResources().getDisplayMetrics().density);

        LinearLayout group = new LinearLayout(activity);
        group.setOrientation(LinearLayout.VERTICAL);

        Button button = new Button(activity);
        button.setText(label);
        button.setOnClickListener(action);
        group.addView(button);

        TextView caption = new TextView(activity);
        caption.setText(description);
        caption.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small);
        caption.setTextColor(0xff999999);
        caption.setPadding(unit * 2, unit, unit * 2, unit * 4);
        group.addView(caption);

        return group;
    }

    /**
     * @param startFailed whether Fuse tried and gave up, which needs a new
     *                    process rather than merely more ROMs.
     */
    void show(boolean startFailed) {
        String path = Storage.romsDirectory(activity).getAbsolutePath();

        title.setText(startFailed ? R.string.roms_start_failed
                                      : R.string.roms_needed);
        message.setText(startFailed
                ? activity.getString(R.string.roms_start_failed_message, path)
                : activity.getString(R.string.roms_needed_message, path));
        restart.setVisibility(startFailed ? View.VISIBLE : View.GONE);
        panel.setVisibility(View.VISIBLE);
        host.setTakeover(true);
    }

    void hide() {
        panel.setVisibility(View.GONE);
        host.setTakeover(false);
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
                    new Thread(this::downloadRoms).start();
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
            activity.runOnUiThread(() -> Toast.makeText(activity, R.string.roms_download_failed,
                                               Toast.LENGTH_LONG).show());
            return;
        } finally {
            if (connection != null) connection.disconnect();
        }

        int copied = 0;
        try (InputStream in = new FileInputStream(zip)) {
            copied = unpackRoms(in, directory);
        } catch (IOException e) {
            Log.e(TAG, "cannot unpack the downloaded set", e);
        }
        zip.delete();

        if (copied == 0) {
            Log.w(TAG, "nothing in the downloaded zip looked like a ROM");
            activity.runOnUiThread(() -> Toast.makeText(activity, R.string.roms_download_failed,
                                               Toast.LENGTH_LONG).show());
            return;
        }

        reportRoms(copied);
    }

    /** A zip of ROMs, as archive.org hands them out, opened from a document. */
    private int unpackRoms(Uri source, File directory) {
        try (InputStream in = activity.getContentResolver().openInputStream(source)) {
            if (in == null) return 0;
            return unpackRoms(in, directory);
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "cannot unpack " + source, e);
            return 0;
        }
    }

    /** Takes the ROMs out of a zip and ignores everything else in it. */
    private int unpackRoms(InputStream source, File directory) throws IOException {
        int copied = 0;

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
                copied++;
            }
        }

        return copied;
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

    /** Says what arrived, and gets a machine going if one can now run. */
    private void reportRoms(int copied) {
        activity.runOnUiThread(() -> {
            Toast.makeText(activity, activity.getString(R.string.roms_imported, copied),
                    Toast.LENGTH_SHORT).show();

            if (!host.hasStarted()) {
                host.onRomsChanged();
            } else if (FuseNative.currentMachine() < 0) {
                // Fuse already tried and gave up; more ROMs cannot reach it.
                show(true);
            }
        });
    }
}
