package dev.ldlab.zedex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Binds a profile's eight controls to keys.
 *
 * Tap a control, tap a key: the keyboard at the bottom is the same view the
 * emulator uses, put into {@link SpectrumKeyboardView.Picker} mode so a tap
 * names a key rather than pressing one. That is why this is a screen of its own
 * rather than eight entries in the settings — choosing SYMBOL SHIFT by pointing
 * at SYMBOL SHIFT needs the picture of the keyboard, and the picture needs room.
 *
 * Everything applies as it is done, like the rest of the app's settings: there
 * is no save button and leaving is not cancelling. What is being edited is
 * whichever profile is current, since that is the one the controls are using and
 * the one whose changes can be felt straight away.
 */
public final class ProfileActivity extends Activity
        implements SpectrumKeyboardView.Picker {

    /** Bright enough to read as chosen against the dark rows. */
    private static final int SELECTED = 0xff00b0c8;
    private static final int ROW = 0x22ffffff;
    private static final int TEXT = 0xffededf2;
    private static final int DIM = 0xff9a9aa5;

    private SharedPreferences preferences;
    private final Button[] slots = new Button[ControlProfiles.SLOTS];

    private int selected = FuseNative.JOYSTICK_UP;
    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        setContentView(build());
        showKeys();
    }

    /**
     * Name, then the eight controls in two columns, then the keyboard.
     *
     * Built in code rather than from a layout file, as everything else in this
     * app is: there are no dependencies to inflate with and the shape is simple
     * enough that a file would only put it somewhere else.
     */
    private View build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xff14151a);
        page.setPadding(pixels(12), pixels(12), pixels(12), 0);

        page.addView(name());

        hint = new TextView(this);
        hint.setTextColor(DIM);
        hint.setTextSize(13);
        hint.setPadding(pixels(4), pixels(8), pixels(4), pixels(4));
        page.addView(hint);

        page.addView(grid());

        // The keyboard keeps its own 541x201 aspect whatever box it is given,
        // so a box with the spare height in it would only centre the picture in
        // the middle of nowhere. The spare height goes above instead, which puts
        // the keyboard at the foot of the screen where one belongs.
        page.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        SpectrumKeyboardView keyboard = new SpectrumKeyboardView(this);
        keyboard.setPicker(this);

        LinearLayout.LayoutParams keys = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        keys.topMargin = pixels(8);
        page.addView(keyboard, keys);

        return page;
    }

    /** The profile's name, editable in place; renaming is not a dialog. */
    private EditText name() {
        EditText field = new EditText(this);

        field.setText(profile().name);
        field.setTextColor(TEXT);
        field.setTextSize(20);
        field.setSingleLine(true);
        field.setBackgroundColor(Color.TRANSPARENT);
        field.setPadding(pixels(4), 0, pixels(4), pixels(4));

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable text) {
                String renamed = text.toString().trim();
                if (renamed.isEmpty()) return;

                List<ControlProfiles.Profile> all = ControlProfiles.all(preferences);
                int index = ControlProfiles.currentIndex(preferences);

                all.set(index, all.get(index).withName(renamed));
                ControlProfiles.store(preferences, all, index);
            }

            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }
        });

        return field;
    }

    /**
     * Two columns of four: the pad's five and fire down the left, the three
     * buttons down the right, so the shape on screen matches the shape in the
     * hand.
     */
    private View grid() {
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);

        int[][] order = {
            { FuseNative.JOYSTICK_UP, FuseNative.JOYSTICK_DOWN,
              FuseNative.JOYSTICK_LEFT, FuseNative.JOYSTICK_RIGHT },
            { FuseNative.JOYSTICK_FIRE, ControlProfiles.BUTTON_1,
              ControlProfiles.BUTTON_2, ControlProfiles.BUTTON_3 },
        };

        for (int[] column : order) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);

            for (int slot : column) {
                Button row = new Button(this);

                row.setAllCaps(false);
                row.setTextColor(TEXT);
                row.setBackgroundColor(ROW);
                row.setOnClickListener(view -> select(slot));

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMargins(pixels(3), pixels(3), pixels(3), pixels(3));

                box.addView(row, params);
                slots[slot] = row;
            }

            columns.addView(box, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        return columns;
    }

    private void select(int slot) {
        selected = slot;
        showKeys();
    }

    /** Every row's key, and which row the next tap on the keyboard will set. */
    private void showKeys() {
        ControlProfiles.Profile profile = profile();

        for (int slot = 0; slot < slots.length; slot++) {
            Button row = slots[slot];
            if (row == null) continue;

            row.setText(ControlProfiles.slotName(slot) + "\n"
                        + ControlProfiles.name(profile.keys[slot]));
            row.setTextColor(slot == selected ? SELECTED : TEXT);
        }

        hint.setText(getString(R.string.profile_hint,
                               ControlProfiles.slotName(selected)));
    }

    /** A key was tapped on the keyboard: it belongs to the selected control. */
    @Override
    public void picked(int keycode) {
        List<ControlProfiles.Profile> all = ControlProfiles.all(preferences);
        int index = ControlProfiles.currentIndex(preferences);

        all.set(index, all.get(index).withKey(selected, keycode));
        ControlProfiles.store(preferences, all, index);

        showKeys();
    }

    private ControlProfiles.Profile profile() {
        return ControlProfiles.current(preferences);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(R.string.profile_delete);
        return true;
    }

    /**
     * Deleting is the only thing here that cannot be undone by doing it again,
     * so it asks - and it is refused outright when it would leave the controls
     * with no profile at all.
     */
    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        List<ControlProfiles.Profile> all = ControlProfiles.all(preferences);

        if (all.size() < 2) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(R.string.profile_last)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.profile_delete_ask, profile().name))
                .setPositiveButton(R.string.profile_delete, (dialog, which) -> {
                    int index = ControlProfiles.currentIndex(preferences);

                    all.remove(index);
                    ControlProfiles.store(preferences, all, Math.max(0, index - 1));
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();

        return true;
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Opens the editor on whichever profile is current. */
    static void open(Context context) {
        context.startActivity(new Intent(context, ProfileActivity.class));
    }
}
