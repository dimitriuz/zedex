package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.work.Work;
import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.screen.MediaViewerActivity;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything known about one game - the gallery, the name, the facts, the
 * description, the action row - built from nothing but its path. <b>The one
 * implementation of these details</b>: {@code GameInfoActivity} had a second
 * copy of every line of it until that screen was reduced to this view in an
 * activity, and the two had drifted apart in both directions - the panel had
 * no extras rows, the screen no autoplay and no measured cover.
 *
 * A view of its own rather than a screen because a second screen wants it
 * too, and a {@link
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

    private static final String TAG = "Zedex";


    /** Roughly what the artwork is decoded at - a whole panel's worth, and
     *  bigger than the 360dp the details screen decoded at while it had a
     *  gallery of its own, because {@link #applyCoverSize} lets the box grow
     *  to most of the lane's own height now, which is more picture than a
     *  phone screen ever gave it. */
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

    /**
     * How long a picture is left up before the video takes over, and the token
     * every one of those waits is posted under so a new selection cancels the
     * one before it.
     *
     * The same three seconds the pane uses, and deliberately the same: the two
     * are the same feature on two screens, and a panel that moved to the video
     * at a different moment from the pane would look like a bug in whichever
     * one you were not watching.
     */
    private static final int VIDEO_DELAY_MS = 3000;

    private final Object videoToken = new Object();

    /** Whether that wait is scheduled at all - the {@code libraryVideoAutoplay}
     *  preference, handed here by whoever shows this view. True until told
     *  otherwise, which is what the pane's own default is. */
    private boolean autoplay = true;

    /** Set the moment somebody swipes the gallery themselves, so the wait
     *  never drags them off a page they chose - the pane's {@code userSwiped},
     *  for the same reason. */
    private boolean userSwiped;

    private final Gallery gallery;

    /** The rows under the description: authors, price, series, compilations,
     *  contents. A column of its own because it is rebuilt whenever the store
     *  answers, and the panel went without it until this view became the one
     *  implementation of these details rather than the lesser of two. */
    private final LinearLayout extras;

    /** The row at the foot of the words lane. Rebuilt by {@link #rebuildRow}
     *  whenever a host adds to it, because the order is fixed - leading,
     *  primary, manual, music, trailing - and a host may add in any order. */
    private final LinearLayout actionRow;

    private final List<View> leadingActions = new ArrayList<>();
    private final List<View> trailingActions = new ArrayList<>();

    /** The one text button, or null where the host wants none - which is the
     *  details screen opened from a running machine, where there is nothing
     *  to start. */
    private Button primaryButton;

    /** The manual and the music are this view's own, not a host's: it is this
     *  view that asks Artwork for them off the UI thread and reveals each
     *  only when the answer arrives. A host says whether it wants the manual
     *  offered at all ({@link #setOffersManual}) and nothing more. */
    private final ImageButton rowManual;
    private final ImageButton rowMusic;

    private final TextView title;
    private final TextView filename;
    private final TextView facts;
    private final TextView description;

    /** The path currently showing, or null - kept so {@link #release} and a
     *  stale async answer both know whether they still apply; see {@link
     *  #showEntry}'s own token. */
    private String path;
    private int token;

    /**
     * Whether this view offers the manual itself.
     *
     * <b>It does, unless something else does.</b> On the emulator's own panel
     * the quick bar is on screen beside these details and carries a manual
     * icon - see {@code EmulatorActivity.applyBarMode} - so a second button
     * floating in the corner of the artwork would be the same action twice,
     * which is what the corner button was asked to stop being. The library's
     * panel lends no controls and so has no bar at all: there the corner is
     * the only place a manual can be offered from, and it stays.
     *
     * True by default, because a caller that says nothing is a caller with no
     * bar to rely on.
     */
    private boolean offersManual = true;

    /** See {@link #offersManual}. Called before the first {@code showEntry},
     *  since that is what decides whether the button is ever revealed. */
    public void setOffersManual(boolean offers) {
        offersManual = offers;
        if (!offers) rowManual.setVisibility(View.GONE);
    }

    /**
     * Told when this view hands something <em>foreign</em> to its own display
     * - a manual, opened in whatever PDF viewer the phone has.
     *
     * A {@link android.app.Presentation} draws above every activity window on
     * its display, so anything landing there is invisible underneath until the
     * panel steps aside. Both panels notice the app's own screens through the
     * application's lifecycle callbacks and need no telling; a foreign
     * activity reaches none of those, which is the whole of why this exists.
     *
     * Only foreign ones, and that is not a detail: coming back from one is
     * {@code topFocusReturned}, the host activity being the top-resumed one
     * again, and a handheld that gives each display its own focus never takes
     * that away from a host sitting on the other screen - so the signal never
     * comes and the panel stays down. Announcing our own full-screen viewer
     * through here latched exactly that, and Back out of a picture looked like
     * it had closed the panel for good. See {@link #openViewer}.
     *
     * Set only by {@code SecondScreen}. {@link dev.ldlab.zedex.screen.GameInfoActivity}
     * shows this view too, but it is an ordinary activity with no panel to
     * step aside and nothing to tell - so it never calls the setter, and a
     * null check where this is read covers that caller the same as every
     * other listener in this app.
     */
    private Runnable onForeignScreen;

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

        // A tap opens the picture full screen, the same as the pane's own
        // gallery and the game info screen's. Without this the tap reached
        // Gallery.notifyTap, found no listener and returned - so artwork on
        // the panel was the one artwork in the app that could not be opened,
        // which reads as a screen that ignores you rather than as a listener
        // nobody had set.
        gallery.setOnPageTapped(this::openViewer);

        // A page somebody chose is a page they keep: without this the wait
        // would drag them to the video three seconds after they swiped away
        // from it.
        //
        // setOnUserSwipe and not setOnPageChanged, which is what this was and
        // why autoplay still did nothing: a page change fires when the gallery
        // settles after a load as well as when a finger moves it, so the very
        // first one marked the selection as swiped and cancelled the wait
        // before it could run. The pane has always used this callback; the two
        // now ask the same question.
        gallery.setOnUserSwipe(() -> userSwiped = true);
        coverBox.addView(gallery, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

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

        // Bigger than the pane's own: that is a strip beside a grid, read
        // close up in the hand, where this fills a panel or a screen and is
        // meant to be read at a slight distance - and to match the picture
        // beside it, now that the picture is no longer a thumbnail either.
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(VERTICAL);
        words.setPadding(pixels(24), pixels(24), pixels(24), pixels(24));

        // The filename until the store answers with a scraped name, exactly
        // as a row in the library does - this is never blank while it waits.
        title = new TextView(context);
        title.setTextColor(Palette.TEXT);
        title.setTextSize(24);
        words.addView(title, wrap());

        filename = new TextView(context);
        filename.setTextColor(Palette.MUTED);
        filename.setTextSize(14);
        filename.setPadding(0, pixels(6), 0, 0);
        filename.setVisibility(View.GONE);
        words.addView(filename, wrap());

        facts = new TextView(context);
        facts.setTextColor(Palette.MUTED);
        facts.setTextSize(16);
        facts.setPadding(0, pixels(14), 0, 0);
        facts.setVisibility(View.GONE);
        words.addView(facts, wrap());

        description = new TextView(context);
        description.setTextColor(Palette.TEXT);
        description.setTextSize(18);
        description.setLineSpacing(pixels(5), 1f);
        // Room under the last line, not only over the first. This column
        // scrolls, and on a fixed panel nothing else says so - text cut off
        // flush against the screen's own edge reads as broken rather than as
        // "there is more below", which is exactly how it looked at 1280x720.
        description.setPadding(0, pixels(20), 0, pixels(24));
        description.setVisibility(View.GONE);
        words.addView(description, wrap());

        // Under the description, because these are the long tail: a quarter
        // of entries have a price, six per cent a series, and a row that is
        // usually absent belongs below the one thing somebody came to read.
        extras = new LinearLayout(context);
        extras.setOrientation(VERTICAL);
        extras.setPadding(0, pixels(20), 0, 0);
        words.addView(extras, wrap());

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

        // The words lane: the scroller, weighted to take whatever room is
        // left, and Play pinned below it at its own natural height - outside
        // the ScrollView on purpose, the same way LibraryActivity's own pane
        // keeps its action row below a weighted spacer rather than inside
        // whatever scrolls. A description long enough to scroll must never
        // carry the one button that starts the game out of reach with it.
        LinearLayout wordsLane = new LinearLayout(context);
        wordsLane.setOrientation(VERTICAL);
        wordsLane.addView(scroller, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        // The row at the foot of the words lane, outside the scroller - the
        // rule every host of this view shares: a description long enough to
        // scroll must never carry the button that starts the game out of
        // reach with it.
        //
        // The shape is DetailPane's, which had it first: one text button
        // taking whatever the icons leave, then fixed 48dp icons, in the
        // order action then manual then music.
        actionRow = new LinearLayout(context);
        actionRow.setOrientation(HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        rowManual = icon(R.drawable.ic_manual, R.string.library_manual);
        rowMusic = icon(R.drawable.ic_music, R.string.music_title);

        rebuildRow();

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowParams.leftMargin = rowParams.rightMargin = pixels(24);
        rowParams.topMargin = pixels(16);
        rowParams.bottomMargin = pixels(24);
        wordsLane.addView(actionRow, rowParams);

        if (landscape) {
            // Words left, media right - the arrangement GameInfoActivity had,
            // and the one this view took when that screen was folded into it.
            // Not a parameter: a knob for which side the picture sits on is
            // the kind that multiplies, and one arrangement is the point of
            // having one implementation.
            LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, LANDSCAPE_WORDS_WEIGHT);
            wordsParams.rightMargin = pixels(LANE_GAP_DP);
            addView(wordsLane, wordsParams);
            addView(media, new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, LANDSCAPE_MEDIA_WEIGHT));
        } else {
            LinearLayout.LayoutParams mediaParams = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, 0, 2f);
            mediaParams.bottomMargin = pixels(LANE_GAP_DP);
            addView(media, mediaParams);
            addView(wordsLane, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 3f));
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

    /** One 48dp icon button, built the way DetailPane builds its own. */
    private ImageButton icon(int iconRes, int descriptionRes) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(iconRes);
        button.setColorFilter(Palette.MUTED);
        button.setBackground(Ripple.make(getResources().getDisplayMetrics().density));
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setContentDescription(getContext().getString(descriptionRes));
        button.setVisibility(View.GONE);
        return button;
    }

    /**
     * The row, in its one order: leading, primary, manual, music, trailing.
     *
     * Rebuilt rather than inserted into, because a host adds in whatever
     * order suits it and the order on screen is not that one - the details
     * screen opened from the machine adds its back icon first and its menu
     * and close icons after, and they must land on opposite sides of two
     * buttons it never mentions.
     */
    private void rebuildRow() {
        actionRow.removeAllViews();

        for (View action : leadingActions) actionRow.addView(action, iconParams());
        if (primaryButton != null) {
            actionRow.addView(primaryButton, new LinearLayout.LayoutParams(
                    0, LayoutParams.WRAP_CONTENT, 1f));
        }
        actionRow.addView(rowManual, iconParams());
        actionRow.addView(rowMusic, iconParams());
        for (View action : trailingActions) actionRow.addView(action, iconParams());
    }

    private LinearLayout.LayoutParams iconParams() {
        return new LinearLayout.LayoutParams(pixels(48), pixels(48));
    }

    /**
     * The one text button, and what it does - Play, everywhere it appears.
     *
     * Never called means no text button at all, which is the details screen
     * opened from a running machine: the game is already going, so there is
     * nothing to start.
     */
    public void setPrimaryAction(int labelRes, Runnable action) {
        primaryButton = new Button(getContext());
        primaryButton.setText(labelRes);
        primaryButton.setOnClickListener(v -> action.run());
        rebuildRow();
        updatePlayVisibility();
    }

    /** An icon before the manual. */
    public void addLeadingAction(int iconRes, int descriptionRes, Runnable action) {
        leadingActions.add(visibleAction(iconRes, descriptionRes, action));
        rebuildRow();
    }

    /** An icon after the music. */
    public void addTrailingAction(int iconRes, int descriptionRes, Runnable action) {
        trailingActions.add(visibleAction(iconRes, descriptionRes, action));
        rebuildRow();
    }

    /** A host's icon, which unlike the manual and the music is shown at once:
     *  the host knows whether its own action exists and this view does not. */
    private ImageButton visibleAction(int iconRes, int descriptionRes, Runnable action) {
        ImageButton button = icon(iconRes, descriptionRes);
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    /** {@link #onForeignScreen}'s own setter - public because the panel
     *  that shows this view is in a different layer; see CLAUDE.md, "a
     *  member another layer needs has to be public". */
    public void setOnForeignScreen(Runnable listener) {
        this.onForeignScreen = listener;
    }

    /**
     * Fills every view from {@code relativePath}'s own store entry and
     * artwork - the title at once from {@code name}, everything scraped
     * once the store and the gallery answer off the UI thread. Safe to call
     * again for a different game at any time - {@link #token} tells an answer
     * that arrives after this game was already left that it no longer
     * applies.
     */
    public void showEntry(String relativePath, String name) {
        int mine = ++token;
        path = relativePath;

        title.setText(name);
        filename.setVisibility(View.GONE);
        facts.setVisibility(View.GONE);
        description.setVisibility(View.GONE);
        rowMusic.setVisibility(View.GONE);
        // This view is reused across selections and the manual answer is
        // asynchronous (see the "pane-manual" work below), so without this a
        // game with no manual would keep showing the last game's manual
        // button - still visible, and still wired to the last game's own
        // Uri, so tapping it would open the wrong game's manual. The
        // listener is dropped too, not just the visibility, so a stale click
        // target can never fire even if something makes the button visible
        // again before the answer for this game arrives.
        rowManual.setVisibility(View.GONE);
        rowManual.setOnClickListener(null);
        // Synchronously, unlike the removeAllViews() in show(Meta): this view is
        // reused across selections and the metadata answer is asynchronous, so
        // leaving the last game's rows up until the store replies would show
        // game A's extras under game B's title for however long that answer
        // takes to arrive - the leak the other four resets above already close.
        extras.removeAllViews();
        updatePlayVisibility();

        gallery.load(relativePath);

        userSwiped = false;
        handler.removeCallbacksAndMessages(videoToken);

        // Off, the video is still there to swipe to and still plays once
        // swiped to; only this automatic move to it is what the setting turns
        // off, so a wait with nothing to do is not even scheduled.
        if (autoplay) {
            int forThis = token;
            handler.postDelayed(() -> advanceToVideo(forThis), videoToken, VIDEO_DELAY_MS);
        }

        Context app = getContext().getApplicationContext();

        Work.run("pane-info", () -> {
            // forPath answers from memory and never parses, so a view opened
            // before the store has been read - straight from ES-DE, most
            // often - would otherwise show a game about which nothing is
            // known. This is already a thread of its own, which is the only
            // place waiting is allowed.
            Metadata.ensureLoaded(app);

            Meta meta = Metadata.forPath(app, relativePath);
            handler.post(() -> {
                if (mine != token) return; // this game was left before the store answered
                show(meta);
            });
        });

        Work.run("pane-music", () -> {
            java.io.File tune;
            try {
                tune = Artwork.music(app, relativePath);
            } catch (Exception e) {
                tune = null;
            }

            boolean any = tune != null;
            handler.post(() -> {
                if (mine != token || !any) return;

                rowMusic.setVisibility(View.VISIBLE);
                rowMusic.setOnClickListener(v -> openMusic(relativePath));
            });
        });

        Work.run("pane-manual", () -> {
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
                if (!offersManual) return;   // the quick bar beside this has it

                // In the row with Play rather than in the corner of the
                // artwork: one place for a game's own actions, which is what
                // the pane has always had and what the corner button was
                // taken off the panel to become.
                rowManual.setVisibility(View.VISIBLE);
                // getDisplay() is this view's own panel, whichever activity
                // put it there - see the class comment. Null before the
                // first layout pass, which Manuals.open reads as "no panel
                // to ask for", the ordinary path - and onForeignScreen with
                // it, since nothing was put on a display that was never
                // asked for.
                // This panel's own display, which is safe again now that the
                // viewer is one of ours - see Panels.openManual. Nothing
                // foreign is announced, because nothing foreign is opened:
                // PdfActivity and InstructionsActivity are both reported
                // through the lifecycle callbacks the panel already watches.
                rowManual.setOnClickListener(
                        v -> Manuals.open(getContext(), result, getDisplay()));
            });
        });
    }

    /**
     * Hands the game's music to the machine, which is the only thing that can
     * play it.
     *
     * The emulator screen rather than one of this pane's own: a tune is the
     * Spectrum running the game's driver, so it happens where the Spectrum
     * is. Whatever was loaded there is put aside and given back - see {@code
     * media.Music}.
     */
    /**
     * Moves to the video, unless the selection changed or somebody swiped.
     *
     * The panel had none of this: the wait, the setting behind it and the
     * swipe that cancels it were all the pane's, so a video on the second
     * screen sat on its first picture until somebody swiped to it by hand.
     * The same three seconds and the same two guards, because it is meant to
     * be the same behaviour on the other screen rather than a second one.
     */
    private void advanceToVideo(int forThis) {
        if (forThis != token || userSwiped) return;

        int index = gallery.videoIndex();
        if (index >= 0) gallery.showPage(index);
    }

    /** Whether {@link #showEntry} schedules that wait at all - the {@code
     *  libraryVideoAutoplay} preference, re-read and handed here by whoever
     *  shows this view, exactly as {@code LibraryActivity} hands it to the
     *  pane on every resume. */
    public void setAutoplay(boolean on) {
        autoplay = on;
    }

    /**
     * The picture that was tapped, full screen, on this view's own display.
     *
     * The display matters, and the manual button next door explains why -
     * but this view is now shown on two different kinds of screen, not one:
     * {@code SecondScreen}'s panel, on its own secondary display, and {@link
     * dev.ldlab.zedex.screen.GameInfoActivity}, an ordinary activity that is
     * always on the main one. Asking {@code getDisplay()} rather than
     * assuming either is what keeps this right for both - on the panel it
     * answers the secondary display, on the activity it answers the main
     * one, which is where an unaddressed launch would land anyway, so the
     * activity case costs nothing and changes nothing. {@code getDisplay()}
     * is null before the first layout pass, which is read here exactly as
     * {@code Manuals.open} reads it: no display to ask for, so ask for none.
     *
     * Unlike the manual, nothing has to be told this happened. {@code
     * MediaViewerActivity} is one of the app's own, so both panels see it
     * through the application's lifecycle callbacks and step aside by
     * themselves - the whole reason {@link #onForeignScreen} exists is that a
     * foreign activity never reaches those, and telling it about one of ours
     * is how the panel came to stay down for good; see that field.
     */
    private void openViewer(int index) {
        if (path == null) return;

        Intent intent = new Intent(getContext(), MediaViewerActivity.class)
                .putExtra(MediaViewerActivity.EXTRA_PATH, path)
                .putExtra(MediaViewerActivity.EXTRA_INDEX, index)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Display display = getDisplay();

        if (display != null) {
            try {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(display.getDisplayId());

                getContext().startActivity(intent, options.toBundle());

                // Not the manual's signal, only the video half of it. This
                // viewer is one of the app's own, so the panel already steps
                // aside for it through the lifecycle callbacks - and telling
                // it a *foreign* screen is up sets a latch that only the host
                // activity's onTopResumedActivityChanged clears. On a
                // handheld that gives each display its own focus, the host
                // never stops being the top-resumed activity on its own
                // screen, so that callback does not come: the panel went away
                // when the picture opened and never came back, which reads as
                // Back out of a picture having closed the panel for good.
                release();
                return;
            } catch (RuntimeException e) {
                // The display refused it or has gone. The main screen is
                // better than nothing at all - the same call Manuals.open
                // makes at the same point.
                Log.w(TAG, "cannot open the viewer on display "
                           + display.getDisplayId(), e);
            }
        }

        getContext().startActivity(intent);
    }

    private void openMusic(String relativePath) {
        Intent intent = new Intent(getContext(), EmulatorActivity.class)
                .putExtra(EmulatorActivity.EXTRA_MUSIC, relativePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        getContext().startActivity(intent);
    }

    /** Nothing to show - clears every field and empties the gallery. */
    public void clear() {
        token++; // an answer already in flight for the last game no longer applies
        path = null;

        title.setText("");
        filename.setVisibility(View.GONE);
        facts.setVisibility(View.GONE);
        description.setVisibility(View.GONE);
        rowManual.setVisibility(View.GONE);
        rowManual.setOnClickListener(null);
        rowMusic.setVisibility(View.GONE);
        // See the matching reset in showEntry(): this view is reused across
        // selections, so nothing selected must mean nothing shown, not the
        // last game's rows left standing under an empty title.
        extras.removeAllViews();
        updatePlayVisibility();
        gallery.clear();

        // Nothing selected has no video to move to, and a wait left running
        // would move an empty gallery three seconds after it emptied.
        handler.removeCallbacksAndMessages(videoToken);
    }

    /**
     * The primary button appears when there is a game for it to act on, and
     * only where a host asked for one at all.
     *
     * The old condition also asked whether {@code onPlay} had been set, which
     * was right when the panel was the only host: it sets the listener
     * separately from building the button. The details screen sets the action
     * *in* {@link #setPrimaryAction} and never touches {@code onPlay}, so
     * asking about it here would hide the Play button on the one screen whose
     * whole row leads with it.
     */
    private void updatePlayVisibility() {
        if (primaryButton == null) return;
        primaryButton.setVisibility(path != null ? View.VISIBLE : View.GONE);
    }

    /**
     * Stops the gallery's own video - one of the times a video must not be
     * left running: the selection moving on, the panel coming down, the host
     * activity pausing, and now a fourth, the panel flipping away from this
     * view to the controls. Safe whether or not anything selected has a
     * video, or anything selected at all.
     */
    public void release() {
        handler.removeCallbacksAndMessages(videoToken);
        gallery.release();
    }

    private void show(Meta meta) {
        if (meta == null) return;

        if (meta.name != null && !meta.name.isEmpty()) {
            filename.setText(title.getText());
            filename.setVisibility(View.VISIBLE);
            title.setText(meta.name);
        }

        String line = factsLine(getContext(), meta);
        if (line != null) {
            facts.setText(line);
            facts.setVisibility(View.VISIBLE);
        }

        if (meta.desc != null && !meta.desc.isEmpty()) {
            description.setText(meta.desc.trim());
            description.setVisibility(View.VISIBLE);
        }

        extras.removeAllViews();
        extra(R.string.info_authors, String.join(", ", meta.authors));
        extra(R.string.info_price, meta.price);
        extra(R.string.info_series, GameInfoText.seriesLine(meta));
        extra(R.string.info_compilations, GameInfoText.titlesOf(meta.compilations));
        extra(R.string.info_contents, GameInfoText.titlesOf(meta.contents));
    }

    /**
     * A labelled fact, or nothing at all.
     *
     * Nothing at all is the common case - see {@link #extras} - and an empty
     * row with a heading over it would claim the database was asked and had
     * no answer, when mostly it was never asked.
     *
     * A point larger than the screen's own version of this throughout, for
     * the same reason every other size here is: this is read at a slight
     * distance on a fixed panel, not close up in the hand.
     */
    private void extra(int label, String value) {
        if (value == null || value.trim().isEmpty()) return;

        TextView heading = new TextView(getContext());
        heading.setText(label);
        heading.setTextColor(Palette.MUTED);
        heading.setTextSize(13);
        heading.setPadding(0, pixels(12), 0, 0);
        extras.addView(heading, wrap());

        TextView text = new TextView(getContext());
        text.setText(value.trim());
        text.setTextColor(Palette.TEXT);
        text.setTextSize(16);
        text.setLineSpacing(pixels(3), 1f);
        extras.addView(text, wrap());
    }

    /**
     * Developer, publisher, year, genre and players, joined the same way the
     * pane's own line is and skipping whatever is not known - which, in a
     * collection scraped by ES-DE, is usually most of it.
     */
    private static String factsLine(android.content.Context context, Meta meta) {
        StringBuilder text = new StringBuilder();

        append(text, meta.developer);
        append(text, meta.publisher);
        append(text, meta.year());
        append(text, meta.genre);
        append(text, meta.players);

        // See LibraryActivity.outOfFive for why this is written rather than
        // drawn as stars.
        String stars = meta.stars();
        if (stars != null) append(text, stars + "/5");

        // Last, after everything the catalogue knows: this one is the reader's
        // own history rather than a fact about the game.
        append(text, Facts.playedLabel(context, meta));

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
