package dev.ldlab.zedex.library.scrape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Which sources a scrape asks, and in what order - against names that are made
 * up.
 *
 * <b>Why this is not in {@code ScrapersTest}.</b> These rules were tested
 * through {@code Scrapers.enabled}, which builds the real providers, so any
 * test about *ordering* needed the build to have two of them. ScreenScraper is
 * only there when the build was given credentials, which a source clone is not
 * - so on a clone three of those tests skipped, and JUnit printed {@code OK}
 * for the class exactly as it does for a pass. Seven tests became four with no
 * other signal, which is a green tick over rules nothing had checked.
 *
 * {@code Scrapers.chosen} is pure and takes names, so everything here runs
 * everywhere, on every build, with no device and no credentials. What is left
 * in {@code ScrapersTest} is the wiring - that the preference is really read
 * and really written - which is what needs a device and is the same on a clone.
 */
public class ScrapersOrderTest {

    /** Two names this app will never really have, so nothing here can pass by
     *  accident on a bench that happens to be configured a particular way. */
    private static final String FIRST = "Alpha";
    private static final String SECOND = "Beta";
    private static final String THIRD = "Gamma";

    private static final List<String> AVAILABLE = Arrays.asList(FIRST, SECOND, THIRD);

    // --- nobody has ever chosen ---------------------------------------------------

    @Test
    public void withNothingStoredEverySourceIsUsedInTheBuildsOwnOrder() {
        assertEquals(AVAILABLE, Scrapers.chosen(null, null, AVAILABLE));
    }

    @Test
    public void anEmptyOlderChoiceIsNoChoiceAtAll() {
        assertEquals(AVAILABLE, Scrapers.chosen(null, "", AVAILABLE));
    }

    /**
     * An older build stored one name, and that was a decision.
     *
     * Migrated faithfully rather than generously: turning "Alpha" into "all
     * three" would widen what the app fetches, and what it spends a
     * ScreenScraper allowance on, because a feature arrived. That is not ours
     * to decide.
     */
    @Test
    public void anOlderSingleChoiceBecomesThatOneAloneAndNotTheDefault() {
        assertEquals(Collections.singletonList(SECOND),
                     Scrapers.chosen(null, SECOND, AVAILABLE));
    }

    /** And it is dropped like any other name this build does not have. */
    @Test
    public void anOlderSingleChoiceThisBuildNoLongerHasFallsAway() {
        assertTrue(Scrapers.chosen(null, "A Service That Went Away", AVAILABLE).isEmpty());
    }

    // --- somebody has chosen ------------------------------------------------------

    @Test
    public void theStoredOrderIsTheOrderTheyAreAsked() {
        assertEquals(Arrays.asList(THIRD, FIRST, SECOND),
                     Scrapers.chosen(THIRD + "\n" + FIRST + "\n" + SECOND, null, AVAILABLE));
    }

    @Test
    public void aSourceLeftOutIsNotAsked() {
        assertEquals(Arrays.asList(SECOND, FIRST),
                     Scrapers.chosen(SECOND + "\n" + FIRST, null, AVAILABLE));
    }

    /**
     * Choosing none is a real choice, and not the same as never having chosen.
     *
     * The trap the media setting beside this one already carries a warning
     * about: collapsing absent into empty makes "none" unselectable, so
     * somebody who turns every source off finds them all back on.
     */
    @Test
    public void choosingNoneMeansNoneRatherThanTheDefault() {
        assertTrue(Scrapers.chosen("", null, AVAILABLE).isEmpty());
    }

    /** Even with an older single choice sitting behind it - the newer, explicit
     *  answer wins. */
    @Test
    public void choosingNoneOutranksAnOlderSingleChoice() {
        assertTrue(Scrapers.chosen("", FIRST, AVAILABLE).isEmpty());
    }

    // --- what a stored value may contain ------------------------------------------

    /** A name from a build that had credentials this one has not. */
    @Test
    public void aStoredNameThisBuildDoesNotHaveIsSkippedRatherThanFailingTheRest() {
        assertEquals(Arrays.asList(FIRST, THIRD),
                     Scrapers.chosen(FIRST + "\nA Service That Went Away\n" + THIRD,
                                     null, AVAILABLE));
    }

    /**
     * The same source twice is asked once.
     *
     * The settings list cannot produce this, but a hand-edited preference file
     * can - and a duplicate is not cosmetic: every source is a search and a
     * fetch per game, so one listed twice is a second whole sweep against the
     * same service, and for ScreenScraper that is the day's allowance spent
     * twice over for nothing.
     */
    @Test
    public void aSourceListedTwiceIsAskedOnce() {
        assertEquals(Arrays.asList(FIRST, SECOND),
                     Scrapers.chosen(FIRST + "\n" + SECOND + "\n" + FIRST, null, AVAILABLE));
    }

    /** Blank lines and stray spacing are somebody's text editor, not a choice. */
    @Test
    public void blankLinesAndSpacingAreIgnored() {
        assertEquals(Arrays.asList(FIRST, SECOND),
                     Scrapers.chosen("  " + FIRST + "  \n\n\n " + SECOND + "\n",
                                     null, AVAILABLE));
    }

    /** A build with nothing to offer answers nothing, whatever is stored. */
    @Test
    public void abuildWithNoSourcesAnswersNothing() {
        List<String> none = Collections.emptyList();

        assertTrue(Scrapers.chosen(null, null, none).isEmpty());
        assertTrue(Scrapers.chosen(FIRST, null, none).isEmpty());
        assertTrue(Scrapers.chosen(null, FIRST, none).isEmpty());
    }
}
