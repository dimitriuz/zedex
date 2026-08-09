package dev.ldlab.zedex.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * The recents list, and the grant rule that two entries of one zip depend on.
 *
 * 11.3's last row. It was written off as needing Robolectric because it leans
 * on {@code Uri.parse}, which the stub android.jar answers null for - true of
 * the JVM and irrelevant here, the same mistake {@code Hotkeys.Bindings} was
 * written off with. A device has real Uris.
 *
 * The rule worth the file is the one the class comment marks in bold and
 * argues twice: <b>an entry inside a zip is remembered by the archive's own
 * uri plus the path within it</b>, because a zip entry has no uri SAF can
 * address. Two rows can therefore share a uri, and every consequence follows
 * from that:
 *
 *  - they are two rows, not one, so opening the second must not replace the
 *    first the way opening the same plain file twice does;
 *  - dropping one must not drop the other;
 *  - and dropping one must not <em>release the grant</em> the other still
 *    needs, which is {@code referencedElsewhere} and the only part of this
 *    with a real cost attached - the app holds a limited number of persisted
 *    grants and a released one cannot be taken back without the picker.
 *
 * Named apart from the instrumentation {@code RecentsTest}, which drives the
 * same feature through the emulator's own Recent panel. This is the list
 * underneath it.
 *
 * A scratch preferences file: somebody's recents are not much to lose, but
 * they are theirs.
 */
@RunWith(AndroidJUnit4.class)
public class RecentsListTest {

    /** Nothing here is a real document, so no grant is ever really taken or
     *  given back - takePersistableUriPermission throws SecurityException for
     *  these and Recents logs it and carries on, which is exactly the one-off
     *  grant path it already handles. */
    private static final String ZIP = "content://test.zedex/games.zip";
    private static final String OTHER = "content://test.zedex/another.zip";

    private ContentResolver resolver;
    private SharedPreferences scratch;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        resolver = context.getContentResolver();
        scratch = context.getSharedPreferences("recents-test", Context.MODE_PRIVATE);
        scratch.edit().clear().commit();
    }

    @After
    public void tidyUp() {
        scratch.edit().clear().commit();
    }

    private void remember(String uri, String name, String inside) {
        Recents.remember(resolver, scratch, Uri.parse(uri), name, inside);
    }

    private List<String> names() {
        List<String> names = new ArrayList<>();
        for (Recents.Item item : Recents.all(scratch)) names.add(item.name);
        return names;
    }

    // --- plain files ------------------------------------------------------------

    @Test
    public void nothingRememberedIsAnEmptyList() {
        assertEquals(0, Recents.all(scratch).size());
    }

    @Test
    public void thenewestIsFirst() {
        remember("content://test.zedex/a", "A", null);
        remember("content://test.zedex/b", "B", null);

        assertEquals(java.util.Arrays.asList("B", "A"), names());
    }

    /** Opening the same file again moves it up rather than adding it twice. */
    @Test
    public void openingTheSameFileAgainMovesItUp() {
        remember("content://test.zedex/a", "A", null);
        remember("content://test.zedex/b", "B", null);
        remember("content://test.zedex/a", "A", null);

        assertEquals(java.util.Arrays.asList("A", "B"), names());
    }

    /** The list is bounded, and the oldest is what falls off. */
    @Test
    public void thelistStopsAtItsLimit() {
        for (int at = 0; at < Recents.LIMIT + 5; at++) {
            remember("content://test.zedex/game" + at, "Game " + at, null);
        }

        List<String> names = names();
        assertEquals(Recents.LIMIT, names.size());
        assertEquals("the newest should be at the top", "Game " + (Recents.LIMIT + 4),
                     names.get(0));
        assertTrue("an entry older than the limit survived",
                   !names.contains("Game 0"));
    }

    @Test
    public void forgettingTakesItOut() {
        remember("content://test.zedex/a", "A", null);
        remember("content://test.zedex/b", "B", null);

        Recents.forget(resolver, scratch, Uri.parse("content://test.zedex/a"));

        assertEquals(java.util.Arrays.asList("B"), names());
    }

    // --- two entries of one zip ------------------------------------------------------

    /**
     * Two games in one archive are two rows, even though they share a uri.
     *
     * The whole reason every comparison in the class is by uri <em>and</em>
     * entry. Compared by uri alone, opening the second game would look like
     * reopening the first and the list would show one row for a zip full of
     * games.
     */
    @Test
    public void twoEntriesOfOneZipAreTwoRows() {
        remember(ZIP, "Manic", "games/Manic.tap");
        remember(ZIP, "Jet Set", "games/JetSet.tap");

        assertEquals(2, Recents.all(scratch).size());
        assertEquals(java.util.Arrays.asList("Jet Set", "Manic"), names());
    }

    /** And reopening one of them moves that one, not both. */
    @Test
    public void reopeningOneEntryMovesOnlyThatOne() {
        remember(ZIP, "Manic", "games/Manic.tap");
        remember(ZIP, "Jet Set", "games/JetSet.tap");
        remember(ZIP, "Manic", "games/Manic.tap");

        assertEquals(java.util.Arrays.asList("Manic", "Jet Set"), names());
        assertEquals(2, Recents.all(scratch).size());
    }

    /**
     * Forgetting one entry leaves its sibling alone.
     *
     * The comment's own case: "a different entry of the same archive that
     * still opens fine is not dropped along with the one that does not". The
     * caller here is the panel dropping a row that would not open - which is
     * exactly when a zip has one bad entry and nine good ones.
     */
    @Test
    public void forgettingOneEntryLeavesItsSibling() {
        remember(ZIP, "Manic", "games/Manic.tap");
        remember(ZIP, "Jet Set", "games/JetSet.tap");

        Recents.forget(resolver, scratch, Uri.parse(ZIP), "games/Manic.tap");

        assertEquals(java.util.Arrays.asList("Jet Set"), names());
        assertEquals("games/JetSet.tap", Recents.all(scratch).get(0).inside);
    }

    /** Forgetting by uri alone means the plain-file row, not every entry of an
     *  archive with that uri - the two overloads have to stay distinguishable. */
    @Test
    public void forgettingAPlainFileDoesNotTouchArchiveEntriesOfTheSameUri() {
        remember(ZIP, "the zip itself", null);
        remember(ZIP, "Manic", "games/Manic.tap");

        Recents.forget(resolver, scratch, Uri.parse(ZIP));

        assertEquals(java.util.Arrays.asList("Manic"), names());
    }

    /**
     * A sibling still on the list keeps the archive's grant.
     *
     * {@code referencedElsewhere}, the part with a real cost: a released grant
     * cannot be taken back without sending the user to the picker again, and
     * an app holds a limited number. Nothing here can observe the grant
     * itself - these are not real documents, so none was ever taken - so what
     * is asserted is the decision's own input: the sibling is still in the
     * kept list when the drop happens, which is the condition
     * {@code referencedElsewhere} answers on. The row surviving is what says
     * so.
     */
    @Test
    public void asiblingStillListedIsWhatKeepsTheGrant() {
        remember(ZIP, "Manic", "games/Manic.tap");
        remember(ZIP, "Jet Set", "games/JetSet.tap");
        remember(OTHER, "Elsewhere", "a.tap");

        Recents.forget(resolver, scratch, Uri.parse(ZIP), "games/Manic.tap");

        boolean siblingStillThere = false;
        for (Recents.Item item : Recents.all(scratch)) {
            if (ZIP.equals(item.uri.toString())) siblingStillThere = true;
        }
        assertTrue("the sibling that keeps the grant alive is gone", siblingStillThere);
    }

    /** Pushing entries off the end of the list one at a time still leaves the
     *  last sibling of an archive holding its own row - the eviction loop
     *  compares by uri, so an off-by-one there would drop rows wholesale. */
    @Test
    public void evictionStopsAtTheLimitAndNoFurther() {
        remember(ZIP, "Manic", "games/Manic.tap");
        for (int at = 0; at < Recents.LIMIT - 1; at++) {
            remember("content://test.zedex/game" + at, "Game " + at, null);
        }

        assertEquals(Recents.LIMIT, Recents.all(scratch).size());
        assertTrue("the archive entry was evicted early", names().contains("Manic"));
    }

    // --- what a broken list does -------------------------------------------------------

    /** A stored list that will not parse reads as no recents rather than
     *  throwing on the way into the panel that shows them. */
    @Test
    public void arubbishStoredListReadsAsEmpty() {
        scratch.edit().putString(Recents.KEY_RECENTS, "not json at all").commit();

        assertEquals(0, Recents.all(scratch).size());
    }

    /** An entry written before {@code inside} existed reads back as a plain
     *  file, which is what the field's own comment promises. */
    @Test
    public void anEntryFromBeforeInsideExistedIsAPlainFile() {
        scratch.edit().putString(Recents.KEY_RECENTS,
                "[{\"name\":\"Old\",\"uri\":\"content://test.zedex/old\"}]").commit();

        List<Recents.Item> items = Recents.all(scratch);
        assertEquals(1, items.size());
        assertEquals("Old", items.get(0).name);
        assertNull("an entry with no stored inside should read as a plain file",
                   items.get(0).inside);
    }
}
