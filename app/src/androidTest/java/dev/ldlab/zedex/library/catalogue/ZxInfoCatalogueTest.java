package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.ZxInfo;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    /**
     * Answers whatever it was told to, remembers what it was asked, and
     * <b>throws once its queue is spent</b>.
     *
     * It used to answer {@code 200 \{\}} for ever instead, which is a lie a
     * test can pass on: a shelf that asked for one page more than the test
     * queued read an empty reply as an empty page and stopped, and an
     * assertion about how many pages were fetched measured the fake's
     * patience rather than the catalogue's behaviour. {@code Fixtures.Canned}
     * on the unit tier is strict for the same reason; the two are separate
     * classes only because the two source sets are.
     */
    private static final class Canned implements Http {
        private final List<Reply> replies = new ArrayList<>();
        final List<String> asked = new ArrayList<>();

        Canned then(int status, String body) {
            replies.add(new Reply(status, body));
            return this;
        }

        /** @throws IllegalStateException once every queued reply is spent. */
        @Override
        public Reply get(String url) {
            asked.add(url);
            if (replies.isEmpty()) {
                throw new IllegalStateException("Canned exhausted: unexpected request " + url);
            }
            return replies.remove(0);
        }

        @Override
        public String save(String url, File into) {
            throw new UnsupportedOperationException("not this test's business");
        }
    }

    /**
     * {@code /search?query=head+over+heels&mode=compact&size=2&offset=0},
     * trimmed to two hits and the fields a row draws.
     *
     * <b>Assembled, not recorded</b> - and now stating that, where its
     * neighbour {@link #RECORD} has always been marked as a real reply. Every
     * field name and every value in it is copied from replies to that same
     * query which were captured live; what is not the service's own is the
     * trimming to two hits and the {@code size=2} that goes with it.
     *
     * The total is the one number here that could not be trimmed and had to
     * be right: it said 37, which is a number nothing ever measured. The live
     * answer for this query is <b>153</b> - counted twice, by Task 11's
     * {@code ImportFlowTest} and again by the paging measurement - so that is
     * what it says. It matters because a fixture is what somebody reads to
     * learn what the service does, and a made-up count in one is how three
     * separate defects on this branch survived two reviews each.
     */
    private static final String SEARCH = "{"
            + "\"hits\":{\"total\":{\"value\":153},\"hits\":["
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
     * {@code /search?query=Jetpac&titlesonly=true&mode=compact&size=8}, one
     * hit of nine, carrying the part that matters here: <b>a search hit has
     * its releases</b>.
     *
     * <b>Recorded, on 2026-08-13, and the reason this fixture exists.</b> The
     * code used to read the releases only for {@code /games}, on the belief
     * that a list reply did not carry them - so every row was built with no
     * files and the filter this pins could not have existed. The live reply
     * says otherwise: the two release files below, their types, their formats
     * and their sizes are the service's own bytes, and the whole {@code
     * _source} was compared field by field against {@code /games/0009362} -
     * eighteen of nineteen shared fields identical, {@code releases} differing
     * only by a {@code releaseSeq} nothing here reads, and twenty-one further
     * fields present only in the record, which is why the pane still fetches
     * it.
     *
     * Trimmed to one hit and to the fields this test reads; nothing invented.
     * {@code Distribution denied} is what this entry really says, and the
     * paths really are under {@code /denied/}.
     */
    private static final String SEARCH_WITH_FILES = "{"
            + "\"hits\":{\"total\":{\"value\":9},\"hits\":["
            + "  {\"_id\":\"0009362\",\"_source\":{"
            + "     \"title\":\"Jetpac\",\"originalYearOfRelease\":1983,"
            + "     \"genreType\":\"Arcade Game\","
            + "     \"availability\":\"Distribution denied\","
            + "     \"publishers\":[{\"name\":\"Ultimate Play The Game\"}],"
            + "     \"releases\":[{\"files\":["
            + "        {\"type\":\"Computer/ZX Interface 2 cartridge ROM image dump\","
            + "         \"format\":\"ROM image dump (ROM)\","
            + "         \"path\":\"/denied/entries/0009362/Jetpac.rom.zip\","
            + "         \"size\":9662},"
            + "        {\"type\":\"Tape image\",\"format\":\"Perfect tape (TZX)\","
            + "         \"path\":\"/denied/entries/0009362/Jetpac.tzx.zip\","
            + "         \"size\":9930}]}],"
            + "     \"additionalDownloads\":[{"
            + "        \"type\":\"Loading screen\",\"format\":\"Screen dump (SCR)\","
            + "        \"path\":\"/pub/sinclair/screens/load/j/scr/Jetpac.scr\","
            + "        \"size\":6912}]}}"
            + "]}}";

    /**
     * {@code /search?query=head+over+heels&mode=compact&size=3&offset=0}, the
     * second of its three hits, trimmed to the fields this test reads.
     *
     * <b>Recorded, on 2026-08-14.</b> Its neighbour {@link #SEARCH_WITH_FILES}
     * pinned "a row carries its own files" on an entry whose files are under
     * {@code /denied/} and are not served - true of the parsing and no longer
     * true of what a row offers, since a withheld file is dropped. So the rule
     * is pinned here, on an entry whose five tape images are all under {@code
     * /pub/} or {@code /zxdb/} and every one of them fetchable, and the denied
     * one now pins the other half of the same rule.
     *
     * Two of the five files are kept - one {@code Tape (TAP)} and one {@code
     * Perfect tape (TZX)}, both the service's own bytes - because two formats
     * is what a format filter needs to have something to tell apart.
     */
    private static final String SEARCH_SERVED = "{"
            + "\"hits\":{\"total\":{\"value\":153},\"hits\":["
            + "  {\"_id\":\"0002259\",\"_source\":{"
            + "     \"title\":\"Head over Heels\",\"originalYearOfRelease\":1987,"
            + "     \"genreType\":\"Arcade Game\",\"availability\":\"Available\","
            + "     \"publishers\":[{\"publisherSeq\":1,\"name\":\"Ocean Software Ltd\","
            + "                      \"country\":\"UK\"}],"
            + "     \"releases\":[{\"files\":["
            + "        {\"path\":\"/pub/sinclair/games/h/HeadOverHeels.tap.zip\","
            + "         \"size\":37132,\"type\":\"Tape image\",\"format\":\"Tape (TAP)\"},"
            + "        {\"path\":\"/pub/sinclair/games/h/HeadOverHeels.tzx.zip\","
            + "         \"size\":38570,\"type\":\"Tape image\","
            + "         \"format\":\"Perfect tape (TZX)\"}]}]}}"
            + "]}}";

    /**
     * {@code /games/0029742?mode=compact} - 1 Line 3D Maze - trimmed to the
     * fields these tests read.
     *
     * <b>Recorded, on 2026-08-14</b>, and it is the served counterpart of
     * {@link #RECORD_WITH_RECORDING}: same shape, same two kinds of file, and
     * every path under {@code /zxdb/} rather than {@code /denied/}. The
     * recording was fetched to be sure - 200, {@code application/zip}, 25,700
     * bytes, which is the size the record states.
     *
     * It exists because the entry that used to pin "a recording lives in
     * {@code additionalDownloads}" is a denied one, whose recording this app
     * no longer offers at all: a test that asserted on a withheld file would
     * now be asserting the app does the thing it must not.
     */
    private static final String RECORD_SERVED_RECORDING = "{"
            + "\"_id\":\"0029742\",\"found\":true,\"_source\":{"
            + "  \"title\":\"1 Line 3D Maze\",\"originalYearOfRelease\":2008,"
            + "  \"genreType\":\"Arcade Game\",\"availability\":\"Available\","
            + "  \"publishers\":[{\"publisherSeq\":1,\"name\":\"Einar Saukas\","
            + "                   \"country\":\"Brazil\"}],"
            + "  \"releases\":[{\"releaseSeq\":0,\"files\":["
            + "     {\"path\":\"/zxdb/sinclair/entries/0029742/1Line3DMaze.tzx.zip\","
            + "      \"size\":695,\"type\":\"Tape image\","
            + "      \"format\":\"Perfect tape (TZX)\"}]}],"
            + "  \"additionalDownloads\":["
            + "     {\"path\":\"/zxdb/sinclair/entries/0029742/1Line3DMaze-RUN-1.scr\","
            + "      \"size\":6912,\"type\":\"Running screen\","
            + "      \"format\":\"Screen dump (SCR)\"},"
            + "     {\"path\":\"/zxdb/sinclair/entries/0029742/1Line3DMaze.rzx.zip\","
            + "      \"size\":25700,\"type\":\"RZX playback file\","
            + "      \"format\":\"Game recording (RZX)\"}]}}";

    /**
     * {@code /games/byletter/Z?mode=compact&size=30&offset=0}, trimmed to two
     * of its thirty hits and the fields a row draws.
     *
     * <b>Values recorded, envelope assembled - and nothing in it is here that
     * was not seen.</b> The two ids, the two titles and the total are the
     * service's own, read off a live reply on 2026-08-11; that request is the
     * one that proved this endpoint returns titles beginning with the letter,
     * and it is written down here so the next person need not spend it again.
     * What is this test's rather than the service's is only the trimming - to
     * two of thirty hits, and to the fields that live run actually reported.
     *
     * A year, a genre and an availability would all have made this look more
     * like a real reply, and every one of them would have been written from
     * memory. Three defects on this branch came from exactly that, so the
     * fields nobody recorded are absent rather than plausible: a row draws
     * fine without them, which is itself worth pinning.
     *
     * The total is 1,701 because that is what came back for Z. It matters that
     * it is a real number: it is well inside the 10,000 cap, so a letter shelf
     * is one of the shelves that can honestly report a count.
     *
     * The envelope is deliberately the same shape as {@link #SEARCH} - that is
     * the measured fact this endpoint is read on, and if it ever stops being
     * true, {@code aletterShelfReadsItsRowsAndItsTotal} is what says so.
     */
    private static final String BY_LETTER = "{"
            + "\"hits\":{\"total\":{\"value\":1701},\"hits\":["
            + "  {\"_id\":\"0005858\",\"_source\":{\"title\":\"Z-Man\"}},"
            + "  {\"_id\":\"0031886\",\"_source\":{\"title\":\"Z-Xtricator\"}}"
            + "]}}";

    /**
     * {@code /games/byletter/%23?mode=compact&size=30&offset=0} - the digit
     * shelf - <b>recorded</b>, trimmed to two of its thirty hits and the fields
     * a row draws.
     *
     * Read off a live reply on 2026-08-11, the one that proved {@code #} is a
     * letter this endpoint takes: every id, title, year, genre, availability
     * and publisher below was in that reply, beside the one after it, and the
     * only thing this test did to it was drop the twenty-eight further hits and
     * the fields nothing here reads.
     *
     * <b>839 is the real count</b>, {@code "relation":"eq"} rather than the
     * {@code "gte"} that marks Elasticsearch's cap - so this shelf reports an
     * honest total, and 839 entries were unreachable from A-Z until it existed.
     *
     * The two rows are utilities because the titles that begin with a digit
     * mostly are: the shelf opens on twenty-odd tape copiers named after James
     * Bond.
     */
    private static final String BY_DIGIT = "{"
            + "\"hits\":{\"total\":{\"value\":839,\"relation\":\"eq\"},\"hits\":["
            + "  {\"_id\":\"0027393\",\"_source\":{"
            + "     \"title\":\"007 BEEP Copier\",\"originalYearOfRelease\":1985,"
            + "     \"genreType\":\"Utility\",\"availability\":\"Available\","
            + "     \"publishers\":[{\"publisherSeq\":1,\"name\":\"ZX-Guaranteed\","
            + "                      \"country\":\"UK\"}]},"
            + "   \"sort\":[\"007 BEEP Copier\"]},"
            + "  {\"_id\":\"0007869\",\"_source\":{"
            + "     \"title\":\"007 Copier\",\"originalYearOfRelease\":1985,"
            + "     \"genreType\":\"Utility\",\"availability\":\"Available\","
            + "     \"publishers\":[{\"publisherSeq\":1,\"name\":\"ZX-Guaranteed\","
            + "                      \"country\":\"UK\"}]},"
            + "   \"sort\":[\"007 Copier\"]}"
            + "]}}";

    /**
     * {@code /games/random/30?mode=compact}, <b>recorded</b>, trimmed to the
     * first of its thirty hits.
     *
     * The reply this came from is one of the two that proved the endpoint
     * resamples: it and the identical request after it shared not one of their
     * thirty ids. Everything below is that first hit's own - {@code 0031049},
     * a 16K game from 2008 - including the loading screen, which is under
     * {@code /zxscreens/} and so on the other host.
     *
     * <b>The total is the cap, and it is here on purpose.</b> {@code
     * {"value":10000,"relation":"gte"}} is what this endpoint answers with -
     * Elasticsearch counting no further rather than a count of anything - and
     * {@code arandomShelfCountsNothing} is the assertion that the shelf refuses
     * to print it. A fixture with a plausible total would have hidden that.
     */
    private static final String RANDOM = "{"
            + "\"hits\":{\"total\":{\"value\":10000,\"relation\":\"gte\"},"
            + "          \"max_score\":3.8814323,\"hits\":["
            + "  {\"_id\":\"0031049\",\"_score\":3.8814323,\"_source\":{"
            + "     \"title\":\"Game of the Yet to Come\",\"originalYearOfRelease\":2008,"
            + "     \"machineType\":\"ZX-Spectrum 16K\","
            + "     \"genreType\":\"Arcade Game\",\"availability\":\"Available\","
            + "     \"publishers\":[{\"publisherSeq\":1,\"name\":\"Digital Prawn\","
            + "                      \"country\":\"UK\"}],"
            + "     \"screens\":[{\"filename\":\"0031049-load-1.png\","
            + "                   \"url\":\"/zxscreens/0031049/0031049-load-1.png\","
            + "                   \"size\":5526,\"type\":\"Loading screen\","
            + "                   \"format\":\"Picture\"}]}}"
            + "]}}";

    /**
     * {@code /games/morelikethis/0002259?mode=compact&size=30} - games like
     * Head over Heels - <b>recorded</b>, trimmed to two of its thirty hits.
     *
     * Read off the live reply on 2026-08-11. Both hits are arcade adventures,
     * which is what the endpoint is for, and both carry the same {@code _score}
     * - the rows come back ordered by it, 8.185992 at the top of 1,858.
     *
     * <b>1,858 is a real count and this shelf still refuses to print it</b>, so
     * the number is here to make that refusal testable: the endpoint takes a
     * size and no offset, so thirty is all there is to see and a count beside
     * the shelf's name would be a number the list can never reach.
     *
     * The top match being {@code Never released} is the service's own doing and
     * is kept: it is the greyed-row case turning up at the head of a shelf
     * somebody will actually open.
     */
    private static final String MORE_LIKE_THIS = "{"
            + "\"hits\":{\"total\":{\"value\":1858,\"relation\":\"eq\"},"
            + "          \"max_score\":8.185992,\"hits\":["
            + "  {\"_id\":\"0024427\",\"_score\":8.185992,\"_source\":{"
            + "     \"title\":\"Slightly Spooky\",\"originalYearOfRelease\":null,"
            + "     \"machineType\":\"ZX-Spectrum 48K/128K\","
            + "     \"genre\":\"Arcade Game: Adventure\",\"genreType\":\"Arcade Game\","
            + "     \"availability\":\"Never released\","
            + "     \"publishers\":[{\"publisherSeq\":1,\"name\":\"Code Masters Ltd\","
            + "                      \"country\":\"UK\"}]}},"
            + "  {\"_id\":\"0014541\",\"_score\":8.185992,\"_source\":{"
            + "     \"title\":\"Pedro v strašidelnom zámku\","
            + "     \"originalYearOfRelease\":1993,"
            + "     \"machineType\":\"ZX-Spectrum 48K/128K\","
            + "     \"genre\":\"Arcade Game: Adventure\",\"genreType\":\"Arcade Game\","
            + "     \"availability\":\"Available\","
            + "     \"publishers\":[{\"publisherSeq\":1,\"name\":\"Ultrasoft [SK]\","
            + "                      \"country\":\"Slovakia\"}]}}"
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
     * <b>Invented in two places, and both are stated.</b> This body exists to
     * pin {@code ZxInfo.urlFor}'s passthrough, and nothing captured from the
     * live service could do it:
     *
     * <ul>
     *   <li><b>The absolute path itself.</b> No captured reply holds one -
     *       every path in every one of them is relative, under {@code /pub/},
     *       {@code /zxdb/} or {@code /denied/}. That such rows exist at all is
     *       read from the ZXDB dump, where 4,941 of 153,959 download links
     *       start with {@code http}; whether this API ever surfaces one is
     *       <b>unverified</b>, and the one probe there was suggests it may
     *       not.</li>
     *   <li><b>Where it sits.</b> The RZX is inside a release's {@code files}
     *       here, and this branch established that a recording is never
     *       there - six captured, every one of them in {@code
     *       additionalDownloads}. So this is a shape the service does not
     *       send, and it is here only because {@code urlFor} is called from
     *       both arrays and this asserts the one no other fixture covers. It
     *       must not be read as evidence about where recordings live; {@link
     *       #RECORD_WITH_RECORDING} is.</li>
     * </ul>
     *
     * Joining such a path onto a base would make a url with an https:// in
     * the middle of it, so the passthrough is worth keeping and worth two
     * labels.
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
     *
     * <b>The key is what is wrong here; the counts are not.</b> This body
     * originally gave Utility a {@code doc_count} of 5,731, which is a number
     * nothing ever measured, and CLAUDE.md now lists that figure by name as
     * one of three defects written from memory - so leaving it here to
     * illustrate the rule was leaving the rule holed. Both counts now come
     * from the numbers below.
     */
    private static final String METADATA = "{"
            + "\"genretype\":[{\"key\":\"Arcade Game\",\"doc_count\":12451},"
            + "               {\"key\":\"Utility\",\"doc_count\":6436}]}";

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
     * not because the service sent them so - an honest gap rather than an
     * invention, and {@code ZxInfoCatalogue.keyOf} is lenient for that reason.
     *
     * The two counts each have a source, since nothing here may be a
     * remembered number. <b>Utility's 6,436 is the service's own</b>: the
     * genre-filter check in Task 5 asked {@code /search?genretype=Utility} and
     * got exactly that total, which is also what the offline ZXDB dump counts
     * for the same genre - the two agreeing to the entry is the strongest
     * evidence on this branch that the dump and the API describe one database.
     * <b>Arcade Game's 12,451 is the dump's</b>, counted the same way and
     * <em>not</em> confirmed against this call, which nothing has ever read the
     * body of past its key names.
     */
    private static final String METADATA_LIVE = "{"
            + "\"machinetypes\":[],"
            + "\"genretypes\":[{\"key\":\"Arcade Game\",\"doc_count\":12451},"
            + "                {\"key\":\"Utility\",\"doc_count\":6436}],"
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
        assertEquals(153, page.total());
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
     * A row from a search carries its own files, and that is what makes a
     * format filter possible at all.
     *
     * ZXInfo's search has no format filter: {@code format}, {@code filetype}
     * and {@code downloadtype} are every one of them <em>ignored</em> rather
     * than refused - measured against a deliberate nonsense parameter, which
     * returned the same total as no parameter at all. So the app keeps what
     * arrives, which it can only do if a row knows what it holds. This used to
     * read the releases only for {@code /games}; see {@link
     * #SEARCH_WITH_FILES}, which is the live reply that says otherwise.
     *
     * The two formats are the fixture's own: a tap and a tzx, each inside a zip
     * - the inner format is what decides what this app can open, so
     * ".tzx.zip" is a tzx.
     *
     * <b>On a served entry, and it has to be.</b> This was written against
     * {@link #SEARCH_WITH_FILES}, which is a real reply and a denied one - its
     * files are under {@code /denied/} and answer 404 - so it pinned formats a
     * row must no longer report. The same bytes now pin the other half of that
     * rule next door, and this reads {@link #SEARCH_SERVED}.
     */
    @Test
    public void arowFromAsearchCarriesItsFormats() throws Exception {
        Canned http = new Canned().then(200, SEARCH_SERVED);

        Catalogue.Page page = new ZxInfoCatalogue(http)
                .open(shelf(http, "search"), Catalogue.Query.text("head over heels"), 0);

        Catalogue.Item first = page.items().get(0);

        assertTrue("a row that does not know its own formats cannot be filtered"
                   + " by one, and asking the service is not an option",
                   first.formats().contains("tzx"));
        assertTrue("the same release has a tap as well, and a filter is what"
                   + " tells the two apart", first.formats().contains("tap"));
    }

    /**
     * A file under {@code /denied/} is one this archive holds a record of and
     * will not serve, and no row may offer it.
     *
     * <b>Measured, on 2026-08-14, after "Play the recording" answered "That did
     * not arrive." for Atic Atac.</b> ZXDB records the file and its size for an
     * entry whose distribution is denied, and the path it gives is under {@code
     * /denied/} rather than {@code /pub/} or {@code /zxdb/}. Every one of those
     * paths answers <b>404</b>, and every non-denied path on the very same
     * entries answers 200: {@code /denied/entries/0009305/AticAtac.tzx.zip} and
     * {@code .rzx.zip} both 404 while {@code
     * /zxdb/sinclair/entries/0009305/AticAtac.jpg} is served, and the same on
     * 1942 (0009297), 1943 (0009298) and 11-a-Side Soccer (0009296). Over 240
     * sampled entries a {@code /denied/} path appeared only on ones whose
     * availability is "Distribution denied", and all three of those carried
     * served paths as well.
     *
     * So this is a fact about the <b>file</b>, not about the entry: the tape and
     * the recording are withheld and the artwork is not. Dropping the entry
     * would lose a scrape's covers over a tape nobody can have.
     *
     * The fixture is {@link #SEARCH_WITH_FILES}, which has always been a
     * recorded reply and has always been a denied one - it says so in its own
     * comment. What it pinned was the opposite of this, that a row carries the
     * formats of its files, which is why the entry that pins that is now a
     * served one and this reads the same bytes for the other half of the rule.
     */
    @Test
    public void awithheldFileIsNotOfferedByArow() throws Exception {
        Canned http = new Canned().then(200, SEARCH_WITH_FILES);

        Catalogue.Page page = new ZxInfoCatalogue(http)
                .open(shelf(http, "search"), Catalogue.Query.text("jetpac"), 0);

        Catalogue.Item first = page.items().get(0);

        assertFalse("the tape is under /denied/ and answers 404 - a format filter"
                    + " offering this row is a shelf of games nobody can have",
                    first.formats().contains("tzx"));
        assertFalse("the rom dump is withheld too", first.formats().contains("rom"));

        assertNull("nothing in this entry can be fetched, and Pick must say so"
                   + " rather than name a file that 404s", Pick.forGame(first));

        // The row itself stays, with its title and the service's own word for
        // why: a game that exists and is not distributed is a real thing to
        // find, and the greyed row and its stated reason are what say so.
        assertEquals("Jetpac", first.title());
        assertEquals("Distribution denied", first.availability());
    }

    /**
     * ...and no recording either, which is the tap that reported this.
     *
     * The record rather than a search row, because a recording is read from
     * {@code additionalDownloads} by a method of its own. Offering it put a
     * "Play the recording" button on a game whose recording the archive
     * refuses, and the import answered 404, which the pane reads as MALFORMED
     * and reports as "That did not arrive." - a network's shrug for a permanent
     * refusal.
     *
     * The fixture is {@link #RECORD_WITH_RECORDING}, Atic Atac's own reply and
     * the entry somebody actually tapped. It used to pin the opposite - that a
     * recording is found at all - and cannot, because everything it holds is
     * withheld; {@link #RECORD_SERVED_RECORDING} pins that now, and the two
     * together are the whole rule.
     */
    @Test
    public void awithheldRecordingIsNotOffered() throws Exception {
        Canned http = new Canned().then(200, RECORD_WITH_RECORDING);

        Catalogue.Item item = new ZxInfoCatalogue(http).item("0009305");

        assertNotNull("the record itself did not parse", item);

        assertNull("the recording is under /denied/ and answers 404",
                   Pick.recording(item));
        assertNull("so is the only tape", Pick.forGame(item));

        // Not dropped, and not empty of everything: the entry is still on the
        // list, saying what it is and why there is nothing to fetch. Its
        // artwork is untouched too - covers, maps and pokes never travel in a
        // catalogue Item at all, they are the scraping provider's, which reads
        // the same record through ZxInfo and is not what this changed.
        assertEquals("Atic Atac", item.title());
        assertEquals("Distribution denied", item.availability());
    }

    /**
     * A shelf that is going to throw most of a page away asks for a bigger one.
     *
     * <b>Measured, and the reason the number is 100.</b> The service has no
     * format parameter, so the app filters what arrives - and a live TRD filter
     * keeps 4.3% of entries, 1.3 rows out of every thirty-row page. What that
     * costs is dominated by the per-request overhead rather than by the bytes:
     * a page is 0.17-0.25s of network and this app waits 250ms between calls,
     * so thirty entries cost about 0.45s and a hundred cost about 0.53s. Three
     * hundred entries read as ten pages take 4.5s; as three pages, 1.6s. Same
     * entries, same rows kept, roughly the same bytes, a third of the wall
     * clock - which is why the hint is about the <em>page</em> and not about
     * asking for more of them.
     *
     * <b>The hint is on the query, so no catalogue is made to care.</b> {@code
     * Query} exists for exactly this - "one object rather than an argument per
     * kind, so adding a filter later changes neither open nor any catalogue
     * that does not use it" - and a catalogue with no notion of page size
     * ignores it.
     */
    @Test
    public void ashelfThatSiftsAsksForAbiggerPage() throws Exception {
        Canned http = new Canned().then(200, SEARCH).then(200, SEARCH);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);

        catalogue.open(shelf(http, "search"), Catalogue.Query.text("manic miner"), 0);
        assertTrue(http.asked.get(0), http.asked.get(0).contains("size=30"));

        catalogue.open(shelf(http, "search"),
                       Catalogue.Query.text("manic miner").sifting(), 0);
        assertTrue("a sifting shelf asked for the same small page, so a filter"
                   + " that keeps one row in thirty pays a whole request for it",
                   http.asked.get(1).contains("size=100"));
    }

    /**
     * ...and the page it asks for is the stride it counts by.
     *
     * <b>{@code offset} is a page number here, not a row number</b> - the
     * measured fact this endpoint is paged on - so the size and the offset
     * multiply, and how many rows a shelf has already seen has to be worked out
     * with the same size it asked for. Get that wrong and {@code Page.hasMore}
     * is answered from a count three times too small: a sifting shelf would
     * walk on past its own end, one wasted paced request per fling, against the
     * host that has blocked this app's address once already.
     *
     * <b>The total is invented and says so.</b> 101 is chosen to sit between
     * the two strides - above 30 + 2 and below 100 + 2 - which is the whole
     * point of it; every other value here is {@link #SEARCH}'s, which is a
     * trimmed real reply. There is no live search whose total lands in that gap
     * to hand, and waiting for one is not a test.
     */
    @Test
    public void asiftingShelfCountsByThePageItAskedFor() throws Exception {
        String body = SEARCH.replace("\"total\":{\"value\":153}", "\"total\":{\"value\":101}");

        Catalogue.Page page = new ZxInfoCatalogue(new Canned().then(200, body))
                .open(shelf(new Canned(), "search"),
                      Catalogue.Query.text("manic miner").sifting(), 1);

        assertFalse("page one of a hundred-row shelf has seen 100 rows, not 30 -"
                    + " counted by the wrong stride, this shelf goes on asking"
                    + " for pages that are not there", page.hasMore());
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

    /**
     * The second page asks for page two, and {@code offset} is a <b>page
     * number</b>.
     *
     * <b>Measured, after this shipped sending a row number.</b> The
     * specification calls the parameter "the page offset for pagination",
     * which reads either way, and page zero - which is all the earlier live
     * checks ever asked for - cannot tell the two apart, since {@code 0 * 30}
     * is {@code 0}. Asked properly: a search stating {@code total=153} was
     * given {@code size=30&offset=30} and answered <b>empty</b>. A row offset
     * of 30 into 153 rows cannot be empty; the service had been asked for page
     * thirty of five. So every shelf in the app stopped after its first thirty
     * rows - a 200, a page of nothing, {@code Page.hasMore} correctly calling
     * that the end - which on screen is a catalogue that has thirty of
     * everything. With {@code offset=1} the same search's second page came
     * back with thirty rows sharing no id with the first.
     *
     * Asserted as the exact string rather than as "not offset=0", which is
     * what it used to say and which both readings pass.
     */
    @Test
    public void thesecondPageAsksForPageTwoAndNotForRowThirty() throws Exception {
        Canned http = new Canned().then(200, SEARCH);

        new ZxInfoCatalogue(http).open(shelf(http, "search"),
                                       Catalogue.Query.text("x"), 1);

        String url = http.asked.get(0);
        assertTrue("the second page did not ask for page 1: " + url,
                   url.contains("offset=1&") || url.endsWith("offset=1"));
        assertFalse("offset is a page number, not a row number: " + url,
                    url.contains("offset=30"));
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
        Canned http = new Canned().then(200, RECORD_SERVED_RECORDING);

        Catalogue.Item game = new ZxInfoCatalogue(http).item("0029742");

        Catalogue.Download recording = Pick.recording(game);
        assertNotNull("no entry in the database has a recording", recording);
        assertEquals("rzx", recording.format());
        assertEquals("https://spectrumcomputing.co.uk/zxdb/sinclair/entries/0029742/"
                     + "1Line3DMaze.rzx.zip", recording.url());

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

            assertTrue(url, url.startsWith(ZxInfo.API));
            assertTrue(url, url.contains("mode=compact"));
            assertNoFilter(url);

            // Surprise me is an endpoint of the service's own, and the count it
            // takes is in the path - so it says thirty in the one place it can.
            // Everything else here is a search.
            if ("random".equals(shelf.id())) {
                assertTrue(url, url.startsWith(ZxInfo.API + "games/random/30?"));
            } else {
                assertTrue(url, url.startsWith(ZxInfo.API + "search?"));
                assertTrue(url, url.contains("size=30"));
            }
        }
    }

    @Test
    public void thesearchShelfSearchesForTheTypedText() throws Exception {
        assertTrue(urlOpening(shelfNamed("search"), Catalogue.Query.text("head"))
                           .contains("query=head"));
    }

    /**
     * A letter asks the by-letter endpoint, and is not a search.
     *
     * <b>This pinned the wrong URL until the specification was read.</b> It
     * asserted {@code search?query=Q&sort=title_asc} - and so pinned the guess
     * that {@code query=} means "title begins with", when it is a full-text
     * match over the whole record. {@code GET /games/byletter/{letter}} is the
     * service's own endpoint for this and comes back alphabetical with nothing
     * asked - see the live figures on {@code ZxInfoCatalogue.letterFor}.
     *
     * A test can pin a URL and prove nothing about whether it is the right
     * question. This one passed for the whole of the branch.
     *
     * Reached the way the screen reaches it: down into the letter's own
     * sub-shelf. Nothing hands this shelf a {@link Catalogue.Query} any more,
     * so a test that built one would be testing a path the app does not take.
     *
     * The sort is asserted <em>absent</em> rather than left unmentioned: a
     * parameter this service does not know is ignored rather than refused, so
     * a {@code sort=} surviving here would never show up as a failure anywhere
     * else.
     */
    @Test
    public void aletterSubShelfAsksTheByLetterEndpointForThatLetter() throws Exception {
        Canned http = new Canned().then(200, BY_LETTER);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);

        Catalogue.Shelf q = catalogue.open(shelf(http, "letter"),
                                           Catalogue.Query.none(), 0).shelves().get(16);
        assertEquals("Q", q.label());

        catalogue.open(q, Catalogue.Query.none(), 0);

        String url = http.asked.get(0);
        assertTrue(url, url.startsWith(ZxInfo.API + "games/byletter/Q?"));
        assertTrue(url, url.contains("mode=compact"));
        assertTrue(url, url.contains("size=30"));

        assertFalse("a letter is not a search: " + url, url.contains("search?"));
        assertFalse("the letter went as a query as well as a path: " + url,
                    url.contains("query="));
        assertFalse("a guessed sort value came back: " + url, url.contains("sort="));

        // The prefix is the catalogue's own and means nothing to anyone else -
        // leaking "letter:" into the path asks for a letter that is not one,
        // which this endpoint answers 400 for.
        assertFalse("the shelf id's prefix went out in the request: " + url,
                    url.contains("%3A"));
        assertNoFilter(url);
    }

    /**
     * A letter's rows are read exactly as a search's are.
     *
     * Worth its own assertion because the two now come from different
     * endpoints: the envelope is the same {@code hits.hits[]._source} either
     * way, and this is what would fail if that stopped being true.
     */
    @Test
    public void aletterShelfReadsItsRowsAndItsTotal() throws Exception {
        Canned http = new Canned().then(200, BY_LETTER);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);

        Catalogue.Shelf z = catalogue.open(shelf(http, "letter"),
                                           Catalogue.Query.none(), 0).shelves().get(25);
        assertEquals("Z", z.label());

        Catalogue.Page page = catalogue.open(z, Catalogue.Query.none(), 0);

        assertEquals(2, page.items().size());
        assertEquals("Z-Man", page.items().get(0).title());
        assertEquals("0005858", page.items().get(0).id());
        assertEquals(1701, page.total());
    }

    /**
     * The by-letter endpoint's {@code offset} is a page number too - measured,
     * not inherited from {@code /search}.
     *
     * The specification gives it the same words on both ({@code "Specifies the
     * page offset for pagination"}), which is exactly the wording that read
     * either way on {@code /search} and was wrong there for the whole of this
     * branch. So it was asked: page 1 of {@code Z} shared no id with page 0 and
     * opened on {@code Z80 CPU Microprocessor Instant Reference Card}, which
     * follows page 0's last row {@code Z80 COLOSS} - where a row offset of 1
     * would have opened on page 0's second row.
     */
    @Test
    public void aletterSsecondPageAsksForPageTwoAndNotForRowThirty() throws Exception {
        Canned http = new Canned().then(200, BY_LETTER);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);

        Catalogue.Shelf z = catalogue.open(shelf(http, "letter"),
                                           Catalogue.Query.none(), 0).shelves().get(25);
        catalogue.open(z, Catalogue.Query.none(), 1);

        String url = http.asked.get(0);
        assertTrue("the second page did not ask for page 1: " + url,
                   url.contains("offset=1&") || url.endsWith("offset=1"));
        assertFalse("offset is a page number, not a row number: " + url,
                    url.contains("offset=30"));
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
        assertEquals(27, page.shelves().size());
        assertTrue("A-Z brought items", page.items().isEmpty());
        assertEquals("A", page.shelves().get(0).label());
        assertEquals("Z", page.shelves().get(25).label());
        assertFalse("a page of shelves must not page on", page.hasMore());
    }

    /**
     * The twenty-seventh shelf, and what a person reads on it.
     *
     * {@code #} is the specification's own argument for "titles that start with
     * a digit" and it is a punctuation mark to everybody else, so the label is
     * {@code 0-9} and the {@code #} stays in the path. Both are asserted here
     * because they are two different things that a single constant could easily
     * have made one: sending {@code 0-9} is a 400, and drawing {@code #} on a
     * row is a shelf nobody will open.
     */
    @Test
    public void thedigitShelfIsTheTwentySeventhAndReadsAsZeroToNine() throws Exception {
        Canned http = new Canned();

        Catalogue.Page page = new ZxInfoCatalogue(http).open(
                shelf(http, "letter"), Catalogue.Query.none(), 0);

        assertEquals("0-9", page.shelves().get(26).label());
    }

    /**
     * And it asks for {@code #}, encoded.
     *
     * <b>{@code %23} and not a bare {@code #}</b>: a raw one in a URL is a
     * fragment marker, so everything after it - the mode, the size, the page -
     * would never leave the phone, and the request that did go out would be for
     * a letter with this app's own default mode behind it. That is {@code
     * Uri.encode}'s doing, which is also why this test class runs on a device.
     */
    @Test
    public void thedigitShelfAsksForHashInThePath() throws Exception {
        Canned http = new Canned().then(200, BY_DIGIT);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);

        Catalogue.Shelf digits = catalogue.open(shelf(http, "letter"),
                                                Catalogue.Query.none(), 0).shelves().get(26);
        catalogue.open(digits, Catalogue.Query.none(), 0);

        String url = http.asked.get(0);
        assertTrue(url, url.startsWith(ZxInfo.API + "games/byletter/%23?"));
        assertTrue(url, url.contains("mode=compact"));
        assertTrue(url, url.contains("size=30"));

        assertFalse("a raw # went into the URL, which would cut it short: " + url,
                    url.contains("#"));
        assertFalse("the shelf id's prefix went out in the request: " + url,
                    url.contains("%3A"));
        assertNoFilter(url);
    }

    /**
     * Its rows and its total, which is a real one.
     *
     * 839 came back with {@code "relation":"eq"}, so unlike the broad shelves
     * this one may print what it says - and those 839 entries were in the
     * database and out of the app for as long as A-Z had twenty-six shelves.
     */
    @Test
    public void thedigitShelfReadsItsRowsAndItsRealTotal() throws Exception {
        Canned http = new Canned().then(200, BY_DIGIT);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);

        Catalogue.Shelf digits = catalogue.open(shelf(http, "letter"),
                                                Catalogue.Query.none(), 0).shelves().get(26);

        Catalogue.Page page = catalogue.open(digits, Catalogue.Query.none(), 0);

        assertEquals(2, page.items().size());
        assertEquals("007 BEEP Copier", page.items().get(0).title());
        assertEquals("0027393", page.items().get(0).id());
        assertEquals(839, page.total());
    }

    @Test
    public void thenewestShelfSortsByDateDescending() throws Exception {
        assertTrue(urlOpening(shelfNamed("newest"), Catalogue.Query.none())
                           .contains("sort=date_desc"));
    }

    /**
     * Surprise me asks the endpoint whose job that is, and not a search.
     *
     * <b>{@code GET /games/random/{total}}</b>, with the count in the path and
     * no {@code offset} and no {@code size} to send. This was
     * {@code search?offset=random} - a real thing to send, since the search's
     * {@code offset} is typed as a string precisely to take that word, and it
     * answered plausibly every time while returning the identical ten entries
     * to two successive requests. So the assertion that matters is the one
     * about what is <em>absent</em>: a leftover {@code offset=random} on the
     * new path would be ignored by this service rather than refused, and
     * nothing else would ever say so.
     */
    @Test
    public void thesurpriseShelfAsksTheRandomEndpointForThirty() throws Exception {
        String url = urlOpening(shelfNamed("random"), Catalogue.Query.none());

        assertTrue(url, url.startsWith(ZxInfo.API + "games/random/30?"));
        assertTrue(url, url.contains("mode=compact"));

        assertFalse("a surprise is not a search: " + url, url.contains("search?"));
        assertFalse("the old random offset came back: " + url, url.contains("offset="));
        assertNoFilter(url);
    }

    /**
     * And it pages on, <b>because it resamples</b>.
     *
     * Measured 2026-08-11: two identical requests to {@code games/random/30}
     * answered thirty entries each and shared not one id. That is the whole
     * difference from {@code search?offset=random}, which answered the same ten
     * twice and had to be stopped after one page or the grid filled with
     * duplicates for ever. Here a second page is thirty games nobody has seen,
     * so it costs a request and it is worth one.
     */
    @Test
    public void thesurpriseShelfPagesOnBecauseItResamples() throws Exception {
        Canned http = new Canned().then(200, RANDOM);

        Catalogue.Page second = new ZxInfoCatalogue(http).open(
                shelf(http, "random"), Catalogue.Query.none(), 1);

        assertEquals("a second surprise page cost something other than one request",
                     1, http.asked.size());
        assertEquals(1, second.items().size());
        assertTrue("the surprise shelf stopped after one page", second.hasMore());

        // The same question, asked again: there is no page to ask for and the
        // answer is different anyway.
        assertEquals(ZxInfo.API + "games/random/30?mode=compact", http.asked.get(0));
    }

    /**
     * And it stops at ten of them.
     *
     * The one shelf in the app nothing in a reply can end - it resamples, so
     * there is no total and no page ever comes back empty - which left {@code
     * Page.hasMore} answering true for ever and a grid that could be flung all
     * night. Each of those flings is one paced API call <em>and</em> up to thirty
     * unpaced cover fetches that cannot hit the cache, against an address this
     * app has already had taken away for looking like a crawler once. See
     * {@code ZxInfoCatalogue.RANDOM_PAGES}.
     *
     * Both sides are asserted, because only the pair says where the bound is:
     * page nine still costs a request, and page ten costs none and comes back
     * empty. Without the guard the second half of this fails - the request goes
     * out and thirty rows come back - which is what it is here for.
     */
    @Test
    public void thesurpriseShelfStopsAtTenPages() throws Exception {
        Canned http = new Canned().then(200, RANDOM).then(200, RANDOM);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);
        Catalogue.Shelf random = shelf(http, "random");

        Catalogue.Page ninth = catalogue.open(random, Catalogue.Query.none(), 9);

        assertEquals("the tenth page is inside the bound and was not asked for",
                     1, http.asked.size());
        assertTrue("the shelf ended before its three hundredth game", ninth.hasMore());

        Catalogue.Page past = catalogue.open(random, Catalogue.Query.none(), 10);

        assertEquals("the eleventh page went out as a request", 1, http.asked.size());
        assertTrue("the eleventh page came back with rows on it", past.items().isEmpty());
        assertFalse("nothing ended the surprise shelf", past.hasMore());
    }

    /**
     * The bound is on the page number, so the shelf can be opened again.
     *
     * That is what makes three hundred honest rather than a shelf somebody has
     * used up: backing out to the roots and tapping Surprise me draws a fresh
     * three hundred, because {@code CatalogueView.restart()} sets the page back
     * to zero for every descent and this endpoint resamples. Nothing is
     * remembered between openings and nothing resumes. An endless scroll is a
     * crawler; a deliberate act repeated is a client.
     */
    @Test
    public void thesurpriseShelfCanBeOpenedAgainOnceItsBoundIsReached() throws Exception {
        Canned http = new Canned().then(200, RANDOM);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);
        Catalogue.Shelf random = shelf(http, "random");

        catalogue.open(random, Catalogue.Query.none(), 10);
        assertTrue("the bound cost a request", http.asked.isEmpty());

        Catalogue.Page again = catalogue.open(random, Catalogue.Query.none(), 0);

        assertEquals("opening the shelf again asked for nothing", 1, http.asked.size());
        assertEquals(ZxInfo.API + "games/random/30?mode=compact", http.asked.get(0));
        assertEquals(1, again.items().size());
    }

    /**
     * A random shelf counts nothing, whatever the reply claims.
     *
     * The live reply's total is {@code {"value":10000,"relation":"gte"}} - the
     * Elasticsearch cap rather than a count - and there is nothing to count
     * anyway, since every page is an independent draw. Printed beside the
     * shelf's own name it would read as "10,000 surprises", of which this list
     * has seen thirty.
     */
    @Test
    public void arandomShelfCountsNothing() throws Exception {
        Canned http = new Canned().then(200, RANDOM);

        Catalogue.Page page = new ZxInfoCatalogue(http).open(
                shelf(http, "random"), Catalogue.Query.none(), 0);

        assertEquals("the shelf printed Elasticsearch's cap as a count",
                     Catalogue.Page.UNKNOWN_TOTAL, page.total());
        assertEquals("Game of the Yet to Come", page.items().get(0).title());
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

    // --- games like this one -----------------------------------------------------------------

    /**
     * A way in built from an entry, and it costs nothing to build.
     *
     * <b>The same mechanism the letters and the genres use</b> - an id carrying
     * an id - which is the whole reason the screen needed no new idea for this:
     * what comes back is an ordinary shelf, opened by an ordinary {@code open}.
     * The label is the caller's, already translated, because there is nothing
     * off the wire to call this shelf and a catalogue has no {@code Context}.
     *
     * The request is asserted absent as well: a way in that fetched a page
     * while a pane was being laid out would be a request nobody asked for, on a
     * host that blocks on behaviour patterns.
     */
    @Test
    public void similarToAnEntryIsAshelfCarryingItsIdAndCostsNothing() {
        Canned http = new Canned();

        Catalogue.Shelf shelf = new ZxInfoCatalogue(http)
                .similarTo(anItem("0002259"), "Games like Head over Heels");

        assertNotNull(shelf);
        assertEquals("building a way in cost a request", 0, http.asked.size());
        assertEquals("Games like Head over Heels", shelf.label());
        assertTrue("the entry id is not in the shelf id: " + shelf.id(),
                   shelf.id().contains("0002259"));
        assertFalse("a shelf about one game asked for text",
                    shelf.accepts(Catalogue.Shelf.Accepts.TEXT));
    }

    /** Nothing to be like: an entry with no id would send {@code
     *  games/morelikethis/} with nothing on the end of it, which is a request
     *  worth not making. */
    @Test
    public void similarToNothingIsNoShelf() {
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(new Canned());

        assertEquals(null, catalogue.similarTo(null, "x"));
        assertEquals(null, catalogue.similarTo(anItem(null), "x"));
        assertEquals(null, catalogue.similarTo(anItem(""), "x"));
    }

    /**
     * Opening it asks {@code games/morelikethis/{game-id}}.
     *
     * <b>The id is in the path and the prefix stays behind.</b> A shelf id is
     * this catalogue's own; {@code more:} leaking into the path asks for an
     * entry that does not exist. And there is no {@code offset} to send - that
     * endpoint has none - so a leftover one would be silently ignored by this
     * service and would say, wrongly, that this shelf can be paged.
     */
    @Test
    public void asimilarShelfAsksTheMoreLikeThisEndpoint() throws Exception {
        Canned http = new Canned().then(200, MORE_LIKE_THIS);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);

        catalogue.open(catalogue.similarTo(anItem("0002259"), "Games like Head over Heels"),
                       Catalogue.Query.none(), 0);

        String url = http.asked.get(0);
        assertEquals(ZxInfo.API + "games/morelikethis/0002259?mode=compact&size=30", url);
        assertFalse("the shelf id's prefix went out in the request: " + url,
                    url.contains("%3A"));
        assertNoFilter(url);
    }

    /**
     * Its rows are read like any other page, and it counts nothing.
     *
     * The envelope is the same {@code hits.hits[]._source} a search answers
     * with, which is a measured fact about this endpoint and not an assumption
     * - this is what would fail if it stopped being true.
     *
     * The total it sends is real (1,858, {@code "relation":"eq"}) and is still
     * not printed: thirty is all this shelf can ever show, so a count beside
     * its name is a number the list cannot reach, which reads as a shelf that
     * stopped early.
     */
    @Test
    public void asimilarShelfReadsItsRowsAndPrintsNoCount() throws Exception {
        Canned http = new Canned().then(200, MORE_LIKE_THIS);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);

        Catalogue.Page page = catalogue.open(
                catalogue.similarTo(anItem("0002259"), "Games like Head over Heels"),
                Catalogue.Query.none(), 0);

        assertEquals(2, page.items().size());
        assertEquals("Slightly Spooky", page.items().get(0).title());
        assertEquals("0024427", page.items().get(0).id());
        assertEquals("Arcade Game", page.items().get(0).kind());

        // The top match is one the row draws greyed, with the reason - which is
        // the service's own answer here and not this fixture's invention.
        assertFalse(page.items().get(0).available());

        assertEquals("the shelf printed a count it can never reach",
                     Catalogue.Page.UNKNOWN_TOTAL, page.total());
    }

    /**
     * And it is one page: the endpoint takes a size and no offset.
     *
     * The second page is empty and costs nothing - this returns before a path
     * is built, so a fling at the bottom of the shelf sends no request. Ended
     * here rather than in {@code Page.hasMore}, whose contract the paging
     * shelves depend on.
     */
    @Test
    public void asimilarShelfDoesNotPageOn() throws Exception {
        Canned http = new Canned().then(200, MORE_LIKE_THIS);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);

        Catalogue.Page second = catalogue.open(
                catalogue.similarTo(anItem("0002259"), "Games like Head over Heels"),
                Catalogue.Query.none(), 1);

        assertEquals("a second page of similar games cost a request", 0, http.asked.size());
        assertTrue("a second page of similar games brought rows", second.items().isEmpty());
        assertFalse("the similar shelf pages for ever", second.hasMore());
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

    /**
     * A 404 ends the shelf and says nothing about how big it is.
     *
     * A 404 from a search is "nothing here" rather than a failure - the same
     * reading {@code ZxInfo.ask} gives it - so the page is empty and {@code
     * hasMore} is false. But the count that goes with it must be {@code
     * UNKNOWN_TOTAL} and not zero: nothing counted anything, and a zero is
     * drawn beside the shelf's own name as "· 0", which states as a fact about
     * the catalogue what is only this app failing to get an answer.
     */
    @Test
    public void afourOhFourEndsTheShelfWithoutClaimingItIsEmpty() throws Exception {
        Canned http = new Canned().then(404, "");

        Catalogue.Page page = new ZxInfoCatalogue(http).open(
                shelf(http, "search"), Catalogue.Query.text("nothing at all"), 0);

        assertTrue("a 404 is not a failure, it is an empty shelf",
                   page.items().isEmpty());
        assertFalse("a 404 must not be paged past", page.hasMore());
        assertEquals("a 404 counted the shelf at zero",
                     Catalogue.Page.UNKNOWN_TOTAL, page.total());
    }

    // --- helpers ----------------------------------------------------------------------------

    // --- no shelf can honour a sort any more ---------------------------------------------

    /**
     * ZXInfo declares nothing but {@code DEFAULT}, on every shelf, including
     * the ones that <em>are</em> a search - see {@link
     * ZxInfoCatalogue#sorts()}'s own comment for why {@code TOP} was removed
     * rather than kept for {@code /search} alone: it sorted by Elasticsearch's
     * relevance, not by the rating {@link Item#rating()} now shows beside it,
     * and the two visibly disagreed - measured on "arkanoid", where relevance
     * ranked a 5-vote, 5/10 entry above a 117-vote, 8.3/10 one. Five plausible
     * parameter names for sorting by the rating itself were tried against the
     * live service and every one was silently ignored, so there is no sort
     * left here that is not either wrong or impossible.
     */
    @Test
    public void everyShelfDeclaresOnlyDefault() {
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(new Canned());
        List<Catalogue.Sort> just = Collections.singletonList(Catalogue.Sort.DEFAULT);

        assertEquals(just, catalogue.sorts());

        for (String id : new String[] { "search", "letter", "genres", "newest", "random" }) {
            assertEquals(id + " must not offer a sort ZXInfo cannot honestly do",
                         just, catalogue.sortsFor(shelfNamed(id)));
        }

        Catalogue.Shelf like = catalogue.similarTo(anItem("0002259"), "Games like this");
        assertEquals("games/morelikethis takes no sort", just, catalogue.sortsFor(like));
    }

    /**
     * ...and a genre sub-shelf the same way, built by opening Categories
     * rather than by hand, so this asserts about the shelf the service's own
     * reply produced - the prefix is internal and a test that guessed at it
     * would be pinning the guess.
     */
    @Test
    public void agenreSubShelfDeclaresOnlyDefaultToo() throws Exception {
        Canned http = new Canned().then(200, METADATA_LIVE);
        ZxInfoCatalogue catalogue = new ZxInfoCatalogue(http);

        Catalogue.Shelf utility = catalogue.open(shelf(http, "genres"),
                                                 Catalogue.Query.none(), 0).shelves().get(1);

        assertEquals(Collections.singletonList(Catalogue.Sort.DEFAULT),
                     catalogue.sortsFor(utility));
    }

    /**
     * And no shelf is ever sent {@code sort=score_desc}, even handed a
     * directly-built {@code Query} asking for {@link Catalogue.Sort#TOP} -
     * the screen itself can no longer choose that sort, since {@link
     * ZxInfoCatalogue#sorts()} never declares it, but this pins the seam's
     * own behaviour rather than only the UI's. Search included, which is the
     * one branch that genuinely used to honour it.
     *
     * <b>Not genres.</b> Its root needs {@code METADATA_LIVE} rather than
     * {@link #SEARCH}, and its sub-shelf's own declared sorts are already
     * pinned by {@link #agenreSubShelfDeclaresOnlyDefaultToo} - proving the
     * screen can never build a {@code Query} carrying {@code TOP} there is
     * enough without a second, differently-shaped request just to check the
     * raw URL too.
     */
    @Test
    public void noShelfIsEverSentAsort() throws Exception {
        for (String id : new String[] { "search", "newest", "random" }) {
            String url = urlOpening(shelfNamed(id),
                                    Catalogue.Query.none().sortedBy(Catalogue.Sort.TOP));

            assertFalse(id + " was sent a sort ZXInfo no longer offers: " + url,
                        url.contains("score_desc"));
        }

        String letter = urlOpening(new ZxInfoCatalogue(new Canned())
                .open(shelfNamed("letter"), Catalogue.Query.none(), 0).shelves().get(0),
                Catalogue.Query.none().sortedBy(Catalogue.Sort.TOP));

        assertFalse("a letter shelf was sent a sort: " + letter,
                    letter.contains("score_desc"));
    }

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

    /** A row as a list hands one over - id and title and nothing that matters
     *  here, since what a similar-games shelf is built from is the id. */
    private static Catalogue.Item anItem(String id) {
        return Catalogue.Item.builder(id)
                .title("Head over Heels").year("1987").publisher("Ocean Software Ltd")
                .kind("Arcade Game").availability("Available")
                .build();
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
