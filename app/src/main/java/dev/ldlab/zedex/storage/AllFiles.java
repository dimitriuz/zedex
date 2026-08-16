package dev.ldlab.zedex.storage;

import dev.ldlab.zedex.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Asking Android for All files access - in one place, not two.
 *
 * {@code StartPanel}'s ROMs panel needs this dialog (a data folder outside
 * the app's own storage, and no All files access to read it with) and so
 * does {@code FoldersPage}'s pair of folder pickers (choosing "anywhere at
 * all", or a folder that turns out to need the permission) - and each of
 * those callers pairs the ask with its own pending-folder/{@code onResumed}
 * mechanism, so two independent copies of the dialog itself is exactly the
 * shape that drifts silently: a change to one and not the other leaves a
 * permission that gets granted and never applied. This is a storage
 * question - {@code MANAGE_EXTERNAL_STORAGE} is a storage permission - so it
 * sits beside {@link Storage}, which already answers every other "may we
 * write here" question, even though what it builds is a dialog.
 */
public final class AllFiles {

    private AllFiles() {
    }

    /**
     * @param activity the screen the dialog and the settings hand-off both
     *                 belong to
     * @param why what the permission is for, which is not the same each
     *            time: one caller is about to choose a folder, another has
     *            one already and cannot read it, and the ROMs panel is a
     *            data folder Android is already refusing to list.
     */
    public static void ask(Activity activity, int why) {
        ask(activity, why, null);
    }

    /**
     * As {@link #ask(Activity, int)}, but with somewhere for Cancel to go.
     *
     * The two-argument form's Cancel is a plain dismiss, which is right for
     * a caller with no state pinned on this ask - {@code chooseAnyFolder}'s
     * own request never sets anything, and the two onboarding pickers clear
     * their {@code pending} unconditionally on the next resume regardless of
     * how they got there. {@code SettingsActivity.useFolder} is different:
     * it sets {@code pendingFolder} before asking, precisely so a grant
     * arriving late - after the user has left this dialog - still applies
     * the folder they picked. Cancel has to undo that pin, or a permission
     * granted afterwards through an unrelated route (the ROMs panel, say)
     * would silently apply a folder the user explicitly backed out of.
     *
     * @param onCancel run on Cancel or on a cancel of the dialog itself - a
     *                 back press or a tap outside it, which land nowhere
     *                 without this. Not called on the guard branch, since
     *                 nothing was pinned before that branch returns either.
     */
    public static void ask(Activity activity, int why, Runnable onCancel) {
        // Nothing to grant in a build that does not declare it, and the
        // settings page would open empty. Every caller checks first; this is
        // the backstop.
        if (!Storage.canAskForAnyFolder(activity)) {
            Toast.makeText(activity, R.string.settings_folder_unusable,
                           Toast.LENGTH_LONG).show();
            return;
        }

        // Back and tap-outside dismiss the dialog through Android's own
        // cancel listener rather than either button, so onCancel is wired
        // there too - both mean the same "no, discard this" here. Guarded
        // rather than trusted to fire once: AlertDialog does not call the
        // cancel listener for a button press, but nothing here should rely
        // on that holding forever, and this way it cannot matter either way.
        boolean[] ran = {false};
        Runnable cancelOnce = () -> {
            if (onCancel != null && !ran[0]) {
                ran[0] = true;
                onCancel.run();
            }
        };

        new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(why)
                .setPositiveButton(R.string.settings_grant, (dialog, which) ->
                        activity.startActivity(new Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + activity.getPackageName()))))
                .setNegativeButton(android.R.string.cancel, onCancel == null ? null
                        : (dialog, which) -> cancelOnce.run())
                .setOnCancelListener(dialog -> cancelOnce.run())
                .show();
    }
}
