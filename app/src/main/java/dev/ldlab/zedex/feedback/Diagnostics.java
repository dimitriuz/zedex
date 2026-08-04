package dev.ldlab.zedex.feedback;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.storage.Storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.view.InputDevice;
import android.os.Build;
import android.util.DisplayMetrics;

import java.io.File;

/**
 * What the app knows about itself, as a dozen lines of text.
 *
 * For a bug report, and for nothing else: it is built when somebody asks for it,
 * shown to them before it goes anywhere, and sent by their own mail app. Nothing
 * here is gathered in the background, kept, or sent on its own — see
 * {@link Feedback}.
 *
 * Every line is a fact somebody has had to ask for by hand at some point in this
 * project's life. Which build and where it came from, because the Play one and
 * the GitHub one behave differently. The Android version, because the storage
 * rules moved three times. The screen and the cutout, because two of the worst
 * layout bugs were a tablet's geometry. Where the ROMs ended up, because the
 * answer is sometimes "not where you think". The settings that change what the
 * app does, because "it does not work" usually means one of them.
 *
 * {@code key=value} a line, so that it reads plainly and two reports diff.
 */
public final class Diagnostics {

    private Diagnostics() {
    }

    /** Google Play's own package name; what installed us says which build this is. */
    private static final String PLAY = "com.android.vending";

    public static String report(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS, Context.MODE_PRIVATE);

        StringBuilder out = new StringBuilder();

        app(context, out);
        device(context, out);
        storage(context, out);
        settings(context, preferences, out);
        controllers(context, out);

        return out.toString();
    }

    private static void app(Context context, StringBuilder out) {
        String version = "?";
        long code = 0;

        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            version = info.versionName;
            code = info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException ignored) {
            // Cannot happen for our own package; the "?" says so if it does.
        }

        line(out, "app", context.getPackageName() + " " + version + " (" + code + ")");
        line(out, "build", context.getString(R.string.build_commit)
                         + " " + context.getString(R.string.build_date));
        line(out, "installer", installer(context));
    }

    /**
     * Who installed the app, which is how a report says whether it came from
     * Play or from a Releases page - the two behave differently over storage and
     * updates, so it is the first thing worth knowing.
     */
    private static String installer(Context context) {
        try {
            String from = context.getPackageManager()
                    .getInstallSourceInfo(context.getPackageName())
                    .getInstallingPackageName();

            if (from == null) return "sideloaded";
            return PLAY.equals(from) ? "play" : from;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private static void device(Context context, StringBuilder out) {
        line(out, "android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        line(out, "device", Build.MANUFACTURER + " " + Build.MODEL);
        line(out, "abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?");

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        line(out, "screen", metrics.widthPixels + "x" + metrics.heightPixels
                          + " @" + metrics.densityDpi + "dpi");
    }

    private static void storage(Context context, StringBuilder out) {
        File roms = Storage.romsDirectory(context);
        File root = Storage.root(context);

        line(out, "data", root.getAbsolutePath());

        // Whether the ROMs are in the data folder or in the app's own storage,
        // which is the fallback for a folder that cannot hold them - and the
        // difference between "no ROMs" and "ROMs you cannot see".
        boolean own = roms.getAbsolutePath().startsWith(
                context.getFilesDir().getAbsolutePath());

        line(out, "roms", (Storage.haveRoms(context) ? "present" : "MISSING")
                        + (own ? ", app storage" : ", data folder"));
        line(out, "allFiles", String.valueOf(Storage.canUseAnyFolder()));
    }

    /**
     * Only the settings that change what the app does, not all of them.
     *
     * A setting nobody has touched reads "default" and not "?": elsewhere in this
     * report a question mark means the app could not find out, and the two are
     * worth telling apart by anyone reading it.
     */
    private static void settings(Context context, SharedPreferences preferences,
                                 StringBuilder out) {
        line(out, "machine", set(preferences, SettingsActivity.KEY_MACHINE));
        line(out, "filter", set(preferences, SettingsActivity.KEY_FILTER));
        line(out, "scale", set(preferences, SettingsActivity.KEY_SCALE_PORTRAIT)
                         + "/" + set(preferences, SettingsActivity.KEY_SCALE_LANDSCAPE));
        line(out, "keyboard", set(preferences, SettingsActivity.KEY_KEYBOARD_SKIN));
        line(out, "joystick", set(preferences, SettingsActivity.KEY_JOYSTICK_TYPE));
        line(out, "fullscreen",
             String.valueOf(preferences.getBoolean(SettingsActivity.KEY_FULLSCREEN, false)));
        line(out, "secondScreen",
             String.valueOf(preferences.getBoolean(SettingsActivity.KEY_SECOND_SCREEN, false)));
    }

    /**
     * Whether anything the app would treat as a controller is plugged in.
     *
     * A great many reports are about controls, and "the buttons do nothing" reads
     * quite differently once you know a pad is connected and the on-screen one
     * has therefore stepped aside.
     */
    private static void controllers(Context context, StringBuilder out) {
        StringBuilder pads = new StringBuilder();

        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) continue;

            int sources = device.getSources();
            boolean pad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                       || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;

            if (!pad) continue;
            if (pads.length() > 0) pads.append(", ");
            pads.append(device.getName());
        }

        line(out, "controllers", pads.length() == 0 ? "none" : pads.toString());
    }

    /** A stored string, or the word for one nobody has changed. */
    private static String set(SharedPreferences preferences, String key) {
        String value = preferences.getString(key, null);
        return value == null || value.isEmpty() ? "default" : value;
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value).append('\n');
    }
}
