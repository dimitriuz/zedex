package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Facets;
import dev.ldlab.zedex.library.Filters;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.view.Palette;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One game's facts, by hand.
 *
 * ES-DE scrapes most of a collection well and some of it wrongly, and there
 * has never been a way to correct it from here - the store was something a
 * link wrote and nothing else touched. This is the something else. Reached
 * from the library's own options popup while a game is selected; see {@code
 * OptionsDialog}'s Edit row.
 *
 * <b>Saving makes the game yours.</b> Every row written here carries {@link
 * Meta#USER} and a link leaves it alone from then on - which is the point, and
 * also the cost: ES-DE's later improvements to that game stop arriving.
 * {@link #forget} is the way back out, and says so in as many words rather
 * than calling itself "revert", because there is a gap where the game has
 * nothing at all.
 *
 * A screen rather than a dialog. Eight fields and a soft keyboard do not fit
 * over a landscape phone, which is the shape this app is mostly held in, and a
 * screen survives a rotation for free where a dialog needs to be told how.
 *
 * Addressed by the game's path relative to the content tree, exactly as {@link
 * GameInfoActivity} is and for the same reason: that path is the store's own
 * key, and reading through it rather than carrying a parsed {@link Meta} in an
 * Intent means this screen cannot be editing something the store no longer
 * says.
 */
public final class EditMetadataActivity extends ZedexActivity {

    /** The game's path relative to the content tree - {@link Metadata#relativePath}. */
    public static final String EXTRA_PATH = "dev.ldlab.zedex.extra.GAME_PATH";

    /** The file's own name, shown as the subtitle so it is clear which file
     *  is being edited when the scraped name differs from it - which is most
     *  of the time, and the whole reason somebody is here. */
    public static final String EXTRA_NAME = "dev.ldlab.zedex.extra.GAME_NAME";

    /** How many characters before the suggestions appear. One is too eager on
     *  a list of 277 developers; two narrows it to something readable. */
    private static final int SUGGEST_AFTER = 2;

    private String path;

    /** What the store said when this screen opened - the baseline every write
     *  below is decided against, so an untouched field is left exactly as it
     *  was rather than rewritten with its own value. */
    private Meta original;

    private final Map<Meta.Field, EditText> fields = new EnumMap<>(Meta.Field.class);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // The manifest's label resolves in the phone's language, not this
        // screen's; see Language.
        setTitle(R.string.edit_metadata_title);

        path = getIntent().getStringExtra(EXTRA_PATH);
        String filename = getIntent().getStringExtra(EXTRA_NAME);

        if (path == null || path.isEmpty()) {
            // Nothing to key by, so nothing to edit. Only reachable by an
            // Intent built somewhere other than the row that offers this.
            finish();
            return;
        }

        original = Metadata.forPath(this, path);

        setContentView(buildPage(filename));
        fitToSafeArea();
    }

    private View buildPage(String filename) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Palette.BACKING);

        ScrollView scroller = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pixels(20), pixels(16), pixels(20), pixels(16));

        if (filename != null && !filename.isEmpty()) {
            TextView subtitle = new TextView(this);
            subtitle.setText(filename);
            subtitle.setTextColor(Palette.MUTED);
            subtitle.setTextSize(13);
            subtitle.setPadding(0, 0, 0, pixels(12));
            column.addView(subtitle);
        }

        // Walked from the enum, not written out: a field added to Meta.Field
        // appears here without this screen being told, which is the one thing
        // a hand-written list of eight gets wrong.
        for (Meta.Field field : Meta.Field.values()) column.addView(row(field));

        scroller.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(buttons());

        return page;
    }

    /** A label and the box under it, with the suggestions where they help. */
    private View row(Meta.Field field) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, pixels(12));

        TextView label = new TextView(this);
        label.setText(labelOf(field));
        label.setTextColor(Palette.MUTED);
        label.setTextSize(12);
        row.addView(label);

        EditText box = boxFor(field);
        box.setText(shownValue(field));
        box.setTextColor(Palette.TEXT);
        box.setTextSize(15);
        box.setHint(R.string.edit_metadata_empty);
        box.setContentDescription(getString(labelOf(field)));

        fields.put(field, box);
        row.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return row;
    }

    /**
     * The box itself, which differs by what the field holds.
     *
     * Genre, developer and publisher get the values already in this
     * collection, ranked by how many games carry them - the same lists {@code
     * Facets} builds the filter sheet from, so the suggestions are the ones
     * that would actually match something. Typing past them is still allowed:
     * the whole point of editing is that the scrape got it wrong, and a new
     * genre has to be enterable.
     */
    private EditText boxFor(Meta.Field field) {
        Filters.Field suggesting = suggestionsFor(field);

        if (suggesting != null) {
            AutoCompleteTextView box = new AutoCompleteTextView(this);
            box.setThreshold(SUGGEST_AFTER);
            box.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, valuesOf(suggesting)));
            box.setSingleLine(true);
            return box;
        }

        EditText box = new EditText(this);

        if (field == Meta.Field.DESC) {
            // The one field that runs to paragraphs, and the one somebody is
            // most likely to be here to rewrite.
            box.setSingleLine(false);
            box.setMinLines(3);
            box.setGravity(Gravity.TOP | Gravity.START);
            box.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            return box;
        }

        box.setSingleLine(true);

        if (field == Meta.Field.RELEASED) {
            box.setInputType(InputType.TYPE_CLASS_NUMBER);
        } else if (field == Meta.Field.RATING) {
            box.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        }

        return box;
    }

    /** Which of the filter's own value lists a field can be suggested from,
     *  or null where there is nothing to suggest - a name and a description
     *  are the game's own, and a year is a number. */
    private static Filters.Field suggestionsFor(Meta.Field field) {
        switch (field) {
            case GENRE:     return Filters.Field.GENRE;
            case SUBGENRE:  return Filters.Field.SUBGENRE;
            case DEVELOPER: return Filters.Field.DEVELOPER;
            case PUBLISHER: return Filters.Field.PUBLISHER;
            default:        return null;
        }
    }

    private List<String> valuesOf(Filters.Field field) {
        List<String> names = new ArrayList<>();
        List<Facets.Value> values = Facets.of(Metadata.all(this)).get(field);

        if (values != null) {
            for (Facets.Value value : values) names.add(value.name);
        }
        return names;
    }

    private static int labelOf(Meta.Field field) {
        switch (field) {
            case NAME:      return R.string.edit_metadata_name;
            case DESC:      return R.string.edit_metadata_desc;
            case DEVELOPER: return R.string.edit_metadata_developer;
            case PUBLISHER: return R.string.edit_metadata_publisher;
            case GENRE:     return R.string.edit_metadata_genre;
            case SUBGENRE:  return R.string.edit_metadata_subgenre;
            case RELEASED:  return R.string.edit_metadata_released;
            case PLAYERS:   return R.string.edit_metadata_players;
            default:        return R.string.edit_metadata_rating;
        }
    }

    // --- what is shown against what is stored ------------------------------------

    /**
     * Two of the eight are not shown the way they are stored, and both would
     * be quietly corrupted by writing back what was displayed.
     *
     * {@code released} is an ES-DE stamp - {@code 20201218T000000} - of which
     * only the year is ever displayed anywhere in this app, so a year is what
     * is offered. {@code rating} is a fraction from 0 to 1 shown as stars out
     * of five. See {@link #storedValue} for the other half of this, which is
     * where the care actually is.
     */
    static String shownValue(Meta original, Meta.Field field) {
        if (original == null) return "";

        if (field == Meta.Field.RELEASED) {
            String year = original.year();
            return year == null ? "" : year;
        }
        if (field == Meta.Field.RATING) {
            String stars = original.stars();
            return stars == null ? "" : stars;
        }

        String value = original.get(field);
        return value == null ? "" : value;
    }

    private String shownValue(Meta.Field field) {
        return shownValue(original, field);
    }

    /**
     * What to store for a field, given what is in its box - and, for the two
     * that are converted, <b>null when nothing actually changed</b>.
     *
     * That null is the whole point. A scraped {@code 20201218T000000} shown as
     * "2020" and written straight back becomes {@code 20200101T000000}: the
     * day and the month are gone, and nobody would ever see it happen. A
     * rating of {@code 0.9} shown as "4.5" and written back as {@code 4.5 / 5}
     * is {@code 0.9000001}. {@code Meta.rating}'s own comment already argues
     * this - the value is kept as the string it arrived as "so that a value
     * this app does not understand survives a link and a write unchanged
     * instead of being rounded into something else" - and an editor that
     * rewrote every field on every save would undo exactly that.
     *
     * Static and package private: this is a pure function of the stored
     * game, the field and what was typed, and it is the one piece here
     * whose failure is silent damage to somebody's scraped data - so it is
     * reachable by a test rather than only through eight boxes and a soft
     * keyboard. See {@code EditMetadataFieldsTest}.
     *
     * @return the value to store, or null to leave the field as it is
     */
    static String storedValue(Meta original, Meta.Field field, String typed) {
        String was = shownValue(original, field);
        if (typed.equals(was)) return null;   // untouched, whatever it holds

        if (typed.isEmpty()) return "";       // cleared, which Meta.with reads as null

        if (field == Meta.Field.RELEASED) {
            // Four digits or nothing: anything else is a typo, and a year is
            // the only part of the stamp this app has ever shown.
            if (!typed.matches("\\d{4}")) return null;
            return typed + "0101T000000";
        }

        if (field == Meta.Field.RATING) {
            try {
                float stars = Float.parseFloat(typed);
                if (stars < 0f || stars > 5f) return null;
                return String.format(Locale.ROOT, "%.4f", stars / 5f);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return typed;
    }

    // --- the three buttons -----------------------------------------------------------

    private View buttons() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(pixels(20), pixels(8), pixels(20), pixels(16));

        Button save = new Button(this);
        save.setText(R.string.edit_metadata_save);
        save.setOnClickListener(v -> save());
        row.addView(save, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button cancel = new Button(this);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(v -> finish());
        row.addView(cancel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Only for a game that is already this screen's doing. Offering it for
        // a scraped game would promise to undo something that was never done.
        if (original != null && original.isMine()) {
            Button forget = new Button(this);
            forget.setText(R.string.edit_metadata_forget);
            forget.setOnClickListener(v -> confirmForget());
            row.addView(forget, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        return row;
    }

    /**
     * Writes whatever actually changed, and nothing else.
     *
     * Built up from {@link #original} one field at a time through {@link
     * Meta#with}, so a field nobody touched keeps the exact string it had -
     * see {@link #storedValue}. A game the store has never heard of starts
     * from an empty {@link Meta} on its own path, which is how a game ES-DE
     * never scraped can be described here at all.
     */
    private void save() {
        Meta building = original != null ? original
                : Meta.at(path).source(Meta.USER).build();

        boolean changed = false;

        for (Map.Entry<Meta.Field, EditText> each : fields.entrySet()) {
            String value = storedValue(original, each.getKey(),
                                       each.getValue().getText().toString().trim());
            if (value == null) continue;

            building = building.with(each.getKey(), value);
            changed = true;
        }

        if (!changed) {
            finish();
            return;
        }

        Metadata.put(this, building);
        Toast.makeText(this, R.string.edit_metadata_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    /**
     * Asked before it happens, because it is not an undo.
     *
     * The row goes entirely: the game reads as unscraped until the next link
     * puts ES-DE's own version back. That is a real gap and the wording says
     * so rather than promising a revert.
     */
    private void confirmForget() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(R.string.edit_metadata_forget_confirm)
                .setPositiveButton(R.string.edit_metadata_forget, (dialog, which) -> forget())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void forget() {
        Metadata.forget(this, path);
        Toast.makeText(this, R.string.edit_metadata_forgotten, Toast.LENGTH_SHORT).show();
        setResult(Activity.RESULT_OK);
        finish();
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
