package dev.ldlab.zedex.library.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.ldlab.zedex.library.catalogue.Catalogue;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The three rules a catalogue row is drawn by, pinned.
 *
 * All three are pure functions of one argument, and nothing asserted any of
 * them until now. <b>Two of the three came out of live requests</b> - the
 * counting cap, and the absent-availability field that {@code greyed} exists
 * for - and those are worth a test because the measurement cost a request and
 * cannot be repeated cheaply. The third, {@code facts}, is not a measurement
 * at all: what a row prints under its name is a layout decision made here, and
 * it is pinned only so that it and {@code Item.describe()} cannot quietly
 * become the same string again.
 *
 * On a device rather than the JVM because {@link CatalogueAdapter} is a
 * {@code RecyclerView.Adapter} - loading it at all wants the real framework,
 * not the stub android.jar, which answers null under {@code
 * returnDefaultValues} and would let an assertion pass against a value never
 * computed. Nothing here draws anything, makes a request or needs a screen.
 */
@RunWith(AndroidJUnit4.class)
public class CatalogueRowsTest {

    /**
     * Only the fields each rule reads; the rest is what a real row carries and
     * neither rule looks at.
     */
    private static Catalogue.Item item(String year, String publisher, String availability) {
        return new Catalogue.Item("2259", "Manic Miner", year, publisher,
                                  "Arcade Game", availability, null, null);
    }

    // --- the count -----------------------------------------------------------------

    /**
     * A real total is printed; ten thousand exactly is not.
     *
     * <b>Ten thousand is Elasticsearch's counting cap</b>, measured in Task 5
     * against a database of about 39,666 entries: an unfiltered search answers
     * {@code total=10000} because that is at once the default limit on
     * counting and about as deep as a paged search may go. It is the right
     * number to page against and a lie to print - "10,000 results" for a search
     * that matched four times as many reads as a broken filter.
     */
    @Test
    public void thecountingCapIsNotPrintedAsAcount() {
        // Three digits, so no locale's grouping separator is involved and this
        // says the same thing on any bench.
        assertEquals("117", CatalogueAdapter.countLabel(117));

        // The row below the cap still counts. Grouped, so only that it is
        // there and carries the digits is asserted - which separator a locale
        // uses is the locale's business.
        String below = CatalogueAdapter.countLabel(9_999);
        assertNotNull("a total below the cap is a real count and must be shown", below);
        assertTrue("9,999 should read as nine thousand nine hundred and ninety-nine, not "
                   + below, below.startsWith("9"));

        assertNull("exactly 10000 is the cap wearing a count's clothes",
                   CatalogueAdapter.countLabel(10_000));
        assertNull("past the cap is the cap too", CatalogueAdapter.countLabel(10_001));

        assertNull("a shelf that cannot count says nothing",
                   CatalogueAdapter.countLabel(Catalogue.Page.UNKNOWN_TOTAL));
    }

    // --- the greying ---------------------------------------------------------------

    /**
     * Greyed on a stated refusal, and <b>never on an absent one</b>.
     *
     * The third case is the one that matters and is the reason this predicate
     * is not {@link Catalogue.Item#available()}: measured on a live ZXInfo
     * reply during Task 5, one row of the first three omitted {@code
     * availability} entirely, and it was a 2024 release. That one answers
     * false to "is it definitely available" - correctly, for that question -
     * so greying by it would tell somebody a game they can have is missing,
     * and then have no reason to show beside it, because the reason is the
     * field that was absent.
     */
    @Test
    public void onlyAstatedRefusalGreysArow() {
        assertFalse("\"Available\" is available",
                    CatalogueAdapter.greyed(item("1983", "Bug-Byte Software Ltd", "Available")));

        assertTrue("a stated refusal greys, and its own words are the reason",
                   CatalogueAdapter.greyed(item("1984", "Software Projects Ltd",
                                                "Distribution denied - still for sale")));

        assertFalse("an absent availability is a third answer and must not grey",
                    CatalogueAdapter.greyed(item("2024", "Somebody New", null)));

        // The two questions, side by side on the same row, answering
        // differently - which is the whole point of there being two.
        assertFalse(item("2024", "Somebody New", null).available());
    }

    // --- the detail line -----------------------------------------------------------

    /** The year and the publisher, whichever are known, and nothing when
     *  neither is. Not {@code describe()}, which puts the title back in front
     *  of facts drawn under a title. */
    @Test
    public void theDetailLineIsTheYearAndThePublisher() {
        assertEquals("1983 · Bug-Byte Software Ltd",
                     CatalogueAdapter.facts(item("1983", "Bug-Byte Software Ltd", "Available")));

        assertEquals("1983", CatalogueAdapter.facts(item("1983", null, null)));
        assertEquals("Bug-Byte Software Ltd",
                     CatalogueAdapter.facts(item(null, "Bug-Byte Software Ltd", null)));

        assertEquals("", CatalogueAdapter.facts(item(null, null, null)));

        // Empty is treated as unknown, since that is what a trimmed-to-nothing
        // field arrives as - not as a fact worth a separator of its own.
        assertEquals("1983", CatalogueAdapter.facts(item("1983", "", null)));
    }
}
