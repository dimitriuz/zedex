package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.library.catalogue.Catalogue;

import java.util.Locale;

/**
 * The parts of a catalogue item's details that are string work and nothing
 * else - shared by {@link CataloguePane} and {@code CatalogueDetailsView} so
 * the two screens cannot quietly start reading a title's facts two different
 * ways. The same split {@link GameInfoText} already makes for the library's
 * own details screen, and for the same reason: free of every Android type, so
 * a JVM test can pin it without a {@code LinearLayout} subclass to load.
 */
public final class CatalogueText {

    private CatalogueText() {
    }

    /**
     * "1987 · Andrew Braybrook · Ocean Software Ltd · Arcade Game · Platform
     * · 4.2/5" - whichever of those the catalogue actually offered, in that
     * order, skipping the rest. Not every catalogue has every part: zxart
     * never has a developer, and neither zxart nor (today) ZXInfo always has
     * a rating - see {@link Catalogue.Item}'s own accessors for which
     * service supplies which. {@link Catalogue.Item#category()} is the finer
     * word beside {@link Catalogue.Item#kind()} - ZXInfo's {@code
     * genreSubType} beside its {@code genreType}, zxart's own leaf beside the
     * root {@code kind()} rolls up to.
     */
    public static String factsLine(Catalogue.Item item) {
        StringBuilder line = new StringBuilder();

        appendFact(line, item.year());
        appendFact(line, item.developer());
        appendFact(line, item.publisher());
        appendFact(line, item.kind());
        appendFact(line, item.category());
        appendFact(line, stars(item.rating()));

        return line.toString();
    }

    private static void appendFact(StringBuilder line, String fact) {
        if (fact == null || fact.isEmpty()) return;
        if (line.length() > 0) line.append(" · ");
        line.append(fact);
    }

    /**
     * "4.2/5" - the same fraction-to-stars conversion {@code Meta#stars()}
     * already does for the library's own screen, so a rating reads the same
     * way whichever of the two a person is looking at. Not shared code with
     * that method: {@link Catalogue.Item#rating()} and {@code Meta#rating}
     * happen to use the same "fraction from 0 to 1" convention, but the two
     * classes are otherwise unrelated and neither should have to know the
     * other exists.
     */
    private static String stars(String rating) {
        if (rating == null || rating.isEmpty()) return null;

        try {
            float fraction = Float.parseFloat(rating.trim());
            if (fraction < 0f || fraction > 1f) return null;

            return String.format(Locale.getDefault(), "%.1f/5", fraction * 5f);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
