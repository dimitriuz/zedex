package dev.ldlab.zedex.library.meta;

/**
 * One game's facts, as the library stores them - whatever ES-DE's own
 * gamelist carries, plus {@link #source} to say where they came from.
 *
 * Immutable, and every field but {@link #path} may be null: most of a
 * collection is unscraped, so "nothing known but the path" is the ordinary
 * shape of a row rather than a special case to guard against. See "What the
 * pane shows" in docs/LIBRARY.md for which of these actually reach the
 * screen.
 */
public final class Meta {

    /** ES-DE's own key: the game's path relative to its ROM folder, {@code "./..."}. */
    public final String path;

    public final String name;
    public final String desc;
    public final String developer;
    public final String publisher;
    public final String genre;
    public final String released;
    public final String players;

    /**
     * Where this entry came from - {@code "esde"} for anything a link
     * brought over. Kept apart from ES-DE's own fields so that a later
     * hand-edited value is never mistaken for a scraped one; not one of
     * ES-DE's own gamelist elements.
     */
    public final String source;

    public Meta(String path, String name, String desc, String developer,
                String publisher, String genre, String released, String players,
                String source) {
        this.path = path;
        this.name = name;
        this.desc = desc;
        this.developer = developer;
        this.publisher = publisher;
        this.genre = genre;
        this.released = released;
        this.players = players;
        this.source = source;
    }

    /**
     * The year alone, or null when {@link #released} holds nothing that can
     * be read as one.
     *
     * ES-DE writes an ISO-ish stamp - {@code 20201218T000000} - of which the
     * day and the time are noise for anything this app shows; a release
     * *year* is what a person recognises a game by. Null rather than a guess
     * when the first four characters are not all digits, since a wrong year
     * is worse than none: the row that shows it has room for one fact and
     * this is it.
     */
    public String year() {
        if (released == null || released.length() < 4) return null;

        String year = released.substring(0, 4);
        for (int i = 0; i < 4; i++) {
            if (!Character.isDigit(year.charAt(i))) return null;
        }
        return year;
    }
}
