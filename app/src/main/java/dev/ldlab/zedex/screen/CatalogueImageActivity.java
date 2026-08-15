package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.work.Work;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * One catalogue image, as large as the window - a tap on any picture in
 * {@code CatalogueDetailsActivity}'s own gallery. Not {@link
 * MediaViewerActivity}: that one is addressed by a game's path and reads
 * local files through {@link dev.ldlab.zedex.library.ui.Gallery}, and there
 * is no local file here at all - only a url, fetched fresh, the same way
 * {@code CataloguePane}'s cover already is.
 *
 * <b>Full size, deliberately not {@code Thumbnails}.</b> That cache is keyed
 * on the url alone and decoded once and for all at 140dp - see its own class
 * comment on the measured cost of decoding a second size into the same key -
 * so asking it for a bigger bitmap here would either hand back the small one
 * or corrupt the cache for every row that url still has on screen. This
 * fetches and decodes its own copy, sized for this window and never kept
 * anywhere the grid could find it.
 *
 * <b>A gif plays, rather than showing its first frame.</b> {@link
 * ImageDecoder} answers an {@link AnimatedImageDrawable} for one, which an
 * {@link ImageView} draws like any other {@code Drawable} once {@link
 * AnimatedImageDrawable#start()} is called - nothing here has to decode
 * frames or drive a timer itself.
 */
public final class CatalogueImageActivity extends ZedexActivity {

    private static final String TAG = "Zedex";

    /** The picture's own url - see {@code Catalogue.Item#images()}. */
    public static final String EXTRA_URL = "dev.ldlab.zedex.extra.CATALOGUE_IMAGE_URL";

    private ImageView image;
    private ProgressBar spinner;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        if (getActionBar() != null) getActionBar().hide();

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) {
            finish();
            return;
        }

        setContentView(build());
        fitToSafeArea();

        load(url);
    }

    /** Black, filling the window, closing on a tap anywhere - the same
     *  convention {@code MediaViewerActivity} already uses for one picture. */
    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        root.setOnClickListener(v -> finish());

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setOnClickListener(v -> finish());
        root.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        return root;
    }

    /**
     * Fetched to a cache file the same way {@code Thumbnails} fetches a
     * cover - {@code Http.save} - then decoded off the main thread, since a
     * full-size decode is exactly the slow step {@code Thumbnails} avoids by
     * never doing one.
     */
    private void load(String url) {
        Context app = getApplicationContext();
        boolean gif = isGif(url);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int targetW = metrics.widthPixels;
        int targetH = metrics.heightPixels;

        Work.alone("catalogue-image", () -> {
            File file = new File(app.getCacheDir(), "catalogue-image-" + System.nanoTime());
            Drawable drawable = null;
            Bitmap bitmap = null;
            boolean failed = false;

            try {
                new Http.Real(app).save(url, file);

                if (gif) {
                    drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file));
                } else {
                    bitmap = decodeForWindow(file, targetW, targetH);
                    if (bitmap == null) failed = true;
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "cannot show " + url, e);
                failed = true;
            } finally {
                file.delete();
            }

            Drawable readyDrawable = drawable;
            Bitmap readyBitmap = bitmap;
            boolean readyFailed = failed;
            Work.onMain(() -> show(readyDrawable, readyBitmap, readyFailed));
        });
    }

    /** Downsampled to this window rather than decoded at whatever size the
     *  file happens to be - an uncapped decode of a large scan is the same
     *  out-of-memory mistake {@code Thumbnails} and {@code PictureCache}
     *  both guard against, just at a bigger size than either ever asks for. */
    private static Bitmap decodeForWindow(File file, int targetW, int targetH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetW
                && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private void show(Drawable drawable, Bitmap bitmap, boolean failed) {
        spinner.setVisibility(View.GONE);

        if (failed || (drawable == null && bitmap == null)) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (drawable != null) {
            image.setImageDrawable(drawable);
            if (drawable instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) drawable).start();
            }
        } else {
            image.setImageBitmap(bitmap);
        }
    }

    /** The one signal this app has for "plays rather than sits still" - see
     *  {@code CatalogueDetailsActivity.isGif}, which decides the same way
     *  for the play badge on the gallery's own thumbnail. */
    static boolean isGif(String url) {
        int dot = url.lastIndexOf('.');
        return dot >= 0 && url.substring(dot + 1).toLowerCase(Locale.ROOT).startsWith("gif");
    }
}
