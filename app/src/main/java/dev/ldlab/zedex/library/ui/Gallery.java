package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.library.meta.Artwork;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every picture {@link Artwork} has for a game, with the video last if there
 * is one - swiped between, dots underneath when there is more than one page,
 * a tap on any page told to whoever asked to be told.
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

    /** The dots under a gallery of more than one page - see {@link
     *  #buildDots}. Copied from {@code GameInfoActivity}'s own, which drew
     *  the first version of this. */
    private static final int DOT_ON = 0xffededf2;
    private static final int DOT_OFF = 0x40ededf2;

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
     * own box wants far less than a full screen does, and {@link #decode}
     * samples down to whatever this says rather than ever decoding a scraped
     * cover at its own resolution. Set once, before the first {@link #load};
     * changing it after pictures are already decoded has no effect on them.
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
     * this one - and shows them, the video last if there is one at all; see
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
            for (Uri picture : pictures) items.add(new MediaItem(false, picture));
            if (video != null) items.add(new MediaItem(true, video));

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

    /** Moves the pager to {@code index}, smoothly - a swipe a host asked for
     *  on its own behalf, never a drag, so {@link #setOnUserSwipe}'s own
     *  listener is not told about it; see {@code PagerSnapHelper} and {@link
     *  RecyclerView#SCROLL_STATE_DRAGGING} for why only a real drag reaches
     *  that one. */
    public void showPage(int index) {
        if (index < 0 || index >= adapter.getItemCount()) return;
        recycler.smoothScrollToPosition(index);
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
    }

    /** Gives one page {@link #pageWidth}, if it is known and this page does
     *  not already have it - called both here and as each page is created or
     *  bound, so a holder made before the width was known still gets it the
     *  moment it is. */
    private void applyWidth(View page) {
        if (pageWidth <= 0) return;

        ViewGroup.LayoutParams params = page.getLayoutParams();
        if (params == null || params.width == pageWidth) return;

        params.width = pageWidth;
        page.setLayoutParams(params);
    }

    private void setItems(List<MediaItem> items, int startIndex) {
        stopVideo();
        videoHolder = null;

        adapter.setItems(items);
        currentIndex = items.isEmpty() ? 0
                : Math.max(0, Math.min(items.size() - 1, startIndex));

        // Not smoothScrollToPosition: this is a fresh gallery, not a page a
        // person is being carried to, and animating from wherever the last
        // selection happened to leave the pager would show a swipe through
        // pictures that do not belong to this game at all.
        recycler.scrollToPosition(currentIndex);

        buildDots(items.size());
        notifyPageChanged(currentIndex);
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
        markDots(currentIndex);

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

        notifyPageChanged(currentIndex);
    }

    private int currentPage() {
        RecyclerView.LayoutManager manager = recycler.getLayoutManager();
        return manager instanceof LinearLayoutManager
                ? ((LinearLayoutManager) manager).findFirstVisibleItemPosition() : 0;
    }

    private void notifyPageChanged(int index) {
        if (pageListener != null) pageListener.onPageChanged(index);
    }

    private void notifyTap(int position) {
        if (position != RecyclerView.NO_POSITION && tapListener != null) {
            tapListener.onPageTapped(position);
        }
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

        markDots(currentIndex);
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
     * Decoded to roughly {@link #targetPx}, the same two-pass way every
     * other picture in this app is: the whole file is not worth holding to
     * draw a fraction of it, and a scraped cover can be far larger than any
     * box wants.
     */
    private Bitmap decode(Uri picture) {
        if (picture == null) return null;

        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;

            try (InputStream probe = getContext().getContentResolver().openInputStream(picture)) {
                if (probe == null) return null;
                BitmapFactory.decodeStream(probe, null, bounds);
            }

            int longest = Math.max(bounds.outWidth, bounds.outHeight);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, longest / Math.max(1, targetPx));

            try (InputStream in = getContext().getContentResolver().openInputStream(picture)) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Exception e) {
            // A picture that will not read is no picture; the rest of the
            // gallery is still worth showing.
            return null;
        }
    }

    private static final class MediaItem {
        final boolean video;
        final Uri uri;

        MediaItem(boolean video, Uri uri) {
            this.video = video;
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

        int videoIndex() {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).video) return i;
            }
            return -1;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).video ? TYPE_VIDEO : TYPE_PICTURE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            // pageWidth if it is already known, MATCH_PARENT otherwise - see
            // pageWidth's own comment for why MATCH_PARENT alone is wrong on
            // this axis, and applyWidth for what corrects a holder made
            // before the real width was.
            int width = pageWidth > 0 ? pageWidth : ViewGroup.LayoutParams.MATCH_PARENT;

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

            // CENTER_INSIDE, not CENTER_CROP: this is the one place a person
            // looks straight at a picture rather than past it on the way to
            // something else, and cropping a cover to a box shaped nothing
            // like it cost the pane a third of one game's width and the
            // bottom of its logo before this existed - see the grid's own
            // tiles, which crop deliberately for the opposite reason. Never
            // enlarges a small scrape past its own size either, which is the
            // other half of what a cropped-and-blown-up cover looked like.
            view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
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

            if (holder instanceof VideoHolder) {
                bindVideo((VideoHolder) holder, item.uri, position);
            } else {
                bindPicture((PictureHolder) holder, item.uri, position);
            }
        }

        private void bindPicture(PictureHolder holder, Uri picture, int position) {
            int token = ++holder.bindToken;

            holder.image.setImageDrawable(null);
            holder.image.setOnClickListener(v -> notifyTap(holder.getAdapterPosition()));

            new Thread(() -> {
                Bitmap decoded = decode(picture);

                handler.post(() -> {
                    if (holder.bindToken != token) return; // recycled meanwhile
                    holder.image.setImageBitmap(decoded);
                });
            }).start();
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

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
