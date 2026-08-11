package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.scrape.Blend;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of several pictures to keep, for one game.
 *
 * Every source is asked for every picture before any of this is shown - see
 * {@code Blend.Media.OFFER_ALTERNATIVES} - so by the time anybody is asked
 * anything the downloads are finished and nothing is waiting on the answer.
 * That is why this is a dialog and not a blocking call: there is no thread
 * parked behind it.
 *
 * <b>Save with nothing touched changes nothing.</b> Whatever is already on
 * disk starts selected, so the safe answer is the default one and a person who
 * does not understand the question cannot lose a picture by pressing the
 * obvious button. A folder that had nothing preselects the first source that
 * offered one, since there is nothing to lose there.
 */
final class ArtworkChoice {

    /** What the sheet came to: the staged files to install, at most one per
     *  folder. */
    interface Chosen {
        void take(List<Blend.Staged> chosen);
    }

    private ArtworkChoice() {
    }

    /** How wide a tile is drawn, in dp - two and a bit fit across a phone,
     *  which is what makes it obvious the row scrolls sideways. */
    private static final int TILE_DP = 128;

    static void show(Activity activity, String gameName, List<Blend.Staged> staged,
                     Chosen onSave) {
        Map<String, List<Blend.Staged>> byFolder = new LinkedHashMap<>();

        for (Blend.Staged one : staged) {
            List<Blend.Staged> forFolder = byFolder.get(one.folder);
            if (forFolder == null) {
                forFolder = new ArrayList<>();
                byFolder.put(one.folder, forFolder);
            }
            forFolder.add(one);
        }

        // One selection per folder; null means "keep what is already there".
        Map<String, Blend.Staged> selected = new LinkedHashMap<>();

        int padding = dp(activity, 16);

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(padding, padding, padding, padding);

        for (Map.Entry<String, List<Blend.Staged>> entry : byFolder.entrySet()) {
            String folder = entry.getKey();
            File existing = firstExisting(entry.getValue());

            // Nothing there: the highest-priority offer wins by default, since
            // there is nothing to lose. Something there: it stays.
            selected.put(folder, existing == null ? entry.getValue().get(0) : null);

            rows.addView(label(activity, nameOf(activity, folder)));
            rows.addView(strip(activity, folder, entry.getValue(), existing, selected));
        }

        ScrollView scrolling = new ScrollView(activity);
        scrolling.addView(rows);

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(activity.getString(R.string.artwork_new_title, gameName))
                .setView(scrolling)
                .setPositiveButton(R.string.artwork_new_save, (dialog, which) -> {
                    List<Blend.Staged> chosen = new ArrayList<>();
                    for (Blend.Staged one : selected.values()) {
                        if (one != null) chosen.add(one);
                    }
                    onSave.take(chosen);
                })
                .setNegativeButton(android.R.string.cancel,
                                   (dialog, which) -> onSave.take(new ArrayList<>()))
                .show();
    }

    /** One folder's choices, side by side. */
    private static View strip(Activity activity, String folder, List<Blend.Staged> offers,
                              File existing, Map<String, Blend.Staged> selected) {
        LinearLayout tiles = new LinearLayout(activity);
        tiles.setOrientation(LinearLayout.HORIZONTAL);

        List<View> all = new ArrayList<>();

        if (existing != null) {
            View yours = tile(activity, existing,
                              activity.getString(R.string.artwork_new_yours),
                              () -> selected.put(folder, null), all);
            all.add(yours);
            tiles.addView(container(activity, yours,
                                    activity.getString(R.string.artwork_new_yours)));
        }

        for (Blend.Staged offer : offers) {
            View tile = tile(activity, offer.file, offer.source,
                             () -> selected.put(folder, offer), all);
            all.add(tile);
            tiles.addView(container(activity, tile, offer.source));
        }

        mark(all, existing == null ? all.get(0) : all.get(all.size() - offers.size()));

        HorizontalScrollView scrolling = new HorizontalScrollView(activity);
        scrolling.addView(tiles);
        return scrolling;
    }

    /**
     * One picture, which is the thing that takes the tap.
     *
     * <b>The description and the click are on the same view</b>, deliberately:
     * a test finds the tile by its description and then taps what it found, so
     * a description on the wrapper and a listener on the picture is a tap that
     * lands on nothing and reads as a sheet that ignores you.
     *
     * The description is set once, at build time, and never changed again -
     * anything on screen whose contentDescription changes continuously makes
     * the accessibility tree never settle, and takes the whole UI Automator
     * suite down with it.
     */
    private static View tile(Activity activity, File picture, String from,
                             Runnable choose, List<View> all) {
        ImageView image = new ImageView(activity);
        image.setLayoutParams(new LinearLayout.LayoutParams(dp(activity, TILE_DP),
                                                            dp(activity, TILE_DP)));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(BitmapFactory.decodeFile(picture.getAbsolutePath()));
        image.setContentDescription(from);

        image.setOnClickListener(view -> {
            choose.run();
            mark(all, view);
        });
        return image;
    }

    /** The picture with its source written under it. */
    private static View container(Activity activity, View tile, String from) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4));

        TextView who = new TextView(activity);
        who.setText(from);
        who.setGravity(Gravity.CENTER_HORIZONTAL);

        column.addView(tile);
        column.addView(who);
        return column;
    }

    /** Which tile is chosen, shown by the only means that needs no drawable. */
    private static void mark(List<View> all, View chosen) {
        for (View one : all) one.setAlpha(one == chosen ? 1f : 0.4f);
    }

    private static TextView label(Activity activity, String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setPadding(0, dp(activity, 12), 0, 0);
        return label;
    }

    /**
     * A media folder's name in the reader's own language.
     *
     * The two arrays are parallel and already exist for the media setting -
     * {@code scrape_media_folders} is ES-DE's own names, never translated, and
     * {@code scrape_media_entries} is what to call them on a screen. Falling
     * back to the folder name is right for a folder the arrays do not know:
     * an untranslated word beats a blank line.
     */
    private static String nameOf(Activity activity, String folder) {
        String[] folders = activity.getResources().getStringArray(R.array.scrape_media_folders);
        String[] names = activity.getResources().getStringArray(R.array.scrape_media_entries);

        for (int at = 0; at < folders.length && at < names.length; at++) {
            if (folders[at].equals(folder)) return names[at];
        }
        return folder;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** Whatever is already on disk for this folder, or null. Every offer for
     *  one folder answers the same file, so the first is enough. */
    private static File firstExisting(List<Blend.Staged> offers) {
        for (Blend.Staged one : offers) {
            if (one.contested) return one.existing;
        }
        return null;
    }
}
