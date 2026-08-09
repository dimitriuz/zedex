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
     * ES-DE's own scraped rating, as it writes it: a fraction from 0 to 1,
     * kept as the string it arrived as rather than parsed here, so that a
     * value this app does not understand survives a link and a write
     * unchanged instead of being rounded into something else. Only about four
     * games in ten have one. See {@link #stars}.
     */
    public final String rating;

    /**
     * Where this entry came from - {@link #ESDE} for anything a link brought
     * over, {@link #USER} for one somebody has edited by hand. Kept apart
     * from ES-DE's own fields so that a later hand-edited value is never
     * mistaken for a scraped one; not one of ES-DE's own gamelist elements.
     *
     * It decides who owns a row: {@link Metadata#replaceScraped} replaces
     * every {@code esde} entry and leaves every {@code user} one exactly as
     * it is, so a link never overwrites something typed in by hand.
     */
    public final String source;

    /** A link brought this one over. */
    public static final String ESDE = "esde";

    /**
     * Somebody edited this one by hand, and a link must leave it alone.
     *
     * The whole of the ownership rule, and it is deliberately per game rather
     * than per field: a game whose genre was corrected keeps the rest of what
     * was scraped, but stops receiving ES-DE's later improvements to any of
     * it. That is the price of the simple rule, and the editor's own "forget
     * my edits" is the way back - it drops the row so the next link brings
     * ES-DE's version again.
     */
    public static final String USER = "user";

    /** Whether somebody typed this one in by hand. */
    public boolean isMine() {
        return USER.equals(source);
    }

    /**
     * Whether an ES-DE link owns this row and may replace it.
     *
     * The rule is "a link replaces only what ES-DE brought over", not "a link
     * keeps hand edits" - those were the same thing until this app could
     * scrape for itself, and then they were not: a row fetched from
     * ScreenScraper carries that provider's name, is nobody's hand edit, and
     * must survive a link every bit as much as one that was typed. See {@code
     * Metadata#replaceScraped}.
     */
    public boolean isEsde() {
        return source == null || ESDE.equals(source);
    }

    /**
     * A copy with one field replaced, named the way the editor names them.
     *
     * Immutable and ten fields wide, so the alternative at every call site is
     * a ten-argument constructor with nine arguments copied across - which is
     * exactly where a field gets silently dropped. The source is set to
     * {@link #USER} by every one of these: a changed field is a hand edit by
     * definition, and there is no way to change one without meaning it.
     */
    public Meta with(Field field, String value) {
        String set = value != null && value.isEmpty() ? null : value;

        return new Meta(
                path,
                field == Field.NAME ? set : name,
                field == Field.DESC ? set : desc,
                field == Field.DEVELOPER ? set : developer,
                field == Field.PUBLISHER ? set : publisher,
                field == Field.GENRE ? set : genre,
                field == Field.RELEASED ? set : released,
                field == Field.PLAYERS ? set : players,
                field == Field.RATING ? set : rating,
                USER);
    }

    /**
     * The eight a person can edit - everything ES-DE's gamelist carries
     * except the path, which is the key, and the source, which is ours.
     *
     * An enum rather than eight methods so the editor can build its rows by
     * walking it: a field added here appears on the screen without the screen
     * being told, which is the failure mode a hand-written list of eight has.
     */
    public enum Field { NAME, DESC, DEVELOPER, PUBLISHER, GENRE, RELEASED, PLAYERS, RATING }

    /** What this game has in {@code field}, or null. */
    public String get(Field field) {
        switch (field) {
            case NAME:      return name;
            case DESC:      return desc;
            case DEVELOPER: return developer;
            case PUBLISHER: return publisher;
            case GENRE:     return genre;
            case RELEASED:  return released;
            case PLAYERS:   return players;
            case RATING:    return rating;
            default:        return null;
        }
    }

    public Meta(String path, String name, String desc, String developer,
                String publisher, String genre, String released, String players,
                String rating, String source) {
        this.path = path;
        this.name = name;
        this.desc = desc;
        this.developer = developer;
        this.publisher = publisher;
        this.genre = genre;
        this.released = released;
        this.players = players;
        this.rating = rating;
        this.source = source;
    }

    /**
     * The rating out of five as a number, or {@code -1} when there is none.
     *
     * Separate from {@link #stars}, which formats for a screen and is no use
     * for a comparison - it is localised, so a decimal point is a comma in
     * half the languages this app ships in.
     */
    public float ratingOutOfFive() {
        if (rating == null || rating.isEmpty()) return -1f;

        try {
            float fraction = Float.parseFloat(rating.trim());
            if (fraction < 0f || fraction > 1f) return -1f;

            return fraction * 5f;
        } catch (NumberFormatException e) {
            return -1f;
        }
    }

    /**
     * The rating out of five, to one decimal place, or null when there is
     * none or it does not read as a number.
     *
     * ES-DE stores a fraction - {@code 0.9} - which means nothing on its own
     * on a screen. Out of five is what a person recognises, and one decimal
     * is as much precision as a scraped average deserves. Null rather than
     * zero when it cannot be read: no rating and a rating of nought are
     * different things, and the row that shows this leaves the fact out
     * entirely rather than claiming a game scored nothing.
     */
    public String stars() {
        float out = ratingOutOfFive();
        if (out < 0f) return null;

        return String.format(java.util.Locale.getDefault(), "%.1f", out);
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
