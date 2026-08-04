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


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * Where the newest release's own page is. Asked with redirects turned off,
     * because the answer is the redirect: it comes back
     * {@code 302 Location: .../releases/tag/v1.1.1}, and that tag is the version.
     *
     * No asset of its own for this. An earlier version published a latest.json
     * beside the APK and read that, which worked but put a file on the release
     * page that means nothing to anybody downloading it. Everything needed is in
     * the two files a release already has - the APK and its .sha256 - as long as
     * their names follow the convention the release workflow gives them, which is
     * {@code Zedex-<version>.apk}. That coupling is the price of a clean release
     * page, and it is between two files in this repository.
     */
    private static final String LATEST = RELEASES + "/latest";

    /**
     * The counted file, and the reason the check is worth anything as a measure.
     *
     * GitHub keeps a download_count per release asset, so fetching the .sha256 of
     * the release this build came from counts one start of this version - and
     * summed across releases, one start of the app. It is a file that has to
     * exist anyway, for anybody checking a download by hand.
     *
     * The API would have been the obvious way to ask what the newest release is,
     * and it is not used for two reasons: sixty unauthenticated requests an hour
     * are counted *per IP*, and a carrier NAT is one address for a great many
     * phones - and an API call is not counted, so it would tell us nothing.
     */
    private static String hashUrl(String version) {
        return apkUrl(version) + ".sha256";
    }

    private static String apkUrl(String version) {
        return RELEASES + "/download/v" + version + "/Zedex-" + version + ".apk";
    }

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

            String newest = newestVersion();
            if (newest == null) return;
            if (versionCode(newest) <= installedVersion(activity)) return;

            Release latest = new Release();
            latest.name = newest;
            latest.apk = apkUrl(newest);

            // Only now, so somebody already up to date costs one redirect and
            // nothing else.
            latest.sha256 = fetchHash(hashUrl(newest));

            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) offer(activity, latest);
            });
        }, "zedex-update").start();
    }

    // --- asking ---------------------------------------------------------------

    /**
     * What GitHub calls the newest release, from the redirect and nothing else.
     *
     * A HEAD, with following turned off: the body is a web page nobody here wants
     * and the header is the whole answer. A draft or a pre-release is not the
     * "latest" as far as GitHub is concerned, which is the behaviour wanted -
     * an unfinished release offers itself to nobody.
     */
    private static String newestVersion() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(LATEST).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);

            String location = connection.getHeaderField("Location");
            return versionFrom(location);
        } catch (Exception e) {
            // Offline, GitHub having a morning: all the same here.
            Log.i(TAG, "cannot ask about releases: " + e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * {@code .../releases/tag/v1.1.1} to {@code 1.1.1}.
     *
     * Package-visible and taking a string so the shape can be tested without a
     * network: it is a contract with somebody else's redirect, and the app is
     * silent rather than wrong if it ever changes - which is the sort of thing
     * worth a test rather than a hope.
     */
    static String versionFrom(String location) {
        if (location == null) return null;

        Matcher tag = Pattern.compile("/releases/tag/v?([0-9]+(?:\\.[0-9]+)*)")
                             .matcher(location);

        return tag.find() ? tag.group(1) : null;
    }

    /**
     * The hash a release published beside its APK, or null.
     *
     * The file is {@code "<hex>  <filename>"}. Null is not a failure worth
     * reporting: the install then rests on Android's own signature check, which
     * is the one that actually matters.
     */
    private static String fetchHash(String url) {
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
            Log.i(TAG, "no published hash: " + e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Downloads this build's own {@code .sha256} and throws it away.
     *
     * Eighty-two bytes whose only product is a number in somebody else's
     * database: the count on that asset is how many times this version has been
     * started, and across releases how much the app is used at all. A release
     * older than any of this has the file too, so old versions report as well.
     *
     * Best effort by design, so a failure is not worth a line in the log, let
     * alone telling the user about.
     */
    private static void ping(Context context) {
        HttpURLConnection connection = null;
        try {
            String version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;

            connection = (HttpURLConnection) new URL(
                    hashUrl(version)).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);

            try (InputStream in = connection.getInputStream()) {
                readAll(in, 4096, null);
            }
        } catch (Exception ignored) {
            // A release too old to have the file has none, and that is fine.
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
            askToAllowInstalling(activity, release);
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
    private static void askToAllowInstalling(Activity activity, Release release) {
        new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.update_allow_title)
                .setMessage(R.string.update_allow_message)
                .setPositiveButton(R.string.settings_grant, (dialog, which) -> {
                    // Remembered here and not when the dialog appeared: going to
                    // the page is the user saying yes, and only that should make
                    // coming back start a download. Somebody who cancelled, and
                    // later allows the app to install for their own reasons,
                    // should not find it fetching something.
                    awaiting = release;

                    try {
                        activity.startActivity(new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName())));
                    } catch (Exception e) {
                        awaiting = null;
                        Log.w(TAG, "no unknown-sources page to open", e);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * The update the user went to the settings page for, if they did.
     *
     * Static, because the page is another activity and this one may be recreated
     * while it is open - and because there is only ever one of these in flight.
     */
    private static Release awaiting;

    /**
     * Carries on where {@link #askToAllowInstalling} left off.
     *
     * Called from onResume. Allowing an app to install packages is a page of
     * Android's own with no result to wait for, so the only way to notice the
     * answer is to look again on the way back - and without this the update
     * simply stopped: permission granted, nothing downloaded, and the offer only
     * returning after a restart. Which is what a user reported, and fairly.
     *
     * Still not allowed means the pending update is kept rather than dropped:
     * they may be on their way to grant it. Nothing is said either way.
     */
    public static void resumeIfAllowed(Activity activity) {
        Release release = awaiting;
        if (release == null) return;

        if (!activity.getPackageManager().canRequestPackageInstalls()) return;

        awaiting = null;
        download(activity, release);
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
             * The .sha256 published beside the APK is the hash the release
             * workflow computed, so a download that arrived short or scrambled
             * is caught here rather than by the installer refusing a corrupt
             * package. A release without one installs anyway, on Android's own
             * signature check.
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
