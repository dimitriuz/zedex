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

    private static final String TAG = "Zedex";


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
    private final ImageButton manualButton;

    /** Beside it, and only for a game a tune was fetched for. */
    private final ImageButton musicButton;
    private final Button playButton;
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
     * Set once by {@code SecondScreen}, the only place this view is ever
     * shown; a null check where it is read covers a caller that never
     * bothers, the same as every other listener in this app.
     */
    private Runnable onForeignScreen;

    /** Whether playing a game means anything from here at all - only ever
     *  set by {@link dev.ldlab.zedex.screen.LibraryPanel}, since only the
     *  library's own panel shows a game that has not started yet; see this
     *  class's own comment on {@link #setOnPlay} and CLAUDE.md's "the host
     *  decides". Null on the emulator's panel, which never calls it, and
     *  {@link #playButton} stays hidden for exactly that reason - see
     *  {@link #updatePlayVisibility}. */
    private Runnable onPlay;

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
        manualButton = new ImageButton(context);
        manualButton.setImageResource(R.drawable.ic_manual);
        manualButton.setBackground(disc());
        manualButton.setPadding(pixels(9), pixels(9), pixels(9), pixels(9));
        manualButton.setColorFilter(0xffffffff);
        manualButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        manualButton.setContentDescription(context.getString(R.string.library_manual));
        manualButton.setVisibility(View.GONE);

        // 48dp: the same button is already 48 on GameInfoActivity, and one
        // control being easier to hit on one screen than another is the sort
        // of difference nobody chooses on purpose. It floats in the corner of
        // the cover box with nothing beside it, so the eight extra dp cost
        // nothing.
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                pixels(48), pixels(48), Gravity.TOP | Gravity.END);
        buttonParams.topMargin = buttonParams.rightMargin = pixels(12);
        coverBox.addView(manualButton, buttonParams);

        musicButton = new ImageButton(context);
        musicButton.setImageResource(R.drawable.ic_music);
        musicButton.setBackground(disc());
        musicButton.setPadding(pixels(9), pixels(9), pixels(9), pixels(9));
        musicButton.setColorFilter(0xffffffff);
        musicButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        musicButton.setContentDescription(context.getString(R.string.music_title));
        musicButton.setVisibility(View.GONE);

        // Under the manual rather than beside it: the box is as wide as a
        // cover and two buttons in a row across the top of one crowd the
        // artwork somebody came to look at.
        FrameLayout.LayoutParams musicParams = new FrameLayout.LayoutParams(
                pixels(48), pixels(48), Gravity.TOP | Gravity.END);
        musicParams.topMargin = pixels(12) + pixels(48) + pixels(8);
        musicParams.rightMargin = pixels(12);
        coverBox.addView(musicButton, musicParams);

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

        playButton = new Button(context);
        playButton.setText(R.string.library_play);
        playButton.setVisibility(View.GONE);
        playButton.setOnClickListener(v -> {
            if (onPlay != null) onPlay.run();
        });
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        playParams.leftMargin = playParams.rightMargin = pixels(24);
        playParams.topMargin = pixels(16);
        playParams.bottomMargin = pixels(24);
        wordsLane.addView(playButton, playParams);

        if (landscape) {
            LinearLayout.LayoutParams mediaParams = new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, LANDSCAPE_MEDIA_WEIGHT);
            mediaParams.rightMargin = pixels(LANE_GAP_DP);
            addView(media, mediaParams);
            addView(wordsLane, new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, LANDSCAPE_WORDS_WEIGHT));
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

    /** {@link #onForeignScreen}'s own setter - public because the panel
     *  that shows this view is in a different layer; see CLAUDE.md, "a
     *  member another layer needs has to be public". */
    public void setOnForeignScreen(Runnable listener) {
        this.onForeignScreen = listener;
    }

    /**
     * {@link #onPlay}'s own setter - public for the same reason {@link
     * #setOnForeignScreen} is, and never called at all by {@code Panels},
     * whose panel shows a game already running; only {@code LibraryPanel}
     * installs an action here, which is what keeps Play off the emulator's
     * own panel without this view guessing from anything it can see for
     * itself. Applied at once through {@link #updatePlayVisibility}, since a
     * game may already be showing by the time this is set - see {@link
     * dev.ldlab.zedex.screen.LibraryPanel#apply}.
     */
    public void setOnPlay(Runnable listener) {
        this.onPlay = listener;
        updatePlayVisibility();
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
        musicButton.setVisibility(View.GONE);
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
            // See GameInfoActivity.load: forPath never parses, so a caller
            // that cannot show anything useful without the store says so and
            // waits, on its own thread.
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

                musicButton.setVisibility(View.VISIBLE);
                musicButton.setOnClickListener(v -> openMusic(relativePath));
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

                manualButton.setVisibility(View.VISIBLE);
                // getDisplay() is this view's own panel, whichever activity
                // put it there - see the class comment. Null before the
                // first layout pass, which Manuals.open reads as "no panel
                // to ask for", the ordinary path - and onForeignScreen with
                // it, since nothing was put on a display that was never
                // asked for.
                manualButton.setOnClickListener(
                        v -> Manuals.open(getContext(), result, getDisplay(), onForeignScreen));
            });
        });
    }

    /**
     * The backing every one of these buttons sits on.
     *
     * One each rather than one shared: a {@code Drawable} handed to two views
     * shares its state with both, and a background that is only ever drawn
     * still costs nothing to make twice.
     */
    private GradientDrawable disc() {
        GradientDrawable disc = new GradientDrawable();

        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(0xd0000000);
        disc.setStroke(pixels(1), 0x66ffffff);

        return disc;
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
     * The display matters, and the manual button next door explains why: this
     * view is only ever shown on a panel, and a screen opened without saying
     * which display it wants lands on the main one - behind the machine, on
     * the screen nobody was looking at. {@code getDisplay()} is null before
     * the first layout pass, which is read here exactly as {@code
     * Manuals.open} reads it: no panel to ask for, so ask for nothing.
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
        manualButton.setVisibility(View.GONE);
        updatePlayVisibility();
        gallery.clear();

        // Nothing selected has no video to move to, and a wait left running
        // would move an empty gallery three seconds after it emptied.
        handler.removeCallbacksAndMessages(videoToken);
    }

    /** Play shows exactly when there is both a game to play ({@link #path})
     *  and somewhere for playing it to mean anything ({@link #onPlay}) - see
     *  that field's own comment. Called from every place either can change:
     *  {@link #showEntry}, {@link #clear} and {@link #setOnPlay} itself. */
    private void updatePlayVisibility() {
        playButton.setVisibility(onPlay != null && path != null ? View.VISIBLE : View.GONE);
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
    }

    /**
     * Developer, publisher, year, genre and players, joined the same way
     * {@code GameInfoActivity}'s own line is and skipping whatever is not
     * known - which, in a collection scraped by ES-DE, is usually most of it.
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
