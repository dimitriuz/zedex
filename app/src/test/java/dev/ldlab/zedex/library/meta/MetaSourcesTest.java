package dev.ldlab.zedex.library.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Who wrote a row, now that more than one thing can have.
 *
 * The field is one string and stays one string - an existing single-name row
 * has to go on reading correctly, which is the whole reason the list is
 * joined into the field that already exists rather than stored beside it.
 */
public class MetaSourcesTest {

    @Test
    public void anOldSingleNameRowReadsAsOneContributor() {
        Meta one = Meta.at("./A.tap").source("ZXInfo").build();

        assertEquals(Collections.singletonList("ZXInfo"), one.sources());
    }

    @Test
    public void aRowWithNoSourceHasNoContributors() {
        assertEquals(Collections.emptyList(), Meta.at("./A.tap").build().sources());
    }

    @Test
    public void contributorsAreKeptInTheOrderTheyContributed() {
        Meta two = Meta.at("./A.tap")
                .contributor("ZXInfo").contributor("ScreenScraper").build();

        assertEquals(Arrays.asList("ZXInfo", "ScreenScraper"), two.sources());
        assertEquals("ZXInfo, ScreenScraper", two.source);
    }

    @Test
    public void aContributorIsNeverListedTwice() {
        Meta twice = Meta.at("./A.tap")
                .contributor("ZXInfo").contributor("ZXInfo").build();

        assertEquals(Collections.singletonList("ZXInfo"), twice.sources());
    }

    /** A link may replace what only it wrote, and nothing else. */
    @Test
    public void aLinkOwnsARowOnlyWhenEsdeIsTheSoleContributor() {
        assertTrue(Meta.at("./A.tap").build().isEsde());
        assertTrue(Meta.at("./A.tap").source(Meta.ESDE).build().isEsde());

        assertFalse(Meta.at("./A.tap")
                        .contributor(Meta.ESDE).contributor("ZXInfo").build().isEsde());
    }

    /** Among the contributors, not the only one: a scraped row somebody then
     *  corrected is still theirs. */
    @Test
    public void aHandEditCountsHoweverManyOthersContributed() {
        assertTrue(Meta.at("./A.tap").source(Meta.USER).build().isMine());
        assertTrue(Meta.at("./A.tap")
                       .contributor("ZXInfo").contributor(Meta.USER).build().isMine());

        assertFalse(Meta.at("./A.tap").contributor("ZXInfo").build().isMine());
    }

    /** Editing a field adds the user to the row rather than erasing the
     *  record of which services were asked. */
    @Test
    public void editingAFieldAddsTheUserAndKeepsTheRest() {
        Meta scraped = Meta.at("./A.tap").contributor("ZXInfo").genre("Action").build();

        Meta edited = scraped.with(Meta.Field.GENRE, "Puzzle");

        assertEquals(Arrays.asList("ZXInfo", Meta.USER), edited.sources());
        assertEquals("Puzzle", edited.genre);
    }
}
