package dev.ldlab.zedex.menu;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.media.Recorder;
import dev.ldlab.zedex.storage.States;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.view.Rows;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;

/**
 * A picture of the machine, or a film of it.
 *
 * Both formats are offered rather than one being settled in the settings,
 * because they are for different things: a GIF drops straight into a forum post
 * and keeps the palette exactly, an MP4 is smaller. {@link Recorder} does the
 * work; this decides what the file is called and says what happened.
 *
 * The reports come back through a callback rather than after the call, because
 * an encoder is still writing when {@code start} and {@code stop} return.
 */
public final class Capture {

    private final Activity activity;
    private final SharedPreferences preferences;

    public Capture(Activity activity, SharedPreferences preferences) {
        this.activity = activity;
        this.preferences = preferences;
    }

    /**
     * Built when the page is opened, so it offers the one thing that makes
     * sense: there is nothing to stop until something is running.
     */
    public void fill(Rows rows) {
        rows.item(R.drawable.ic_camera, text(R.string.capture_screenshot),
                  this::screenshot);

        if (Recorder.isRecording()) {
            rows.item(R.drawable.ic_stop, text(R.string.capture_stop),
                      Recorder::stop);
        } else {
            rows.item(R.drawable.ic_record, text(R.string.capture_gif),
                      () -> record(Recorder.Format.GIF));
            rows.item(R.drawable.ic_film, text(R.string.capture_mp4),
                      () -> record(Recorder.Format.MP4));
        }

        rows.rule();
        rows.item(R.drawable.ic_folder, text(R.string.capture_open_folder),
                  this::openFolder);
    }

    public void screenshot() {
        File target = fileIn(Storage.screenshotsDirectory(activity), "png");
        if (target == null) return;

        Recorder.screenshotTo(target, this::report);
    }

    /** The GIF, for a controller hotkey that toggles rather than chooses. */
    public void toggleRecording() {
        if (Recorder.isRecording()) Recorder.stop();
        else record(Recorder.Format.GIF);
    }

    private void record(Recorder.Format format) {
        File target = fileIn(Storage.recordingsDirectory(activity),
                             format.extension);
        if (target == null) return;

        if (!Recorder.start(target, format, this::report)) {
            Toast.makeText(activity, R.string.capture_busy,
                           Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(activity, text(R.string.capture_recording, target.getName()),
                       Toast.LENGTH_SHORT).show();
    }

    /** Reported when the file is really written, not when it was asked for. */
    private void report(File file, String error) {
        String said = error == null
                ? text(R.string.capture_saved, file.getName())
                : text(R.string.capture_failed, file.getName(), error);

        activity.runOnUiThread(() ->
                Toast.makeText(activity, said, Toast.LENGTH_LONG).show());
    }

    /** Named after whatever is loaded, numbered so nothing is overwritten. */
    private File fileIn(File directory, String extension) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Toast.makeText(activity, R.string.state_failed,
                           Toast.LENGTH_LONG).show();
            return null;
        }

        String base = preferences.getString(States.KEY_MEDIA_NAME, null);
        if (base == null || base.isEmpty()) base = "Spectrum";

        File target = new File(directory, base + "." + extension);
        for (int n = 2; target.exists() && n < 10000; n++) {
            target = new File(directory, base + " " + n + "." + extension);
        }

        return target;
    }

    /**
     * Hands the folder to whatever browses files on this device.
     *
     * Two ways to ask and neither is guaranteed: viewing the folder as a
     * document is what the Files app understands, and the document picker
     * opened at that folder is the fallback. If the data folder is the app's
     * own, no intent can reach it and the path is all there is to offer.
     */
    private void openFolder() {
        File folder = Storage.recordingsDirectory(activity);
        folder.mkdirs();

        Uri uri = Storage.documentUriFor(folder);

        if (uri != null) {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // The emulator is singleInstance, so without a task of its own the
            // file manager is handed the intent in the background and never
            // comes forward.
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            if (open(view)) return;

            Intent browse = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            browse.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri);
            browse.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (open(browse)) return;
        }

        Toast.makeText(activity, text(R.string.capture_no_browser,
                                      folder.getAbsolutePath()),
                       Toast.LENGTH_LONG).show();
    }

    private boolean open(Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            return false;
        }
    }

    private String text(int message, Object... arguments) {
        return activity.getString(message, arguments);
    }
}
