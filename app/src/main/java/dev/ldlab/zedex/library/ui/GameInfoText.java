package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.library.meta.Meta;

import java.util.List;

/**
 * The parts of a game's details that are string work and nothing else.
 *
 * Split out of {@code GameInfoActivity} when that screen was folded into
 * {@link GameInfoView}, and kept free of every Android type on purpose: this
 * project has no Robolectric ({@code unitTests.returnDefaultValues = true}),
 * so a JVM test cannot load a {@code LinearLayout} subclass, and these are the
 * only part of that screen worth pinning on the JVM. See
 * {@code GameInfoTextTest}.
 */
public final class GameInfoText {

    private GameInfoText() {
    }

    /** The titles of other entries, comma separated. The ids travel with them
     *  in the store and nothing reads them yet - see {@link Meta.Link}. */
    public static String titlesOf(List<Meta.Link> links) {
        if (links == null || links.isEmpty()) return null;

        StringBuilder text = new StringBuilder();

        for (Meta.Link link : links) {
            if (text.length() > 0) text.append(", ");
            text.append(link.title);
        }

        return text.toString();
    }

    /** The series' name, and the rest of it after a dash where the record
     *  names any - "Lords of Chaos — Chaos". */
    public static String seriesLine(Meta meta) {
        String rest = titlesOf(meta.seriesGames);

        if (meta.series == null) return rest;
        return rest == null ? meta.series : meta.series + " — " + rest;
    }
}
