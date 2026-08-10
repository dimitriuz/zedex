package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.ZxInfo;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ZXInfo as something to browse rather than something to ask.
 *
 * On a device rather than the JVM because this class builds URLs with
 * android.net.Uri and logs with android.util.Log, and the stub android.jar
 * answers both with null - silently, under returnDefaultValues - so a unit
 * test here would assert against a URL that was never encoded.
 *
 * Every body below is meant to be one the service actually sent, trimmed to
 * what is asserted on - and each says which it is. Writing one from memory is
 * how a client comes to believe a field name the service does not use, which
 * this app has now been caught by three times: /filecheck answers entry_id
 * where the specification says id; {@link #METADATA} below claimed a genretype
 * array where the service sends genretypes; and the body {@link #RECORD}
 * replaced said {@code "format":"TZX"} where every live record says
 * {@code "Perfect tape (TZX)"}, which made {@code Pick.forGame} answer null for
 * <b>every entry in the database</b> - a whole feature inert, with this file
 * green. Each time the parser and the fixture agreed with each other and both
 * disagreed with the service, which is the one class of mistake a canned test
 * cannot catch unless the can was filled from a reply. {@link #METADATA} is
 * kept, marked, as the reason {@link #METADATA_LIVE} exists.
 *
 * <b>What a canned body cannot attest to is the URL.</b> A wrong parameter name
 * is ignored rather than refused by this service - it answers 200 with a full
 * unfiltered result set that reads exactly like a shelf that works - so the
 * requests are asserted on directly, shelf by shelf, further down.
 */
@RunWith(AndroidJUnit4.class)
public class ZxInfoCatalogueTest {

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

    /** {@code /search?query=head+over+heels&mode=compact&size=2&offset=0},
     *  trimmed to two hits and the fields a row draws. */
    private static final String SEARCH = "{"
            + "\"hits\":{\"total\":{\"value\":37},\"hits\":["
            + "  {\"_id\":\"0002259\",\"_source\":{"
            + "     \"title\":\"Head over Heels\",\"originalYearOfRelease\":1987,"
            + "     \"genreType\":\"Arcade Game\",\"availability\":\"Available\","
            + "     \"publishers\":[{\"name\":\"Ocean Software Ltd\"}],"
            + "     \"screens\":[{\"type\":\"Loading screen\",\"format\":\"Picture\","
            + "                   \"url\":\"/zxscreens/0002259/HeadOverHeels-load.png\"}]}},"
            + "  {\"_id\":\"0021418\",\"_source\":{"
            + "     \"title\":\"Head over Heels 128\",\"originalYearOfRelease\":1987,"
            + "     \"genreType\":\"Arcade Game\",\"availability\":\"Never released\","
            + "     \"publishers\":[]}}"
            + "]}}";

    /**
     * {@code /games/0002259?mode=compact}, <b>recorded</b>, trimmed to two of
     * its five releases and the fields read here.
     *
     * Every phrase in it is the service's own. The one this replaced said
     * {@code "format":"TZX"} and {@code "releaseYear":1987}, both written from
     * memory, and both wrong in a way that could only be seen against a live
     * reply: the field holds <b>a human phrase</b> - {@code Perfect tape
     * (TZX)}, {@code Tape (TAP)}, {@code Screen dump (SCR)} - and the year is
     * under {@code yearOfRelease}. The first of those made
     * {@code Pick.forGame} answer null for every entry in the database, so
     * nothing could be imported at all, and this file passed either way.
     *
     * The screens are kept because they carry the two-host rule inside one
     * record: the rendered loading screen is under {@code /zxscreens/} and the
     * running screen under {@code /pub/}, in the same array.
     */
    private static final String RECORD = "{"
            + "\"_index\":\"zxinfo-20260723-075659\",\"_id\":\"0002259\",\"found\":true,"
            + "\"_source\":{"
            + "  \"title\":\"Head over Heels\",\"originalYearOfRelease\":1987,"
            + "  \"machineType\":\"ZX-Spectrum 48K/128K\","
            + "  \"genre\":\"Arcade Game: Adventure\",\"genreType\":\"Arcade Game\","
            + "  \"availability\":\"Available\","
            + "  \"publishers\":[{\"publisherSeq\":1,\"name\":\"Ocean Software Ltd\","
            + "                   \"country\":\"UK\"}],"
            + "  \"releases\":["
            + "    {\"releaseSeq\":0,"
            + "     \"publishers\":[{\"publisherSeq\":1,\"name\":\"Ocean Software Ltd\"}],"
            + "     \"releaseTitles\":[\"Foot and Mouth\"],\"yearOfRelease\":1987,"
            + "     \"files\":["
            + "       {\"path\":\"/pub/sinclair/games/h/HeadOverHeels.tap.zip\","
            + "        \"size\":37132,\"type\":\"Tape image\",\"format\":\"Tape (TAP)\","
            + "        \"origin\":null,\"encodingScheme\":\"Undetermined\"},"
            + "       {\"path\":\"/pub/sinclair/games/h/HeadOverHeels.tzx.zip\","
            + "        \"size\":38570,\"type\":\"Tape image\","
            + "        \"format\":\"Perfect tape (TZX)\","
            + "        \"origin\":\"Original release (O)\",\"encodingScheme\":\"SpeedLock 2\"},"
            + "       {\"path\":\"/zxdb/sinclair/entries/0002259/HeadOverHeels(ULAplus).tap.zip\","
            + "        \"size\":37240,\"type\":\"Tape image\",\"format\":\"Tape (TAP)\","
            + "        \"comments\":\"ULAplus version\",\"encodingScheme\":\"None\"}"
            + "    ]},"
            + "    {\"releaseSeq\":4,"
            + "     \"publishers\":[{\"publisherSeq\":1,\"name\":\"The Hit Squad\"}],"
            + "     \"releaseTitles\":[\"ARCADE COLLECTION 12: Head over Heels\"],"
            + "     \"yearOfRelease\":1990,\"code\":\"AC12\",\"barcode\":\"5013156410802\","
            + "     \"files\":["
            + "       {\"path\":\"/pub/sinclair/games/h/HeadOverHeels(TheHitSquad).tzx.zip\","
            + "        \"size\":38504,\"type\":\"Tape image\","
            + "        \"format\":\"Perfect tape (TZX)\","
            + "        \"origin\":\"Re-release (R)\",\"encodingScheme\":\"SpeedLock 2\"}"
            + "    ]}],"
            + "  \"additionalDownloads\":["
            + "    {\"path\":\"/zxdb/sinclair/entries/0002259/HeadOverHeels(EN).pdf\","
            + "     \"size\":1061210,\"type\":\"Instructions\",\"format\":\"Document (PDF)\","
            + "     \"language\":\"English\"},"
            + "    {\"path\":\"/pub/sinclair/screens/load/h/scr/HeadOverHeels.scr\","
            + "     \"size\":6912,\"type\":\"Loading screen\",\"format\":\"Screen dump (SCR)\","
            + "     \"language\":null},"
            + "    {\"path\":\"/pub/sinclair/music/ay/games/h/HeadOverHeels.ay.zip\","
            + "     \"size\":2404,\"type\":\"Ripped in-game/theme music in AY format\","
            + "     \"format\":\"Music (AY)\",\"language\":null}"
            + "  ],"
            + "  \"screens\":["
            + "    {\"filename\":\"HeadOverHeels-load.png\","
            + "     \"url\":\"/zxscreens/0002259/HeadOverHeels-load.png\","
            + "     \"scrUrl\":\"/pub/sinclair/screens/load/h/scr/HeadOverHeels.scr\","
            + "     \"size\":8491,\"type\":\"Loading screen\",\"format\":\"Picture\"},"
            + "    {\"filename\":\"HeadOverHeels.gif\","
            + "     \"url\":\"/pub/sinclair/screens/in-game/h/HeadOverHeels.gif\","
            + "     \"size\":6878,\"type\":\"Running screen\",\"format\":\"Picture (GIF)\"}"
            + "  ]}}";

    /**
     * An entry that <b>has a recording</b> - 0009305, Atic Atac - and the one
     * thing it proves that {@link #RECORD} cannot.
     *
     * <b>Recorded, with one honest join:</b> the {@code _source} is the one a
     * compact <em>search</em> answered with for this entry, wrapped in the
     * {@code /games} envelope. A compact search's releases carry only
     * {@code publishers} and {@code files} - no {@code yearOfRelease}, no
     * {@code releaseSeq} - so this fixture is evidence about the recording and
     * about {@code Distribution denied}, and {@link #RECORD} is the evidence
     * about a release's fields.
     *
     * <b>The rzx is in {@code additionalDownloads}, where the service puts
     * it.</b> Measured over every captured reply that carries one: six
     * recordings, every one of them there and none in a release's files. The
     * body this replaced invented one inside a release, which is why
     * {@code Pick.recording} could answer for the fixture and never for the
     * service.
     */
    private static final String RECORD_WITH_RECORDING = "{"
            + "\"_id\":\"0009305\",\"found\":true,\"_source\":{"
            + "  \"title\":\"Atic Atac\",\"originalYearOfRelease\":1983,"
            + "  \"genreType\":\"Arcade Game\",\"availability\":\"Distribution denied\","
            + "  \"publishers\":[{\"publisherSeq\":1,\"name\":\"Ultimate Play The Game\"}],"
            + "  \"releases\":["
            + "    {\"publishers\":[{\"publisherSeq\":1,\"name\":\"Ultimate Play The Game\"}],"
            + "     \"files\":[{\"path\":\"/denied/entries/0009305/AticAtac.tzx.zip\","
            + "                \"size\":24495,\"type\":\"Tape image\","
            + "                \"format\":\"Perfect tape (TZX)\",\"encodingScheme\":\"None\"}]},"
            + "    {\"publishers\":[{\"publisherSeq\":1,\"name\":\"Microbyte [ES]\"}],"
            + "     \"files\":[]}],"
            + "  \"additionalDownloads\":["
            + "    {\"path\":\"/denied/entries/0009305/AticAtac.rzx.zip\",\"size\":75857,"
            + "     \"type\":\"RZX playback file\",\"format\":\"Game recording (RZX)\","
            + "     \"language\":null}"
            + "  ]}}";

    /**
     * <b>Not a recording.</b> No captured reply holds an absolute path in a
     * release's files or in {@code additionalDownloads} - every one of them is
     * relative, under {@code /pub/}, {@code /zxdb/} or {@code /denied/}. This
     * body exists to pin {@code urlFor}'s passthrough, which is there because
     * ZXDB is said to keep some recordings on archive.org; that claim is this
     * project's own and is <b>unverified</b>. Joining such a path onto a base
     * would make a url with an https:// in the middle of it, so the
     * passthrough is worth keeping and worth labelling.
     */
    private static final String RECORD_WITH_AN_ABSOLUTE_PATH = "{"
            + "\"_id\":\"0009305\",\"found\":true,\"_source\":{"
            + "  \"title\":\"Atic Atac\","
            + "  \"releases\":[{\"publishers\":[{\"name\":\"Ultimate Play The Game\"}],"
            + "    \"files\":[{\"type\":\"RZX playback file\","
            + "               \"format\":\"Game recording (RZX)\",\"size\":75857,"
            + "               \"path\":\"https://archive.org/download/zx_rzx/AticAtac.rzx.zip\"}]}]}}";

    /**
     * {@code /metadata/} as this class first believed it - <b>written from
     * memory, and wrong</b>. The array is {@code genretypes}; see
     * {@link #METADATA_LIVE}. Kept because the parser is deliberately lenient
     * about which of the two it finds, and this is what pins that leniency.
     */
    private static final String METADATA = "{"
            + "\"genretype\":[{\"key\":\"Arcade Game\",\"doc_count\":12184},"
            + "               {\"key\":\"Utility\",\"doc_count\":5731}]}";

    /**
     * The same call, under the name the service actually uses.
     *
     * <b>Measured, and it corrected the body above:</b> asked on 2026-08-10,
     * {@code /metadata/} answered 5,289 bytes whose three top-level keys are
     * {@code machinetypes}, {@code genretypes} and {@code features} - plural.
     * A parser reading the singular found no genres at all and handed back an
     * empty Categories shelf, which on screen is indistinguishable from a
     * screen that failed to draw.
     *
     * The two arrays that are empty here are empty because they were not read,
     * not because the service sent them so; and the buckets inside
     * {@code genretypes} are carried over from the body above rather than
     * measured, since the one request this was allowed established the array's
     * name and not its contents. Both are honest gaps rather than inventions -
     * see {@code ZxInfoCatalogue.keyOf}, which is lenient for that reason.
     */
    private static final String METADATA_LIVE = "{"
            + "\"machinetypes\":[],"
            + "\"genretypes\":[{\"key\":\"Arcade Game\",\"doc_count\":12184},"
            + "                {\"key\":\"Utility\",\"doc_count\":5731}],"
            + "\"features\":[]}";

    // --- the shelves --------------------------------------------------------------------

    /** Declared, and asking for them makes no request - the tab builds itself
     *  on the UI thread. */
    @Test
    public void theshelvesAreDeclaredWithoutAsking() {
        Canned http = new Canned();

        List<Catalogue.Shelf> shelves = new ZxInfoCatalogue(http).shelves();

        assertEquals("declaring the shelves cost a request", 0, http.asked.size());
        assertFalse(shelves.isEmpty());
        assertTrue(hasShelf(shelves, "search"));
        assertTrue(hasShelf(shelves, "genres"));
    }

    /** ZXInfo needs no credentials, so it is always configured - which is
     *  what makes it the one that ships first. */
    @Test
    public void itneedsNoCredentials() {
        assertTrue(new ZxInfoCatalogue(new Canned()).configured());
    }

    // --- searching -----------------------------------------------------------------------

    @Test
    public void asearchAnswersRowsAndAtotal() throws Exception {
        Canned http = new Canned().then(200, SEARCH);

        Catalogue.Page page = new ZxInfoCatalogue(http).open(
                shelf(http, "search"), Catalogue.Query.text("head over heels"), 0);

        assertEquals(2, page.items().size());
        assertEquals(37, page.total());
        assertTrue(page.hasMore());

        Catalogue.Item first = page.items().get(0);
        assertEquals("0002259", first.id());
        assertEquals("Head over Heels", first.title());
        assertEquals("1987", first.year());
        assertEquals("Ocean Software Ltd", first.publisher());
        assertEquals("Arcade Game", first.kind());
        assertTrue(first.available());
    }

    /**
     * <b>Compact, and never filtered by machine.</b>
     *
     * PENTAGON is a sibling of ZXSPECTRUM in ZXInfo's scheme rather than a
     * variant of it, so filtering to Spectrum silently drops the Pentagon
     * demoscene - most of what arrives as .trd and .scl. Asserted on the URL
     * because there is nothing in a reply that would ever show it.
     */
    @Test
    public void thesearchIscompactAndUnfiltered() throws Exception {
        Canned http = new Canned().then(200, SEARCH);

        new ZxInfoCatalogue(http).open(shelf(http, "search"),
                                       Catalogue.Query.text("head over heels"), 0);

        String url = http.asked.get(0);
        assertTrue(url, url.contains("mode=compact"));
        assertFalse("a machine filter was applied", url.contains("machinetype"));
        assertFalse("a content filter was applied", url.contains("contenttype"));
    }

    /** The typed text is encoded, which is the whole reason this test is on a
     *  device: a space in a query is the commonest thing there is. */
    @Test
    public void whatWasTypedIsEncoded() throws Exception {
        Canned http = new Canned().then(200, SEARCH);

        new ZxInfoCatalogue(http).open(shelf(http, "search"),
                                       Catalogue.Query.text("head over heels"), 0);

        assertFalse("a raw space went into the URL", http.asked.get(0).contains(" "));
        assertTrue(http.asked.get(0).contains("head%20over%20heels")
                           || http.asked.get(0).contains("head+over+heels"));
    }

    /** The second page asks for the second page. An offset that does not move
     *  is a grid that shows the first ten games for ever, which reads as a
     *  catalogue with ten games in it. */
    @Test
    public void thesecondPageAsksForAnoffset() throws Exception {
        Canned http = new Canned().then(200, SEARCH);

        new ZxInfoCatalogue(http).open(shelf(http, "search"),
                                       Catalogue.Query.text("x"), 1);

        String url = http.asked.get(0);
        assertFalse("the second page asked for offset 0", url.contains("offset=0"));
    }

    /** An unavailable entry stays on the list, with the reason - "announced
     *  and cancelled" is a fact about a game worth reading, and a catalogue
     *  that silently omits things looks broken. */
    @Test
    public void anunavailableEntryIsAcrossedOutRowRatherThanAmissingOne() throws Exception {
        Canned http = new Canned().then(200, SEARCH);

        Catalogue.Page page = new ZxInfoCatalogue(http).open(
                shelf(http, "search"), Catalogue.Query.text("x"), 0);

        Catalogue.Item second = page.items().get(1);
        assertFalse(second.available());
        assertEquals("Never released", second.availability());
    }

    // --- one item ------------------------------------------------------------------------

    @Test
    public void anitemCarriesItsVersionsAndFiles() throws Exception {
        Canned http = new Canned().then(200, RECORD);

        Catalogue.Item game = new ZxInfoCatalogue(http).item("0002259");

        assertEquals(2, game.versions().size());
        assertEquals(3, game.versions().get(0).files().size());
        assertEquals("one request, not two", 1, http.asked.size());
    }

    /**
     * <b>A record's paths are relative to two different hosts</b>, and both
     * turn up in the same record.
     *
     * {@code /pub/} and {@code /zxdb/} are on spectrumcomputing.co.uk; every
     * rendered loading screen is under {@code /zxscreens/} on zxinfo.dk/media
     * and 404s on the archive. They arrive inside the same {@code screens}
     * array, so the array is no guide and the prefix is the only thing that
     * decides - this is how every loading screen this app offered came to be
     * fetched from the wrong host and discarded, which looks exactly like a
     * game that has none.
     */
    @Test
    public void everyUrlIsAbsoluteAndOnItsOwnHost() throws Exception {
        Canned http = new Canned().then(200, RECORD);

        Catalogue.Item game = new ZxInfoCatalogue(http).item("0002259");
        List<Catalogue.Download> files = game.versions().get(0).files();

        assertEquals("https://spectrumcomputing.co.uk/pub/sinclair/games/h/HeadOverHeels.tap.zip",
                     files.get(0).url());
        assertEquals("https://zxinfo.dk/media/zxscreens/0002259/HeadOverHeels-load.png",
                     game.pictureUrl());
    }

    /** A path that is already a url is left alone - see the fixture, which
     *  says why that is not something this app has read from a reply. */
    @Test
    public void apathThatIsAlreadyAurlIsUsedAsItIs() throws Exception {
        Canned http = new Canned().then(200, RECORD_WITH_AN_ABSOLUTE_PATH);

        List<Catalogue.Download> files =
                new ZxInfoCatalogue(http).item("0009305").versions().get(0).files();

        assertEquals("https://archive.org/download/zx_rzx/AticAtac.rzx.zip", files.get(0).url());
        assertEquals("rzx", files.get(0).format());
    }

    /**
     * <b>The stated format is a human phrase, and the code in it is the
     * format.</b>
     *
     * "Perfect tape (TZX)" is a tzx. Handing that phrase on as it stood is
     * what made {@code Pick.PREFERENCE}, which matches with {@code equals},
     * match nothing at all - so no entry in the database could be imported and
     * every record read as one the Spectrum cannot open, with every test in
     * two tasks passing. The fixture that hid it said {@code "format":"TZX"},
     * which is a value the service does not send.
     *
     * The size is the zip's, since that is what arrives.
     */
    @Test
    public void thehumanPhraseIsReadDownToTheFormatItNames() throws Exception {
        Canned http = new Canned().then(200, RECORD);

        List<Catalogue.Download> files =
                new ZxInfoCatalogue(http).item("0002259").versions().get(0).files();

        assertEquals("tap", files.get(0).format());
        assertEquals("tzx", files.get(1).format());
        assertEquals("tap", files.get(2).format());
        assertEquals(37132, files.get(0).size());
    }

    /**
     * <b>The assertion whose absence let an inert feature ship.</b>
     *
     * Every file in a real record is stated in that vocabulary, so a parser
     * that cannot read it leaves {@code Pick.forGame} answering null for
     * everything - which is not a corner case, it is the whole catalogue. What
     * comes back here is asserted to be a real file and the right one: the
     * original release's tape, in preference to the tap beside it.
     */
    @Test
    public void arecordInTheServicesOwnWordsYieldsAfileToImport() throws Exception {
        Canned http = new Canned().then(200, RECORD);

        Catalogue.Download tape = Pick.forGame(new ZxInfoCatalogue(http).item("0002259"));

        assertNotNull("nothing in a live record could be imported", tape);
        assertEquals("tzx", tape.format());
        assertEquals("https://spectrumcomputing.co.uk/pub/sinclair/games/h/HeadOverHeels.tzx.zip",
                     tape.url());
    }

    /**
     * <b>The branch the class comment leans on hardest, and until now the one
     * branch nothing exercised through {@code formatOf} itself.</b>
     *
     * {@code "Picture"} - word for word what {@link #RECORD}'s own {@code
     * screens[0]} carries - is the one measured value with no parenthesis at
     * all. That entry never reaches {@code formatOf} though: a rendered
     * loading screen's url is built by {@code pictureUrl}, which reads its
     * format through {@code extensionOf} on the path instead, so the bare
     * value has only ever been exercised on the side of the parser this test
     * is not about. This puts the identical bare word where {@code formatOf}
     * itself reads it - a release's own file - so falling through to the
     * path's extension ({@code .png}) is what a real reply, not merely code()
     * in isolation, is asserted to do.
     */
    @Test
    public void abareFormatWithNoParenthesisFallsBackToThePath() throws Exception {
        String record = "{\"_id\":\"0000001\",\"found\":true,\"_source\":{"
                + "\"title\":\"No Parenthesis\","
                + "\"publishers\":[{\"name\":\"Nobody\"}],"
                + "\"releases\":[{\"publishers\":[{\"name\":\"Nobody\"}],"
                + "  \"files\":[{\"path\":\"/pub/sinclair/screens/load/h/scr/Something.png\","
                + "             \"size\":1234,\"type\":\"Loading screen\","
                + "             \"format\":\"Picture\"}]}]}}";

        Canned http = new Canned().then(200, record);

        List<Catalogue.Download> files =
                new ZxInfoCatalogue(http).item("0000001").versions().get(0).files();

        assertEquals("a bare word with no parenthesis was read as a format of"
                     + " its own rather than falling through to the path's own"
                     + " extension", "png", files.get(0).format());
    }

    /**
     * <b>A parenthetical that is not an extension must not be taken for one.</b>
     *
     * Nothing measured has this shape, but nothing rules it out either: a
     * phrase like {@code "Compilation (multiload)"} has no space inside its
     * brackets, so the no-space rule alone would accept {@code multiload} as
     * a format and {@code Pick} would look for it in {@code PREFERENCE}, find
     * nothing, and drop a file that could have been opened by its own
     * extension. The length guard is what stops that: nothing in the
     * measured vocabulary needs more than four characters ({@code tzx},
     * {@code jpeg}, ...), so a longer word falls through to the path instead.
     */
    @Test
    public void aparentheticalLongerThanAnExtensionFallsBackToThePath() throws Exception {
        String record = "{\"_id\":\"0000002\",\"found\":true,\"_source\":{"
                + "\"title\":\"Overlong Parenthetical\","
                + "\"publishers\":[{\"name\":\"Nobody\"}],"
                + "\"releases\":[{\"publishers\":[{\"name\":\"Nobody\"}],"
                + "  \"files\":[{\"path\":\"/pub/sinclair/games/x/Something.tzx.zip\","
                + "             \"size\":1234,\"type\":\"Tape image\","
                + "             \"format\":\"Compilation (multiload)\"}]}]}}";

        Canned http = new Canned().then(200, record);

        List<Catalogue.Download> files =
                new ZxInfoCatalogue(http).item("0000002").versions().get(0).files();

        assertEquals("a parenthetical too long to be an extension was taken"
                     + " for one anyway", "tzx", files.get(0).format());
    }

    /** A release's year is {@code yearOfRelease}. Read as {@code releaseYear},
     *  a key no live record carries, every version this class answered with
     *  had no year and the list somebody chooses one from showed none. */
    @Test
    public void aversionCarriesTheYearTheServiceStates() throws Exception {
        Canned http = new Canned().then(200, RECORD);

        List<Catalogue.Version> versions = new ZxInfoCatalogue(http).item("0002259").versions();

        assertEquals("1987", versions.get(0).year());
        assertEquals("1990", versions.get(1).year());
        assertEquals("The Hit Squad", versions.get(1).label());
    }

    /**
     * <b>A recording is not a release.</b>
     *
     * ZXDB hangs an rzx off the entry, in {@code additionalDownloads}, and
     * never off a release - six captured recordings, every one of them there.
     * Read only from the releases, as the invented fixture encouraged,
     * {@code Pick.recording} answered null for every entry in the database and
     * "Play the recording" was a button nothing could ever show.
     */
    @Test
    public void therecordingComesFromTheEntrysOwnDownloads() throws Exception {
        Canned http = new Canned().then(200, RECORD_WITH_RECORDING);

        Catalogue.Item game = new ZxInfoCatalogue(http).item("0009305");

        Catalogue.Download recording = Pick.recording(game);
        assertNotNull("no entry in the database has a recording", recording);
        assertEquals("rzx", recording.format());
        assertEquals("https://spectrumcomputing.co.uk/denied/entries/0009305/AticAtac.rzx.zip",
                     recording.url());

        // And it is not mistaken for the game: the tape is still what a tap
        // means, and the recording is never in PREFERENCE.
        assertEquals("tzx", Pick.forGame(game).format());
    }

    /** Nothing else in {@code additionalDownloads} becomes a download: the
     *  pictures, manuals, pokes and music there are the scraping provider's
     *  business, and a catalogue offering a PDF as something to load would be
     *  offering the emulator a manual. */
    @Test
    public void theentrysOtherDownloadsAreNotThingsToLoad() throws Exception {
        Canned http = new Canned().then(200, RECORD);

        List<Catalogue.Download> files =
                new ZxInfoCatalogue(http).item("0002259").versions().get(0).files();

        assertEquals("the entry's manuals and music became loadable files", 3, files.size());
    }

    // --- sub-shelves ----------------------------------------------------------------------

    /**
     * Opening Categories yields shelves rather than items.
     *
     * The mechanism zxart's category tree needs, exercised here on the list
     * ZXInfo already publishes - so the tab's folder navigation is proved
     * before the second catalogue exists to need it.
     */
    @Test
    public void openingCategoriesYieldsShelves() throws Exception {
        Canned http = new Canned().then(200, METADATA);

        Catalogue.Page page = new ZxInfoCatalogue(http).open(
                shelf(http, "genres"), Catalogue.Query.none(), 0);

        assertTrue("categories brought items", page.items().isEmpty());
        assertEquals(2, page.shelves().size());
        assertEquals("Arcade Game", page.shelves().get(0).label());
        assertFalse("a page of shelves must not page on",  page.hasMore());
    }

    /** And the array is the plural one, which is the whole of what a live
     *  request changed here. */
    @Test
    public void thegenresComeFromThepluralArray() throws Exception {
        Canned http = new Canned().then(200, METADATA_LIVE);

        Catalogue.Page page = new ZxInfoCatalogue(http).open(
                shelf(http, "genres"), Catalogue.Query.none(), 0);

        assertEquals(2, page.shelves().size());
        assertEquals("Arcade Game", page.shelves().get(0).label());
    }

    /**
     * Categories never opens onto nothing.
     *
     * The genres are the one list this app already knows without asking - it
     * is recorded in {@code Kinds.ZXDB_VOCABULARY} from ZXDB's own dump - so a
     * reply it cannot read falls back to that rather than to an empty screen.
     * A key name that changes again should cost the counts and not the shelf.
     */
    @Test
    public void areplyWithNoGenresFallsBackToTheRecordedVocabulary() throws Exception {
        Canned http = new Canned().then(200, "{\"machinetypes\":[],\"features\":[]}");

        Catalogue.Page page = new ZxInfoCatalogue(http).open(
                shelf(http, "genres"), Catalogue.Query.none(), 0);

        assertEquals(Kinds.ZXDB_VOCABULARY.length, page.shelves().size());
        assertTrue("a fallback shelf brought items", page.items().isEmpty());
    }

    // --- what each shelf actually asks for --------------------------------------------------

    /**
     * <b>Every shelf, opened offline, asserted on its URL.</b>
     *
     * These cost no requests and catch what no reply ever could. This service
     * <em>ignores</em> a parameter it does not know rather than refusing it:
     * a wrong name answers 200 with the whole unfiltered database and reads on
     * screen as a shelf that works. So a wrong base host, a misspelt path, a
     * renamed parameter or a changed page size all have to be caught here or
     * not at all - and this task met that failure twice while it was being
     * written, at {@code genretypes} and nearly at {@code genretype=}.
     *
     * The absence of a filter is asserted on every shelf rather than only on
     * search: a filter added to one branch later is exactly how the Pentagon
     * demoscene disappears, and PENTAGON is a sibling of ZXSPECTRUM in this
     * scheme rather than a variant of it.
     */
    @Test
    public void everyShelfAsksThisServiceForAcompactUnfilteredPageOfThirty() throws Exception {
        for (Catalogue.Shelf shelf : new ZxInfoCatalogue(new Canned()).shelves()) {
            // Categories and A-Z are not searches at all - each yields
            // sub-shelves, and each has its own test below; the shelves they
            // yield are covered by their own round trips.
            if ("genres".equals(shelf.id()) || "letter".equals(shelf.id())) continue;

            String url = urlOpening(shelf, Catalogue.Query.text("x"));

            assertTrue(url, url.startsWith(ZxInfo.API + "search?"));
            assertTrue(url, url.contains("mode=compact"));
            assertTrue(url, url.contains("size=30"));
            assertNoFilter(url);
        }
    }

    @Test
    public void thesearchShelfSearchesForTheTypedText() throws Exception {
        assertTrue(urlOpening(shelfNamed("search"), Catalogue.Query.text("head"))
                           .contains("query=head"));
    }

    /**
     * The letter is the query, and the shelf is alphabetical - which is the
     * whole difference between A-Z and Search.
     *
     * Reached the way the screen reaches it: down into the letter's own
     * sub-shelf. Nothing hands this shelf a {@link Catalogue.Query} any more,
     * so a test that built one would be testing a path the app does not take.
     */
    @Test
    public void aletterSubShelfSearchesForThatLetterInTitleOrder() throws Exception {
        Canned http = new Canned().then(200, SEARCH);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);

        Catalogue.Shelf q = catalogue.open(shelf(http, "letter"),
                                           Catalogue.Query.none(), 0).shelves().get(16);
        assertEquals("Q", q.label());

        catalogue.open(q, Catalogue.Query.none(), 0);

        String url = http.asked.get(0);
        assertTrue(url, url.startsWith(ZxInfo.API + "search?"));
        assertTrue(url, url.contains("query=Q"));
        assertTrue(url, url.contains("sort=title_asc"));

        // The prefix is the catalogue's own and means nothing to anyone else -
        // leaking "letter:" into the query would search for a phrase no title
        // contains, which this service answers with a plausible empty shelf.
        assertFalse("the shelf id's prefix went out in the request: " + url,
                    url.contains("%3A"));
        assertNoFilter(url);
    }

    /**
     * Opening A-Z yields the alphabet and costs nothing.
     *
     * The same mechanism Categories uses, on the one list that needs no reply
     * at all - which is why this is the cheaper of the two proofs that a page
     * of shelves works, and why the letters are not a widget the screen had to
     * grow for one shelf.
     */
    @Test
    public void openingAtoZyieldsAshelfPerLetterAndNoRequest() throws Exception {
        Canned http = new Canned();

        Catalogue.Page page = new ZxInfoCatalogue(http).open(
                shelf(http, "letter"), Catalogue.Query.none(), 0);

        assertEquals("the alphabet cost a request", 0, http.asked.size());
        assertEquals(26, page.shelves().size());
        assertTrue("A-Z brought items", page.items().isEmpty());
        assertEquals("A", page.shelves().get(0).label());
        assertEquals("Z", page.shelves().get(25).label());
        assertFalse("a page of shelves must not page on", page.hasMore());
    }

    @Test
    public void thenewestShelfSortsByDateDescending() throws Exception {
        assertTrue(urlOpening(shelfNamed("newest"), Catalogue.Query.none())
                           .contains("sort=date_desc"));
    }

    @Test
    public void thesurpriseShelfAsksForArandomOffset() throws Exception {
        assertTrue(urlOpening(shelfNamed("random"), Catalogue.Query.none())
                           .contains("offset=random"));
    }

    /**
     * A surprise is one page.
     *
     * {@code offset=random} does not resample - two successive requests with it
     * returned the identical ten entries - so a second page is the first page
     * again, appended to itself. With no total to stop it that repeats for
     * ever: an endless grid of duplicates, one paced request per fling, against
     * a host that blocks on behaviour. Ended in the shelf rather than in
     * {@code Page.hasMore}, whose contract the other shelves depend on.
     */
    @Test
    public void thesurpriseShelfDoesNotPageOn() throws Exception {
        Canned http = new Canned().then(200, SEARCH);

        Catalogue.Page second = new ZxInfoCatalogue(http).open(
                shelf(http, "random"), Catalogue.Query.none(), 1);

        assertEquals("a second surprise page cost a request", 0, http.asked.size());
        assertTrue("a second surprise page brought rows", second.items().isEmpty());
        assertFalse("the surprise shelf pages for ever", second.hasMore());
    }

    @Test
    public void categoriesAsksForThemetadataDocument() throws Exception {
        Canned http = new Canned().then(200, METADATA_LIVE);

        new ZxInfoCatalogue(http).open(shelf(http, "genres"), Catalogue.Query.none(), 0);

        assertEquals(ZxInfo.API + "metadata/", http.asked.get(0));
    }

    /**
     * The round trip: Categories yields a shelf, and opening that shelf asks
     * for that genre.
     *
     * The one mechanism nothing else offline touches, and the one zxart's
     * category tree will rest on entirely. It also pins that the id's internal
     * prefix stays internal - a shelf id is the catalogue's own and means
     * nothing to anyone else, so leaking "genre:" into the query would be a
     * filter for a genre that does not exist, which this service would answer
     * with everything.
     */
    @Test
    public void agenreSubShelfComesBackAsAsearchFilteredByThatGenre() throws Exception {
        Canned http = new Canned().then(200, METADATA_LIVE).then(200, SEARCH);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);

        Catalogue.Shelf utility = catalogue.open(shelf(http, "genres"),
                                                 Catalogue.Query.none(), 0).shelves().get(1);
        assertEquals("Utility", utility.label());

        catalogue.open(utility, Catalogue.Query.none(), 0);

        String url = http.asked.get(1);
        assertTrue(url, url.startsWith(ZxInfo.API + "search?"));
        assertTrue(url, url.contains("genretype=Utility"));
        assertFalse("the shelf id's prefix went out in the request: " + url,
                    url.contains("%3A"));
        assertNoFilter(url);
    }

    // --- refusals --------------------------------------------------------------------------

    /** Told apart by kind, so the screen can say "in a minute" rather than
     *  "tomorrow" - the same three kinds the provider raises. */
    @Test
    public void arefusalSaysWhichKindItIs() {
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(new Canned());

        assertNotNull(catalogue.refusalFor(429));
        assertNotNull(catalogue.refusalFor(503));
        assertNotNull(catalogue.refusalFor(404));
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Opens a shelf against a canned reply and hands back what it asked for. */
    private static String urlOpening(Catalogue.Shelf shelf, Catalogue.Query query)
            throws Exception {
        Canned http = new Canned().then(200, SEARCH);

        new ZxInfoCatalogue(http).open(shelf, query, 0);

        assertEquals("opening a shelf is one request", 1, http.asked.size());
        return http.asked.get(0);
    }

    /** Nothing this app sends narrows a search. Every filter can only lose the
     *  right answer, and the one that matters most is invisible: filtering to
     *  ZXSPECTRUM drops PENTAGON, which is a sibling of it here. */
    private static void assertNoFilter(String url) {
        assertFalse("a machine filter was applied: " + url, url.contains("machinetype"));
        assertFalse("a content filter was applied: " + url, url.contains("contenttype"));
    }

    private static Catalogue.Shelf shelfNamed(String id) {
        return shelf(new Canned(), id);
    }

    private static Catalogue.Shelf shelf(Http http, String id) {
        for (Catalogue.Shelf shelf : new ZxInfoCatalogue(http).shelves()) {
            if (id.equals(shelf.id())) return shelf;
        }
        throw new AssertionError("no shelf called " + id);
    }

    private static boolean hasShelf(List<Catalogue.Shelf> shelves, String id) {
        for (Catalogue.Shelf shelf : shelves) {
            if (id.equals(shelf.id())) return true;
        }
        return false;
    }
}
