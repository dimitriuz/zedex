package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.Types;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.view.Palette;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Metadata and artwork for whatever is selected - always there, whether or
 * not anything is: a side pane in landscape, a panel across the bottom in
 * portrait, both a third of the window against the other two thirds the list
 * takes. See docs/LIBRARY.md's second pull request, "reserved... whether or
 * not anything is selected" being the whole point of shipping the container
 * before anything filled it, and "linking to ES-DE" for what fills it now.
 *
 * A class of its own rather than a set of the library screen's fields, which
 * is what it was: twenty-eight of them and eight methods, spread down a
 * three-thousand-line activity between the nav stack, the listing and the
 * gamepad cursor, none of which it touches. The test for whether that was a
 * real seam is CLAUDE.md's own - a {@code Host} wider than about four methods
 * means the seam is in the wrong place - and this one needs three. It is far
 * larger than its interface, which is the shape to want.
 *
 * What it does <em>not</em> know is as much the point as what it does. It
 * does not know there is a second screen it can be stood down in favour of;
 * {@link #standDown} is told, and asks nothing about why. It does not know
 * how to start an activity, which is why the magnifier, the pictures and the
 * action button all go out through {@link Host} rather than reaching for
 * {@code GameInfoActivity} and {@code MediaViewerActivity} directly - those
 * live a layer up, and a widget reaching up into the screen holding it is the
 * dependency this extraction exists to not have.
 *
 * The one piece of real difficulty in here is that three things happen
 * asynchronously and the selection moves faster than any of them answers:
 * the scraped manual's own SAF lookup, the gallery's pictures and video, and
 * the three-second wait that carries a resting cursor to the video. {@link
 * #token} is what tells an answer that arrived too late to act - see its own
 * comment, and {@link #advanceToVideo}'s.
 */
public final class DetailPane extends FrameLayout {

    /**
     * What the pane cannot do for itself: three things that all mean
     * "somewhere else in the app, about this row".
     *
     * Deliberately three and not more. The pane holds twenty views and
     * resolves three asynchronous things about the row it is showing, and
     * none of that needs the screen around it - only these do, because all
     * three are an activity starting, and a widget does not start
     * activities. If this ever wants a fourth, that is the signal the seam
     * has moved and is worth stopping for; see CLAUDE.md, and the fifteen-
     * method {@code Host} that kept the menus where they were.
     */
    public interface Host {

        /** The action button: play a game, or walk into a folder or a zip -
         *  see {@link Entry#isContainer}, which is what decides which of the
         *  two the button says. */
        void open(Entry entry);

        /** The magnifier beside it: everything known about this row, on a
         *  screen of its own. */
        void showInfo(Entry entry);

        /** A tap on page {@code index} of the gallery - the pane's own zoom. */
        void showPicture(Entry entry, int index);
    }

    /** How long the cursor has to rest on a row before its video starts -
     *  long enough that walking through a list does not start a dozen of
     *  them, short enough to feel like an answer to stopping; see
     *  docs/LIBRARY.md. */
    private static final int VIDEO_DELAY_MS = 3000;

    /** Roughly what the cover box actually draws, in dp - bigger than a row's
     *  or a tile's, since the box itself is, but still a fraction of a
     *  full-size cover; see {@code Scraped#load}'s own note on why decoding
     *  to a target rather than a picture's full resolution is what keeps this
     *  from being needlessly slow. */
    private static final int TARGET_DP = 240;

    private final Host host;

    /** The adapter's own, not one of this pane's: a manual resolved for a row
     *  the list has already asked about must not be looked up twice, and the
     *  cache that answers that is the one the rows share. */
    private final Scraped scraped;

    private final View empty;
    private final View details;

    /** The box the artwork sits in, when there is a landscape pane whose
     *  height follows whether there is any - see the constructor's own
     *  listener. Null in portrait, where the split is by width and by
     *  weight. */
    private View cover;

    /** The pictures and, last, the video - swiped between, zoomed to the
     *  viewer on a tap, and faded in over the empty box's own background as
     *  each resolves. Empty, showing the plain box underneath, for anything
     *  unscraped; see {@link #show}. */
    private final Gallery gallery;

    private TextView title;

    /** The filename, under {@link #title} - shown only when that title is a
     *  scraped name rather than the filename itself, so "look closely and the
     *  disk's own name is still there" has somewhere to say it. */
    private TextView filename;

    /** Developer, publisher and the release year, whichever of the three
     *  {@link Meta} actually has, one line, joined by the same separator
     *  {@link EntryAdapter#detail} already uses for size and date. Gone rather
     *  than empty when none of the three is known. */
    private TextView facts;

    private TextView subtitle;

    private ImageButton infoButton;

    /** Beside {@link #infoButton} - shown only once {@link #show}'s own call
     *  to {@link Scraped#loadManual} answers that this selection has one. */
    private ImageButton manualButton;

    /** Beside it, for a game a tune was fetched for. */
    private ImageButton musicButton;

    /** Plays a file, or opens a folder or an archive - see {@link #show},
     *  which is the one place that decides which. */
    private Button actionButton;

    /** The row this pane is currently about, or null when there is none -
     *  held rather than asked for, because the gallery's own tap listener is
     *  registered once, at construction, and has to know at tap time which
     *  game the picture it was given belongs to. */
    private Entry showing;

    /** Set once a person has swiped {@link #gallery} for whatever is
     *  currently showing - cleared by every {@link #show}, since it answers
     *  "has this selection's own gallery been swiped", not "has anyone ever
     *  swiped anything". {@link #advanceToVideo} reads this so the
     *  three-second timer that would otherwise carry a person to the video
     *  never overrides a page they chose for themselves. */
    private boolean userSwiped;

    /** Whether {@link #show} schedules {@link #advanceToVideo} at all - the
     *  library screen re-reads the setting behind it on every {@code
     *  onResume} and hands it here; true until told otherwise, which is what
     *  this always did before the switch existed. */
    private boolean autoplay = true;

    /** Bumped on every {@link #show} call, before anything asynchronous is
     *  asked for - the same shape {@link EntryAdapter}'s own {@code bindToken}
     *  is, for the same reason: the manual a background thread resolves, or
     *  the three-second timer that would bring the video forward, must be
     *  told when the selection has already moved on by the time either is
     *  ready to act. {@link #gallery} keeps its own token for the pictures and
     *  the video themselves - see {@link Gallery#load}. */
    private int token;

    /** Where {@link #advanceToVideo}'s own three-second wait is scheduled,
     *  and where it is cancelled from - see {@link #show}. */
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Tags every {@link Handler#postDelayed} this pane schedules for the
     *  video, so {@link #show} can cancel whichever one is pending without
     *  needing to keep the exact {@link Runnable} it was scheduled with. */
    private final Object videoToken = new Object();

    /**
     * Builds the whole pane, in whichever of its two shapes.
     *
     * The cover box holds {@link #gallery} over a plain background - see
     * {@link #show} for what fills it.
     *
     * {@code details} is the one thing that differs by shape, and only in
     * which way it stacks two pieces that are otherwise identical either way -
     * the cover box, and everything {@link #addDetailViews} adds. Landscape
     * keeps the tall narrow column this always was: the box on top at a fixed
     * height, the rest below it. Portrait cannot afford that - height is the
     * scarce thing there, not width - so the box sits beside the text instead,
     * both weighted against the pane's own width, 2 to 3, rather than a dp
     * fixed at design time or a size derived from the pane's height, which on
     * a strip far wider than it is tall left next to nothing for the text: a
     * portrait pane is wide, not tall, and it is the width the box and the
     * text column split, not the height. Either way, {@link #addDetailViews}
     * puts exactly one weighted child - the spacer - among the rest at their
     * natural height, so that is what yields to a short pane and {@link
     * #actionButton} is always laid out, never squeezed out of it.
     *
     * Landscape is passed in rather than read from the configuration here:
     * the screen holding this already asked, to decide how it stacks the pane
     * against the list, and two reads of the same question are two chances to
     * disagree.
     */
    public DetailPane(Context context, boolean landscape, Scraped scraped, Host host) {
        super(context);

        this.scraped = scraped;
        this.host = host;

        TextView nothing = new TextView(context);
        nothing.setText(R.string.library_nothing_selected);
        nothing.setTextColor(Palette.MUTED);
        nothing.setTextSize(14);
        nothing.setGravity(Gravity.CENTER);
        nothing.setPadding(pixels(24), pixels(24), pixels(24), pixels(24));
        empty = nothing;
        addView(empty, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        FrameLayout coverBox = new FrameLayout(context);
        coverBox.setBackgroundColor(0x14ffffff);

        // Fitting the whole picture inside Gallery's own picture pages is
        // where the grid's tiles crop instead: the pane is the one place a
        // person looks at the picture rather than past it, and the box here
        // is nothing like the shape of box art - 322x640 in portrait,
        // against a cover's own 3:4 - so CENTER_CROP once threw away a third
        // of Ms. Pac-Man's width and cut "FROM ATARISOFT" off the bottom,
        // while the tile above it in the grid showed the same cover whole.
        // See Gallery's own comment on exactly this, and on why FIT_CENTER
        // rather than CENTER_INSIDE is what fits it now.
        gallery = new Gallery(context);
        gallery.setPictureTargetPx(targetPx());
        gallery.setOnPageTapped(this::openViewer);
        gallery.setOnUserSwipe(() -> userSwiped = true);

        // How tall the box is depends on whether there is anything in it, and
        // only the gallery knows - it resolves another app's content provider
        // off this thread. A game ES-DE has never scraped would otherwise be
        // handed the same room as one with seven screenshots, which on a
        // tablet is a large empty rectangle where the artwork would be. It
        // keeps the height it always had in that case; the extra is for
        // pictures, and there are none.
        gallery.setOnContent(count -> {
            if (cover == null) return;

            int wanted = count > 0 ? coverHeight() : pixels(160);
            if (cover.getLayoutParams().height == wanted) return;

            cover.getLayoutParams().height = wanted;
            cover.requestLayout();
        });
        coverBox.addView(gallery, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Only the landscape pane resizes it; portrait splits the width by
        // weight and has no fixed height to change - see below.
        cover = landscape ? coverBox : null;

        LinearLayout column = new LinearLayout(context);
        column.setPadding(pixels(16), pixels(16), pixels(16), pixels(16));

        if (landscape) {
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(coverBox, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, coverHeight()));
            addDetailViews(column);
        } else {
            column.setOrientation(LinearLayout.HORIZONTAL);

            // 2 against 3 of the pane's own width - about 40% for the box,
            // 60% for the text - rather than either a fixed dp or a size
            // derived from the pane's height: a portrait pane is wide, not
            // tall, so it is the width the two split, and a weight adapts to
            // whatever that width actually is without measuring anything.
            LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 2f);
            coverParams.rightMargin = pixels(16);
            column.addView(coverBox, coverParams);

            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            addDetailViews(textColumn);
            column.addView(textColumn, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 3f));
        }

        details = column;
        addView(details, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        show(null);
    }

    /**
     * The title, the filename, the facts line, the size and date, the spacer
     * and the action button - the same six views regardless of shape, added
     * straight into {@code column}: {@code details} itself in landscape,
     * since that is already the vertical stack that wants them; a narrower
     * column beside the cover box in portrait, which needs the exact same
     * weighted spacer to resolve against its own height rather than the whole
     * row's. The spacer is the one weighted child among these six - see its
     * own comment - so it is always this, never the button beneath it, that
     * gives way to a short column.
     */
    private void addDetailViews(LinearLayout column) {
        Context context = getContext();

        title = new TextView(context);
        title.setTextColor(Palette.TEXT);
        title.setTextSize(16);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        // In landscape this is the gap below the cover box sitting above it;
        // in portrait, beside it, it is just breathing room at the top of
        // the column - harmless either way.
        title.setPadding(0, pixels(12), 0, 0);
        column.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Gone rather than empty when the title is already the filename -
        // see show - so "look closely and the disk's own name is still
        // there" costs no blank line when there is nothing to add to what
        // the title already says.
        filename = new TextView(context);
        filename.setTextColor(Palette.MUTED);
        filename.setTextSize(12);
        filename.setMaxLines(1);
        filename.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        filename.setPadding(0, pixels(2), 0, 0);
        filename.setVisibility(View.GONE);
        column.addView(filename, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Developer, publisher, year - gone rather than empty when none of
        // the three is known, which is most of this collection even linked;
        // see factsLine.
        facts = new TextView(context);
        facts.setTextColor(Palette.MUTED);
        facts.setTextSize(13);
        facts.setPadding(0, pixels(6), 0, 0);
        facts.setVisibility(View.GONE);
        column.addView(facts, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // The size and the date - unconditional, exactly as before any of
        // this existed.
        subtitle = new TextView(context);
        subtitle.setTextColor(Palette.MUTED);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, pixels(4), 0, 0);
        column.addView(subtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Whatever is left over, and nothing in it. The pane says the few
        // facts that fit on one line each; a description does not fit on one
        // line, and the version of this that tried ran out of room in
        // landscape and squeezed itself down to 26px - a scroll bar with no
        // room to scroll in. GameInfoActivity is where the long text lives
        // now, and this spacer is what keeps the buttons at the foot of the
        // pane rather than floating under the facts. The one weighted child
        // here, everything else at its own natural height, so this is what
        // yields on a short pane and never the button beneath it.
        column.addView(new View(context), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        // The label is set by show, per selection - a folder or an archive is
        // what is on screen here, not only a game, so there is nothing this
        // button can say once and for all. Always laid out at its own natural
        // height, whatever above it was squeezed to get there.
        actionButton = new Button(context);
        actionButton.setOnClickListener(v -> {
            if (showing != null) host.open(showing);
        });
        actions.addView(actionButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        float density = getResources().getDisplayMetrics().density;

        // Beside Play rather than anywhere in the facts, because it is about
        // the same game Play is about. Hidden for a folder or an archive,
        // which have nothing to tell.
        infoButton = new ImageButton(context);
        infoButton.setImageResource(R.drawable.ic_info);
        infoButton.setColorFilter(Palette.MUTED);
        infoButton.setBackground(Ripple.make(density));
        infoButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        infoButton.setContentDescription(context.getString(R.string.library_info));
        infoButton.setOnClickListener(v -> {
            if (showing != null) host.showInfo(showing);
        });
        actions.addView(infoButton, new LinearLayout.LayoutParams(
                pixels(48), LinearLayout.LayoutParams.MATCH_PARENT));

        // Beside the info icon rather than in the gallery it used to be a
        // page of - see Gallery's own class comment. Starts hidden, same as
        // infoButton does for a folder or an archive; show brings it back
        // once (and only if) Scraped#loadManual answers off the UI thread
        // that there is one - that round trip is a SAF query, never safe to
        // make just to decide whether to draw a button.
        manualButton = new ImageButton(context);
        manualButton.setImageResource(R.drawable.ic_manual);
        manualButton.setColorFilter(Palette.MUTED);
        manualButton.setBackground(Ripple.make(density));
        manualButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        manualButton.setContentDescription(context.getString(R.string.library_manual));
        manualButton.setVisibility(View.GONE);
        actions.addView(manualButton, new LinearLayout.LayoutParams(
                pixels(48), LinearLayout.LayoutParams.MATCH_PARENT));

        // And the music, on the same terms: hidden until something says there
        // is any, which for these is a scraped .ay - a tune belongs to about
        // one game in fifty. Tapping it hands the game to the machine, which
        // is the only thing that can play one.
        musicButton = new ImageButton(context);
        musicButton.setImageResource(R.drawable.ic_music);
        musicButton.setColorFilter(Palette.MUTED);
        musicButton.setBackground(Ripple.make(density));
        musicButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        musicButton.setContentDescription(context.getString(R.string.music_title));
        musicButton.setVisibility(View.GONE);
        actions.addView(musicButton, new LinearLayout.LayoutParams(
                pixels(48), LinearLayout.LayoutParams.MATCH_PARENT));

        column.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /**
     * Hands the game's music to the machine.
     *
     * The emulator screen, because a tune is the Spectrum running the game's
     * own driver - so it plays where the Spectrum is, and whatever was loaded
     * there is put aside and given back. See {@code media.Music}.
     */
    private void openMusic(String relativePath) {
        getContext().startActivity(
                new android.content.Intent(getContext(),
                                           dev.ldlab.zedex.EmulatorActivity.class)
                        .putExtra(dev.ldlab.zedex.EmulatorActivity.EXTRA_MUSIC,
                                  relativePath)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    // --- what the screen tells it ---------------------------------------------

    /**
     * Shows what is known about {@code entry}, or says there is nothing
     * selected when it is null - the one place that fills the pane, reached
     * from every route to a selection there is: a tap, the pad's cursor
     * landing on a row as it moves, a folder or an archive restoring the row
     * it was entered from, and a tab switch clearing it. That has to be true
     * here rather than in whichever of those prompted the call: with a
     * gamepad a folder or an archive is a routine stop for the cursor, not a
     * rare one reached only by coming back out of it, so a label decided
     * anywhere else would be showing whatever the previous row happened to be
     * by the time this one is looked at.
     *
     * The gallery always stops here, immediately - "stopping when the
     * selection moves on" is not something to wait three seconds for, only
     * bringing the video forward is - and a fresh three-second wait is
     * scheduled for whatever is showing now, {@link #token} telling a timer
     * that fires after the selection has moved on again not to act. {@link
     * #userSwiped} is cleared here too: it answers for this selection's own
     * gallery, not for the pane in general.
     *
     * Only for a selection that has actually changed - the screen asks first,
     * and calls {@link #refreshFacts} instead where the key is the same one
     * already showing. Every one of the callers above can land on that: a
     * repeat tap, a held gamepad direction clamped at either end of the list,
     * and a reload as ordinary as a tab switch or {@code onResume} all hand
     * the very row already selected, and reaching this method for that would
     * throw the gallery away and load it again for a game that never left - a
     * fresh {@code Thread} per call before {@link Gallery#load} was given a
     * bounded pool of its own, and unbounded reselecting is what once made
     * that read as the gallery scrolling on its own.
     */
    public void show(Entry entry) {
        showing = entry;

        int forThis = ++token;

        handler.removeCallbacksAndMessages(videoToken);
        userSwiped = false;

        // Hidden until (and unless) the async check below answers yes for
        // this selection - covers every early return below the same way
        // gallery.clear() does, without repeating it at each one.
        manualButton.setVisibility(View.GONE);
        musicButton.setVisibility(View.GONE);

        boolean have = entry != null;

        empty.setVisibility(have ? View.GONE : View.VISIBLE);
        details.setVisibility(have ? View.VISIBLE : View.GONE);

        if (!have) {
            gallery.clear();
            return;
        }

        title.setText(entry.name);
        filename.setVisibility(View.GONE);
        facts.setVisibility(View.GONE);
        applyEntryFacts(entry);

        // A folder, an archive, or a file reached from inside a zip has no
        // path of its own to have been scraped by - see EntryAdapter's own
        // note on exactly this, which this mirrors.
        if (entry.isContainer() || entry.inside != null) {
            gallery.clear();
            return;
        }

        String relativePath = Metadata.relativePath(getContext(), entry.uri);
        if (relativePath == null) {
            gallery.clear();
            return;
        }

        // Read here and now. The gallery resolves and shows its own pictures
        // and video, so what was left of this call was the words - and those
        // are a map read once Metadata has been loaded, so asking a worker
        // for them is what made the pane show a filename and then replace it
        // with the game's name a moment later. Null until the store lands on
        // a cold start, and the library screen calls this again when it does.
        applyMeta(Metadata.forPath(getContext(), relativePath));

        // Beside Play and the magnifier, but only once this answers - see
        // manualButton's own comment for why the round trip has to happen off
        // the UI thread rather than deciding this up front.
        scraped.loadManual(getContext(), relativePath, manual -> {
            if (forThis != token) return; // the selection moved on
            manualButton.setVisibility(manual != null ? View.VISIBLE : View.GONE);
            manualButton.setOnClickListener(
                    manual != null ? v -> Manuals.open(getContext(), manual) : null);
        });

        // The same shape, and off the UI thread for the same reason: this is
        // a look at the media folder, which is a SAF query on some devices.
        scraped.loadMusic(getContext(), relativePath, any -> {
            if (forThis != token) return;

            musicButton.setVisibility(any ? View.VISIBLE : View.GONE);
            musicButton.setOnClickListener(any ? v -> openMusic(relativePath) : null);
        });

        gallery.load(relativePath);

        // Off, the video is still there to swipe to and still plays once
        // swiped to - see docs/LIBRARY.md and the setting's own comment on
        // the library screen - only this automatic move to it is what the
        // setting turns off, so a timer with nothing to do is not even
        // scheduled.
        if (autoplay) {
            handler.postDelayed(() -> advanceToVideo(forThis), videoToken, VIDEO_DELAY_MS);
        }
    }

    /**
     * Empties the pane and stops everything it had running, without saying
     * anything about a selection - what the library screen calls when the
     * second screen's panel is showing the selection instead and this pane is
     * hidden behind it.
     *
     * Kept empty rather than fed: a video playing behind a pane nobody can
     * see is exactly the leak CLAUDE.md warns about, and there is nothing
     * else here for an unseen pane to do. Everything else {@link #show} would
     * have started - the manual lookup, the three-second wait - is stopped
     * for the same reason, by the same bumped {@link #token} and the same
     * cancelled handler that a fresh selection would have used.
     *
     * Not {@code show(null)}, which says "nothing is selected" on a pane
     * somebody can read; this says nothing at all, because nobody can.
     */
    public void standDown() {
        showing = null;
        token++;

        handler.removeCallbacksAndMessages(videoToken);
        userSwiped = false;
        manualButton.setVisibility(View.GONE);
        musicButton.setVisibility(View.GONE);

        gallery.clear();
    }

    /**
     * The lighter half of {@link #show}: a fresh {@link Entry} for the row
     * already showing, not a different one - so this only redoes what {@code
     * Entry} itself carries and a rescan could have moved, which is the size
     * and date line and, in case a rescan ever found a file where a folder
     * was or the other way round, what the action button says and whether the
     * magnifier shows. Everything {@link #show} sets that is keyed by a path
     * rather than by this object - the scraped words, the manual, the
     * gallery's pictures and video, the three-second wait to the video - is
     * untouched here, since all of it is still answering for the very same
     * game: no new {@link Gallery#load}, no reset {@link #userSwiped}, no
     * {@link #token} bumped to tell an in-flight resolve it arrived too late,
     * because none of that is true.
     */
    public void refreshFacts(Entry entry) {
        if (entry == null) return;

        showing = entry;
        applyEntryFacts(entry);
    }

    /** Whether {@link #show} schedules the three-second wait at all - re-read
     *  from its preference and handed here on every {@code onResume}, since
     *  the settings screen's own Library tab is exactly as liable to have
     *  changed since last time as the sort or the folder is. */
    public void setAutoplay(boolean on) {
        autoplay = on;
    }

    /**
     * Lets go of the player behind the pane - a video left running when the
     * screen goes to the background is a real leak, and this is the screen
     * people leave running. Going to the background is not "the selection
     * changed", but it is still one of the three times a video must not be
     * left playing.
     */
    public void release() {
        gallery.release();
    }

    // --- filling it in ----------------------------------------------------------

    /** The three things that come from {@code entry} itself rather than from
     *  anything scraped - the only part {@link #refreshFacts} redoes, and the
     *  first part {@link #show} does. */
    private void applyEntryFacts(Entry entry) {
        subtitle.setText(EntryAdapter.detail(getContext(), entry));
        // A music or screenshot import says Open too - the button hands it
        // to another app, exactly as it does for a folder or an archive,
        // never Play, which only ever means the machine.
        actionButton.setText(entry.isContainer() || Types.external(entry.name)
                ? R.string.library_open : R.string.library_play);

        // A folder or an archive has nothing an information screen could say,
        // and neither has an entry inside a zip, which has no path of its own
        // for the store to have matched - the same test the screen makes
        // before it acts on Host.showInfo.
        infoButton.setVisibility(
                !entry.isContainer() && entry.inside == null ? View.VISIBLE : View.GONE);
    }

    /**
     * The name, the filename beneath it when the name replaced it, and
     * developer/publisher/year - everything the store's answer carries
     * besides the picture this no longer uses, applied by {@link #show}
     * itself since it alone decides whether the answer arrived in time to
     * matter.
     */
    private void applyMeta(Meta meta) {
        if (meta != null && meta.name != null && !meta.name.isEmpty()) {
            title.setText(meta.name);
            filename.setText(showing.name);
            filename.setVisibility(View.VISIBLE);
        }

        String line = factsLine(getContext(), meta);
        if (line != null) {
            facts.setText(line);
            facts.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Developer, publisher and the release year, joined the same way size and
     * date already are - {@link EntryAdapter#detail} - skipping whatever of
     * the three is not known rather than printing an empty label for it.
     * Null, not empty, when none of the three is - the difference between
     * "nothing to show" and "a blank line to show" that {@link #facts}'s own
     * visibility depends on.
     */
    private static String factsLine(android.content.Context context, Meta meta) {
        if (meta == null) return null;

        StringBuilder line = new StringBuilder();
        appendFact(line, meta.developer);
        appendFact(line, meta.publisher);
        appendFact(line, meta.year());

        // Genre and the rating too, the same as the details screen has always
        // shown - the pane had room for them and was showing three facts where
        // GameInfoView showed five.
        appendFact(line, meta.genre);
        appendFact(line, outOfFive(meta));

        // And how often it has been opened, which is the reader's own history
        // rather than a fact about the game - so it goes last, and only once
        // there is one. See Facts.playedLabel.
        appendFact(line, Facts.playedLabel(context, meta));

        return line.length() > 0 ? line.toString() : null;
    }

    /**
     * The scraped rating as {@code 4.5/5}, or null when there is none.
     *
     * Written out rather than drawn as stars: a row of glyphs is read aloud
     * by a screen reader as "black star black star black star", and this line
     * is plain text that goes straight into a contentDescription. The bare
     * fraction ES-DE stores - 0.9 - would mean nothing here, so {@link
     * Meta#stars} scales it; the "/5" is what makes 4.5 a rating rather than
     * a number, and it needs no translating.
     */
    private static String outOfFive(Meta meta) {
        String stars = meta.stars();
        return stars == null ? null : stars + "/5";
    }

    private static void appendFact(StringBuilder line, String fact) {
        if (fact == null || fact.isEmpty()) return;
        if (line.length() > 0) line.append(" · ");
        line.append(fact);
    }

    // --- the video, and the wait to it ------------------------------------------

    /**
     * The far side of {@link #show}'s own three-second wait: carries the
     * gallery to its own video page, exactly as a swipe would, unless either
     * reason to leave it alone applies - the selection has moved on since the
     * wait was scheduled, or {@link #userSwiped} says a person already chose a
     * page of their own for this one. {@link Gallery#showPage} moves the pager
     * without telling that listener about it, so a wait that does win the race
     * never looks like the swipe it is not.
     */
    private void advanceToVideo(int forThis) {
        if (forThis != token || userSwiped) return;

        int index = gallery.videoIndex();
        if (index >= 0) gallery.showPage(index);
    }

    /**
     * A tap on a page of the gallery, on its way out to {@link
     * Host#showPicture}. The same test {@link #show} made before it loaded
     * anything into the gallery at all is made again here rather than trusted
     * to still hold: a tap and the load that filled what was tapped are not
     * the same instant.
     */
    private void openViewer(int index) {
        if (showing == null || showing.isContainer() || showing.inside != null) return;

        host.showPicture(showing, index);
    }

    // --- sizes ------------------------------------------------------------------

    /**
     * How tall the picture is in a side pane, which is a share of the window
     * rather than the 160dp it used to be always.
     *
     * A fixed height is right on a phone and wrong on a tablet, and it was the
     * tablet that showed it: the pane is as tall as the window, a description
     * takes what it needs and no more, and the rest was simply empty - a small
     * picture at the top of a column of nothing. The one place a person looks
     * *at* the artwork rather than past it had the least of it.
     *
     * A share of the height, since that is what differs: about two fifths, so
     * the picture leads and the text still has the greater part. Floored at the
     * old 160dp so no window gets less than it had, and capped so a very tall
     * one does not turn the pane into a poster with a caption. On a phone in
     * landscape - around 400dp of height - two fifths lands within a few dp of
     * 160 anyway, so this changes nothing there, which is the point.
     *
     * Read from the display at build time rather than measured: this runs
     * while the screen holding it is in onCreate, the activity is recreated on
     * rotation, and a listener that resized a child after layout would be the
     * thing CLAUDE.md warns about doing to a RecyclerView, which is what the
     * gallery inside this box is.
     */
    private int coverHeight() {
        int windowHeight = getResources().getDisplayMetrics().heightPixels;

        int wanted = Math.round(windowHeight * 0.42f);
        return Math.max(pixels(160), Math.min(wanted, pixels(320)));
    }

    /**
     * What the pane's picture is decoded to.
     *
     * At least the old 240dp, and never less than the box it has to fill: a
     * picture decoded to 240 and stretched into a 320dp box is a soft one, so
     * enlarging the box without this would have traded empty space for blur.
     *
     * One method rather than the constant in two places, because {@link
     * Scraped}'s cache is keyed by the path *and* the size asked for - two
     * callers asking for different numbers would decode the same picture
     * twice and keep both.
     */
    private int targetPx() {
        return Math.max(pixels(TARGET_DP), coverHeight());
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
