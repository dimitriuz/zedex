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

    /**
     * Every field, in the right direction.
     *
     * The companion to {@link #everyFieldIsMerged}, and the half that one
     * cannot do: it builds an empty base, so "base wins unless null" and
     * "addition always wins" produce identical output for every field and only
     * presence is proved. This gives both sides a distinct value, so a field
     * whose arguments are the wrong way round is caught.
     */
    @Test
    public void everyFieldKeepsTheBasesOwnValue() throws Exception {
        Meta base = everythingSet("mine");
        Meta addition = everythingSet("theirs");

        Meta merged = Merge.of(base, addition);

        for (Field field : Meta.class.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers())
                    || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.getName().equals("path") || field.getName().equals("source")) continue;

            assertEquals("Merge.of takes " + field.getName() + " from the addition; "
                         + "the base's own value must win",
                         field.get(base), field.get(merged));
        }
    }

    @Test
    public void nullAdditionAnswersBaseUnchanged() {
        Meta base = Meta.at("./A.tap").name("Manic Miner").build();
        Meta merged = Merge.of(base, null);

        assertEquals(base, merged);
    }

    @Test
    public void nullBaseAnswersAddition() {
        Meta addition = Meta.at("./B.tap").name("Jet Set Willy").build();
        Meta merged = Merge.of(null, addition);

        assertEquals(addition, merged);
    }

    /** Every field of Meta with something in it. Kept beside the test that
     *  uses it so that adding a field here is the obvious fix when the test
     *  above says one is missing. */
    private static Meta everythingSet() {
        return everythingSet("");
    }

    /** Every field of Meta with something in it, tagged for distinctness. */
    private static Meta everythingSet(String tag) {
        String suffix = tag.isEmpty() ? "" : " " + tag;
        return Meta.at(null)
                .name("Manic Miner" + suffix).desc("A miner." + suffix)
                .developer("Matthew Smith" + suffix).publisher("Bug-Byte" + suffix)
                .genre("Arcade Game" + suffix).subgenre("Platform" + suffix)
                .released("19831001T000000" + suffix).players("1" + suffix).rating("0.9" + suffix)
                .keymap("0:left = q" + suffix).machine("ZX-Spectrum 48K" + suffix)
                .inputs(Collections.singletonList("Cursor" + suffix))
                .authors(Collections.singletonList("Matthew Smith" + suffix))
                .price("£5.95" + suffix).series("Miner Willy" + suffix)
                .seriesGames(Collections.singletonList(new Meta.Link("2", "Jet Set Willy" + suffix)))
                .compilations(Collections.singletonList(new Meta.Link("3", "They Sold a Million" + suffix)))
                .contents(Collections.singletonList(new Meta.Link("4", "Something" + suffix)))

                // Neither takes a tag: one is a count and the other a flag,
                // and " mine" on the end of either is not a value the app or
                // ES-DE would ever write. The two are told apart by their
                // values instead - see thebasesOwnValuesWin, which is what the
                // tag is for elsewhere.
                .playCount(tag.isEmpty() ? "7" : String.valueOf(3 + tag.length()))
                .completed(tag.isEmpty() || tag.equals("mine") ? "true" : "false")
                .build();
    }
}
