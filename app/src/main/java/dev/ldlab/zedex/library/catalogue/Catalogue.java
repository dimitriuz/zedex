package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.library.scrape.ScrapeException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

        private static final Query NOTHING = new Query(null, null);

        private final String text;
        private final String letter;

        private Query(String text, String letter) {
            this.text = text;
            this.letter = letter;
        }

        /** For a shelf that takes nothing. */
        public static Query none() {
            return NOTHING;
        }

        public static Query text(String typed) {
            return new Query(typed, null);
        }

        public static Query letter(String one) {
            return new Query(null, one);
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

        public Item(String id, String title, String year, String publisher,
                    String kind, String availability, String pictureUrl,
                    List<Version> versions) {
            this.id = id;
            this.title = title;
            this.year = year;
            this.publisher = publisher;
            this.kind = kind;
            this.availability = availability;
            this.pictureUrl = pictureUrl;
            this.versions = versions == null ? Collections.<Version>emptyList()
                                              : new ArrayList<Version>(versions);
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

        /** Empty until {@link Catalogue#item} has been asked - a list does
         *  not need them and they are what makes that call expensive. */
        public List<Version> versions() {
            return Collections.unmodifiableList(versions);
        }

        /**
         * Whether there is anything to download.
         *
         * <b>Only "Available" means available.</b> Everything else is a fact
         * worth reading - a game announced and cancelled is a real thing to
         * find - so those rows stay on the list, greyed, with the service's
         * own word as the reason. Judged by matching the one good value
         * rather than by listing the bad ones, because a vocabulary that
         * grows must not silently start reading as available: that is exactly
         * how a substring match once offered a 16K Spectrum for every ZX81
         * program in the database.
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
