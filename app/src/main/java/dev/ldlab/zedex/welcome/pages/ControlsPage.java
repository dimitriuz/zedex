package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.view.SpectrumKeyboardView;
import dev.ldlab.zedex.welcome.Step;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The on-screen keyboard skin - which plate is drawn over the picture when
 * a game asks for its keys.
 *
 * <b>A real keyboard, drawn.</b> {@link SpectrumKeyboardView} touches
 * {@code FuseNative} only on a key press, never to draw - which is why
 * {@code ProfileActivity} can already show a plate with no machine running -
 * so the preview here is the actual skin rather than a picture of one. Touch
 * is off, so nothing is ever sent to a Fuse that is not there.
 *
 * <b>Where the plate sits depends on the room there is.</b> Landscape has
 * room sideways, so the rows and the plate sit side by side and a tap
 * updates a picture that is already on screen. Portrait does not - a wide,
 * low plate squeezed into half a column would be too small to read - so the
 * list comes first and the plate under it at full width. The activity has no
 * {@code configChanges}, so a rotation rebuilds the page for whatever window
 * there is now. The joystick question used to be asked here as well and is
 * not any more: which interface a game thinks is plugged in is the machine's
 * own business (see {@code ControlsUi}), and a page about the keyboard
 * should only ask about the keyboard.
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

        LinearLayout skinsColumn = new LinearLayout(context);
        skinsColumn.setOrientation(LinearLayout.VERTICAL);

        previewSlot = new LinearLayout(context);
        previewSlot.setOrientation(LinearLayout.VERTICAL);

        preview = new SpectrumKeyboardView(context);
        preview.setEnabled(false);
        preview.setClickable(false);

        SpectrumKeyboardView.Skin current = SpectrumKeyboardView.Skin.of(
                preferences.getString(Prefs.KEY_KEYBOARD_SKIN, null));
        showSkin(context, current);

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

            skinsColumn.addView(skins.add(context, context.getString(skin.title),
                    description,
                    v -> {
                        preferences.edit()
                                .putString(Prefs.KEY_KEYBOARD_SKIN, skin.value)
                                .apply();
                        showSkin(context, skin);
                    },
                    skin == current));
        }

        // Landscape has room sideways: the rows and the plate sit side by
        // side. Portrait does not - see the class comment - so the list
        // comes first and the plate under it at full width.
        if (context.getResources().getConfiguration()
                .orientation == Configuration.ORIENTATION_LANDSCAPE) {
            LinearLayout sideBySide = new LinearLayout(context);
            sideBySide.setOrientation(LinearLayout.HORIZONTAL);

            sideBySide.addView(skinsColumn, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            previewLp.leftMargin = Cards.unit(context, 2);
            sideBySide.addView(previewSlot, previewLp);

            column.addView(sideBySide);
        } else {
            column.addView(skinsColumn);
            column.addView(previewSlot);
        }

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
