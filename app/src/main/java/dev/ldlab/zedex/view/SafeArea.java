package dev.ldlab.zedex.view;

import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

/**
 * Keeps a screen's content out of the status bar and the camera hole.
 *
 * An app targeting API 35 is laid out edge to edge whether it asks or not, and
 * into the display cutout with it: the framework no longer insets a window for
 * the system bars, and the cutout mode that used to letterbox one away from a
 * camera reads as "always". Every screen here builds its own view tree and none
 * of them asked for that, so the title of the settings page came out on top of
 * its own tabs and the quick bar's icons under the camera.
 *
 * The emulator's own window does not use this - {@link EmulatorLayout} has to
 * arrange around the same insets itself, since its children are placed by hand
 * rather than by padding. Everything else is a column in a
 * {@code setContentView}, and padding is the whole of what it needs.
 */
public final class SafeArea {

    private SafeArea() {
    }

    /**
     * Pads {@code content} by whatever the system is keeping, on top of the
     * padding it already has.
     *
     * The listener stays: the insets change with the orientation, and a cutout
     * that was at the top is at one end sideways.
     */
    public static void fit(View content) {
        int left = content.getPaddingLeft();
        int top = content.getPaddingTop();
        int right = content.getPaddingRight();
        int bottom = content.getPaddingBottom();

        content.setOnApplyWindowInsetsListener((view, insets) -> {
            // The keyboard counts as something that is not ours to draw
            // under, the same as a bar or a cutout. Without it the library's
            // list and its whole details pane sit under the IME in portrait
            // after a search, and a settings dialog's own buttons can too.
            Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout()
                            | WindowInsets.Type.ime());

            view.setPadding(left + safe.left, top + safe.top,
                            right + safe.right, bottom + safe.bottom);

            return insets;
        });

        // The first insets arrive before a listener added in onCreate would hear
        // them, so ask for them again rather than waiting for a rotation.
        content.requestApplyInsets();
    }
}
