package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.view.SafeArea;
import dev.ldlab.zedex.input.ControlProfiles;
import dev.ldlab.zedex.input.Gamepad;
import dev.ldlab.zedex.input.Hotkeys;
import dev.ldlab.zedex.input.PadMap;
import dev.ldlab.zedex.input.PadMaps;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
public final class GamepadActivity extends ZedexActivity {


    private static final int ROW = 0x22ffffff;


    /** The rows, so a capture can rewrite them all without rebuilding. */
    private final Button[] rows = new Button[Hotkeys.Action.values().length];
    private Button modifierRow;

    /** One row per control slot, in slot order, so a capture can redraw them
     *  all - a capture always risks taking a binding off a second row. */
    private final Button[] controlRows = new Button[ControlProfiles.SLOTS];

    /** The pad being edited: the one connected when this screen opened. Null
     *  when none is, in which case every row shows the built-in defaults and
     *  nothing can be captured. */
    private String deviceKey;
    private String deviceName;

    /** This pad's mapping. Defaults when {@link #deviceKey} is null. */
    private PadMap map = PadMap.defaults();

    /**
     * What the next button press binds to, or null while nothing is waiting.
     * The hotkey itself is {@code capturingModifier}; a control slot is
     * {@code capturingSlot}.
     */
    private Hotkeys.Action capturing;
    private boolean capturingModifier;
    private int capturingSlot = -1;
    private AlertDialog capture;

    /** Past this a push is a capture. Well above Gamepad's own 0.4, because a
     *  binding is meant, and a play threshold would take a lean. */
    private static final float CAPTURE = 0.7f;

    /** And under this before the next one will arm - a worn stick rests off
     *  centre, and without this it binds itself the moment a row is tapped. */
    private static final float RELEASED = 0.2f;

    private boolean axisArmed = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The manifest label is resolved in the phone's language rather than
        // this screen's, so the title is set here; see Language.
        setTitle(R.string.gamepad_activity);

        InputDevice pad = connectedPad();
        if (pad != null) {
            deviceKey = PadMaps.keyFor(pad);
            deviceName = pad.getName();
        }
        map = PadMaps.load(preferences, deviceKey);

        setContentView(build());

        // Nothing of ours under the status bar or the camera; see SafeArea.
        fitToSafeArea();
        showBindings();
    }

    private View build() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pixels(12), pixels(12), pixels(12), pixels(24));

        // The machine's eight controls go first: it is what most people come
        // to this screen for, above the app's own hotkeys.
        column.addView(note(getString(R.string.gamepad_machine_section), Palette.MUTED, 13));

        for (int slot = 0; slot < ControlProfiles.SLOTS; slot++) {
            int fixedSlot = slot;
            Button button = row(ControlProfiles.slotName(this, slot));
            button.setOnClickListener(view -> captureSlot(fixedSlot));

            controlRows[slot] = button;
            column.addView(button, rowParams());
        }

        Button resetPad = row(getString(R.string.gamepad_reset_pad));
        resetPad.setOnClickListener(view -> resetPad());
        column.addView(resetPad, rowParams());

        column.addView(note(getString(R.string.gamepad_app_section), Palette.MUTED, 13));
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

    /**
     * Every row's button: the eight controls, the hotkey, and every action.
     *
     * All of them, every time - a capture on one control row can take its
     * binding off a second one (see {@link PadMap#with}), and a redraw that
     * skipped the row it did not capture would hide that having happened.
     */
    private void showBindings() {
        for (int slot = 0; slot < ControlProfiles.SLOTS; slot++) {
            PadMap.Binding binding = map.bindingFor(slot);
            Button button = controlRows[slot];

            String value;
            if (binding == null) {
                value = getString(R.string.gamepad_unbound);
            } else {
                String name = binding.isAxis
                        ? MotionEvent.axisToString(binding.code) + (binding.sign < 0 ? " -" : " +")
                        : KeyEvent.keyCodeToString(binding.code);
                value = map.isDefault(slot)
                        ? getString(R.string.gamepad_default_marker, name)
                        : name;
            }

            button.setText(ControlProfiles.slotName(this, slot) + "\n" + value);
            button.setTextColor(binding == null ? Palette.MUTED : Palette.TEXT);
        }

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
        capturingSlot = -1;
        ask(getString(R.string.gamepad_press, getString(action.title)));
    }

    private void captureModifier() {
        capturing = null;
        capturingModifier = true;
        capturingSlot = -1;
        ask(getString(R.string.gamepad_press_hotkey));
    }

    private void captureSlot(int slot) {
        capturing = null;
        capturingModifier = false;
        capturingSlot = slot;
        axisArmed = false;
        ask(getString(R.string.gamepad_press_control, ControlProfiles.slotName(this, slot)));
    }

    /**
     * Saves a capture against {@link #capturingSlot} and redraws every row.
     *
     * Pulled out to its own method, rather than left inline where it is
     * called, so a later capture path - an axis push as well as a button
     * press - can share it without repeating the save-and-redraw.
     */
    private void bind(PadMap.Binding binding) {
        map = map.with(capturingSlot, binding);
        PadMaps.save(preferences, deviceKey, deviceName, map);
        showBindings();
    }

    /** Every capture on this pad, undone in one row. */
    private void resetPad() {
        PadMaps.forget(preferences, deviceKey);
        map = PadMaps.load(preferences, deviceKey);
        showBindings();
    }

    /**
     * The pad this screen edits: whichever gamepad or joystick is connected
     * right now, or null when none is.
     *
     * Picking among several is {@code GamepadActivity}'s own job in a later
     * step; today there is at most one to find.
     */
    private static InputDevice connectedPad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) continue;

            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return device;
            }
        }
        return null;
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

        // No Clear for a control slot: unlike a hotkey, a slot is never
        // simply unbound - it is captured or it is on its defaults, and
        // "Reset this pad" is the row that answers "take it off what it is
        // on now" for every slot at once rather than one at a time.
        if (Gamepad.connected() && (capturing != null || capturingModifier)) {
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
        capturingSlot = -1;

        if (capture != null) {
            AlertDialog dialog = capture;
            capture = null;
            dialog.dismiss();
        }
    }

    /**
     * Makes {@code device} the pad this screen edits, reloading its mapping
     * first if it is not already.
     *
     * The event names its own device, so the pad being edited is fixed from
     * whatever actually sent the press or push - which also covers a pad
     * connected only after this screen opened, when nothing was found for it
     * in onCreate. When it is from a *different* pad than the one this
     * screen loaded (two pads can be connected at once - both key presses and
     * axis pushes can arrive from either), the map has to be reloaded for it
     * too - otherwise the capture would merge into the wrong pad's map and
     * save that under the new pad's key, silently replacing whatever it
     * actually had stored. There is no picker yet to make this switch
     * explicit (Task 10 adds one); until then, the press or push itself is
     * taken as saying which pad is meant.
     *
     * Shared by both capture paths so this rule is fixed in one place - two
     * copies of it is how it gets got wrong again.
     */
    private void adoptDevice(InputDevice device) {
        if (device == null) return;

        String key = PadMaps.keyFor(device);
        if (!key.equals(deviceKey)) {
            deviceKey = key;
            deviceName = device.getName();
            map = PadMaps.load(preferences, deviceKey);
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
        if ((capturing == null && !capturingModifier && capturingSlot < 0)
                || event.getAction() != KeyEvent.ACTION_DOWN
                || !Gamepad.isFrom(event)) {
            return super.dispatchKeyEvent(event);
        }

        int keycode = event.getKeyCode();

        if (capturingModifier) {
            Hotkeys.setModifier(preferences, keycode);
        } else if (capturingSlot >= 0) {
            adoptDevice(event.getDevice());
            bind(PadMap.Binding.button(keycode));
            stop();
            return true;
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

    /**
     * Takes a stick or hat push while a capture is waiting.
     *
     * Only for a control slot: the app's own hotkeys and the modifier are
     * keys, never directions, so an axis push while one of those is waiting
     * is ignored rather than offered.
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (capturingSlot < 0 || !Gamepad.isFrom(event)) {
            return super.onGenericMotionEvent(event);
        }

        InputDevice device = event.getDevice();
        if (device == null) return true;

        int furthest = -1;
        float most = 0f;

        for (InputDevice.MotionRange range : device.getMotionRanges()) {
            float value = event.getAxisValue(range.getAxis());
            if (Math.abs(value) > Math.abs(most)) {
                most = value;
                furthest = range.getAxis();
            }
        }

        if (Math.abs(most) < RELEASED) {
            axisArmed = true;
            return true;
        }

        if (!axisArmed || Math.abs(most) < CAPTURE || furthest < 0) return true;

        axisArmed = false;
        adoptDevice(device);
        bind(PadMap.Binding.axis(furthest, most < 0 ? -1 : +1));
        stop();
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
