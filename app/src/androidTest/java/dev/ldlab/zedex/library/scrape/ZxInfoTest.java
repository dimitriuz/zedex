package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The second provider, against recorded replies rather than the service.
 *
 * Every shape here was taken from real answers - {@code /games/0002259} and a
 * {@code /search} - captured before the API stopped answering this address.
 * The bodies are cut down to the fields under test and otherwise unaltered,
 * which is the point: a parser tested against JSON somebody wrote to make the
 * parser pass proves only that the two agree.
 *
 * <b>{@code /filecheck} is a recording now too.</b> It used to be written
 * from the specification's sentence - "returns id and title for found entry" -
 * because the service was unreachable from here, and it was the one part of
 * this class a live call could still have contradicted. It did not: the real
 * reply is below, taken 2026-08-10, and it is a flat object keyed
 * {@code entry_id} rather than {@code id}, carrying rather more than the
 * sentence promised. The parser was lenient about which key it found, so it
 * had been right all along - by luck, and now on the record.
 */
@RunWith(AndroidJUnit4.class)
public class ZxInfoTest {

    // --- a stand-in for the network ---------------------------------------------------

    /** Answers whatever it was told to, and remembers what it was asked. */
    private static final class Canned implements Http {
        private final List<Reply> replies = new ArrayList<>();
        final List<String> asked = new ArrayList<>();

        Canned then(int status, String body) {
            replies.add(new Reply(status, body));
            return this;
        }

        @Override
        public Reply get(String url) {
            asked.add(url);
            if (replies.isEmpty()) return new Reply(200, "{}");
            return replies.remove(0);
        }

        @Override
        public String save(String url, File into) {
            throw new UnsupportedOperationException("not this test's business");
        }
    }

    private static final class AGame implements Provider.Game {
        private final String md5;

        AGame(String md5) {
            this.md5 = md5;
        }

        @Override public String path() { return "./h/HeadOverHeels.tap"; }
        @Override public String filename() { return "Head over Heels 48K (1987)(Ocean).tap"; }
        @Override public long size() { return 37132; }
        @Override public String md5() { return md5; }
    }

    // --- recorded bodies -----------------------------------------------------------------

    /** {@code /games/0002259?mode=full}, trimmed to what is asserted on. */
    private static final String RECORD = "{"
            + "\"_id\":\"0002259\",\"found\":true,\"_source\":{"
            + "  \"title\":\"Head over Heels\","
            + "  \"originalYearOfRelease\":1987,"
            + "  \"machineType\":\"ZX-Spectrum 48K/128K\","
            + "  \"genre\":\"Arcade Game: Adventure\","
            + "  \"genreType\":\"Arcade Game\","
            + "  \"genreSubType\":\"Adventure\","
            + "  \"numberOfPlayers\":1,"
            + "  \"availability\":\"Available\","
            + "  \"remarks\":\"Jon Ritman and Bernie Drummond's isometric one.\","
            + "  \"score\":{\"score\":8.48,\"votes\":756},"
            + "  \"authors\":[{\"name\":\"Jon Ritman\",\"type\":\"Creator\"}],"
            + "  \"publishers\":[{\"name\":\"Ocean Software Ltd\",\"country\":\"UK\"}],"
            + "  \"controls\":[{\"control\":\"Cursor\"},{\"control\":\"Kempston Joystick\"}],"
            + "  \"screens\":["
            + "    {\"type\":\"Loading screen\",\"format\":\"Picture\","
            + "     \"url\":\"/zxscreens/0002259/HeadOverHeels-load.png\",\"size\":8491},"
            + "    {\"type\":\"Running screen\",\"format\":\"Picture (GIF)\","
            + "     \"url\":\"/pub/sinclair/screens/in-game/h/HeadOverHeels.gif\",\"size\":6878}"
            + "  ],"
            + "  \"additionalDownloads\":["
            + "    {\"type\":\"Inlay - Front\",\"format\":\"Picture (JPG)\","
            + "     \"path\":\"/zxdb/sinclair/entries/0002259/HeadOverHeels.jpg\"},"
            + "    {\"type\":\"Inlay - Back\",\"format\":\"Picture (JPG)\","
            + "     \"path\":\"/zxdb/sinclair/entries/0002259/HeadOverHeels_back.jpg\"},"
            + "    {\"type\":\"Loading screen\",\"format\":\"Screen dump (SCR)\","
            + "     \"path\":\"/pub/sinclair/screens/load/h/scr/HeadOverHeels.scr\"},"
            + "    {\"type\":\"Running screen\",\"format\":\"Picture (GIF)\","
            + "     \"path\":\"/pub/sinclair/screens/in-game/h/HeadOverHeels.gif\"},"
            + "    {\"type\":\"Instructions\",\"format\":\"Document (PDF)\","
            + "     \"path\":\"/zxdb/sinclair/entries/0002259/HeadOverHeels(EN).pdf\"},"
            + "    {\"type\":\"Instructions\",\"format\":\"Document (TXT)\","
            + "     \"path\":\"/pub/sinclair/games-info/h/HeadOverHeels.txt\"},"
            + "    {\"type\":\"Game map\",\"format\":\"Picture (JPG)\","
            + "     \"path\":\"/pub/sinclair/games-maps/h/HeadOverHeels.jpg\"},"
            // Four maps and three scans on the real record; two of each here,
            // because "the first wins" is only worth asserting against a
            // second. Paths verbatim.
            + "    {\"type\":\"Game map\",\"format\":\"Picture (JPG)\","
            + "     \"path\":\"/pub/sinclair/games-maps/h/HeadOverHeels_2.jpg\"},"
            + "    {\"type\":\"Advertisement\",\"format\":\"Picture (JPG)\","
            + "     \"path\":\"/pub/sinclair/games-adverts/h/HeadOverHeels.jpg\"},"
            + "    {\"type\":\"Media scan\",\"format\":\"Picture (JPG)\","
            + "     \"path\":\"/zxdb/sinclair/entries/0002259/HeadOverHeels_Media.jpg\"},"
            + "    {\"type\":\"Media scan\",\"format\":\"Picture (JPG)\","
            + "     \"path\":\"/zxdb/sinclair/entries/0002259/HeadOverHeels_Media_2.jpg\"},"
            + "    {\"type\":\"POK pokes file\",\"format\":\"Pokes (POK)\","
            + "     \"path\":\"/zxdb/sinclair/pokes/h/Head over Heels (1987)(Ocean Software).pok\"}"
            + "  ]"
            + "}}";

    /** {@code /search?...&mode=compact}, two hits. */
    private static final String SEARCH = "{\"hits\":{\"total\":{\"value\":2},\"hits\":["
            + "{\"_id\":\"0002259\",\"_source\":{\"title\":\"Head over Heels\","
            + "  \"originalYearOfRelease\":1987,"
            + "  \"publishers\":[{\"name\":\"Ocean Software Ltd\"}]}},"
            + "{\"_id\":\"0031337\",\"_source\":{\"title\":\"Head over Heels (128K remix)\","
            + "  \"originalYearOfRelease\":2013,"
            + "  \"publishers\":[{\"name\":\"Somebody\"}]}}"
            + "]}}";

    private static final String NO_HITS = "{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}";

    /** Written from the specification, not recorded - see the class comment. */
    /**
     * The real thing: {@code GET /v3/filecheck/dc93198d211b845a1977b0b38506ce50},
     * the md5 of a TOSEC copy of Arkanoid, answered 2026-08-10.
     *
     * Unaltered but for the {@code sha512}, which is cut short because its
     * length is all this cares about. Note what it is <em>not</em>: not an
     * array, not keyed "id", and not two fields - it carries the machine type
     * and genre as well, which a future version of this provider could read
     * rather than spending a second call on.
     */
    private static final String BY_HASH = "{\"entry_id\":\"0000255\","
            + "\"title\":\"Arkanoid\",\"zxinfoVersion\":\"1.0.239\","
            + "\"contentType\":\"SOFTWARE\",\"originalYearOfRelease\":1987,"
            + "\"machineType\":\"ZX-Spectrum 48K\",\"genre\":\"Arcade Game: Action\","
            + "\"genreType\":\"Arcade Game\",\"genreSubType\":\"Action\","
            + "\"publishers\":[{\"name\":\"Imagine Software Ltd\"}],"
            + "\"file\":[{\"filename\":\"Arkanoid (1987)(Imagine).tzx\","
            + "\"md5\":\"dc93198d211b845a1977b0b38506ce50\","
            + "\"sha512\":\"1654b6c6aafc503f\",\"source\":\"TOSEC 2023\"}]}";

    // --- what a filename is worth searching for -------------------------------------------

    /**
     * A Spectrum collection names its files in a convention, and almost none
     * of it belongs in a title search.
     *
     * The year, the publisher and the disambiguating number all live in
     * brackets, and the machine often rides outside them. Searching for the
     * whole filename finds nothing at all; searching for what is left finds
     * the game.
     */
    @Test
    public void afilenameIsReducedToSomethingWorthSearchingFor() {
        assertEquals("Arkanoid", ZxInfo.titleOf("Arkanoid 48K (1987)(Imagine) (1).tzx"));
        assertEquals("Head over Heels",
                     ZxInfo.titleOf("Head over Heels 128K (1987)(Ocean).tap"));
        assertEquals("Chase H.Q.", ZxInfo.titleOf("Chase H.Q..tzx"));
        assertEquals("Cybernoid II - The Revenge",
                     ZxInfo.titleOf("Cybernoid II - The Revenge 128K (1988)(Hewson).tzx"));
        assertEquals("Bloodstone", ZxInfo.titleOf("Bloodstone [2026].trd"));
    }

    /** And nothing sensible is thrown away: a title that is only a title
     *  survives untouched. */
    @Test
    public void aplainNameIsLeftAlone() {
        assertEquals("Manic Miner", ZxInfo.titleOf("Manic Miner.tap"));
        assertEquals("", ZxInfo.titleOf(null));
    }

    /**
     * Three real {@code compact} replies, trimmed to the fields under test.
     *
     * Recorded 2026-08-10 from {@code /games/0002259}, {@code /games/0000894}
     * and {@code /games/0012019} - Head over Heels, Chaos, and a compilation
     * the first of them appears on. Between them they carry every shape these
     * fields come in: an author with no role and one with a role, a price, a
     * series, both directions of compilation.
     *
     * The authors are cut to two and the lists to two entries; nothing is
     * reshaped. Only their {@code notes} are dropped, which are paragraphs of
     * prose about the person and nothing this reads.
     */
    private static final String HEAD_OVER_HEELS = "{\"title\":\"Head over Heels\",\"authors\":[{\"type\":\"Creator\",\"authorSe"
            + "q\":1,\"name\":\"Jon Ritman\",\"country\":\"UK\",\"labelType\":\"Person\",\""
            + "roles\":[],\"groupName\":null,\"groupCountry\":null,\"groupType\":null},{\"t"
            + "ype\":\"Creator\",\"authorSeq\":3,\"name\":\"F. David Thorpe\",\"country\":"
            + "\"UK\",\"labelType\":\"Nickname\",\"roles\":[{\"roleType\":\"S\",\"roleName\""
            + ":\"Load Screen\"}],\"groupName\":null,\"groupCountry\":null,\"groupType\":nu"
            + "ll}],\"originalPrice\":{\"amount\":\"7.95\",\"currency\":\"£\",\"prefix\":1}"
            + ",\"inCompilations\":[{\"entry_id\":12019,\"title\":\"Dixons Premier Collecti"
            + "on for Your +2\",\"publishers\":[{\"name\":\"Dixons\",\"country\":\"UK\",\"l"
            + "abelType\":null}],\"machineType\":\"ZX-Spectrum 48K\",\"type\":\"Compilation"
            + "\"},{\"entry_id\":14204,\"title\":\"Outlet issue 117\",\"publishers\":[{\"na"
            + "me\":\"Chezron Software\",\"country\":\"UK\",\"labelType\":\"Company\"}],\"m"
            + "achineType\":\"ZX-Spectrum 48K\",\"type\":\"Electronic Magazine\"}]}";

    private static final String CHAOS = "{\"title\":\"Chaos\",\"authors\":[{\"type\":\"Creator\",\"authorSeq\":1,\"na"
            + "me\":\"Julian Gollop\",\"country\":\"UK\",\"labelType\":\"Person\",\"roles\""
            + ":[],\"groupName\":null,\"groupCountry\":null,\"groupType\":null},{\"type\":"
            + "\"Contributor\",\"authorSeq\":3,\"name\":\"Julek Heller\",\"country\":null,\""
            + "labelType\":\"Person\",\"roles\":[{\"roleType\":\"A\",\"roleName\":\"Inlay/P"
            + "oster Art\"}],\"groupName\":null,\"groupCountry\":null,\"groupType\":null}],"
            + "\"originalPrice\":{\"amount\":\"7.95\",\"currency\":\"£\",\"prefix\":1},\"se"
            + "ries\":[{\"entry_id\":894,\"title\":\"Chaos\",\"publishers\":[{\"name\":\"Ga"
            + "mes Workshop\",\"country\":\"UK\",\"labelType\":\"Company: Publisher/Manager"
            + "\"}],\"machineType\":\"ZX-Spectrum 48K\",\"groupName\":\"Chaos\"},{\"entry_i"
            + "d\":2930,\"title\":\"Lords of Chaos\",\"publishers\":[{\"name\":\"Blade Soft"
            + "ware Ltd\",\"country\":\"UK\",\"labelType\":\"Company\"}],\"machineType\":\""
            + "ZX-Spectrum 48K\",\"groupName\":\"Chaos\"}],\"inCompilations\":[{\"entry_id"
            + "\":14482,\"title\":\"The Rebelstar Collection\",\"publishers\":[{\"name\":\"M"
            + "ythos Games Ltd\",\"country\":\"UK\",\"labelType\":\"Company\"}],\"machineTy"
            + "pe\":\"ZX-Spectrum 48K\",\"type\":\"Compilation\"},{\"entry_id\":13788,\"tit"
            + "le\":\"Your Sinclair issue 57: Smash Tape 34\",\"publishers\":[{\"name\":nul"
            + "l,\"country\":null,\"labelType\":null}],\"machineType\":\"ZX-Spectrum 48K\","
            + "\"type\":\"Covertape\"}]}";

    private static final String COMPILATION = "{\"title\":\"Dixons Premier Collection for Your +2\",\"authors\":[{}],\"comp"
            + "ilationContents\":[{\"entry_id\":1860,\"title\":\"Freddy Hardest\",\"publish"
            + "ers\":[{\"name\":\"Dinamic Software\",\"country\":\"Spain\",\"labelType\":\""
            + "Company\"}],\"machineType\":\"ZX-Spectrum 48K\",\"sequence\":1,\"side\":\"Ta"
            + "pe 1, side A\",\"variation\":\"Full version\"},{\"entry_id\":5129,\"title\":"
            + "\"Tank\",\"publishers\":[{\"name\":\"Ocean Software Ltd\",\"country\":\"UK\""
            + ",\"labelType\":\"Company: Publisher/Manager\"}],\"machineType\":\"ZX-Spectru"
            + "m 48K/128K\",\"sequence\":2,\"side\":\"Tape 1, side A\",\"variation\":\"Full"
            + " version\"}]}";

    // --- finding ----------------------------------------------------------------------------

    /**
     * A hash match is certain, and certainty is the whole point.
     *
     * It is what lets {@code Scrape.certain} fill a row in without asking
     * anybody, and so what makes an unattended sweep of eight hundred games
     * worth starting. Without it every game would need a person.
     */
    @Test
    public void ahashMatchIsTheOneCertainAnswer() throws Exception {
        Canned http = new Canned().then(200, BY_HASH);
        List<Candidate> found = new ZxInfo(http).search(
                new AGame("dc93198d211b845a1977b0b38506ce50"));

        assertEquals(1, found.size());
        assertTrue("a hash match must be exact, or nothing scrapes unattended",
                   found.get(0).exact);
        assertEquals("the id is under entry_id, not id - see BY_HASH",
                     "0000255", found.get(0).handle);

        assertTrue("it did not ask filecheck: " + http.asked.get(0),
                   http.asked.get(0).contains("/filecheck/dc93198d"));
    }

    /**
     * A name match never is, however good the ranking.
     *
     * Their own search prioritises original entries over modified ones, which
     * is better than anything written here would be - and still a guess. A
     * guess acted on silently is one game's cover on another for ever.
     */
    @Test
    public void anameMatchIsNeverCertain() throws Exception {
        Canned http = new Canned().then(404, "").then(200, SEARCH);
        List<Candidate> found = new ZxInfo(http).search(new AGame("deadbeef"));

        assertEquals(2, found.size());
        for (Candidate candidate : found) {
            assertFalse("a name match claimed to be exact", candidate.exact);
        }

        assertEquals("Head over Heels", found.get(0).name);
        assertEquals("1987", found.get(0).year);
        assertEquals("Ocean Software Ltd", found.get(0).publisher);
    }

    /** The search asks about the title alone, and asks for the title alone. */
    @Test
    public void thesearchIsRestrictedToTitles() throws Exception {
        Canned http = new Canned().then(404, "").then(200, SEARCH);
        new ZxInfo(http).search(new AGame(null));

        String asked = http.asked.get(http.asked.size() - 1);

        assertTrue("without titlesonly a file named after its publisher brings"
                   + " back everything they published: " + asked,
                   asked.contains("titlesonly=true"));
        assertTrue(asked.contains("Head%20over%20Heels") || asked.contains("Head+over+Heels"));
    }

    /**
     * Nothing is filtered by kind, machine or file type.
     *
     * The one that would actually bite: PENTAGON is a sibling of ZXSPECTRUM in
     * their scheme rather than a variant of it, so filtering to Spectrum would
     * silently drop the Pentagon demoscene - which is most of what arrives as
     * .trd and .scl. genretype would drop demos and magazines with it.
     */
    @Test
    public void nothingIsFilteredOutOfTheSearch() throws Exception {
        Canned http = new Canned().then(404, "").then(200, SEARCH);
        new ZxInfo(http).search(new AGame(null));

        String asked = http.asked.get(http.asked.size() - 1);

        for (String filter : new String[] { "genretype", "machinetype",
                                            "contenttype", "tosectype" }) {
            assertFalse("the search narrowed by " + filter + ", which can only"
                        + " lose the right answer: " + asked,
                        asked.contains(filter));
        }
    }

    /** A hash nobody knows falls through to the name, rather than failing. */
    @Test
    public void anunknownHashFallsThroughToTheName() throws Exception {
        Canned http = new Canned().then(404, "").then(200, SEARCH);

        assertEquals(2, new ZxInfo(http).search(new AGame("deadbeef")).size());
        assertEquals("both were asked", 2, http.asked.size());
    }

    /** And a game nobody has heard of at all is an empty answer, not an error.
     *  Most of a Spectrum collection is obscure; throwing here would stop a
     *  collection-wide run within a dozen games. */
    @Test
    public void agameNobodyKnowsIsAnEmptyAnswer() throws Exception {
        Canned http = new Canned().then(404, "").then(200, NO_HITS);

        assertTrue(new ZxInfo(http).search(new AGame("deadbeef")).isEmpty());
    }

    // --- the record ----------------------------------------------------------------------

    private static Provider.Scraped fetched(Provider.Wanted wanted) throws Exception {
        return new ZxInfo(new Canned().then(200, RECORD))
                .fetch(new Candidate("0002259", "Head over Heels", null, null, true), wanted);
    }

    @Test
    public void thefactsComeOutOfTheRecord() throws Exception {
        Meta meta = fetched(Provider.Wanted.nothing()).meta;

        assertEquals("Head over Heels", meta.name);
        assertEquals("Ocean Software Ltd", meta.publisher);
        assertEquals("1", meta.players);
        assertTrue(meta.desc.startsWith("Jon Ritman"));
    }

    /**
     * The genre arrives in two levels and is kept that way.
     *
     * Joined, "Arcade Game: Adventure" would be one facet among hundreds in a
     * filter that splits on commas. Apart, it is two useful lists.
     */
    @Test
    public void thegenreIsKeptInItsTwoLevels() throws Exception {
        Meta meta = fetched(Provider.Wanted.nothing()).meta;

        assertEquals("Arcade Game", meta.genre);
        assertEquals("Adventure", meta.subgenre);
    }

    /** ZXDB scores out of ten; the store keeps ES-DE's fraction. 8.48 of ten
     *  is 0.848, which {@code Meta.stars} shows as 4.2 of five. */
    @Test
    public void thescoreBecomesAFractionOutOfOne() throws Exception {
        Meta meta = fetched(Provider.Wanted.nothing()).meta;

        assertEquals("0.8480", meta.rating);
        assertEquals(4.24f, meta.ratingOutOfFive(), 0.01f);
    }

    /** A year alone becomes the stamp ES-DE writes, so both providers sort
     *  together. */
    @Test
    public void theyearBecomesTheSameStampEverythingElseUses() throws Exception {
        assertEquals("19870101T000000", fetched(Provider.Wanted.nothing()).meta.released);
        assertEquals("1987", fetched(Provider.Wanted.nothing()).meta.year());
    }

    /**
     * The developer is the person who wrote it.
     *
     * A judgement call worth pinning down: ZXDB keeps people and companies
     * apart where ES-DE and ScreenScraper both mean a company by "developer".
     * The author is closer to what that row is for than the publisher printed
     * twice.
     */
    @Test
    public void thedeveloperIsTheAuthorRatherThanThePublisherAgain() throws Exception {
        Meta meta = fetched(Provider.Wanted.nothing()).meta;

        assertEquals("Jon Ritman", meta.developer);
        assertFalse(meta.developer.equals(meta.publisher));
    }

    /**
     * The two that will change what the app does, rather than what it shows.
     *
     * Kept verbatim, in the provider's own words. Turning
     * "ZX-Spectrum 48K/128K" into one of Fuse's fourteen machines is a
     * decision about what somebody wants - that value names two - and belongs
     * where the machine gets switched, not in a store.
     */
    @Test
    public void themachineAndTheInputsAreRecordedAsTheServiceStatesThem() throws Exception {
        Meta meta = fetched(Provider.Wanted.nothing()).meta;

        assertEquals("ZX-Spectrum 48K/128K", meta.machine);
        assertEquals(java.util.Arrays.asList("Cursor", "Kempston Joystick"), meta.inputs);
    }

    /** And a record with no controls gives an empty list, never null - the
     *  one field here that is iterated at every call site. */
    @Test
    public void arecordWithNoControlsGivesAnEmptyListRatherThanNull() throws Exception {
        Provider.Scraped scraped = new ZxInfo(new Canned().then(200, ONLY_A_SCR))
                .fetch(new Candidate("1", "Something", null, null, true),
                       Provider.Wanted.nothing());

        assertNotNull(scraped.meta.inputs);
        assertTrue(scraped.meta.inputs.isEmpty());
    }

    /** Nothing invents a source or a path: both belong to whoever asked. */
    @Test
    public void thepathAndTheSourceAreTheCallersToSet() throws Exception {
        Meta meta = fetched(Provider.Wanted.nothing()).meta;

        assertNull(meta.path);
        assertNull(meta.source);
    }

    // --- media ----------------------------------------------------------------------------

    private static String urlIn(List<Medium> media, String folder) {
        for (Medium medium : media) {
            if (medium.folder.equals(folder)) return medium.url;
        }
        return null;
    }

    @Test
    public void themediaLandInTheFoldersTheAppAlreadyDraws() throws Exception {
        List<Medium> media = fetched(Provider.Wanted.usual()).media;

        assertEquals("covers, screenshots and titlescreens were asked for", 3, media.size());
        assertTrue(urlIn(media, "covers").endsWith("/HeadOverHeels.jpg"));
        assertTrue(urlIn(media, "screenshots").endsWith("/HeadOverHeels.gif"));
        assertTrue(urlIn(media, "titlescreens").endsWith("/HeadOverHeels-load.png"));
    }

    /**
     * The three the app now has folders for, from a real record.
     *
     * A media scan is a photograph of the cassette or the disk, which is
     * exactly what ES-DE's {@code physicalmedia} already meant, so it needed
     * no folder of its own. A map and an advertisement did: ES-DE has neither.
     * Worth having, over the whole of ZXDB - a scan is on 11.8% of Spectrum
     * entries, a map on 5.3%, an advertisement on 4.0%.
     */
    @Test
    public void thethreeExtraKindsLandInTheirOwnFolders() throws Exception {
        List<Medium> media = fetched(
                Provider.Wanted.of("physicalmedia", "maps", "adverts")).media;

        assertEquals(3, media.size());
        assertTrue(urlIn(media, "physicalmedia").endsWith("/HeadOverHeels_Media.jpg"));
        assertTrue(urlIn(media, "maps").endsWith("/HeadOverHeels.jpg"));
        assertTrue(urlIn(media, "adverts").endsWith("/HeadOverHeels.jpg"));

        assertTrue("a map is on the file host like everything else",
                   urlIn(media, "maps").contains("/games-maps/"));
    }

    /**
     * One per folder, even where the record has four.
     *
     * Head over Heels has four maps and three media scans, and the store
     * keeps one file per folder per game - {@code maps/HeadOverHeels.jpg} and
     * nowhere to put a second. The first wins, which is the same rule every
     * other folder here follows; showing all four would mean teaching {@code
     * Artwork} to enumerate a folder rather than resolve a name, and that is
     * a different piece of work.
     */
    @Test
    public void onlyTheFirstOfSeveralMapsIsTaken() throws Exception {
        List<Medium> media = fetched(Provider.Wanted.of("maps")).media;

        assertEquals(1, media.size());
        assertTrue("the first map in the record is the one taken: "
                   + media.get(0).url,
                   media.get(0).url.endsWith("/HeadOverHeels.jpg"));
    }

    /**
     * The cheats, which are not a picture at all.
     *
     * A {@code .pok} is a few lines of text in the format the bundled poke
     * database is built from, so a fetched one drops straight into the cheats
     * page. On 7.9% of Spectrum entries, against a bundled database that
     * finds pokes for about a third of a real collection - so this is what
     * tops it up rather than what replaces it.
     */
    @Test
    public void thepokesAreFetchedAsTheirOwnKindOfFile() throws Exception {
        List<Medium> media = fetched(Provider.Wanted.of("pokes")).media;

        assertEquals(1, media.size());
        assertEquals("pokes", media.get(0).folder);
        assertEquals("pok", media.get(0).extension);
        assertTrue(media.get(0).url.endsWith(".pok"));
    }

    /** And nothing else is taken as one - a picture in the pokes folder
     *  would be a file the cheats page tries to read as text. */
    @Test
    public void onlyApokFileIsTakenAsPokes() throws Exception {
        String wrong = RECORD.replace(
                "{\"type\":\"POK pokes file\",\"format\":\"Pokes (POK)\","
                + "     \"path\":\"/zxdb/sinclair/pokes/h/Head over Heels (1987)(Ocean Software).pok\"}",
                "{\"type\":\"POK pokes file\",\"format\":\"Picture (JPG)\","
                + "     \"path\":\"/zxdb/sinclair/pokes/h/HeadOverHeels.jpg\"}");

        Provider.Scraped scraped = new ZxInfo(new Canned().then(200, wrong))
                .fetch(new Candidate("1", "x", null, null, true),
                       Provider.Wanted.of("pokes"));

        assertTrue("a jpg was taken as a pokes file", scraped.media.isEmpty());
    }

    /**
     * A dump is a loading screen and nothing else.
     *
     * The folder is the guard: a {@code .scr} named as a cover or a map is
     * either a mistake in the record or a format this app has not thought
     * about, and either way it must not become the picture every row draws.
     */
    @Test
    public void adumpIsOnlyEverTakenAsAloadingScreen() throws Exception {
        String asAcover = ONLY_A_SCR.replace("Loading screen", "Inlay - Front");

        Provider.Scraped scraped = new ZxInfo(new Canned().then(200, asAcover))
                .fetch(new Candidate("1", "Something", null, null, true),
                       Provider.Wanted.of("covers", "titlescreens"));

        assertTrue("a .scr was taken as a cover", scraped.media.isEmpty());
    }

    /** Off spectrumcomputing.co.uk, not the API: these are static files and
     *  cost nothing against anything. */
    @Test
    public void themediaComeFromTheArchiveRatherThanTheApi() throws Exception {
        for (Medium medium : fetched(Provider.Wanted.usual()).media) {
            assertTrue(medium.url + " is not on a file host",
                       medium.url.startsWith("https://spectrumcomputing.co.uk/")
                       || medium.url.startsWith("https://zxinfo.dk/media/"));
            assertFalse("a medium was pointed at the API",
                        medium.url.contains("api.zxinfo.dk"));
        }
    }

    /**
     * A rendered loading screen comes off ZXInfo's own host, not the archive.
     *
     * The two are mixed inside one {@code screens} array - the running screen
     * below is on the archive and the loading screen is not - so the prefix
     * is the only thing that decides. Fetching a {@code /zxscreens/} path from
     * the archive returns a 404 page, which {@code Downloads} discards, which
     * looks exactly like a game with no loading screen. Every one this
     * provider offered was lost that way until it was measured.
     */
    @Test
    public void arenderedLoadingScreenComesFromZxinfosOwnHost() throws Exception {
        List<Medium> media = fetched(Provider.Wanted.of("titlescreens", "screenshots")).media;

        assertEquals("https://zxinfo.dk/media/zxscreens/0002259/HeadOverHeels-load.png",
                     urlIn(media, "titlescreens"));
        assertTrue("the running screen is on the archive: " + urlIn(media, "screenshots"),
                   urlIn(media, "screenshots")
                           .startsWith("https://spectrumcomputing.co.uk/pub/"));
    }

    /** Only what was asked for. */
    @Test
    public void nothingUnwantedIsFetched() throws Exception {
        assertTrue(fetched(Provider.Wanted.nothing()).media.isEmpty());

        List<Medium> covers = fetched(Provider.Wanted.of("covers")).media;
        assertEquals(1, covers.size());
        assertEquals("covers", covers.get(0).folder);
    }

    /**
     * When a loading screen exists as both a picture and a raw {@code .scr},
     * the picture wins.
     *
     * This one is about order, not filtering: {@code screens} is read before
     * {@code additionalDownloads} and a folder keeps the first thing it is
     * given. The filtering is the test below, and the two were one test until
     * a deliberately broken build showed it passing with the filter removed -
     * the dedupe was doing all the work and the {@code .scr} check was never
     * reached.
     */
    @Test
    public void thepictureLoadingScreenIsPreferredToTheRawDump() throws Exception {
        String title = urlIn(fetched(Provider.Wanted.of("titlescreens")).media, "titlescreens");

        assertTrue("expected the picture, got " + title, title.endsWith(".png"));
    }

    /** A record whose only loading screen is a {@code .scr}, which is what
     *  plenty of entries have. */
    private static final String ONLY_A_SCR = "{\"_source\":{"
            + "\"title\":\"Something\","
            + "\"additionalDownloads\":["
            + "  {\"type\":\"Loading screen\",\"format\":\"Screen dump (SCR)\","
            + "   \"path\":\"/pub/sinclair/screens/load/s/scr/Something.scr\"}"
            + "]}}";

    /**
     * And where the {@code .scr} is all there is, it is taken.
     *
     * This used to assert the opposite, and said why: 6912 bytes of Spectrum
     * memory is not an image file, and fetching one would have left {@code
     * Artwork} resolving a file {@code BitmapFactory} cannot decode - a
     * permanently broken thumbnail hiding the fact that no picture was found.
     * "This app could render it, being an emulator, and does not yet" was the
     * closing line. It does now: {@code Downloads} converts the dump into a
     * png as it lands, so what reaches the folder is a picture like any other.
     *
     * Worth the change for how common it is - a third of ZXDB's Spectrum
     * entries carry their loading screen only this way, against a fifth with
     * a front inlay.
     */
    @Test
    public void arawScrIsTakenWhenItIsTheOnlyLoadingScreen() throws Exception {
        Provider.Scraped scraped = new ZxInfo(new Canned().then(200, ONLY_A_SCR))
                .fetch(new Candidate("1", "Something", null, null, true),
                       Provider.Wanted.of("titlescreens"));

        assertEquals(1, scraped.media.size());
        assertEquals("titlescreens", scraped.media.get(0).folder);
        assertEquals("it has to reach Downloads as a dump to be converted there",
                     "scr", scraped.media.get(0).extension);
    }

    /** Instructions come as PDF and as text, and the manual viewer reads
     *  PDFs. */
    @Test
    public void onlyThePdfInstructionsAreTakenAsAManual() throws Exception {
        String manual = urlIn(fetched(Provider.Wanted.of("manuals")).media, "manuals");

        assertTrue("the text instructions were taken as a manual: " + manual,
                   manual.endsWith(".pdf"));
    }

    /**
     * The running screen is listed in both places, and is fetched once.
     *
     * {@code screens} and {@code additionalDownloads} overlap, and a folder
     * taking two media would mean the second overwriting the first on disk -
     * a wasted download and, on a slow connection, a visible flicker.
     */
    @Test
    public void amediumListedTwiceIsFetchedOnce() throws Exception {
        List<Medium> media = fetched(Provider.Wanted.of("screenshots")).media;

        assertEquals(1, media.size());
    }

    /** No checksums: ZXDB publishes none per file, so Downloads must skip the
     *  verification it does for ScreenScraper rather than discard everything. */
    @Test
    public void themediaCarryNoChecksumAndSaySo() throws Exception {
        for (Medium medium : fetched(Provider.Wanted.usual()).media) {
            assertNull(medium.md5);
        }
    }

    // --- what it costs, and what stops it -----------------------------------------------

    /**
     * Two requests a game whatever is asked for, because the pictures are
     * free.
     *
     * The number the sweep multiplies by. Against ScreenScraper the same two
     * hundred games with the default three media is eight hundred requests;
     * here it is four hundred, and adding video and manuals changes it not at
     * all.
     */
    @Test
    public void agameCostsTwoRequestsHoweverMuchIsWanted() {
        Provider zxinfo = new ZxInfo(new Canned());

        assertEquals(2, zxinfo.costPerGame(Provider.Wanted.nothing()));
        assertEquals(2, zxinfo.costPerGame(Provider.Wanted.usual()));
        assertEquals(2, zxinfo.costPerGame(Provider.Wanted.of(
                "covers", "screenshots", "titlescreens", "backcovers",
                "physicalmedia", "miximages", "videos", "manuals")));
    }

    /** It needs no credentials, which is why a source clone can scrape. */
    @Test
    public void itisAlwaysConfigured() {
        assertTrue(new ZxInfo(new Canned()).configured());
    }

    /**
     * Being refused stops a run rather than letting it hammer on.
     *
     * This service says "enough" by blocking an address for hours, so 429 and
     * 403 have to be the kind of failure {@code Sweep} stops for. Mapping them
     * to something it carries on past would turn one refusal into eight
     * hundred.
     */
    @Test
    public void beingRefusedIsTheKindOfFailureThatStopsASweep() {
        Provider zxinfo = new ZxInfo(new Canned());

        assertEquals(ScrapeException.Kind.CLOSED, zxinfo.refusalFor(429).kind);
        assertEquals(ScrapeException.Kind.CLOSED, zxinfo.refusalFor(403).kind);

        // A server having a bad minute is worth waiting out, not stopping for.
        assertTrue(zxinfo.refusalFor(503).worthWaiting());
    }

    /** A body that is not JSON is a reply this build cannot read, and says so
     *  rather than pretending the game is unknown. */
    @Test
    public void abodyThatIsNotJsonIsMalformedRatherThanEmpty() {
        Canned http = new Canned().then(200, "<html>maintenance</html>");

        try {
            new ZxInfo(http).fetch(new Candidate("1", "x", null, null, true),
                                   Provider.Wanted.nothing());
            fail("a page of HTML was accepted as a record");
        } catch (ScrapeException e) {
            assertEquals(ScrapeException.Kind.MALFORMED, e.kind);
        }
    }

    // --- the fields ES-DE's schema had no room for -------------------------------------

    private static Meta metaOf(String record) throws Exception {
        return new ZxInfo(new Canned().then(200, "{\"_source\":" + record + "}"))
                .fetch(new Candidate("1", "x", null, null, true),
                       Provider.Wanted.nothing()).meta;
    }

    /**
     * The credits, with a role only where the record names one.
     *
     * ZXDB gives the main creators no role at all - it keeps them for
     * specialists - so a list of roles would be mostly empty and a list of
     * names is what anybody wants to read. Nine per cent of entries have a
     * role on anybody; sixty-four have an author.
     */
    @Test
    public void theauthorsCarryTheirRolesWhereThereAreAny() throws Exception {
        assertEquals(Arrays.asList("Jon Ritman", "F. David Thorpe (Load Screen)"),
                     metaOf(HEAD_OVER_HEELS).authors);

        assertEquals(Arrays.asList("Julian Gollop", "Julek Heller (Inlay/Poster Art)"),
                     metaOf(CHAOS).authors);
    }

    /** The developer is still the first author, and the credits do not
     *  replace it - one is the row every provider fills in. */
    @Test
    public void thedeveloperIsStillTheFirstOfThem() throws Exception {
        assertEquals("Jon Ritman", metaOf(HEAD_OVER_HEELS).developer);
    }

    /**
     * The price, with the symbol on the side the record says.
     *
     * {@code prefix} decides it: a pound sign leads, and the currencies that
     * follow their amount would read as nonsense the other way round.
     */
    @Test
    public void thepriceIsFormattedTheWayTheRecordSpellsIt() throws Exception {
        assertEquals("£7.95", metaOf(HEAD_OVER_HEELS).price);
    }

    /**
     * The series, without the game itself in it.
     *
     * ZXDB's list includes the entry you asked about - Chaos is the first of
     * the "Chaos" series - and a game listing itself among its related games
     * reads as a bug.
     */
    @Test
    public void theseriesIsNamedAndDoesNotListTheGameItself() throws Exception {
        Meta meta = metaOf(CHAOS);

        assertEquals("Chaos", meta.series);
        assertEquals(Collections.singletonList(new Meta.Link("2930", "Lords of Chaos")),
                     meta.seriesGames);
    }

    /** Both directions of compilation: what this is on, and - for a
     *  compilation - what is on it. */
    @Test
    public void thecompilationsAreReadBothWaysRound() throws Exception {
        Meta game = metaOf(HEAD_OVER_HEELS);

        assertEquals(Arrays.asList(
                        new Meta.Link("12019", "Dixons Premier Collection for Your +2"),
                        new Meta.Link("14204", "Outlet issue 117")),
                     game.compilations);
        assertTrue(game.contents.isEmpty());

        Meta compilation = metaOf(COMPILATION);

        assertEquals(Arrays.asList(new Meta.Link("1860", "Freddy Hardest"),
                                   new Meta.Link("5129", "Tank")),
                     compilation.contents);
        assertTrue(compilation.compilations.isEmpty());
    }

    /** A record with none of them yields none of them, which is most records:
     *  a quarter carry a price, six per cent a series. */
    @Test
    public void arecordWithoutThemYieldsNothingRatherThanEmptyText() throws Exception {
        Meta meta = metaOf("{\"title\":\"Bare\"}");

        assertNull(meta.price);
        assertNull(meta.series);
        assertTrue(meta.authors.isEmpty());
        assertTrue(meta.seriesGames.isEmpty());
        assertTrue(meta.compilations.isEmpty());
        assertTrue(meta.contents.isEmpty());
    }

    /** And the whole record is asked for in the mode that carries them. */
    @Test
    public void therecordIsAskedForCompactRatherThanFull() throws Exception {
        Canned http = new Canned().then(200, RECORD);
        new ZxInfo(http).fetch(new Candidate("0002259", "x", null, null, true),
                               Provider.Wanted.nothing());

        assertTrue("asked for " + http.asked.get(0),
                   http.asked.get(0).contains("mode=compact"));
    }
}
