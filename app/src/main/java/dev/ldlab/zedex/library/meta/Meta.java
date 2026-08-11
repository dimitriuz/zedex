package dev.ldlab.zedex.library.meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One game's facts, as the library stores them.
 *
 * Immutable, and every field but {@link #path} may be null: most of a
 * collection is unscraped, so "nothing known but the path" is the ordinary
 * shape of a row rather than a special case to guard against. See "What the
 * pane shows" in docs/LIBRARY.md for which of these actually reach the
 * screen.
 *
 * <b>Built, not constructed.</b> This class grew from eight fields to twelve
 * and will grow again as providers offer more, and a positional constructor
 * that wide has already dropped a field silently once - see {@code
 * Scrape.owned}, which rebuilt a row through it and lost the key map without
 * anything failing or logging. A builder names every value at the call site,
 * so a field added here cannot quietly become null at nineteen of them.
 */
public final class Meta {

    /** ES-DE's own key: the game's path relative to its ROM folder, {@code "./..."}. */
    public final String path;

    public final String name;
    public final String desc;
    public final String developer;
    public final String publisher;

    /**
     * The broad kind of thing this is - {@code Arcade Game}, {@code Utility}.
     *
     * Whatever the provider called it, and they do not agree: ES-DE and
     * ScreenScraper write one string that may itself be a comma-separated
     * list, and ZXInfo writes a two-level classification whose upper half
     * lands here. {@code Filters.genresOf} splits on commas, so a collection
     * scraped from more than one service ends up with more than one
     * vocabulary in the filter - untidy, and not wrong.
     */
    public final String genre;

    /**
     * The narrower half, where the provider has one, or null.
     *
     * ZXInfo's {@code genreSubType}: {@code Adventure} under {@code Arcade
     * Game}. Kept apart from {@link #genre} rather than joined into
     * "Arcade Game: Adventure" so the filter can offer two levels instead of
     * one long facet, which on a Spectrum collection is the difference
     * between a usable list and three hundred distinct strings. Null for
     * every ES-DE and ScreenScraper row, which have no such thing.
     */
    public final String subgenre;

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
     * over, {@link #USER} for one somebody has edited by hand, otherwise the
     * name of the provider that scraped it.
     *
     * It decides who owns a row: {@link Metadata#replaceScraped} replaces
     * every {@code esde} entry and leaves everything else exactly as it is,
     * so a link never overwrites something typed in by hand or fetched from
     * a provider.
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

    /**
     * How the contributors are joined into the one field that has always
     * held this.
     *
     * A row is written by more than one thing now - a link, a hand edit and
     * any number of providers, each filling in what the ones before left out
     * - and this stays a single string so that a store written by an older
     * build still reads: one name is a one-element list, which is exactly
     * what it always meant.
     */
    private static final String SOURCE_SEPARATOR = ", ";

    /**
     * How this game's own keys are laid out, as the provider wrote it, or
     * null.
     *
     * ScreenScraper's {@code sp2kcfg}: a few lines of hand-authored config
     * naming which Spectrum key each pad control should send for this
     * particular game - {@code 0:left = q}, {@code 0:a = v} and so on. Kept
     * <em>verbatim</em> rather than parsed, because nothing reads it yet:
     * mapping it onto {@code ControlProfiles}' own eight slots is a separate
     * piece of work, and parsing it now would decide the shape of that in
     * advance and throw away whatever turns out to matter.
     *
     * <b>Not the same thing as which joystick a game speaks.</b> That is a
     * separate fact, from a separate provider, and it will live in a separate
     * field: ZXInfo says a game accepts Kempston and cursor keys, this says
     * what to bind each button to. Naming this one {@code controls} - which
     * it was - invited exactly that confusion, since {@code controls} is
     * ZXInfo's own name for the other thing.
     *
     * Not one of {@link Field}, so the hand editor does not offer it. It is a
     * config file, not a fact about the game, and a one-line box is the wrong
     * way to edit one.
     *
     * <b>The line breaks are two characters, not newlines.</b> ScreenScraper
     * sends {@code "# Manic Miner \n0:left = q"} with a literal backslash and
     * n, so anything reading this has to split on that rather than on
     * {@code \n} - measured against the live service, and the sort of thing
     * that costs an afternoon to rediscover.
     *
     * Present for roughly half of what is well known: seven of twelve famous
     * titles asked for, which is worth having and not worth relying on.
     */
    public final String keymap;

    /**
     * Which machine the game wants, in the provider's own words, or null.
     *
     * ZXInfo's {@code machineType} - {@code ZX-Spectrum 48K}, {@code
     * ZX-Spectrum 48K/128K}, {@code Pentagon}. Kept verbatim rather than
     * mapped onto one of Fuse's fourteen here, because the mapping is a
     * decision about behaviour and this is a store: several of their values
     * name two machines at once, and which of the two to start is a question
     * about what somebody wants rather than about what the record says.
     */
    public final String machine;

    /**
     * Which input devices the game accepts, or empty.
     *
     * ZXInfo's {@code controls} - {@code Kempston Joystick}, {@code Cursor},
     * {@code Interface 2 (right)}, {@code Redefineable keys}. <b>Not the same
     * thing as {@link #keymap}</b>, which is one provider's idea of what each
     * pad button should send; this is the game's own list of what it will
     * listen to. One picks the joystick interface, the other lays out the
     * buttons.
     *
     * Empty rather than null when there is none, unlike every other field
     * here. A list is iterated at every call site and a null check is the
     * thing that gets forgotten; nothing is gained by making absent and empty
     * different for a list nobody can distinguish them in.
     */
    public final List<String> inputs;

    /**
     * Who made it, one to a line, with a role where the record names one -
     * {@code "Jon Ritman"}, {@code "F. David Thorpe (Load Screen)"}.
     *
     * Formatted here rather than kept apart, because a role is a qualifier on
     * a name and never appears without one: ZXDB gives the main creators no
     * role at all and reserves them for specialists, so a list of roles is
     * mostly empty and a list of names is what anybody wants to read. Nine
     * per cent of entries carry a role; sixty-four carry an author.
     */
    public final List<String> authors;

    /**
     * What it cost when it came out, formatted - {@code "£7.95"}.
     *
     * One string rather than an amount and a currency, for the same reason
     * {@link #rating} keeps the string it arrived as: nothing here does
     * arithmetic on it, and a currency this app has never heard of survives
     * unchanged instead of being turned into something it is not. Just under
     * a quarter of entries have one.
     */
    public final String price;

    /** The series this game belongs to, by name - {@code "Chaos"} - or null.
     *  Six per cent of entries are in one. */
    public final String series;

    /** The other games in that series. Never includes this one: ZXDB's own
     *  list does, and a series that lists the game you are looking at is
     *  noise. */
    public final List<Link> seriesGames;

    /** The compilations this game appears on - a quarter of entries are on
     *  at least one. */
    public final List<Link> compilations;

    /** And, when this entry <em>is</em> a compilation, what is on it. */
    public final List<Link> contents;

    /**
     * Another entry in the database: what it is called, and its id.
     *
     * <b>The id is stored although nothing reads it yet.</b> Every one of
     * these is a game somebody may want to open, and turning a title into
     * something tappable needs the id - the store is keyed by file path and
     * the provider knows nothing about paths, so the two can only be joined
     * through it. Capturing it now costs a few bytes a row; capturing it
     * later costs scraping the whole collection again, which is the argument
     * that decided every other field here.
     */
    public static final class Link {

        /** The provider's own id for the entry, as a string - it is an
         *  opaque handle here, and {@code Candidate.handle} is one too. */
        public final String id;

        public final String title;

        public Link(String id, String title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Link)) return false;

            Link that = (Link) other;
            return java.util.Objects.equals(id, that.id)
                    && java.util.Objects.equals(title, that.title);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(id, title);
        }

        /** For a test's failure message, and for nothing else. */
        @Override
        public String toString() {
            return title + " (" + id + ")";
        }
    }

    private Meta(Builder from) {
        this.path = from.path;
        this.name = from.name;
        this.desc = from.desc;
        this.developer = from.developer;
        this.publisher = from.publisher;
        this.genre = from.genre;
        this.subgenre = from.subgenre;
        this.released = from.released;
        this.players = from.players;
        this.rating = from.rating;
        this.source = from.source;
        this.keymap = from.keymap;
        this.machine = from.machine;
        this.inputs = copy(from.inputs);
        this.authors = copy(from.authors);
        this.price = from.price;
        this.series = from.series;
        this.seriesGames = copy(from.seriesGames);
        this.compilations = copy(from.compilations);
        this.contents = copy(from.contents);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? Collections.emptyList()
                              : Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** Everyone who has written something into this row, in the order they
     *  did. Empty when nothing has. */
    public List<String> sources() {
        if (source == null || source.isEmpty()) return Collections.emptyList();

        List<String> names = new ArrayList<>();
        for (String one : source.split(",")) {
            String trimmed = one.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Whether somebody typed something into this row.
     *
     * <b>Among the contributors, not the only one.</b> A row a provider
     * scraped and a person then corrected is still theirs, and the whole
     * point of a scrape that only fills gaps is that the two can share a row
     * without either losing anything.
     */
    public boolean isMine() {
        return sources().contains(USER);
    }

    /**
     * Whether an ES-DE link owns this row and may replace it.
     *
     * <b>Only when ES-DE is the only contributor.</b> The rule was "a link
     * replaces only what ES-DE brought over", and with several contributors
     * per row that has to mean "brought over all of it": a row a link started
     * and a provider then filled in is no longer ES-DE's to replace, or
     * relinking would throw away the scrape.
     */
    public boolean isEsde() {
        List<String> who = sources();
        return who.isEmpty() || (who.size() == 1 && ESDE.equals(who.get(0)));
    }

    // --- building ------------------------------------------------------------------

    /** A new row for {@code path}, which is the one thing every row must have. */
    public static Builder at(String path) {
        return new Builder().path(path);
    }

    /** This row again, to change something. Every field is carried over, which
     *  is the whole point: the old way was to retype eleven of them. */
    public Builder but() {
        return new Builder()
                .path(path).name(name).desc(desc)
                .developer(developer).publisher(publisher)
                .genre(genre).subgenre(subgenre)
                .released(released).players(players).rating(rating)
                .source(source).keymap(keymap)
                .machine(machine).inputs(inputs)
                .authors(authors).price(price)
                .series(series).seriesGames(seriesGames)
                .compilations(compilations).contents(contents);
    }

    /**
     * A copy with one editable field replaced, named the way the editor names
     * them.
     *
     * The source becomes {@link #USER} for every one of these: a changed field
     * is a hand edit by definition, and there is no way to change one without
     * meaning it.
     */
    public Meta with(Field field, String value) {
        return but().set(field, value).contributor(USER).build();
    }

    public static final class Builder {

        private String path, name, desc, developer, publisher, genre, subgenre,
                released, players, rating, source, keymap, machine, price, series;

        private List<String> inputs, authors;
        private List<Link> seriesGames, compilations, contents;

        /** Empty means absent. Every caller here is either a parser reading a
         *  file or a screen reading a text box, and both produce "" for a
         *  field nobody filled in - storing that would make an empty string
         *  and an absent value two different kinds of nothing. */
        private static String orNull(String value) {
            return value == null || value.isEmpty() ? null : value;
        }

        public Builder path(String v)      { path = v;                 return this; }
        public Builder name(String v)      { name = orNull(v);         return this; }
        public Builder desc(String v)      { desc = orNull(v);         return this; }
        public Builder developer(String v) { developer = orNull(v);    return this; }
        public Builder publisher(String v) { publisher = orNull(v);    return this; }
        public Builder genre(String v)     { genre = orNull(v);        return this; }
        public Builder subgenre(String v)  { subgenre = orNull(v);     return this; }
        public Builder released(String v)  { released = orNull(v);     return this; }
        public Builder players(String v)   { players = orNull(v);      return this; }
        public Builder rating(String v)    { rating = orNull(v);       return this; }
        public Builder source(String v)    { source = orNull(v);       return this; }

        /**
         * Adds one contributor, if it is not already listed.
         *
         * Appended rather than prepended: the order is the order they
         * contributed, and under a priority scrape that is also the order of
         * priority - which is worth being able to read back even though
         * nothing does yet. Adding one twice is a no-op, so re-scraping from
         * the same service does not grow the field for ever.
         */
        public Builder contributor(String name) {
            if (name == null || name.isEmpty()) return this;

            List<String> who = new ArrayList<>();
            if (source != null && !source.isEmpty()) {
                for (String one : source.split(",")) {
                    String trimmed = one.trim();
                    if (!trimmed.isEmpty()) who.add(trimmed);
                }
            }

            if (!who.contains(name)) who.add(name);

            return source(String.join(SOURCE_SEPARATOR, who));
        }

        public Builder keymap(String v)    { keymap = orNull(v);       return this; }
        public Builder price(String v)     { price = orNull(v);        return this; }
        public Builder series(String v)    { series = orNull(v);       return this; }

        public Builder authors(List<String> v)     { authors = v;      return this; }
        public Builder seriesGames(List<Link> v)   { seriesGames = v;  return this; }
        public Builder compilations(List<Link> v)  { compilations = v; return this; }
        public Builder contents(List<Link> v)      { contents = v;     return this; }
        public Builder machine(String v)   { machine = orNull(v);      return this; }

        /** Copied, and empty is the same as none. */
        public Builder inputs(List<String> v) {
            inputs = v == null || v.isEmpty() ? null : new ArrayList<>(v);
            return this;
        }

        /** By {@link Field}, for the editor and for {@link Meta#with}. */
        public Builder set(Field field, String value) {
            switch (field) {
                case NAME:      return name(value);
                case DESC:      return desc(value);
                case DEVELOPER: return developer(value);
                case PUBLISHER: return publisher(value);
                case GENRE:     return genre(value);
                case SUBGENRE:  return subgenre(value);
                case RELEASED:  return released(value);
                case PLAYERS:   return players(value);
                case RATING:    return rating(value);
                case MACHINE:   return machine(value);
                case INPUTS:    return inputs(listOf(value));
                default:        return this;
            }
        }

        /**
         * A typed line of controls as the list it stands for.
         *
         * Split on commas, trimmed, and the empties dropped - the line was
         * typed by a person, so the spacing is theirs and "Kempston,, Cursor,"
         * means two controls rather than four. An empty result is an empty
         * list, which is how the record says nothing rather than saying
         * nothing in particular.
         */
        private static List<String> listOf(String line) {
            List<String> values = new ArrayList<>();
            if (line == null) return values;

            for (String one : line.split(",")) {
                String trimmed = one.trim();
                if (!trimmed.isEmpty()) values.add(trimmed);
            }

            return values;
        }

        public Meta build() {
            return new Meta(this);
        }
    }

    // --- the editable fields ----------------------------------------------------------

    /**
     * The nine a person can edit - the facts about the game, but not the path,
     * which is the key, nor the source, which is ours, nor the key map, which
     * is a config file.
     *
     * An enum rather than nine methods so the editor can build its rows by
     * walking it: a field added here appears on the screen without the screen
     * being told, which is the failure mode a hand-written list has.
     */
    /**
     * The fields somebody can fill in by hand, in the order the editor shows
     * them - it walks this rather than listing them, so a field added here
     * appears there.
     *
     * {@link #MACHINE} and {@link #INPUTS} are last because they are the two a
     * person is here for rather than correcting: the setup dialog offers what
     * they say, and plenty of games are in no database that knows.
     */
    public enum Field {
        NAME, DESC, DEVELOPER, PUBLISHER, GENRE, SUBGENRE, RELEASED, PLAYERS,
        RATING, MACHINE, INPUTS
    }

    /** How a list of controls is written on one line, and split back up. */
    private static final String INPUT_SEPARATOR = ", ";

    /** What this game has in {@code field}, or null. */
    public String get(Field field) {
        switch (field) {
            case NAME:      return name;
            case DESC:      return desc;
            case DEVELOPER: return developer;
            case PUBLISHER: return publisher;
            case GENRE:     return genre;
            case SUBGENRE:  return subgenre;
            case RELEASED:  return released;
            case PLAYERS:   return players;
            case RATING:    return rating;
            case MACHINE:   return machine;
            case INPUTS:    return inputs.isEmpty() ? null
                                                    : String.join(INPUT_SEPARATOR, inputs);
            default:        return null;
        }
    }

    // --- reading the awkward fields -----------------------------------------------------

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
