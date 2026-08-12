package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.screen.InstructionsActivity;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Opens a manual - resolved by {@link dev.ldlab.zedex.library.meta.Artwork#manual}
 * - in whatever app the phone has for a PDF.
 *
 * Used to be {@code Gallery}'s own, reached by a tap on the manual's page in
 * the pager; it is a button now, beside Play and the magnifier in the pane and
 * on {@code GameInfoActivity}, so this moved out to where both of those can
 * reach it without a gallery in between. {@code Gallery} no longer resolves or
 * shows a manual at all - see its own class comment.
 */
public final class Manuals {

    private static final String TAG = "Zedex";

    private Manuals() {
    }

    /** Which of the two kinds of manual this is - see {@code
     *  Artwork.MANUAL_EXTENSIONS}, which resolves either. */
    private static boolean isText(Uri manual) {
        String path = manual.getPath();
        return path != null && path.toLowerCase(java.util.Locale.US).endsWith(".txt");
    }

    /**
     * The app's own reader, on the display that asked for it.
     *
     * The same display dance as the PDF below and for the same reason - a
     * manual opened from the second screen belongs on the second screen - but
     * without any of the granting: this is one of the app's own screens
     * reading a file the app can already read, so there is nobody to grant
     * anything to.
     */
    private static void showText(Context context, Uri manual, Display display,
                                 Runnable onDisplay) {
        Intent intent = new Intent(context, InstructionsActivity.class)
                .putExtra(InstructionsActivity.EXTRA_FILE, manual.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (display != null) {
            try {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(display.getDisplayId());

                context.startActivity(intent, options.toBundle());
                if (onDisplay != null) onDisplay.run();
                return;
            } catch (RuntimeException e) {
                // The display refused it, or has gone. The main screen is
                // better than nothing at all, which is what the PDF path
                // below decided too.
                Log.w(TAG, "cannot open the instructions on display "
                           + display.getDisplayId(), e);
            }
        }

        context.startActivity(intent);
    }

    /** {@link #open(Context, Uri, Display)} with no display to ask for - the
     *  ordinary path, unchanged from before that existed. */
    public static void open(Context context, Uri manual) {
        open(context, manual, null);
    }

    /** {@link #open(Context, Uri, Display, Runnable)} with nothing waiting
     *  to know whether the display was actually used. */
    public static void open(Context context, Uri manual, Display display) {
        open(context, manual, display, null);
    }

    /**
     * {@code ACTION_VIEW}, {@code application/pdf}, with the explicit grant
     * every resolver needs - see {@link #grantToResolvers}. Needs the
     * {@code <queries>} entry in the manifest for
     * {@code ACTION_VIEW}/{@code application/pdf}, or Android 11 and later
     * hide every PDF viewer from an app that has not declared it, exactly the
     * trap {@code Feedback}'s own mail button hit first - see CLAUDE.md.
     *
     * {@code display}, when given, is asked for first through {@link
     * ActivityOptions#setLaunchDisplayId} - a manual opened from the second
     * screen's own game info should open there too, not behind it on the
     * main one. The same technique {@code Panels.openOwnScreen} already uses
     * for the app's own screens, not a second way of doing it: a task of its
     * own, since a task lives on one display, and a plain fallback to the
     * ordinary path when the display refuses it or the request fails for any
     * other reason - a third-party viewer may ignore the ask or be moved by
     * the system, and it must land on the main screen rather than fail.
     *
     * {@code onDisplay}, when given, is told exactly once {@code
     * startActivity} for that display has actually gone out with no
     * exception - never when there was no display to ask for, and never on
     * the fallback below, since neither of those puts anything on {@code
     * display} at all. {@code GameInfoView} passes its own {@code
     * onForeignScreen} here, set once by {@code SecondScreen} - the only
     * place that view is ever shown - because a {@link
     * android.app.Presentation} draws above every activity window on its
     * own display and would otherwise sit over whatever this puts there,
     * exactly as it would over one of the app's own screens; unlike one of
     * those, a foreign activity never reaches this app's own lifecycle
     * callbacks; see {@code Panels}'s class comment, the fourth corner.
     */
    public static void open(Context context, Uri manual, Display display, Runnable onDisplay) {
        if (manual == null) return;

        // A transcription rather than a PDF: shown here rather than handed
        // out. See InstructionsActivity for why - not every phone has
        // anything for text/plain, and the ones that do wrap it to the
        // window, which is the one thing these documents cannot survive.
        if (isText(manual)) {
            showText(context, manual, display, onDisplay);
            return;
        }

        Uri shareable = manual;

        if ("file".equals(manual.getScheme())) {
            // A file:// Uri handed to another app's ACTION_VIEW has been
            // refused outright since Android 7 - Updater.install hit the
            // identical wall over the APK it downloads, and fixed it the
            // same way: a content:// Uri from this app's own FileProvider,
            // scoped to ES-DE's folder specifically, in place of the plain
            // path Artwork.resolve hands back when this build reached that
            // folder directly rather than through a SAF tree.
            //
            // In the other case - a SAF grant, so this Uri is already a
            // content:// from ExternalStorageProvider - none of this branch
            // runs and the Uri is passed straight through below. That
            // provider declares its own grantUriPermissions and is the same
            // mechanism ES-DE's own %ROMPROVIDER% hand-off already relies
            // on elsewhere in this app, so it is expected to need no
            // explicit grant of its own; grantToResolvers is applied to it
            // anyway, since doing so costs nothing and removes the need to
            // trust that expectation instead of covering it.
            File file = new File(manual.getPath());
            String authority = context.getPackageName() + ".esde";

            try {
                shareable = FileProvider.getUriForFile(context, authority, file);
            } catch (IllegalArgumentException outsideTheRoots) {
                // Not under any root the provider declares, which is the
                // ordinary case for a manual this app scraped for itself: it
                // lives in the media folder, under the data folder, which is a
                // preference and has no path that can be written into XML -
                // and may be on internal storage, where no external-path could
                // reach it at all. Every one of those answered "could not open
                // that file" before this, on every screen.
                shareable = servedFromCache(context, authority, file);

                if (shareable == null) {
                    Toast.makeText(context, R.string.open_failed, Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (Exception e) {
                Toast.makeText(context, R.string.open_failed, Toast.LENGTH_LONG).show();
                return;
            }
        }

        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(shareable, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Belt and braces over the flag above: on a device this reached
        // Google's own PDF viewer, which opened and then failed reading our
        // FileProvider with a SecurityException naming a uid the grant never
        // reached - the flag's own implicit grant did not arrive, for a
        // reason narrower than this app can see into. Granting the same Uri
        // explicitly, to every activity that could handle this intent,
        // before any of them starts, is the reliable form of the same
        // thing. The flag stays on the intent as well; that is what a viewer
        // reached later through a chooser, rather than this call's own
        // list, ends up using.
        grantToResolvers(context, intent, shareable);

        if (display != null) {
            // A copy, not intent itself: a task of its own is only wanted
            // for this attempt, and the ordinary path below must still be
            // the plain intent if this one fails for any reason.
            Intent targeted = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(display.getDisplayId());

            try {
                context.startActivity(targeted, options.toBundle());
                if (onDisplay != null) onDisplay.run();
                return;
            } catch (RuntimeException e) {
                // The display refused it, or anything else went wrong asking
                // for it specifically - the ordinary path below is what a
                // manual opened with no display in mind already does.
                Log.w(TAG, "cannot open the manual on the second screen", e);
            }
        }

        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // No PDF viewer at all, rather than doing nothing silently - the
            // same choice Feedback makes when there is no mail app.
            Toast.makeText(context, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The manual, copied where the provider can serve it from, or null.
     *
     * One folder, one file at a time: the copy before this one is thrown away
     * on the way in, so the cache holds at most the manual somebody is
     * actually reading rather than one per manual ever opened. There is no way
     * out to hook for a tidier scheme - the viewer is another app's activity
     * and never says when it has finished - which is the same argument {@link
     * #releaseLastGrant} already makes about the grants.
     *
     * On the calling thread, which is the UI thread. A copy is what makes that
     * defensible rather than a stream: these are a few megabytes at the
     * outside, ZXDB's PDFs being scans of cassette inlays and typeset
     * instructions rather than anything long. If manuals ever get big enough
     * to be felt here, the answer is to move the copy off the thread, not to
     * hand out a path the provider cannot serve.
     */
    private static Uri servedFromCache(Context context, String authority, File manual) {
        File folder = new File(context.getCacheDir(), "manuals");

        emptyOut(folder);

        if (!folder.isDirectory() && !folder.mkdirs()) {
            Log.w(TAG, "cannot make " + folder);
            return null;
        }

        File copy = new File(folder, manual.getName());

        try (java.io.InputStream in = new java.io.FileInputStream(manual);
             java.io.OutputStream out = new java.io.FileOutputStream(copy)) {

            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
        } catch (java.io.IOException e) {
            Log.w(TAG, "cannot copy " + manual.getName() + " where it can be served from", e);
            copy.delete();
            return null;
        }

        try {
            return FileProvider.getUriForFile(context, authority, copy);
        } catch (Exception e) {
            Log.w(TAG, "the cache copy is not under a declared root either", e);
            return null;
        }
    }

    private static void emptyOut(File folder) {
        File[] previous = folder.listFiles();
        if (previous == null) return;

        for (File one : previous) {
            if (!one.delete()) Log.w(TAG, "cannot remove " + one);
        }
    }

    /**
     * {@code queryIntentActivities} is itself gated by the manifest's own
     * {@code <queries>} block - without the {@code application/pdf} entry
     * this would silently see no activities at all, the same as {@code
     * resolveActivity} would - so that entry and this loop are load-bearing
     * together now, not the {@code <queries>} entry alone. Safe to call with
     * nothing found: an empty list grants nothing and {@link #open}'s own
     * {@code ActivityNotFoundException} catch is still what answers "no
     * viewer at all".
     */
    private static void grantToResolvers(Context context, Intent intent, Uri uri) {
        releaseLastGrant(context);

        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(intent, 0)) {
            context.grantUriPermission(info.activityInfo.packageName, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        granted = uri;
    }

    /** The manual whose grant is still out, or null. */
    private static Uri granted;

    /**
     * Takes back the grant on the manual opened before this one.
     *
     * The grant goes to every activity that answered the query, not only the
     * one the user picked, and nothing took it back - so an app that registers
     * for application/pdf accumulated a standing read on every manual ever
     * opened, for the life of the process.
     *
     * Released on the way in to the next one rather than on the way out of
     * this one, because there is no way out to hook: the viewer is another
     * app's activity and it never says when it is finished. One manual's worth
     * of grant is what a person actually has open, and the one being opened
     * now is the one that must keep working.
     */
    private static void releaseLastGrant(Context context) {
        if (granted == null) return;

        context.revokeUriPermission(granted, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        granted = null;
    }
}
