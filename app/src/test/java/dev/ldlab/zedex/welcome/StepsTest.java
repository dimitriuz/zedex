package dev.ldlab.zedex.welcome;

import dev.ldlab.zedex.FakePreferences;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Which pages the wizard shows, and in what order.
 *
 * Asked afresh on every move rather than settled at the start: whether the
 * archive page applies depends on what the folders page answered, and a list
 * built before that was answered cannot know.
 */
public class StepsTest {

    private static final boolean CATALOGUE = true;
    private static final boolean NO_CATALOGUE = false;

    private FakePreferences withFolder() {
        return new FakePreferences().with(Storage.KEY_CONTENT_TREE,
                "content://com.android.externalstorage.documents/tree/primary%3AROMs");
    }

    @Test
    public void everythingApplies() {
        List<Page> pages = Steps.applicable(withFolder(), CATALOGUE);

        assertEquals(java.util.Arrays.asList(
                Page.WELCOME, Page.FOLDERS, Page.MACHINE, Page.CONTROLS,
                Page.SCREEN, Page.LIBRARY, Page.SCRAPING, Page.DONE), pages);
    }

    /** Nothing to browse means nothing to offer: startsInLibrary is false
     *  without a content folder whatever the switch says, so the switch would
     *  be a setting that cannot take effect. */
    @Test
    public void noContentFolderDropsTheLibraryPage() {
        List<Page> pages = Steps.applicable(new FakePreferences(), CATALOGUE);

        assertFalse(pages.contains(Page.LIBRARY));
        assertTrue(pages.contains(Page.SCRAPING));
    }

    @Test
    public void noCatalogueDropsTheLibraryPage() {
        List<Page> pages = Steps.applicable(withFolder(), NO_CATALOGUE);

        assertFalse(pages.contains(Page.LIBRARY));
    }

    /** Whatever is dropped, the two ends stay: something has to ask the
     *  language and something has to finish. */
    @Test
    public void theFirstAndLastPagesAlwaysApply() {
        List<Page> pages = Steps.applicable(new FakePreferences(), NO_CATALOGUE);

        assertEquals(Page.WELCOME, pages.get(0));
        assertEquals(Page.DONE, pages.get(pages.size() - 1));
    }

    @Test
    public void afterSkipsWhatDoesNotApply() {
        FakePreferences none = new FakePreferences();

        assertEquals(Page.SCRAPING, Steps.after(Page.SCREEN, none, CATALOGUE));
        assertEquals(Page.LIBRARY,
                     Steps.after(Page.SCREEN, withFolder(), CATALOGUE));
    }

    @Test
    public void afterTheLastPageIsNothing() {
        assertNull(Steps.after(Page.DONE, withFolder(), CATALOGUE));
    }

    @Test
    public void beforeWalksBackThroughWhatApplies() {
        FakePreferences none = new FakePreferences();

        assertEquals(Page.SCREEN, Steps.before(Page.SCRAPING, none, CATALOGUE));
        assertEquals(Page.LIBRARY,
                     Steps.before(Page.SCRAPING, withFolder(), CATALOGUE));
    }

    @Test
    public void beforeTheFirstPageIsNothing() {
        assertNull(Steps.before(Page.WELCOME, withFolder(), CATALOGUE));
    }
}
