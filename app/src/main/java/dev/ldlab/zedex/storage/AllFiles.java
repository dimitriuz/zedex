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
        // Nothing to grant in a build that does not declare it, and the
        // settings page would open empty. Every caller checks first; this is
        // the backstop.
        if (!Storage.canAskForAnyFolder(activity)) {
            Toast.makeText(activity, R.string.settings_folder_unusable,
                           Toast.LENGTH_LONG).show();
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
}
