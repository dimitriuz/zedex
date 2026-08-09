# Keep rules for the JNI boundary.
#
# Applied: minifyEnabled is true for release. It was false when these were
# written, and they were written first on purpose - the day somebody turns
# minification on is the day this becomes a shipped crash that no debug build
# can show. That day has come and the rules were already reviewed.
#
# Verified on a release install rather than assumed, because none of this can
# fail in a way the test suite sees: instrumentation runs against the debug
# build, where minification is off. Checked by hand - the app launched, a
# malformed .sna produced "libspectrum_sna_identify: unknown length" on
# screen, and a recording came out a 274-frame GIF. Those three are onError,
# onFrame and onScreenshot respectively, and they are the whole point of this
# file.
#
# There are two ways to get it wrong and only one of them is obvious.
#
#   * With minifyEnabled true and NO configuration at all, R8 renames
#     dev.ldlab.zedex.FuseNative. android_bridge.c does
#     FindClass("dev/ldlab/zedex/FuseNative") in JNI_OnLoad, gets NULL, and
#     the app is dead on launch - in release only.
#
#   * With minifyEnabled true and the *standard* proguard-android-optimize.txt
#     the class survives, because that file carries
#     -keepclasseswithmembernames class * { native <methods>; }, and so do all
#     71 native methods. onError, onFrame and onScreenshot do not: they are
#     package-private statics called from C and from nowhere in Java, so R8
#     reads them as unreachable and removes them. The three GetStaticMethodID
#     calls then return NULL, the guards in android_bridge.c skip the
#     callbacks, and the app ships with errors that never appear and GIF
#     recording that writes nothing. Nothing crashes and nothing is logged.
#
# That second one is why this file exists rather than a line in build.gradle
# saying getDefaultProguardFile is enough. Keep the callbacks by name.

-keepclasseswithmembers class dev.ldlab.zedex.FuseNative {
    native <methods>;

    # Called only from android_bridge.c, by GetStaticMethodID, so R8 has no
    # way to see them as reachable. Signatures must match the ones there.
    static void onError(int, java.lang.String);
    static void onFrame(int, int);
    static void onScreenshot(int, int);
}

# The class itself, for the FindClass above: keeping members does not stop the
# type being renamed.
-keepnames class dev.ldlab.zedex.FuseNative

# EmulatorActivity is addressed by name from outside the app - ES-DE launches
# dev.ldlab.zedex/.EmulatorActivity by explicit component, and the scripts and
# docs use `am start -n` with the same string. The manifest keeps activities
# from being removed, but the name is a contract with other software here.
-keepnames class dev.ldlab.zedex.EmulatorActivity
