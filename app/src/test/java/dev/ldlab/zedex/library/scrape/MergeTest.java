package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One rule for every field: a later source may fill a gap and may never
 * overwrite.
 *
 * That single rule is what makes scraping from several services safe, and it
 * is why there is no rule per field to get wrong - see the design note, and
 * the paragraph in Scrapers that this reverses.
 */
public class MergeTest {

    @Test
    public void whatTheBaseHasIsKept() {
        Meta base = Meta.at("./A.tap").name("Manic Miner").genre("Arcade").build();
        Meta addition = Meta.at(null).name("MANIC MINER").genre("Platform").build();

        Meta merged = Merge.of(base, addition);

        assertEquals("Manic Miner", merged.name);
        assertEquals("Arcade", merged.genre);
    }

    @Test
    public void whatTheBaseLacksIsTakenFromTheAddition() {
        Meta base = Meta.at("./A.tap").name("Manic Miner").build();
        Meta addition = Meta.at(null).publisher("Bug-Byte").price("£5.95").build();

        Meta merged = Merge.of(base, addition);

        assertEquals("Manic Miner", merged.name);
        assertEquals("Bug-Byte", merged.publisher);
        assertEquals("£5.95", merged.price);
    }

    /** Empty is absent for a list, which is the one place the two differ. */
    @Test
    public void anEmptyListIsAGapAndANonEmptyOneIsNot() {
        Meta base = Meta.at("./A.tap").inputs(Collections.emptyList()).build();
        Meta addition = Meta.at(null).inputs(Arrays.asList("Kempston", "Cursor")).build();

        assertEquals(Arrays.asList("Kempston", "Cursor"), Merge.of(base, addition).inputs);

        Meta held = Meta.at("./A.tap").inputs(Collections.singletonList("Sinclair 1")).build();

        assertEquals(Collections.singletonList("Sinclair 1"), Merge.of(held, addition).inputs);
    }

    @Test
    public void thePathAndTheContributorsAreTheBasesOwn() {
        Meta base = Meta.at("./A.tap").contributor("ZXInfo").build();
        Meta addition = Meta.at("./somewhere-else.tap").source("ScreenScraper").build();

        Meta merged = Merge.of(base, addition);

        assertEquals("./A.tap", merged.path);
        assertEquals(Collections.singletonList("ZXInfo"), merged.sources());
    }

    /**
     * Every field, without naming them.
     *
     * Meta grew from eight fields to twenty and will grow again, and a field
     * added there and forgotten here would silently never merge - the exact
     * failure Meta's own class doc was written about, and one nothing else
     * would catch: the store would simply never carry that field for anybody
     * whose first source did not have it.
     *
     * So this walks the class rather than a list. path and source are
     * excluded because Blend sets both itself.
     */
    @Test
    public void everyFieldIsMerged() throws Exception {
        Meta base = Meta.at("./A.tap").build();
        Meta addition = everythingSet();

        Meta merged = Merge.of(base, addition);

        for (Field field : Meta.class.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers())
                    || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.getName().equals("path") || field.getName().equals("source")) continue;

            Object value = field.get(merged);
            assertNotNull("Merge.of does not carry " + field.getName()
                          + " - add it to the method", value);

            if (value instanceof List) {
                assertTrue("Merge.of does not carry the list " + field.getName(),
                           !((List<?>) value).isEmpty());
            }
        }
    }

    /** Every field of Meta with something in it. Kept beside the test that
     *  uses it so that adding a field here is the obvious fix when the test
     *  above says one is missing. */
    private static Meta everythingSet() {
        return Meta.at(null)
                .name("Manic Miner").desc("A miner.")
                .developer("Matthew Smith").publisher("Bug-Byte")
                .genre("Arcade Game").subgenre("Platform")
                .released("19831001T000000").players("1").rating("0.9")
                .keymap("0:left = q").machine("ZX-Spectrum 48K")
                .inputs(Collections.singletonList("Cursor"))
                .authors(Collections.singletonList("Matthew Smith"))
                .price("£5.95").series("Miner Willy")
                .seriesGames(Collections.singletonList(new Meta.Link("2", "Jet Set Willy")))
                .compilations(Collections.singletonList(new Meta.Link("3", "They Sold a Million")))
                .contents(Collections.singletonList(new Meta.Link("4", "Something")))
                .build();
    }
}
