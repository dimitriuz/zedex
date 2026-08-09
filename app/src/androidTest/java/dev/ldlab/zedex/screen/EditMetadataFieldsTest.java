package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.library.meta.Meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What the editor decides to write, and - more to the point - what it decides
 * to leave alone.
 *
 * Six of the eight fields are the string they are shown as and there is
 * nothing to get wrong. Two are not, and both would be quietly corrupted by an
 * editor that wrote back whatever was on screen:
 *
 *  - {@code released} is an ES-DE stamp, {@code 20201218T000000}, of which
 *    only the year is displayed anywhere in this app. Written back from
 *    "2020" it becomes {@code 20200101T000000} - the day and the month gone,
 *    on a game somebody opened to fix a typo in the name.
 *  - {@code rating} is a fraction from 0 to 1 shown as stars out of five.
 *    {@code 0.9} displayed as "4.5" and written back as {@code 4.5 / 5} is
 *    {@code 0.9000001}. {@code Meta.rating}'s own comment is explicit that the
 *    value is kept as the string it arrived as "so that a value this app does
 *    not understand survives a link and a write unchanged instead of being
 *    rounded into something else".
 *
 * Neither failure is visible. The game keeps working, the pane keeps showing
 * the same year and the same stars, and the damage only shows up as a
 * difference from what ES-DE has if anyone ever compares.
 *
 * Instrumentation rather than JVM only because {@code Meta.stars} formats with
 * the default locale; everything under test is otherwise pure.
 */
@RunWith(AndroidJUnit4.class)
public class EditMetadataFieldsTest {

    private static Meta scraped(String released, String rating) {
        return Meta.at("./a.tap")
                .name("A name").desc("A description")
                .developer("Ocean").publisher("Ocean")
                .genre("Platform").released(released).players("1-2").rating(rating)
                .source(Meta.ESDE)
                .build();
    }

    private static String stored(Meta original, Meta.Field field, String typed) {
        return EditMetadataActivity.storedValue(original, field, typed);
    }

    // --- what is shown ----------------------------------------------------------

    /** Only the year of the stamp, because that is all the app ever shows. */
    @Test
    public void thedateIsShownAsAYear() {
        assertEquals("2020", EditMetadataActivity.shownValue(
                scraped("20201218T000000", null), Meta.Field.RELEASED));
    }

    /** And the fraction as stars, because that is what the pane shows. */
    @Test
    public void theratingIsShownOutOfFive() {
        assertEquals("4.5", EditMetadataActivity.shownValue(
                scraped(null, "0.9"), Meta.Field.RATING));
    }

    /** A game with neither shows empty boxes rather than "null". */
    @Test
    public void whatIsNotThereIsShownAsNothing() {
        assertEquals("", EditMetadataActivity.shownValue(
                scraped(null, null), Meta.Field.RELEASED));
        assertEquals("", EditMetadataActivity.shownValue(
                scraped(null, null), Meta.Field.RATING));
        assertEquals("", EditMetadataActivity.shownValue(null, Meta.Field.NAME));
    }

    // --- the two that must not be rewritten -------------------------------------

    /**
     * A year left alone writes nothing, so the day and month survive.
     *
     * The whole point. Somebody opens a game to correct its name; every other
     * box is redisplayed and re-read on the way past, and this is what stops
     * that being a write.
     */
    @Test
    public void anUntouchedYearLeavesTheFullDateAlone() {
        Meta game = scraped("20201218T000000", null);

        assertNull("the editor would have flattened 20201218 to 20200101",
                   stored(game, Meta.Field.RELEASED, "2020"));
    }

    /** And an untouched rating leaves the exact fraction alone. */
    @Test
    public void anUntouchedRatingLeavesTheFractionAlone() {
        Meta game = scraped(null, "0.9");

        assertNull("the editor would have rewritten 0.9 as 0.9000",
                   stored(game, Meta.Field.RATING, "4.5"));
    }

    /** A year actually changed is written, and as a stamp Fuse's own reader
     *  and ES-DE both understand. */
    @Test
    public void achangedYearIsWrittenAsAStamp() {
        Meta game = scraped("20201218T000000", null);

        assertEquals("19840101T000000", stored(game, Meta.Field.RELEASED, "1984"));
    }

    /** A changed rating is written back as the fraction. */
    @Test
    public void achangedRatingIsWrittenAsAFraction() {
        Meta game = scraped(null, "0.9");

        assertEquals("0.6000", stored(game, Meta.Field.RATING, "3.0"));
    }

    /** Cleared means cleared - the empty string, which {@code Meta.with}
     *  stores as nothing at all. */
    @Test
    public void aclearedFieldIsWrittenAsEmpty() {
        Meta game = scraped("20201218T000000", "0.9");

        assertEquals("", stored(game, Meta.Field.RELEASED, ""));
        assertEquals("", stored(game, Meta.Field.RATING, ""));
        assertEquals("", stored(game, Meta.Field.NAME, ""));
    }

    /**
     * A typo writes nothing rather than something wrong.
     *
     * There is no error to show on a field somebody may still be typing into,
     * and a half-typed year turning into a real stored date would be worse
     * than the edit not taking.
     */
    @Test
    public void anonsenseYearIsNotWritten() {
        Meta game = scraped("20201218T000000", null);

        assertNull(stored(game, Meta.Field.RELEASED, "19"));
        assertNull(stored(game, Meta.Field.RELEASED, "nineteen"));
        assertNull(stored(game, Meta.Field.RELEASED, "20201218"));
    }

    /** And a rating outside the five stars it is asked for. */
    @Test
    public void anonsenseRatingIsNotWritten() {
        Meta game = scraped(null, "0.9");

        assertNull(stored(game, Meta.Field.RATING, "9"));
        assertNull(stored(game, Meta.Field.RATING, "-1"));
        assertNull(stored(game, Meta.Field.RATING, "lots"));
    }

    /** Both ends of the scale are allowed - nought stars is a real opinion
     *  and five is the top of the scale, and an off-by-one here refuses them. */
    @Test
    public void thelimitsOfTheScaleAreAllowed() {
        Meta game = scraped(null, "0.9");

        assertEquals("0.0000", stored(game, Meta.Field.RATING, "0"));
        assertEquals("1.0000", stored(game, Meta.Field.RATING, "5"));
    }

    // --- the six that are just strings --------------------------------------------

    @Test
    public void anUntouchedPlainFieldWritesNothing() {
        Meta game = scraped(null, null);

        assertNull(stored(game, Meta.Field.NAME, "A name"));
        assertNull(stored(game, Meta.Field.DEVELOPER, "Ocean"));
        assertNull(stored(game, Meta.Field.GENRE, "Platform"));
    }

    @Test
    public void achangedPlainFieldIsWrittenAsTyped() {
        Meta game = scraped(null, null);

        assertEquals("Another name", stored(game, Meta.Field.NAME, "Another name"));
        assertEquals("Imagine", stored(game, Meta.Field.DEVELOPER, "Imagine"));
    }

    // --- a game the store has never heard of -----------------------------------------

    /**
     * Everything typed against no stored game at all is a change.
     *
     * How a game ES-DE never scraped gets described here: there is nothing to
     * compare against, so every filled box is written and every empty one
     * stays empty.
     */
    @Test
    public void everythingTypedAgainstNoGameIsAChange() {
        assertEquals("Typed in", stored(null, Meta.Field.NAME, "Typed in"));
        assertEquals("19840101T000000", stored(null, Meta.Field.RELEASED, "1984"));
        assertEquals("0.8000", stored(null, Meta.Field.RATING, "4"));

        assertNull("an empty box against no game is not a change",
                   stored(null, Meta.Field.NAME, ""));
    }

    // --- and what Meta.with does with the answer ---------------------------------------

    /** Storing a field makes the row the user's own, which is what a link then
     *  leaves alone. */
    @Test
    public void changingAnythingMakesTheRowMine() {
        Meta game = scraped(null, null);

        assertEquals(Meta.USER, game.with(Meta.Field.NAME, "Mine").source);
        assertEquals("Mine", game.with(Meta.Field.NAME, "Mine").name);
    }

    /** An empty value clears the field rather than storing an empty string,
     *  so the pane's "gone rather than empty" tests still work. */
    @Test
    public void anEmptyValueClearsTheField() {
        Meta game = scraped(null, null);

        assertNull(game.with(Meta.Field.GENRE, "").genre);
    }

    /** And nothing else moves - the whole reason `with` exists rather than a
     *  ten-argument constructor at each call site. */
    @Test
    public void changingOneFieldLeavesTheOthers() {
        Meta changed = scraped("20201218T000000", "0.9").with(Meta.Field.NAME, "Mine");

        assertEquals("A description", changed.desc);
        assertEquals("Ocean", changed.developer);
        assertEquals("Platform", changed.genre);
        assertEquals("20201218T000000", changed.released);
        assertEquals("0.9", changed.rating);
        assertEquals("./a.tap", changed.path);
    }
}
