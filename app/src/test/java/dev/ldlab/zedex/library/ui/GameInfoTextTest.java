package dev.ldlab.zedex.library.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import dev.ldlab.zedex.library.meta.Meta;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * The two helpers that survived the merge of GameInfoActivity into
 * GameInfoView. They are here, in a class of their own with no Android type
 * in it, precisely so this test can exist: a JVM test cannot load a
 * LinearLayout subclass against a stubbed android.jar, and these are the only
 * part of that screen worth pinning on the JVM.
 */
public class GameInfoTextTest {

    @Test
    public void titlesAreJoinedWithCommas() {
        assertEquals("Chaos, Rebelstar", GameInfoText.titlesOf(Arrays.asList(
                new Meta.Link("1", "Chaos"), new Meta.Link("2", "Rebelstar"))));
    }

    @Test
    public void noTitlesAtAllIsNullRatherThanEmpty() {
        assertNull(GameInfoText.titlesOf(null));
        assertNull(GameInfoText.titlesOf(Collections.emptyList()));
    }

    @Test
    public void aSeriesWithOtherGamesNamesBothAcrossADash() {
        Meta meta = Meta.at("./g.tap")
                .series("Lords of Chaos")
                .seriesGames(Collections.singletonList(new Meta.Link("1", "Chaos")))
                .build();

        assertEquals("Lords of Chaos — Chaos", GameInfoText.seriesLine(meta));
    }

    @Test
    public void aSeriesWithNoOtherGamesIsJustItsName() {
        Meta meta = Meta.at("./g.tap").series("Lords of Chaos").build();
        assertEquals("Lords of Chaos", GameInfoText.seriesLine(meta));
    }

    /** The row belongs to a series nobody named, which is not the same as
     *  belonging to none - the other games are still worth listing. */
    @Test
    public void otherGamesWithNoSeriesNameAreStillListed() {
        Meta meta = Meta.at("./g.tap")
                .seriesGames(Collections.singletonList(new Meta.Link("1", "Chaos")))
                .build();

        assertEquals("Chaos", GameInfoText.seriesLine(meta));
    }

    @Test
    public void neitherIsNull() {
        assertNull(GameInfoText.seriesLine(Meta.at("./g.tap").build()));
    }
}
