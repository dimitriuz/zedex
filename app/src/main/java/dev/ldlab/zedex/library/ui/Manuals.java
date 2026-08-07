package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;

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

    /** {@link #open(Context, Uri, Display)} with no display to ask for - the
     *  ordinary path, unchanged from before that existed. */
    public static void open(Context context, Uri manual) {
        open(context, manual, null);
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
     */
    public static void open(Context context, Uri manual, Display display) {
        if (manual == null) return;

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
            try {
                shareable = FileProvider.getUriForFile(
                        context, context.getPackageName() + ".esde", new File(manual.getPath()));
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
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(intent, 0)) {
            context.grantUriPermission(info.activityInfo.packageName, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }
}
