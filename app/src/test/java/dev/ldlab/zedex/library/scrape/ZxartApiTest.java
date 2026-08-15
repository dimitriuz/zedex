package dev.ldlab.zedex.library.scrape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.ldlab.zedex.library.catalogue.Fixtures;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Locale;

/**
 * zxart's URL grammar, on the JVM because it is arithmetic and string
 * building and no part of it is Android.
 *
 * <b>Every name asserted here was measured against the live service on
 * 2026-08-14, and the measurement was only meaningful because of a control:
 * an unrecognised zxart parameter is ignored rather than refused.</b> A
 * deliberate `zzznonsense` came back byte-identical to no parameter at all,
 * 58,032 results either way - so a typo in one of these constants is not a
 * failed request, it is a search that quietly matches everything. That is
 * what this test exists to stop.
 */
public class ZxartApiTest {

    @Test
    public void aPageOfAShelfIsOnePath() {
        String path = new ZxartApi.Ask(ZxartApi.PROD)
                .language("eng")
                .page(0, 30)
                .path();

        assertEquals("action:filter/export:zxProd/language:eng/start:0/limit:30/", path);
    }

    /**
     * <b>{@code start} is an item offset, not a page number.</b> The opposite
     * of ZXInfo's {@code offset}, where confusing the two let a shelf walk
     * past its own end one paced request per fling. Page two of thirty rows
     * starts at thirty.
     */
    @Test
    public void theSecondPageStartsAtTheRowAfterTheFirst() {
        String path = new ZxartApi.Ask(ZxartApi.PROD).language("eng").page(1, 30).path();

        assertEquals("action:filter/export:zxProd/language:eng/start:30/limit:30/", path);
    }

    /** Absent, limit defaults to 1000 - a 1.18 MB reply, measured. So it is
     *  not possible to build a path without one. */
    @Test
    public void everyPathCarriesALimit() {
        assertEquals("action:filter/export:zxProd/language:eng/start:0/limit:1/",
                     new ZxartApi.Ask(ZxartApi.PROD).language("eng").page(0, 1).path());
    }

    @Test
    public void aFilterIsOneSegmentAndItsValueIsEncoded() {
        String path = new ZxartApi.Ask(ZxartApi.PROD)
                .language("eng")
                .page(0, 5)
                .filter(ZxartApi.FILTER_SEARCH, "head over heels")
                .path();

        assertEquals("action:filter/export:zxProd/language:eng/start:0/limit:5/"
                     + "filter:zxProdSearch=head%20over%20heels/", path);
    }

    /** A space is %20 and never +: the measured request that worked used %20,
     *  and java.net.URLEncoder's + is a form encoding, not a path one. */
    @Test
    public void encodingIsPercentAndNeverPlus() {
        assertEquals("head%20over%20heels", ZxartApi.encode("head over heels"));
        assertEquals("a%2Fb%3Ac%3Bd", ZxartApi.encode("a/b:c;d"));
        assertEquals("R-Type_1.0~x", ZxartApi.encode("R-Type_1.0~x"));
        assertEquals("%D0%98%D0%B3%D1%80%D1%8B", ZxartApi.encode("Игры"));
    }

    /** The names, measured. Two of these read wrong in this project's earlier
     *  notes: zxProdTitleSearch is ignored and zxProdSearch is the real one. */
    @Test
    public void theMeasuredNames() {
        assertEquals("zxProdSearch", ZxartApi.FILTER_SEARCH);
        assertEquals("zxProdId", ZxartApi.FILTER_PROD_ID);
        assertEquals("zxProdCategory", ZxartApi.FILTER_CATEGORY);
        assertEquals("authorId", ZxartApi.FILTER_AUTHOR);
        assertEquals("authorSearch", ZxartApi.FILTER_AUTHOR_SEARCH);
        assertEquals("votes,desc", ZxartApi.ORDER_TOP);
        assertEquals("date,desc", ZxartApi.ORDER_NEWEST);
        assertEquals("title,asc", ZxartApi.ORDER_TITLE);
    }

    @Test
    public void anOrderIsItsOwnSegment() {
        String path = new ZxartApi.Ask(ZxartApi.PROD)
                .language("eng").page(0, 30).order(ZxartApi.ORDER_TOP).path();

        assertEquals("action:filter/export:zxProd/language:eng/start:0/limit:30/"
                     + "order:votes,desc/", path);
    }

    /** Three of the app's nine languages are zxart's; the other six read
     *  English rather than Russian, which is what the service defaults to. */
    @Test
    public void theLanguageIsTheUsersWhereTheServiceHasIt() {
        assertEquals("rus", ZxartApi.language(new Locale("ru")));
        assertEquals("spa", ZxartApi.language(new Locale("es")));
        assertEquals("eng", ZxartApi.language(Locale.ENGLISH));
        assertEquals("eng", ZxartApi.language(new Locale("pl")));
        assertEquals("eng", ZxartApi.language(new Locale("uk")));
    }

    /** Titles arrive HTML-escaped, measured: "Girl &amp; Sea" and
     *  "Shoot &#039;em up (Shmups)". Unescaped once, here. */
    @Test
    public void titlesAreUnescapedOnce() {
        assertEquals("Girl & Sea", ZxartApi.unescape("Girl &amp; Sea"));
        assertEquals("Shoot 'em up (Shmups)", ZxartApi.unescape("Shoot &#039;em up (Shmups)"));
        assertEquals("doom'er", ZxartApi.unescape("doom&#039;er"));
        assertEquals("a < b > c \" d", ZxartApi.unescape("a &lt; b &gt; c &quot; d"));
        assertEquals(null, ZxartApi.unescape(null));
    }

    /** The wrapper, against a reply the service actually sent. */
    @Test
    public void aReplyIsRowsAndATotal() throws Exception {
        Pace.forget();
        ZxartApi api = new ZxartApi(new Fixtures.Canned().then(Fixtures.PROD_LICENCE_TO_KILL));

        JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.PROD).page(0, 2)
                                   .filter(ZxartApi.FILTER_PROD_ID, "92668"));

        assertEquals(1, ZxartApi.totalOf(reply));
        assertEquals(1, ZxartApi.rows(reply, ZxartApi.PROD).size());
        assertEquals("Licence to Kill",
                     ZxartApi.rows(reply, ZxartApi.PROD).get(0).optString("title"));
    }

    /** An entity nobody asked for is empty, not an exception - a reply about
     *  prods holds no releases and a caller reading both must not crash. */
    @Test
    public void anAbsentEntityIsNoRows() throws Exception {
        Pace.forget();
        ZxartApi api = new ZxartApi(new Fixtures.Canned().then(Fixtures.PROD_LICENCE_TO_KILL));
        JSONObject reply = api.ask(new ZxartApi.Ask(ZxartApi.PROD).page(0, 2));

        assertTrue(ZxartApi.rows(reply, ZxartApi.RELEASE).isEmpty());
    }

    /**
     * A 500 is NETWORK, and the comment on refusalFor says why that is not
     * complacency: zxart answers 500 with an empty body for a request it does
     * not understand, so this is also what a wrong name here looks like.
     */
    @Test
    public void aRefusalIsToldApartByKind() {
        ZxartApi api = new ZxartApi(new Fixtures.Canned());

        assertEquals(ScrapeException.Kind.CLOSED, api.refusalFor(429).kind);
        assertEquals(ScrapeException.Kind.CLOSED, api.refusalFor(403).kind);
        assertEquals(ScrapeException.Kind.NETWORK, api.refusalFor(500).kind);
        assertEquals(ScrapeException.Kind.MALFORMED, api.refusalFor(418).kind);
    }

    /** responseStatus is the service's own word for whether it answered.
     *  Anything else is not a page with no rows, it is a reply to distrust. */
    @Test(expected = ScrapeException.class)
    public void anythingButSuccessIsMalformed() throws Exception {
        Pace.forget();
        new ZxartApi(new Fixtures.Canned().then("{\"responseStatus\":\"error\"}"))
                .ask(new ZxartApi.Ask(ZxartApi.PROD).page(0, 1));
    }
}
