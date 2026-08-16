package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.input.Controls;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.view.SpectrumKeyboardView;
import dev.ldlab.zedex.welcome.Step;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The on-screen keyboard skin, and what a game should think is plugged in.
 *
 * <b>A real keyboard, drawn.</b> {@link SpectrumKeyboardView} touches
 * {@code FuseNative} only on a key press, never to draw - which is why
 * {@code ProfileActivity} can already show a plate with no machine running -
 * so the preview here is the actual skin rather than a picture of one. Touch
 * is off, so nothing is ever sent to a Fuse that is not there.
 *
 * <b>The joystick list is Fuse's own</b>, read live through
 * {@link FuseNative#joystickTypeNames()} - which, unlike {@code
 * machineIds()}, is populated before Fuse runs, so this page can ask rather
 * than keep a table the way {@link MachinePage} has to. Fuse has no joystick
 * called the keyboard: its eight are None, Cursor, Kempston, Sinclair 1/2,
 * Timex 1/2, Fuller. The pad's keyboard mode is this app's own -
 * {@link Controls#JOYSTICK_KEYBOARD} - appended after Fuse's list rather than
 * looked up in it, since looking it up by name in Fuse's own array finds
 * nothing and never will.
 */
public final class ControlsPage implements Step {

    /** The single keyboard, reused across every skin tap rather than rebuilt -
     *  {@code setSkin} already does the work of changing what it draws. */
    private SpectrumKeyboardView preview;

    /** Holds either {@link #preview} or the system-keyboard note, whichever
     *  the current skin calls for. */
    private LinearLayout previewSlot;

    @Override
    public int title() {
        return R.string.welcome_controls;
    }

    @Override
    public int blurb() {
        return R.string.welcome_controls_hint;
    }

    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        previewSlot = new LinearLayout(context);
        previewSlot.setOrientation(LinearLayout.VERTICAL);
        column.addView(previewSlot);

        preview = new SpectrumKeyboardView(context);
        preview.setEnabled(false);
        preview.setClickable(false);

        SpectrumKeyboardView.Skin current = SpectrumKeyboardView.Skin.of(
                preferences.getString(Prefs.KEY_KEYBOARD_SKIN, null));
        showSkin(context, current);

        column.addView(Cards.note(context, R.string.welcome_controls_keyboard));

        // A Cards.Group so tapping a different skin moves the cyan live,
        // rather than leaving it on the row that was current when the page
        // was built.
        Cards.Group skins = new Cards.Group();

        for (SpectrumKeyboardView.Skin skin : SpectrumKeyboardView.Skin.values()) {
            // The blurb above already says what this list of rows is for;
            // repeating it as every row's own caption was what pushed the
            // way past the wizard below the fold. Only SYSTEM needs its own
            // word - it draws nothing, unlike every other row here.
            int description = skin == SpectrumKeyboardView.Skin.SYSTEM
                    ? R.string.welcome_controls_system_note : 0;

            column.addView(skins.add(context, context.getString(skin.title),
                    description,
                    v -> {
                        preferences.edit()
                                .putString(Prefs.KEY_KEYBOARD_SKIN, skin.value)
                                .apply();
                        showSkin(context, skin);
                    },
                    skin == current));
        }

        column.addView(Cards.note(context, R.string.welcome_controls_joystick));

        // Fuse's own list, then ours appended after it - see the class
        // comment for why this array, and not a table, is the right source.
        String[] names = FuseNative.joystickTypeNames();

        int stored = preferences.getInt(Prefs.KEY_JOYSTICK_TYPE,
                Controls.JOYSTICK_KEMPSTON);

        Cards.Group joysticks = new Cards.Group();

        for (int i = 0; i < names.length; i++) {
            int type = i;
            column.addView(joysticks.add(context, names[i], 0,
                    v -> preferences.edit()
                            // putInt: the wrong getter on this key throws
                            // only when the key is present, so it passes
                            // every fresh-install test and crashes on the
                            // first device where the setting has been
                            // touched.
                            .putInt(Prefs.KEY_JOYSTICK_TYPE, type).apply(),
                    type == stored));
        }

        column.addView(joysticks.add(context, R.string.joystick_keyboard, 0,
                v -> preferences.edit()
                        .putInt(Prefs.KEY_JOYSTICK_TYPE,
                                Controls.JOYSTICK_KEYBOARD).apply(),
                stored == Controls.JOYSTICK_KEYBOARD));

        return column;
    }

    /**
     * Swaps the plate in, or the note that stands for it: SYSTEM has no keys
     * of its own to draw, so leaving the slot empty would read as a page that
     * failed to load rather than a deliberate "nothing here".
     */
    private void showSkin(Context context, SpectrumKeyboardView.Skin skin) {
        previewSlot.removeAllViews();

        if (skin == SpectrumKeyboardView.Skin.SYSTEM) {
            TextView note = Cards.note(context);
            note.setText(R.string.welcome_controls_system_note);
            previewSlot.addView(note);
            return;
        }

        preview.setSkin(skin);
        previewSlot.addView(preview);
    }
}
