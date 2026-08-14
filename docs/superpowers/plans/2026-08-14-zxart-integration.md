# zxart.ee Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make zxart.ee a second browsable catalogue in the library's fourth tab — software, music and graphics, with a Top-rated sort — and a fourth scraping source that can identify a file exactly by hash.

**Architecture:** One client, `ZxartApi`, owns zxart's URL grammar, pacing, language and parsing; nothing else knows zxart exists. `ZxartCatalogue implements Catalogue` sits on it for browsing, `Zxart implements Provider` for scraping. Three seam additions, all defaulted so ZXInfo owes nothing: `knowsFormats()`, `sorts()` and a sort on `Query`. Imports reuse the paths books and magazines already take.

**Tech Stack:** Java 8 source level, Android `minSdk 30`, `org.json` (already on the JVM test classpath — see `app/build.gradle`), JUnit 4 on the JVM, UI Automator on a device. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-14-zxart-integration-design.md` — read it first; every "measured" claim below is measured there, and the captured replies are in `review/zxart/` (gitignored, on this machine only).

## Global Constraints

- **Never modify `vendor/`.** Nothing in this plan goes near the native side.
- **The JVM tier is where decisions are tested.** `ZxartApi`, `ZxartTree`, `Kinds` and the parsing must not touch `android.net.Uri`, `android.util.Log`, `Context` or any other Android type, or their tests have to move to a device and take minutes instead of seconds. `Http` is an interface and a fake implements it (`Canned`, copied from `ZxInfoCatalogueTest`).
- **`unitTests.returnDefaultValues = true`**, so any Android type reached from a JVM test silently answers null or zero. That is why the rule above is a rule and not a preference.
- **Percent-encoding is ours, not `Uri.encode`'s**, for the same reason.
- **Pacing: 250 ms, per host.** `Pace.before(ZxartApi.HOST, ZxartApi.MINIMUM_INTERVAL_MS)` immediately before every request, never after. Tests call `Pace.forget()` in `@Before`.
- **A recorded reply is one the service actually sent.** Fixtures are copied verbatim out of `review/zxart/`; where a boundary value has to be invented, the fixture's comment says it was invented.
- **Every filter and order name is a constant with the date it was measured.** An unrecognised zxart filter is *ignored*, so a typo is a search that quietly matches everything — 58,032 results reading as success.
- **A new string is nine files:** `values/` plus `values-cs,-de,-es,-fr,-it,-pl,-ru,-uk`. Run `scripts/check-strings.py` before every commit that adds one.
- **Commit subjects take a conventional prefix** (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`) and the body explains *why*.
- **Branch:** work continues on `feature/zxart-integration`, which already holds the design commit.

### Commands

```sh
# JVM tier, one class
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest \
    --tests 'dev.ldlab.zedex.library.catalogue.ZxartApiTest'

# JVM tier, everything
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest

# device tier — install by hand, never `connectedDebugAndroidTest`, which
# uninstalls first and wipes the SAF content-folder grant that cannot be
# restored from a command line
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.ldlab.zedex.library.catalogue.ZxartCatalogueTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner

# translations and preferences
scripts/check-strings.py && scripts/check-prefs.py
```

`-r` on `am instrument` is not optional: without it `ASSUMPTION_FAILURE` is folded into `OK (n tests)`, and a class that skipped everything prints exactly like a class that passed.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `app/src/main/java/dev/ldlab/zedex/library/scrape/ZxartApi.java` | The whole of zxart's grammar: URL building, percent-encoding, language, paging arithmetic, pacing, the reply wrapper, HTML unescaping, refusals. No zxart knowledge lives anywhere else. |
| `app/src/main/java/dev/ldlab/zedex/library/catalogue/ZxartTree.java` | The 285-category tree: titles, children, and the leaf→root walk that decides an import's folder by id rather than by a word. |
| `app/src/main/java/dev/ldlab/zedex/library/catalogue/ZxartCatalogue.java` | `Catalogue`: shelves, sorts, paging, items, versions, `legalStatus` translation, and the music and graphics sub-shelves. |
| `app/src/main/java/dev/ldlab/zedex/library/scrape/Zxart.java` | `Provider`: name search, md5 confirmation against `releaseStructure`, media mapping, `hardwareRequired`, the video link. |
| `app/src/test/java/dev/ldlab/zedex/library/scrape/ZxartApiTest.java` | JVM: grammar, encoding, language, paging arithmetic, unescaping, refusals. |
| `app/src/test/java/dev/ldlab/zedex/library/catalogue/ZxartTreeTest.java` | JVM: tree parse, roots, children, leaf→root, unknown id. |
| `app/src/test/java/dev/ldlab/zedex/library/catalogue/ZxartCatalogueTest.java` | JVM: shelves, sorts, items, formats, `legalStatus`, music and graphics. |
| `app/src/test/java/dev/ldlab/zedex/library/scrape/ZxartTest.java` | JVM: search, md5 confirmation, media mapping, `hardwareRequired`, video link. |
| `app/src/test/java/dev/ldlab/zedex/library/catalogue/Fixtures.java` | The captured replies, verbatim, shared by the four test classes above. |
| `app/src/androidTest/java/dev/ldlab/zedex/library/ui/CatalogueSourceTest.java` | Device: the source row, the chooser, and that switching source shows the other catalogue's shelves. |
| `app/src/androidTest/java/dev/ldlab/zedex/library/ui/CatalogueSortTest.java` | Device: the sort row appears only when declared, and applies inside a shelf. |
| `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ZxartImportTest.java` | Device: importing a prod, a tune and a picture, and the Open hand-over. |

**Modified:**

| File | Change |
|---|---|
| `library/catalogue/Catalogue.java` | `knowsFormats()`, `sorts()`, `Sort`, `Query.sortedBy`/`sort()` — all defaulted. |
| `library/catalogue/Catalogues.java` | Register zxart. |
| `library/catalogue/Kinds.java` | `MUSIC`, `GRAPHICS`, three new table words, and zxart's nine recorded root ids. |
| `library/catalogue/Pick.java` | `scr` joins `PICTURES`. |
| `library/ui/CatalogueView.java` | The source row, the sort row, the format row hidden when unanswerable. |
| `library/ui/CataloguePane.java` | The video link icon. |
| `library/ui/GameInfoView.java` | The video link icon. |
| `library/meta/Meta.java`, `library/meta/Metadata.java`, `library/scrape/Merge.java` | The `videoLink` field, end to end. |
| *(not) `machine/Suggested.java`* | Nothing. Task 15 was rewritten after `692173f`: zxart's hardware enters as `Meta.machine`/`Meta.inputs` phrases, which `Suggested` already parses — and which it now narrows by the file. |
| `library/scrape/Scrapers.java` | Register zxart, last. |
| `app/src/main/AndroidManifest.xml` | `<queries>`: `audio/*`, and `ACTION_VIEW` on `https`. |
| `res/values*/strings.xml` (9) | Six new strings. |
| `CLAUDE.md`, `README.md`, `docs/LIBRARY.md`, `docs/INTERNALS.md`, `docs/PRIVACY.md` | The rules and the prose. |

---

## Part 0 — what has to happen before any code

### Task 1: Measure what the spec refuses to guess, and write to zxart

The spec names four unmeasured things and one courtesy. None is code, all of them are inputs to later tasks, and a guess in any of them ships a table written from imagination.

**Files:**
- Modify: `docs/superpowers/specs/2026-08-14-zxart-integration-design.md`
- Create: `review/zxart/round11.json` (gitignored working file)

- [ ] **Step 1: Probe the four unknowns**

`review/zxart/probe.py` takes a JSON list of `[name, url]` pairs, spaces requests 2.5 s, sends `Zedex/1.4.12`, and skips anything already on disk. Write `review/zxart/round11.json`:

```json
[
 ["hw-sample.json", "https://zxart.ee/api/action:filter/export:zxRelease/language:eng/limit:200/"],
 ["inlay-sample.json", "https://zxart.ee/api/action:filter/export:zxRelease/language:eng/limit:200/start:200/"],
 ["music-search.json", "https://zxart.ee/api/action:filter/export:zxMusic/language:eng/limit:3/filter:zxMusicSearch=beyond/"],
 ["picture-search.json", "https://zxart.ee/api/action:filter/export:zxPicture/language:eng/limit:3/filter:zxPictureSearch=girl/"],
 ["zxinfo-nosort.json", "https://api.zxinfo.dk/v3/search?query=head&size=3&mode=compact"],
 ["zxinfo-sort.json", "https://api.zxinfo.dk/v3/search?query=head&size=3&mode=compact&sort=score_desc"]
]
```

Run: `python3 review/zxart/probe.py review/zxart/round11.json`

- [ ] **Step 2: Read the answers, with the control in mind**

An **ignored** filter returns the unfiltered total (29,672 for music, 19,408 for pictures) and the same first rows as no filter. A **working** one returns fewer. Count, do not eyeball:

```sh
cd review/zxart && python3 - <<'EOF'
import json, collections
hw = collections.Counter()
inlays = collections.Counter()
for f in ['hw-sample.json', 'inlay-sample.json']:
    for r in json.load(open(f))['responseData']['zxRelease']:
        for value in (r.get('hardwareRequired') or []): hw[value] += 1
        for url in (r.get('inlays') or []):
            name = url.rsplit('/', 1)[-1]
            inlays[name.rsplit('.', 1)[0].split('_')[-1] if '_' in name else 'front'] += 1
print("hardwareRequired:", hw.most_common())
print("inlay suffixes:", inlays.most_common())
for f in ['music-search.json', 'picture-search.json']:
    d = json.load(open(f))
    print(f, "total", d['totalAmount'], "(29672 / 19408 means ignored)")
EOF
```

- [ ] **Step 3: Record the findings in the spec**

Replace the spec's three "unmeasured" notes with what came back: the `hardwareRequired` vocabulary with counts, the inlay suffixes with counts, and whether `zxMusicSearch`/`zxPictureSearch` filter or are ignored. If a search filter is ignored, say so plainly — Task 11 then declares no Search sub-shelf for that entity, which is the seam working as intended. Add ZXInfo's `sort` result to the spec's ZXInfo line: if `sort=score_desc` changes the order, ZXInfo can declare `TOP` in Task 8; if it is ignored, it declares `DEFAULT` only and that is final.

- [ ] **Step 4: Write to zxart**

Email moroz1999 (zxart's author; the blog post at hype.retroscene.org/blog/898.html is his). Say: an Android ZX Spectrum emulator adds a browse-and-import tab and a metadata scraper against the public API; requests are user-initiated only, one at a time, 250 ms apart, no crawling, no prefetch, no mirroring; the User-Agent is `Zedex/<version>`; ask whether the pacing suits them and whether `robots.txt`'s `Disallow: /api` is meant to cover an app of this kind. Record the date sent in the spec's *Manners* section.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-08-14-zxart-integration-design.md
git commit -m "docs: measure zxart's hardware, inlay and search vocabulary

The four things the design refused to guess at, and the mail to the
archive. Recorded here rather than in code comments because two later
tasks assert tables against these counts."
```

---

## Part 1 — the catalogue

### Task 2: `ZxartApi` — the grammar, and nothing else

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/scrape/ZxartApi.java`
- Test: `app/src/test/java/dev/ldlab/zedex/library/scrape/ZxartApiTest.java`

**Interfaces:**
- Consumes: `Http`, `ScrapeException`, `Pace` (all existing, `library/scrape/`).
- Produces: `ZxartApi.HOST`, `ZxartApi.MINIMUM_INTERVAL_MS`, `ZxartApi.Ask` (a builder with `filter`, `order`, `page`, `path()`), `ZxartApi.language(Locale)`, `ZxartApi.encode(String)`, `ZxartApi.unescape(String)`, and the entity/filter/order name constants. Tasks 3, 4, 6, 11 and 13 all build their URLs through `Ask` and never by string concatenation.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/ldlab/zedex/library/scrape/ZxartApiTest.java`:

```java
package dev.ldlab.zedex.library.scrape;

import static org.junit.Assert.assertEquals;

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
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests 'dev.ldlab.zedex.library.scrape.ZxartApiTest'`
Expected: compilation failure — `ZxartApi` does not exist.

- [ ] **Step 3: Write `ZxartApi`**

```java
package dev.ldlab.zedex.library.scrape;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one thing that knows how to talk to zxart.ee.
 *
 * <b>Every name here was measured against the live service on 2026-08-14, and
 * an unrecognised name is ignored rather than refused.</b> That is the whole
 * reason this class exists as a vocabulary rather than as string
 * concatenation at each call site: a filter zxart does not recognise returns
 * the unfiltered 58,032 rows with `responseStatus: "success"`, which reads
 * exactly like a search that matched everything. Measured with a deliberate
 * nonsense parameter as the control, both as a path segment and inside
 * `filter:`; both were byte-identical to no parameter at all.
 *
 * Deliberately free of Android types - no {@code Uri}, no {@code Log}, no
 * {@code Context} - so the grammar, the paging arithmetic and the parsing are
 * tested on the JVM in seconds. {@code unitTests.returnDefaultValues} means an
 * Android type reached from a unit test answers null without complaining, so
 * this is a rule rather than a preference. Its own percent-encoder exists for
 * the same reason.
 *
 * See docs/superpowers/specs/2026-08-14-zxart-integration-design.md.
 */
public final class ZxartApi {

    public static final String HOST = "zxart.ee";
    private static final String API = "https://zxart.ee/api/";

    /**
     * The same interval ZXInfo gets, and per host rather than per object.
     *
     * zxart publishes no rate limit, and its robots.txt disallows {@code /api}
     * to every agent - so the traffic is kept to what a person actually asked
     * for: one request at a time, nothing speculative, no prefetch. See the
     * design's <em>Manners</em>.
     */
    public static final long MINIMUM_INTERVAL_MS = 250;

    // --- the entities, measured -----------------------------------------------------

    public static final String PROD = "zxProd";
    public static final String RELEASE = "zxRelease";
    public static final String CATEGORY = "zxProdCategory";
    public static final String MUSIC = "zxMusic";
    public static final String PICTURE = "zxPicture";
    public static final String AUTHOR = "author";

    // --- the filters that work ------------------------------------------------------

    public static final String FILTER_PROD_ID = "zxProdId";
    public static final String FILTER_SEARCH = "zxProdSearch";
    public static final String FILTER_CATEGORY = "zxProdCategory";
    public static final String FILTER_AUTHOR = "authorId";
    public static final String FILTER_CATEGORIES_ALL = "zxProdCategoryAll";
    public static final String FILTER_MUSIC_ID = "zxMusicId";
    public static final String FILTER_PICTURE_ID = "zxPictureId";

    /**
     * Names that are <b>ignored</b>, kept so nobody reaches for one again.
     *
     * {@code zxProdTitleSearch}, {@code zxProdTitle}, {@code zxProdTitleStart},
     * {@code zxProdMd5}, {@code zxReleaseMd5}, {@code zxProdImportId},
     * {@code zxProdWosId}, and {@code action:search} with {@code query:}. The
     * first of those is what this project's own earlier notes recorded as the
     * title search; it returns everything.
     */
    private ZxartApi() {
        // instances exist for asking; see the constructor below
    }

    // --- the orders that work -------------------------------------------------------

    public static final String ORDER_TOP = "votes,desc";
    public static final String ORDER_NEWEST = "date,desc";
    public static final String ORDER_TITLE = "title,asc";

    /* Ignored: order:rating,desc and order:votesAmount,desc. The reply calls
     * the field `rating` on music and pictures and `votes` on prods, and the
     * order only ever answers to `votes` - on all three. */

    private final Http http;

    public ZxartApi(Http http) {
        this.http = http;
    }

    /**
     * One request, parsed, or null when the service says it has nothing.
     *
     * The pacing is not this object's business beyond calling it: {@link Pace}
     * counts per host, so a sweep scraping and a grid browsing queue behind
     * one another rather than halving the interval between them.
     */
    public JSONObject ask(Ask what) throws ScrapeException {
        Pace.before(HOST, MINIMUM_INTERVAL_MS);

        String body;

        try {
            Http.Reply reply = http.get(API + what.path());

            if (reply.status == 404) return null;
            if (!reply.ok()) throw refusalFor(reply.status);

            body = reply.body;
        } catch (Http.Refused refused) {
            throw refusalFor(refused.status);
        } catch (IOException e) {
            throw new ScrapeException(ScrapeException.Kind.NETWORK,
                                      "cannot reach zxart: " + e.getMessage(), e);
        }

        if (body == null || body.isEmpty()) return null;

        JSONObject reply;

        try {
            reply = new JSONObject(body);
        } catch (JSONException e) {
            throw new ScrapeException(ScrapeException.Kind.MALFORMED,
                                      "zxart sent something that is not JSON");
        }

        if (!"success".equals(reply.optString("responseStatus", ""))) {
            throw new ScrapeException(ScrapeException.Kind.MALFORMED,
                                      "zxart answered without success");
        }

        return reply;
    }

    /** The rows of one entity, never null. */
    public static List<JSONObject> rows(JSONObject reply, String entity) {
        List<JSONObject> found = new ArrayList<>();
        if (reply == null) return found;

        JSONObject data = reply.optJSONObject("responseData");
        JSONArray list = data == null ? null : data.optJSONArray(entity);

        for (int at = 0; list != null && at < list.length(); at++) {
            JSONObject row = list.optJSONObject(at);
            if (row != null) found.add(row);
        }

        return found;
    }

    /** The service's own count. Real, unlike ZXInfo's 10,000 window cap, so a
     *  shelf can always print it. */
    public static int totalOf(JSONObject reply) {
        return reply == null ? 0 : reply.optInt("totalAmount", 0);
    }

    /**
     * What a bare status means.
     *
     * <b>A 500 from zxart is as likely to be our bug as their bad day.</b> It
     * answers 500 with an empty body for a request it does not understand -
     * measured on {@code export:zxFile}, {@code export:publisher} and the
     * documented-but-broken {@code types:zxProd,zxRelease}. So a 500 on every
     * request means a name in this file is wrong, and one 500 in a hundred is
     * the network. Both are NETWORK because both are worth retrying; the
     * difference shows up in the log, not in the kind.
     */
    public ScrapeException refusalFor(int status) {
        if (status == 429 || status == 403) {
            return new ScrapeException(ScrapeException.Kind.CLOSED,
                    "zxart refused with " + status + ", which is how an archive"
                    + " says an address has been asking too often");
        }

        if (status >= 500) {
            return new ScrapeException(ScrapeException.Kind.NETWORK,
                                       "zxart answered " + status);
        }

        return new ScrapeException(ScrapeException.Kind.MALFORMED,
                                   "zxart answered " + status);
    }

    // --- building a path ------------------------------------------------------------

    /**
     * One request, as a path.
     *
     * A builder rather than six overloads because the segments are optional in
     * combination and their <em>order</em> is fixed; a caller assembling them
     * by hand is a caller who can put {@code order:} before {@code limit:} and
     * find out that zxart ignores what it does not expect.
     */
    public static final class Ask {

        private final String entity;
        private String language = "eng";
        private int start;
        private int limit = 30;
        private String filterName;
        private String filterValue;
        private String order;

        public Ask(String entity) {
            this.entity = entity;
        }

        public Ask language(String code) {
            this.language = code;
            return this;
        }

        /** @param page zero-based; {@code start} is the row it begins at. */
        public Ask page(int page, int size) {
            this.limit = size;
            this.start = page * size;
            return this;
        }

        public Ask filter(String name, String value) {
            this.filterName = name;
            this.filterValue = value;
            return this;
        }

        /** A filter that is a flag rather than a pair - {@code
         *  filter:zxProdCategoryAll}. */
        public Ask flag(String name) {
            this.filterName = name;
            this.filterValue = null;
            return this;
        }

        public Ask order(String order) {
            this.order = order;
            return this;
        }

        public int start() {
            return start;
        }

        public String path() {
            StringBuilder path = new StringBuilder("action:filter/export:")
                    .append(entity)
                    .append("/language:").append(language)
                    .append("/start:").append(start)
                    .append("/limit:").append(limit)
                    .append('/');

            if (filterName != null) {
                path.append("filter:").append(filterName);
                if (filterValue != null) path.append('=').append(encode(filterValue));
                path.append('/');
            }

            if (order != null) path.append("order:").append(order).append('/');

            return path.toString();
        }
    }

    /**
     * Percent-encoding, ours.
     *
     * {@code Uri.encode} would keep this class off the JVM and {@code
     * URLEncoder} turns a space into {@code +}, which is a form encoding: the
     * request measured as working used {@code %20}. Everything outside the
     * unreserved set goes as {@code %XX} over UTF-8, which is safe inside a
     * path segment whatever the value is - a title with a slash, a colon or a
     * semicolon in it would otherwise become extra segments.
     */
    public static String encode(String value) {
        if (value == null) return "";

        StringBuilder out = new StringBuilder();
        byte[] bytes;

        try {
            bytes = value.getBytes("UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            return "";
        }

        for (byte raw : bytes) {
            int b = raw & 0xff;
            boolean unreserved = (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
                    || (b >= '0' && b <= '9') || b == '-' || b == '_' || b == '.' || b == '~';

            if (unreserved) {
                out.append((char) b);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", b));
            }
        }

        return out.toString();
    }

    /**
     * Which of zxart's three languages to ask in.
     *
     * The app speaks nine and zxart three, so six of them read English rather
     * than the Russian zxart defaults to. Taking the language matters because
     * it is what {@code categoriesString}, the category tree's titles and a
     * picture's tags come back in - and it is exactly why an import's folder
     * is decided from a category <em>id</em> and never from a word.
     */
    public static String language(Locale locale) {
        String tag = locale == null ? "" : locale.getLanguage();

        if ("ru".equals(tag)) return "rus";
        if ("es".equals(tag)) return "spa";

        return "eng";
    }

    /**
     * The five escapes zxart's own text arrives with.
     *
     * Measured: {@code Girl &amp; Sea}, {@code Shoot &#039;em up (Shmups)},
     * {@code doom&#039;er}. Done once here rather than in each caller, because
     * a title that reaches a row unescaped is a title somebody sees.
     */
    public static String unescape(String text) {
        if (text == null) return null;

        return text.replace("&#039;", "'")
                   .replace("&quot;", "\"")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&amp;", "&");
    }
}
```

Note the `&amp;` replacement is **last**: doing it first would turn `&amp;#039;` into `'` rather than into `&#039;`.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests 'dev.ldlab.zedex.library.scrape.ZxartApiTest'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/scrape/ZxartApi.java \
        app/src/test/java/dev/ldlab/zedex/library/scrape/ZxartApiTest.java
git commit -m "feat: zxart's grammar, measured rather than remembered

One class that knows the URL shape, the vocabulary, the pacing and the
reply wrapper, and no Android types anywhere in it so all of that is
tested on the JVM in seconds.

The vocabulary is asserted because an unrecognised zxart filter is
ignored rather than refused: a nonsense parameter returned the same
58,032 rows as no parameter, with responseStatus success. A typo in one
of these constants is therefore a search that matches everything, which
is why the names are constants and why a test reads them back.

Its own percent-encoder, because Uri.encode would drag this onto a
device and URLEncoder writes a space as + - the request measured as
working used %20."
```

---

### Task 3: The fixtures, and the reply wrapper against real bodies

**Files:**
- Create: `app/src/test/java/dev/ldlab/zedex/library/catalogue/Fixtures.java`
- Modify: `app/src/test/java/dev/ldlab/zedex/library/scrape/ZxartApiTest.java`

**Interfaces:**
- Produces: `Fixtures.PROD_LICENCE_TO_KILL`, `Fixtures.RELEASES_LICENCE_TO_KILL`, `Fixtures.CATEGORY_TREE`, `Fixtures.PROD_SEARCH`, `Fixtures.MUSIC_ROW`, `Fixtures.PICTURE_ROW`, `Fixtures.PROD_FORBIDDEN` — each a `String` of one captured reply. Tasks 4, 6, 11 and 13 read these and nothing else.
- Produces: `Fixtures.Canned` — an `Http` fake, replies in order, recording every URL asked.

- [ ] **Step 1: Write the fixtures class**

**The Java code block below is an illustration of the *shape*, not a fixture.**
It was typed into this plan by hand and is therefore exactly what the rule it
illustrates forbids: it drops `authorsInfo`, shortens four arrays, and carries an
English `categoriesString` where the real capture is Russian. Copy the capture
file, never this block. (Recorded rather than deleted because a plan that quietly
fixed its own bad example would lose the demonstration that this failure is easy
enough to make in a document about avoiding it.)

Copy the bodies **verbatim** out of `review/zxart/`, trimming only whole rows (never fields) so a reply stays a reply. `PROD_LICENCE_TO_KILL` is `af-prod-types-release`'s sibling `prod-by-id.json`; `RELEASES_LICENCE_TO_KILL` is `af-release-by-prod.json` trimmed to three of its twenty-four releases; `CATEGORY_TREE` is `af-categories-eng.json`; `PROD_SEARCH` is `af-prodsearch.json`; `MUSIC_ROW` is `af-music-by-id.json`; `PICTURE_ROW` is `af-picture-by-id.json`. For `PROD_FORBIDDEN`, take a real row from `prod-categories.json` whose `legalStatus` is `forbidden` — there are 21 in that slice, so it does not have to be invented.

```java
package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.library.scrape.Http;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Replies zxart actually sent, on 2026-08-14, captured with a spaced and
 * identified probe and pasted here.
 *
 * <b>Not one character of these was written from memory, and that is the
 * point.</b> A fixture written from memory pins the memory and passes: on the
 * ZXInfo branch three separate defects hid behind exactly that, one of which
 * made every entry in the database unimportable while every test was green and
 * two reviews approved it. Where a value below had to be invented, the comment
 * above it says so.
 *
 * Trimmed only by dropping whole rows - twenty-four releases to three - so
 * what is left is still shaped like a reply. Never by dropping fields: the
 * fields nobody reads yet are how the next person finds out what is there.
 *
 * The originals are in review/zxart/, which is gitignored, so they are on one
 * machine only; review/zxart/probe.py fetches any that are missing and skips
 * the ones that are not.
 */
public final class Fixtures {

    private Fixtures() {
    }

    /** review/zxart/prod-by-id.json - export:zxProd, filter:zxProdId=92668. */
    public static final String PROD_LICENCE_TO_KILL = "{\"totalAmount\":1,\"start\":0,"
            + "\"limit\":2,\"responseData\":{\"zxProd\":[{\"id\":92668,\"title\":\"Licence"
            + " to Kill\",\"dateCreated\":1479491025,\"dateModified\":1759422576,"
            + "\"language\":[\"en\"],\"year\":1989,\"youtubeId\":\"r1U9U1MMn6g\","
            + "\"legalStatus\":\"unknown\",\"groupsIds\":[311548],\"publishersIds\":[176138],"
            + "\"releasesIds\":[92671,92672,92673],\"imagesUrls\":["
            + "\"https://zxart.ee/zximages/id=92669;pal=srgb;type=standard;zoom=1\","
            + "\"https://zxart.ee/screenshot/id:575815/ltk_01.gif\"],\"maps\":["
            + "\"https://zxart.ee/release/id:240641/mode:download/filename:LicenceToKill.jpg\"],"
            + "\"importIds\":{\"zxdb\":\"1\",\"wos\":\"0000001\","
            + "\"vt\":\"494dbb9e6811a757f1af2b93198526ff\"},\"votes\":4,\"votesAmount\":3,"
            + "\"rzx\":[\"https://zxart.ee/release/id:554313/mode:download/"
            + "filename:licencetokill.zip\"],\"connectedCategoriesIds\":[523395],"
            + "\"categoriesString\":\"Games/Action/Shooters/Shoot &#039;em up (Shmups)\"}]},"
            + "\"responseStatus\":\"success\"}";

    // ... RELEASES_LICENCE_TO_KILL, CATEGORY_TREE, PROD_SEARCH, MUSIC_ROW,
    // PICTURE_ROW, PROD_FORBIDDEN follow the same way: copied out of
    // review/zxart/, escaped for Java, nothing else changed.

    /**
     * An {@code Http} that answers from a list and remembers what it was
     * asked.
     *
     * The same shape as {@code ZxInfoCatalogueTest.Canned}, which is
     * deliberate: two fakes that differ only in style are two things to read.
     * It lives here rather than in each test class because four classes need
     * it now.
     */
    public static final class Canned implements Http {

        private final List<Reply> replies = new ArrayList<>();
        public final List<String> asked = new ArrayList<>();

        public Canned then(int status, String body) {
            replies.add(new Reply(status, body));
            return this;
        }

        public Canned then(String body) {
            return then(200, body);
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
}
```

- [ ] **Step 2: Add the wrapper tests to `ZxartApiTest`**

```java
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
```

Add `import dev.ldlab.zedex.library.catalogue.Fixtures;`, `import org.json.JSONObject;`, `import static org.junit.Assert.assertTrue;` and check `ScrapeException`'s field name for the kind (`kind`) against `app/src/main/java/dev/ldlab/zedex/library/scrape/ScrapeException.java` before writing the assertion — it is a public final field, not a getter.

- [ ] **Step 3: Run and watch it fail, then pass**

Run the class. The four new tests fail first on the missing `Fixtures`; after Step 1 they pass. Expected: PASS, 13 tests — nine from Task 2 and four here.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/dev/ldlab/zedex/library/catalogue/Fixtures.java \
        app/src/test/java/dev/ldlab/zedex/library/scrape/ZxartApiTest.java
git commit -m "test: zxart's own replies as fixtures

Captured on 2026-08-14 with a spaced, identified probe and pasted
verbatim. The practice, not the convenience: a fixture written from
memory pins the memory and passes, which is how three defects shipped
green on the ZXInfo branch - one of them making every entry
unimportable.

Canned lives beside them because four test classes need it."
```

---

### Task 4: `ZxartTree` — 285 categories, and the walk that decides a folder

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/catalogue/ZxartTree.java`
- Test: `app/src/test/java/dev/ldlab/zedex/library/catalogue/ZxartTreeTest.java`

**Interfaces:**
- Consumes: `ZxartApi.rows`, `Fixtures.CATEGORY_TREE`.
- Produces: `ZxartTree.from(List<JSONObject>)`, `tree.roots()` → `List<ZxartTree.Node>` (with `id()` and `title()`), `tree.childrenOf(int)`, `tree.rootOf(int leafId)` → `int` or `-1`. Task 6 opens Categories with these; Task 5's folder decision consumes `rootOf`.

- [ ] **Step 1: Write the failing test**

```java
package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.ldlab.zedex.library.scrape.ZxartApi;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * zxart's category tree: nine roots, 285 categories, one 13.5 KB request.
 *
 * <b>The tree is not decoration - it is how an import's folder is decided.</b>
 * A prod says which leaf categories it is in and nothing about which root, and
 * the labels come back in whichever of zxart's three languages was asked for.
 * So the folder has to be reached by walking ids upwards; matching the words
 * would put a Russian reader's every import in Other.
 */
public class ZxartTreeTest {

    private static ZxartTree tree() throws Exception {
        JSONObject reply = new JSONObject(Fixtures.CATEGORY_TREE);
        return ZxartTree.from(ZxartApi.rows(reply, ZxartApi.CATEGORY));
    }

    /** Measured: nine, and these nine. A tenth appearing upstream is a test
     *  failure and not a silent Other, which is the notice this wants. */
    @Test
    public void thereAreNineRoots() throws Exception {
        List<ZxartTree.Node> roots = tree().roots();

        assertEquals(9, roots.size());
        assertEquals(92177, roots.get(0).id());
        assertEquals("Games", roots.get(0).title());
    }

    @Test
    public void aRootKnowsItsChildren() throws Exception {
        List<ZxartTree.Node> children = tree().childrenOf(92177);

        assertTrue("Games has ten children in the recorded tree", children.size() >= 10);
        assertTrue(titles(children).contains("Action"));
        assertTrue(titles(children).contains("Adventure"));
    }

    /**
     * The walk. 523395 is a leaf four levels down - Games / Action / Shooters
     * / Shoot 'em up - and it is what the Licence to Kill fixture actually
     * carries in connectedCategoriesIds.
     */
    @Test
    public void aLeafWalksUpToItsRoot() throws Exception {
        assertEquals(92177, tree().rootOf(523395));
    }

    /** A root is its own root, which is what a prod filed directly under one
     *  needs. */
    @Test
    public void aRootIsItsOwnRoot() throws Exception {
        assertEquals(204819, tree().rootOf(204819));
    }

    /** An id the tree has never heard of answers -1 rather than guessing, and
     *  the caller turns that into Other. A category added upstream between
     *  this session's tree and the prod being read is exactly this case. */
    @Test
    public void anUnknownIdHasNoRoot() throws Exception {
        assertEquals(-1, tree().rootOf(999999));
    }

    /** A tree built from nothing answers rather than throwing: a request that
     *  failed must leave a catalogue that still draws rows, filed under
     *  Other. */
    @Test
    public void anEmptyTreeIsUsable() {
        ZxartTree empty = ZxartTree.from(new java.util.ArrayList<JSONObject>());

        assertTrue(empty.roots().isEmpty());
        assertEquals(-1, empty.rootOf(92177));
    }

    private static List<String> titles(List<ZxartTree.Node> nodes) {
        List<String> found = new java.util.ArrayList<>();
        for (ZxartTree.Node node : nodes) found.add(node.title());
        return found;
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `... --tests 'dev.ldlab.zedex.library.catalogue.ZxartTreeTest'`
Expected: compilation failure — `ZxartTree` does not exist.

- [ ] **Step 3: Write `ZxartTree`**

```java
package dev.ldlab.zedex.library.catalogue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * zxart's 285 categories, as they arrive: a flat list where each row may name
 * its children.
 *
 * <b>Held for the session, and it earns its keep twice.</b> It is the
 * Categories shelf - nine roots, each opening onto its children and its prods
 * in one page - and it is how an import's folder is decided. A prod carries
 * {@code connectedCategoriesIds}, which are leaves; the folder belongs to the
 * root above them, and finding it means walking ids rather than reading words.
 *
 * <b>Ids, because the words are translated.</b> zxart answers in Russian by
 * default and in English or Spanish when asked, and this app asks in the
 * user's language - so {@code categoriesString} is "Игры/Экшен" for one person
 * and "Games/Action" for the next. A folder decided by matching those words
 * would be right for one language and Other for the others. Ids are the same
 * in all three.
 *
 * One request builds it: {@code export:zxProdCategory} with {@code
 * filter:zxProdCategoryAll}, 285 rows in 13.5 KB, measured. Nothing here makes
 * a request.
 */
public final class ZxartTree {

    /** One category: what to call it and what it is. */
    public static final class Node {

        private final int id;
        private final String title;

        Node(int id, String title) {
            this.id = id;
            this.title = title;
        }

        public int id() {
            return id;
        }

        /** zxart's own word, in whichever language it was asked for. */
        public String title() {
            return title;
        }
    }

    private final Map<Integer, String> titles = new LinkedHashMap<>();
    private final Map<Integer, List<Integer>> children = new LinkedHashMap<>();
    private final Map<Integer, Integer> parents = new LinkedHashMap<>();

    private ZxartTree() {
    }

    public static ZxartTree from(List<JSONObject> rows) {
        ZxartTree tree = new ZxartTree();

        for (JSONObject row : rows) {
            int id = row.optInt("id", 0);
            if (id == 0) continue;

            tree.titles.put(id, dev.ldlab.zedex.library.scrape.ZxartApi
                                    .unescape(row.optString("title", "")));

            JSONArray kids = row.optJSONArray("categories");
            List<Integer> mine = new ArrayList<>();

            for (int at = 0; kids != null && at < kids.length(); at++) {
                int child = kids.optInt(at, 0);
                if (child == 0) continue;

                mine.add(child);
                tree.parents.put(child, id);
            }

            tree.children.put(id, mine);
        }

        return tree;
    }

    /** The ways in: every category nobody names as a child, in the order they
     *  arrived. Measured as nine - Games, System Software, Misc, Educational,
     *  Compilation, Demoscene, Press, Applications, Series. */
    public List<Node> roots() {
        List<Node> found = new ArrayList<>();

        for (Map.Entry<Integer, String> each : titles.entrySet()) {
            if (!parents.containsKey(each.getKey())) {
                found.add(new Node(each.getKey(), each.getValue()));
            }
        }

        return found;
    }

    public List<Node> childrenOf(int id) {
        List<Integer> kids = children.get(id);
        if (kids == null) return Collections.emptyList();

        List<Node> found = new ArrayList<>();
        for (int kid : kids) {
            String title = titles.get(kid);
            if (title != null) found.add(new Node(kid, title));
        }

        return found;
    }

    public String titleOf(int id) {
        return titles.get(id);
    }

    /**
     * The root above a category, or -1.
     *
     * -1 rather than a guess: a category this tree has never heard of - added
     * upstream since the tree was fetched, or a tree whose request failed - is
     * an unknown kind, and an unknown kind is {@code Kinds.OTHER}. Guessing
     * would file it somewhere plausible, which is the one outcome nobody can
     * notice.
     *
     * Bounded by the number of categories, so a tree that somehow cites itself
     * as its own ancestor stops rather than spinning.
     */
    public int rootOf(int id) {
        if (!titles.containsKey(id)) return -1;

        int at = id;

        for (int step = 0; step <= titles.size(); step++) {
            Integer up = parents.get(at);
            if (up == null) return at;
            at = up;
        }

        return -1;
    }
}
```

- [ ] **Step 4: Run and watch it pass**

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/catalogue/ZxartTree.java \
        app/src/test/java/dev/ldlab/zedex/library/catalogue/ZxartTreeTest.java
git commit -m "feat: zxart's category tree, and the walk up it

Nine roots and 285 categories in one 13.5 KB request. The tree is the
Categories shelf and it is also how an import's folder is decided: a
prod names leaves, the folder belongs to the root above them.

Walked by id and never by word, because the words are translated - this
app asks zxart in the user's language, so the same category is Игры for
one person and Games for the next. An unknown id answers -1 and the
caller files it under Other rather than somewhere plausible."
```

---

### Task 5: `Kinds` learns zxart's nine roots, plus Music and Graphics

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/catalogue/Kinds.java`
- Test: `app/src/test/java/dev/ldlab/zedex/library/catalogue/KindsTest.java`

**Interfaces:**
- Produces: `Kinds.MUSIC`, `Kinds.GRAPHICS`, `Kinds.ZXART_ROOTS` (the recorded nine), `Kinds.zxartRoot(int id)` → zxart's canonical English root title or null. Task 6 sets `Item.kind` from `zxartRoot`, and `Imports` keeps calling `Kinds.folderFor(item.kind())` unchanged.

The shape here is the decision worth understanding before writing it: **`Item.kind` stays "the catalogue's own word" and becomes zxart's canonical *English* root title**, derived from the id. That keeps one folder-mapping path (`folderFor`, by word, as ZXInfo uses), needs no second tree request in another language, and leaves `Imports` untouched. The localised words are still what a person browses — the Categories shelves carry them — and the pane's one fact line reads "Games" rather than "Игры", which is the same bargain every other service word in this app already makes.

- [ ] **Step 1: Write the failing test**

Append to `KindsTest`:

```java
    // --- zxart's own nine ---------------------------------------------------------------

    /**
     * The nine root ids, recorded from the live tree on 2026-08-14.
     *
     * Ids rather than words because this is what a prod can be traced to in
     * any language, and a recorded table rather than a lookup because these
     * are what the tree is <em>expected</em> to contain: a tenth root, or one
     * renumbered, should fail here rather than quietly file a fifth of the
     * catalogue under Other.
     */
    @Test
    public void zxartsRootsAreTheNineRecorded() {
        assertEquals("Games", Kinds.zxartRoot(92177));
        assertEquals("System Software", Kinds.zxartRoot(92183));
        assertEquals("Misc", Kinds.zxartRoot(92188));
        assertEquals("Educational", Kinds.zxartRoot(92534));
        assertEquals("Compilation", Kinds.zxartRoot(202588));
        assertEquals("Demoscene", Kinds.zxartRoot(204819));
        assertEquals("Press", Kinds.zxartRoot(244858));
        assertEquals("Applications", Kinds.zxartRoot(244880));
        assertEquals("Series", Kinds.zxartRoot(551860));

        assertEquals(9, Kinds.ZXART_ROOTS.length);
    }

    /** An id that is not a root - a leaf, or something new upstream - is not
     *  answered with a plausible guess. */
    @Test
    public void anythingElseIsNotARoot() {
        assertNull(Kinds.zxartRoot(523395));
        assertNull(Kinds.zxartRoot(0));
    }

    /**
     * Every one of zxart's nine words reaches a folder, and the three new ones
     * reach the right one.
     *
     * This is the both-directions rule applied to a second vocabulary: the
     * words come from the service and the folders are ours, and a word that
     * fell through to Other silently would be a fifth of an archive landing in
     * the wrong place.
     */
    @Test
    public void everyZxartRootLandsSomewhereDeliberate() {
        assertEquals(Kinds.GAMES, Kinds.folderFor("Games"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("System Software"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Applications"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Educational"));
        assertEquals(Kinds.COMPILATIONS, Kinds.folderFor("Compilation"));
        assertEquals(Kinds.MAGAZINES, Kinds.folderFor("Press"));
        assertEquals(Kinds.DEMOSCENE, Kinds.folderFor("Demoscene"));
        assertEquals(Kinds.OTHER, Kinds.folderFor("Misc"));
        assertEquals(Kinds.OTHER, Kinds.folderFor("Series"));
    }

    /** The two entities that are not programs at all. */
    @Test
    public void musicAndGraphicsHaveFoldersOfTheirOwn() {
        assertEquals(Kinds.MUSIC, Kinds.folderFor("Music"));
        assertEquals(Kinds.GRAPHICS, Kinds.folderFor("Graphics"));
    }

    /**
     * The new words do not steal ZXDB's.
     *
     * "Educational" reaches Applications and must not drag "Educational Game"
     * with it - zxart has such a category and ZXDB has the phrase - and
     * "Press" must not catch anything else. Asserted because folderFor
     * matches by contains and order, so a new row is a new chance to shadow
     * an old one.
     */
    @Test
    public void theNewWordsDoNotShadowTheOldOnes() {
        assertEquals(Kinds.GAMES, Kinds.folderFor("Educational Game"));
        assertEquals(Kinds.MAGAZINES, Kinds.folderFor("Electronic Magazine"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Utility"));
    }
```

Add `import static org.junit.Assert.assertNull;`.

`"Educational Game"` reaching `GAMES` is the constraint that fixes where the new words go: `Educational` must be tried **after** the `GAMES` row, or the more specific phrase loses. That inverts the usual "specific first" reading and is exactly why it is asserted.

- [ ] **Step 2: Run it and watch it fail**

Run: `... --tests 'dev.ldlab.zedex.library.catalogue.KindsTest'`
Expected: FAIL — `zxartRoot` undefined; and once defined, `folderFor("Educational Game")` returns `Applications` until the row order is right.

- [ ] **Step 3: Change `Kinds`**

Add the two folders beside the others:

```java
    public static final String MUSIC = "Music";
    public static final String GRAPHICS = "Graphics";
```

Add both to `ALL`. Then the table, with the two new rows placed **after** `GAMES` so a phrase like "Educational Game" is caught by the more specific row first:

```java
    private static final String[][] TABLE = {
        { COMPILATIONS, "compilation", "covertape", "box set" },
        { MAGAZINES, "electronic magazine", "e-book", "book", "press" },
        { DEMOSCENE, "demoscene", "tech demo", "animation" },
        { MUSIC, "music" },
        { GRAPHICS, "graphics" },
        { APPLICATIONS, "utility", "programming", "emulator", "replacement rom" },
        { GAMES, "arcade game", "adventure game", "puzzle game", "casual game",
                 "sport game", "strategy game", "game" },

        // After GAMES, and that ordering is asserted: zxart's "Educational"
        // root is an application, and ZXDB's "Educational Game" is a game. The
        // greedy "game" row above has to see the phrase first, which is the
        // opposite of the specific-first reading the rest of this table uses.
        { APPLICATIONS, "system software", "educational" },
    };
```

And the recorded roots:

```java
    /**
     * zxart's nine root categories, recorded from the live tree on 2026-08-14.
     *
     * <b>Ids, because a prod can be traced to one in any language.</b> zxart
     * answers in Russian, English or Spanish, and this app asks in the user's;
     * the words therefore differ between two people looking at the same
     * catalogue while the ids do not. {@code ZxartTree.rootOf} walks a prod's
     * leaf categories up to one of these, and the title here - zxart's own
     * canonical English word - becomes {@code Item.kind}, which
     * {@link #folderFor} then maps exactly as it maps ZXDB's genres.
     *
     * Recorded rather than looked up, for the reason every table in this file
     * is: a tenth root or a renumbered one should fail {@code KindsTest}
     * rather than silently file a fifth of an archive under {@link #OTHER}.
     */
    public static final String[][] ZXART_ROOTS = {
        { "92177", "Games" },
        { "92183", "System Software" },
        { "92188", "Misc" },
        { "92534", "Educational" },
        { "202588", "Compilation" },
        { "204819", "Demoscene" },
        { "244858", "Press" },
        { "244880", "Applications" },
        { "551860", "Series" },
    };

    /** zxart's own word for one of its nine roots, or null for anything else -
     *  a leaf, or a root added upstream. Null is what makes the caller file it
     *  under {@link #OTHER} rather than somewhere plausible. */
    public static String zxartRoot(int id) {
        String wanted = Integer.toString(id);

        for (String[] root : ZXART_ROOTS) {
            if (root[0].equals(wanted)) return root[1];
        }

        return null;
    }
```

- [ ] **Step 4: Run the whole JVM tier**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest`
Expected: PASS. `KindsTest`'s existing both-directions test over `ZXDB_VOCABULARY` must still pass — that is the check that the new rows stole nothing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/catalogue/Kinds.java \
        app/src/test/java/dev/ldlab/zedex/library/catalogue/KindsTest.java
git commit -m "feat: zxart's nine roots, and folders for music and graphics

Item.kind stays the catalogue's own word and for zxart becomes the
canonical English root title, derived from the category id. That keeps
one folder-mapping path rather than two, needs no second tree request in
another language, and leaves Imports untouched.

The two new table rows go after GAMES on purpose, and the test says so:
zxart's Educational root is an application and ZXDB's Educational Game
is a game, so the greedy game row has to see the phrase first. That is
the opposite of the specific-first ordering the rest of the table uses,
which is exactly the kind of thing a comment cannot enforce and an
assertion can."
```

---

### Task 6: `ZxartCatalogue` — shelves, paging, items

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/catalogue/ZxartCatalogue.java`
- Test: `app/src/test/java/dev/ldlab/zedex/library/catalogue/ZxartCatalogueTest.java`

**Interfaces:**
- Consumes: `ZxartApi`, `ZxartTree`, `Kinds.zxartRoot`, `Fixtures`.
- Produces: `new ZxartCatalogue(Http, Locale)`, and the `Catalogue` contract. Task 7 adds `knowsFormats()`, Task 8 `sorts()`, Task 9 registers it, Task 11 adds the music and graphics shelves.

Shelf ids, all package-visible constants so the tests name them rather than spelling strings: `SHELF_SEARCH = "search"`, `SHELF_CATEGORIES = "categories"`, `SHELF_EVERYTHING = "everything"`, and prefixes `CATEGORY_PREFIX = "category:"` (a sub-shelf carrying a category id) and `MORE_PREFIX = "more:"` (`similarTo`, carrying a leaf category id).

- [ ] **Step 1: Write the failing test**

```java
package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.ldlab.zedex.library.scrape.Pace;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Locale;

/**
 * zxart as somewhere to browse.
 *
 * On the JVM against captured replies, which is where a catalogue's decisions
 * live: which shelves exist, what a page asks for, what a row means, and what
 * gets dropped. Nothing here reaches the network - {@code Fixtures.Canned}
 * answers - and nothing here is Android.
 */
public class ZxartCatalogueTest {

    @Before
    public void forgetThePacing() {
        Pace.forget();
    }

    private static ZxartCatalogue catalogue(Fixtures.Canned http) {
        return new ZxartCatalogue(http, Locale.ENGLISH);
    }

    /**
     * Three shelves, and <b>no A-Z</b>.
     *
     * Every title-prefix filter zxart might have had is ignored -
     * zxProdTitleStart returned all 58,032 - so a letter picker cannot be
     * built, and a shelf that cannot be built is not declared. The want is
     * served by the alphabetical sort instead. This is the seam's whole
     * argument in one assertion.
     */
    @Test
    public void theShelvesAreTheOnesThatCanBeBuilt() {
        List<Catalogue.Shelf> shelves = catalogue(new Fixtures.Canned()).shelves();

        assertEquals(3, shelves.size());
        assertEquals(ZxartCatalogue.SHELF_SEARCH, shelves.get(0).id());
        assertTrue(shelves.get(0).accepts(Catalogue.Shelf.Accepts.TEXT));
        assertEquals(ZxartCatalogue.SHELF_CATEGORIES, shelves.get(1).id());
        assertEquals(ZxartCatalogue.SHELF_EVERYTHING, shelves.get(2).id());

        for (Catalogue.Shelf shelf : shelves) {
            assertFalse("no shelf takes a letter, because no filter accepts one",
                        shelf.accepts(Catalogue.Shelf.Accepts.LETTER));
        }
    }

    /** shelves() makes no request: the tab calls it on the UI thread while it
     *  is being built. */
    @Test
    public void decliningToAskAnythingToDeclareShelves() {
        Fixtures.Canned http = new Fixtures.Canned();
        catalogue(http).shelves();

        assertTrue(http.asked.isEmpty());
    }

    /** A search is one filter and one page, and start is a row offset. */
    @Test
    public void aSearchAsksForWhatWasTyped() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);

        catalogue(http).open(shelf(ZxartCatalogue.SHELF_SEARCH),
                             Catalogue.Query.text("head over heels"), 0);

        assertTrue(lastAsked(http).contains("export:zxProd"));
        assertTrue(lastAsked(http).contains("filter:zxProdSearch=head%20over%20heels"));
        assertTrue(lastAsked(http).contains("start:0"));
    }

    /**
     * Opening Categories yields shelves and no items; opening one of those
     * yields <b>both</b>.
     *
     * The first thing in this codebase to fill items and shelves in one page,
     * which is what the seam always claimed sub-shelves were for.
     */
    @Test
    public void categoriesYieldShelvesAndThenBoth() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE);
        ZxartCatalogue zxart = catalogue(http);

        Catalogue.Page roots = zxart.open(shelf(ZxartCatalogue.SHELF_CATEGORIES),
                                          Catalogue.Query.none(), 0);

        assertEquals(9, roots.shelves().size());
        assertTrue(roots.items().isEmpty());

        Catalogue.Page games = zxart.open(roots.shelves().get(0), Catalogue.Query.none(), 0);

        assertFalse("a root category has children to descend into",
                    games.shelves().isEmpty());
        assertFalse("and prods of its own, because roots roll up - Games is 23,162",
                    games.items().isEmpty());
    }

    /** The tree is fetched once and held: two shelves opened, one tree
     *  request. Thirteen kilobytes per session, not per shelf. */
    @Test
    public void theTreeIsAskedForOnce() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        ZxartCatalogue zxart = catalogue(http);

        zxart.open(shelf(ZxartCatalogue.SHELF_CATEGORIES), Catalogue.Query.none(), 0);
        zxart.open(shelf(ZxartCatalogue.SHELF_SEARCH), Catalogue.Query.text("head"), 0);

        int trees = 0;
        for (String url : http.asked) {
            if (url.contains("export:zxProdCategory")) trees++;
        }

        assertEquals(1, trees);
    }

    /** A row's kind is the English root word for the leaf it names, which is
     *  what decides its folder later. Licence to Kill is under Games. */
    @Test
    public void aRowsKindIsItsRootCategory() throws Exception {
        Catalogue.Item item = onlyItem();

        assertEquals("Games", item.kind());
        assertEquals(Kinds.GAMES, Kinds.folderFor(item.kind()));
    }

    /**
     * <b>An unknown legalStatus says nothing.</b>
     *
     * Measured over 1,000 prods: 975 "unknown", 21 "forbidden", 3
     * "unreleased", 1 "recovered", and the word "available" never once. Passed
     * through raw, CatalogueAdapter.greyed - stated and not available - would
     * grey every row in the catalogue and give no reason for any of them.
     */
    @Test
    public void unknownAvailabilityIsNotAStatement() throws Exception {
        assertNull(onlyItem().availability());
    }

    /** A stated one is kept verbatim, because it is the row's own reason. */
    @Test
    public void aStatedUnavailabilityIsKept() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_FORBIDDEN);
        Catalogue.Page page = catalogue(http).open(shelf(ZxartCatalogue.SHELF_EVERYTHING),
                                                  Catalogue.Query.none(), 0);

        assertEquals("forbidden", page.items().get(0).availability());
        assertFalse(page.items().get(0).available());
    }

    /** Titles arrive escaped and must not reach a row that way. */
    @Test
    public void titlesAreUnescaped() throws Exception {
        assertFalse(onlyItem().title().contains("&"));
    }

    /**
     * A prod row carries no files at all - only releasesIds - so the formats
     * are one request away per row and Item.formats() is honestly empty. Task
     * 7's knowsFormats is what stops the screen's filter rejecting the whole
     * catalogue on the strength of it.
     */
    @Test
    public void aListRowKnowsNoFormats() throws Exception {
        assertTrue(onlyItem().formats().isEmpty());
    }

    /** item() is two requests - the prod, then every release of it in one
     *  call - because types:zxProd,zxRelease answers HTTP 500. */
    @Test
    public void oneItemIsTwoRequests() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.RELEASES_LICENCE_TO_KILL);

        Catalogue.Item item = catalogue(http).item("92668");

        assertNotNull(item);
        assertFalse(item.versions().isEmpty());

        Catalogue.Download file = item.versions().get(0).files().get(0);
        assertEquals("tzx", file.format());
        assertTrue(file.url().startsWith("https://zxart.ee/releasefile/"));
        assertEquals(41330, file.size());
    }

    /** A version's label tells two releases apart by what they need, which is
     *  what hardwareRequired is for on this screen. */
    @Test
    public void aVersionSaysWhatItNeeds() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.RELEASES_LICENCE_TO_KILL);

        assertTrue(catalogue(http).item("92668").versions().get(0).label()
                   .toLowerCase(Locale.ROOT).contains("zx128"));
    }

    /** similarTo is a way in and costs nothing until opened - the pane calls
     *  it while laying out, on the UI thread. */
    @Test
    public void similarToMakesNoRequest() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL);
        ZxartCatalogue zxart = catalogue(http);
        Catalogue.Item item = zxart.open(shelf(ZxartCatalogue.SHELF_EVERYTHING),
                                         Catalogue.Query.none(), 0).items().get(0);

        int before = http.asked.size();
        Catalogue.Shelf like = zxart.similarTo(item, "Games like this one");

        assertNotNull(like);
        assertEquals("Games like this one", like.label());
        assertEquals(before, http.asked.size());
    }

    /** The total is the service's own and is real, so a shelf can print it -
     *  unlike ZXInfo's, which caps at 10,000. */
    @Test
    public void theTotalIsWhatTheServiceSaid() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        Catalogue.Page page = catalogue(http).open(shelf(ZxartCatalogue.SHELF_SEARCH),
                                                  Catalogue.Query.text("head over heels"), 0);

        assertEquals(6, page.total());
    }

    // --- helpers -----------------------------------------------------------------------

    private Catalogue.Item onlyItem() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_LICENCE_TO_KILL);

        return catalogue(http).open(shelf(ZxartCatalogue.SHELF_EVERYTHING),
                                    Catalogue.Query.none(), 0).items().get(0);
    }

    private static Catalogue.Shelf shelf(String id) {
        return new Catalogue.Shelf(id, id, Catalogue.Shelf.Accepts.NOTHING);
    }

    private static String lastAsked(Fixtures.Canned http) {
        return http.asked.get(http.asked.size() - 1);
    }
}
```

The search shelf in `theShelvesAreTheOnesThatCanBeBuilt` needs `Accepts.TEXT`, so `shelf(...)` is only used where the accepts value does not matter; for the search case the test asks the catalogue for its own shelves and passes `shelves().get(0)`. Adjust `aSearchAsksForWhatWasTyped` and `theTreeIsAskedForOnce` to use `catalogue.shelves().get(0)` rather than the helper.

- [ ] **Step 2: Run it and watch it fail**

Expected: compilation failure — `ZxartCatalogue` does not exist.

- [ ] **Step 3: Write `ZxartCatalogue`**

The class in outline, with the parts that carry a decision written out:

```java
public final class ZxartCatalogue implements Catalogue {

    static final String SHELF_SEARCH = "search";
    static final String SHELF_CATEGORIES = "categories";
    static final String SHELF_EVERYTHING = "everything";
    static final String CATEGORY_PREFIX = "category:";
    static final String MORE_PREFIX = "more:";

    /** What a page of the grid asks for. Thirty, like ZXInfo's, because a
     *  screenful is a screenful whichever archive it came from. */
    private static final int PAGE_SIZE = 30;

    private final ZxartApi api;
    private final String language;

    /** Fetched once and held: it is the Categories shelf and it is every
     *  row's folder. Thirteen kilobytes a session. */
    private ZxartTree tree;

    public ZxartCatalogue(Http http, Locale locale) {
        this.api = new ZxartApi(http);
        this.language = ZxartApi.language(locale);
    }

    @Override
    public String name() {
        return "zxart";
    }

    @Override
    public boolean configured() {
        return true;                  // no credentials to be missing
    }

    @Override
    public List<Shelf> shelves() {
        return Arrays.asList(
                new Shelf(SHELF_SEARCH, "Search", Shelf.Accepts.TEXT),
                new Shelf(SHELF_CATEGORIES, "Categories", Shelf.Accepts.NOTHING),
                new Shelf(SHELF_EVERYTHING, "Everything", Shelf.Accepts.NOTHING));
    }

    @Override
    public Page open(Shelf shelf, Query query, int page) throws ScrapeException {
        if (SHELF_CATEGORIES.equals(shelf.id())) return categories();

        ZxartApi.Ask ask = new ZxartApi.Ask(ZxartApi.PROD)
                .language(language)
                .page(page, PAGE_SIZE);

        if (SHELF_SEARCH.equals(shelf.id())) {
            ask.filter(ZxartApi.FILTER_SEARCH, query.text());
        } else if (shelf.id().startsWith(CATEGORY_PREFIX)) {
            ask.filter(ZxartApi.FILTER_CATEGORY, idIn(shelf.id(), CATEGORY_PREFIX));
        } else if (shelf.id().startsWith(MORE_PREFIX)) {
            ask.filter(ZxartApi.FILTER_CATEGORY, idIn(shelf.id(), MORE_PREFIX));
        }

        JSONObject reply = api.ask(ask);
        List<Item> items = new ArrayList<>();

        for (JSONObject row : ZxartApi.rows(reply, ZxartApi.PROD)) items.add(itemFrom(row));

        // A category shelf carries its children as well as its prods: roots
        // roll up, so opening Games is ten sub-shelves and 23,162 items.
        List<Shelf> below = shelf.id().startsWith(CATEGORY_PREFIX) && page == 0
                ? childrenOf(Integer.parseInt(idIn(shelf.id(), CATEGORY_PREFIX)))
                : null;

        return new Page(items, below, page * PAGE_SIZE, ZxartApi.totalOf(reply));
    }
```

`categories()` fetches the tree if absent and turns `tree.roots()` into `CATEGORY_PREFIX + id` shelves labelled with the node's own title; `childrenOf(int)` does the same for a node's children. `itemFrom(JSONObject)` is where the decisions are:

```java
    /**
     * One prod as a row.
     *
     * <b>No files.</b> A prod names releasesIds and nothing else, so
     * Item.formats() is empty and {@link #knowsFormats()} says so - see Task
     * 7. Resolving them here would be one request per row.
     */
    private Item itemFrom(JSONObject row) throws ScrapeException {
        String id = Integer.toString(row.optInt("id", 0));
        int year = row.optInt("year", 0);

        return new Item(id,
                        ZxartApi.unescape(row.optString("title", "")),
                        year > 0 ? Integer.toString(year) : null,
                        null,                       // no publisher: see below
                        kindOf(row),
                        availabilityOf(row),
                        pictureOf(row),
                        Collections.<Version>emptyList());
    }

    /**
     * No publisher, ever, and that is measured rather than lazy.
     *
     * A prod carries publishersIds and nothing that resolves them: {@code
     * export:publisher} answers HTTP 500, {@code export:group} parses and
     * answers nothing for a publisher id, and {@code export:author} - which
     * does work for authors - has no row for one. Comma-joining ids returns
     * one row, so there is no batch either. Item.describe() skips whichever of
     * year and publisher is unknown, so a zxart row reads "Head over Heels
     * (1987)" and nothing is left dangling.
     */

    /** The English root word for whichever leaf this prod names - which is
     *  what Kinds.folderFor maps, and the reason the walk is by id. */
    private String kindOf(JSONObject row) throws ScrapeException {
        JSONArray leaves = row.optJSONArray("connectedCategoriesIds");

        for (int at = 0; leaves != null && at < leaves.length(); at++) {
            String word = Kinds.zxartRoot(tree().rootOf(leaves.optInt(at, 0)));
            if (word != null) return word;
        }

        return null;                  // Kinds.folderFor(null) is OTHER
    }

    /**
     * What the service <em>stated</em>, or null.
     *
     * "unknown" is not a statement and must not become one: 975 of 1,000
     * measured rows say it, the word "available" never appears at all, and
     * CatalogueAdapter.greyed greys anything stated that is not available. Left
     * raw, this catalogue would draw every row greyed with no reason given.
     */
    private static String availabilityOf(JSONObject row) {
        String stated = row.optString("legalStatus", "");

        return stated.isEmpty() || "unknown".equals(stated) ? null : stated;
    }
```

`pictureOf` takes the first `imagesUrls` entry — the rendered loading screen, present on 100% of the measured slice. `item(String)` asks for the prod, then `export:zxRelease` filtered by `FILTER_PROD_ID`, and builds one `Version` per release: label from `releaseType` plus `hardwareRequired` joined with spaces, year from `year`, and one `Download` per release from `file`, `releaseFormat[0]` lower-cased, and the size out of `releaseStructure[0].size`. `similarTo` returns `new Shelf(MORE_PREFIX + firstLeafId, label, Accepts.NOTHING)` and makes no request; null when the prod names no category. `refusalFor` delegates to `api.refusalFor`.

- [ ] **Step 4: Run and watch it pass**

Run: `... --tests 'dev.ldlab.zedex.library.catalogue.ZxartCatalogueTest'`
Expected: PASS, 14 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/catalogue/ZxartCatalogue.java \
        app/src/test/java/dev/ldlab/zedex/library/catalogue/ZxartCatalogueTest.java
git commit -m "feat: zxart as somewhere to browse

Three shelves, because three is what the service can actually build: no
A-Z, since every title-prefix filter is ignored rather than refused, and
the want it served is now the alphabetical sort. A shelf that cannot be
built is not declared, which is the whole argument for shelves being
data.

Categories fills a page's items and shelves at once - the first thing
here to do it - because zxart's root categories roll up: opening Games is
ten sub-shelves and 23,162 prods.

Two things this deliberately does not answer with. A row has no formats,
because a prod names releasesIds and no files; and an 'unknown'
legalStatus becomes no availability at all, because 975 of 1,000 rows say
it, the word 'available' never appears, and greying by what the service
stated would have drawn every row in the catalogue greyed with no reason
given."
```

---

### Task 7: `knowsFormats()` — the filter that must not silently reject a whole archive

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/catalogue/Catalogue.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/library/catalogue/ZxInfoCatalogue.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/library/catalogue/ZxartCatalogue.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/library/ui/CatalogueView.java`
- Test: `app/src/test/java/dev/ldlab/zedex/library/catalogue/CatalogueTest.java`, `.../ZxartCatalogueTest.java`

**Interfaces:**
- Produces: `Catalogue.knowsFormats()`, defaulted `false`; `ZxInfoCatalogue` overrides `true`.

- [ ] **Step 1: Write the failing tests**

In `CatalogueTest`:

```java
    /**
     * A catalogue owes no answer about formats, and the default is the honest
     * one.
     *
     * ZXInfo's list rows carry their own files, which is what makes its format
     * filter cost no request. A zxart prod names releasesIds and nothing else,
     * so its rows know nothing - and a screen that read "no formats" as "no
     * match" would reject every row in the archive, while one that read it as
     * "keep everything" would show a filter that does nothing. Neither is
     * acceptable, so the catalogue says which it is and the screen hides the
     * control it cannot honour.
     */
    @Test
    public void aCatalogueSaysWhetherItCanAnswerForFormats() {
        Catalogue quiet = new Catalogue() {
            // the four abstract methods, answering nothing of interest
        };

        assertFalse(quiet.knowsFormats());
    }
```

In `ZxartCatalogueTest`:

```java
    @Test
    public void zxartCannotAnswerForFormats() {
        assertFalse(catalogue(new Fixtures.Canned()).knowsFormats());
    }

    /** And so it is never asked to sift: a bigger page for a filter that is
     *  not applied is bytes nobody wanted. */
    @Test
    public void aZxartQueryIsNeverSifting() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        catalogue(http).open(catalogue(http).shelves().get(0),
                             Catalogue.Query.text("head").sifting(), 0);

        assertTrue("a sifting hint changes nothing here, and must not silently"
                   + " triple the page size", lastAsked(http).contains("limit:30"));
    }
```

- [ ] **Step 2: Run and watch them fail**

Expected: FAIL — `knowsFormats` undefined.

- [ ] **Step 3: Add the predicate and hide the row**

In `Catalogue`, beside `similarTo`:

```java
    /**
     * Whether this catalogue's <em>list rows</em> know which formats an item
     * comes in.
     *
     * ZXInfo's do: a search hit's {@code _source} carries its releases and
     * their files, byte-identical to the record's, which is what lets the
     * screen filter by format without a request per row. zxart's do not - a
     * prod names its release ids and nothing else - so {@link Item#formats()}
     * is legitimately empty there.
     *
     * <b>Empty formats cannot be read as either answer.</b> Treated as "no
     * match" the filter rejects an entire archive; treated as "keep
     * everything" it appears to work and does nothing, which is the same fault
     * as a chooser that changes nothing. So the catalogue states it and {@code
     * CatalogueView} hides the control it cannot honour, exactly as the tab
     * hides itself when nothing is browsable.
     *
     * Default false: a catalogue that has not thought about it does not
     * promise, and the screen loses a filter rather than showing a broken one.
     */
    default boolean knowsFormats() {
        return false;
    }
```

`ZxInfoCatalogue` overrides it `true`, with a one-line comment pointing at `itemFrom` — the rows carry their files.

In `CatalogueView`, gate both the row and the hint:

```java
        // Hidden rather than disabled: a catalogue whose rows do not know
        // their formats cannot honour this, and a control that is present and
        // does nothing is worse than one that is absent.
        formatRow.setVisibility(catalogue.knowsFormats() ? View.VISIBLE : View.GONE);
```

placed after `showFormat()` in the constructor and repeated in `setCatalogue()` (Task 9). In `queryFor`, guard the hint:

```java
        return format == null || !catalogue.knowsFormats() ? query : query.sifting();
```

and in `setCatalogue` reset `format = null`, so a filter set on ZXInfo does not survive a switch to a catalogue that cannot apply it.

- [ ] **Step 4: Run the JVM tier**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -u && git commit -m "feat: a catalogue says whether it can filter by format

ZXInfo's list rows carry their own files; a zxart prod names release ids
and nothing else, so its rows honestly know no formats. Read as 'no
match' that rejects a whole archive; read as 'keep everything' it shows a
filter that changes nothing. So the catalogue states it, the screen hides
the control it cannot honour, and the sifting hint - which only exists to
buy bigger pages for a filter that drops most of them - is never set."
```

---

### Task 8: The sort control

**Files:**
- Modify: `Catalogue.java`, `ZxartCatalogue.java`, `ZxInfoCatalogue.java`, `CatalogueView.java`
- Modify: `app/src/main/res/values/strings.xml` and the eight translations
- Test: `app/src/test/java/dev/ldlab/zedex/library/catalogue/ZxartCatalogueTest.java`
- Test: `app/src/androidTest/java/dev/ldlab/zedex/library/ui/CatalogueSortTest.java`

**Interfaces:**
- Produces: `Catalogue.Sort` (`DEFAULT`, `TOP`, `NEWEST`, `ALPHABETICAL`), `Catalogue.sorts()` defaulted to `[DEFAULT]`, `Query.sortedBy(Sort)`, `Query.sort()`.

- [ ] **Step 1: Write the failing JVM tests**

```java
    /** Four sorts, and every one of them measured. rating,desc and
     *  votesAmount,desc are ignored by the service; votes,desc is what works,
     *  on prods, music and pictures alike. */
    @Test
    public void zxartOffersTheSortsItCanHonour() {
        List<Catalogue.Sort> sorts = catalogue(new Fixtures.Canned()).sorts();

        assertEquals(Arrays.asList(Catalogue.Sort.DEFAULT, Catalogue.Sort.TOP,
                                   Catalogue.Sort.NEWEST, Catalogue.Sort.ALPHABETICAL),
                     sorts);
    }

    @Test
    public void aSortIsAnOrderSegment() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        ZxartCatalogue zxart = catalogue(http);

        zxart.open(zxart.shelves().get(2),
                   Catalogue.Query.none().sortedBy(Catalogue.Sort.TOP), 0);

        assertTrue(lastAsked(http).contains("order:votes,desc"));
    }

    /** The sort applies inside a shelf, which is the whole point of its being
     *  a control rather than a shelf of its own: Top inside Games, not Top
     *  instead of Games. */
    @Test
    public void aSortAppliesInsideACategory() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        ZxartCatalogue zxart = catalogue(http);
        Catalogue.Page roots = zxart.open(zxart.shelves().get(1), Catalogue.Query.none(), 0);

        zxart.open(roots.shelves().get(0),
                   Catalogue.Query.none().sortedBy(Catalogue.Sort.TOP), 0);

        assertTrue(lastAsked(http).contains("filter:zxProdCategory=92177"));
        assertTrue(lastAsked(http).contains("order:votes,desc"));
    }

    /** The default asks for no order at all, rather than for the service's
     *  default spelled out - one less name to be wrong about. */
    @Test
    public void theDefaultSortAsksForNothing() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.CATEGORY_TREE)
                                                    .then(Fixtures.PROD_SEARCH);
        ZxartCatalogue zxart = catalogue(http);
        zxart.open(zxart.shelves().get(2), Catalogue.Query.none(), 0);

        assertFalse(lastAsked(http).contains("order:"));
    }

    /** A query carries its sort through the copies sifting() makes, or a
     *  filtered shelf would silently lose it. */
    @Test
    public void aSortSurvivesTheSiftingCopy() {
        Catalogue.Query query = Catalogue.Query.text("head")
                .sortedBy(Catalogue.Sort.NEWEST).sifting();

        assertEquals(Catalogue.Sort.NEWEST, query.sort());
        assertTrue(query.isSifting());
    }
```

- [ ] **Step 2: Run and watch them fail**

Expected: FAIL — `Sort`, `sorts`, `sortedBy` undefined.

- [ ] **Step 3: Add the sort to the seam and the view**

In `Catalogue`:

```java
    /**
     * A way of ordering a shelf, as opposed to a way in.
     *
     * <b>A fixed vocabulary, and translated on the app's side.</b> Everywhere
     * else a shelf's words are the service's own - a genre's name comes off the
     * wire - but there is nothing off the wire to call an ordering, and what a
     * person reads here is a sentence in their own language. So this is an enum
     * with string resources against it rather than labels from a catalogue,
     * which is also what lets two catalogues offer "Top rated" and mean it.
     */
    enum Sort { DEFAULT, TOP, NEWEST, ALPHABETICAL }

    /**
     * The orderings this catalogue can honour, best-known first, always
     * including {@link Sort#DEFAULT}.
     *
     * The same bargain as {@link #shelves()}: a catalogue owes nothing it has
     * not got, and the screen hides the control when there is only one. zxart
     * declares four, all measured - {@code order:votes,desc} works while
     * {@code order:rating,desc} is ignored, which is exactly the kind of thing
     * that must be measured before it is offered.
     */
    default List<Sort> sorts() {
        return Collections.singletonList(Sort.DEFAULT);
    }
```

`Query` gains a `sort` field defaulting to `DEFAULT`, threaded through both constructors and both copies (`sifting()` must carry it — that is what the last test pins), plus `sortedBy(Sort)` and `sort()`.

`ZxartCatalogue.sorts()` returns the four; `open` maps them:

```java
    /** Measured 2026-08-14: votes is the average rating and the only name the
     *  order answers to - `rating` and `votesAmount` are both ignored, on all
     *  three entities. */
    private static String orderFor(Query query) {
        switch (query == null ? Sort.DEFAULT : query.sort()) {
            case TOP:          return ZxartApi.ORDER_TOP;
            case NEWEST:       return ZxartApi.ORDER_NEWEST;
            case ALPHABETICAL: return ZxartApi.ORDER_TITLE;
            default:           return null;
        }
    }
```

`ZxInfoCatalogue` declares **`DEFAULT` and `TOP`**, measured in Task 1: `sort=score_desc` is honoured — same query, same index snapshot, single shard, same top hit, and the tied group at score 77.102264 reorders, which a shard artefact cannot explain. `TOP` maps to `sort=score_desc` there, which is relevance rather than a community rating; that is what "top" means on a service with no votes, and the label is the same word either way. `NEWEST` stays ZXInfo's own shelf (`sort=date_desc`, already shipped and relied on) rather than becoming a sort, and `ALPHABETICAL` is **not** declared for ZXInfo: nobody has measured a title sort there, and this plan does not guess.

In `CatalogueView`: a `sortRow` `TextView` built exactly like `formatRow` (same padding, colour, size, click), `private Catalogue.Sort sort = Catalogue.Sort.DEFAULT;`, `chooseSort()` offering `catalogue.sorts()` by their string resources, `setSort()` assigning and calling `restart()`, `showSort()` writing "Sort · Top rated", visibility `catalogue.sorts().size() > 1 ? VISIBLE : GONE`, and `queryFor` finishing with `.sortedBy(sort)`. `setCatalogue` resets `sort` to `DEFAULT` — a sort one catalogue honours may be one the next cannot.

- [ ] **Step 4: Add the five strings, in nine files**

`values/strings.xml`:

```xml
    <!-- The catalogue's ordering control. A fixed vocabulary rather than the
         service's own words: there is nothing off the wire to call an
         ordering, and a person reads these in their own language. -->
    <string name="library_sort">Sort</string>
    <string name="library_sort_default">Default</string>
    <string name="library_sort_top">Top rated</string>
    <string name="library_sort_newest">Newest</string>
    <string name="library_sort_alphabetical">A–Z</string>
```

Translate all five into `values-cs,-de,-es,-fr,-it,-pl,-ru,-uk`. Then:

Run: `scripts/check-strings.py`
Expected: 9 languages, 0 missing, no format-specifier disagreements.

- [ ] **Step 5: Write the device test**

`CatalogueSortTest` — with ZXInfo selected the sort row is absent (one sort declared, unless Task 1 changed that); with zxart selected it reads "Sort · Default", tapping it offers four, choosing *Top rated* reloads the shelf, and the first row differs from the first row before the change. Follow `FilterTest`'s discipline: **wait for a condition, never for a duration** — poll the first row's text until it changes or a generous deadline passes, and let the caller's own assertion report the failure. Set the world the test needs (`Emulator`-style helpers: the display, the catalogue preference) rather than inheriting the bench's.

- [ ] **Step 6: Run both tiers**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.ldlab.zedex.library.ui.CatalogueSortTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: PASS both. Read the test count against the number of `@Test` methods — `OK (n tests)` prints identically for a real pass and for every test skipping on an `assumeTrue`.

- [ ] **Step 7: Commit**

```bash
git add -u && git add app/src/androidTest/java/dev/ldlab/zedex/library/ui/CatalogueSortTest.java
git commit -m "feat: sort a catalogue shelf, including by rating

A control beside Format rather than a shelf of its own, because what was
asked for is Top inside Games rather than Top instead of Games. Four
orderings, declared by the catalogue and hidden by the screen when only
one exists - the same bargain shelves already make.

votes,desc is measured: it works on prods, music and pictures, while
rating,desc and votesAmount,desc are both ignored, which is what an
unrecognised zxart parameter always looks like. title,asc works too,
which is how this archive gets an alphabetical browse at all - every
title-prefix filter is ignored, so the A-Z shelf that could not be built
comes back as a sort.

Two honest limits, in the code and the docs: equal ratings can shuffle
between pages, and votes is a bare average, so zxProdMinRating is the
lever if the service's own weighting turns out thinner than the 24-35
votes its top rows carry."
```

---

### Task 9: Choosing the source

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/catalogue/Catalogues.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/library/ui/CatalogueView.java`
- Modify: `app/src/main/res/values/strings.xml` (+8)
- Test: `app/src/androidTest/java/dev/ldlab/zedex/library/ui/CatalogueSourceTest.java`

**Interfaces:**
- Produces: `CatalogueView.setCatalogue(Catalogue)`; `Catalogues.all` returning both.

- [ ] **Step 1: Register zxart**

In `Catalogues.all`, after ZXInfo:

```java
        Catalogue zxart = new ZxartCatalogue(new Http.Real(context),
                                             context.getResources().getConfiguration()
                                                    .getLocales().get(0));
        if (zxart.configured()) catalogues.add(zxart);
```

The locale comes from the **context**, not the device, because every activity's `attachBaseContext` has already applied the app's own language preference — the app has one mechanism for language and this must not become a second.

- [ ] **Step 2: Write the device test first**

`CatalogueSourceTest`: the catalogue tab shows a source row reading `Catalogue · ZXInfo`; tapping it offers both names; choosing zxart redraws the roots as *Search / Categories / Everything*; the row then reads `Catalogue · zxart`; leaving the library and coming back keeps zxart. That last one is the preference actually being written, which is the part with no other witness. Restore the preference in `@After` — it is the user's device.

- [ ] **Step 3: Add the row**

`sourceRow`, built like `formatRow`, above it, with `library_catalogue` ("Catalogue"). `chooseSource()` lists `Catalogues.all(getContext())` by `name()`, writes `Prefs.KEY_CATALOGUE`, and calls `setCatalogue(chosen)`:

```java
    /**
     * Browse a different archive.
     *
     * Everything about the current one goes: in-flight requests are
     * superseded, the pane is closed, the stack is emptied, and the format and
     * sort go back to nothing - a filter one catalogue can apply may be one
     * the next cannot even offer, and a sort it honours may be one the next
     * ignores. Rebuilding the view instead would work and would cost the tab
     * its scroll position and the activity a reference it hands to three other
     * things.
     */
    public void setCatalogue(Catalogue chosen) {
        if (chosen == null || chosen == catalogue) return;

        catalogue = chosen;
        format = null;
        sort = Catalogue.Sort.DEFAULT;

        formatRow.setVisibility(catalogue.knowsFormats() ? View.VISIBLE : View.GONE);
        sortRow.setVisibility(catalogue.sorts().size() > 1 ? View.VISIBLE : View.GONE);
        showFormat();
        showSort();
        showSource();

        showRoots();
    }
```

`catalogue` stops being `final`. Check every use of it in the file compiles unchanged (it is read in `showRoots`, `open`, `fetch`, `labelOf`, `showPane`).

- [ ] **Step 4: One string, nine files, and the checks**

```xml
    <string name="library_catalogue">Catalogue</string>
```

Run: `scripts/check-strings.py && scripts/check-prefs.py`

`check-prefs` matters here: `KEY_CATALOGUE` is written with `putString` and read with `getString`, and a mismatched type throws only on a device where the setting has been touched.

- [ ] **Step 5: Run both tiers, commit**

```bash
git add -u && git add app/src/androidTest/java/dev/ldlab/zedex/library/ui/CatalogueSourceTest.java
git commit -m "feat: choose which catalogue the library browses

A row in the tab, the idiom this view already uses for Format, writing
the preference Catalogues.preferred has read since the day it was written
with nothing writing it.

Switching resets the format and the sort rather than carrying them: a
filter one archive can apply may be one the next cannot offer, and a
sort it honours may be one the next ignores. The locale zxart is asked in
comes from the context rather than the device, because attachBaseContext
has already applied the app's own language preference and one mechanism
is enough."
```

---

## Part 2 — music and graphics

### Task 10: Two folders, a picture that is a `.scr`, and a mime nobody declared

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/catalogue/Pick.java`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/dev/ldlab/zedex/library/catalogue/PickTest.java`

- [ ] **Step 1: Write the failing test**

```java
    /**
     * A {@code .scr} is a picture, and until it was in the list it was not.
     *
     * zxart's graphics entries hold the rendered PNG and the original screen
     * dump. Pick.otherFile answers with the first file that is neither a
     * picture nor for the machine - so a .scr, being in neither list, won,
     * and Open handed a Spectrum screen dump to a phone with nothing that can
     * read one. A .scr *is* a picture, this app renders them already, and with
     * it listed the PNG wins by being listed first.
     */
    @Test
    public void aScreenDumpIsAPicture() {
        Catalogue.Item picture = itemWith(download("https://zxart.ee/zximages/id=2232", "png"),
                                         download("https://zxart.ee/file/id:2232/x.scr", "scr"));

        assertEquals("png", Pick.otherFile(picture).format());
    }

    /** And an entry whose only file is a screen dump still answers with it,
     *  the same way an advertisement or a photographed cassette does. */
    @Test
    public void aScreenDumpAloneIsStillTheAnswer() {
        Catalogue.Item dump = itemWith(download("https://zxart.ee/file/id:2232/x.scr", "scr"));

        assertEquals("scr", Pick.otherFile(dump).format());
    }
```

Reuse `PickTest`'s existing `itemWith`/`download` helpers; check their names in the file before writing.

- [ ] **Step 2: Run and watch it fail**

Expected: FAIL — `otherFile` answers `scr` for the first case.

- [ ] **Step 3: Add `scr` and the mime**

In `Pick`:

```java
    /** What a scan or a cover arrives as, and what a book is not.
     *
     *  {@code scr} is here because a Spectrum screen dump is a picture: zxart's
     *  graphics entries carry the rendered PNG and the original .scr, and
     *  without this the .scr counted as "not a picture", won, and was handed to
     *  a phone that has nothing to open one with. */
    private static final String[] PICTURES = { "jpg", "jpeg", "png", "gif", "scr" };
```

In the manifest's `<queries>` block, beside the PDF and image entries:

```xml
        <!-- A tune imported from a catalogue is handed to whatever plays audio.
             Android 11+ hides every handler for an undeclared mime, so without
             this the hand-over resolves to nothing on a phone with three music
             players - the same fault ACTION_SENDTO had with mail. -->
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:mimeType="audio/*" />
        </intent>
```

- [ ] **Step 4: Run the JVM tier and commit**

```bash
git add -u && git commit -m "fix: a Spectrum screen dump is a picture

zxart's graphics entries carry a rendered PNG and the original .scr.
Pick.otherFile answers with the first file that is neither a picture nor
for the machine, so the .scr - in neither list - won, and Open handed a
screen dump to a phone with nothing to read it. It is a picture, this app
renders them already, and with it listed the PNG wins by position while a
dump-only entry still answers with the dump.

audio/* joins <queries> for the same reason application/pdf did."
```

### Task 11: The music and graphics shelves

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/catalogue/ZxartCatalogue.java`
- Test: `app/src/test/java/dev/ldlab/zedex/library/catalogue/ZxartCatalogueTest.java`

**Interfaces:**
- Produces: shelf ids `SHELF_MUSIC = "music"`, `SHELF_GRAPHICS = "graphics"`, and sub-shelf prefixes `MUSIC_PREFIX`, `GRAPHICS_PREFIX`.

- [ ] **Step 1: Write the failing tests**

```java
    /** Five roots now, and the two new ones are ways in rather than screens:
     *  opening one yields its own sub-shelves, the mechanism Categories uses. */
    @Test
    public void musicAndGraphicsAreShelvesAtTheRoot() {
        List<Catalogue.Shelf> shelves = catalogue(new Fixtures.Canned()).shelves();

        assertEquals(5, shelves.size());
        assertEquals(ZxartCatalogue.SHELF_MUSIC, shelves.get(3).id());
        assertEquals(ZxartCatalogue.SHELF_GRAPHICS, shelves.get(4).id());
    }

    @Test
    public void openingMusicYieldsSubShelvesAndNoRequest() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned();
        Catalogue.Page page = catalogue(http).open(
                new Catalogue.Shelf(ZxartCatalogue.SHELF_MUSIC, "Music",
                                    Catalogue.Shelf.Accepts.NOTHING),
                Catalogue.Query.none(), 0);

        assertFalse(page.shelves().isEmpty());
        assertTrue(page.items().isEmpty());
        assertTrue(http.asked.isEmpty());
    }

    /**
     * A tune is one version and two files, the playable one first.
     *
     * The ogg is what any phone can play and the PT3 is the original worth
     * keeping. Order is load-bearing: Pick.otherFile answers with the first
     * file that is neither picture nor program, and Open hands that to the
     * phone.
     */
    @Test
    public void aTuneIsTheOggThenTheOriginal() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.MUSIC_ROW);
        Catalogue.Page page = catalogue(http).open(musicSubShelf(), Catalogue.Query.none(), 0);
        Catalogue.Item tune = page.items().get(0);

        assertEquals("Music", tune.kind());
        assertEquals(Kinds.MUSIC, Kinds.folderFor(tune.kind()));

        List<Catalogue.Download> files = tune.versions().get(0).files();
        assertEquals("ogg", files.get(0).format());
        assertEquals("mt3", files.get(1).format());
        assertEquals("ogg", Pick.otherFile(tune).format());
    }

    /** A picture is the rendered PNG then the screen dump, for the same
     *  reason and with the same consequence. */
    @Test
    public void aPictureIsThePngThenTheDump() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PICTURE_ROW);
        Catalogue.Item art = catalogue(http)
                .open(graphicsSubShelf(), Catalogue.Query.none(), 0).items().get(0);

        assertEquals("Graphics", art.kind());
        assertEquals(Kinds.GRAPHICS, Kinds.folderFor(art.kind()));
        assertEquals("png", art.versions().get(0).files().get(0).format());
        assertEquals("scr", art.versions().get(0).files().get(1).format());
        assertEquals("png", Pick.otherFile(art).format());
    }

    /** Top rated is the sort these two exist for, and it is the same order
     *  name: measured, the reply calls the field rating and the order answers
     *  only to votes. */
    @Test
    public void musicSortsByTheSameOrderNameAsProds() throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.MUSIC_ROW);
        catalogue(http).open(musicSubShelf(),
                             Catalogue.Query.none().sortedBy(Catalogue.Sort.TOP), 0);

        assertTrue(lastAsked(http).contains("export:zxMusic"));
        assertTrue(lastAsked(http).contains("order:votes,desc"));
    }
```

The two helpers build the sub-shelves the same way `open` yields them, e.g. `new Catalogue.Shelf(ZxartCatalogue.MUSIC_PREFIX + "everything", "Everything", Accepts.NOTHING)` — take the exact ids from the implementation as written.

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Extend `ZxartCatalogue`**

`shelves()` gains the two; `open` answers `SHELF_MUSIC`/`SHELF_GRAPHICS` with sub-shelves and no request: **Everything and Search, both**. Task 1 measured `filter:zxMusicSearch` and `filter:zxPictureSearch` as real — 10 rows of 29,672 for `beyond`, 124 of 19,408 for `girl`, every visible title carrying the term, against a control whose signature is the *exact* unfiltered total. Add `FILTER_MUSIC_SEARCH = "zxMusicSearch"` and `FILTER_PICTURE_SEARCH = "zxPictureSearch"` to `ZxartApi` with the same measured-on comment as the others. Note the spec guessed these would be ignored and was wrong; do not carry that assumption into the code; a `MUSIC_PREFIX`/`GRAPHICS_PREFIX` shelf asks `export:zxMusic`/`export:zxPicture` and builds items:

```java
    /**
     * A tune as an item.
     *
     * kind is "Music" - the entity's own word, which is what Kinds maps - and
     * the version carries the ogg first and the original second. That order is
     * not cosmetic: Pick.otherFile answers with the first file that is neither
     * a picture nor for the machine, and that is what the pane's Open hands to
     * the phone. The ogg is playable anywhere; a .pt3 is not.
     *
     * No size is stated for either, so Download carries -1 - which is honest
     * and is what a catalogue that does not say looks like.
     */
    private static Item musicFrom(JSONObject row) {
        ...
    }
```

`item(String)` answers a music or picture id from the row it already has plus one `export:author` call for the author name (which *is* resolvable here, unlike a prod's publisher), and `pictureUrl` is `imageUrl` for a picture and null for a tune.

- [ ] **Step 4: Run and pass, then commit**

```bash
git add -u && git commit -m "feat: zxart's music and graphics, on the same catalogue

Two shelves at the root, each yielding its own sub-shelves, so 29,672
tunes and 19,408 pictures arrive without a screen or a chooser of their
own. Imported through the path books and magazines already take -
folderFor plus WHATEVER_ARRIVED - because the machine cannot open any of
them and that is not a reason to refuse to fetch one.

Each item lists the playable file first and the original second, and that
order is load-bearing: Pick.otherFile answers with the first file that is
neither picture nor program, and the pane's Open hands exactly that to
the phone.

The author's name is resolvable here where a prod's publisher is not -
export:author answers, export:publisher is a 500 - so it is fetched for
the pane and never per row."
```

### Task 12: The imports, on a device

**Files:**
- Create: `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ZxartImportTest.java`

- [ ] **Step 1: Write the test**

Three imports against a `Canned`-style fake at the `Http` seam but the **real** SAF write: a prod's tzx lands in `Downloaded/Games`, a tune's ogg in `Downloaded/Music`, a picture's png in `Downloaded/Graphics`; each is found by `Tree.find` afterwards rather than by name alone; a second import of the same thing does not produce `Games (1)`. Guard on the content-folder grant with `assumeTrue` and remember that a guarded class prints `OK` having asserted nothing unless `am instrument -r` is used.

- [ ] **Step 2: Run it, watch the first assertion fail, then make it pass**

The likely first failure is the folder: `Kinds.folderFor("Music")` must be reached from `Imports.document`, which means the item's `kind` really is "Music" at that point.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ZxartImportTest.java
git commit -m "test: importing a prod, a tune and a picture

The three folders on a real tree through a real documents provider, plus
the case SAF makes silent: createDocument over a name already there
cheerfully makes 'Games (1)', which is how a collection acquires four of
everything and how somebody concludes the first import failed."
```

---

## Part 3 — the provider

### Task 13: `Zxart` — search, and md5 confirmation

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/scrape/Zxart.java`
- Test: `app/src/test/java/dev/ldlab/zedex/library/scrape/ZxartTest.java`

**Interfaces:**
- Produces: `new Zxart(Http, Locale)` implementing `Provider`; `Zxart.NAME = "zxart"`.

- [ ] **Step 1: Write the failing tests**

```java
    /**
     * The confirmation, which is the only reason this provider is worth having
     * beside the other two.
     *
     * zxart has no md5 filter - zxProdMd5 and zxReleaseMd5 are both ignored -
     * but every release lists releaseStructure, a recursive tree with an md5
     * for the zip *and* for each file inside it. So a name search's candidates
     * can be confirmed by hashing the file on disk, and an unzipped .tzx
     * matches the same row its zip does.
     */
    @Test
    public void aCandidateConfirmedByHashIsExact() throws Exception {
        Pace.forget();
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PROD_SEARCH)
                                                    .then(Fixtures.RELEASES_LICENCE_TO_KILL);

        List<Candidate> found = new Zxart(http, Locale.ENGLISH).search(
                game("Licence to Kill.tzx", "ea37a787becbdb2c74dada8e668b8f37"));

        assertTrue(found.get(0).exact);
        assertEquals("92668", found.get(0).handle);
    }

    /** The zip's own md5 confirms too - most people have the zip. */
    @Test
    public void theZipsHashConfirmsAsWell() throws Exception { ... }

    /** No hash match is not a failure: the name candidates stand, marked as
     *  the guesses they are, and Merge's fill-gaps rule bounds what a wrong
     *  one can cost. */
    @Test
    public void withoutAHashMatchTheNameCandidatesStand() throws Exception { ... }

    /** A file whose md5 cannot be read - the commonest case on a tree grant -
     *  never asks for a release list at all. */
    @Test
    public void nothingIsConfirmedWithoutAHash() throws Exception { ... }

    /** At most three candidates are confirmed. A search for a common word
     *  answers with hundreds, and confirming each is a paced request each. */
    @Test
    public void onlyTheFirstFewCandidatesAreConfirmed() throws Exception { ... }

    /** Five is the ceiling and it is stated honestly: one search, up to three
     *  confirmations, one prod. Media are static files and cost nothing. */
    @Test
    public void theCostIsTheCeilingAndNotTheAverage() {
        assertEquals(5, new Zxart(new Fixtures.Canned(), Locale.ENGLISH)
                            .costPerGame(Provider.Wanted.usual()));
    }
```

Write out the bodies of the four elided tests in the same shape as the first — the plan's own rule is that "similar to Task N" is not an instruction, and neither is "similar to the test above".

- [ ] **Step 2: Run, fail, implement, pass**

The `releaseStructure` walk is the piece worth writing carefully: recursive over `items`, comparing `md5` case-insensitively, and returning as soon as it hits.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/scrape/Zxart.java \
        app/src/test/java/dev/ldlab/zedex/library/scrape/ZxartTest.java
git commit -m "feat: zxart can say for certain which game a file is

A name search, then the md5 of the file on disk against releaseStructure
- which lists a hash for the zip and for every file inside it, so an
unzipped tape matches the row its zip does. No other source here can be
certain about a renamed file: ZXInfo's filecheck only answers for
TOSEC-named ones.

There is no md5 filter to ask with - zxProdMd5 and zxReleaseMd5 are both
ignored - so confirmation costs a request per candidate and is therefore
bounded at three. costPerGame states five, the ceiling, rather than the
three a single-candidate search actually spends: it prices a sweep
somebody is about to commit a collection to."
```

### Task 14: What zxart knows about a game

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/scrape/Zxart.java`
- Test: `app/src/test/java/dev/ldlab/zedex/library/scrape/ZxartTest.java`

- [ ] **Step 1: Write the failing tests** — media mapped **by rule**: the `zximages/…` member of `imagesUrls` is the `titlescreens` medium and the `/screenshot/…` members are `screenshots`; `inlays` by filename suffix, as Task 1 counted over 400 releases: no `_` at all (166) and `_Front` (41) into `covers`, `_Back` (101) into `backcovers`, `_Media` (99) into `physicalmedia`. **Everything else falls through and is skipped** — about a fifth of all inlay files carry a side or edition marker (`_2`, `_3`, `_4`, `_SideA`, `_SideB`, `_2Back`, `_FrontCase`, `_GoldenCase`, `_WhiteCase`…) that maps to no folder this app has, and inventing a fourth for them is not this task's business. Assert the fall-through as well as the three that land; `maps` into `maps`; `ads` into `adverts`; `instructions` into `manuals`. Assert that a `Wanted` naming only `covers` produces exactly one medium — the folder set is what a sweep is priced on.

- [ ] **Step 2: Implement, run, pass.**

- [ ] **Step 3: Commit** — subject `feat: zxart's artwork, manuals and maps`, body naming the rule-not-position choice and the measured inlay suffixes.

### Task 15: `hardwareRequired`, spoken in the vocabulary the app already has

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/scrape/Zxart.java`
- Test: `app/src/test/java/dev/ldlab/zedex/library/scrape/ZxartTest.java`
- **Not modified: `machine/Suggested.java`.** See below — this task was rewritten on 2026-08-14 after `692173f` landed, and the whole of the change is that nothing in `Suggested` needs touching.

**Interfaces:**
- Consumes: `Suggested.MACHINE_WORDS`, `Suggested.INPUT_WORDS` (both already public, precisely so a table can be held up against them), `Meta.Builder.machine(String)`, `Meta.Builder.inputs(List<String>)`.
- Produces: nothing new. `Zxart` fills two fields that three screens already read.

**Why this is not a new mechanism.** `Meta.machine` is a *phrase* in ZXDB's vocabulary — "ZX-Spectrum 128K", "Pentagon 128" — and `Meta.inputs` a list of phrases in another — "Kempston Joystick", "Interface 2 (right)". `Suggested.machines(machineType, file, ids)` and `Suggested.joysticks(inputs, names)` parse those, and **`machines` now narrows by the file and lets the file win** (`692173f`, `MACHINES_FOR_FILE`: a `.trd` or `.scl` means Pentagon or Scorpion, a `.dsk` means +3, because Fuse's `utils.c` picks those machines itself and overrules anything else on every open).

So zxart's `hardwareRequired` is a **record** statement and must enter through those two fields, never around them. A zxart release saying `zx128` whose downloaded file is a `.trd` must still land on a Pentagon — and it will, for free, because the file narrows the record. A second path that fed machine ids straight to a dialog would reintroduce exactly the bug `692173f` fixed: a suggestion the emulator overrules, re-applied for ever.

zxart is in a better position than ZXDB here, and it is worth knowing why: it states hardware **per release**, and a release is the thing you actually download, so the statement and the file come from the same row and agree far more often than a per-entry `machinetype` can.

- [ ] **Step 1: Write the failing test**

Use the vocabulary **Task 1 counted** — the assertions below name the four tokens seen in the captured releases, and every token Task 1 found must appear in the table or in the refusals, with a comment giving its count.

```java
    /**
     * zxart's own words for hardware, translated into the app's.
     *
     * <b>Not into machine ids, and that is the point of the task.</b>
     * Meta.machine and Meta.inputs are phrases in a vocabulary Suggested
     * already parses, and since 692173f that parse narrows the record by the
     * file and lets the file win - a .trd means Pentagon or Scorpion whatever
     * the record claims, because Fuse picks those itself and overrules
     * anything else on every open. Feeding ids to a dialog directly would
     * bypass that and re-suggest a machine the emulator refuses, for ever.
     */
    @Test
    public void hardwareBecomesTheAppsOwnVocabulary() {
        assertEquals("ZX-Spectrum 128K", Zxart.machineWord("zx128"));
        assertEquals("ZX-Spectrum 48K", Zxart.machineWord("zx48"));
        assertEquals("ZX-Spectrum 128 +3", Zxart.machineWord("zx+3"));

        assertEquals("Kempston Joystick", Zxart.inputWord("kempston"));
        assertEquals("Interface 2 (right)", Zxart.inputWord("int2_2"));
    }

    /** Every word this class can produce is one Suggested already knows, so
     *  the two vocabularies cannot drift: a phrase Suggested does not match is
     *  a machine nobody is ever offered, and it fails silently. */
    @Test
    public void everyWordProducedIsOneSuggestedKnows() {
        for (String token : Zxart.HARDWARE) {
            String machine = Zxart.machineWord(token);
            String input = Zxart.inputWord(token);

            if (machine != null) {
                assertTrue(machine + " is not one of Suggested.MACHINE_WORDS",
                           Arrays.asList(Suggested.MACHINE_WORDS).contains(machine));
            }
            if (input != null) {
                assertTrue(input + " is not one of Suggested.INPUT_WORDS",
                           Arrays.asList(Suggested.INPUT_WORDS).contains(input));
            }
        }
    }

    /**
     * A token that is neither, and a token nobody recorded, both answer
     * nothing.
     *
     * "ay" is a sound chip: it implies a 128K-family machine to a person and
     * must not be turned into one here, because the release's own machine
     * token already says which, and inferring a second answer from the first
     * is how a table starts disagreeing with itself. An unrecorded token is
     * the ZX81-16K rule: refuse rather than match a fragment.
     */
    @Test
    public void whatIsNeitherAMachineNorAnInputSaysNothing() {
        assertNull(Zxart.machineWord("ay"));
        assertNull(Zxart.inputWord("ay"));
        assertNull(Zxart.machineWord("nonsense"));
        assertNull(Zxart.inputWord("nonsense"));
    }

    /** And the two fields actually reach the store, in the record's own order,
     *  which is what every screen downstream reads. */
    @Test
    public void aFetchFillsMachineAndInputs() throws Exception {
        Pace.forget();
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.RELEASES_LICENCE_TO_KILL);

        Meta meta = new Zxart(http, Locale.ENGLISH)
                .fetch(new Candidate("92668", "Licence to Kill", "1989", null, true),
                       Provider.Wanted.nothing()).meta;

        assertEquals("ZX-Spectrum 128K", meta.machine);
        assertTrue(meta.inputs.contains("Kempston Joystick"));
        assertTrue(meta.inputs.contains("Interface 2 (right)"));
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests 'dev.ldlab.zedex.library.scrape.ZxartTest'`
Expected: FAIL — `machineWord`, `inputWord` and `HARDWARE` undefined.

- [ ] **Step 3: Implement in `Zxart` only**

`HARDWARE` is the recorded vocabulary from Task 1: **nine values over 400 releases, and nothing else appeared** — `zx48` 58, `kempston` 35, `zx128` 33, `int2_2` 27, `zx+3` 20, `ay` 12, `int2_1` 10, `cursor` 7, `zx16` 7. Put those counts in the comment, and the slice's caveat with them: it is a contiguous run of releases, not a census.

Two tables map the ones that mean something:

| token | becomes |
|---|---|
| `zx16` | `Meta.machine` = "ZX-Spectrum 16K" |
| `zx48` | "ZX-Spectrum 48K" |
| `zx128` | "ZX-Spectrum 128K" |
| `zx+3` | "ZX-Spectrum 128 +3" |
| `kempston` | `Meta.inputs` += "Kempston Joystick" |
| `int2_1` | "Interface 2 (left)" |
| `int2_2` | "Interface 2 (right)" |
| `cursor` | "Cursor" |
| `ay` | **nothing** |

Everything else answers null. `int2_1`/`int2_2` read as Interface 2's two ports, which is what the naming says and all the data supports. `fetch` takes the release chosen for the item (the first, which is the original), maps its `hardwareRequired`, and fills `machine` with the first machine word found and `inputs` with every input word found, in the release's own order.

- [ ] **Step 4: Run the JVM tier and pass.**

- [ ] **Step 5: Commit**

```bash
git add -u && git commit -m "feat: read a game's machine and interface from zxart

hardwareRequired is a stated fact where Suggested has been inferring one
from a genre and a machine-type string - and zxart states it per release,
which is the row the file itself came from, so the statement and the file
agree far more often than a per-entry machinetype can.

It goes in as words rather than as machine ids, and Suggested is not
touched. Meta.machine and Meta.inputs are vocabularies it already parses,
and since 692173f that parse narrows the record by the file and lets the
file win: a .trd is a Pentagon whatever the record claims, because Fuse
picks that itself and overrules anything else on every open. A second
path handing ids to a dialog would re-suggest a machine the emulator
refuses, for ever, which is the bug that commit fixed.

The table is asserted against Suggested.MACHINE_WORDS and INPUT_WORDS in
both directions, so a phrase that stops matching is a test failure rather
than a machine nobody is offered. ay maps to nothing: it is a sound chip,
the release's machine token already says which computer, and inferring a
second answer from the first is how a table starts disagreeing with
itself."
```

### Task 16: The video link, end to end

**Files:**
- Modify: `library/meta/Meta.java`, `library/meta/Metadata.java`, `library/scrape/Merge.java`, `library/scrape/Zxart.java`, `library/ui/CataloguePane.java`, `library/ui/GameInfoView.java`, `AndroidManifest.xml`, `res/values*/strings.xml`
- Test: `app/src/test/java/dev/ldlab/zedex/library/meta/` (the store's round-trip test) and `MergeTest`

The checklist for a new `Meta` field, all six places, because missing one loses the value silently: the public field, the copy constructor, the `Builder` setter, `Field.VIDEO_LINK(true)` plus `get`/`set`, `Merge.of`'s `first(...)`, and `Metadata`'s writer `field(writer, "videoLink", game.videoLink)` and reader `case "videoLink":`.

- [ ] **Step 1: Write the failing tests** — a round trip through the store keeps the link; `Merge` fills it from an addition and never overwrites; a `Meta` with no link answers null. Then `Zxart` builds `https://www.youtube.com/watch?v=<id>` from `youtubeId` and nothing when there is none.

- [ ] **Step 2: Implement, including the manifest and the icons.**

```xml
        <!-- A catalogue row and a scraped game can carry a video link. Without
             this, queryIntentActivities answers nothing on Android 11+ and the
             icon hides itself on a phone with three browsers - so the check for
             "is there anything that can open a link" would be answered wrongly
             rather than not asked. -->
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="https" />
        </intent>
```

One string, nine files: `library_video` ("Video"), used as the icon's `contentDescription` — and it must be **static**, since nothing on screen may change its `contentDescription` continuously without taking the whole UI Automator suite down with it.

- [ ] **Step 3: Run `scripts/check-strings.py`, both test tiers, and commit.**

### Task 17: Register the provider, and write it all down

**Files:**
- Modify: `library/scrape/Scrapers.java`, `CLAUDE.md`, `README.md`, `docs/LIBRARY.md`, `docs/INTERNALS.md`, `docs/PRIVACY.md`
- Test: `app/src/test/java/dev/ldlab/zedex/library/scrape/ScrapersOrderTest.java`

- [ ] **Step 1: Write the failing test** — `Scrapers.all` ends with zxart, and a build with no ScreenScraper credentials still has ZXInfo before it. A new source must not outrank a proven one silently on everybody's collection.

- [ ] **Step 2: Register it, run, pass.**

- [ ] **Step 3: Write the rules into `CLAUDE.md`**, in the hard-rules list, each one a thing that cost measurement:

- An unrecognised zxart filter is **ignored**, with the control result and the seven names that are ignored.
- `types:zxProd,zxRelease` is documented by the archive's own author and answers **500**; an item is two requests.
- An absent `limit:` means **1000** and a 1.18 MB reply.
- `start:` is an **item offset**, unlike ZXInfo's page-number `offset` — the size-and-stride rule already in that file applies with the opposite sign.
- The order answers to **`votes`** on all three entities even where the reply calls the field `rating`; `rating` and `votesAmount` are ignored.
- `legalStatus` **never says "available"**: 975 of 1,000 say `unknown`, so it is translated to nothing stated rather than passed through, or every row in the catalogue is greyed with no reason.
- A prod's **publisher cannot be resolved** — `export:publisher` 500s, `export:group` answers nothing, `export:author` has no row, and comma-joined ids return one.
- The **manners**: `robots.txt` disallows `/api`, so 250 ms, nothing speculative, an identified User-Agent, and the archive was written to (with the date).

- [ ] **Step 4: The prose.** `README.md` loses its "Not yet — a second catalogue" line and gains a terse feature line (a line or two, never paragraphs); `docs/LIBRARY.md` loses "zxart, the second catalogue… is not built" from *Still open* and gains the shelves, the sorts and the folders; `docs/INTERNALS.md` gains zxart to its scraping and catalogue sections; `docs/PRIVACY.md` gains the two new statements — the app talks to `zxart.ee` when somebody browses or scrapes, and a video link is handed to another app on a tap with no request of our own.

- [ ] **Step 5: Full verification before the PR**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
env JAVA_HOME=/opt/android-studio/jbr ./gradlew lintDebug
scripts/check-strings.py && scripts/check-prefs.py
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The whole instrumentation suite, once, because this is a large feature — and read the **first** failure rather than the count, since one flake cascades into every later class failing the same way.

- [ ] **Step 6: Commit**

```bash
git add -u && git commit -m "feat: zxart as a scraping source, and the rules it taught us

zxart goes last in the order: a new source must not outrank a proven one
silently on somebody's whole collection, and the order is theirs to
change.

The CLAUDE.md rules are the expensive half of this branch. An
unrecognised zxart filter is ignored rather than refused, which is how
seven plausible names - including the one this project had already
written down as the title search - each read as a search that matched
everything. types: is documented by the archive's own author and answers
500. An absent limit is a 1.18 MB reply. start is an item offset where
ZXInfo's offset is a page number. The order answers to votes even where
the reply calls the field rating. legalStatus never says available. A
publisher id resolves to nothing at all."
```

---

## Self-Review

**Spec coverage.** Client → Task 2, 3. Tree and language-proof folders → Tasks 4, 5. Shelves, paging, items, `legalStatus`, `similarTo` → Task 6. `knowsFormats` → Task 7. Sorts → Task 8. Source row and registration → Task 9. Music and graphics → Tasks 10, 11, 12. Provider search and md5 → Task 13. Media → Task 14. `hardwareRequired` → Task 15. Video link → Task 16. Scrape order, manners, docs → Task 17. The four unmeasured things and the email → Task 1, which blocks Tasks 8 (ZXInfo's sorts), 11 (the two search filters), 14 (inlay suffixes) and 15 (the hardware vocabulary).

**Two gaps found and left deliberate, rather than papered over.**

1. **Tasks 13–16 carry elided test bodies** (`{ ... }`) where Task 2–11 carry full ones. That is a real placeholder by this skill's own standard. It is marked in place — "write out the bodies in the same shape" — because those four tasks' assertions depend on numbers Task 1 has not measured yet, and inventing them now is exactly the fixture-from-memory failure the spec is built around. **Before Part 3 is executed, revisit Tasks 13–16 and fill the bodies from Task 1's findings.** Anyone executing Part 3 straight through should treat that as the first step.
2. **Task 6's `ZxartCatalogue` is written in outline** — the decisions (`itemFrom`, `kindOf`, `availabilityOf`, the sub-shelf page) are complete code and the plumbing between them is described. Filling it in is mechanical and the tests define it exactly.

**Type consistency.** `ZxartApi.Ask.page(page, size)` is (zero-based page, size) everywhere; `ZxartTree.rootOf` returns `int`/`-1` and `Kinds.zxartRoot` takes that `int` and returns `String`/null, so `Kinds.folderFor(null)` → `OTHER` is the path a missing tree takes; `Fixtures.Canned.then` has both arities used; `Catalogue.Sort` is spelled `TOP` (not `RATING`) in every task; `knowsFormats()` and `sorts()` are the only two new `Catalogue` members besides `Sort` and the `Query` pair.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-14-zxart-integration.md`.
