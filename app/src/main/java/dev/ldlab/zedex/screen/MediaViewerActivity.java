package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.ui.Gallery;
import dev.ldlab.zedex.view.SafeArea;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;

/**
 * One picture, or the video, as large as the window - reached by a tap on any
 * page of a {@link Gallery} in the pane or in {@code GameInfoActivity}, and
 * swipeable across the rest of that same gallery once here, so leaving on the
 * cover and arriving on the video is one gesture rather than a screen of its
 * own for each.
 *
 * The video is muted everywhere a {@link Gallery} appears except here - see
 * {@link Gallery}'s own class comment - so this is the one place a sound
 * button belongs, and the only page it belongs on: {@link #updateSoundButton}
 * hides it the moment the pager is not showing the video.
 *
 * Addressed by the game's path and a starting page rather than a list of
 * media {@code Uri}s, for the same reason {@code GameInfoActivity} takes a
 * path rather than a parsed {@link dev.ldlab.zedex.library.meta.Meta}: the
 * store and the artwork are both a lookup away on the other side, and a copy
 * carried through an Intent could go stale between being built and being
 * shown.
 */
public final class MediaViewerActivity extends ZedexActivity {

    /** The game's path relative to the content tree - {@link
     *  dev.ldlab.zedex.library.meta.Metadata#relativePath}. */
    public static final String EXTRA_PATH = "dev.ldlab.zedex.extra.MEDIA_PATH";

    /** Which page was tapped to get here - the gallery lands on it directly
     *  rather than always opening on the cover. */
    public static final String EXTRA_INDEX = "dev.ldlab.zedex.extra.MEDIA_INDEX";

    private Gallery gallery;
    private ImageButton soundButton;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // No title bar to put a label in - see page() - but the task
        // switcher still reads android:label off the manifest in the
        // phone's own language regardless, which is why every other screen
        // here sets one; there being nothing to set it to that shows on
        // screen is exactly why this one does not.
        if (getActionBar() != null) getActionBar().hide();

        String path = getIntent().getStringExtra(EXTRA_PATH);
        int index = getIntent().getIntExtra(EXTRA_INDEX, 0);

        setContentView(page());
        fitToSafeArea();

        if (path != null) gallery.load(path, index);
    }

    @Override
    protected void onPause() {
        super.onPause();

        // One of the three times a video must not be left running - see
        // CLAUDE.md - and the one screen here that is nothing but a video
        // when there is one to show at all.
        gallery.release();
    }

    /**
     * The gallery, filling the window, and the sound button floating over its
     * top corner rather than sharing a row with the (absent) dots' own
     * padding - there is no toolbar here for it to sit in, and a button that
     * only ever applies to one page of several does not deserve one built
     * just for it.
     */
    private View page() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);

        gallery = new Gallery(this);
        gallery.setPictureTargetPx(fullScreenTargetPx());

        // The one place a picture is looked straight at rather than flicked
        // past: a game map is unreadable at a phone's width, and a media scan
        // is opened to read the small print on a cassette.
        gallery.setZoomable(true);

        // A tap anywhere in the gallery puts it away again - the same
        // gesture the picture-only dialog this screen replaced answered to,
        // now true of the video page as well.
        gallery.setOnPageTapped(index -> finish());
        gallery.setOnPageChanged(this::updateSoundButton);

        root.addView(gallery, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        soundButton = new ImageButton(this);
        soundButton.setBackgroundColor(0x00000000);
        soundButton.setColorFilter(0xffffffff);
        soundButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        soundButton.setVisibility(View.GONE);
        soundButton.setOnClickListener(v -> toggleSound());

        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                pixels(48), pixels(48), Gravity.TOP | Gravity.END);
        buttonParams.topMargin = pixels(16);
        buttonParams.rightMargin = pixels(16);
        root.addView(soundButton, buttonParams);

        // A cross, top left, because Back cannot be relied on to leave this
        // screen. Measured on an AYN Thor Lite: that device drops Back for
        // every app on it - ours and Settings alike - until it is rebooted,
        // and this screen's other way out is a tap on the picture, which is
        // invisible until somebody guesses it and is a whole double-tap
        // timeout away besides, since a tap here has to be told apart from
        // the first tap of a pinch. See CLAUDE.md for the measurement.
        //
        // Same size and margin as the sound button opposite it, so the two
        // read as one pair rather than as two accidents.
        ImageButton close = new ImageButton(this);
        close.setBackgroundColor(0x00000000);
        close.setColorFilter(0xffffffff);
        close.setImageResource(R.drawable.ic_close);
        close.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        close.setContentDescription(getString(android.R.string.cancel));
        close.setOnClickListener(v -> finish());

        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                pixels(48), pixels(48), Gravity.TOP | Gravity.START);
        closeParams.topMargin = pixels(16);
        closeParams.leftMargin = pixels(16);
        root.addView(close, closeParams);

        return root;
    }

    /** Only on the video's own page - a picture has no sound to speak of,
     *  and CLAUDE.md's own rule against a description that changes on its
     *  own is about a state nobody asked to change; this one only moves
     *  because a person swiped, which is exactly what that rule allows. */
    private void updateSoundButton(int index) {
        boolean onVideo = index == gallery.videoIndex();
        soundButton.setVisibility(onVideo ? View.VISIBLE : View.GONE);
        if (onVideo) refreshSoundIcon();
    }

    private void toggleSound() {
        gallery.setMuted(!gallery.isMuted());
        refreshSoundIcon();
    }

    /**
     * The icon says what is true right now - a crossed speaker while muted -
     * which is the near-universal reading of that glyph and the one every
     * video app already trained people on. The description names what
     * tapping would do instead, the same way the toolbar's own view-mode
     * button is named for the shape switching to it gives rather than the
     * one on screen: a screen reader says "Mute" or "Unmute", not "sound is
     * currently off".
     */
    private void refreshSoundIcon() {
        boolean muted = gallery.isMuted();
        soundButton.setImageResource(muted ? R.drawable.ic_sound_off : R.drawable.ic_sound);
        soundButton.setContentDescription(getString(
                muted ? R.string.library_gallery_unmute : R.string.library_gallery_mute));
    }

    /** As large as the screen will draw a picture - {@code
     *  GameInfoActivity}'s own dialog decoded a picture at this same size for
     *  the same reason: the pane and the details screen both sample a cover
     *  down for a box a fraction of this one, and reusing that bitmap here
     *  would show it. */
    private int fullScreenTargetPx() {
        return Math.max(getResources().getDisplayMetrics().widthPixels,
                        getResources().getDisplayMetrics().heightPixels);
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
