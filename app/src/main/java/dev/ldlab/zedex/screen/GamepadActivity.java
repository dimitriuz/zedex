package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.view.SafeArea;
import dev.ldlab.zedex.input.Gamepad;
import dev.ldlab.zedex.input.Hotkeys;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Binds a controller's buttons to the app's own actions.
 *
 * The list is of <i>actions</i> rather than of buttons, each showing what it is
 * on: there are a fixed two dozen things a hotkey can do and any number of pads
 * to do them with, so the actions are what can be laid out and read down.
 *
 * <b>Bindings are captured, not chosen.</b> Tap a row and press the button — the
 * screen takes whatever the pad actually sends, which is the only thing that
 * works across pads that disagree about what their own buttons are called. It
 * needs a controller connected, and says so when there is not one.
 *
 * A screen of its own like {@link ProfileActivity}, and for the same reason: it
 * is a page of rows that each open something, which a menu sheet is the wrong
 * shape for.
 */
public final class GamepadActivity extends Activity {

    /** Every screen speaks the chosen language; see {@link Language}. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Language.wrap(base));
    }

    private static final int ROW = 0x22ffffff;

    private SharedPreferences preferences;

    /** The rows, so a capture can rewrite them all without rebuilding. */
    private final Button[] rows = new Button[Hotkeys.Action.values().length];
    private Button modifierRow;

    /**
     * What the next button press binds to, or null while nothing is waiting.
     * The hotkey itself is {@code capturingModifier}.
     */
    private Hotkeys.Action capturing;
    private boolean capturingModifier;
    private AlertDialog capture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The manifest label is resolved in the phone's language rather than
        // this screen's, so the title is set here; see Language.
        setTitle(R.string.gamepad_activity);

        preferences = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        setContentView(build());

        // Nothing of ours under the status bar or the camera; see SafeArea.
        SafeArea.fit(findViewById(android.R.id.content));
        showBindings();
    }

    private View build() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pixels(12), pixels(12), pixels(12), pixels(24));

        column.addView(note(getString(R.string.gamepad_explain), Palette.MUTED, 13));

        modifierRow = row(getString(R.string.gamepad_hotkey));
        modifierRow.setOnClickListener(view -> captureModifier());
        column.addView(modifierRow, rowParams());

        column.addView(note(getString(R.string.gamepad_actions), Palette.MUTED, 13));

        for (Hotkeys.Action action : Hotkeys.Action.values()) {
            Button button = row(getString(action.title));
            button.setOnClickListener(view -> capture(action));
            button.setOnLongClickListener(view -> {
                Hotkeys.bind(preferences, action, 0);
                showBindings();
                return true;
            });

            rows[action.ordinal()] = button;
            column.addView(button, rowParams());
        }

        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(0xff14151a);
        page.addView(column);

        return page;
    }

    /** Every row's button, and the hotkey's. */
    private void showBindings() {
        int modifier = Hotkeys.modifier(preferences);

        modifierRow.setText(getString(R.string.gamepad_hotkey) + "\n"
                + (modifier == 0 ? getString(R.string.gamepad_hotkey_none)
                                 : Hotkeys.buttonName(modifier)));

        for (Hotkeys.Action action : Hotkeys.Action.values()) {
            int keycode = Hotkeys.keycodeFor(preferences, action);
            Button button = rows[action.ordinal()];

            String held = action.held ? " " + getString(R.string.gamepad_held) : "";
            String pressing = keycode == 0
                    ? getString(R.string.gamepad_unbound)
                    : (modifier == 0 ? "" : Hotkeys.buttonName(modifier) + " + ")
                            + Hotkeys.buttonName(keycode) + held;

            button.setText(getString(action.title) + "\n" + pressing);
            button.setTextColor(keycode == 0 ? Palette.MUTED : Palette.TEXT);
        }
    }

    private void capture(Hotkeys.Action action) {
        capturing = action;
        capturingModifier = false;
        ask(getString(R.string.gamepad_press, getString(action.title)));
    }

    private void captureModifier() {
        capturing = null;
        capturingModifier = true;
        ask(getString(R.string.gamepad_press_hotkey));
    }

    /**
     * Waits for a button. The dialog is what makes the wait obvious, and its
     * buttons are the two ways out that a press is not: clearing the binding,
     * and changing one's mind.
     */
    private void ask(String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Gamepad.connected() ? R.string.gamepad_waiting
                                              : R.string.gamepad_none)
                .setMessage(Gamepad.connected() ? message
                                                : getString(R.string.gamepad_none_message))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> stop())
                .setOnDismissListener(dialog -> stop());

        if (Gamepad.connected()) {
            builder.setPositiveButton(R.string.gamepad_clear, (dialog, which) -> {
                if (capturingModifier) Hotkeys.setModifier(preferences, 0);
                else if (capturing != null) Hotkeys.bind(preferences, capturing, 0);

                stop();
                showBindings();
            });
        }

        capture = builder.show();
    }

    private void stop() {
        capturing = null;
        capturingModifier = false;

        if (capture != null) {
            AlertDialog dialog = capture;
            capture = null;
            dialog.dismiss();
        }
    }

    /**
     * Takes the press while a capture is waiting.
     *
     * Through {@code dispatchKeyEvent} rather than {@code onKeyDown}, because a
     * dialog is up and would otherwise have the event first - Start and B are
     * both ways of dismissing one, and both are buttons somebody will want to
     * bind.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if ((capturing == null && !capturingModifier)
                || event.getAction() != KeyEvent.ACTION_DOWN
                || !Gamepad.isFrom(event)) {
            return super.dispatchKeyEvent(event);
        }

        int keycode = event.getKeyCode();

        if (capturingModifier) {
            Hotkeys.setModifier(preferences, keycode);
        } else if (keycode == Hotkeys.modifier(preferences)) {
            // The hotkey cannot also be an action: it is held down while the
            // action is pressed, so it would fire itself on the way.
            warn(R.string.gamepad_is_hotkey);
            stop();
            return true;
        } else {
            Hotkeys.bind(preferences, capturing, keycode);
        }

        stop();
        showBindings();
        return true;
    }

    private void warn(int message) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private Button row(String title) {
        Button button = new Button(this);

        button.setAllCaps(false);
        button.setText(title);
        button.setTextColor(Palette.TEXT);
        button.setBackgroundColor(ROW);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(pixels(14), pixels(10), pixels(14), pixels(10));

        return button;
    }

    private TextView note(String words, int colour, float size) {
        TextView view = new TextView(this);

        view.setText(words);
        view.setTextColor(colour);
        view.setTextSize(size);
        view.setLineSpacing(0, 1.15f);
        view.setPadding(pixels(4), pixels(10), pixels(4), pixels(6));

        return view;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, pixels(3), 0, pixels(3));

        return params;
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    public static void open(Context context) {
        context.startActivity(new Intent(context, GamepadActivity.class));
    }
}
