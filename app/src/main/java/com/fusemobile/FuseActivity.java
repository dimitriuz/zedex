package com.fusemobile;

import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Entry point for the Android port of Fuse.
 *
 * Fuse itself is used completely unmodified: it is cross-compiled with its SDL 2
 * UI and linked as libmain.so, so SDL's Android bootstrap finds the SDL_main
 * that <SDL.h> generates from fuse.c's main().
 *
 * All this class has to do is set up the environment Fuse expects from a
 * Unix host before that main() runs:
 *
 *   - unpack the ROMs / widget font / bitmaps into FUSEDATADIR, which was
 *     baked in at configure time as {@code /data/data/com.fusemobile/files/fuse}
 *     (see scripts/build-native.sh);
 *   - point $HOME, $XDG_CONFIG_HOME and $TMPDIR at writable app storage;
 *   - hand Fuse its command line via SDL's getArguments() hook.
 */
public class FuseActivity extends SDLActivity {

    private static final String TAG = "FuseMobile";

    /** Assets subdirectory unpacked into {@code getFilesDir()/fuse}. */
    private static final String DATA_DIR = "fuse";

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    /** Fuse's command line. argv[0] is supplied by SDL. */
    @Override
    protected String[] getArguments() {
        return new String[] {
            "--machine", "128",
            // Without this SDL honours Fuse's 320x240 window request and the
            // emulated screen ends up unscaled in a corner of the surface.
            "--full-screen",
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File files = getFilesDir();
        try {
            installAssets(DATA_DIR, new File(files, DATA_DIR));
        } catch (IOException e) {
            Log.e(TAG, "failed to unpack Fuse data files", e);
        }

        try {
            // Fuse resolves its config through $XDG_CONFIG_HOME / $HOME and
            // writes temporary files to $TMPDIR; none of the Unix defaults
            // (/tmp, an unset HOME) are writable on Android.
            Os.setenv("HOME", files.getAbsolutePath(), true);
            Os.setenv("XDG_CONFIG_HOME", files.getAbsolutePath(), true);
            Os.setenv("TMPDIR", getCacheDir().getAbsolutePath(), true);
        } catch (ErrnoException e) {
            Log.e(TAG, "failed to set up environment", e);
        }

        super.onCreate(savedInstanceState);
    }

    /**
     * Copies an assets directory into internal storage. Fuse opens these with
     * plain stdio, so they cannot stay inside the APK.
     */
    private void installAssets(String assetDir, File target) throws IOException {
        String[] entries = getAssets().list(assetDir);
        if (entries == null || entries.length == 0) return;

        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("cannot create " + target);
        }

        for (String entry : entries) {
            String assetPath = assetDir + "/" + entry;
            File out = new File(target, entry);

            String[] children = getAssets().list(assetPath);
            if (children != null && children.length > 0) {
                installAssets(assetPath, out);
                continue;
            }

            // Data files are read-only and versioned with the APK, so an
            // existing copy of the right size is always up to date.
            try (InputStream in = getAssets().open(assetPath)) {
                if (out.length() == in.available() && out.exists()) continue;
            }

            try (InputStream in = getAssets().open(assetPath);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        }
    }
}
