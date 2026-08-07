# Keep rules for the JNI boundary.
#
# Not currently applied: minifyEnabled is false for every build type. They are
# here and wired into the release block anyway, because the day somebody turns
# minification on is the day this becomes a shipped crash that no debug build
# can show, and a rule that is already written and already reviewed costs
# nothing until then.
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
#     72 native methods. onError, onFrame and onScreenshot do not: they are
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
