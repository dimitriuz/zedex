package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.library.scrape.ScrapeException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/**
 * Somewhere the app can browse, as opposed to somewhere it can ask about a
 * game it already has.
 *
 * The seam next door, {@code Provider}, answers "here is a file - what do you
 * know about it?", and every one of its methods assumes the file exists:
 * {@code search} takes a local game, {@code fetch} fills in a row already in
 * the store, {@code costPerGame} prices a sweep over a collection. This is the
 * other question, and it needs its own shape. A site may implement one, the
 * other, or both; ScreenScraper implements only {@code Provider} and is never
 * asked to be browsable.
 *
 * <b>A way in is data, not a method.</b> A catalogue declares its shelves and
 * the tab renders what it is given, so a second catalogue never owes an answer
 * it has not got and adding a browsing mode later does not widen this
 * interface for everybody else. zxart's way in is a category tree and ZXInfo's
 * is a search box; both are shelves.
 *
 * Nothing here touches the network directly - everything goes through {@code
 * Http}, which the tests replace.
 */
public interface Catalogue {

    /** What to show somebody choosing between catalogues. Not translated: it
     *  is the service's own name. */
    String name();

    /**
     * Whether this catalogue can be asked anything at all.
     *
     * The tab hides itself rather than offering something that can only fail,
     * exactly as the scrape rows already do.
     */
    boolean configured();

    /**
     * The ways in, declared.
     *
     * <b>Makes no request.</b> A catalogue whose shelves depend on something
     * fetched - a category tree - declares one shelf that yields the rest when
     * it is opened; see {@link Page#shelves()}. That keeps this callable from
     * the UI thread while the tab is being built.
     */
    List<Shelf> shelves();

    /**
     * One page of one shelf.
     *
     * @param page zero-based. A shelf that cannot page ignores anything past
     *             zero and answers an empty page, which ends the list.
     * @throws ScrapeException for anything that is not an answer, told apart
     *         by kind so the screen can say "try again in a minute" rather
     *         than "something went wrong" - the same three kinds
     *         {@code Provider} already raises.
     */
    Page open(Shelf shelf, Query query, int page) throws ScrapeException;

    /**
     * One item in full: its versions and their files.
     *
     * How many requests that costs is the catalogue's own business - ZXInfo
     * answers with one call and zxart takes {@code types:zxProd,zxRelease} on
     * another single one. The caller asks once either way.
     */
    Item item(String id) throws ScrapeException;

    /**
     * A way in to whatever this catalogue thinks is like one of its items, or
     * null where it has no such notion.
     *
     * <b>A shelf, so nothing new has to be understood by anybody.</b> "Games
     * like this one" is the same shape as a letter or a genre: an id that
     * carries an entry id, opened through {@link #open} like any other, drawn
     * by a screen that already knows how to descend into one. That is why this
     * answers with a {@link Shelf} rather than with a list of items - a page of
     * results would have needed a request made before anybody asked for it, and
     * a way in costs nothing until it is opened.
     *
     * <b>Makes no request.</b> The same duty {@link #shelves()} states, and for
     * the same reason: {@code CataloguePane.show()} calls this while a pane is
     * being laid out, on the UI thread, to decide whether the button appears at
     * all. A catalogue that implemented it with a lookup would hang the pane on
     * every tap and ANR on a slow network. A shelf is a way in and costs nothing
     * until somebody opens it - the request belongs in {@link #open}.
     *
     * <b>Default null, so a catalogue owes nothing it has not got.</b> This
     * and {@link #knowsFormats()} are the only two defaults here; everything
     * else is abstract, and that is not an accident being extended. What the
     * seam actually promises is the line at the top of this file - <em>a way
     * in is data</em> - and this keeps it: a site with no notion of
     * similarity says so by not overriding this, whatever offers the way in
     * simply does not offer it, and no future catalogue is made to implement
     * a method most of them have no endpoint for.
     *
     * <b>The label comes from the caller, and that is deliberate.</b>
     * Everywhere else a shelf's words are the service's own - a genre's name
     * comes off the wire, a letter is a letter - and there is nothing off the
     * wire to call this one. What a person reads here is a sentence in their
     * own language about a game ("Games like Head over Heels"), and translation
     * lives on the app's side of this seam: a catalogue has no {@code Context}
     * and must not grow one for a caption. The id and {@link Shelf.Accepts}
     * stay the catalogue's, which is what the caller cannot know.
     *
     * @param label what to call the shelf, already translated.
     */
    default Shelf similarTo(Item item, String label) {
        return null;
    }

    /**
     * A way of ordering a shelf, as opposed to a way in.
     *
     * <b>A fixed vocabulary, and translated on the app's side.</b> Everywhere
     * else a shelf's words are the service's own - a genre's name comes off the
     * wire - but there is nothing off the wire to call an ordering, and what a
     * person reads here is a sentence in their own language. So this is an enum
     * with string resources against it rather than labels from a catalogue,
     * which is also what lets two catalogues offer "Top rated" and mean it even
     * though one is a community vote and the other is Elasticsearch's own
     * relevance score.
     *
     * <b>A control, not a shelf.</b> "Top rated" as a shelf would give Top over
     * the whole archive; this is Top <em>inside</em> whatever is already on
     * screen - inside Games, inside a search, inside a sub-category - which is
     * why it rides on {@link Query} rather than {@link #shelves()}.
     */
    enum Sort { DEFAULT, TOP, NEWEST, ALPHABETICAL }

    /**
     * The orderings this catalogue can honour, best-known first, always
     * including {@link Sort#DEFAULT}.
     *
     * <b>The same bargain {@link #shelves()} makes.</b> A catalogue owes
     * nothing it has not got, and {@code CatalogueView} hides the control when
     * there is only one - exactly as it hides a shelf nothing can build. zxart
     * declares four, all measured: {@code order:votes,desc} works while {@code
     * order:rating,desc} is ignored, which is exactly the kind of thing that
     * must be measured before it is offered rather than guessed at.
     */
    default List<Sort> sorts() {
        return Collections.singletonList(Sort.DEFAULT);
    }

    /**
     * Whether this catalogue's <em>list rows</em> know which formats an item
     * comes in.
     *
     * ZXInfo's do: a search hit's {@code _source} carries its releases and
     * their files, byte-identical to the record's, which is what lets the
     * screen filter by format without a request per row. zxart's do not - a
     * prod names its release ids and nothing else - so {@link Item#formats()}
     * is legitimately empty there, for a reason that has nothing to do with
     * the game actually holding no files.
     *
     * <b>Empty formats cannot be read as either answer, so the catalogue has
     * to say which it means.</b> Read as "no match" the filter would reject
     * an entire archive on the strength of a question it was never asked;
     * read as "keep everything" it would show a control that appears to
     * filter and changes nothing, which this codebase already treats as the
     * same class of fault as a chooser with no effect. Neither reading is
     * safe to guess at silently, so this states it and {@code CatalogueView}
     * hides the control it cannot honour, exactly as the tab hides itself
     * when nothing is browsable.
     *
     * <b>A second default, and it earns the same reasoning {@link
     * #similarTo} does.</b> This is not a convenience for a catalogue that
     * has not gotten round to implementing something - it is a genuine
     * absence of an endpoint, exactly as "games like this one" is: zxart's
     * rows cannot know their formats without a request per row, which
     * defeats the entire reason a list is filterable by format at all.
     * Default false, so a catalogue that has not thought about it does not
     * promise: the screen loses a filter rather than showing a broken one.
     */
    default boolean knowsFormats() {
        return false;
    }

    /**
     * What a bare HTTP status from this service means.
     *
     * Borrowed unchanged from {@code Provider}: only the service knows whether
     * a 429 is worth retrying and a 403 is not, and a screen that treated them
     * alike would tell somebody to come back tomorrow over a hiccup.
     */
    ScrapeException refusalFor(int status);

    // --- what a catalogue declares -------------------------------------------------

    /**
     * A declared way in.
     *
     * {@link #id} is the catalogue's own - a search endpoint's name, a
     * category number - and is opaque to everything else here. {@link #label}
     * is what a person reads; a shelf that comes off the wire carries the
     * service's own word for itself, which is why this is not a string
     * resource.
     */
    final class Shelf {

        /** What a shelf will do something with, if it is given one. */
        public enum Accepts { NOTHING, TEXT, LETTER }

        private final String id;
        private final String label;
        private final Accepts accepts;

        public Shelf(String id, String label, Accepts accepts) {
            this.id = id;
            this.label = label;
            this.accepts = accepts;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        /** Whether handing this shelf that kind of query means anything. The
         *  tab hands the same {@link Query} to every shelf and lets each
         *  ignore what it does not use. */
        public boolean accepts(Accepts kind) {
            return accepts == kind;
        }
    }

    /**
     * What a shelf was given.
     *
     * One object rather than an argument per kind, so adding a filter later
     * changes neither {@link #open} nor any catalogue that does not use it.
     */
    final class Query {

        private static final Query NOTHING = new Query(null, null, false, Sort.DEFAULT);

        private final String text;
        private final String letter;
        private final boolean sifting;
        private final Sort sort;

        private Query(String text, String letter, boolean sifting, Sort sort) {
            this.text = text;
            this.letter = letter;
            this.sifting = sifting;
            this.sort = sort;
        }

        /** For a shelf that takes nothing. */
        public static Query none() {
            return NOTHING;
        }

        public static Query text(String typed) {
            return new Query(typed, null, false, Sort.DEFAULT);
        }

        public static Query letter(String one) {
            return new Query(null, one, false, Sort.DEFAULT);
        }

        /**
         * The same question, from a caller that is going to keep only some of
         * the answer.
         *
         * <b>A hint about the page, and a catalogue owes nothing to it.</b>
         * The screen's format filter is applied here rather than by the
         * service - ZXInfo has no such parameter, and an unknown one is
         * ignored rather than refused - so a filtered shelf reads thirty
         * entries to keep one, and what that costs is mostly the round trip
         * and the pacing rather than the bytes. A catalogue that can ask for
         * more rows at once should; one that cannot ignores this and is no
         * worse off, which is the whole reason it rides on {@link Query}
         * rather than widening {@link Catalogue#open}.
         *
         * Never say this for a shelf whose rows are all kept: a bigger page
         * would be more bytes for rows nobody asked for yet.
         */
        public Query sifting() {
            return sifting ? this : new Query(text, letter, true, sort);
        }

        public boolean isSifting() {
            return sifting;
        }

        /**
         * The same question, ordered.
         *
         * <b>Survives {@link #sifting()}, and must go on surviving it</b> - a
         * filtered shelf makes a copy of the query on every page, and a sort
         * lost in that copy would be a sort that silently stopped applying
         * after the first page.
         */
        public Query sortedBy(Sort wanted) {
            return sort == wanted ? this : new Query(text, letter, sifting, wanted);
        }

        /** Never null - {@link Sort#DEFAULT} for a query nobody ordered. */
        public Sort sort() {
            return sort;
        }

        /** Never null - a shelf building a URL wants a string. */
        public String text() {
            return text == null ? "" : text;
        }

        public String letter() {
            return letter == null ? "" : letter;
        }
    }

    /**
     * What came back: the items, any sub-shelves, and whether to ask again.
     */
    final class Page {

        /** For a shelf that cannot say how many there are. */
        public static final int UNKNOWN_TOTAL = -1;

        private final List<Item> items;
        private final List<Shelf> shelves;
        private final int seenBefore;
        private final int total;

        /**
         * @param seenBefore how many items the caller already had before this
         *                   page - which is what decides whether there is
         *                   more, since a shelf may legitimately hand back a
         *                   short page in the middle of a run.
         */
        public Page(List<Item> items, List<Shelf> shelves, int seenBefore, int total) {
            this.items = items == null ? Collections.<Item>emptyList() : new ArrayList<Item>(items);
            this.shelves = shelves == null ? Collections.<Shelf>emptyList()
                                            : new ArrayList<Shelf>(shelves);
            this.seenBefore = seenBefore;
            this.total = total;
        }

        public List<Item> items() {
            return Collections.unmodifiableList(items);
        }

        public List<Shelf> shelves() {
            return Collections.unmodifiableList(shelves);
        }

        /** The catalogue's own count, or {@link #UNKNOWN_TOTAL}. */
        public int total() {
            return total;
        }

        /**
         * Whether asking for the next page is worth a request.
         *
         * An empty page always ends it, whatever a total claims: a total that
         * disagrees with an empty page is a service being wrong about itself,
         * and believing it asks for ever - once per fling, against an address
         * that blocks on behaviour.
         */
        public boolean hasMore() {
            if (items.isEmpty()) return false;
            if (total == UNKNOWN_TOTAL) return true;

            return seenBefore + items.size() < total;
        }
    }

    // --- what a catalogue holds ----------------------------------------------------

    /**
     * One title, as much of it as a list needs plus the versions a detail view
     * needs.
     *
     * {@link #kind} is <b>the catalogue's own word</b>, untouched - "Arcade
     * Game", "Utility", whatever zxart calls its categories. Translating it
     * into a folder is {@link Kinds}' job and happens at import, not here: a
     * catalogue is never asked to know what this app's folders are called.
     */
    final class Item {

        private final String id;
        private final String title;
        private final String year;
        private final String publisher;
        private final String kind;
        private final String availability;
        private final String pictureUrl;
        private final List<Version> versions;
        private final String videoLink;

        /** No video link - what every catalogue but zxart answers. See the
         *  nine-argument constructor below for one that has something to
         *  offer here. */
        public Item(String id, String title, String year, String publisher,
                    String kind, String availability, String pictureUrl,
                    List<Version> versions) {
            this(id, title, year, publisher, kind, availability, pictureUrl, versions, null);
        }

        /**
         * @param videoLink a link to a video about this game, or null - see
         *                  {@link #videoLink()}
         */
        public Item(String id, String title, String year, String publisher,
                    String kind, String availability, String pictureUrl,
                    List<Version> versions, String videoLink) {
            this.id = id;
            this.title = title;
            this.year = year;
            this.publisher = publisher;
            this.kind = kind;
            this.availability = availability;
            this.pictureUrl = pictureUrl;
            this.versions = versions == null ? Collections.<Version>emptyList()
                                              : new ArrayList<Version>(versions);
            this.videoLink = videoLink;
        }

        public String id() {
            return id;
        }

        public String title() {
            return title;
        }

        /** Four digits, or null. */
        public String year() {
            return year;
        }

        public String publisher() {
            return publisher;
        }

        /** The catalogue's own word. Never translated, never mapped here. */
        public String kind() {
            return kind;
        }

        /** The catalogue's own word again - shown as the reason a row is
         *  greyed. */
        public String availability() {
            return availability;
        }

        /** A thumbnail on an ordinary web host, or null. */
        public String pictureUrl() {
            return pictureUrl;
        }

        /**
         * A link to a video about this game, or null - zxart's own {@code
         * youtubeId}, turned into a watch url; no other catalogue here has
         * one to offer.
         *
         * <b>Not the {@code videos} media folder.</b> That holds an mp4 the
         * library's own gallery decodes and plays inline, once a game has
         * actually been imported; this is a page on the open web, offered
         * for a title that may not be in the library at all yet, and the
         * only thing done with it is handing it to whatever app the phone
         * has for a link - see {@code CataloguePane.openLink}.
         */
        public String videoLink() {
            return videoLink;
        }

        /** Empty until {@link Catalogue#item} has been asked - a list does
         *  not need them and they are what makes that call expensive. */
        /**
         * Every format this entry holds, lower-case and without a dot.
         *
         * Answered from the files themselves, which a <em>row</em> carries as
         * well as a fetched record does - see {@code
         * ZxInfoCatalogue.itemFrom}. That is what lets a shelf be filtered by
         * format without a request per row, and it has to be done here rather
         * than asked of the service: {@code format}, {@code filetype} and
         * {@code downloadtype} are all ignored by ZXInfo's search, measured
         * against a deliberate nonsense parameter as the control, so a filter
         * that trusted one of them would quietly return everything.
         */
        public Set<String> formats() {
            Set<String> found = new LinkedHashSet<>();

            for (Version version : versions()) {
                for (Download file : version.files()) {
                    if (file != null && !file.format().isEmpty()) found.add(file.format());
                }
            }

            return found;
        }

        public List<Version> versions() {
            return Collections.unmodifiableList(versions);
        }

        /**
         * Whether there is anything to download.
         *
         * <b>Only "Available" means available.</b> Judged by matching the one
         * good value rather than by listing the bad ones, because a vocabulary
         * that grows must not silently start reading as available: that is
         * exactly how a substring match once offered a 16K Spectrum for every
         * ZX81 program in the database.
         *
         * <b>This is not the greying rule, and using it as one is the defect
         * that rule exists to prevent.</b> Everything that is not "Available"
         * is a fact worth reading - a game announced and cancelled is a real
         * thing to find - so those rows stay on the list, greyed, with the
         * service's own word as the reason. But a record can state no
         * availability at all: measured on a live reply, one row of three
         * omitted the field entirely and it was a 2024 release, which this
         * method answers false for, correctly, since it cannot promise there
         * is something to download. Greying by that answer would tell somebody
         * a game they can have is missing and give no reason, because a field
         * was absent. The rule for the row is {@code CatalogueAdapter.greyed}
         * - <em>stated</em> and not available - and it is a different question
         * from this one.
         */
        public boolean available() {
            return availability != null
                    && "available".equals(availability.toLowerCase(Locale.ROOT));
        }

        /** "Head over Heels (1987) · Ocean Software Ltd", skipping whichever
         *  is unknown. The same joining {@code Candidate.describe} does. */
        public String describe() {
            StringBuilder line = new StringBuilder(title == null ? "" : title);

            if (year != null && !year.isEmpty()) line.append(" (").append(year).append(")");
            if (publisher != null && !publisher.isEmpty()) {
                line.append(" · ").append(publisher);
            }

            return line.toString();
        }
    }

    /**
     * One release of an item, and the files it comes in.
     *
     * The original is whichever the catalogue lists first; nothing here sorts
     * them, because their order is the catalogue's own statement about which
     * came first and this app has no better source for it.
     */
    final class Version {

        private final String label;
        private final String year;
        private final List<Download> files;

        public Version(String label, String year, List<Download> files) {
            this.label = label;
            this.year = year;
            this.files = files == null ? Collections.<Download>emptyList()
                                        : new ArrayList<Download>(files);
        }

        /** What tells two apart on a list - "Spanish re-release", "128K
         *  version" - or null. */
        public String label() {
            return label;
        }

        public String year() {
            return year;
        }

        public List<Download> files() {
            return Collections.unmodifiableList(files);
        }
    }

    /**
     * One file, and where to get it.
     *
     * <b>The url is absolute.</b> Not a path to be joined onto a base: ZXDB's
     * own recordings are on archive.org while its games are on
     * spectrumcomputing.co.uk, and a ZXInfo record's rendered screens are on
     * a third host again. A catalogue's files can be spread anywhere and the
     * downloader follows what it is given.
     *
     * {@link #format} is lower-case and without a dot - "tap", "z80", "rzx" -
     * and is the <b>inner</b> format where the file is zipped, since that is
     * what decides whether this app can open it. A ".tap.zip" is a tap.
     */
    final class Download {

        private final String url;
        private final String format;
        private final long size;

        public Download(String url, String format, long size) {
            this.url = url;
            this.format = format == null ? "" : format.toLowerCase(Locale.ROOT);
            this.size = size;
        }

        public String url() {
            return url;
        }

        public String format() {
            return format;
        }

        /** Bytes as delivered - which for these is the zip, since that is
         *  what arrives and so what a short download can be caught by. -1
         *  when the catalogue does not say. */
        public long size() {
            return size;
        }
    }
}
