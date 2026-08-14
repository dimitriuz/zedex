package dev.ldlab.zedex.library.scrape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.ldlab.zedex.library.catalogue.Fixtures;

import org.junit.Test;

import java.util.List;
import java.util.Locale;

/**
 * zxart as a scraping source, against captured replies - on the JVM, exactly
 * as {@link ZxartApi} and {@code ZxartCatalogue} are, because none of this is
 * Android: {@code unitTests.returnDefaultValues} would answer null for
 * anything reached from {@code android.*} and every assertion here would pass
 * having tested nothing.
 *
 * <b>{@link Fixtures#PROD_SEARCH} and {@link Fixtures#RELEASES_HEAD_OVER_HEELS}
 * pair with each other, and nothing else in this file pairs {@code
 * PROD_SEARCH} with a release list of a different entry.</b> {@code
 * PROD_SEARCH} is a real {@code zxProdSearch=head over heels} reply whose top
 * hit is entry 100938, Head over Heels - so the release list any test
 * confirms against has to be 100938's own, or a canned queue's inability to
 * check that two replies describe the same entry would let a test pass while
 * pairing a candidate with somebody else's files. (An earlier draft of this
 * class did exactly that - {@code Licence to Kill.tzx} against Licence to
 * Kill's own releases while the search reply on top of the queue was Head
 * over Heels' - and it would have gone green regardless.)
 */
public class ZxartTest {

    /**
     * The confirmation, which is the only reason this provider is worth
     * having beside the other two.
     *
     * zxart has no md5 filter - zxProdMd5 and zxReleaseMd5 are both ignored -
     * but every release lists releaseStructure, a recursive tree with an md5
     * for the zip *and* for each file inside it. So a name search's
     * candidates can be confirmed by hashing the file on disk, and an
     * unzipped .tzx matches the same row its zip does - this confirms
     * against a file <em>inside</em> the archive; {@link
     * #theZipsHashConfirmsAsWell} confirms against the archive itself.
     */
    @Test
    public void aCandidateConfirmedByHashIsExact() throws Exception {
        Pace.forget();
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PROD_SEARCH)
                                                    .then(Fixtures.RELEASES_HEAD_OVER_HEELS);

        List<Candidate> found = new Zxart(http, Locale.ENGLISH).search(
                game("Head Over Heels.tzx", "82bb33587530d337323ef3cd4456d4c4"));

        assertTrue(found.get(0).exact);
        assertEquals("100938", found.get(0).handle);
    }

    /**
     * The zip's own hash confirms too, and that is the commoner case: most
     * people have the file as the archive downloaded it.
     */
    @Test
    public void theZipsHashConfirmsAsWell() throws Exception {
        Pace.forget();
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PROD_SEARCH)
                                                    .then(Fixtures.RELEASES_HEAD_OVER_HEELS);

        List<Candidate> found = new Zxart(http, Locale.ENGLISH).search(
                game("HeadOverHeels.tzx.zip", "b95d7490a4258bfbd6782af62a862602"));

        assertTrue(found.get(0).exact);
        assertEquals("100938", found.get(0).handle);
    }

    /**
     * No hash match is not a failure.
     *
     * The name candidates stand, marked as the guesses they are, and {@code
     * Merge}'s fill-gaps rule bounds what a wrong one can cost - a wrong
     * cover on a game nobody has scraped by hand, never an overwritten
     * value.
     *
     * <b>Three release-list replies, not one.</b> {@link
     * Fixtures#PROD_SEARCH} answers with three candidates (100938, 349650,
     * 349658), and a bogus hash confirms none of them - so all three are
     * checked before {@link #search} gives up, exactly as {@link
     * #onlyTheFirstFewCandidatesAreConfirmed} demonstrates for a four-row
     * search. The brief this class was written from queued only one reply
     * here, which only holds together for a search reply with a single
     * candidate; against {@code PROD_SEARCH}'s real three it throws {@code
     * Canned}'s "exhausted" exception on the second candidate rather than
     * proving anything about the third.
     */
    @Test
    public void withoutAHashMatchTheNameCandidatesStand() throws Exception {
        Pace.forget();
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PROD_SEARCH)
                                                    .then(Fixtures.RELEASES_HEAD_OVER_HEELS)
                                                    .then(Fixtures.RELEASES_HEAD_OVER_HEELS)
                                                    .then(Fixtures.RELEASES_HEAD_OVER_HEELS);

        List<Candidate> found = new Zxart(http, Locale.ENGLISH).search(
                game("Head over Heels.tzx", "00000000000000000000000000000000"));

        assertFalse("a name match must not claim to be certain", found.get(0).exact);
        assertEquals("100938", found.get(0).handle);
    }

    /**
     * A file whose md5 cannot be read never asks for a release list at all.
     *
     * That is the commonest case under a tree grant, where hashing means
     * reading the whole file through the documents provider - which is why
     * {@code Provider.Game#md5} is a supplier rather than a value. Asking for
     * releases anyway would spend a paced request per candidate to compare
     * against nothing.
     */
    @Test
    public void nothingIsConfirmedWithoutAHash() throws Exception {
        Pace.forget();
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PROD_SEARCH);

        List<Candidate> found = new Zxart(http, Locale.ENGLISH).search(
                game("Head over Heels.tzx", null));

        assertFalse(found.isEmpty());
        assertFalse(found.get(0).exact);
        assertEquals("one search and no confirmation", 1, http.asked.size());
    }

    /**
     * At most three candidates are confirmed.
     *
     * A search for a common word answers with hundreds - "head" alone is
     * 271 - and confirming each is one paced request each against an archive
     * that blocks on behaviour patterns. Three is the bound; the fourth
     * candidate is never asked about, however promising its name.
     */
    @Test
    public void onlyTheFirstFewCandidatesAreConfirmed() throws Exception {
        Pace.forget();
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PROD_SEARCH_MANY)
                                                    .then(Fixtures.RELEASES_HEAD_OVER_HEELS)
                                                    .then(Fixtures.RELEASES_HEAD_OVER_HEELS)
                                                    .then(Fixtures.RELEASES_HEAD_OVER_HEELS);

        new Zxart(http, Locale.ENGLISH).search(
                game("Head.tzx", "00000000000000000000000000000000"));

        assertEquals("one search and three confirmations, never a fourth",
                     4, http.asked.size());
    }

    /** Five is the ceiling and it is stated honestly: one search, up to
     *  three confirmations, one prod. Media are static files and cost
     *  nothing. */
    @Test
    public void theCostIsTheCeilingAndNotTheAverage() {
        assertEquals(5, new Zxart(new Fixtures.Canned(), Locale.ENGLISH)
                            .costPerGame(Provider.Wanted.usual()));
    }

    /**
     * {@code fetch} writes {@link dev.ldlab.zedex.library.meta.Meta#genre}
     * into the store, and {@code Filters}/{@code Facets} group a whole
     * collection by that one string - so a genre read in Russian for half a
     * collection and English for the other half would be two facets for one
     * genre, and {@code Merge}'s fill-gaps rule means whichever scrape
     * landed first would keep the bucket for ever. Built with Russian on
     * purpose: if {@code fetch} ever went back to asking in the
     * constructor's own locale, this is what would catch it. Browsing is
     * different and stays localised - see {@code ZxartCatalogueTest}, which
     * is exactly why this class does not also assert {@link #search}'s own
     * request carries a locale-derived language segment: it already does,
     * on purpose, and is not this test's concern.
     */
    @Test
    public void fetchAsksInEnglishWhateverTheLocaleIs() throws Exception {
        Pace.forget();
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PROD_LICENCE_TO_KILL);

        new Zxart(http, new Locale("ru")).fetch(
                new Candidate("92668", "Licence to Kill", "1989", null, true),
                Provider.Wanted.nothing());

        assertEquals(1, http.asked.size());
        assertTrue("fetch must ask in English", http.asked.get(0).contains("language:eng"));
        assertFalse("never the provider's own locale",
                    http.asked.get(0).contains("language:rus"));
    }

    /**
     * Nothing is hashed when the search finds nothing to confirm against.
     *
     * {@code Provider.Game#md5} is a supplier precisely because taking it
     * means reading the whole file through the documents provider - on a
     * phone, for a disk image, that is real I/O. A search with no candidates
     * has nothing a hash could confirm, so the hash must never be taken at
     * all - not "taken and then discarded".
     */
    @Test
    public void aSearchWithNoCandidatesNeverHashesTheFile() throws Exception {
        Pace.forget();
        Fixtures.Canned http = new Fixtures.Canned().then(NO_MATCH);
        CountingGame game = new CountingGame("Some Obscure Thing.tzx");

        List<Candidate> found = new Zxart(http, Locale.ENGLISH).search(game);

        assertTrue(found.isEmpty());
        assertEquals("md5() must not be called with nothing to confirm against",
                     0, game.md5Calls);
    }

    // --- a game to ask about -------------------------------------------------------------

    /** A search reply with no rows at all - not a capture, since there is
     *  nothing substantive to get wrong from memory about an empty array and
     *  a success status; {@code Fixtures.java}'s own "not one character from
     *  memory" rule is about the replies that carry real content. */
    private static final String NO_MATCH =
            "{\"totalAmount\":0,\"start\":0,\"limit\":10,\"responseData\":{\"zxProd\":[]},"
            + "\"responseStatus\":\"success\"}";

    /** A stand-in for a file on disk, the same shape {@code ZxInfoTest}'s own
     *  {@code AGame} takes - {@code md5} is a value here rather than a real
     *  supplier because nothing in this class needs to prove the supplier is
     *  called at most once <em>in general</em>; that is {@link
     *  Provider.Game}'s own contract to keep. {@link CountingGame} is the one
     *  place this file does need to know how many times it was called. */
    private static Provider.Game game(String filename, String md5) {
        return new Provider.Game() {
            @Override public String path() { return "./" + filename; }
            @Override public String filename() { return filename; }
            @Override public long size() { return -1; }
            @Override public String md5() { return md5; }
        };
    }

    /** {@link #game}'s twin, counting every {@code md5()} call so {@link
     *  #aSearchWithNoCandidatesNeverHashesTheFile} can assert on zero rather
     *  than trust that nothing crashed. */
    private static final class CountingGame implements Provider.Game {
        private final String filename;
        int md5Calls;

        CountingGame(String filename) {
            this.filename = filename;
        }

        @Override public String path() { return "./" + filename; }
        @Override public String filename() { return filename; }
        @Override public long size() { return -1; }

        @Override
        public String md5() {
            md5Calls++;
            return "ea37a787becbdb2c74dada8e668b8f37";
        }
    }
}
