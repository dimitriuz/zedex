package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.catalogue.Catalogue;
import dev.ldlab.zedex.library.catalogue.Catalogues;
import dev.ldlab.zedex.library.catalogue.Imports;
import dev.ldlab.zedex.library.catalogue.Pick;
import dev.ldlab.zedex.library.catalogue.Thumbnails;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.Provider;
import dev.ldlab.zedex.library.scrape.Scrapers;
import dev.ldlab.zedex.library.ui.CatalogueText;
import dev.ldlab.zedex.library.ui.Ripple;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.storage.Tree;
import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.work.Work;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;

/**
 * Everything one catalogue knows about a title, on a screen of its own -
 * every image it offered, its description, and the same facts the pane
 * already shows, at a size the pane cannot afford. {@link
 * dev.ldlab.zedex.library.ui.CataloguePane}'s own Details button opens this;
 * see that class's own comment on why it waits for the full record.
 *
 * <b>Not {@code GameInfoActivity}, on purpose.</b> That screen is built
 * entirely around a game already in the library - a {@code Meta} row keyed
 * by path, a gallery reading local files through {@code Artwork} - and none
 * of that exists yet for something only browsed, not imported. Rather than
 * teach that screen a second, parallel data path through every one of its
 * methods, this is a small screen of its own against {@link Catalogue.Item}
 * directly: remote images fetched through the same {@link Thumbnails} the
 * pane already uses, no manual or music icon (nothing local to point at),
 * and Import in place of Play.
 *
 * <b>The item travels with the intent, not by id.</b> {@link Catalogue.Item}
 * is {@link java.io.Serializable} for exactly this - the pane has already
 * paid for {@link Catalogue#item}, the expensive call, and re-fetching by id
 * here would be a second request for data this screen's own caller is
 * holding already.
 */
public final class CatalogueDetailsActivity extends ZedexActivity {

    private static final String TAG = "Zedex";

    /** The fetched {@link Catalogue.Item}, serialized straight through - see
     *  the class comment. */
    public static final String EXTRA_ITEM = "dev.ldlab.zedex.extra.CATALOGUE_ITEM";

    /** Which catalogue it came from, by {@link Catalogue#name()} - the one
     *  thing about a catalogue that is stable across instances. Resolved
     *  back to a real {@link Catalogue} here rather than carried as one:
     *  a {@code Catalogue} is a live client with an {@code Http} of its own,
     *  not a value to serialize. */
    public static final String EXTRA_CATALOGUE_NAME = "dev.ldlab.zedex.extra.CATALOGUE_NAME";

    /**
     * Set by {@link #openSimilar} and read by {@code CataloguePane}'s own
     * {@code onActivityResult}: this screen cannot descend into a shelf
     * itself - that is the list behind the pane's job, and the stack of
     * shelves lives there - so finishing with the shelf attached is how the
     * choice gets back to somewhere that can.
     */
    public static final String EXTRA_SHELF = "dev.ldlab.zedex.extra.CATALOGUE_SIMILAR_SHELF";

    private Catalogue.Item item;
    private Catalogue catalogue;
    private Http http;

    @Override
    protected int title() {
        return R.string.catalogue_details;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        item = (Catalogue.Item) getIntent().getSerializableExtra(EXTRA_ITEM);
        String catalogueName = getIntent().getStringExtra(EXTRA_CATALOGUE_NAME);

        if (item == null) {
            // Nothing to show - the caller built this intent wrong, which is
            // a programming error rather than something a person did.
            finish();
            return;
        }

        catalogue = catalogueNamed(catalogueName);
        http = new Http.Real(this);

        setContentView(build());
        fitToSafeArea();
    }

    /** The catalogue this item came from, by name - see {@link
     *  #EXTRA_CATALOGUE_NAME}'s own comment for why this is resolved rather
     *  than carried. Null leaves Import and Similar both quietly absent
     *  rather than guessing at a service to ask. */
    private Catalogue catalogueNamed(String name) {
        if (name == null) return null;

        for (Catalogue candidate : Catalogues.all(this)) {
            if (candidate.name().equals(name)) return candidate;
        }
        return null;
    }

    // --- building the screen ---------------------------------------------------------

    private View build() {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Palette.BACKING);

        List<String> images = item.images().isEmpty() && item.pictureUrl() != null
                ? Collections.singletonList(item.pictureUrl())
                : item.images();

        if (!images.isEmpty()) column.addView(gallery(images, density));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setTextColor(Palette.TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setText(item.title());
        words.addView(title, wrap());

        TextView facts = new TextView(this);
        facts.setTextColor(Palette.MUTED);
        facts.setTextSize(15);
        facts.setPadding(0, Math.round(6 * density), 0, 0);
        facts.setText(CatalogueText.factsLine(item));
        words.addView(facts, wrap());

        if (item.description() != null && !item.description().isEmpty()) {
            TextView description = new TextView(this);
            description.setTextColor(Palette.TEXT);
            description.setTextSize(15);
            description.setPadding(0, Math.round(16 * density), 0, 0);
            description.setText(item.description());
            words.addView(description, wrap());
        }

        words.addView(actionRow(density));

        ScrollView scroller = new ScrollView(this);
        scroller.addView(words, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        column.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return column;
    }

    /**
     * Every image in a row, each fetched the same way the pane's own cover
     * is - {@link Thumbnails#get} first, {@link Thumbnails#load} behind it -
     * so a picture the pane or the list already drew costs no request here.
     */
    private View gallery(List<String> urls, float density) {
        HorizontalScrollView scroller = new HorizontalScrollView(this);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        int box = Math.round(220 * density);
        int margin = Math.round(4 * density);

        for (String url : urls) {
            FrameLayout frame = new FrameLayout(this);
            frame.setBackgroundColor(0x14ffffff);
            frame.setForeground(Ripple.make(density));
            frame.setOnClickListener(v -> openFullscreen(url));

            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            frame.addView(image, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            // A play badge is the one thing a thumbnail cannot show for
            // itself - the gallery draws a still frame either way, gif or
            // not, so this is the only signal that tapping it plays rather
            // than just opens. See CatalogueImageActivity.isGif for the same
            // question asked the same way when the tap lands.
            if (CatalogueImageActivity.isGif(url)) {
                ImageView badge = new ImageView(this);
                badge.setImageResource(R.drawable.ic_play);
                badge.setBackgroundColor(0x99000000);
                badge.setPadding(Math.round(6 * density), Math.round(6 * density),
                                 Math.round(6 * density), Math.round(6 * density));
                int badgeSize = Math.round(32 * density);
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                        badgeSize, badgeSize, Gravity.CENTER);
                frame.addView(badge, badgeParams);
            }

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(box, box);
            params.leftMargin = params.rightMargin = margin;
            row.addView(frame, params);

            showPicture(image, url);
        }

        scroller.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, Math.round(220 * density)));
        return scroller;
    }

    private void showPicture(ImageView into, String url) {
        Bitmap have = Thumbnails.get(url);
        if (have != null) {
            into.setImageBitmap(have);
            return;
        }

        Thumbnails.load(this, http, url, (fetched, picture) -> {
            if (picture == null || !fetched.equals(url)) return;
            into.setImageBitmap(picture);
        });
    }

    // --- the action row ---------------------------------------------------------------

    private View actionRow(float density) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(16 * density), 0, 0);

        int gap = Math.round(8 * density);

        Button importButton = new Button(this);
        importButton.setText(R.string.catalogue_import);
        importButton.setOnClickListener(v -> importTheGame());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        importParams.rightMargin = gap;
        row.addView(importButton, importParams);

        Catalogue.Shelf similar = catalogue == null ? null
                : catalogue.similarTo(item, getString(R.string.catalogue_similar));
        if (similar != null) {
            Button similarButton = new Button(this);
            similarButton.setText(R.string.catalogue_similar);
            similarButton.setOnClickListener(v -> openSimilar(similar));
            row.addView(similarButton, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        if (item.videoLink() != null) {
            ImageButton videoButton = new ImageButton(this);
            videoButton.setImageResource(R.drawable.ic_video);
            videoButton.setColorFilter(Palette.MUTED);
            videoButton.setBackground(Ripple.make(density));
            videoButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
            videoButton.setContentDescription(getString(R.string.library_video));
            videoButton.setOnClickListener(v -> openLink(item.videoLink()));
            row.addView(videoButton, new LinearLayout.LayoutParams(
                    Math.round(48 * density), Math.round(48 * density)));
        }

        return row;
    }

    /**
     * The same import a tap on the pane's own button starts - {@code
     * Imports.game}/{@code otherFile}, the same {@code Provider} match by
     * name - except for the one thing this screen does not have a folder
     * picker for.
     *
     * <b>No write-grant flow here, deliberately.</b> {@code CataloguePane}
     * asks for the folder the moment an import needs one it does not have,
     * and this screen is always reached by tapping Details on that very
     * pane - so the grant is either already there, or the pane's own button
     * is one tap away and already knows how to ask. Building a second folder
     * picker here would be a second thing to keep in step with the first
     * rather than a feature this screen is missing.
     */
    private void importTheGame() {
        Catalogue.Download game = Pick.forGame(item);
        Catalogue.Download file = game != null ? game : Pick.otherFile(item);

        if (file == null) {
            Toast.makeText(this, R.string.catalogue_nothing_to_get, Toast.LENGTH_LONG).show();
            return;
        }

        Uri tree = Storage.contentFolder(this);
        if (!Tree.canWrite(this, tree)) {
            Toast.makeText(this, R.string.catalogue_details_import, Toast.LENGTH_LONG).show();
            return;
        }

        boolean document = game == null;
        android.content.Context app = getApplicationContext();
        Http requestHttp = http;
        Catalogue.Item asked = item;
        Provider provider = providerFor();

        Work.alone("catalogue-details-import", () -> {
            Imports.Result result;

            try {
                result = document ? Imports.document(app, requestHttp, asked, file)
                                  : Imports.game(app, requestHttp, asked, file);

                if (result.failure == null && !result.alreadyThere && !document) {
                    Imports.describe(app, provider, requestHttp, result, asked);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "the import of " + asked.title() + " went wrong", e);
                result = null;
            }

            Imports.Result answered = result;
            Work.onMain(() -> importFinished(answered));
        });
    }

    private void importFinished(Imports.Result result) {
        boolean landed = result != null && result.failure == null && result.documentUri != null;

        if (landed) {
            String name = result.displayName != null ? result.displayName : item.title();
            Toast.makeText(this, getString(result.alreadyThere
                    ? R.string.catalogue_already : R.string.catalogue_imported, name),
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.catalogue_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** See {@link #providerFor} in {@code CataloguePane} - the same match by
     *  name, and the same reason: an id is only certain against the service
     *  that issued it. */
    private Provider providerFor() {
        for (Provider provider : Scrapers.all(this)) {
            if (provider.name().equals(catalogue == null ? "" : catalogue.name())) {
                return provider;
            }
        }
        return null;
    }

    /** Finishes with the shelf attached - see {@link #EXTRA_SHELF}'s own
     *  comment for why this screen hands it back rather than opening it. */
    private void openSimilar(Catalogue.Shelf shelf) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_SHELF, shelf));
        finish();
    }

    /** A tap on any gallery tile - the picture, full size, on {@link
     *  CatalogueImageActivity}. */
    private void openFullscreen(String url) {
        startActivity(new Intent(this, CatalogueImageActivity.class)
                .putExtra(CatalogueImageActivity.EXTRA_URL, url));
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "nothing can open " + url, e);
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                             LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
