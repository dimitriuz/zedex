package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.scrape.Blend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Choosing between the pictures several sources offered.
 *
 * Driven by calling the sheet directly rather than by scraping: a live
 * contested scrape needs two services to disagree about a game that happens to
 * be on the bench, which is not a fixture anybody can rely on. What is under
 * test is the sheet and what it commits, and both are the same either way.
 */
@RunWith(AndroidJUnit4.class)
public class ArtworkChoiceTest {

    private static final String PATH = "./zedex-sheet-test/Game.tap";
    private static final long TIMEOUT_MS = 5000;

    private Context context;
    private UiDevice device;
    private Activity activity;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        Artwork.clearStaging(context);
        clearMedia();

        activity = anyActivity();
    }

    @After
    public void tidyUp() {
        Artwork.clearStaging(context);
        clearMedia();
    }

    private void clearMedia() {
        for (String extension : Arrays.asList("png", "jpg")) {
            File file = Artwork.fileFor(context, PATH, "covers", extension);
            if (file.isFile()) file.delete();
        }
        Artwork.forget(PATH);
    }

    /**
     * An activity of ours to hang the dialog on.
     *
     * The library is the one that owns this sheet in the app, and any of ours
     * will do to show it - what is being tested is the dialog, not who
     * launched it.
     */
    private Activity anyActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                LibraryActivity.class.getName(), null, false);

        context.startActivity(
                new android.content.Intent(context, LibraryActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));

        Activity launched = instrumentation.waitForMonitorWithTimeout(monitor, TIMEOUT_MS);
        assertTrue("the library never came up", launched != null);
        return launched;
    }

    /** A one-colour picture, so two of them differ by more than their name. */
    private File picture(File into, int colour) throws IOException {
        File parent = into.getParentFile();
        if (parent != null) parent.mkdirs();

        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(colour);

        try (FileOutputStream out = new FileOutputStream(into)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return into;
    }

    /** Shows the sheet and answers with what it committed. */
    private List<Blend.Staged> showAndTap(List<Blend.Staged> staged, String tapThis,
                                          String thenPress) throws Exception {
        AtomicReference<List<Blend.Staged>> chosen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        activity.runOnUiThread(() -> ArtworkChoice.show(
                activity, "Game.tap", staged,
                taken -> {
                    chosen.set(taken);
                    done.countDown();
                }));

        if (tapThis != null) {
            UiObject2 tile = device.wait(Until.findObject(By.desc(tapThis)), TIMEOUT_MS);
            assertTrue("no tile described " + tapThis, tile != null);
            tile.click();
        }

        UiObject2 button = device.wait(Until.findObject(By.text(thenPress)), TIMEOUT_MS);
        assertTrue("no " + thenPress + " button", button != null);
        button.click();

        assertTrue("the sheet never answered", done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        return chosen.get();
    }

    private String save() {
        return context.getString(dev.ldlab.zedex.R.string.artwork_new_save);
    }

    private String yours() {
        return context.getString(dev.ldlab.zedex.R.string.artwork_new_yours);
    }

    /**
     * Save without touching anything keeps what was there.
     *
     * The safe answer is the default one, so somebody who does not understand
     * the question cannot lose a picture by pressing the obvious button.
     */
    @Test
    public void savingWithoutChoosingKeepsThePictureAlreadyThere() throws Exception {
        File mine = picture(Artwork.fileFor(context, PATH, "covers", "png"), Color.GREEN);
        File theirs = picture(Artwork.stagingFileFor(context, PATH, "covers/ZXInfo", "png"),
                              Color.RED);

        List<Blend.Staged> staged = Collections.singletonList(
                Blend.staged("covers", "png", "ZXInfo", theirs, true, mine));

        List<Blend.Staged> chosen = showAndTap(staged, null, save());

        assertTrue("something was committed without being chosen", chosen.isEmpty());
    }

    /**
     * The existing picture is the one drawn selected when there is one -
     * matching {@code savingWithoutChoosingKeepsThePictureAlreadyThere} above,
     * which is the promise this checks from the other side: what is drawn as
     * chosen and what Save actually keeps have to be the same tile.
     *
     * {@code isSelected()} rather than {@code contentDescription}, which
     * {@code ArtworkChoice.mark} explains: a description that changed on
     * every tap would be exactly the kind of continuous change that makes the
     * accessibility tree never settle.
     */
    @Test
    public void theExistingPictureStartsSelectedWhenThereIsOne() throws Exception {
        File mine = picture(Artwork.fileFor(context, PATH, "covers", "png"), Color.GREEN);
        File theirs = picture(Artwork.stagingFileFor(context, PATH, "covers/ZXInfo", "png"),
                              Color.RED);

        List<Blend.Staged> staged = Collections.singletonList(
                Blend.staged("covers", "png", "ZXInfo", theirs, true, mine));

        CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> ArtworkChoice.show(
                activity, "Game.tap", staged, taken -> done.countDown()));

        UiObject2 yours = device.wait(Until.findObject(By.desc(yours())), TIMEOUT_MS);
        assertTrue("no tile described " + yours(), yours != null);
        assertTrue("the existing picture must start selected - Save with "
                   + "nothing touched has to keep it", yours.isSelected());

        UiObject2 offer = device.wait(Until.findObject(By.desc("ZXInfo")), TIMEOUT_MS);
        assertTrue("no tile described ZXInfo", offer != null);
        assertFalse("only one tile may be selected at a time", offer.isSelected());

        UiObject2 cancel = device.wait(
                Until.findObject(By.text(context.getString(android.R.string.cancel))),
                TIMEOUT_MS);
        assertTrue("no Cancel button", cancel != null);
        cancel.click();

        assertTrue(done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    /** Choosing the new one installs it, and the old extension goes with it. */
    @Test
    public void choosingTheNewPictureReplacesTheOldOneAndItsExtension() throws Exception {
        File mine = picture(Artwork.fileFor(context, PATH, "covers", "png"), Color.GREEN);
        File theirs = picture(Artwork.stagingFileFor(context, PATH, "covers/ZXInfo", "jpg"),
                              Color.RED);

        List<Blend.Staged> staged = Collections.singletonList(
                Blend.staged("covers", "jpg", "ZXInfo", theirs, true, mine));

        List<Blend.Staged> chosen = showAndTap(staged, "ZXInfo", save());

        assertEquals(1, chosen.size());
        assertEquals("ZXInfo", chosen.get(0).source);

        assertEquals(1, Blend.commit(context, PATH, chosen));

        assertTrue(Artwork.fileFor(context, PATH, "covers", "jpg").isFile());
        assertFalse("the png it replaced is still there, and png outranks jpg",
                    Artwork.fileFor(context, PATH, "covers", "png").isFile());
    }

    /**
     * Every offer is on screen, named after the source that made it.
     *
     * Matched on {@code android.widget.ImageView}: a subclassed ImageView
     * still answers the framework class from getAccessibilityClassName, so a
     * selector built from the subclass finds nothing - which looks exactly
     * like a screen that failed to draw.
     */
    @Test
    public void everyOfferIsOnScreenAndNamedAfterItsSource() throws Exception {
        File mine = picture(Artwork.fileFor(context, PATH, "covers", "png"), Color.GREEN);
        File one = picture(Artwork.stagingFileFor(context, PATH, "covers/ZXInfo", "png"),
                           Color.RED);
        File two = picture(Artwork.stagingFileFor(context, PATH, "covers/ScreenScraper", "png"),
                           Color.BLUE);

        List<Blend.Staged> staged = Arrays.asList(
                Blend.staged("covers", "png", "ZXInfo", one, true, mine),
                Blend.staged("covers", "png", "ScreenScraper", two, true, mine));

        CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> ArtworkChoice.show(
                activity, "Game.tap", staged, taken -> done.countDown()));

        for (String who : Arrays.asList(yours(), "ZXInfo", "ScreenScraper")) {
            UiObject2 tile = device.wait(
                    Until.findObject(By.clazz("android.widget.ImageView").desc(who)),
                    TIMEOUT_MS);
            assertTrue("nothing on screen is described " + who, tile != null);
        }

        UiObject2 cancel = device.wait(
                Until.findObject(By.text(context.getString(android.R.string.cancel))),
                TIMEOUT_MS);
        assertTrue("no Cancel button", cancel != null);
        cancel.click();

        assertTrue(done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }
    /**
     * A manual has no picture, so it is not drawn as one.
     *
     * BitmapFactory answers null for a PDF, an mp4, a .pok and a .ay alike, so
     * the tile that used to be built for them was a 128dp square of nothing -
     * which reads as artwork that failed to load rather than as a file with no
     * picture in it, and four such folders filled a dialog that has to fit on
     * a phone in landscape with a keyboard under it.
     */
    @Test
    public void afolderWithNothingToShowIsNamedRatherThanDrawn() throws Exception {
        File mine = write(Artwork.fileFor(context, PATH, "manuals", "pdf"), "an older manual");
        File theirs = write(Artwork.stagingFileFor(context, PATH, "manuals/ZXInfo", "pdf"),
                            "a newer manual");

        List<Blend.Staged> staged = Collections.singletonList(
                Blend.staged("manuals", "pdf", "ZXInfo", theirs, true, mine));

        CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> ArtworkChoice.show(
                activity, "Game.tap", staged, taken -> done.countDown()));

        // Named: the source is on screen, and so is what is already there.
        for (String who : Arrays.asList(yours(), "ZXInfo")) {
            assertTrue("nothing on screen is described " + who,
                       device.wait(Until.findObject(By.desc(who)), TIMEOUT_MS) != null);
        }

        // Not drawn: no picture tile carries either name. A subclassed
        // ImageView still answers android.widget.ImageView, so this is the
        // selector that would find one if it were there.
        for (String who : Arrays.asList(yours(), "ZXInfo")) {
            assertNull("a manual was drawn as a picture, which is a square of "
                       + "nothing - " + who,
                       device.findObject(By.clazz("android.widget.ImageView").desc(who)));
        }

        UiObject2 cancel = device.wait(
                Until.findObject(By.text(context.getString(android.R.string.cancel))),
                TIMEOUT_MS);
        assertTrue("no Cancel button", cancel != null);
        cancel.click();

        assertTrue(done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    /** And it is still a choice - tapping the source's name and saving takes
     *  it, exactly as tapping a tile would. */
    @Test
    public void anamedFolderIsStillChosenByTapping() throws Exception {
        File mine = write(Artwork.fileFor(context, PATH, "manuals", "pdf"), "an older manual");
        File theirs = write(Artwork.stagingFileFor(context, PATH, "manuals/ZXInfo", "pdf"),
                            "a newer manual");

        List<Blend.Staged> staged = Collections.singletonList(
                Blend.staged("manuals", "pdf", "ZXInfo", theirs, true, mine));

        List<Blend.Staged> chosen = showAndTap(staged, "ZXInfo", save());

        assertEquals(1, chosen.size());
        assertEquals("ZXInfo", chosen.get(0).source);
    }

    /** Writes a file and answers it, for the media that are not pictures. */
    private File write(File into, String text) throws IOException {
        File parent = into.getParentFile();
        if (parent != null) parent.mkdirs();

        try (java.io.FileOutputStream out = new java.io.FileOutputStream(into)) {
            out.write(text.getBytes("UTF-8"));
        }
        return into;
    }
}
