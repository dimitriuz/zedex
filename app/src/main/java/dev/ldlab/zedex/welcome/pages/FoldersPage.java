package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.welcome.Step;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Where things are kept, and where they are opened from.
 *
 * Everything the app writes goes in the data folder, and everything it opens
 * tends to live in one place too, and both are better asked about once than
 * discovered later - a hundred saved states in app-private storage are a
 * hundred states that go when the app is uninstalled. This used to be a
 * screen of its own, shown before anything else could be; now it is one page
 * of the wizard, and skippable like every other - see {@code
 * dev.ldlab.zedex.screen.StartPanel}, which is left with only the ROMs half
 * of what it used to do.
 */
public final class FoldersPage implements Step {

    /** Ours to answer; WelcomeActivity forwards anything with these codes. */
    public static final int REQUEST_DATA_TREE = 8;
    public static final int REQUEST_CONTENT_TREE = 9;

    private final Activity activity;

    /** Held from {@link #body}, so a result or a resume can reach it too. */
    private SharedPreferences preferences;

    private TextView dataFolder;
    private TextView contentFolder;

    /**
     * A folder chosen before the app was allowed to use it.
     *
     * Granting All files access is another screen, and the way back from it is
     * a resume rather than a result - so the choice is kept here and made when
     * the answer arrives, instead of asking the user to pick the same folder
     * twice with no sign that the first time did anything.
     */
    private File pending;

    public FoldersPage(Activity activity) {
        this.activity = activity;
    }

    @Override
    public int title() {
        return R.string.setup_title;
    }

    @Override
    public int blurb() {
        return R.string.setup_message;
    }

    @Override
    public View body(Context context, SharedPreferences preferences) {
        this.preferences = preferences;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // The two folders, the same kind of row with the answer written on
        // the button: what a folder is called matters more here than what
        // the row would do to it.
        dataFolder = new TextView(context);
        column.addView(Cards.valueCard(context, dataFolder, R.string.setup_data_hint,
                v -> chooseDataFolder()));

        contentFolder = new TextView(context);
        column.addView(Cards.valueCard(context, contentFolder, R.string.setup_content_hint,
                v -> chooseContentFolder()));

        describeFolders();

        return column;
    }

    /**
     * Results for the two pickers.
     *
     * A settings-page permission has no {@code onActivityResult} of its own -
     * see {@link #onResumed} - but choosing a folder from Android's own picker
     * does, and this is that half.
     */
    public void onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_DATA_TREE && request != REQUEST_CONTENT_TREE) return;

        Uri tree = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (tree == null) return;

        if (request == REQUEST_DATA_TREE) {
            File folder = Storage.pathFor(tree);

            if (folder == null) toast(R.string.settings_folder_unusable);
            else useDataFolder(folder);
        }

        if (request == REQUEST_CONTENT_TREE) {
            // Write too, now that importing a game means writing into this
            // same folder - see Tree.canWrite. An existing grant made before
            // this cannot be upgraded in place; Task 11's import flow is what
            // asks a read-only grant to be re-picked.
            Storage.keepAccessTo(activity, tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            preferences.edit()
                    .putString(Storage.KEY_CONTENT_TREE, tree.toString())
                    .apply();
            describeFolders();
        }
    }

    /**
     * Back from somewhere - most usefully from Android's All files screen.
     *
     * A folder picked before the permission existed is applied now, and the
     * rows are described again either way: the permission decides what the
     * default folder is, so granting it changes what this page should say
     * even when nothing was picked.
     *
     * Only reached while this page is the one on screen - {@code
     * WelcomeActivity} checks {@code instanceof FoldersPage} before calling
     * it - so unlike {@code StartPanel}'s own version, there is no "is this
     * still being asked" flag to check first.
     */
    public void onResumed() {
        File wanted = pending;
        pending = null;

        if (wanted != null && Storage.canUseAnyFolder()) {
            useDataFolder(wanted);
            return;
        }

        describeFolders();
    }

    /** Both buttons say where they point, which is the answer they hold. */
    private void describeFolders() {
        dataFolder.setText(activity.getString(R.string.setup_data,
                Storage.root(activity).getAbsolutePath()));

        String content = Storage.describe(
                preferences.getString(Storage.KEY_CONTENT_TREE, null));

        contentFolder.setText(activity.getString(R.string.setup_content,
                content != null ? content
                        : activity.getString(R.string.setup_content_none)));
    }

    /**
     * The data folder, as the settings screen offers it: the roots the device
     * has, and anywhere at all for whoever has granted All files access.
     *
     * By path and not as a document tree, because Fuse opens its files with
     * stdio and a {@code content://} URI is not something it can pass to
     * {@code fopen}.
     */
    private void chooseDataFolder() {
        // The short one at the root of storage goes first, and is offered
        // whether or not the permission to make it is held: it is the answer
        // most people want, and being told what it needs is more use than not
        // being shown it. Picking it without the permission asks for that
        // instead, and comes back here.
        List<File> roots = new ArrayList<>();
        // A folder at the root of storage, and any folder at all, are both only
        // worth offering to a build that can ask for the permission they need.
        boolean anywhere = Storage.canAskForAnyFolder(activity);

        if (anywhere) roots.add(Storage.sharedRoot(activity));
        roots.addAll(Storage.roots(activity));

        String[] items = new String[roots.size() + (anywhere ? 1 : 0)];

        for (int i = 0; i < roots.size(); i++) {
            items[i] = Storage.label(activity, roots.get(i))
                    + "\n" + roots.get(i).getAbsolutePath();
        }
        if (anywhere) {
            items[roots.size()] = activity.getString(R.string.settings_choose_folder);
        }

        new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.settings_data_folder)
                .setItems(items, (dialog, which) -> {
                    if (which < roots.size()) {
                        useDataFolder(roots.get(which));
                    } else if (!Storage.canUseAnyFolder()) {
                        askForAllFiles(R.string.settings_all_files);
                    } else {
                        activity.startActivityForResult(
                                new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                                REQUEST_DATA_TREE);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

    private void useDataFolder(File folder) {
        // A folder outside the app's own storage cannot even be made without
        // All files access, so a refusal here is usually a permission and not a
        // bad folder. Ask for the one, remember the other, and put it in place
        // when the answer comes back - see {@link #onResumed}.
        if (!Storage.canUseAnyFolder() && Storage.needsAllFilesFor(activity, folder)) {
            pending = folder;
            askForAllFiles(R.string.settings_all_files_folder);
            return;
        }

        if (!Storage.isWritable(folder)) {
            toast(R.string.settings_folder_unusable);
            return;
        }

        preferences.edit()
                .putString(Storage.KEY_STATES_ROOT, folder.getAbsolutePath())
                .apply();

        // Nothing to move: on the first run there is nothing there yet, and the
        // ROMs are unpacked into whatever this ends up being when the machine
        // is asked for.
        Storage.createFolders(activity);
        describeFolders();
    }

    private void chooseContentFolder() {
        try {
            activity.startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                    REQUEST_CONTENT_TREE);
        } catch (android.content.ActivityNotFoundException e) {
            toast(R.string.open_failed);
        }
    }

    /** A toast, for the things that finish with nothing to show. */
    private void toast(int text, Object... arguments) {
        Toast.makeText(activity, activity.getString(text, arguments),
                       Toast.LENGTH_LONG).show();
    }
}
