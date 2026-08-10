package dev.ldlab.zedex;

import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.screen.MediaViewerActivity;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Pinching into a picture, and handing the pager back its gestures.
 *
 * A game map is two thousand pixels across, and a phone shows it as a grey
 * smudge - so the folder is worth nothing without this. The arithmetic is
 * {@code ZoomTest}, on the JVM; what needs a device is the part that cannot be
 * reasoned about, which is who gets a gesture.
 *
 * <b>Two ways to get this wrong, and both have happened.</b> Consume every
 * touch and the viewer can no longer be dismissed - it closes on a tap, and
 * swallowing that shipped a picture with no way out but the back button. Keep
 * no gestures and a zoomed map cannot be dragged at all. So both are asserted
 * here rather than the zoom itself, which has no handle to read.
 *
 * The picture is one this test draws and puts in the media folder, so the run
 * does not depend on what anybody has scraped.
 */
@RunWith(AndroidJUnit4.class)
public class ZoomViewerTest {

    /** A game that need not exist as a file: the viewer resolves media by the
     *  store's own key, and nothing here opens it. */
    private static final String PATH = "./uitest-zoom.tap";

    /** Far wider than any phone, so there is always somewhere to drag to. */
    private static final int WIDE = 3000;
    private static final int HIGH = 2000;

    private static final long SETTLES = 600;

    private UiDevice device;
    private Context context;
    private File picture;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();
        device = UiDevice.getInstance(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation());

        File root = Storage.root(context);
        assumeTrue("the data folder is not usable on this device: " + root,
                   root.isDirectory() && Storage.isWritable(root));

        picture = Artwork.fileFor(context, PATH, "maps", "png");
        draw(picture);
        Artwork.forget(PATH);

        open();
    }

    @After
    public void tearDown() {
        device.pressBack();

        if (picture != null) picture.delete();
        Artwork.forget(PATH);
    }

    /**
     * Zoomed in, a drag moves the picture rather than the pager.
     *
     * The pager is the only thing that could take it, and there is exactly
     * one page here - so what this really asserts is that the picture is
     * still on screen and still being dragged rather than flung away, which
     * is what a gesture going to the wrong place looks like.
     */
    @Test
    public void azoomedPictureKeepsTheDragAndTheViewerStaysUp() {
        UiObject2 page = picturePage();
        assertNotNull("nothing to pinch: the viewer showed no picture", page);

        page.pinchOpen(0.75f);
        SystemClock.sleep(SETTLES);

        assertTrue("the viewer went away during a pinch", showing());

        device.swipe(device.getDisplayWidth() / 4, device.getDisplayHeight() / 2,
                     device.getDisplayWidth() * 3 / 4, device.getDisplayHeight() / 2, 20);
        SystemClock.sleep(SETTLES);

        assertTrue("dragging a zoomed picture closed the viewer", showing());
    }

    /**
     * And a single tap still puts it away.
     *
     * The regression this exists for: the viewer closes on a tap, the zoom
     * handler consumed every touch, and a picture became something you could
     * only leave with the back button. Nothing about that is visible from a
     * JVM test, and it survived being looked at.
     */
    @Test
    public void asingleTapStillDismissesTheViewer() {
        assertNotNull(picturePage());

        assertTrue("the viewer is not up to be dismissed", viewerIsUp());

        device.click(device.getDisplayWidth() / 2, device.getDisplayHeight() / 2);

        // The tap is answered on confirmation, so nothing happens for the
        // double-tap timeout first - see ZoomableImageView.Tapping.
        for (long waited = 0; waited < 4000 && viewerIsUp(); waited += 200) {
            SystemClock.sleep(200);
        }

        assertFalse("a tap no longer closes the viewer", viewerIsUp());
    }

    // --- the world this needs ------------------------------------------------

    private void open() {
        Intent intent = new Intent(context, MediaViewerActivity.class)
                .putExtra(MediaViewerActivity.EXTRA_PATH, PATH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        context.startActivity(intent, Screen.here());
        SystemClock.sleep(2 * SETTLES);
    }

    /**
     * The page itself.
     *
     * <b>Matched as an {@code ImageView} and not as its own class.</b>
     * {@code ImageView.getAccessibilityClassName} returns
     * {@code android.widget.ImageView} whatever it is subclassed into, so the
     * tree never carries the real name and a selector built from it finds
     * nothing at all - which reads exactly like a viewer that failed to draw.
     * The sound button is an {@code ImageButton} and reports that, so this
     * still means the picture and only the picture.
     */
    private UiObject2 picturePage() {
        return device.wait(Until.findObject(By.clazz("android.widget.ImageView")), 8000);
    }

    private boolean showing() {
        return device.hasObject(By.clazz("android.widget.ImageView"));
    }

    /**
     * Whether the viewer itself is still in front.
     *
     * Asked of the activity registry rather than of the screen. "No picture
     * on screen" is not the same question and answers wrongly here: what is
     * behind the viewer is a launcher full of icons, and every one of them is
     * an {@code ImageView} - so a viewer that closed perfectly still looks
     * like one that did not.
     */
    private boolean viewerIsUp() {
        boolean[] up = { false };

        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> {
                    for (android.app.Activity activity
                            : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                                    .getInstance().getActivitiesInStage(
                                            androidx.test.runner.lifecycle.Stage.RESUMED)) {
                        if (activity instanceof MediaViewerActivity) up[0] = true;
                    }
                });

        return up[0];
    }

    /**
     * A picture with something in it, since a flat colour cannot be seen to
     * have moved and an empty file will not decode at all.
     */
    private static void draw(File into) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(WIDE, HIGH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();

        canvas.drawColor(Color.DKGRAY);

        for (int at = 0; at < WIDE; at += 200) {
            paint.setColor(at % 400 == 0 ? Color.WHITE : Color.RED);
            canvas.drawRect(at, 0, at + 100, HIGH, paint);
        }

        try (FileOutputStream out = new FileOutputStream(into)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
}
