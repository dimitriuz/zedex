package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.meta.Artwork;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.VideoView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every picture {@link Artwork} has for a game, with the video after them -
 * swiped between, dots underneath when there is more than one page, a tap on
 * any page told to whoever asked to be told, and a drag pushed past either
 * end jumping to the other once it settles, rather than stopping there.
 *
 * The manual is not a page here any more: it is a button of its own, beside
 * Play and the magnifier in the pane and on {@code GameInfoActivity}, opened
 * through {@link Manuals#open} - see that class, which is what {@code
 * openManual} used to be before this stopped needing it. A manual is for
 * reading, not for swiping past on the way to the video.
 *
 * The pane, {@code GameInfoActivity} and {@code MediaViewerActivity} all want
 * this: a strip in the pane, a slightly larger one in the details screen, and
 * the whole window in the viewer. Rather than three copies disagreeing about
 * page order or how a video is muted, this is the one place either question
 * is answered - started life as {@code GameInfoActivity}'s own pager, moved
 * here so all three can share it instead of a second copy drifting from the
 * first the way two lists that can disagree always do.
 *
 * A {@link PagerSnapHelper} on a horizontal {@link RecyclerView} is a pager
 * without a dependency for it - see CLAUDE.md, "RecyclerView is already on
 * the classpath" - which is why this is built on one rather than on
 * {@code ViewPager2}.
 *
 * The video is muted here, always: {@code MediaViewerActivity} is the one
 * place it can be turned up, through {@link #setMuted}, and everywhere else
 * simply never calls it. It plays only while its own page is the one on
 * screen - {@link #updateCurrentPage} is what notices a swipe has moved on
 * and stops it - and {@link #release} is there for a host that is going away
 * without so much as a swipe to notice.
 *
 * {@link GalleryAdapter#getItemCount} is the real item count and nothing
 * more - every position anywhere in this class is real, full stop, which was
 * not always true. It used to be multiplied by a large, fixed number of laps
 * so a swipe past either end found another page already there rather than
 * running out, since {@link RecyclerView.Adapter} has no over-scroll of its
 * own to catch on. That trick cost three separate incidents before it was
 * worth asking whether it was worth keeping: a page that measured zero wide
 * made {@code LinearLayoutManager} fill the viewport forever, and
 * multiplying the count by the same mistake turned "stops after eight" into
 * hundreds of thousands of binds and the low-memory killer; a fast resolve
 * later raced the same failure from the opposite direction, landing before
 * the first layout had a real width to give a page at all. Neither had
 * anything to do with the multiplier being wrong <em>as a trick</em> - both
 * were the zero-width bug wearing it as a hundred-thousandfold amplifier -
 * but a mechanism that is the first thing suspected every time, innocently or
 * not, is still a cost, and the honest answer was to stop paying it. Wrapping
 * is a jump now, done by {@link #wrapTarget} once a drag has actually settled
 * back on the end it pushed past - never mid-drag, see that method - and a
 * jump is not a swipe: the two pages either side of the join no longer slide
 * past one another, which used to be seamless and now visibly is not. That is
 * the price of this, on purpose, and it is not one to fix by bringing the
 * multiplier back - see {@link #wrapTarget}'s own comment before reaching for
 * it again. A real count does not remove the zero-width danger {@link
 * #currentPageWidth} still explains; it only lowers the ceiling on it from
 * "the process" to "the real number of pages", which is why every page still
 * needs the pager's own measured width given to it explicitly.
 */
public final class Gallery extends LinearLayout {

    /** Told which page a tap landed on - a host opens {@code
     *  MediaViewerActivity} at that index. */
    public interface OnPageTapped {
        void onPageTapped(int index);
    }

    /** Told whenever the page the pager has settled on changes - including
     *  once, at whatever page a load or {@link #showPage} lands on, so a host
     *  showing a sound button for the video page alone has an answer from
     *  the first frame rather than only after the first swipe. */
    public interface OnPageChanged {
        void onPageChanged(int index);
    }

    private static final int TYPE_PICTURE = 0;
    private static final int TYPE_VIDEO = 1;

    /**
     * How far, in dp, a drag has to keep travelling past an end it has
     * already reached before {@link #wrapTarget} sends it to the other one -
     * see that method for how "past an end" is told apart from "arrived at
     * an end by navigating there". Big enough that letting go right after a
     * swipe lands on the last page - the ordinary way a swipe ends when
     * there is nowhere further for {@code PagerSnapHelper} to snap to -
     * leaves the gallery exactly where it landed: the platform's own {@code
     * EdgeEffect} glow already says "this is the end" without anything
     * moving, and the small extra travel a finger has while that shows is
     * not a request to leave. Small enough that a real, continued push past
     * the glow is not mistaken for hesitation either.
     */
    private static final int EDGE_WRAP_THRESHOLD_DP = 72;

    /** The dots under a gallery of more than one page - see {@link
     *  #buildDots}. Copied from {@code GameInfoActivity}'s own, which drew
     *  the first version of this. */
    private static final int DOT_ON = 0xffededf2;
    private static final int DOT_OFF = 0x40ededf2;

    /** Two at once, the same reasoning and the same number as {@code
     *  Scraped}'s own pool: enough that a fast scroll does not queue dozens
     *  of decodes behind each other, few enough not to fight the UI thread
     *  for the disk or the CPU. A raw {@code Thread} per bind was the other
     *  half of what turned a page measuring zero wide into the low-memory
     *  killer - unbounded threads on top of an unbounded fill - and this is
     *  shared by every {@link Gallery} instance rather than one pool each,
     *  since the pane, the details screen and the viewer are never all
     *  decoding at once in practice, and a shared bound is the whole point
     *  of having one.
     *
     *  Reserved for a page a person is actually looking at, or about to
     *  swipe to on their own: see {@link #prefetchExecutor} for the other
     *  kind of decode, and why the two must never share a queue. */
    private static final ExecutorService decodeExecutor = Executors.newFixedThreadPool(2);

    /**
     * Decodes nobody is waiting on yet - a neighbour {@link
     * #prefetchAround} is getting ahead of, on the chance a swipe reaches it
     * next. One thread, not two: this work only ever saves a swipe from
     * waiting on a decode that was going to happen anyway, so it is worth
     * doing slowly rather than worth doing at {@link #decodeExecutor}'s own
     * pace - and a queue of its own is the whole point, since a bind that is
     * actually on screen must never sit behind a guess about one that is
     * not. Shared across every {@link Gallery} instance for the same reason
     * {@link #decodeExecutor} is.
     */
    private static final ExecutorService prefetchExecutor = Executors.newFixedThreadPool(1);

    /**
     * How many pages either side of the current one are kept decoded ahead
     * of a swipe reaching them - two: enough that a fast swipe rarely finds
     * a page still blank, not so many that opening a long gallery decodes
     * pictures nobody may ever swipe to.
     */
    private static final int PREFETCH_RADIUS = 2;

    /**
     * What {@link #load} resolves {@code Artwork.pictures} and {@code
     * Artwork.video} on - a raw {@code Thread} per call until this existed,
     * which was fine as long as nothing called {@link #load} often. Once
     * something did - {@code LibraryActivity} reselecting the row already
     * showing on every held gamepad direction, every tab switch, every
     * return from {@code GameInfoActivity} - that turned into a thread every
     * time, climbing for as long as the reselecting kept happening, which is
     * what read as the gallery scrolling on its own. The de-dupe in {@code
     * LibraryActivity.select} and {@link DetailPane#refreshFacts} is the real fix -
     * the ordinary case now makes no call here at all - but the same
     * reasoning as {@link #decodeExecutor} still applies: a future bug that
     * calls {@link #load} too often should be slow, not one more unbounded
     * thread pool towards the low-memory killer. Two, not one, for the same
     * reason {@link #decodeExecutor} is two - the pane and a details screen
     * open at once are each allowed one in flight without queuing behind the
     * other - and shared across every {@link Gallery} instance for the same
     * reason every pool here is.
     */
    private static final ExecutorService loadExecutor = Executors.newFixedThreadPool(2);

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final RecyclerView recycler;
    private final GalleryAdapter adapter;
    private final LinearLayout dots;

    /** Roughly what a picture is decoded at - see {@link #setPictureTargetPx}.
     *  A guess until a host sets its own: every host does, so this only
     *  matters if one of them stops. */
    private int targetPx;

    /**
     * The recycler's own measured width, in pixels - {@code 0} until it is
     * known, which is before the first layout pass this view has been
     * through. Every page's own {@code LayoutParams} is given this width
     * explicitly rather than {@code MATCH_PARENT}: {@code
     * LinearLayoutManager}'s own {@code getChildMeasureSpec} turns {@code
     * MATCH_PARENT} into {@code UNSPECIFIED} in the direction a list scrolls
     * - a child cannot match a parent it is free to scroll within - so a
     * horizontal pager of pages asking for {@code MATCH_PARENT} gets each one
     * sized to whatever it measures to on its own: an {@code ImageView} with
     * no bitmap yet is nearly nothing, a {@code VideoView} is its own natural
     * size, and neither is the width of the box. This looked correct in
     * {@code GameInfoActivity}'s own first version of this only because its
     * pictures were decoded at a target near that screen's own box width, by
     * coincidence rather than by measurement - move the box, or catch a page
     * before its bitmap lands, and the same bug shows.
     */
    private int pageWidth;

    /** Bumped by every {@link #load}, so a resolve still running when a newer
     *  one is asked for - a fast reselect while the previous one is still on
     *  its way back from another app's content provider - is dropped rather
     *  than drawn into a gallery that has moved on. Mirrors the token every
     *  slow load in this app is guarded by; see CLAUDE.md's own notes on
     *  {@code EntryAdapter} and {@code LibraryActivity}. */
    private int loadToken;

    /**
     * A {@link #setItems} still waiting for {@link #pageWidth} to become
     * real - null the rest of the time. Set only when {@link #load}'s own
     * resolve answers before this gallery's recycler has been through its
     * first layout at all: a brand new {@code GameInfoActivity} or {@code
     * MediaViewerActivity} builds this view and starts the resolve in the
     * same {@code onCreate}, and once {@link Artwork}'s own caches are warm
     * - which, per docs/LIBRARY.md, is the ordinary case once the pane has
     * resolved this same selection first - that resolve can answer before
     * the window has drawn a single frame. Handing the adapter real items
     * at that moment is exactly the "zero-width page" disaster {@link
     * #currentPageWidth} warns about, just arrived at from a new direction:
     * every earlier version of this bug needed a slow resolve to lose the
     * race against the first layout, and a fast one no longer does.
     * {@link #applyPageWidth} flushes this the moment a real width is
     * known, which is at most one frame away regardless, since the screen
     * this gallery sits in is already on its way to being laid out either
     * way.
     */
    private List<MediaItem> pendingItems;
    private int pendingStartIndex;

    /** Wherever the pager has actually settled - kept apart from asking the
     *  layout manager each time because a video prepares asynchronously and
     *  needs to know, once it is ready, whether its own page is still the
     *  one showing. */
    private int currentIndex;

    /**
     * Wherever the pager was <em>before</em> the touch gesture now in
     * progress, or {@code -1} between gestures - captured on every {@code
     * ACTION_DOWN}, alongside {@link #dragStartX}, since {@link
     * #wrapTarget} needs to know where a drag began, not merely where it
     * ended: a drag that began at the last page and stayed pushed against it
     * is a request to leave; one that began somewhere earlier and simply
     * arrived at the last page, however far it travelled to get there, is
     * navigation and must never wrap. Set back to {@code -1} the moment
     * {@link #wrapTarget} has read its verdict, on the touch listener's own
     * {@code ACTION_UP} - see that listener's comment for why the decision
     * is made there and not from a settle - so a later, unrelated settle -
     * {@link #showPage}'s own smooth scroll, in particular - never sees a
     * stale one left over from the last real touch.
     */
    private int gestureStartIndex = -1;

    /**
     * The finger's own x, in the recycler's window, at this gesture's {@code
     * ACTION_DOWN} and at its {@code ACTION_UP} or {@code ACTION_CANCEL} -
     * not the recycler's own scroll delta, which is what {@link
     * RecyclerView.OnScrollListener#onScrolled} would give and which clamps
     * to nothing the moment a bounded list has nowhere further to move: once
     * the pager is pinned against an end, every extra millimetre a finger
     * drags produces no more scroll at all, so the raw touch position is the
     * only place "how hard did this push" survives to be read at all. Read
     * by {@link #wrapTarget}, from the same {@code ACTION_UP} that just set
     * the second of the two.
     */
    private float dragStartX, dragEndX;

    /** The one video item's own holder, while it is bound - null when this
     *  selection has no video, or before it has been bound at all. At most
     *  one of these ever exists at a time, since {@link #setItems} only ever
     *  adds one video item, last. */
    private VideoHolder videoHolder;

    /** Whether the video plays with sound - false only in {@code
     *  MediaViewerActivity}, and only once somebody has asked for it there;
     *  see {@link #setMuted}. */
    private boolean muted = true;

    private OnPageTapped tapListener;
    private OnPageChanged pageListener;

    /** Called when the pager starts moving from a real drag rather than
     *  {@link #showPage}'s own {@code smoothScrollToPosition} - see {@link
     *  #setOnUserSwipe} for why a host cares about the difference. */
    private Runnable userSwipeListener;

    public Gallery(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);

        targetPx = pixels(200);

        recycler = new RecyclerView(context);
        recycler.setLayoutManager(
                new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        adapter = new GalleryAdapter();
        recycler.setAdapter(adapter);
        new PagerSnapHelper().attachToRecyclerView(recycler);

        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView view, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    if (userSwipeListener != null) userSwipeListener.run();
                } else if (state == RecyclerView.SCROLL_STATE_IDLE && isSettled()) {
                    // isSettled()'s own comment is the reason for the guard -
                    // an IDLE that fails it is not skipped for good, only
                    // for this one dispatch: PagerSnapHelper's own listener,
                    // registered on this recycler before this one is, is
                    // about to send it after the very page it just refused
                    // to read, and that scroll's own IDLE is what this
                    // reaches next.
                    updateCurrentPage();
                }
            }
        });

        // Passive: onInterceptTouchEvent always returns false, so this never
        // claims the gesture away from RecyclerView's own handling - and
        // PagerSnapHelper's, riding on top of it - which is the whole of how
        // this avoids fighting either. Every event of the stream still
        // reaches onInterceptTouchEvent regardless, exactly because nothing
        // ever claims it: RecyclerView keeps offering each one to every
        // unclaimed listener, and onTouchEvent below is what a listener gets
        // instead <em>after</em> it returns true from an intercept, which
        // this deliberately never does - so ACTION_UP has to be read here
        // too, not there, or it is never read at all.
        //
        // {@link #wrapTarget} is decided right here, on {@code ACTION_UP},
        // rather than from {@link #onScrollStateChanged}'s own {@code
        // SCROLL_STATE_IDLE} the way an ordinary settle is read elsewhere in
        // this class. Measured on a real device and on this project's own
        // AVD alike: a drag that starts at position 0 and is pushed further
        // backward - the one direction a bounded list has never had to
        // refuse before this - leaves {@code RecyclerView}'s own scroll
        // state stuck on {@code DRAGGING} forever; the symmetric push
        // forward past the last page settles normally. Confirmed not to be
        // anything this class does: the same hang reproduces with this
        // listener removed outright, so it is RecyclerView's or
        // PagerSnapHelper's own handling of "nothing to scroll to" at
        // position 0 specifically, on the platform this was tested on - not
        // a fight this class picked. Waiting for that {@code IDLE} would
        // make the backward half of wrapping simply never happen; deciding
        // here instead needs nothing from it, since {@link
        // #gestureStartIndex} and the two ends of the drag are already
        // known the moment a finger lifts. The jump itself is still posted
        // rather than run inline, so whatever {@code RecyclerView} does with
        // this same {@code ACTION_UP} first - settling an ordinary swipe,
        // or doing nothing at all for one that cannot move - happens first.
        recycler.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragStartX = event.getX();
                        gestureStartIndex = currentIndex;
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragEndX = event.getX();
                        int wrapTo = wrapTarget();
                        gestureStartIndex = -1; // this gesture's own verdict is in either way
                        if (wrapTo >= 0) {
                            // Posting, not jumping inline, leaves a real gap
                            // before this runs - long enough, on a device
                            // that rotates and this activity's own lack of
                            // configChanges, for the window this recycler
                            // was in to have been torn down under it. Both
                            // checked again once the gap has actually
                            // passed, not just here before it: isAttachedToWindow
                            // is what stops a jump nobody is here to see, and
                            // settleOn's own defence against a video's
                            // MediaPlayer having gone with it is what stops
                            // the crash that cost an afternoon to trace to
                            // exactly this gap.
                            recycler.post(() -> {
                                if (!recycler.isAttachedToWindow()) return;
                                recycler.scrollToPosition(wrapTo);
                                settleOn(wrapTo);
                            });
                        }
                        break;
                    default:
                        break;
                }
                return false;
            }

            @Override
            public void onTouchEvent(RecyclerView view, MotionEvent event) {
                // Never reached - see this listener's own comment above.
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallow) {
            }
        });

        // The recycler has no width until its own first layout pass - the
        // pane, the details screen and the viewer all measure it differently
        // - and it can change again after that, on a rotation that reuses
        // this instance rather than recreating it. See pageWidth's own
        // comment for why every page needs to be told this explicitly.
        recycler.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            if (width > 0 && width != pageWidth) {
                // Not applied straight from here: this fires during the
                // recycler's own layout, and setLayoutParams on an attached
                // child from inside that asks for a pass that this one does
                // not deliver - the exact mistake that once left the
                // library's own grid empty on a cold start; see
                // LibraryActivity.updateGridSpanCount's note on the same
                // trap.
                recycler.post(() -> applyPageWidth(width));
            }
        });

        addView(recycler, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        dots = new LinearLayout(context);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, pixels(8), 0, pixels(8));
        dots.setVisibility(View.GONE);
        addView(dots, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /**
     * Roughly how large a picture is decoded, on its longest side - a pane's
     * own box wants far less than a full screen does, and {@link
     * PictureCache#decode} samples down to whatever this says rather than
     * ever decoding a scraped cover at its own resolution. Set once, before
     * the first {@link #load}; changing it after pictures are already
     * decoded has no effect on them, and changes the cache key every later
     * bind asks for, since {@link PictureCache} keys on this alongside the
     * uri.
     */
    public void setPictureTargetPx(int targetPx) {
        this.targetPx = targetPx;
    }

    public void setOnPageTapped(OnPageTapped listener) {
        this.tapListener = listener;
    }

    public void setOnPageChanged(OnPageChanged listener) {
        this.pageListener = listener;
    }

    /**
     * Told whenever the pager moves because somebody dragged it, as opposed
     * to {@link #showPage} moving it on a host's own behalf - which is
     * exactly what {@code LibraryActivity}'s own three-second dwell needs to
     * know: once a person has swiped this selection's gallery themselves, the
     * timer bringing the video forward must leave the page they chose alone
     * rather than yanking them back to whatever it was about to show.
     */
    public void setOnUserSwipe(Runnable listener) {
        this.userSwipeListener = listener;
    }

    /** {@link #load(String, int)}, starting on the first page. */
    public void load(String relativePath) {
        load(relativePath, 0);
    }

    /**
     * Resolves {@code relativePath}'s pictures and video off the UI thread -
     * both are a round trip to another app's content provider, never safe on
     * this one - and shows them, the video after the pictures; see
     * docs/LIBRARY.md and the class comment above. Lands on {@code
     * startIndex} once they are in, clamped to whatever the gallery actually
     * has.
     *
     * Safe to call again for a different selection at any time: {@link
     * #loadToken} tells a resolve that is still in flight when a newer one is
     * asked for that its own answer no longer applies. Runs on {@link
     * #loadExecutor} rather than a {@code Thread} of its own - see that
     * field's own comment for why a call here has to be bounded the same way
     * every other decode in this class already is.
     */
    public void load(String relativePath, int startIndex) {
        int token = ++loadToken;
        stopVideo();

        Context app = getContext().getApplicationContext();

        loadExecutor.execute(() -> {
            List<Uri> pictures;
            Uri video;

            try {
                pictures = Artwork.pictures(app, relativePath);
            } catch (Exception e) {
                pictures = Collections.emptyList();
            }

            try {
                video = Artwork.video(app, relativePath);
            } catch (Exception e) {
                video = null;
            }

            List<MediaItem> items = new ArrayList<>();
            for (Uri picture : pictures) items.add(new MediaItem(MediaItem.Kind.PICTURE, picture));
            if (video != null) items.add(new MediaItem(MediaItem.Kind.VIDEO, video));

            List<MediaItem> result = items;
            handler.post(() -> {
                if (token != loadToken) return; // a newer load answered first, or this one moved on
                setItems(result, startIndex);
                tellContent();
            });
        });
    }

    /**
     * Told how many items the gallery ended up with, whenever that changes -
     * a load answering, or a {@link #clear}. Both are the same question to a
     * host that has to decide how much room to give this: a game with no
     * artwork at all should not be handed the same space as one with seven
     * screenshots, and only the gallery knows which it is, asynchronously.
     */
    public interface OnContent {
        void onContent(int count);
    }

    private OnContent onContent;

    public void setOnContent(OnContent listener) {
        onContent = listener;
        if (listener != null) listener.onContent(adapter.realCount());
    }

    private void tellContent() {
        if (onContent != null) onContent.onContent(adapter.realCount());
    }

    /** Nothing selected: empties the gallery without asking anything of it. */
    public void clear() {
        loadToken++; // drops whatever a previous load might still be resolving
        setItems(Collections.emptyList(), 0);
        tellContent();
    }

    /** The video's own page, or {@code -1} when this selection has none -
     *  {@link #showPage} of this is what a host's own dwell timer, or a tap
     *  that landed on the video, asks for. */
    public int videoIndex() {
        return adapter.videoIndex();
    }

    /**
     * Moves the pager to {@code index} - a move a host asked for on its own
     * behalf, never a drag, so {@link #setOnUserSwipe}'s own listener is not
     * told about it; see {@code PagerSnapHelper} and {@link
     * RecyclerView#SCROLL_STATE_DRAGGING} for why only a real drag reaches
     * that one. {@code index} is real, the same number {@link #videoIndex}
     * answers with - every position in this class is, now that {@link
     * GalleryAdapter#getItemCount} is too; see the class comment.
     *
     * A neighbour is scrolled to, anything further is jumped to.
     *
     * This used to smooth-scroll whatever the distance, and the one thing that
     * calls it is the three-second wait that carries a person to the video -
     * which on a scraped game is page seven or eight of screenshots. So the
     * pane, having sat still for three seconds, would suddenly riffle through
     * every screenshot in turn on its way there. What that reads as is not
     * "here is the video" but the gallery running away on its own, and it
     * costs a decode for each page it passes through besides.
     *
     * One page apart is a different thing - that is the motion saying which
     * way it went - so that one keeps the animation.
     */
    public void showPage(int index) {
        if (index < 0 || index >= adapter.realCount()) return;

        if (Math.abs(index - currentPage()) <= 1) {
            // A scroll, so SCROLL_STATE_IDLE arrives and settles it.
            recycler.smoothScrollToPosition(index);
            return;
        }

        // A jump is a layout change, not a scroll: no DRAGGING, no SETTLING,
        // no IDLE - so nothing dispatches onScrollStateChanged and the settle
        // has to be done here. Without it currentIndex kept the page the
        // gallery was on before, and prepareVideo's own listener - which only
        // starts a video whose holder is the current page - therefore never
        // started this one. The pane autoswitches from the first picture to
        // the video, which is four or five pages on almost every game, so it
        // always took this branch and always showed a black rectangle.
        //
        // setItems does exactly this after its own scrollToPosition, and
        // settleOn's own comment says it is the one place a video is started,
        // however the page was reached. This branch was the one way that did
        // not go through it.
        recycler.scrollToPosition(index);
        settleOn(index);
    }

    /**
     * Whether the video plays with sound - false everywhere but {@code
     * MediaViewerActivity}. Applied at once if the video is the current page
     * and already prepared; otherwise it is what {@link #applyMute} reads
     * once it is.
     */
    public void setMuted(boolean muted) {
        this.muted = muted;
        if (videoHolder != null) applyMute(videoHolder);
    }

    public boolean isMuted() {
        return muted;
    }

    /**
     * Stops the video - a host going to the background, or away for good, is
     * one of the three times a video must not be left running; see
     * CLAUDE.md's own note on exactly that. Safe whether or not a video was
     * actually playing, or exists at all.
     */
    public void release() {
        stopVideo();
    }

    private void stopVideo() {
        if (videoHolder == null) return;
        videoHolder.view.stopPlayback();
        videoHolder.player = null;
    }

    /**
     * Called once the recycler's own width is known, or has changed - posted
     * rather than applied straight from the layout listener; see its own
     * comment. Corrects every page currently attached directly, rather than
     * {@code notifyDataSetChanged()}: that would tear down and rebuild every
     * holder for a width change alone, and a video holder rebuilt mid-play
     * would restart it - see {@code bindVideo}'s own "already set up" guard,
     * which a full rebuild has no chance to reach.
     */
    private void applyPageWidth(int width) {
        if (width == pageWidth) return; // superseded by a newer post already applied

        pageWidth = width;

        for (int i = 0; i < recycler.getChildCount(); i++) {
            applyWidth(recycler.getChildAt(i));
        }

        recycler.requestLayout();

        // setItems held these back rather than hand the adapter a page it
        // had no real width to give - see pendingItems's own comment for
        // why. There is a real width now, so this is the same setItems
        // that would have run at once had the resolve simply been slower.
        if (pendingItems != null) {
            List<MediaItem> items = pendingItems;
            int startIndex = pendingStartIndex;
            pendingItems = null;
            setItems(items, startIndex);
        }
    }

    /**
     * {@link #pageWidth} once the layout listener has learned it, or the
     * recycler's own measured width as a stand-in before that - a {@code
     * RecyclerView} measures itself before laying out its children, so this
     * is already the right answer the very first time a page is created,
     * not just eventually.
     *
     * There is no third case that falls back to {@code MATCH_PARENT}
     * deliberately: that used to be the fallback, and on the scroll axis
     * {@code LinearLayoutManager}'s own {@code getChildMeasureSpec} turns
     * {@code MATCH_PARENT} into {@code UNSPECIFIED}, so a picture page with
     * no bitmap decoded yet measured to <em>zero</em> wide. A zero-width
     * page never fills the viewport, so {@code LinearLayoutManager} kept
     * creating more of them without ever stopping - a handful of pages at
     * most while {@code getItemCount} was the real count on its own, which
     * is what made it look like nothing was wrong; multiplying that same
     * count by a fixed number of laps, to make a swipe wrap past either end,
     * is what turned it into hundreds of thousands of binds, one decode
     * thread each, and the low-memory killer inside ten seconds - see the
     * class comment for why that multiplier is gone rather than tuned. A
     * real count lowers the ceiling on this same danger back down to a
     * handful of pages; it does not raise it to none, since {@code
     * LinearLayoutManager} still has no idea a page it created came out
     * zero wide and there is still nothing else here to tell it to stop
     * short of running out of real items. The fallback below is checked,
     * not assumed: it only returns something other than a real width in the
     * one moment before the recycler has measured itself at all, which
     * {@link #applyWidth} still declines to apply.
     */
    private int currentPageWidth() {
        if (pageWidth > 0) return pageWidth;
        int measured = recycler.getMeasuredWidth();
        if (measured > 0) return measured;
        int laidOut = recycler.getWidth();
        return laidOut > 0 ? laidOut : ViewGroup.LayoutParams.MATCH_PARENT;
    }

    /** Gives one page {@link #currentPageWidth}, if this page does not
     *  already have it - called both here and as each page is created or
     *  bound, so a holder made before the real width was known still gets it
     *  the moment it is. */
    private void applyWidth(View page) {
        int width = currentPageWidth();
        if (width == ViewGroup.LayoutParams.MATCH_PARENT) return; // nothing to go on yet at all

        ViewGroup.LayoutParams params = page.getLayoutParams();
        if (params == null || params.width == width) return;

        params.width = width;
        page.setLayoutParams(params);
    }

    private void setItems(List<MediaItem> items, int startIndex) {
        if (!items.isEmpty() && currentPageWidth() == ViewGroup.LayoutParams.MATCH_PARENT) {
            // No page has anywhere real to measure itself against yet -
            // see pendingItems's own comment for why this happens and what
            // it used to cost. An empty list needs none of this: it hands
            // the adapter nothing to create a page for at all.
            pendingItems = items;
            pendingStartIndex = startIndex;
            return;
        }
        pendingItems = null;

        stopVideo();
        videoHolder = null;

        adapter.setItems(items);

        int size = items.size();
        int real = size == 0 ? 0 : Math.max(0, Math.min(size - 1, startIndex));

        // No gesture of this fresh gallery's own has happened yet - a stale
        // one left over from whatever this instance showed before must not
        // be read as the verdict on a drag that has not occurred against
        // this selection at all.
        gestureStartIndex = -1;

        // Not smoothScrollToPosition: this is a fresh gallery, not a page a
        // person is being carried to, and animating from wherever the last
        // selection happened to leave the pager would show a swipe through
        // pictures that do not belong to this game at all.
        recycler.scrollToPosition(real);

        buildDots(size);
        settleOn(real);
    }

    /**
     * Reads the pager's own settled page and applies it - the single place
     * a dot is marked or a video started or stopped, called only once the
     * pager is actually idle: a page mid-swipe is not "the" page yet, and
     * acting on one would say so a swipe early. This is the ordinary settle
     * only - {@link #wrapTarget}'s own jump is decided and carried out from
     * the touch listener's own {@code ACTION_UP}, not from here; see that
     * listener's comment for why {@code SCROLL_STATE_IDLE} cannot be trusted
     * to arrive at all for the one gesture that would need it to.
     */
    private void updateCurrentPage() {
        settleOn(currentPage());
    }

    /**
     * Marks the dots, starts or stops the video, and tells a host the page
     * has changed, all for {@code real} - the one place any of those three
     * happens, so a page reached by an ordinary settle and one reached by
     * {@link #wrapTarget}'s own jump are told apart by nothing else in this
     * class.
     */
    private void settleOn(int real) {
        currentIndex = real;

        if (videoHolder != null) {
            if (videoHolder.position == currentIndex) {
                if (videoHolder.player == null && videoHolder.uri != null) {
                    // release() - a host's own onPause - stopped this one
                    // outright rather than merely pausing it, so there is no
                    // MediaPlayer left to start; re-preparing from the same
                    // uri is what lets swiping back to this page after a
                    // background-and-return actually play it again, rather
                    // than a page that only ever worked once per selection.
                    prepareVideo(videoHolder, videoHolder.uri);
                } else {
                    safeStart(videoHolder);
                    // Still preparing: the onPreparedListener checks
                    // currentIndex itself and starts it the moment it is
                    // ready.
                }
            } else {
                videoHolder.view.pause();
            }
        }

        markDots(real);
        notifyPageChanged(real);
        prefetchAround(real);
    }

    private int currentPage() {
        RecyclerView.LayoutManager manager = recycler.getLayoutManager();
        return manager instanceof LinearLayoutManager
                ? ((LinearLayoutManager) manager).findFirstVisibleItemPosition() : 0;
    }

    /**
     * Whether {@link #currentPage} is actually flush with the edge of the
     * viewport, rather than merely the page {@code RecyclerView} last has an
     * opinion about - the two are not the same thing, and {@link
     * #onScrollStateChanged}'s own guard exists because a real device found a
     * case where they briefly disagree.
     *
     * {@link PagerSnapHelper} attaches its own scroll listener to this
     * recycler before the constructor attaches this class's, in {@code
     * SCROLL_STATE_IDLE} order that matters: given a raw drag that ends
     * unaligned, both listeners are told the same {@code IDLE} in the same
     * dispatch, and {@code PagerSnapHelper}'s runs first. It always corrects
     * an unaligned rest - {@code snapToTargetExistingView} asks on every
     * single {@code IDLE} - but the correction is a {@code smoothScrollBy}
     * that only takes visible effect on the next frame; scroll state is
     * still nominally {@code IDLE} for the remainder of this exact dispatch.
     * A listener that trusts that {@code IDLE} unconditionally, as {@link
     * #onScrollStateChanged} once did, marks a dot, starts or stops the
     * video and tells a host the page has changed for a page {@code
     * PagerSnapHelper} is already in the middle of correcting - which then
     * visibly hops away underneath the dot that was just lit for it.
     *
     * Measured on the device, repeatably: an isolated swipe, left alone to
     * settle, never mis-reports - {@link PagerSnapHelper}'s own correction
     * for an ordinary drag-release always finishes before {@code IDLE} ever
     * reaches this class. It takes a <em>second</em> swipe landing on top of
     * an already-settling first one, and ending too gently itself to read as
     * its own fling, to produce the unaligned {@code IDLE} this guards
     * against - which is exactly "sometimes", and exactly why three
     * unhurried swipes afterwards, each with nothing left to interrupt, look
     * like the bug fixing itself: it was never the snap that failed, only
     * this class reading it one dispatch too early.
     *
     * Nothing here asks for a corrective scroll of its own - {@code
     * PagerSnapHelper} was already issuing the right one the whole time.
     * Skipping the unaligned {@code IDLE} costs nothing: the aligned one
     * behind it always follows, on the very next state change, and {@link
     * #updateCurrentPage} answers to that one instead.
     */
    private boolean isSettled() {
        RecyclerView.LayoutManager manager = recycler.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) return true;

        View page = ((LinearLayoutManager) manager).findViewByPosition(currentPage());
        return page == null || page.getLeft() == 0;
    }

    /**
     * Whether the drag that just settled the pager back onto an end page was
     * a push to leave by that end, or only a swipe that happened to arrive
     * there because that is where it was going anyway - told apart by where
     * the drag <em>began</em>, {@link #gestureStartIndex}, not by how far it
     * travelled or where it ended: a bounded list cannot travel past its own
     * end at all, so "arrived at the last page" is true of both a drag that
     * started there and pushed, and one that started three pages back and
     * simply went all the way - only the first is a request to leave, and
     * the second must never wrap however far or fast it was. Returns the
     * real index to jump to, or {@code -1} to stay exactly where the drag
     * left things.
     *
     * Never for a gallery of one page: {@link #EDGE_WRAP_THRESHOLD_DP} would
     * otherwise send its only page to itself on every sufficiently long
     * press, which is a bug wearing this feature's clothes, not the feature.
     */
    private int wrapTarget() {
        int size = adapter.realCount();
        if (size <= 1 || gestureStartIndex < 0) return -1;

        float travel = dragStartX - dragEndX; // positive: finger moved toward higher indices
        int threshold = pixels(EDGE_WRAP_THRESHOLD_DP);

        if (gestureStartIndex == size - 1 && travel > threshold) return 0;
        if (gestureStartIndex == 0 && -travel > threshold) return size - 1;
        return -1;
    }

    private void notifyPageChanged(int realIndex) {
        if (pageListener != null) pageListener.onPageChanged(realIndex);
    }

    /** {@code position} is real, the same number every position in this
     *  class is now - see the class comment - so {@link #tapListener} is
     *  simply handed it back. */
    private void notifyTap(int position) {
        if (position == RecyclerView.NO_POSITION || tapListener == null) return;
        tapListener.onPageTapped(position);
    }

    /**
     * {@code VideoView} keeps the video's own aspect regardless of the
     * bounds it is given, so in a box taller than it is wide - a pane sized
     * for box art - a video shorter than the box collects its empty space
     * underneath, at {@code FrameLayout}'s default gravity. There is no
     * {@code FrameLayout} here to set a gravity on, but {@code VideoView}
     * centres itself inside whatever measures it larger than the video
     * needs, which {@code MATCH_PARENT} already does - noted here because it
     * looked like an oversight the first time this moved, and it is not one.
     *
     * Shared by a fresh bind and by {@link #updateCurrentPage}'s own repeat
     * visit to a holder {@link #release} had fully stopped: both start from
     * nothing prepared, and the only difference is who is asking.
     */
    private void prepareVideo(VideoHolder holder, Uri video) {
        holder.player = null;
        holder.view.setVideoURI(video);
        holder.view.setOnPreparedListener(mp -> {
            holder.player = mp;
            mp.setLooping(true);

            // A video prepares asynchronously; the pager may already have
            // moved on to a different page by the time it answers, and
            // starting it now would be a video nobody asked for playing
            // behind whatever is actually on screen.
            if (holder.position == currentIndex) safeStart(holder);
        });
    }

    /**
     * {@code holder.view.start()}, muted first through {@link #applyMute} -
     * the two always go together here, and only here, so a holder is never
     * started at the wrong volume for even one frame. Guarded the same way
     * {@link #applyMute} guards itself, and for the same reason: see that
     * method's own comment.
     */
    private void safeStart(VideoHolder holder) {
        applyMute(holder);
        if (holder.player == null) return;

        try {
            holder.view.start();
        } catch (IllegalStateException e) {
            holder.player = null;
        }
    }

    /**
     * {@code holder.player.setVolume(...)}, treating an {@link
     * IllegalStateException} the platform's own {@code MediaPlayer} throws
     * as "no player any more" rather than a crash - the same reasoning
     * {@code PictureCache.decodeFresh} already applies to a foreign
     * provider or a corrupt file answering unpredictably, extended here to
     * a player whose window has gone out from under it. Measured, not
     * theoretical: {@link #wrapTarget}'s own jump is posted rather than run
     * inline, and the gap that leaves - long enough, on a device that
     * rotates and this activity's own lack of {@code configChanges}, for
     * the window this holder's video was playing in to have been torn down
     * - is exactly what threw this from {@link #settleOn} the first time
     * the backward half of wrapping was tried on a device. Drops {@link
     * VideoHolder#player} on the way out, the same as a deliberate {@link
     * #release} does, so the next settle re-prepares from the same uri
     * rather than throwing again.
     */
    private void applyMute(VideoHolder holder) {
        if (holder.player == null) return;

        try {
            holder.player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
        } catch (IllegalStateException e) {
            holder.player = null;
        }
    }

    /** Builds the dots themselves; {@link #setItems} marks the current one
     *  right after, through {@link #settleOn}, since a fresh load knows
     *  which page it is landing on before the pager itself has moved there. */
    private void buildDots(int count) {
        dots.removeAllViews();
        dots.setVisibility(count > 1 ? View.VISIBLE : View.GONE);

        // Decoration, and nothing else: they are 7dp circles that say which
        // page is current by colour alone, at a contrast a sighted reader
        // would struggle with and a screen reader cannot use at all. The page
        // itself carries "Picture 2 of 5" now, which is the same information
        // in a form that can be read, so these are taken out of the tree
        // rather than left as unnamed focusable clutter.
        dots.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        if (count <= 1) return;

        for (int i = 0; i < count; i++) {
            View dot = new View(getContext());
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(pixels(7), pixels(7));
            params.leftMargin = params.rightMargin = pixels(4);
            dots.addView(dot, params);
        }
    }

    private void markDots(int current) {
        for (int i = 0; i < dots.getChildCount(); i++) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(i == current ? DOT_ON : DOT_OFF);
            dots.getChildAt(i).setBackground(circle);
        }
    }

    /** Lets the decoded pictures go; see PictureCache.forget. Static
     *  because the cache is - every gallery in the app shares it. */
    public static void forgetPictures() {
        PictureCache.forget();
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /**
     * Decodes every neighbour within {@link #PREFETCH_RADIUS} of {@code
     * real} that is not already cached, so a swipe that reaches one finds
     * {@link PictureCache} already holding it rather than starting a decode
     * only once the page is actually on screen. Called once a fresh {@link
     * #setItems} has landed and again every time {@link #updateCurrentPage}
     * settles on a new page - never mid-swipe, since a page not yet settled
     * on is not "current" and prefetching around it would guess wrong the
     * moment the swipe actually lands.
     *
     * Never the video: {@code VideoView} does its own buffering, and there
     * is nothing here for a decode to cache ahead of time.
     *
     * {@code Math.floorMod} wraps a neighbour past either end of the real
     * list on purpose, independently of whether {@link #wrapTarget} would
     * actually send a drag there: the picture after the last page is still
     * the one a big enough push lands on, so it is still worth having ready.
     */
    private void prefetchAround(int real) {
        int size = adapter.realCount();
        if (size <= 1) return;

        Context app = getContext().getApplicationContext();

        for (int delta = -PREFETCH_RADIUS; delta <= PREFETCH_RADIUS; delta++) {
            if (delta == 0) continue;

            int neighbour = Math.floorMod(real + delta, size);
            Uri picture = adapter.pictureUriAt(neighbour);
            if (picture == null) continue; // no such page, or it is the video

            if (PictureCache.get(picture, targetPx) != null) continue; // already there

            prefetchExecutor.execute(() -> {
                // Checked again on this thread: two neighbours can name the
                // same file - a cover and a back cover this game shares by
                // mistake, say - and the first of them to actually run may
                // already have decoded it by the time the second starts.
                if (PictureCache.get(picture, targetPx) == null) {
                    PictureCache.decode(app, picture, targetPx);
                }
            });
        }
    }

    private static final class MediaItem {
        enum Kind { PICTURE, VIDEO }

        final Kind kind;
        final Uri uri;

        MediaItem(Kind kind, Uri uri) {
            this.kind = kind;
            this.uri = uri;
        }
    }

    private static final class PictureHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        int bindToken;

        PictureHolder(ImageView view) {
            super(view);
            image = view;
        }
    }

    /**
     * {@code uri} is what was last bound - kept so a holder recycled back to
     * the same video, which a short gallery's small view pool can do, does
     * not restart a video that is already prepared or playing.
     *
     * The item view is a {@code FrameLayout} around the {@code VideoView},
     * not the {@code VideoView} itself - see {@code onCreateViewHolder}'s own
     * comment on why: a {@code VideoView} measures itself to its content's
     * aspect ratio even when told an exact width, and {@code
     * LinearLayoutManager} takes that measured size as how much of the pager
     * this page actually occupies, pulling a neighbour into whatever is left
     * over. A plain view around it does not have that problem - it reports
     * whatever size it was told to be, its child's own measurement having no
     * say in it - which is exactly what a page's outer bounds need to do.
     */
    private static final class VideoHolder extends RecyclerView.ViewHolder {
        final VideoView view;
        Uri uri;
        MediaPlayer player;
        int position;

        VideoHolder(FrameLayout frame, VideoView view) {
            super(frame);
            this.view = view;
        }
    }

    private final class GalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<MediaItem> items = new ArrayList<>();

        void setItems(List<MediaItem> found) {
            items.clear();
            items.addAll(found);
            notifyDataSetChanged();
        }

        /** {@code items.size()} - the same number {@link #getItemCount}
         *  answers with directly now, kept under its own name only because
         *  "real count" reads better at every call site than the override's
         *  own name does. */
        int realCount() {
            return items.size();
        }

        int videoIndex() {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).kind == MediaItem.Kind.VIDEO) return i;
            }
            return -1;
        }

        /** {@code real}'s own picture, or null when {@code real} is out of
         *  range or names the video - {@link #prefetchAround}'s own use,
         *  which has nothing to decode ahead of time for either. */
        Uri pictureUriAt(int real) {
            if (real < 0 || real >= items.size()) return null;
            MediaItem item = items.get(real);
            return item.kind == MediaItem.Kind.PICTURE ? item.uri : null;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).kind == MediaItem.Kind.VIDEO ? TYPE_VIDEO : TYPE_PICTURE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            // Never MATCH_PARENT here - see currentPageWidth's own comment
            // for what that cost before this was fixed.
            int width = currentPageWidth();

            if (type == TYPE_VIDEO) {
                // The FrameLayout, not the VideoView, is what RecyclerView
                // measures as this page - see VideoHolder's own comment for
                // why that distinction is the whole fix. The VideoView still
                // letterboxes to its own aspect ratio inside it, centred
                // rather than pinned to a corner, which is the same trade
                // the pane's own cover box always made; see CLAUDE.md.
                FrameLayout frame = new FrameLayout(getContext());
                frame.setLayoutParams(new RecyclerView.LayoutParams(
                        width, ViewGroup.LayoutParams.MATCH_PARENT));

                VideoView view = new VideoView(getContext());
                FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
                videoParams.gravity = Gravity.CENTER;
                frame.addView(view, videoParams);

                return new VideoHolder(frame, view);
            }

            ImageView view = new ImageView(getContext());

            // FIT_CENTER, not CENTER_CROP: this is the one place a person
            // looks straight at a picture rather than past it on the way to
            // something else, and cropping a cover to a box shaped nothing
            // like it cost the pane a third of one game's width and the
            // bottom of its logo before this existed - see the grid's own
            // tiles, which crop deliberately for the opposite reason.
            //
            // Not CENTER_INSIDE either, which this used to be: that never
            // enlarges, so a 256x192 Spectrum screenshot sat at its own size
            // in the middle of a box built for box art and looked lost.
            // FIT_CENTER scales a small picture up to fill the box, aspect
            // kept, which is what CENTER_INSIDE was reaching for and did not
            // do - the two only differ on a picture smaller than the box,
            // and every Spectrum screenshot is one. Do not "fix" this back to
            // CENTER_INSIDE: that regression is what this comment exists to
            // stop.
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setContentDescription(null);
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    width, ViewGroup.LayoutParams.MATCH_PARENT));

            return new PictureHolder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            // A holder can be bound again without being recreated - the
            // view pool's whole point - so a width learned after it was
            // created is applied again here rather than only in
            // onCreateViewHolder.
            applyWidth(holder.itemView);

            MediaItem item = items.get(position);

            // Named, because these are clickable and they are the whole
            // content of the pane: unlabelled, a screen reader announced the
            // picture somebody is looking at as "unlabelled, button". The
            // position goes in the name rather than being left to the dots,
            // which are colour alone and now hidden from accessibility
            // outright - so "Picture 2 of 5" says both what it is and where
            // in the strip it sits.
            holder.itemView.setContentDescription(getContext().getString(
                    holder instanceof VideoHolder
                            ? R.string.gallery_video
                            : R.string.gallery_picture,
                    position + 1, items.size()));

            if (holder instanceof VideoHolder) {
                bindVideo((VideoHolder) holder, item.uri, position);
            } else {
                bindPicture((PictureHolder) holder, item.uri, position);
            }
        }

        private void bindPicture(PictureHolder holder, Uri picture, int position) {
            int token = ++holder.bindToken;

            holder.image.setOnClickListener(v -> notifyTap(holder.getAdapterPosition()));

            if (picture == null) {
                holder.image.setImageDrawable(null);
                return;
            }

            // An exact hit means nothing to decode at all - a swipe back to
            // a page already shown, or a neighbour prefetchAround got to
            // first. See PictureCache's own comment for why this is a cache
            // shared with Scraped rather than one of this class's own.
            Bitmap exact = PictureCache.get(picture, targetPx);
            if (exact != null) {
                holder.image.setImageBitmap(exact);
                return;
            }

            // Nothing at this exact size yet, but the tile or row this game
            // was opened from almost certainly decoded the same file only
            // moments ago, at its own smaller size - showing that now turns
            // a blank box into a soft one, replaced the moment the real
            // decode lands rather than left blank until it does.
            holder.image.setImageBitmap(PictureCache.placeholder(picture));

            Context app = getContext().getApplicationContext();
            decodeExecutor.execute(() -> {
                Bitmap decoded = PictureCache.decode(app, picture, targetPx);

                handler.post(() -> {
                    if (holder.bindToken != token) return; // recycled meanwhile
                    if (decoded != null) holder.image.setImageBitmap(decoded);
                    // A decode that failed leaves whatever was already
                    // showing - the placeholder, or nothing - rather than
                    // clearing a picture that did decode a moment ago.
                });
            });
        }

        /** New page, or a rebind to the same one - see {@link #prepareVideo}
         *  for what actually sets the video up. */
        private void bindVideo(VideoHolder holder, Uri video, int position) {
            videoHolder = holder;
            holder.position = position;

            // The frame, not the VideoView: a letterboxed video leaves bars
            // on two sides that belong to no view a tap could land on
            // otherwise, and this page should open the viewer wherever it
            // is tapped, the same as a picture's own page does.
            holder.itemView.setOnClickListener(v -> notifyTap(holder.getAdapterPosition()));

            if (video.equals(holder.uri)) return; // already set up - a rebind to the same page

            holder.uri = video;
            prepareVideo(holder, video);
        }

        /**
         * The real count, exactly - see the class comment for what used to
         * be here instead and why it no longer is. Wrapping past either end
         * is {@link #wrapTarget}'s job now, done once a drag has settled
         * rather than by giving the pager more list than there really is.
         */
        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
