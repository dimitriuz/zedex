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

    /** Where the releases live; both URLs below are built from this. */
    private static final String RELEASES =
            "https://github.com/dimitriuz/zedex/releases";

    /**
     * What the newest release says about itself: version, APK and its hash.
     *
     * An asset of the release rather than api.github.com, for two reasons that
     * have nothing to do with each other and both matter.
     *
     * The API allows sixty unauthenticated requests an hour <em>per IP</em>, and
     * a carrier NAT is one address for a great many phones - so the check would
     * fail exactly for the users who share one, and fail silently, which is the
     * worst way. An asset download has no such limit.
     *
     * And an asset is counted. GitHub keeps a download_count for each one, which
     * is the only anonymous measure of use this project has any way of getting:
     * nothing about anybody reaches the developer, a total does, from GitHub,
     * later. {@code /releases/latest/download/} is a permanent redirect to the
     * newest release's copy, so the URL never has to change.
     */
    private static final String LATEST = RELEASES + "/latest/download/latest.json";

    /**
     * Fetched purely to be counted, from the release this build came from.
     *
     * The counter on {@link #LATEST} says how much the app is being started; this
     * one, being per release, says which versions are doing the starting - which
     * is the question worth asking before dropping support for anything. It is
     * one small file, its contents are not read, and a 404 for a release that
     * predates all this is the expected answer and is ignored.
     */
    private static final String ALIVE = "/alive.txt";

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
    static final class Release {
        String name;      // "1.0.5"
        String apk;       // where the APK is
        String sha256;    // and what it should hash to, or null
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
            // Counted first, so the figure is "app started with the check on"
            // rather than "app found an update", which would only ever count the
            // people who are behind.
            ping(activity);

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
            connection.setInstanceFollowRedirects(true);   // /latest/ is a redirect
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "no release news: HTTP " + connection.getResponseCode());
                return null;
            }

            try (InputStream in = connection.getInputStream()) {
                return parse(new String(readAll(in, 64 * 1024, null), "UTF-8"));
            }
        } catch (Exception e) {
            // Offline, no such asset yet, GitHub having a morning: all the same.
            Log.i(TAG, "cannot ask about releases: " + e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * The three fields {@code latest.json} carries, and nothing assumed.
     *
     * Package-visible and taking a string rather than a connection so that the
     * shape the release workflow writes can be tested without a network: this is
     * a contract between a shell script and a parser, and the two are in
     * different files in different languages.
     *
     * @return null unless all three fields are there, which is what makes a
     *         half-written release no release at all
     */
    static Release parse(String json) {
        try {
            JSONObject object = new JSONObject(json);

            Release release = new Release();
            release.name = object.optString("version", "").replaceFirst("^[vV]", "");
            release.apk = object.optString("apk", null);
            release.sha256 = object.optString("sha256", null);

            if (release.name.isEmpty() || release.apk == null) return null;

            return release;
        } catch (Exception e) {
            Log.w(TAG, "latest.json did not parse", e);
            return null;
        }
    }

    /**
     * Downloads the counted file from this build's own release, and throws the
     * contents away.
     *
     * Best effort by design: it is one small GET whose only product is a number
     * in somebody else's database, so a failure is not worth a line in the log,
     * let alone telling the user about.
     */
    private static void ping(Context context) {
        HttpURLConnection connection = null;
        try {
            String version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;

            connection = (HttpURLConnection) new URL(
                    RELEASES + "/download/v" + version + ALIVE).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);

            try (InputStream in = connection.getInputStream()) {
                readAll(in, 4096, null);
            }
        } catch (Exception ignored) {
            // A release that predates alive.txt has none, and that is fine.
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
             * latest.json carries the hash the release workflow computed, so a
             * download that arrived short or scrambled is caught here rather
             * than by the installer refusing a corrupt package. A release
             * without one installs anyway, on Android's signature check.
             */
            if (release.sha256 != null) {
                String got = hex(sha.digest());
                if (!release.sha256.equalsIgnoreCase(got)) {
                    Log.w(TAG, "download hash " + got + ", published " + release.sha256);
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
