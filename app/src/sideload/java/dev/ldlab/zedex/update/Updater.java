package dev.ldlab.zedex.update;

import dev.ldlab.zedex.R;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Offers the newest release from GitHub, for the builds that came from there.
 *
 * The app is published twice: on Google Play, which updates itself, and as an
 * APK on GitHub, which until now had no way of telling anybody that a newer one
 * existed. This is that way - one request to the releases API at startup, and a
 * question if the answer is newer than what is running.
 *
 * <b>Never for a Play install.</b> Play updates its own, and an app from the
 * store that fetches an APK and installs it is against its policy besides. The
 * Play build does not even contain this class - {@code src/play/java} has one
 * that answers no to everything - and its manifest has the install permission
 * taken out. On top of that {@link #available} asks who installed the app, so
 * the GitHub APK sideloaded through some other store stays quiet too.
 *
 * <b>Never for a debuggable build</b> either. The APK on GitHub is the release
 * one, signed with the release key and carrying the release package name;
 * offering it to a debug build would be offering something Android would refuse
 * to install over it.
 */
public final class Updater {

    private static final String TAG = "Zedex";

    /** Whether to look at all. On unless somebody turns it off. */
    public static final String KEY_CHECK = "updateCheck";

    /** The one request: what GitHub calls the newest release of this app. */
    private static final String LATEST =
            "https://api.github.com/repos/dimitriuz/zedex/releases/latest";

    /** Long enough for a slow phone on a train, short enough not to hang about. */
    private static final int CONNECT_MS = 10_000;
    private static final int READ_MS = 20_000;

    /** Google Play's own package name, and the one install this never touches. */
    private static final String PLAY = "com.android.vending";

    /** Where the download goes: ours, private, and swept up by the system. */
    private static final String DOWNLOAD = "update.apk";

    private Updater() {
    }

    /** What the releases API said, reduced to the three things that matter. */
    private static final class Release {
        String name;        // "1.0.5"
        String apk;         // the browser_download_url of the APK
        String checksum;    // ...and of the .sha256 beside it, if there is one
    }

    /**
     * Whether this build, installed this way, may update itself.
     *
     * Three questions, and all three have to be yes. The permission is asked of
     * the manifest rather than assumed, because the Play build's manifest has it
     * removed and that build must not so much as offer.
     */
    public static boolean available(Context context) {
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return false;
        }

        if (!mayInstall(context)) return false;

        return !installedByPlay(context);
    }

    private static boolean mayInstall(Context context) {
        try {
            String[] asked = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(),
                                    PackageManager.GET_PERMISSIONS)
                    .requestedPermissions;

            if (asked != null) {
                for (String permission : asked) {
                    if (Manifest.permission.REQUEST_INSTALL_PACKAGES.equals(permission)) {
                        return true;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "cannot read our own manifest", e);
        }

        return false;
    }

    private static boolean installedByPlay(Context context) {
        try {
            String installer = context.getPackageManager()
                    .getInstallSourceInfo(context.getPackageName())
                    .getInstallingPackageName();

            return PLAY.equals(installer);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Asks GitHub, on a thread of its own, and puts the question if there is one.
     *
     * Everything about this is quiet except a release that is actually newer: no
     * spinner while it asks, nothing said when the answer is "you have it", and
     * nothing said when there is no answer at all. A phone with no signal has
     * not got a problem the user needs telling about.
     */
    public static void checkOnStart(Activity activity, SharedPreferences preferences) {
        if (!available(activity)) return;
        if (!preferences.getBoolean(KEY_CHECK, true)) return;

        new Thread(() -> {
            Release latest = fetchLatest();
            if (latest == null || latest.apk == null) return;

            if (versionCode(latest.name) <= installedVersion(activity)) return;

            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) offer(activity, latest);
            });
        }, "zedex-update").start();
    }

    // --- asking ---------------------------------------------------------------

    private static Release fetchLatest() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(LATEST).openConnection();
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);
            connection.setRequestProperty("Accept", "application/vnd.github+json");

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "no release news: HTTP " + connection.getResponseCode());
                return null;
            }

            JSONObject json;
            try (InputStream in = connection.getInputStream()) {
                json = new JSONObject(new String(readAll(in, -1, null), "UTF-8"));
            }

            Release release = new Release();
            release.name = json.optString("tag_name", "").replaceFirst("^[vV]", "");

            JSONArray assets = json.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;

                String name = asset.optString("name", "")
                                   .toLowerCase(Locale.ROOT);
                String url = asset.optString("browser_download_url", null);
                if (url == null) continue;

                if (name.endsWith(".apk")) release.apk = url;
                else if (name.endsWith(".apk.sha256")) release.checksum = url;
            }

            return release.name.isEmpty() ? null : release;
        } catch (Exception e) {
            // Offline, rate limited, GitHub having a morning: all the same here.
            Log.i(TAG, "cannot ask about releases: " + e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 1.2.3 -> 10203, the same arithmetic the build uses for versionCode. */
    private static int versionCode(String name) {
        String[] parts = name.split("\\.");
        int code = 0;

        for (int i = 0; i < 3; i++) {
            int part = 0;
            if (i < parts.length) {
                try {
                    part = Integer.parseInt(parts[i].trim());
                } catch (NumberFormatException ignored) {
                    // "1.0.5-beta" and the like: what parses, counts.
                }
            }
            code = code * 100 + part;
        }

        return code;
    }

    private static int installedVersion(Context context) {
        try {
            return versionCode(context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException e) {
            return Integer.MAX_VALUE;   // cannot tell: do not offer
        }
    }

    // --- offering -------------------------------------------------------------

    private static void offer(Activity activity, Release release) {
        new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(activity.getString(R.string.update_title, release.name))
                .setMessage(R.string.update_message)
                .setPositiveButton(R.string.update_now,
                        (dialog, which) -> download(activity, release))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    // --- fetching and installing ----------------------------------------------

    private static void download(Activity activity, Release release) {
        /*
         * Asked before the download and not after it.
         *
         * Holding REQUEST_INSTALL_PACKAGES is not permission to install
         * anything: the user has to allow this app as a source, once, in a
         * settings page of Android's own. Without that the installer answers
         * "for your security..." and the update is over - so finding out first
         * saves fetching fourteen megabytes to be told no. Found by testing on a
         * device that had never been asked.
         */
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            askToAllowInstalling(activity);
            return;
        }

        ProgressBar bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);
        bar.setMax(100);

        AlertDialog progress = new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.update_downloading)
                .setView(bar)
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            File apk = new File(activity.getCacheDir(), DOWNLOAD);
            String failure = fetch(release, apk, percent ->
                    activity.runOnUiThread(() -> bar.setProgress(percent)));

            activity.runOnUiThread(() -> {
                progress.dismiss();

                if (failure != null) {
                    apk.delete();
                    Toast.makeText(activity,
                                   activity.getString(R.string.update_failed, failure),
                                   Toast.LENGTH_LONG).show();
                    return;
                }

                install(activity, apk);
            });
        }, "zedex-download").start();
    }

    /**
     * Takes the user to the page where this app can be allowed to install one.
     *
     * There is no asking for this from code - no requestPermissions, no dialog
     * of ours that can grant it. The page is the only way, and coming back from
     * it is a resume with no result, so the update is simply offered again next
     * start rather than resumed behind the user's back.
     */
    private static void askToAllowInstalling(Activity activity) {
        new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.update_allow_title)
                .setMessage(R.string.update_allow_message)
                .setPositiveButton(R.string.settings_grant, (dialog, which) -> {
                    try {
                        activity.startActivity(new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName())));
                    } catch (Exception e) {
                        Log.w(TAG, "no unknown-sources page to open", e);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** How far along, in whole percent, for the bar. */
    private interface Progress {
        void at(int percent);
    }

    /**
     * @return null when the APK is on disk and its hash is the published one,
     *         otherwise something short enough to put in a toast
     */
    private static String fetch(Release release, File apk, Progress progress) {
        String published = release.checksum == null ? null : fetchChecksum(release.checksum);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(release.apk).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return "HTTP " + connection.getResponseCode();
            }

            long expected = connection.getContentLengthLong();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");

            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(apk)) {

                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int read;

                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    sha.update(buffer, 0, read);
                    done += read;

                    if (expected > 0) {
                        progress.at((int) (done * 100 / expected));
                    }
                }
            }

            /*
             * The release publishes a .sha256 beside the APK, so a download that
             * arrived short or scrambled is caught here rather than by the
             * installer refusing a corrupt package. If GitHub had no hash to
             * give, the install goes ahead on its own signature check.
             */
            if (published != null) {
                String got = hex(sha.digest());
                if (!published.equalsIgnoreCase(got)) {
                    Log.w(TAG, "download hash " + got + ", published " + published);
                    return "checksum";
                }
            }

            return null;
        } catch (Exception e) {
            Log.w(TAG, "cannot download the update", e);
            return e.getClass().getSimpleName();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** The published hash, which is "<hex>  <filename>" in a very small file. */
    private static String fetchChecksum(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

            try (InputStream in = connection.getInputStream()) {
                String text = new String(readAll(in, 4096, null), "UTF-8").trim();
                int space = text.indexOf(' ');
                return space > 0 ? text.substring(0, space) : text;
            }
        } catch (Exception e) {
            Log.i(TAG, "no published checksum: " + e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int limit, Void unused)
            throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        int read;

        while ((read = in.read(buffer)) != -1) {
            all.write(buffer, 0, read);
            if (limit > 0 && all.size() > limit) break;
        }

        return all.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format("%02x", b));
        return text.toString();
    }

    /**
     * Hands the APK to the system installer, which asks the user itself.
     *
     * A {@code content://} URI from our own FileProvider rather than a path: an
     * installer in another process cannot open a file in our cache, and a
     * {@code file://} URI has been refused since Android 7.
     */
    private static void install(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    activity, activity.getPackageName() + ".updates", apk);

            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                           | Intent.FLAG_ACTIVITY_NEW_TASK);

            activity.startActivity(install);
        } catch (Exception e) {
            Log.w(TAG, "cannot start the installer", e);
            Toast.makeText(activity,
                           activity.getString(R.string.update_failed,
                                              e.getClass().getSimpleName()),
                           Toast.LENGTH_LONG).show();
        }
    }
}
