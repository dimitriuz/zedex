package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;

import android.content.Context;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.graphics.drawable.GradientDrawable;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Everything known about one game - the gallery, the name, the facts, the
 * description - built from nothing but its path, the same page {@code
 * GameInfoActivity} builds in its own {@code onCreate}. Pulled out into a
 * view of its own because a second screen wants it too, and a {@link
 * android.app.Presentation} belongs to whichever activity opened it: what
 * {@link dev.ldlab.zedex.screen.SecondScreen} shows cannot be borrowed from
 * another activity's layout the way it borrows the emulator's own controls,
 * since the library and the emulator are different activities and the app
 * moves between them. This is built from state instead - a path in, a page
 * out - so either activity's own panel can hold one and feed it whatever is
 * current without caring who built it last.
 *
 * Landscape or portrait is read from this view's own {@link
 * Configuration}, not the activity's: built with a {@link
 * android.app.Presentation}'s own context, that already answers for the
 * panel it is actually going to sit on, which is exactly what a page split
 * between the artwork and the words needs to know.
 */
public final class GameInfoView extends LinearLayout {

    private static final int TEXT = 0xffededf2;
    private static final int MUTED = 0xff9a9aa5;

    /** Roughly what the artwork is decoded at - a whole panel's worth, the
     *  same reasoning {@code GameInfoActivity}'s own target follows, bigger
     *  than that screen's own 360dp because {@link #applyCoverSize} lets the
     *  box grow to most of the panel's own height now, which is more
     *  picture than a phone screen ever gave it. */
    private static final int ARTWORK_TARGET_DP = 480;

    /** The cover box's own shape - box art's, 3:4, the same ratio {@link
     *  AspectFrame} gives the grid's tiles - fitted inside whatever the lane
     *  actually measures by {@link #applyCoverSize}, rather than stretched
     *  to fill it the way a plain {@code MATCH_PARENT} once did: that let a
     *  lane far taller than a cover needs stretch the picture's own box into
     *  a strip twice the height, and left the dots a good 200px below
     *  whatever of the picture actually showed inside it. */
    private static final int COVER_WIDTH = 3;
    private static final int COVER_HEIGHT = 4;

    /** The picture's own share of the row against the words, in landscape -
     *  bigger than the words get, because the picture is what this screen is
     *  for showing; see the class comment. Portrait keeps the ratio it
     *  always had, the picture stacked above a taller scrolling word column
     *  rather than beside a narrower one, so it is not named here. */
    private static final float LANDSCAPE_MEDIA_WEIGHT = 3f;
    private static final float LANDSCAPE_WORDS_WEIGHT = 2f;

    /** The gap between the picture's own lane and the words - {@link
     *  dev.ldlab.zedex.screen.LibraryActivity}'s pane uses the same 16dp
     *  between its own cover box and its text column. */
    private static final int LANE_GAP_DP = 16;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Gallery gallery;
    private final ImageButton manualButton;
    private final TextView title;
    private final TextView filename;
    private final TextView facts;
    private final TextView description;

    /** The path currently showing, or null - kept so {@link #release} and a
     *  stale async answer both know whether they still apply; see {@link
     *  #showEntry}'s own token. */
    private String path;
    private int token;

    public GameInfoView(Context context) {
        super(context);

        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        setOrientation(landscape ? HORIZONTAL : VERTICAL);
        setBackgroundColor(0xff0f0e13);

        FrameLayout media = new FrameLayout(context);

        // The picture's own box, fitted inside the lane by applyCoverSize
        // rather than left to stretch across it - see COVER_WIDTH's own
        // comment. Centred so whatever the lane has left over once the box
        // takes what its shape allows is spread evenly round it, which
        // reads as the picture's own gutter rather than as dead space.
        FrameLayout coverBox = new FrameLayout(context);
        media.addView(coverBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        gallery = new Gallery(context);
        gallery.setPictureTargetPx(pixels(ARTWORK_TARGET_DP));
        coverBox.addView(gallery, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Floats over the cover box's own top corner, the same place {@code
        // GameInfoActivity} puts it - there is no toolbar here for it to sit
        // in either. On the box itself now, not the wider lane it sits in:
        // pinned to the lane before this existed, it floated over whatever
        // blank letterbox the old full-height stretch left above the
        // picture, reading as stuck between the picture and the words
        // rather than belonging to either.
        //
        // On a filled disc, though, rather than the plain 50% black square
        // that came with it there. The icon is a thin white outline and what
        // sits behind it is whatever art the game happens to have: box art is
        // as often pale as dark - Ms. Pac-Man's is nearly white - so a scrim
        // that only darkens is a coin toss. Opaque enough to win against any
        // picture, with a
        // faint ring so the disc still reads as an edge against black art,
        // and round so it reads as a button rather than as part of the
        // picture it sits on.
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(0xd0000000);
        disc.setStroke(pixels(1), 0x66ffffff);

        manualButton = new ImageButton(context);
        manualButton.setImageResource(R.drawable.ic_manual);
        manualButton.setBackground(disc);
        manualButton.setPadding(pixels(9), pixels(9), pixels(9), pixels(9));
        manualButton.setColorFilter(0xffffffff);
        manualButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        manualButton.setContentDescription(context.getString(R.string.library_manual));
        manualButton.setVisibility(View.GONE);

        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                pixels(40), pixels(40), Gravity.TOP | Gravity.END);
        buttonParams.topMargin = buttonParams.rightMargin = pixels(12);
        coverBox.addView(manualButton, buttonParams);

        // Neither of the lane's own dimensions exists yet at construction
        // time - portrait's lane is bounded by width and open on height,
        // landscape's the other way about - so the box is sized only once
        // media has actually been through a layout pass and both are real.
        media.addOnLayoutChangeListener((v, left, top, right, bottom, ol, ot, or2, ob) -> {
            int laneWidth = right - left;
            int laneHeight = bottom - top;
            if (laneWidth > 0 && laneHeight > 0) {
                // Posted, not applied here: this fires during media's own
                // layout, and sizing a child from inside that asks for a
                // pass this one does not deliver - the same trap Gallery's
                // own pageWidth listener avoids, and for the same reason.
                media.post(() -> applyCoverSize(coverBox, laneWidth, laneHeight));
            }
        });

        // Bigger than the pane's own, and than GameInfoActivity's - both are
        // read close up in the hand, where this is a fixed panel meant to be
        // read at a slight distance and sized to match the picture beside it
        // now that the picture is no longer a thumbnail either.
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(VERTICAL);
        words.setPadding(pixels(24), pixels(24), pixels(24), pixels(24));

        // The filename until the store answers with a scraped name, exactly
        // as GameInfoActivity's own title does - this is never blank while
        // it waits.
        title = new TextView(context);
        title.setTextColor(TEXT);
        title.setTextSize(24);
        words.addView(title, wrap());

        filename = new TextView(context);
        filename.setTextColor(MUTED);
        filename.setTextSize(14);
        filename.setPadding(0, pixels(6), 0, 0);
        filename.setVisibility(View.GONE);
        words.addView(filename, wrap());

        facts = new TextView(context);
        facts.setTextColor(MUTED);
        facts.setTextSize(16);
        facts.setPadding(0, pixels(14), 0, 0);
        facts.setVisibility(View.GONE);
        words.addView(facts, wrap());

        description = new TextView(context);
        description.setTextColor(TEXT);
        description.setTextSize(18);
        description.setLineSpacing(pixels(5), 1f);
        // Room under the last line, not only over the first. This column
        // scrolls, and on a fixed panel nothing else says so - text cut off
        // flush against the screen's own edge reads as broken rather than as
        // "there is more below", which is exactly how it looked at 1280x720.
        description.setPadding(0, pixels(20), 0, pixels(24));
        description.setVisibility(View.GONE);
        words.addView(description, wrap());

        ScrollView scroller = new ScrollView(context);
        scroller.addView(words);

        // A fading edge, which is the only thing here that says this column
        // scrolls at all. A phone screen has a scrollbar, a thumb and the
        // habit of dragging; a panel somebody glances down at has none of
        // them, and a three-paragraph description otherwise ends mid-line
        // hard against the panel's own edge and reads as clipped rather than
        // as continued. The fade only appears when there is actually more
        // below, which is exactly when the question is asked.
        scroller.setVerticalFadingEdgeEnabled(true);
        scroller.setFadingEdgeLength(pixels(28));

        if (landscape) {
            LinearLayout.LayoutParams mediaParams = new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, LANDSCAPE_MEDIA_WEIGHT);
            mediaParams.rightMargin = pixels(LANE_GAP_DP);
            addView(media, mediaParams);
            addView(scroller, new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, LANDSCAPE_WORDS_WEIGHT));
        } else {
            LinearLayout.LayoutParams mediaParams = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, 0, 2f);
            mediaParams.bottomMargin = pixels(LANE_GAP_DP);
            addView(media, mediaParams);
            addView(scroller, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 3f));
        }
    }

    /**
     * Sizes {@code box} to whatever fits {@link #COVER_WIDTH}:{@link
     * #COVER_HEIGHT} inside a lane measuring {@code laneWidth} by {@code
     * laneHeight} - height first, since that is the one of the two a
     * landscape panel actually runs short of. A lane wide enough for more
     * than its own height allows is left with room beside the box rather
     * than above and below it, which reads as the picture's own gutter
     * rather than as the dead space a full-height stretch used to leave.
     */
    private void applyCoverSize(FrameLayout box, int laneWidth, int laneHeight) {
        int wantedHeight = laneWidth * COVER_HEIGHT / COVER_WIDTH;
        int height = Math.min(laneHeight, wantedHeight);
        int width = height * COVER_WIDTH / COVER_HEIGHT;

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) box.getLayoutParams();
        if (params.width == width && params.height == height) return; // already this size

        params.width = width;
        params.height = height;
        box.setLayoutParams(params);
    }

    /**
     * Fills every view from {@code relativePath}'s own store entry and
     * artwork - the title at once from {@code name}, everything scraped
     * once the store and the gallery answer off the UI thread; mirrors
     * {@code GameInfoActivity#onCreate}. Safe to call again for a different
     * game at any time - {@link #token} tells a answer that arrives after
     * this game was already left that it no longer applies.
     */
    public void showEntry(String relativePath, String name) {
        int mine = ++token;
        path = relativePath;

        title.setText(name);
        filename.setVisibility(View.GONE);
        facts.setVisibility(View.GONE);
        description.setVisibility(View.GONE);
        manualButton.setVisibility(View.GONE);

        gallery.load(relativePath);

        Context app = getContext().getApplicationContext();

        new Thread(() -> {
            Meta meta = Metadata.forPath(app, relativePath);
            handler.post(() -> {
                if (mine != token) return; // this game was left before the store answered
                show(meta);
            });
        }).start();

        new Thread(() -> {
            Uri manual;
            try {
                manual = Artwork.manual(app, relativePath);
            } catch (Exception e) {
                manual = null;
            }

            Uri result = manual;
            handler.post(() -> {
                if (mine != token) return;
                if (result == null) return;

                manualButton.setVisibility(View.VISIBLE);
                // getDisplay() is this view's own panel, whichever activity
                // put it there - see the class comment. Null before the
                // first layout pass, which Manuals.open reads as "no panel
                // to ask for", the ordinary path.
                manualButton.setOnClickListener(
                        v -> Manuals.open(getContext(), result, getDisplay()));
            });
        }).start();
    }

    /** Nothing to show - clears every field and empties the gallery. */
    public void clear() {
        token++; // an answer already in flight for the last game no longer applies
        path = null;

        title.setText("");
        filename.setVisibility(View.GONE);
        facts.setVisibility(View.GONE);
        description.setVisibility(View.GONE);
        manualButton.setVisibility(View.GONE);
        gallery.clear();
    }

    /**
     * Stops the gallery's own video - one of the times a video must not be
     * left running: the selection moving on, the panel coming down, the host
     * activity pausing, and now a fourth, the panel flipping away from this
     * view to the controls. Safe whether or not anything selected has a
     * video, or anything selected at all.
     */
    public void release() {
        gallery.release();
    }

    private void show(Meta meta) {
        if (meta == null) return;

        if (meta.name != null && !meta.name.isEmpty()) {
            filename.setText(title.getText());
            filename.setVisibility(View.VISIBLE);
            title.setText(meta.name);
        }

        String line = factsLine(meta);
        if (line != null) {
            facts.setText(line);
            facts.setVisibility(View.VISIBLE);
        }

        if (meta.desc != null && !meta.desc.isEmpty()) {
            description.setText(meta.desc.trim());
            description.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Developer, publisher, year, genre and players, joined the same way
     * {@code GameInfoActivity}'s own line is and skipping whatever is not
     * known - which, in a collection scraped by ES-DE, is usually most of it.
     */
    private static String factsLine(Meta meta) {
        StringBuilder text = new StringBuilder();

        append(text, meta.developer);
        append(text, meta.publisher);
        append(text, meta.year());
        append(text, meta.genre);
        append(text, meta.players);

        return text.length() > 0 ? text.toString() : null;
    }

    private static void append(StringBuilder text, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (text.length() > 0) text.append(" · ");
        text.append(value.trim());
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
