package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.view.SafeArea;
import dev.ldlab.zedex.storage.States;
import dev.ldlab.zedex.storage.Storage;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The saved states, as a screen of their own.
 *
 * A state is a picture, and pictures want room: in the ☰ sheet they were a
 * column of stamps three hundred dp wide, and the thing that tells one save
 * from another - the screen it was taken of - was too small to read. A screen
 * of its own gives them a grid.
 *
 * An activity rather than a page for the same reason the settings screen is one,
 * and opened the same way: {@link Panels#openOwnScreen} puts it on whichever
 * display the controls are on, so on a handheld it lands on the panel the request
 * came from, and its own dialogs land there with it.
 *
 * It talks to the machine directly. Saving and loading are queued to the
 * emulation thread through {@link FuseNative} like every other command, so
 * nothing has to be handed back to the emulator's activity first.
 */
public final class StatesActivity extends Activity {

    /** True to save over what is here, false to load one of them. */
    public static final String EXTRA_SAVING = "saving";

    private static final int BACKING = 0xff14151a;
    private static final int TEXT = 0xffededf2;

    /** A cell wide enough for a readable 4:3 picture, in dp. */
    private static final int CELL_DP = 190;

    private SharedPreferences preferences;
    private boolean saving;

    private GridView grid;
    private TextView empty;
    private final List<States.Saved> states = new ArrayList<>();

    /** Opens the list, saving or loading. */
    public static Intent intent(Context context, boolean saving) {
        return new Intent(context, StatesActivity.class)
                .putExtra(EXTRA_SAVING, saving);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        preferences = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        saving = getIntent().getBooleanExtra(EXTRA_SAVING, false);

        setTitle(saving ? R.string.menu_save_state : R.string.menu_load_state);
        setContentView(page());

        // Nothing of ours under the status bar or the camera; see SafeArea.
        SafeArea.fit(findViewById(android.R.id.content));

        // The platform's own bar carries the title and the way back, so there
        // is no heading of ours underneath it saying the same thing again.
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        refresh();
    }

    private View page() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BACKING);

        empty = new TextView(this);
        empty.setText(R.string.state_none);
        empty.setTextColor(0xff8b8b99);
        empty.setTextSize(15);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(pixels(16), pixels(32), pixels(16), pixels(16));
        empty.setVisibility(View.GONE);
        column.addView(empty);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(pixels(CELL_DP));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(pixels(10), 0, pixels(10), pixels(10));
        grid.setClipToPadding(false);
        grid.setAdapter(new Cards());

        column.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        return column;
    }

    /** Reads the folder again and redraws; called after anything changes it. */
    private void refresh() {
        states.clear();
        states.addAll(States.all(this));

        // Saving always has something to offer - a new one - so the empty note
        // is only ever for loading.
        empty.setVisibility(!saving && states.isEmpty() ? View.VISIBLE : View.GONE);
        ((Cards) grid.getAdapter()).notifyDataSetChanged();
    }

    private int position(int index) {
        return saving ? index - 1 : index;
    }

    /**
     * The grid: one card per state, with a card for a new one first while
     * saving. The new one is drawn from the same layout with its picture blank,
     * so the grid stays one kind of thing.
     */
    private final class Cards extends BaseAdapter {

        @Override
        public int getCount() {
            return states.size() + (saving ? 1 : 0);
        }

        @Override
        public Object getItem(int index) {
            return index;
        }

        @Override
        public long getItemId(int index) {
            return index;
        }

        @Override
        public View getView(int index, View reuse, ViewGroup parent) {
            View card = reuse != null ? reuse
                    : getLayoutInflater().inflate(R.layout.state_card, parent, false);

            ImageView thumbnail = card.findViewById(R.id.thumbnail);
            TextView title = card.findViewById(R.id.title);
            TextView subtitle = card.findViewById(R.id.subtitle);
            ImageButton rename = card.findViewById(R.id.rename);
            ImageButton delete = card.findViewById(R.id.delete);

            // The picture is as wide as the cell and 4:3, which the grid cannot
            // work out for itself: its cells are as tall as what is in them.
            int width = grid.getColumnWidth() - pixels(12);
            thumbnail.getLayoutParams().height = Math.max(pixels(60), width * 3 / 4);

            if (saving && index == 0) {
                thumbnail.setImageDrawable(getDrawable(R.drawable.ic_plus));
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                title.setText(R.string.state_add);
                subtitle.setText(R.string.state_add_summary);

                rename.setVisibility(View.GONE);
                delete.setVisibility(View.GONE);

                card.setOnClickListener(v -> askName());
                return card;
            }

            States.Saved state = states.get(position(index));
            DateFormat when = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT);

            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setImageBitmap(
                    States.thumbnail(States.thumbnailFor(StatesActivity.this,
                                                         state.name)));
            title.setText(state.name);
            subtitle.setText(getString(R.string.state_details,
                    when.format(new Date(state.snapshot.lastModified())),
                    state.format()));

            // Named after the state they belong to, so a screen reader - and a
            // test - can tell one card's buttons from another's.
            rename.setVisibility(View.VISIBLE);
            rename.setContentDescription(
                    getString(R.string.state_rename_action, state.name));
            rename.setOnClickListener(v -> askNewName(state));

            delete.setVisibility(View.VISIBLE);
            delete.setContentDescription(
                    getString(R.string.state_delete_action, state.name));
            delete.setOnClickListener(v -> confirmDelete(state));

            card.setOnClickListener(v -> {
                if (saving) confirmOverwrite(state); else load(state);
            });

            return card;
        }
    }

    // --- what the cards do --------------------------------------------------

    private void askName() {
        EditText input = field(States.suggest(this, preferences));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.state_name)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = Storage.sanitise(input.getText().toString());
                    if (name.isEmpty()) name = "Snapshot";
                    save(name);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmOverwrite(States.Saved state) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(getString(R.string.state_overwrite, state.name))
                .setPositiveButton(R.string.state_overwrite_confirm,
                        (dialog, which) -> save(state.name))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askNewName(States.Saved state) {
        EditText input = field(state.name);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.state_rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        rename(state, Storage.sanitise(input.getText().toString())))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDelete(States.Saved state) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(getString(R.string.state_delete, state.name))
                .setPositiveButton(R.string.state_delete_confirm, (dialog, which) -> {
                    States.delete(this, state);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * A name already taken is refused rather than written over: a card's own tap
     * is how a state is overwritten, and that asks first.
     */
    private void rename(States.Saved state, String name) {
        if (name.isEmpty() || name.equals(state.name)) return;

        if (States.find(this, name) != null) {
            toast(getString(R.string.state_name_taken, name));
            return;
        }

        if (!States.rename(this, state, name)) {
            toast(getString(R.string.state_rename_failed));
            return;
        }

        refresh();
    }

    /**
     * Saving and loading both finish the screen: the answer is on the machine's
     * side of the app, and standing here afterwards means one more tap to see
     * what happened.
     */
    private void save(String name) {
        if (!States.save(this, preferences, name)) {
            toast(getString(R.string.state_failed));
            return;
        }

        preferences.edit().putString(States.KEY_MEDIA_NAME, name).apply();
        toast(getString(R.string.state_saved, name));
        finish();
    }

    private void load(States.Saved state) {
        States.load(state);
        preferences.edit().putString(States.KEY_MEDIA_NAME, state.name).apply();
        toast(getString(R.string.state_loaded, state.name));
        finish();
    }

    /** The bar's back arrow: the same as Back, since nothing here is a step. */
    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // --- odds and ends -------------------------------------------------------

    private EditText field(String initial) {
        EditText input = new EditText(this);

        input.setSingleLine();
        input.setText(initial);
        input.setSelection(input.getText().length());

        return input;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
