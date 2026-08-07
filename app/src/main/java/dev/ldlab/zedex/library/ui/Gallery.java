package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.library.meta.Artwork;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
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
 * any page told to whoever asked to be told, wrapping past either end back to
 * the other.
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
 * {@link GalleryAdapter#getItemCount} reports a large multiple of the real
 * item count so a swipe past either end lands back at the other, rather than
 * stopping - {@link RecyclerView.Adapter} has no over-scroll to catch, so a
 * virtualised count is the ordinary way to fake one. Every position from the
 * adapter's own callbacks is real modulo the underlying list's size, except
 * where a comment says otherwise; {@link #notifyTap} and {@link
 * #notifyPageChanged} always hand a caller the real one, since a host has no
 * business knowing this trick exists.
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
     * How many times the real item list repeats in {@link
     * GalleryAdapter#getItemCount} - the wraparound trick, see the class
     * comment. A thousand laps is four thousand swipes from the middle to
     * either end - nobody reaches that in one sitting - and it is kept
     * deliberately small rather than merely "large": before wraparound
     * existed, {@code getItemCount} was the real size, which was an
     * accidental ceiling on a bug that was there the whole time - see {@link
     * #currentPageWidth}. A page that measures zero wide makes {@code
     * LinearLayoutManager} keep creating more of them to fill the viewport,
     * and multiplying the count by 100,000 turned that from "stops after
     * eight" into several hundred thousand binds, several hundred threads,
     * and the low-memory killer inside ten seconds. Fixing the zero-width
     * page is the real fix; this number is the second line of defence, so
     * the *next* such mistake stalls a swipe instead of taking the process
     * with it.
     */
    private static final int WRAP_LAPS = 1_000;

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
                } else if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    updateCurrentPage();
                }
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
     * asked for that its own answer no longer applies.
     */
    public void load(String relativePath, int startIndex) {
        int token = ++loadToken;
        stopVideo();

        Context app = getContext().getApplicationContext();

        new Thread(() -> {
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
            });
        }).start();
    }

    /** Nothing selected: empties the gallery without asking anything of it. */
    public void clear() {
        loadToken++; // drops whatever a previous load might still be resolving
        setItems(Collections.emptyList(), 0);
    }

    /** The video's own page, or {@code -1} when this selection has none -
     *  {@link #showPage} of this is what a host's own dwell timer, or a tap
     *  that landed on the video, asks for. */
    public int videoIndex() {
        return adapter.videoIndex();
    }

    /**
     * Moves the pager to {@code index}, smoothly - a swipe a host asked for
     * on its own behalf, never a drag, so {@link #setOnUserSwipe}'s own
     * listener is not told about it; see {@code PagerSnapHelper} and {@link
     * RecyclerView#SCROLL_STATE_DRAGGING} for why only a real drag reaches
     * that one.
     *
     * {@code index} is real, i.e. what {@link #videoIndex} answers with, not
     * a position on the virtualised pager the wraparound needs - see the
     * class comment - so this asks {@link GalleryAdapter#nearestVirtual} for
     * whichever lap of it is actually closest to where the pager already is,
     * rather than always jumping back to the lap a fresh {@link #load}
     * started on.
     */
    public void showPage(int index) {
        if (index < 0 || index >= adapter.realCount()) return;
        recycler.smoothScrollToPosition(adapter.nearestVirtual(currentIndex, index));
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
     * creating more of them without ever stopping - invisible before
     * wraparound existed, because the real item count was itself a ceiling
     * of a handful of pages; multiplying it, see {@link #WRAP_LAPS}, is what
     * turned the same bug into hundreds of thousands of binds, one decode
     * thread each, and the low-memory killer inside ten seconds. The
     * fallback below is checked, not assumed: it only returns something
     * other than a real width in the one moment before the recycler has
     * measured itself at all, which {@link #applyWidth} still declines to
     * apply.
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

        // The middle lap of the virtualised count a fresh gallery lands on,
        // not lap zero - see WRAP_LAPS and middleVirtual - so there is room
        // to wrap in *both* directions from the very first page shown rather
        // than only after enough swipes have carried it away from the start.
        // Single item or none: real and virtual are the same position, since
        // GalleryAdapter#getItemCount never multiplies a list that short.
        currentIndex = size <= 1 ? real : adapter.middleVirtual(real);

        // Not smoothScrollToPosition: this is a fresh gallery, not a page a
        // person is being carried to, and animating from wherever the last
        // selection happened to leave the pager would show a swipe through
        // pictures that do not belong to this game at all.
        recycler.scrollToPosition(currentIndex);

        buildDots(size);
        markDots(real);
        notifyPageChanged(real);
        prefetchAround(real);
    }

    /**
     * Reads the pager's own settled page, marks the dots for it, and starts
     * or stops the video depending on whether it is the one now showing -
     * the single place all three of those happen, called only once the pager
     * is actually idle: a page mid-swipe is not "the" page yet, and marking
     * a dot or starting a video against one would say so a swipe early.
     */
    private void updateCurrentPage() {
        currentIndex = currentPage();

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
                    applyMute(videoHolder);
                    if (videoHolder.player != null) videoHolder.view.start();
                    // Still preparing: the onPreparedListener checks
                    // currentIndex itself and starts it the moment it is
                    // ready.
                }
            } else {
                videoHolder.view.pause();
            }
        }

        // Both of these want the real page, 0..size-1 - see the class
        // comment - never the virtualised position currentIndex actually
        // holds; videoHolder.position above is the one comparison in this
        // class that is deliberately virtual on both sides instead.
        int real = adapter.realIndexOf(currentIndex);
        markDots(real);
        notifyPageChanged(real);
        prefetchAround(real);
    }

    private int currentPage() {
        RecyclerView.LayoutManager manager = recycler.getLayoutManager();
        return manager instanceof LinearLayoutManager
                ? ((LinearLayoutManager) manager).findFirstVisibleItemPosition() : 0;
    }

    private void notifyPageChanged(int realIndex) {
        if (pageListener != null) pageListener.onPageChanged(realIndex);
    }

    /**
     * {@code position} is whatever the recycler's own click handler saw,
     * which is virtual once wraparound has carried the pager past lap zero -
     * converted to real here, once, so {@link #tapListener} never has to know
     * about the trick: a page tapped after wrapping past the end still opens
     * {@code MediaViewerActivity} on the picture it actually is, not on a
     * position past the end of that screen's own, un-virtualised list.
     */
    private void notifyTap(int position) {
        if (position == RecyclerView.NO_POSITION || tapListener == null) return;
        tapListener.onPageTapped(adapter.realIndexOf(position));
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
            applyMute(holder);

            // A video prepares asynchronously; the pager may already have
            // moved on to a different page by the time it answers, and
            // starting it now would be a video nobody asked for playing
            // behind whatever is actually on screen.
            if (holder.position == currentIndex) holder.view.start();
        });
    }

    private void applyMute(VideoHolder holder) {
        if (holder.player != null) {
            holder.player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
        }
    }

    /** Builds the dots themselves; {@link #setItems} marks the current one
     *  right after, since it alone knows the real (non-virtualised) index to
     *  mark - see the class comment on why currentIndex itself is the wrong
     *  thing to ask here. */
    private void buildDots(int count) {
        dots.removeAllViews();
        dots.setVisibility(count > 1 ? View.VISIBLE : View.GONE);

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

        /** {@code items.size()} - the real count wraparound multiplies away;
         *  see {@link #getItemCount}. */
        int realCount() {
            return items.size();
        }

        /** A virtual position modulo the real list - the identity when
         *  there is no wraparound to undo, i.e. at most one item. Every
         *  adapter callback below is keyed off this, once, rather than
         *  scattering the same modulo through each of them. */
        int realIndexOf(int position) {
            int size = items.size();
            return size == 0 ? 0 : position % size;
        }

        /**
         * {@code real}, moved into the lap furthest from either physical end
         * of {@link #getItemCount} - the middle one, since {@link
         * #getItemCount} always multiplies by an even {@link #WRAP_LAPS}.
         * What a fresh {@link #setItems} starts on, so the very first page
         * shown already has room to wrap either way rather than only after
         * enough swipes have carried it there.
         */
        int middleVirtual(int real) {
            int size = items.size();
            return size <= 1 ? real : (WRAP_LAPS / 2) * size + real;
        }

        /**
         * Whichever virtual position stands for {@code targetReal} is
         * closest to {@code fromVirtual} - the lap {@code fromVirtual} is
         * already on, or the one before or after it, whichever lands
         * nearest. {@link #showPage} uses this so carrying the pager to the
         * video, say, is always the short way there, on whatever lap the
         * pager happens to be on already, rather than a jump back to
         * whatever lap {@link #middleVirtual} chose when this gallery loaded.
         */
        int nearestVirtual(int fromVirtual, int targetReal) {
            int size = items.size();
            if (size <= 1) return targetReal;

            int lap = Math.floorDiv(fromVirtual, size);
            int best = lap * size + targetReal;

            for (int delta = -1; delta <= 1; delta++) {
                int candidate = best + delta * size;
                if (Math.abs(candidate - fromVirtual) < Math.abs(best - fromVirtual)) {
                    best = candidate;
                }
            }

            return Math.max(0, best);
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
            return items.get(realIndexOf(position)).kind == MediaItem.Kind.VIDEO
                    ? TYPE_VIDEO : TYPE_PICTURE;
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

            MediaItem item = items.get(realIndexOf(position));

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
         * A large multiple of the real count so a swipe never runs out of
         * pages to move to in either direction - see the class comment and
         * {@link #middleVirtual} - except for zero or one item, which is
         * never multiplied: a single page that scrolls forever, with nothing
         * else to land on, is a bug rather than a wraparound.
         */
        @Override
        public int getItemCount() {
            int size = items.size();
            return size <= 1 ? size : size * WRAP_LAPS;
        }
    }
}
