# The Catalogue Browser and Downloader — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fourth library tab that browses an online catalogue — ZXInfo first — and imports a chosen game into the user's own content folder with its details and artwork already attached.

**Architecture:** A `Catalogue` seam beside the existing `Provider` seam, in a new `library/catalogue` package. Everything that *decides* something — which folder a category maps to, which file of which version to take, how a page is counted — is plain Java with no Android types and is tested on the JVM in seconds. Everything that *talks* — the ZXInfo adapter, the SAF writer, the importer — is instrumentation-tested against a canned `Http` exactly as `ZxInfoTest` already is. The screen is a self-contained `CatalogueView` that `LibraryActivity` shows in place of its list; the activity gains about forty lines, not six hundred.

**Tech Stack:** Java 17, Android SDK 36 (minSdk 30), AndroidX RecyclerView, JUnit 4, `org.json` (already on both classpaths). No new runtime dependencies.

## Global Constraints

- **Design spec:** `docs/superpowers/specs/2026-08-10-catalogue-downloader-design.md`. Read it before Task 1. Where this plan and the spec differ, see *Two places this plan differs from the spec* at the end — nowhere else.
- **Never `curl` `api.zxinfo.dk`, `zxinfo.dk` or `spectrumcomputing.co.uk` bare.** The address was blocked once, at the network layer, and it took an email to lift. Every request in development goes through the app, which sends `Zedex/<version>`. A bare `curl` is how the address was lost the first time.
- **The API is paced; static files are not.** Every `api.zxinfo.dk` request goes through `Pace` (Task 1). Thumbnails and downloads are files on an ordinary web host and are not paced.
- **Nothing is fetched speculatively.** A page when the grid reaches it, a thumbnail when its row is on screen, a file when somebody asked for it. No prefetch, no warming, no background sync.
- **A new string is nine files.** Every `values/strings.xml` key must also be added to `values-cs`, `-de`, `-es`, `-fr`, `-it`, `-pl`, `-ru`, `-uk`. Run `python3 scripts/check-strings.py` before every commit; it must print `8 translations agree with values/`.
- **Never translate `*_values` arrays.** They are compared with `equals`. This feature adds none, so if you find yourself adding one, stop and re-read the task.
- **A preference's type is whatever wrote it.** This feature adds one preference, `catalogueProvider`, a String. Run `python3 scripts/check-prefs.py` before every commit; it must print `every preference is read as it is written`.
- **No Android types in the JVM tier.** `library/catalogue/Catalogue.java`, `Kinds.java`, `Pick.java` and `library/scrape/Pace.java` must not import `android.*` or `androidx.*` — not even `android.net.Uri` or `android.util.Log`. `unitTests.returnDefaultValues = true` makes every stub method answer null or zero *silently*, so a test that touches one passes having asserted nothing. That is why `ZxInfoTest` is an instrumentation test and not a unit test.
- **Recorded replies, never invented ones.** Every JSON fixture in a test is a body a real service actually sent, trimmed to what is asserted on — the practice that caught `/filecheck` answering `entry_id` where the specification said `id`. Follow `ZxInfoTest`'s convention: an inline `private static final String` constant with a comment naming the exact request it came from.
- **`Provider.Scraped` and `library.ui.Scraped` are different classes with the same name.** One is a fetch's result, the other is the library's async artwork loader. Import the one you mean, explicitly, every time.
- **`ScrapeException.Kind` has seven values** — `NOT_CONFIGURED`, `BAD_CREDENTIALS`, `QUOTA_EXCEEDED`, `THREAD_LIMIT`, `CLOSED`, `NETWORK`, `MALFORMED` — and `worthWaiting()` answers true for `THREAD_LIMIT` and `NETWORK`. There are no static factories; each provider keeps its own private helpers. A catalogue raises the same kinds for the same reasons.
- **`Types` exposes predicates, not its array.** `Types.openable(String name)`, `Types.archive`, `Types.supported`, `Types.extension`. `OPENABLE` is private — read it through the predicate, never copy the list.
- **Build:** `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug`. No native change here, so `build-native.sh` is never run.
- **Unit tests:** `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest`.
- **Per task, run `testDebugUnitTest` and `assembleDebug`** — a minute together, and the compile catches a broken screen. Do not run the full instrumentation suite per task.
- **Targeted instrumentation only, named per task.** Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk` after `assembleDebug` **and** `assembleDebugAndroidTest`, then:
  ```sh
  adb shell am instrument -w -e class dev.ldlab.zedex.library.catalogue.<Name> \
      dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
  ```
  Never `connectedAndroidTest`: it uninstalls the app and wipes its data and its content-folder grant.
- **`assembleDebugAndroidTest` does not repackage `app-debug.apk`.** Build both, every time, or you test the previous build and believe it.
- **Set `ANDROID_SERIAL=emulator-5554`.** A physical phone may also be attached and is not to be touched. Set `secondScreen` to `false` before any instrumentation run.
- **The full suite runs once**, at Task 13.
- **Commit subjects** take a conventional prefix: `feat:`, `fix:`, `test:`, `refactor:`, `docs:`. The body explains *why*.
- **Branch:** `feature/catalogue-downloader`, already checked out, already carrying the three design commits.

## File Structure

| File | Responsibility |
|---|---|
| `library/scrape/Pace.java` | **New.** How long ago this host was last asked, and sleeping the difference. Static, per-host, no Android types. |
| `library/catalogue/Catalogue.java` | **New.** The seam: the interface and the value types `Shelf`, `Query`, `Page`, `Item`, `Version`, `Download`. No Android types. |
| `library/catalogue/Kinds.java` | **New.** A catalogue's own word for what a thing is → the folder it lands in. No Android types. |
| `library/catalogue/Pick.java` | **New.** Which version, and which file of it. Format preference, and the rule that a recording is never the game. No Android types. |
| `library/catalogue/ZxInfoCatalogue.java` | **New.** ZXInfo as a `Catalogue`: shelves, paging, an item and its files. |
| `library/catalogue/Catalogues.java` | **New.** Which catalogues exist and which one is configured — `Scrapers`' opposite number. |
| `library/catalogue/Imports.java` | **New.** Cache → check → unzip → SAF → tell the caches. |
| `library/catalogue/Thumbnails.java` | **New.** A bounded url→bitmap cache over `Http`, for rows on screen. |
| `storage/Tree.java` | **New.** Find-or-create a folder under a granted tree, and write a file into it. The SAF verbs, in one place. |
| `library/ui/CatalogueAdapter.java` | **New.** Rows for shelves and items, the failed-page row, the greyed unavailable row. |
| `library/ui/CatalogueView.java` | **New.** The tab itself: shelf stack, paging on scroll, the pane. |
| `library/ui/CataloguePane.java` | **New.** One item's facts, Import, Other versions…, Play the recording. |
| `screen/LibraryActivity.java` | Gains a fourth `Tab`, its label and icon, and one branch in `show(Tab)`. |
| `library/scrape/ZxInfo.java` | Loses its private pacing to `Pace`; its two host constants become package-visible so the catalogue shares them. |
| `app/src/test/java/dev/ldlab/zedex/library/catalogue/` | **New.** JVM tests for the four pure classes. |
| `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/` | **New.** Device tests for the adapter, the writer, the importer and the screen. |

---

### Task 1: `Pace` — one clock for one host

The ban was "based on behaviour patterns", and `ZxInfo` paces itself **per instance**. The moment a catalogue object exists beside the provider object, two things are each waiting 500ms and neither is waiting for the other: the real spacing halves exactly when this feature doubles the traffic. Pull it out first, before anything can be built on top of the wrong one.

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/scrape/Pace.java`
- Create: `app/src/test/java/dev/ldlab/zedex/library/scrape/PaceTest.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/library/scrape/ZxInfo.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Pace.before(String host, long minimumMs)`, `Pace.forget()` (tests only), and the package-visible `ZxInfo.API`, `ZxInfo.FILES`, `ZxInfo.SCREENS`, `ZxInfo.SCREENS_PREFIX`, `ZxInfo.MINIMUM_INTERVAL_MS`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/ldlab/zedex/library/scrape/PaceTest.java`:

```java
package dev.ldlab.zedex.library.scrape;

import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * How long ago this host was last asked.
 *
 * On the JVM because the whole of it is a clock and a map, and because the
 * failure it exists to stop is invisible from the outside: two objects each
 * waiting half a second and neither waiting for the other looks exactly like
 * one object waiting half a second, right up until an address is blocked for
 * "behaviour patterns".
 */
public class PaceTest {

    @Before
    public void setUp() {
        Pace.forget();
    }

    /** The first ask of a host waits for nothing - there is nothing to wait
     *  behind, and a spinner half a second long for one request is a cost
     *  paid for no reason. */
    @Test
    public void thefirstAskIsNotDelayed() {
        long began = System.nanoTime();

        Pace.before("api.example", 500);

        assertTrue("the first call waited", elapsedMs(began) < 100);
    }

    /**
     * <b>The whole point: the second ask waits, whoever makes it.</b>
     *
     * Not "the same object's second ask" - Pace is asked by host, so a
     * catalogue and a scraper hitting the same API queue behind each other
     * rather than interleaving into twice the rate.
     */
    @Test
    public void asecondAskOfTheSameHostWaits() {
        Pace.before("api.example", 200);
        long began = System.nanoTime();

        Pace.before("api.example", 200);

        assertTrue("the second call did not wait", elapsedMs(began) >= 190);
    }

    /** A different host is a different queue. Waiting on ZXInfo's account
     *  before asking spectrumcomputing for a picture would make a grid of
     *  thumbnails take a minute. */
    @Test
    public void adifferentHostIsNotWaitedOn() {
        Pace.before("api.example", 200);
        long began = System.nanoTime();

        Pace.before("files.example", 200);

        assertTrue("an unrelated host was waited on", elapsedMs(began) < 100);
    }

    /** Zero and below mean no pacing at all, rather than a busy loop. */
    @Test
    public void nointervalIsNoWait() {
        Pace.before("api.example", 0);
        long began = System.nanoTime();

        Pace.before("api.example", 0);

        assertTrue(elapsedMs(began) < 100);
    }

    private static long elapsedMs(long began) {
        return (System.nanoTime() - began) / 1_000_000L;
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest --tests '*PaceTest'`
Expected: FAIL, compilation error — `cannot find symbol: class Pace`.

- [ ] **Step 3: Write `Pace`**

> **The source below has a defect, found in review and fixed in `b916dc0` — read the shipped `Pace.java` rather than this block.** `static synchronized` locks on `Pace.class`, and with `Thread.sleep` inside that lock a thread pacing one host blocks callers for *every* host, which is precisely what the class exists not to do. What shipped reserves the next slot per host under a brief lock and sleeps outside it, so hosts are independent while two callers for the same host still queue — `slot = max(now, reserved + minimum)` is monotonic, so two same-host callers can never both compute "no wait".

Create `app/src/main/java/dev/ldlab/zedex/library/scrape/Pace.java`:

```java
package dev.ldlab.zedex.library.scrape;

import java.util.HashMap;
import java.util.Map;

/**
 * How long ago a host was last asked, and sleeping the difference.
 *
 * <b>Per host and not per object.</b> ZXInfo publishes no rate limit and asks
 * clients to behave; the one time this app misbehaved, the address was blocked
 * at the network layer and it took an email to lift - "you have been jailed
 * because of bad requests... there's no hard limit, it's all based on
 * behaviour patterns". So the thing being spaced is the traffic arriving at
 * the far end, which is not a property of whichever object here happened to
 * send it. A provider scraping and a catalogue browsing are two objects and
 * one address.
 *
 * Static state, deliberately. There is one network and one of each host, and
 * an instance per caller is exactly the arrangement this replaces.
 *
 * {@code System.nanoTime} rather than {@code SystemClock.elapsedRealtime}: it
 * is monotonic in the same way, and it keeps this class out of Android
 * entirely so its arithmetic can be tested in milliseconds rather than on a
 * device in minutes.
 */
public final class Pace {

    private static final Map<String, Long> lastAsked = new HashMap<>();

    private Pace() {
    }

    /**
     * Returns when it is this host's turn.
     *
     * Call immediately before the request, never after: the clock is stamped
     * here, so a caller that stamps late spaces from the end of one request
     * to the start of the next and drifts slower than asked. Slower is the
     * safe direction, but it is not the stated one.
     *
     * An interrupt is not swallowed - it is re-raised on the thread, because
     * the one thing that legitimately interrupts a paced request is the
     * screen that started it going away.
     */
    public static synchronized void before(String host, long minimumMs) {
        if (minimumMs <= 0) return;

        Long previous = lastAsked.get(host);
        long now = System.nanoTime() / 1_000_000L;

        if (previous != null) {
            long since = now - previous;

            if (since < minimumMs) {
                try {
                    Thread.sleep(minimumMs - since);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                now = System.nanoTime() / 1_000_000L;
            }
        }

        lastAsked.put(host, now);
    }

    /** Tests only: forget every host, so one test's pacing is not the next
     *  test's wait. */
    public static synchronized void forget() {
        lastAsked.clear();
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest --tests '*PaceTest'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Move `ZxInfo` onto it, and open its constants**

In `ZxInfo.java`:

1. Delete the `lastAsked` field and the whole `private void pace()` method.
2. Change the four host constants from `private static final String` to package-visible `static final String`, and rename `BASE` to `API` (it is one of three hosts now, and "base" says nothing about which):
   ```java
   static final String API = "https://api.zxinfo.dk/v3/";
   static final String FILES = "https://spectrumcomputing.co.uk";
   static final String SCREENS = "https://zxinfo.dk/media";
   static final String SCREENS_PREFIX = "/zxscreens/";
   ```
   Update every use of `BASE` in the file to `API`.
3. Make the interval package-visible so the catalogue paces to the same number rather than to a copy of it that can drift:
   ```java
   static final long MINIMUM_INTERVAL_MS = 500;
   ```
4. Replace the `pace();` call in `ask(...)` with:
   ```java
   Pace.before("api.zxinfo.dk", MINIMUM_INTERVAL_MS);
   ```
5. Remove the now-unused `android.os.SystemClock` reference.

Leave the class comment's `MINIMUM_INTERVAL_MS` reference alone — it is still the name of the number. Add one sentence to that comment: `Paced through {@link Pace}, which counts per host rather than per object - see its own comment for why that distinction is the whole of the manners here.`

- [ ] **Step 6: Prove the provider still works**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug assembleDebugAndroidTest`
Then install both and run the existing provider test, which must be unaffected:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w -e class dev.ldlab.zedex.library.scrape.ZxInfoTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Expected: OK, the same count as before the change.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/scrape/Pace.java \
        app/src/test/java/dev/ldlab/zedex/library/scrape/PaceTest.java \
        app/src/main/java/dev/ldlab/zedex/library/scrape/ZxInfo.java
git commit -m "refactor: pace the host, not the object

ZxInfo waited 500ms between its own requests, which is the right number and
the wrong subject. The thing being spaced is what arrives at the far end, and
that is not a property of whichever object here sent it - so the moment a
catalogue object exists beside the provider object, two of them each wait half
a second, neither waits for the other, and the real spacing halves exactly as
the traffic doubles.

Pace counts per host, statically, because there is one network. Nothing about
the provider changes; this is the groundwork put in before anything can be
built on the wrong version of it.

The address was blocked once for 'behaviour patterns' and it took an email to
lift. That is the whole reason this is a separate commit rather than a line in
a later one."
```

---

### Task 2: The `Catalogue` seam

Five methods and six value types, all plain Java. Nothing implements it yet — this task is the shape, the paging arithmetic and the tests that pin it.

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/catalogue/Catalogue.java`
- Create: `app/src/test/java/dev/ldlab/zedex/library/catalogue/CatalogueTest.java`

**Interfaces:**
- Consumes: `dev.ldlab.zedex.library.scrape.ScrapeException`.
- Produces: the interface `Catalogue` with `name()`, `configured()`, `shelves()`, `open(Shelf, Query, int)`, `item(String)`, `refusalFor(int)`; and the nested finals `Shelf`, `Query`, `Page`, `Item`, `Version`, `Download`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/ldlab/zedex/library/catalogue/CatalogueTest.java`:

```java
package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * The seam's own value types, and the counting.
 *
 * All of it on the JVM because none of it is Android and because the piece
 * that goes wrong is arithmetic nobody looks at: a page count off by one is
 * either a grid that stops one page early - which reads as a catalogue that
 * has fewer games than it has - or one that asks for ever, which reads as
 * nothing at all and costs a request every time the list is flung.
 */
public class CatalogueTest {

    private static Catalogue.Item anItem(String id) {
        return new Catalogue.Item(id, "Head over Heels", "1987", "Ocean Software Ltd",
                                  "Arcade Game", "Available", null,
                                  Collections.<Catalogue.Version>emptyList());
    }

    // --- paging ----------------------------------------------------------------------

    /** A full page against a known total has more behind it. */
    @Test
    public void afullPageOfAknownTotalHasMore() {
        Catalogue.Page page = new Catalogue.Page(
                Arrays.asList(anItem("1"), anItem("2")),
                Collections.<Catalogue.Shelf>emptyList(), 2, 10);

        assertTrue(page.hasMore());
        assertEquals(10, page.total());
    }

    /**
     * The last page does not.
     *
     * Counted from what has been seen rather than from the page number, since
     * a shelf may hand back a short page in the middle - which is the whole
     * reason this is not `items.size() == pageSize`.
     */
    @Test
    public void thelastPageOfAknownTotalDoesNot() {
        Catalogue.Page page = new Catalogue.Page(
                Arrays.asList(anItem("9"), anItem("10")),
                Collections.<Catalogue.Shelf>emptyList(), 8, 10);

        assertFalse(page.hasMore());
    }

    /**
     * An unknown total is judged by whether anything came at all.
     *
     * Some shelves cannot say how many there are - a random one has no total
     * and never will - so "did this page bring anything" is the only question
     * left. An empty page ends the list; a non-empty one is worth asking
     * again after.
     */
    @Test
    public void anunknownTotalIsJudgedByWhetherAnythingCame() {
        Catalogue.Page some = new Catalogue.Page(
                Arrays.asList(anItem("1")), Collections.<Catalogue.Shelf>emptyList(),
                0, Catalogue.Page.UNKNOWN_TOTAL);
        Catalogue.Page none = new Catalogue.Page(
                Collections.<Catalogue.Item>emptyList(),
                Collections.<Catalogue.Shelf>emptyList(),
                0, Catalogue.Page.UNKNOWN_TOTAL);

        assertTrue(some.hasMore());
        assertFalse(none.hasMore());
    }

    /** A page of nothing at all is the end, whatever the total claims - a
     *  total that disagrees with an empty page is a service being wrong, and
     *  believing it asks for ever. */
    @Test
    public void anemptyPageEndsTheListEvenAgainstAtotal() {
        Catalogue.Page page = new Catalogue.Page(
                Collections.<Catalogue.Item>emptyList(),
                Collections.<Catalogue.Shelf>emptyList(), 0, 500);

        assertFalse(page.hasMore());
    }

    /** Sub-shelves count as arrival too: a page of categories and no items is
     *  a page that worked. */
    @Test
    public void apageOfShelvesIsNotAnemptyPage() {
        Catalogue.Page page = new Catalogue.Page(
                Collections.<Catalogue.Item>emptyList(),
                Arrays.asList(new Catalogue.Shelf("92177", "Games",
                                                  Catalogue.Shelf.Accepts.NOTHING)),
                0, Catalogue.Page.UNKNOWN_TOTAL);

        assertFalse("a page of shelves has nothing more to page through",
                    page.hasMore());
        assertEquals(1, page.shelves().size());
    }

    // --- what a shelf accepts --------------------------------------------------------

    /** A shelf that takes nothing ignores whatever it is handed, rather than
     *  refusing it - the tab hands the same Query to every shelf. */
    @Test
    public void ashelfThatAcceptsNothingIgnoresTheQuery() {
        Catalogue.Shelf shelf = new Catalogue.Shelf("newest", "Newest",
                                                    Catalogue.Shelf.Accepts.NOTHING);

        assertFalse(shelf.accepts(Catalogue.Shelf.Accepts.TEXT));
        assertTrue(shelf.accepts(Catalogue.Shelf.Accepts.NOTHING));
    }

    @Test
    public void ashelfSaysWhatItTakes() {
        Catalogue.Shelf search = new Catalogue.Shelf("search", "Search",
                                                     Catalogue.Shelf.Accepts.TEXT);
        Catalogue.Shelf letters = new Catalogue.Shelf("az", "A-Z",
                                                      Catalogue.Shelf.Accepts.LETTER);

        assertTrue(search.accepts(Catalogue.Shelf.Accepts.TEXT));
        assertTrue(letters.accepts(Catalogue.Shelf.Accepts.LETTER));
        assertFalse(letters.accepts(Catalogue.Shelf.Accepts.TEXT));
    }

    // --- availability ----------------------------------------------------------------

    /**
     * Available is the only word that means available.
     *
     * Everything else - "Never released", "MIA", "Distribution denied" - is a
     * fact about the game and stays on the list, greyed. Guessing the other
     * way round would hide whichever states a future vocabulary adds, silently,
     * which is the failure this app has already had once with machine types.
     */
    @Test
    public void anythingButAvailableIsNot() {
        assertTrue(anItemAvailable("Available").available());
        assertFalse(anItemAvailable("Never released").available());
        assertFalse(anItemAvailable("MIA").available());
        assertFalse(anItemAvailable(null).available());

        assertTrue("case is the service's business",
                   anItemAvailable("available").available());
    }

    private static Catalogue.Item anItemAvailable(String availability) {
        return new Catalogue.Item("1", "A game", null, null, null, availability, null,
                                  Collections.<Catalogue.Version>emptyList());
    }

    // --- a download ------------------------------------------------------------------

    /** Absolute, always. ZXDB's own recordings live on archive.org, so a
     *  path joined onto one base host would fetch every one of them from the
     *  wrong place - which is a 404 and looks exactly like a game that has
     *  none. */
    @Test
    public void adownloadCarriesAwholeUrl() {
        Catalogue.Download file = new Catalogue.Download(
                "https://archive.org/download/x/HeadOverHeels.rzx.zip", "rzx", 41232);

        assertEquals("rzx", file.format());
        assertEquals(41232, file.size());
        assertTrue(file.url().startsWith("https://"));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest --tests '*CatalogueTest'`
Expected: FAIL, compilation error — `package dev.ldlab.zedex.library.catalogue does not exist`.

- [ ] **Step 3: Write the seam**

Create `app/src/main/java/dev/ldlab/zedex/library/catalogue/Catalogue.java`:

```java
package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.library.scrape.ScrapeException;

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
            this.items = items == null ? Collections.<Item>emptyList() : items;
            this.shelves = shelves == null ? Collections.<Shelf>emptyList() : shelves;
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
            this.versions = versions == null ? Collections.<Version>emptyList() : versions;
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

        /** "Head over Heels (1987) - Ocean Software Ltd", skipping whichever
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
            this.files = files == null ? Collections.<Download>emptyList() : files;
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
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest --tests '*CatalogueTest'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Prove the paging test is not vacuous**

Change `hasMore()`'s first line to `if (false) return false;` and re-run.
Expected: `anemptyPageEndsTheListEvenAgainstAtotal` FAILS.
Then change `seenBefore + items.size() < total` to `<=` and re-run.
Expected: `thelastPageOfAknownTotalDoesNot` FAILS.
Restore both. This is the mutation check the spec asks for on the parts that fail silently — an off-by-one here is a catalogue that quietly has fewer games than it has.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/catalogue/Catalogue.java \
        app/src/test/java/dev/ldlab/zedex/library/catalogue/CatalogueTest.java
git commit -m "feat: a seam for browsing, beside the one for asking

Provider answers 'here is a file, what do you know about it', and every
method of it assumes the file exists. Browsing a catalogue is the other
question and gets its own five methods.

The one idea that makes it universal: a way in is data. A catalogue declares
its shelves and the tab renders what it is given, so a second one never owes
an answer it has not got - zxart's way in is a category tree and ZXInfo's is
a search box, and both are shelves. A page carries sub-shelves as well as
items, which is how a tree fits through a seam with no method for one.

Nothing implements it yet. This is the shape and the arithmetic, on the JVM,
where an off-by-one in the paging can be caught in a second rather than read
as a catalogue with fewer games than it has."
```

---

### Task 3: `Kinds` — the catalogue's word, our folder

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/catalogue/Kinds.java`
- Create: `app/src/test/java/dev/ldlab/zedex/library/catalogue/KindsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Kinds.GAMES`, `APPLICATIONS`, `COMPILATIONS`, `MAGAZINES`, `DEMOSCENE`, `RECORDINGS`, `OTHER` (String constants), `Kinds.ALL` (`String[]`), `Kinds.folderFor(String kind)`, and `Kinds.ZXDB_VOCABULARY` (`String[]`, the recorded list the test asserts against).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/ldlab/zedex/library/catalogue/KindsTest.java`:

```java
package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Which folder an imported thing lands in.
 *
 * The table is asserted <b>in both directions</b>, which is the lesson from
 * the machine table: written from one collection, it matched "16" inside "ZX81
 * 16K" and offered a 16K Spectrum for the third commonest value in the
 * database. So here the vocabulary is the recorded one - every genreType ZXDB
 * actually uses, 23 of them over 42,828 entries - and the test says both that
 * each known word lands where it should and that nothing outside the list can
 * reach a folder by accident.
 */
public class KindsTest {

    // --- the six folders and the fallback ---------------------------------------------

    @Test
    public void thegameGenresAreGames() {
        assertEquals(Kinds.GAMES, Kinds.folderFor("Arcade Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Adventure Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Puzzle Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Casual Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Sport Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Strategy Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Game"));
    }

    @Test
    public void theprogramsAreApplications() {
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Utility"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Programming"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Emulator"));
        assertEquals(Kinds.APPLICATIONS, Kinds.folderFor("Replacement ROM"));
    }

    @Test
    public void thecollectionsAreCompilations() {
        assertEquals(Kinds.COMPILATIONS, Kinds.folderFor("Compilation"));
        assertEquals(Kinds.COMPILATIONS, Kinds.folderFor("Covertape"));
        assertEquals(Kinds.COMPILATIONS, Kinds.folderFor("Box Set"));
    }

    @Test
    public void thereadingIsMagazines() {
        assertEquals(Kinds.MAGAZINES, Kinds.folderFor("Electronic Magazine"));
        assertEquals(Kinds.MAGAZINES, Kinds.folderFor("Book"));
        assertEquals(Kinds.MAGAZINES, Kinds.folderFor("E-Book"));
    }

    @Test
    public void thesceneIsDemoscene() {
        assertEquals(Kinds.DEMOSCENE, Kinds.folderFor("Demoscene"));
        assertEquals(Kinds.DEMOSCENE, Kinds.folderFor("Tech Demo"));
        assertEquals(Kinds.DEMOSCENE, Kinds.folderFor("Animation"));
    }

    /**
     * <b>Unknown falls to Other - never a guess, never dropped.</b>
     *
     * "General" alone is 3,650 entries, and a genre added upstream next year
     * has to land somewhere sensible rather than somewhere plausible.
     */
    @Test
    public void anythingUnrecognisedFallsToOther() {
        assertEquals(Kinds.OTHER, Kinds.folderFor("General"));
        assertEquals(Kinds.OTHER, Kinds.folderFor("Hardware"));
        assertEquals(Kinds.OTHER, Kinds.folderFor("Advertising"));

        assertEquals("a genre invented after this was written",
                     Kinds.OTHER, Kinds.folderFor("Firmware"));
        assertEquals(Kinds.OTHER, Kinds.folderFor(null));
        assertEquals(Kinds.OTHER, Kinds.folderFor(""));
    }

    // --- both directions --------------------------------------------------------------

    /**
     * Every word ZXDB actually uses has a home.
     *
     * The direction that catches an upstream vocabulary this table has drifted
     * behind. Failing here is not a bug in the mapping - it is notice that the
     * database has a word this app has never seen, which is worth knowing
     * before somebody's import lands in Other.
     */
    @Test
    public void everyRecordedGenreLandsSomewhere() {
        for (String genre : Kinds.ZXDB_VOCABULARY) {
            assertNotNull(genre + " has no folder", Kinds.folderFor(genre));
        }
    }

    /**
     * And nothing lands anywhere but the seven.
     *
     * The direction that catches a typo in the table itself, which would
     * otherwise create a folder named after a mistake and put games in it.
     */
    @Test
    public void nothingLandsOutsideTheSeven() {
        Set<String> allowed = new HashSet<>(Arrays.asList(Kinds.ALL));

        for (String genre : Kinds.ZXDB_VOCABULARY) {
            assertTrue(genre + " landed in " + Kinds.folderFor(genre),
                       allowed.contains(Kinds.folderFor(genre)));
        }

        assertEquals("seven folders, no more", 7, allowed.size());
    }

    // --- the order is a rule ----------------------------------------------------------

    /**
     * A compilation of games is a compilation.
     *
     * An entry can honestly be both, and the table is read in order so that
     * the more specific word wins. Without a stated order the rule is not a
     * rule and the answer depends on which line somebody happened to write
     * first.
     */
    @Test
    public void themoreSpecificWordWins() {
        assertEquals(Kinds.COMPILATIONS, Kinds.folderFor("Compilation Game"));
        assertEquals(Kinds.MAGAZINES, Kinds.folderFor("Electronic Magazine Game"));
    }

    /**
     * The catch-all takes any game, and the ordering protects the rest.
     *
     * The bare "game" keyword is deliberately greedy, and that is not the
     * ZX81 mistake repeating: there, "16" matched inside "ZX81 16K" - a
     * numeric fragment matching across an unrelated machine. Here "game" is a
     * whole word of the domain matching something that genuinely is one - a
     * holographic game, a board game and an educational game are all games -
     * so pin it deliberately, or a later reader mistakes the greediness for a
     * bug and narrows the match.
     *
     * But greedy only works because two rows are tried first: "Gameboy
     * Emulator" and "Electronic Magazine Game" both contain "game" too, and
     * both have to keep landing in Applications and Magazines rather than
     * being swallowed by it. Without asserting these here, moving the GAMES
     * row to the top of the table would silently break them while every
     * other assertion in this method kept passing.
     */
    @Test
    public void thecatchAllTakesAnyGameAndTheOrderingProtectsTheRest() {
        assertEquals(Kinds.GAMES, Kinds.folderFor("Holographic Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Board Game"));
        assertEquals(Kinds.GAMES, Kinds.folderFor("Educational Game"));

        assertEquals("the ordering catches this one first",
                     Kinds.APPLICATIONS, Kinds.folderFor("Gameboy Emulator"));
        assertEquals("and this one",
                     Kinds.MAGAZINES, Kinds.folderFor("Electronic Magazine Game"));
    }

    /** Matching is case-insensitive, since a second catalogue's vocabulary is
     *  its own and zxart's categories are not capitalised like ZXDB's. */
    @Test
    public void thecaseIsNotTheCatalogueSproblem() {
        assertEquals(Kinds.GAMES, Kinds.folderFor("arcade game"));
        assertEquals(Kinds.DEMOSCENE, Kinds.folderFor("DEMOSCENE"));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest --tests '*KindsTest'`
Expected: FAIL — `cannot find symbol: class Kinds`.

- [ ] **Step 3: Write `Kinds`**

Create `app/src/main/java/dev/ldlab/zedex/library/catalogue/Kinds.java`:

```java
package dev.ldlab.zedex.library.catalogue;

import java.util.Locale;

/**
 * The catalogue's own word for what a thing is, and the folder it lands in.
 *
 * Imports go to {@code Downloaded/<folder>/} rather than to the root of
 * somebody's collection: without that there is no way afterwards to tell what
 * this app added from what they put there themselves. Six folders and a
 * fallback, grounded in ZXDB's own 23 genre types over 42,828 entries -
 * Games ~22,000, Applications ~7,600, Compilations ~3,600, Magazines ~3,400,
 * Demoscene ~1,400, Other ~4,800.
 *
 * Three rules, all of them there to avoid repeating the machine table's
 * mistake - written from one collection, it matched "16" inside "ZX81 16K":
 *
 * <ul>
 *   <li><b>The category is the catalogue's; the folder is ours.</b> zxart's
 *       vocabulary is different and gets its own mapping into these same
 *       folders, not a folder scheme of its own.</li>
 *   <li><b>Unknown falls to {@link #OTHER}</b> - never a guess, never dropped.</li>
 *   <li><b>The table is asserted against the recorded vocabulary</b>, in both
 *       directions, in {@code KindsTest}. {@link #ZXDB_VOCABULARY} is that
 *       record and is not to be edited to make a test pass.</li>
 * </ul>
 *
 * {@link #RECORDINGS} is not reachable from here: it is decided by the file's
 * own kind and not by the entry's, which is the one place that ordering is
 * inverted. See {@code Pick} and {@code Imports}.
 */
public final class Kinds {

    public static final String GAMES = "Games";
    public static final String APPLICATIONS = "Applications";
    public static final String COMPILATIONS = "Compilations";
    public static final String MAGAZINES = "Magazines";
    public static final String DEMOSCENE = "Demoscene";
    public static final String RECORDINGS = "Recordings";
    public static final String OTHER = "Other";

    /** Every folder this feature can create, for the test that says nothing
     *  lands outside them. */
    public static final String[] ALL = {
        GAMES, APPLICATIONS, COMPILATIONS, MAGAZINES, DEMOSCENE, RECORDINGS, OTHER,
    };

    /**
     * Every {@code genretype} ZXDB uses, as counted from the offline dump at
     * github.com/zxdb/ZXDB.
     *
     * Recorded rather than imagined, and the reason {@code KindsTest} can
     * assert both directions. If a future dump has a word that is not here,
     * add it here <em>and</em> decide where it goes - the test failing is the
     * notice, not the bug.
     */
    public static final String[] ZXDB_VOCABULARY = {
        "Adventure Game", "Advertising", "Animation", "Arcade Game", "Book",
        "Box Set", "Casual Game", "Compilation", "Covertape", "Demoscene",
        "E-Book", "Electronic Magazine", "Emulator", "Game", "General",
        "Hardware", "Programming", "Puzzle Game", "Replacement ROM",
        "Sport Game", "Strategy Game", "Tech Demo", "Utility",
    };

    /**
     * The table, in order.
     *
     * <b>The order is a rule, not an accident.</b> An entry can honestly be a
     * game and a compilation, so the more specific word has to be looked for
     * first - otherwise "a compilation of games goes to Compilations" depends
     * on which line somebody typed first, which is not a rule.
     *
     * Each row is one folder followed by the words that reach it.
     */
    private static final String[][] TABLE = {
        { COMPILATIONS, "compilation", "covertape", "box set" },
        { MAGAZINES, "electronic magazine", "e-book", "book" },
        { DEMOSCENE, "demoscene", "tech demo", "animation" },
        { APPLICATIONS, "utility", "programming", "emulator", "replacement rom" },
        { GAMES, "arcade game", "adventure game", "puzzle game", "casual game",
                 "sport game", "strategy game", "game" },
    };

    private Kinds() {
    }

    /**
     * Where a thing of this kind lands.
     *
     * Never null and never anything but one of {@link #ALL}: an unrecognised
     * word is {@link #OTHER}, which is a real answer and not a failure.
     *
     * Contains rather than equals, because a second catalogue's words are
     * phrases - zxart says "Game, Arcade" where ZXDB says "Arcade Game" - and
     * because ZXDB's own genre field is sometimes the full "Arcade Game:
     * Adventure". The refusals that a substring match needs are handled by
     * the ordering above rather than by a list of exceptions: every word here
     * is at least two syllables long and none is a substring of another
     * except deliberately, which {@code KindsTest} pins.
     */
    public static String folderFor(String kind) {
        if (kind == null || kind.isEmpty()) return OTHER;

        String lower = kind.toLowerCase(Locale.ROOT);

        for (String[] row : TABLE) {
            for (int at = 1; at < row.length; at++) {
                if (lower.contains(row[at])) return row[0];
            }
        }

        return OTHER;
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest --tests '*KindsTest'`
Expected: PASS, 9 tests.

If `everyRecordedGenreLandsSomewhere` fails, the mapping is wrong. If `nothingLandsOutsideTheSeven` fails, a folder constant is misspelled in `TABLE`. Fix the code, never the recorded vocabulary.

- [ ] **Step 5: Mutation-check the ordering**

Move the `GAMES` row to the top of `TABLE` and re-run.
Expected: `themoreSpecificWordWins` FAILS with `Compilation Game` landing in Games.
Restore the order. The spec calls this out as one of two mappings that fail silently — you get *a* file in *a* folder either way.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/catalogue/Kinds.java \
        app/src/test/java/dev/ldlab/zedex/library/catalogue/KindsTest.java
git commit -m "feat: which folder an imported thing lands in

Downloaded/<kind>/, from the catalogue's own word for what a thing is. Six
folders and a fallback, sized against ZXDB's 23 genre types over 42,828
entries rather than against one person's collection - which is exactly how
the machine table came to match '16' inside 'ZX81 16K'.

So the vocabulary is recorded in the class and asserted in both directions:
every word the database uses has a home, and nothing lands outside the seven
folders. Unknown falls to Other rather than being guessed at or dropped -
'General' alone is 3,650 entries.

The table is read in order, and that order is the rule that makes 'a
compilation of games is a compilation' true rather than accidental."
```

---

### Task 4: `Pick` — which version, and which file of it

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/catalogue/Pick.java`
- Create: `app/src/test/java/dev/ldlab/zedex/library/catalogue/PickTest.java`

**Interfaces:**
- Consumes: `Catalogue.Item`, `Catalogue.Version`, `Catalogue.Download`.
- Produces: `Pick.PREFERENCE` (`String[]`), `Pick.forGame(Catalogue.Version)`, `Pick.forGame(Catalogue.Item)`, `Pick.recording(Catalogue.Item)`, `Pick.isRecording(Catalogue.Download)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/ldlab/zedex/library/catalogue/PickTest.java`:

```java
package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One tap has to choose a file, and the choice is invisible.
 *
 * Whatever it picks, something loads and the game runs - so a wrong preference
 * order is never reported as a bug, it is just a collection that quietly
 * became snapshots. That is why this is mutation-tested and why the order is
 * written down as a constant rather than as a sort.
 */
public class PickTest {

    private static Catalogue.Download file(String format) {
        return new Catalogue.Download("https://example/x." + format + ".zip", format, 1000);
    }

    private static Catalogue.Version version(String label, Catalogue.Download... files) {
        return new Catalogue.Version(label, "1987", Arrays.asList(files));
    }

    private static Catalogue.Item item(Catalogue.Version... versions) {
        return new Catalogue.Item("1", "A game", "1987", "Ocean", "Arcade Game",
                                  "Available", null, Arrays.asList(versions));
    }

    // --- the order ---------------------------------------------------------------------

    /**
     * A tape image beats a disk image beats a snapshot.
     *
     * Not arbitrary: a tape carries the loading scheme and the custom loader,
     * and half of what a Spectrum game is remembered for happens while it
     * loads. A snapshot always works and always starts after the part worth
     * seeing.
     */
    @Test
    public void atapeBeatsAdiskBeatsAsnapshot() {
        assertEquals("tzx", Pick.forGame(version(null, file("z80"), file("dsk"),
                                                 file("tzx"))).format());
        assertEquals("dsk", Pick.forGame(version(null, file("z80"), file("dsk"))).format());
        assertEquals("z80", Pick.forGame(version(null, file("sna"), file("z80"))).format());
    }

    /** tzx before tap: the same tape, and tzx is the format that can hold the
     *  loader a tap has already flattened. */
    @Test
    public void tzxBeatsTap() {
        assertEquals("tzx", Pick.forGame(version(null, file("tap"), file("tzx"))).format());
    }

    /** The whole order, walked - every format beats every one after it. */
    @Test
    public void everyFormatBeatsTheOnesAfterIt() {
        for (int first = 0; first < Pick.PREFERENCE.length; first++) {
            for (int second = first + 1; second < Pick.PREFERENCE.length; second++) {
                Catalogue.Download chosen = Pick.forGame(
                        version(null, file(Pick.PREFERENCE[second]),
                                file(Pick.PREFERENCE[first])));

                assertEquals(Pick.PREFERENCE[first] + " lost to " + Pick.PREFERENCE[second],
                             Pick.PREFERENCE[first], chosen.format());
            }
        }
    }

    // --- what is never the game --------------------------------------------------------

    /**
     * <b>A recording is not the game.</b>
     *
     * An RZX is somebody playing, and this app can play it back - verified on
     * a device, 10th Frame bowled a frame with nobody touching the controls.
     * So it is worth importing and it is never what "import this game" means:
     * a recording where the game should be is a game you cannot play.
     */
    @Test
    public void arecordingIsNeverChosenAsTheGame() {
        assertNull("a recording was taken as the game",
                   Pick.forGame(version(null, file("rzx"))));

        assertEquals("tap", Pick.forGame(version(null, file("rzx"), file("tap"))).format());
    }

    /** And a wrapper is not a format. .gz is how a thing arrived, not what it
     *  is, so nothing is ever chosen for being one. */
    @Test
    public void agzipIsNeverChosen() {
        assertNull(Pick.forGame(version(null, file("gz"))));
    }

    /** Nor is anything this app has never heard of. */
    @Test
    public void anunknownFormatIsNotChosen() {
        assertNull(Pick.forGame(version(null, file("exe"), file("pdf"))));
    }

    /** A version with nothing in it answers null rather than throwing - an
     *  entry with no files at all is an ordinary thing to find. */
    @Test
    public void aversionOfNothingIsNotAcrash() {
        assertNull(Pick.forGame(new Catalogue.Version(null, null, null)));
        assertNull(Pick.forGame(new Catalogue.Version(null, null,
                                                      Collections.<Catalogue.Download>emptyList())));
    }

    // --- which version ------------------------------------------------------------------

    /**
     * The original, which is whichever the catalogue lists first.
     *
     * Not the best-formatted one: the second version may be a Spanish
     * re-release with a tzx where the original has only a tap, and quietly
     * importing that instead is how somebody ends up with a game in a language
     * they cannot read.
     */
    @Test
    public void theoriginalIsThefirstVersionListed() {
        Catalogue.Item game = item(version("original", file("tap")),
                                   version("Spanish re-release", file("tzx")));

        assertEquals("tap", Pick.forGame(game).format());
    }

    /**
     * Unless the first has nothing this app can open.
     *
     * A version whose only file is a scan or a manual is not a reason to
     * refuse the whole entry - fall through to the next that has something.
     */
    @Test
    public void aversionWithNothingUsableFallsThroughToTheNext() {
        Catalogue.Item game = item(version("original", file("pdf")),
                                   version("re-release", file("tap")));

        assertEquals("tap", Pick.forGame(game).format());
    }

    @Test
    public void anitemWithNothingUsableAnywhereIsNull() {
        assertNull(Pick.forGame(item(version("original", file("pdf")))));
        assertNull(Pick.forGame(item()));
    }

    // --- the recording, in its own right ------------------------------------------------

    /** Offered separately, and found wherever it is - a recording may hang off
     *  any version. */
    @Test
    public void therecordingIsFoundWhereverItIs() {
        Catalogue.Item game = item(version("original", file("tap")),
                                   version("re-release", file("rzx")));

        Catalogue.Download found = Pick.recording(game);

        assertNotNull(found);
        assertEquals("rzx", found.format());
    }

    @Test
    public void nothingIsNotArecording() {
        assertNull(Pick.recording(item(version("original", file("tap")))));
        assertNull(Pick.recording(item()));
    }

    @Test
    public void whatArecordingIs() {
        assertTrue(Pick.isRecording(file("rzx")));
        assertFalse(Pick.isRecording(file("tap")));
        assertFalse(Pick.isRecording(null));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest --tests '*PickTest'`
Expected: FAIL — `cannot find symbol: class Pick`.

- [ ] **Step 3: Write `Pick`**

Create `app/src/main/java/dev/ldlab/zedex/library/catalogue/Pick.java`:

```java
package dev.ldlab.zedex.library.catalogue;

import java.util.List;

/**
 * Which version, and which file of it, one tap means.
 *
 * The choice is invisible from the outside: whatever this returns, something
 * loads and the game runs. A wrong order here is never reported as a bug - it
 * is a collection that quietly became snapshots - which is why the order is a
 * written-down constant and why {@code PickTest} mutation-checks it.
 */
public final class Pick {

    /**
     * Tape, then disk, then snapshot.
     *
     * Tape images first because they carry the loading scheme and the custom
     * loader that a snapshot has already thrown away, and half of what a
     * Spectrum game is remembered for happens during loading. Disk images
     * next, in the order the machines that read them appear. Snapshots last:
     * they always work and they always start after the part worth seeing.
     *
     * {@code gz} is deliberately absent - it is a wrapper rather than a
     * format. {@code rzx} is deliberately absent for a different reason: it is
     * somebody playing the game, not the game. See {@link #recording}.
     */
    public static final String[] PREFERENCE = {
        "tzx", "tap", "trd", "scl", "dsk", "mgt", "img", "udi", "szx", "z80", "sna",
    };

    /** What this app can play back but must never mistake for the program. */
    private static final String RECORDING = "rzx";

    private Pick() {
    }

    /**
     * The best file of the original release.
     *
     * The original is whichever the catalogue lists first, and nothing here
     * re-sorts them: their order is the catalogue's own statement about which
     * came first, and this app has no better source. A version with nothing
     * openable in it falls through to the next rather than failing the whole
     * entry - a release that is only a scan is an ordinary thing to find.
     *
     * @return null when nothing anywhere in the item can be opened, which is
     *         an answer: the screen says so rather than importing a file the
     *         emulator will refuse.
     */
    public static Catalogue.Download forGame(Catalogue.Item item) {
        if (item == null) return null;

        for (Catalogue.Version version : item.versions()) {
            Catalogue.Download found = forGame(version);
            if (found != null) return found;
        }

        return null;
    }

    /** The best file of one version, or null. */
    public static Catalogue.Download forGame(Catalogue.Version version) {
        if (version == null) return null;

        List<Catalogue.Download> files = version.files();

        for (String wanted : PREFERENCE) {
            for (Catalogue.Download file : files) {
                if (wanted.equals(file.format())) return file;
            }
        }

        return null;
    }

    /**
     * The recording, if the item has one.
     *
     * Looked for across every version rather than only the original: a
     * recording is of a playthrough, not of a release, and which version it
     * hangs off is an accident of how it was catalogued. 13.5% of ZXDB's
     * entries have one.
     */
    public static Catalogue.Download recording(Catalogue.Item item) {
        if (item == null) return null;

        for (Catalogue.Version version : item.versions()) {
            for (Catalogue.Download file : version.files()) {
                if (isRecording(file)) return file;
            }
        }

        return null;
    }

    public static boolean isRecording(Catalogue.Download file) {
        return file != null && RECORDING.equals(file.format());
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon testDebugUnitTest --tests '*PickTest'`
Expected: PASS, 13 tests at this point — Step 5's findings take it to 15.

- [ ] **Step 5: Mutation-check the preference**

**Corrections, found across two review rounds:**

*Round 1.* The original wording of this step claimed reversing `PREFERENCE`
fails `everyFormatBeatsTheOnesAfterIt` along with two others. It does not.
That test built its own expected winner from `Pick.PREFERENCE[first]` (with
`first < second` by index construction), so it only proved the scan honours
whatever order the array currently declares - reversing the array reverses
the expectation right along with it, and the test passed either way. It was
renamed `theScanHonoursTheDeclaredOrderWhateverItIs` and its javadoc now says
so plainly; it remains a real, distinct property worth pinning (that
`Pick#forGame` returns the earliest-declared format present, regardless of
the order the files arrive in), just not proof the declared order is correct.

*Round 2.* The test added in round 1 to close that gap,
`atapeBeatsAnyDiskBeatsAnySnapshot`, pinned ordering *between* the three
groups (tape/disk/snapshot) but not *within* them - a swap inside the disk
run (`trd`/`udi`) or the snapshot run passed silently, while `Pick.java`'s own
javadoc claims that run order is deliberate ("in the order the machines that
read them appear") with nothing then testing it. It was folded into a single
test, `theWholeOrderIsWhatThisAppIntends`, built from a new constant
`EXPECTED = concat(TAPES, DISKS, SNAPSHOTS)` - the full intended order written
out independently of `PREFERENCE` - asserting every earlier entry in
`EXPECTED` beats every later one, pairwise, the same shape as the retired
`everyFormatBeatsTheOnesAfterIt` loop but reading its expectations from
`EXPECTED` instead of from the constant under test. `theGroupsAccountForEvery
DeclaredFormat` was kept, now built from `EXPECTED` too, still asserting the
groups add up to the whole of `PREFERENCE`.

Three mutations, all restored after checking:

1. **Reverse `PREFERENCE`.**
   Expected and confirmed: `atapeBeatsAdiskBeatsAsnapshot`, `tzxBeatsTap` and
   `theWholeOrderIsWhatThisAppIntends` FAIL (3 of 15).
   `theScanHonoursTheDeclaredOrderWhateverItIs` and
   `theGroupsAccountForEveryDeclaredFormat` still PASS - the array is still a
   permutation of the same 11 formats, just in the wrong order, and neither
   test was ever able to notice that.

2. **Append `"rzx"` to the end of `PREFERENCE`.**
   Expected and confirmed: `arecordingIsNeverChosenAsTheGame` FAILS, and so
   does `theGroupsAccountForEveryDeclaredFormat` (2 of 15) - a bonus catch,
   since `rzx` is not in any of the three groups and was never meant to be in
   `PREFERENCE` at all.

3. **Swap two formats within one group** (`trd` and `udi`, both `DISKS`),
   leaving the array a permutation of the same 11 formats in the same three
   groups.
   Expected and confirmed: only `theWholeOrderIsWhatThisAppIntends` FAILS
   (1 of 15). Neither of the two-and-three-group tests from round 1 could
   have caught this even in principle - this is the mutation the round-2 fix
   exists for.

Restore all three.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/catalogue/Pick.java \
        app/src/test/java/dev/ldlab/zedex/library/catalogue/PickTest.java
git commit -m "feat: which file one tap means

Tape, then disk, then snapshot. A tape image carries the loading scheme and
the custom loader that a snapshot has already thrown away, and half of what a
Spectrum game is remembered for happens while it loads.

The original release is whichever the catalogue lists first and nothing here
re-sorts them - picking the better-formatted second version is how somebody
ends up with a Spanish re-release they did not ask for.

Two formats are deliberately unreachable: .gz is a wrapper rather than a
format, and .rzx is somebody playing the game rather than the game. The
second is offered in its own right instead, and found across every version
because a recording is of a playthrough, not of a release.

Mutation-tested, because the whole choice is invisible: whatever it picks,
something loads and the game runs, so a wrong order is never reported as a
bug - it is a collection that quietly became snapshots."
```

---

### Task 5: `ZxInfoCatalogue` — the first implementation

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/catalogue/ZxInfoCatalogue.java`
- Create: `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ZxInfoCatalogueTest.java`

Instrumentation rather than JVM: this class builds URLs with `android.net.Uri.encode` and logs with `android.util.Log`, both of which the stub `android.jar` answers with null and zero **silently** — a unit test of it would pass having encoded nothing. That is the same reason `ZxInfoTest` is where it is.

**Interfaces:**
- Consumes: `Catalogue` and its value types, `Http`, `Http.Reply`, `Pace`, `ScrapeException`, `ZxInfo.API`, `ZxInfo.FILES`, `ZxInfo.SCREENS`, `ZxInfo.SCREENS_PREFIX`, `ZxInfo.MINIMUM_INTERVAL_MS`.
- Produces: `new ZxInfoCatalogue(Http http)`; the five `Catalogue` methods; and the shelf ids `"search"`, `"letter"`, `"newest"`, `"random"`, `"genres"`.

- [ ] **Step 1: Read the swagger and write down the four endpoint shapes**

Before writing anything, fetch `https://api.zxinfo.dk/v3/swagger_v3.yaml` **through a browser or the existing app**, not with `curl` against the host, and confirm four things, writing each into the class comment as you go:

1. The search path and its parameter names (`query`, `mode`, `size`, `offset`, `sort`, `contenttype`).
2. Whether a by-letter path exists (`/games/byletter/{letter}`). **If it does not**, the A–Z shelf is implemented as the ordinary search with `query=<letter>` and `sort=title_asc`, and the class comment says so — a shelf is data, and one implemented a different way is still the same shelf.
3. What `sort` accepts, for the newest shelf.
4. That `/metadata/` answers with `genretypes` — **plural**, measured 2026-08-10: top-level keys are `machinetypes genretypes features`, 5,289 bytes. This plan originally said `genretype`, singular, and was wrong; the recorded body below was written from memory rather than from a reply, which is the exact thing this plan's own testing rules forbid. Read `genretypes` then `genretype`, the same leniency `ZxInfo.byHash` already uses for `entry_id`. And a count each — CLAUDE.md records 32 machine types over 39,666 entries from this call, and it is the genre list the *Categories* shelf hands back as sub-shelves. **Check its size.** `Http.Real.get` refuses a body over `MOST_BODY` (2 MB) with `IOException("reply too large")`; if `/metadata/` is bigger than that, the *Categories* shelf needs the genre list hard-coded from `Kinds.ZXDB_VOCABULARY` instead, and the class comment says why.

**Never filter a search by machine.** `PENTAGON` is a sibling of `ZXSPECTRUM` in ZXInfo's scheme, not a variant of it, and filtering to Spectrum silently excludes the Pentagon demoscene — most of what arrives as `.trd` and `.scl`.

**Ask for `mode=compact`.** Measured on one game: 10,286 bytes against `full`'s 45,318, with every field this app reads byte-identical. `tiny` is not an option — it has no `controls`.

- [ ] **Step 2: Write the failing test**

Create `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ZxInfoCatalogueTest.java`. The canned `Http` and the recorded-body convention are lifted from `ZxInfoTest`; keep them identical so the two read as siblings.

```java
package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.ldlab.zedex.library.scrape.Http;

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
 * Every body below is one the service actually sent, trimmed to what is
 * asserted on. Writing one to make a parser pass is how a client comes to
 * believe a field name the service does not use, which this app has been
 * caught by once already: /filecheck answers entry_id where the specification
 * says id.
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

    /** {@code /games/0002259?mode=compact}, trimmed to one release and its
     *  files - including the recording, which is on archive.org. */
    private static final String RECORD = "{"
            + "\"_id\":\"0002259\",\"found\":true,\"_source\":{"
            + "  \"title\":\"Head over Heels\",\"originalYearOfRelease\":1987,"
            + "  \"genreType\":\"Arcade Game\",\"availability\":\"Available\","
            + "  \"publishers\":[{\"name\":\"Ocean Software Ltd\"}],"
            + "  \"releases\":[{\"releaseSeq\":0,\"releaseYear\":1987,"
            + "    \"publishers\":[{\"name\":\"Ocean Software Ltd\"}],"
            + "    \"files\":["
            + "      {\"type\":\"Tape image\",\"format\":\"TZX\",\"size\":41232,"
            + "       \"path\":\"/pub/sinclair/games/h/HeadOverHeels.tzx.zip\"},"
            + "      {\"type\":\"Snapshot image\",\"format\":\"Z80\",\"size\":38104,"
            + "       \"path\":\"/pub/sinclair/games/h/HeadOverHeels.z80.zip\"},"
            + "      {\"type\":\"RZX recording\",\"format\":\"RZX\",\"size\":190222,"
            + "       \"path\":\"https://archive.org/download/zx_rzx/HeadOverHeels.rzx.zip\"}"
            + "  ]}]}}";

    /** {@code /metadata/}, trimmed to the genre list the Categories shelf
     *  hands back. */
    private static final String METADATA = "{"
            + "\"genretypes\":[{\"key\":\"Arcade Game\",\"doc_count\":12184},"
            + "               {\"key\":\"Utility\",\"doc_count\":5731}]}";

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

        assertEquals(1, game.versions().size());
        assertEquals(3, game.versions().get(0).files().size());
        assertEquals("one request, not two", 1, http.asked.size());
    }

    /**
     * <b>A record's paths are relative to two different hosts, and one is
     * neither.</b>
     *
     * /pub/ is on spectrumcomputing.co.uk; ZXDB's own recordings are on
     * archive.org and arrive as whole urls already. Joining every path onto
     * one base is how every loading screen this app offered was fetched from
     * the wrong host and discarded as a 404 - which looks exactly like a game
     * that has none.
     */
    @Test
    public void everyFileUrlIsAbsoluteAndOnItsOwnHost() throws Exception {
        Canned http = new Canned().then(200, RECORD);

        List<Catalogue.Download> files =
                new ZxInfoCatalogue(http).item("0002259").versions().get(0).files();

        assertEquals("https://spectrumcomputing.co.uk/pub/sinclair/games/h/HeadOverHeels.tzx.zip",
                     files.get(0).url());
        assertEquals("https://archive.org/download/zx_rzx/HeadOverHeels.rzx.zip",
                     files.get(2).url());
    }

    /** The format is the inner one, not "zip" - what decides whether this app
     *  can open it is what is inside. */
    @Test
    public void theformatIsWhatIsInsideTheZip() throws Exception {
        Canned http = new Canned().then(200, RECORD);

        List<Catalogue.Download> files =
                new ZxInfoCatalogue(http).item("0002259").versions().get(0).files();

        assertEquals("tzx", files.get(0).format());
        assertEquals("z80", files.get(1).format());
        assertEquals("rzx", files.get(2).format());
        assertEquals(41232, files.get(0).size());
    }

    /** And the two decisions built on that, end to end. */
    @Test
    public void thetapeIsTheGameAndTherzxIsTheRecording() throws Exception {
        Canned http = new Canned().then(200, RECORD);

        Catalogue.Item game = new ZxInfoCatalogue(http).item("0002259");

        assertEquals("tzx", Pick.forGame(game).format());
        assertEquals("rzx", Pick.recording(game).format());
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
```

- [ ] **Step 3: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug assembleDebugAndroidTest
```
Expected: FAIL to compile — `cannot find symbol: class ZxInfoCatalogue`.

- [ ] **Step 4: Write `ZxInfoCatalogue`**

Create `app/src/main/java/dev/ldlab/zedex/library/catalogue/ZxInfoCatalogue.java`. The shape, with the parts that carry a decision written out:

```java
package dev.ldlab.zedex.library.catalogue;

import android.net.Uri;
import android.util.Log;

import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.Pace;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.library.scrape.ZxInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * ZXInfo, the API over ZXDB, as something to browse.
 *
 * A class of its own rather than more methods on {@code ZxInfo}: that one is
 * already the longest file in the package and the two answer different
 * questions - one is asked about a file somebody has, this one about the
 * 39,666 they do not. They share the hosts and the pacing, which is the part
 * that must not be duplicated, and nothing else.
 *
 * <b>Paced through {@link Pace}, per host.</b> There is no published rate
 * limit; the API asks clients to identify themselves and behave. This address
 * was blocked once at the network layer for "behaviour patterns" and it took
 * an email to lift.
 *
 * <b>Filtered by nothing.</b> PENTAGON is a sibling of ZXSPECTRUM in ZXInfo's
 * scheme rather than a variant of it, so a machine filter silently drops the
 * Pentagon demoscene - most of what arrives as .trd and .scl. Every filter can
 * only lose the right answer.
 *
 * <b>mode=compact.</b> Measured on one game: 10,286 bytes against full's
 * 45,318, with every field this app reads byte-identical. tiny has no
 * controls.
 */
public final class ZxInfoCatalogue implements Catalogue {

    private static final String TAG = "Zedex";

    /** What a page of the grid asks for. Big enough that a screen's worth is
     *  one request, small enough that flinging past it does not pull a
     *  megabyte of JSON that nobody reads. */
    private static final int PAGE_SIZE = 30;

    static final String SHELF_SEARCH = "search";
    static final String SHELF_LETTER = "letter";
    static final String SHELF_NEWEST = "newest";
    static final String SHELF_RANDOM = "random";
    static final String SHELF_GENRES = "genres";

    /** A sub-shelf yielded by {@link #SHELF_GENRES} carries the genre as its
     *  id behind this prefix, so {@link #open} can tell one from a declared
     *  shelf without a second field. */
    private static final String GENRE_PREFIX = "genre:";

    private final Http http;

    public ZxInfoCatalogue(Http http) {
        this.http = http;
    }

    @Override
    public String name() {
        return "ZXInfo";
    }

    /** No credentials to be missing - which is why this is the catalogue that
     *  ships first. */
    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public List<Shelf> shelves() {
        return Arrays.asList(
                new Shelf(SHELF_SEARCH, "Search", Shelf.Accepts.TEXT),
                new Shelf(SHELF_LETTER, "A-Z", Shelf.Accepts.LETTER),
                new Shelf(SHELF_GENRES, "Categories", Shelf.Accepts.NOTHING),
                new Shelf(SHELF_NEWEST, "Newest", Shelf.Accepts.NOTHING),
                new Shelf(SHELF_RANDOM, "Surprise me", Shelf.Accepts.NOTHING));
    }

    @Override
    public Page open(Shelf shelf, Query query, int page) throws ScrapeException {
        if (SHELF_GENRES.equals(shelf.id())) return genres();

        return search(pathFor(shelf, query, page), page * PAGE_SIZE);
    }

    @Override
    public Item item(String id) throws ScrapeException {
        JSONObject source = object(ask("games/" + Uri.encode(id) + "?mode=compact"))
                .optJSONObject("_source");

        return source == null ? null : itemFrom(id, source, true);
    }

    @Override
    public ScrapeException refusalFor(int status) {
        // Deliberately the same kinds ZxInfo raises, and for the same
        // reason: ScrapeException.worthWaiting() is what tells a hiccup from
        // a wall, and a screen that treated them alike would tell somebody to
        // come back tomorrow over a 503.
        ...
    }
}
```

Implement the rest against these rules, each of which the test above pins:

1. **`pathFor`** builds `search?query=…&mode=compact&size=30&offset=<page*30>` for `SHELF_SEARCH`; the letter shelf per what Step 1 found; `SHELF_NEWEST` adds the sort the swagger names; `SHELF_RANDOM` uses `offset=random`, answers a `Page` whose total is `UNKNOWN_TOTAL`, **and answers an empty page for any `page > 0`**. `offset=random` is not random per call — two successive requests returned the identical ten entries — so a second page is the first page again. Without the empty-page rule, `UNKNOWN_TOTAL` plus a non-empty page makes `hasMore()` permanently true and the grid appends the same thirty rows for ever, one paced request per fling. A surprise is one page; the total stays unknown because there genuinely is no count. A genre sub-shelf is the ordinary search with `genretype=<the id after the prefix>`.
2. **`ask`** calls `Pace.before("api.zxinfo.dk", ZxInfo.MINIMUM_INTERVAL_MS)` then `http.get(ZxInfo.API + path)`, mapping a non-2xx through `refusalFor` and an `IOException` to a retryable `ScrapeException` — copy `ZxInfo.ask`'s body rather than inventing a second shape.
3. **`itemFrom`** reads `title`, `originalYearOfRelease`, the first of `publishers[].name`, `genreType`, `availability`, and the first `screens[]` entry as the picture. **Pass every screen path through a `hostOf`** that returns `ZxInfo.SCREENS` for a path starting `ZxInfo.SCREENS_PREFIX` and `ZxInfo.FILES` otherwise — this is the two-hosts rule and it is why the loading screens were all 404s before it was measured.
4. **Files** come from `releases[].files[]`. Each `path` that already starts `http` is used **as it is** — that is ZXDB's archive.org recordings; anything else is joined onto `hostOf(path)`. The format is derived from the JSON `format` field lower-cased, and where that is `zip`, from the inner extension of the path (`HeadOverHeels.tzx.zip` → `tzx`). Size comes from `size` or `-1`.
5. **`genres`** calls `metadata/`, reads the `genretypes` array (falling back to `genretype`, and to `Kinds.ZXDB_VOCABULARY` as a floor if the list is empty for any reason — an empty shelf list is indistinguishable on screen from a screen that failed to draw), and returns a `Page` of `Shelf(GENRE_PREFIX + key, key, Accepts.NOTHING)` with **no items** and `UNKNOWN_TOTAL`.
6. **Every `optString`/`optJSONArray` is guarded.** A field this app has never seen missing is a field the next dump drops.

- [ ] **Step 5: Run the test and watch it pass**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w -e class dev.ldlab.zedex.library.catalogue.ZxInfoCatalogueTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Expected: OK (14 tests).

- [ ] **Step 6: One live request, and only one**

With the app installed, add a temporary `@Test` that builds a `ZxInfoCatalogue(new Http.Real(context))` and opens the search shelf for `"head over heels"`, page 0, printing the item count and the first title to logcat. Run that one test, once. Confirm the count is non-zero and the title is real.

Then **delete the temporary test** and commit without it. This is the one live call the task makes: it proves the recorded bodies still match the shape the service sends, which no canned test can. Do not loop it, do not add more.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/catalogue/ZxInfoCatalogue.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ZxInfoCatalogueTest.java
git commit -m "feat: ZXInfo as somewhere to browse

Five shelves: search, A-Z, categories, newest and a random one. Categories
yields sub-shelves rather than items, which is the mechanism zxart's tree will
need, proved here against the genre list ZXInfo already publishes.

Filtered by nothing, on purpose. PENTAGON is a sibling of ZXSPECTRUM in this
scheme rather than a variant, so a machine filter silently drops the Pentagon
demoscene - most of what arrives as .trd and .scl.

The file urls are absolute and may be on a host neither of us chose: /pub/ is
on spectrumcomputing.co.uk and ZXDB's own recordings are on archive.org.
Joining every path onto one base is how every loading screen this app offered
was fetched from the wrong host and discarded as a 404, which looks exactly
like a game that has none.

A class of its own rather than more methods on ZxInfo: the two answer
different questions and share only the hosts and the pacing, which is the part
that must not be duplicated."
```

---

### Task 6: `Tree` — the SAF verbs, in one place

`EsDe.java` already does find-or-create-and-write through SAF, and every helper it uses is `private`. The importer needs the same three verbs, and a second private copy of them is how two callers come to disagree about what `"wt"` means.

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/storage/Tree.java`
- Create: `app/src/androidTest/java/dev/ldlab/zedex/storage/TreeTest.java`

**Interfaces:**
- Consumes: `Storage.contentFolder(Context)`.
- Produces: `Tree.folder(Context, Uri tree, String... names)` → `Uri` of the (created if absent) folder; `Tree.find(Context, Uri parent, String name)` → `Uri` or null; `Tree.write(Context, Uri parent, String name, File from)` → `Uri` or null.

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/dev/ldlab/zedex/storage/TreeTest.java`:

```java
package dev.ldlab.zedex.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeNotNull;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writing into somebody's own folder through the grant they gave.
 *
 * The route that makes this work on the Play build, which has no All-files
 * access - the same one ES-DE's two files already take. On a device because
 * there is no SAF anywhere else.
 *
 * The test needs a content folder to have been granted; without one it skips
 * rather than failing, since a bench with no folder chosen is a setup fact and
 * not a defect. It writes under a folder of its own and removes it afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class TreeTest {

    private static final String SCRATCH = "zedex-tree-test";

    private Context context;
    private Uri tree;
    private File source;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        tree = Storage.contentFolder(context);
        assumeNotNull("no content folder granted on this device", tree);

        source = new File(context.getCacheDir(), "tree-test.tap");
        try (FileOutputStream out = new FileOutputStream(source)) {
            out.write("not really a tape".getBytes(StandardCharsets.US_ASCII));
        }
    }

    @After
    public void tidyUp() {
        if (source != null) source.delete();
        if (tree == null) return;

        Uri scratch = Tree.find(context, Tree.folder(context, tree), SCRATCH);
        if (scratch != null) {
            try {
                android.provider.DocumentsContract.deleteDocument(
                        context.getContentResolver(), scratch);
            } catch (Exception ignored) {
                // A leftover folder is untidy, not a failure - and deleting
                // somebody's content folder because a delete went wide is
                // very much worse than untidy.
            }
        }
    }

    /** A folder that is not there is made. */
    @Test
    public void afolderIsCreatedWhenItIsAbsent() {
        Uri made = Tree.folder(context, tree, SCRATCH);

        assertNotNull("the folder was not created", made);
        assertNotNull(Tree.find(context, Tree.folder(context, tree), SCRATCH));
    }

    /**
     * And a second call finds the first one rather than making a second.
     *
     * SAF will happily create two documents with the same display name in the
     * same folder - the id is what is unique, not the name - so a
     * find-or-create that only creates gives somebody "Games" and "Games (1)"
     * and splits their imports across both.
     */
    @Test
    public void asecondCallFindsRatherThanMakesAsecond() {
        Uri first = Tree.folder(context, tree, SCRATCH);
        Uri second = Tree.folder(context, tree, SCRATCH);

        assertEquals("a second folder of the same name was made", first, second);
    }

    /** Several levels in one call, which is what Downloaded/Games/ is. */
    @Test
    public void afolderSeveralLevelsDownIsMadeInOneCall() {
        Uri deep = Tree.folder(context, tree, SCRATCH, "Downloaded", "Games");

        assertNotNull(deep);
        assertNotNull(Tree.find(context, Tree.folder(context, tree, SCRATCH, "Downloaded"),
                                "Games"));
    }

    /** A file written through the grant is there afterwards and has the
     *  bytes. */
    @Test
    public void afileIsWrittenAndCanBeReadBack() throws Exception {
        Uri into = Tree.folder(context, tree, SCRATCH);

        Uri written = Tree.write(context, into, "written.tap", source);

        assertNotNull(written);
        assertEquals(source.length(), lengthOf(written));
    }

    /** Nothing there is null rather than a throw - "is it already there" is a
     *  question the importer asks about every file. */
    @Test
    public void findingNothingIsNull() {
        Uri into = Tree.folder(context, tree, SCRATCH);

        assertNull(Tree.find(context, into, "nothing-of-that-name.tap"));
    }

    private long lengthOf(Uri document) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor =
                     context.getContentResolver().openFileDescriptor(document, "r")) {
            return descriptor == null ? -1 : descriptor.getStatSize();
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Build both APKs, install, and run `dev.ldlab.zedex.storage.TreeTest`.
Expected: FAIL to compile — `cannot find symbol: class Tree`.

- [ ] **Step 3: Write `Tree`**

Create `app/src/main/java/dev/ldlab/zedex/storage/Tree.java`, with `folder`, `find` and `write` public and the framework's verbosity kept inside:

```java
package dev.ldlab.zedex.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Find-or-create a folder under a granted tree, and write a file into it.
 *
 * The three SAF verbs this app needs, in one place. {@code EsDe} has its own
 * private copies and is left alone here - the point of this class is that the
 * <em>next</em> caller does not make a third set, not that the existing one is
 * rewritten in a task about something else.
 *
 * <b>Why SAF at all, when the app usually has All-files access:</b> the Play
 * build does not, and this is the route that works either way. It is the same
 * one ES-DE's two files already take.
 *
 * <b>Find before create, always.</b> SAF's uniqueness is the document id, not
 * the display name, so createDocument will cheerfully make a second "Games"
 * beside the first - after which imports land in whichever one the last call
 * happened to return, and half of somebody's collection is invisible from the
 * other.
 */
public final class Tree {

    private static final String TAG = "Zedex";

    private Tree() {
    }

    /**
     * The folder at the end of that path, made where it is absent.
     *
     * {@code parent} is a <b>document</b> uri, not a tree uri - which is
     * exactly what {@code Storage.contentFolder} already hands back, since it
     * builds the root document itself. Re-deriving one from the tree here
     * would work by luck for that caller and be wrong for every other level,
     * so it is not done: the parent is where the walk starts, whatever it is.
     * With no names that is the answer.
     *
     * @return null if any level could not be made, which on a tree the user
     *         granted means something is wrong with the grant rather than
     *         with the name.
     */
    public static Uri folder(Context context, Uri parent, String... names) {
        Uri at = parent;

        for (String name : names) {
            Uri existing = find(context, at, name);

            if (existing == null) {
                existing = create(context, at, DocumentsContract.Document.MIME_TYPE_DIR, name);
            }
            if (existing == null) {
                Log.w(TAG, "cannot reach " + name + " under " + at);
                return null;
            }

            at = existing;
        }

        return at;
    }

    /** The child of that folder with that display name, or null. */
    public static Uri find(Context context, Uri parent, String name) {
        if (parent == null || name == null) return null;

        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                parent, DocumentsContract.getDocumentId(parent));
        ContentResolver resolver = context.getContentResolver();

        try (Cursor cursor = resolver.query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (cursor == null) return null;

            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent,
                                                                       cursor.getString(0));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot look inside " + parent, e);
        }

        return null;
    }

    /**
     * Copies a local file into that folder under that name.
     *
     * The file is written whole and the document is deleted if the copy fails
     * part way: a half-written .tap is indistinguishable from a real one, and
     * the emulator's refusal to load it reads as a broken download of a
     * working game.
     *
     * @return the document written, or null.
     */
    public static Uri write(Context context, Uri parent, String name, File from) {
        Uri document = create(context, parent, "application/octet-stream", name);
        if (document == null) return null;

        try (InputStream in = new FileInputStream(from);
             OutputStream out = context.getContentResolver().openOutputStream(document, "wt")) {

            if (out == null) throw new java.io.IOException("no stream for " + document);

            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, read);
            }
            return document;

        } catch (Exception e) {
            Log.w(TAG, "cannot write " + name, e);
            delete(context, document);
            return null;
        }
    }

    private static Uri create(Context context, Uri parent, String mime, String name) {
        try {
            return DocumentsContract.createDocument(context.getContentResolver(),
                                                    parent, mime, name);
        } catch (Exception e) {
            Log.w(TAG, "cannot create " + name, e);
            return null;
        }
    }

    private static void delete(Context context, Uri document) {
        try {
            DocumentsContract.deleteDocument(context.getContentResolver(), document);
        } catch (Exception e) {
            Log.w(TAG, "cannot remove the half-written " + document, e);
        }
    }
}
```

Note the `"wt"` on `openOutputStream`: without truncate, a shorter document written over a longer one keeps the old tail — the bug that made ES-DE's files parse as neither.

- [ ] **Step 4: Run the test and watch it pass**

Run `dev.ldlab.zedex.storage.TreeTest`.
Expected: OK (5 tests). If every test skips, the bench has no content folder granted — choose one in the app first, then re-run. A run that skips everything has proved nothing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/storage/Tree.java \
        app/src/androidTest/java/dev/ldlab/zedex/storage/TreeTest.java
git commit -m "feat: the three SAF verbs, in one place

Find-or-create a folder under a granted tree, and write a file into it. EsDe
has private copies of all three and keeps them; the point is that the next
caller does not make a third set and then disagree with both about what 'wt'
means.

Find before create, always: SAF's uniqueness is the document id and not the
display name, so createDocument will make a second 'Games' beside the first -
after which half of somebody's imports are invisible from the other one.

And the write is whole or not at all. A half-written .tap is indistinguishable
from a real one, and the emulator refusing to load it reads as a broken
download of a working game."
```

---

### Task 7: `Imports` — cache, check, unzip, write

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/catalogue/Imports.java`
- Create: `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ImportsTest.java`

**Interfaces:**
- Consumes: `Catalogue.Item`, `Catalogue.Download`, `Pick`, `Kinds`, `Http`, `Tree`, `Storage.contentFolder(Context)`, `Types.openable(String)`.
- Produces: `Imports.Result` (fields `documentUri`, `displayName`, `folder`, `alreadyThere`, `failure`); `Imports.game(Context, Http, Catalogue.Item, Catalogue.Download)`; `Imports.recording(Context, Http, Catalogue.Item, Catalogue.Download)`; `Imports.nameInside(String url, List<String> insideNames)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ImportsTest.java`. It fakes `Http.save` by writing a zip it built itself, so no network is touched:

```java
package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.storage.Tree;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bringing a file in from somewhere else.
 *
 * On a device because the destination is SAF and the source is a zip, and
 * neither exists on the JVM. Nothing here touches the network: the fake Http
 * writes a zip this test built, with exactly the awkward contents worth asking
 * about - which is not something a real archive can be relied on to have.
 *
 * <b>Every fixture is named uniquely and only its own documents are removed.</b>
 * The obvious tidy-up - delete Downloaded/Games/ and start clean - deletes
 * real games on any bench that has imported one by hand, and a test that can
 * destroy the collection it is run against is not a test anybody will run
 * twice. So each run stamps its names with nanoTime and the @After deletes
 * the uris this run created, which also fixes the other half of the problem:
 * a run killed by hand never reaches its @After, and a leftover under a
 * unique name collides with nothing.
 */
@RunWith(AndroidJUnit4.class)
public class ImportsTest {

    /** New every run, so a killed run's leftovers collide with nothing and
     *  nothing this test deletes was ever somebody's own. */
    private final String stamp = "zedex-" + System.nanoTime();

    private Context context;
    private Uri tree;
    private final List<Uri> made = new ArrayList<>();

    /** Writes a zip of whatever it was told to hold, in place of a download. */
    private final class Zipped implements Http {
        private final String[] names;

        Zipped(String... names) {
            this.names = names;
        }

        @Override
        public Reply get(String url) {
            throw new UnsupportedOperationException("not this test's business");
        }

        @Override
        public String save(String url, File into) throws java.io.IOException {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(into))) {
                for (String name : names) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(("contents of " + name).getBytes(StandardCharsets.US_ASCII));
                    zip.closeEntry();
                }
            }
            return "00000000000000000000000000000000";
        }
    }

    private static Catalogue.Item item(String kind, Catalogue.Download... files) {
        return new Catalogue.Item("0002259", "Head over Heels", "1987", "Ocean",
                                  kind, "Available", null,
                                  Collections.singletonList(
                                          new Catalogue.Version(null, "1987",
                                                                Arrays.asList(files))));
    }

    private static Catalogue.Download download(String format, long size) {
        return new Catalogue.Download("https://example/HeadOverHeels." + format + ".zip",
                                      format, size);
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        tree = Storage.contentFolder(context);
        assumeNotNull("no content folder granted on this device", tree);
    }

    /**
     * Removes what this run made, and nothing else.
     *
     * Never the kind folders themselves - Downloaded/Games is the feature's
     * own folder and may hold games somebody imported on purpose.
     */
    @After
    public void tidyUp() {
        for (Uri document : made) {
            try {
                android.provider.DocumentsContract.deleteDocument(
                        context.getContentResolver(), document);
            } catch (Exception ignored) {
                // A leftover under a nanoTime name collides with nothing;
                // untidy is not a failure.
            }
        }
    }

    // --- the ordinary case --------------------------------------------------------------

    /**
     * A zip of one file yields that file, named as it is named inside.
     *
     * The archive ships HeadOverHeels.tzx.zip holding HeadOverHeels.tzx, and
     * it is the inner name that is already unique and TOSEC-ish. The store
     * keys on path, so an import and a hand-copied file look identical to
     * everything downstream.
     */
    @Test
    public void azipOfOneFileYieldsThatFileUnderItsInnerName() {
        Imports.Result result = Imports.game(
                context, new Zipped("HeadOverHeels.tzx"),
                item("Arcade Game", download("tzx", 41232)),
                download("tzx", 41232));

        assertNull(result.failure);
        assertEquals("HeadOverHeels.tzx", result.displayName);
        assertEquals(Kinds.GAMES, result.folder);
        assertNotNull(result.documentUri);
    }

    /** And it lands under Downloaded/<kind>/, which is the whole point of not
     *  writing into the root of somebody's collection. */
    @Test
    public void itlandsUnderDownloadedAndTheKindSfolder() {
        Imports.game(context, new Zipped("Tasword.tap"),
                     item("Utility", download("tap", 900)), download("tap", 900));

        Uri downloaded = Tree.find(context, Tree.folder(context, tree), "Downloaded");
        assertNotNull("no Downloaded folder", downloaded);
        assertNotNull("no Applications folder",
                      Tree.find(context, downloaded, Kinds.APPLICATIONS));
    }

    // --- several files ------------------------------------------------------------------

    /**
     * A multi-load game becomes a folder named after it.
     *
     * Several openable files and nowhere to put a second - the library already
     * browses folders, so that is where they go.
     */
    @Test
    public void azipOfSeveralOpenableFilesBecomesAfolder() {
        Imports.Result result = Imports.game(
                context, new Zipped("Robocop - Side 1.tap", "Robocop - Side 2.tap"),
                item("Arcade Game", download("tap", 100000)), download("tap", 100000));

        assertNull(result.failure);
        assertEquals("Head over Heels", result.displayName);

        Uri downloaded = Tree.find(context, Tree.folder(context, tree), "Downloaded");
        Uri games = Tree.find(context, downloaded, Kinds.GAMES);
        Uri folder = Tree.find(context, games, "Head over Heels");

        assertNotNull("no folder for the multi-load game", folder);
        assertNotNull(Tree.find(context, folder, "Robocop - Side 1.tap"));
        assertNotNull(Tree.find(context, folder, "Robocop - Side 2.tap"));
    }

    /** Anything in the zip that this app cannot open is left behind - a
     *  readme is not a game and a folder full of them is not a library. */
    @Test
    public void whatCannotBeOpenedIsLeftInTheZip() {
        Imports.Result result = Imports.game(
                context, new Zipped("readme.txt", "HeadOverHeels.tzx", "cover.jpg"),
                item("Arcade Game", download("tzx", 41232)), download("tzx", 41232));

        assertEquals("HeadOverHeels.tzx", result.displayName);
    }

    /** A zip with nothing openable in it is a failure with a reason, not a
     *  file written with a name and no contents. */
    @Test
    public void azipOfNothingUsableIsArefusal() {
        Imports.Result result = Imports.game(
                context, new Zipped("readme.txt"),
                item("Arcade Game", download("tzx", 41232)), download("tzx", 41232));

        assertNotNull("nothing openable was accepted", result.failure);
        assertNull(result.documentUri);
    }

    // --- already there ---------------------------------------------------------------------

    /**
     * A second import of the same thing says so rather than writing a second
     * copy.
     *
     * SAF would happily make "HeadOverHeels (1).tzx", which is how a
     * collection acquires four of everything and how somebody comes to think
     * the first import failed.
     */
    @Test
    public void asecondImportOfTheSameThingSaysSo() {
        Imports.game(context, new Zipped("HeadOverHeels.tzx"),
                     item("Arcade Game", download("tzx", 41232)), download("tzx", 41232));

        Imports.Result again = Imports.game(
                context, new Zipped("HeadOverHeels.tzx"),
                item("Arcade Game", download("tzx", 41232)), download("tzx", 41232));

        assertTrue("a second copy was written", again.alreadyThere);
        assertNull(again.failure);
        assertNotNull("nothing to open", again.documentUri);
    }

    // --- recordings -------------------------------------------------------------------------

    /**
     * A recording goes to Recordings whatever the entry's genre says.
     *
     * The one place a file's kind outranks the entry's category: the folder
     * scheme answers "what kind of thing is this file", and a recording of
     * Bomb Jack in the Games folder is not Bomb Jack.
     */
    @Test
    public void arecordingGoesToRecordingsWhateverTheGenreSays() {
        Imports.Result result = Imports.recording(
                context, new Zipped("HeadOverHeels.rzx"),
                item("Arcade Game", download("rzx", 190222)), download("rzx", 190222));

        assertNull(result.failure);
        assertEquals(Kinds.RECORDINGS, result.folder);
    }

    // --- a short download ----------------------------------------------------------------------

    /**
     * Short means discarded, before anything is unpacked.
     *
     * ZXDB gives a size per file and no checksum, so length is what there is -
     * and a truncated zip that unpacks to half a tape is a game that loads and
     * then crashes, which nobody attributes to the download.
     */
    @Test
    public void adownloadThatArrivedShortIsThrownAway() {
        Imports.Result result = Imports.game(
                context, new Zipped("HeadOverHeels.tzx"),
                item("Arcade Game", download("tzx", 9_000_000)),
                download("tzx", 9_000_000));

        assertNotNull("a short download was accepted", result.failure);
        assertNull(result.documentUri);
    }

    /** A catalogue that does not say how big is not a reason to refuse - most
     *  of them do not. */
    @Test
    public void anunstatedSizeIsNotAfailure() {
        Imports.Result result = Imports.game(
                context, new Zipped("HeadOverHeels.tzx"),
                item("Arcade Game", download("tzx", -1)), download("tzx", -1));

        assertNull(result.failure);
        assertNotNull(result.documentUri);
    }

    // --- nothing is left behind -----------------------------------------------------------------

    /** The cache is empty afterwards, however it went. A failed import that
     *  leaves a megabyte behind per attempt is a disk that fills up for a
     *  reason nobody can find. */
    @Test
    public void thecacheIsEmptyAfterwards() {
        File cache = new File(context.getCacheDir(), "imports");

        Imports.game(context, new Zipped("readme.txt"),
                     item("Arcade Game", download("tzx", 41232)), download("tzx", 41232));

        File[] left = cache.listFiles();
        assertTrue("the cache kept " + (left == null ? 0 : left.length) + " files",
                   left == null || left.length == 0);
    }

    /** Remembers what a call wrote, so tidyUp removes that and only that. */
    private Imports.Result kept(Imports.Result result) {
        if (result != null && result.documentUri != null && !result.alreadyThere) {
            made.add(result.documentUri);
        }
        return result;
    }
}
```

Every `Imports.game(...)` / `Imports.recording(...)` call in the tests above is wrapped in `kept(...)`, and every fixture name is built from `stamp` — `stamp + "-HeadOverHeels.tzx"` rather than the bare name. Adjust the assertions to match: they assert on `result.displayName` and on `Tree.find(..., stamp + "-…")`, not on a fixed string.

The one test that needs a name twice is `asecondImportOfTheSameThingSaysSo` — it uses the same `stamp`-prefixed name for both calls, which is the point of it.

- [ ] **Step 2: Run it and watch it fail**

Build both, install, run `dev.ldlab.zedex.library.catalogue.ImportsTest`.
Expected: FAIL to compile — `cannot find symbol: class Imports`.

- [ ] **Step 3: Write `Imports`**

Create `app/src/main/java/dev/ldlab/zedex/library/catalogue/Imports.java`. The order of operations is the design and must not be rearranged:

1. **Into the cache first** — `new File(context.getCacheDir(), "imports")`, a name from `System.nanoTime()`. SAF writes are not atomic and a half-written `.tap` is indistinguishable from a real one; fetched, checked, and only then created in the tree, so a failed download leaves nothing behind.
2. **`http.save(file.url(), cached)`**, then **check the length against `file.size()`** where the catalogue stated one. Short → discard and return a failure. `size() < 0` → accept.
3. **Unzip into the cache**, keeping only names for which `Types.openable(name)`. A `.rzx` import keeps the `.rzx` — `Types.OPENABLE` already contains it.
4. **One openable file inside** → that file, under its own inner name. **Several** → a folder named after the item's title, holding all of them. **None** → a failure with a reason.
5. **`Tree.folder(context, tree, "Downloaded", folder)`** where `folder` is `Kinds.RECORDINGS` for `Imports.recording` and `Kinds.folderFor(item.kind())` for `Imports.game`. That inversion is the one place a file's kind outranks the entry's category, and it belongs here rather than in `Kinds`.
6. **`Tree.find` before `Tree.write`** — already there is not an error: return `alreadyThere` with the existing document so the screen can offer to open it.
7. **Delete every cache file in a `finally`.**

`Imports.Result` is a plain final class with public final fields and no logic; `failure` is a `ScrapeException` or null so the screen can say *why* with the same three kinds it already knows.

- [ ] **Step 4: Run the test and watch it pass**

Run `dev.ldlab.zedex.library.catalogue.ImportsTest`.
Expected: OK (10 tests).

- [ ] **Step 5: Prove the short-download check is not vacuous**

Comment out the length check and re-run.
Expected: `adownloadThatArrivedShortIsThrownAway` FAILS.
Restore it.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/catalogue/Imports.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ImportsTest.java
git commit -m "feat: bring a file in from the catalogue

Cache, check the length, unpack, then write through SAF - in that order,
because SAF writes are not atomic and a half-written .tap is indistinguishable
from a real one. A failed import leaves nothing behind, including in the
cache.

Named as the file inside the zip is named, not as the zip is: the archive
ships HeadOverHeels.tzx.zip holding HeadOverHeels.tzx, and the inner name is
already unique and TOSEC-ish. The store keys on path, so an import and a
hand-copied file look identical to everything downstream.

A multi-load game becomes a folder the library already browses. Already there
is not an error - SAF would make 'HeadOverHeels (1).tzx' and somebody would
conclude the first import failed.

And a recording goes to Recordings whatever the entry's genre says. That is
the one place a file's kind outranks the entry's category, because the folder
scheme answers what kind of thing a file is, and a recording of Bomb Jack in
the Games folder is not Bomb Jack."
```

---

### Task 8: The import reaches the scraper and the caches

An imported game that appears as a filename is worse than one imported by hand — the app had the record in memory and threw it away.

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/library/catalogue/Imports.java`
- Create: `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ImportScrapeTest.java`

**Interfaces:**
- Consumes: `Metadata.relativePath(Context, Uri)`, `Metadata.ensureLoaded(Context)`, `Scrape.apply(Context, Provider, Http, Candidate, String, Provider.Wanted)`, `Scrapers.preferred(Context)`, `Scrapers.wanted(Context)`, `Artwork.forget(String)`, `Candidate`.
- Produces: `Imports.describe(Context, Http, Imports.Result, Catalogue.Item)` → `Downloads.Result` or null.

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ImportScrapeTest.java`, asserting the one thing that matters and is easy to get wrong — **the entry id is handed straight through, with no second search**:

```java
package dev.ldlab.zedex.library.catalogue;

/**
 * The two seams meet at the entry id.
 *
 * Provider.fetch already takes a Candidate whose handle IS the catalogue's
 * entry id, so an import hands that id straight to the scraper: no second
 * search, no name matching, and a certainty a name match can never promise -
 * on a Spectrum collection full of hacks and re-releases, a name match is as
 * often wrong as right.
 *
 * What this pins is that the handle arrives unchanged and that `exact` is
 * true, since a Candidate that says it is a guess sends the scrape back
 * through a dialog asking somebody to confirm the game they just chose.
 */
```

Fake the `Provider` with one that records the `Candidate` it was given and returns a `Scraped` with a known `Meta` and no media, then assert `candidate.handle` equals the item id and `candidate.exact` is true. Assert that `Metadata.forPath` answers with that `Meta` afterwards.

- [ ] **Step 2: Run it and watch it fail**

Expected: FAIL to compile — `cannot find symbol: method describe`.

- [ ] **Step 3: Write `Imports.describe`**

```java
    /**
     * Details and artwork for what was just imported.
     *
     * <b>The entry id goes straight through.</b> Provider.fetch takes a
     * Candidate whose handle is the catalogue's own id, so this builds one
     * around the id already in hand rather than searching by the name of a
     * file it has only this second written. That is the difference between
     * certainty and a guess, and a guess acted on silently is one game's cover
     * on another for ever.
     *
     * {@code exact} is true for the same reason: a Candidate that admits to
     * guessing sends the flow back through a dialog asking somebody to confirm
     * the game they have just chosen off a list.
     *
     * Returns null when there is no configured provider, which is not a
     * failure - the file is imported either way and details are the extra.
     */
    public static Downloads.Result describe(Context context, Http http,
                                            Result result, Catalogue.Item item) {
        ...
    }
```

It must, in order: return null if `Scrapers.preferred(context)` is null; `Metadata.ensureLoaded(context)` — **the store does not read itself**, and a game imported before the library has run in this process would otherwise resolve against an empty cache and read as unscraped; get the relative path with `Metadata.relativePath(context, result.documentUri)`; build `new Candidate(item.id(), item.title(), item.year(), item.publisher(), true)`; call `Scrape.apply(...)` with `Scrapers.wanted(context)`.

`relativePath` answers null for anything outside the granted content tree, and an import is inside it by construction — but check for null anyway and return null rather than throwing: the one way it can happen is somebody re-granting a different folder mid-import, and a crash there is worse than an uncovered game.

Run it on `Work.alone`, not `Work.run` — a download plus a scrape plus up to three media fetches is a long job, and `Work.alone` is what `ScrapeOneGame` uses for exactly this shape. `Work.run`'s pool is for the short things the screen does.

Then, whatever it returns, the **caller** must clear the caches — the tree listing and `Artwork` are both keyed by path and a new file neither knows about is invisible until they are cleared. `Artwork.forget(path)` is already called inside `Downloads.fetch` when something arrived; the listing is `LibraryActivity`'s and is refreshed in Task 12.

- [ ] **Step 4: Run the test and watch it pass**

Expected: OK.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/catalogue/Imports.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ImportScrapeTest.java
git commit -m "feat: an import arrives with its cover on

The two seams meet at the entry id. Provider.fetch already takes a Candidate
whose handle IS the catalogue's own id, so the import hands that straight
through - no second search, no name matching, and a certainty a name match can
never promise on a collection full of hacks and re-releases.

The candidate is marked exact for the same reason: one that admits to guessing
sends the flow back through a dialog asking somebody to confirm the game they
have just picked off a list.

ensureLoaded first, because the metadata store does not read itself - a game
imported before the library has run in this process would resolve against an
empty cache and read as unscraped, silently."
```

---

### Task 9: `Thumbnails` — pictures for rows on screen

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/catalogue/Thumbnails.java`
- Create: `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ThumbnailsTest.java`

**Interfaces:**
- Consumes: `Http`, `Work.run`, `Work.onMain`.
- Produces: `Thumbnails.get(String url)` → `Bitmap` or null (cache only, never blocks); `Thumbnails.load(Context, Http, String url, Listener)`; `Thumbnails.forget()`; `interface Thumbnails.Listener { void ready(String url, Bitmap picture); }`.

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ThumbnailsTest.java`:

```java
package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.ldlab.zedex.library.scrape.Http;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Covers for the rows that are on screen.
 *
 * On a device because a Bitmap is Android's and because the bound that matters
 * is a fraction of this process's own heap. What is pinned here is the
 * request count rather than the picture: a grid flung past a row and back is
 * the ordinary case, and a cache that misses on the way back doubles every
 * fetch - which against an address that blocks on behaviour patterns is not a
 * performance question.
 */
@RunWith(AndroidJUnit4.class)
public class ThumbnailsTest {

    private static final String URL = "https://example/covers/HeadOverHeels.jpg";

    private Context context;

    /** Writes a 1x1 png, and counts. */
    private static final class OnePixel implements Http {
        final List<String> asked = new ArrayList<>();

        @Override
        public Reply get(String url) {
            throw new UnsupportedOperationException("not this test's business");
        }

        @Override
        public synchronized String save(String url, File into) throws java.io.IOException {
            asked.add(url);

            Bitmap dot = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            try (FileOutputStream out = new FileOutputStream(into)) {
                dot.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return "00000000000000000000000000000000";
        }
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        Thumbnails.forget();
    }

    /** A miss answers null immediately - the adapter draws a placeholder and
     *  gets on with binding the next row, rather than blocking layout on the
     *  network. */
    @Test
    public void amissIsNullAndDoesNotBlock() {
        assertNull(Thumbnails.get(URL));
    }

    /** What arrives is cached, and asking again costs nothing. */
    @Test
    public void whatArrivesIsCachedAndNotFetchedTwice() throws Exception {
        OnePixel http = new OnePixel();
        CountDownLatch arrived = new CountDownLatch(1);

        Thumbnails.load(context, http, URL, (url, picture) -> arrived.countDown());
        assertEquals("the picture never arrived", true,
                     arrived.await(10, TimeUnit.SECONDS));

        assertNotNull("it was not cached", Thumbnails.get(URL));

        Thumbnails.load(context, http, URL, (url, picture) -> { });
        assertEquals("it was fetched a second time", 1, http.asked.size());
    }

    /**
     * <b>Two rows wanting the same picture make one request.</b>
     *
     * Not a nicety: the same cover appears in a search result and again in
     * whatever list it was reached from, and a grid scrolled past a row and
     * back asks again before the first answer has landed. One request per url
     * in flight is what keeps that from being two.
     */
    @Test
    public void thesameUrlAskedTwiceAtOnceIsOneRequest() throws Exception {
        OnePixel http = new OnePixel();
        CountDownLatch both = new CountDownLatch(2);

        Thumbnails.load(context, http, URL, (url, picture) -> both.countDown());
        Thumbnails.load(context, http, URL, (url, picture) -> both.countDown());

        assertEquals("both callers were not told", true,
                     both.await(10, TimeUnit.SECONDS));
        assertEquals("the same picture was fetched twice", 1, http.asked.size());
    }

    /** A url of nothing is not a request. Plenty of catalogue entries have no
     *  picture at all, and those rows are text rows. */
    @Test
    public void nourlIsNorequest() {
        OnePixel http = new OnePixel();

        Thumbnails.load(context, http, null, (url, picture) -> { });

        assertEquals(0, http.asked.size());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

- [ ] **Step 3: Write `Thumbnails`**

An `LruCache<String, Bitmap>` sized from `Runtime.getRuntime().maxMemory() / 8`, a `Set<String>` of urls in flight, `Work.run` for the fetch and `Work.onMain` for the callback. `BitmapFactory.Options.inSampleSize` from the requested width, as `PictureCache.decode` already does — a full-size inlay scan in a 140dp row is the same measured mistake that cost 1.9 GB once.

**Not paced.** These are static files on an ordinary web host, not API calls, and throttling image loads to two a second would make the grid unusable and buy nobody anything. They carry the identity header because `Http.Real` sends it on everything.

- [ ] **Step 4: Run the test and watch it pass**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/catalogue/Thumbnails.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ThumbnailsTest.java
git commit -m "feat: covers for the rows that are on screen

A bounded url-keyed cache over Http, one request per url however many rows ask
for it - a grid flung past a row and back is the ordinary case, and doubling
every fetch for it is exactly the behaviour pattern that got this app's
address blocked once.

Not paced: these are static files on an ordinary web host rather than API
calls, and throttling them to two a second would make the grid unusable and
buy nobody anything. Sampled down on decode, because a full-size inlay scan in
a 140dp row is the measured mistake that cost 1.9 GB."
```

---

### Task 10: `CatalogueAdapter` and `CatalogueView` — the screen

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/ui/CatalogueAdapter.java`
- Create: `app/src/main/java/dev/ldlab/zedex/library/ui/CatalogueView.java`
- Create: `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/CatalogueScreenTest.java`
- Modify: `app/src/main/res/values/strings.xml` and all eight `values-*/strings.xml`

**Interfaces:**
- Consumes: `Catalogue`, `Thumbnails`, `Work`.
- Produces: `CatalogueView(Context, Catalogue, Host)` where `Host` is `{ void chosen(Catalogue.Item item); }`; `CatalogueView.onBack()` → boolean; `CatalogueAdapter` with `setRows(List<Object>)` and `Callbacks { void openShelf(Catalogue.Shelf); void openItem(Catalogue.Item); void retry(); }`.

- [ ] **Step 1: Add the strings, in nine files**

| Key | English | cs | de | es | fr | it | pl | ru | uk |
|---|---|---|---|---|---|---|---|---|---|
| `library_tab_catalogue` | Catalogue | Katalog | Katalog | Catálogo | Catalogue | Catalogo | Katalog | Каталог | Каталог |
| `catalogue_search_hint` | Search the catalogue | Hledat v katalogu | Katalog durchsuchen | Buscar en el catálogo | Rechercher dans le catalogue | Cerca nel catalogo | Szukaj w katalogu | Поиск по каталогу | Пошук у каталозі |
| `catalogue_empty` | Nothing here. | Nic tu není. | Nichts hier. | No hay nada aquí. | Rien ici. | Niente qui. | Nic tu nie ma. | Здесь ничего нет. | Тут нічого немає. |
| `catalogue_failed` | That did not arrive. | Nepodařilo se načíst. | Das kam nicht an. | No se ha recibido. | Cela n'est pas arrivé. | Non è arrivato. | Nie udało się pobrać. | Не удалось получить. | Не вдалося отримати. |
| `catalogue_retry` | Try again | Zkusit znovu | Erneut versuchen | Reintentar | Réessayer | Riprova | Spróbuj ponownie | Повторить | Повторити |

Then `python3 scripts/check-strings.py` must print `8 translations agree with values/`.

- [ ] **Step 2: Write the failing test**

Create `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/CatalogueScreenTest.java` — a UI Automator test that launches the library, taps the Catalogue tab, types a search, and asserts a row appears. Two things this codebase has learned apply directly:

- **Wait for the condition, never for a duration.** A page arrives when the network says so; poll for the row count, as `FilterTest.awaitRowCount` does. A sample taken after a sleep passes alone and fails behind three other classes.
- **Assert the app is what is resumed.** A picker or settings page left open by hand outlives `am force-stop` and every reading afterwards is of the wrong screen; use `Screen.assertHere()`.

The test skips itself when there is no network, on a fact rather than a wait — `ConnectivityManager.getActiveNetwork() == null` — because a skip decided by a timeout is how `NewDiskTest` came to pass in twenty seconds having formatted nothing.

- [ ] **Step 3: Write `CatalogueAdapter`**

One adapter, three row types: a shelf, an item, and the failed-page row. Modelled on `EntryAdapter` but its own class — `EntryAdapter` is written throughout in terms of `Entry`, which is a file with a `Uri`, a size and a modified time, and a catalogue item is none of those. Forcing one into the other means four fields that lie.

- An **item row** draws the title, `Item.describe()` as the detail line, and `Thumbnails.get(item.pictureUrl())` — requesting it through `Thumbnails.load` when it is a miss, and `notifyItemChanged` on the callback. **A null picture url is a text row**, exactly as an unscraped local game already is; it is not a placeholder waiting for something that will never come.
- The bind carries a **staleness token**, as `EntryAdapter.onBindViewHolder` does (`int token = ++holder.bindToken;`, checked in the callback). A recycled holder whose old picture lands afterwards puts one game's cover on another, which is the failure this app takes most seriously.
- An **unavailable item** is drawn at 50% alpha with `availability()` as its detail line. It is still tappable: the reason is worth reading.
- **Never print `total` as a count when it is exactly 10,000.** Measured in Task 5: ZXInfo's search answers `total=10000` against a database of ~39,666, because that is Elasticsearch's counting cap and its paging window at once. It is the right number for `hasMore` to page against and a lie to show somebody — "10,000 results" for a search that matched four times that many reads as a broken filter. Show the count only below the cap.
- **Grey only what the catalogue actually calls unavailable.** `Item.available()` answers "is this definitely available", so it reads an *absent* `availability` as false — correct for that question, wrong for this one. Measured on a live ZXInfo reply during Task 5: one row of three omitted the field entirely, and it was a 2024 release. Greying it would tell somebody a game they can have is missing, with no reason given, because a field was absent. So the row greys on **stated and not available** — `availability() != null && !available()` — and an unstated availability draws normally. Two questions, two predicates; this codebase has been bitten by one predicate answering two questions before.
- The **failed row** carries `catalogue_failed` and a `catalogue_retry` button. What already arrived stays above it — a page that fails must not empty the grid.

- [ ] **Step 4: Write `CatalogueView`**

A `FrameLayout` holding a search field, a `RecyclerView` and a shelf stack (`ArrayDeque<Shelf>`), owning:

- **the stack**: opening a sub-shelf pushes; `onBack()` pops and returns true, or returns false when it is at the roots so the activity handles Back;
- **paging on scroll**:

  ```java
      recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
          @Override
          public void onScrolled(RecyclerView view, int dx, int dy) {
              if (dy <= 0 || inFlight || !hasMore) return;

              RecyclerView.LayoutManager manager = view.getLayoutManager();
              int total = manager.getItemCount();
              int last = manager instanceof LinearLayoutManager
                      ? ((LinearLayoutManager) manager).findLastVisibleItemPosition()
                      : 0;

              // One screen's warning, not one row's - fetching at the very
              // last row means the grid stops dead while the request goes
              // out, which reads as a catalogue that has ended.
              if (last >= total - AHEAD) nextPage();
          }
      });
  ```

  `AHEAD` is the number of rows a screen holds, not a magic constant — measure it from the layout manager rather than guessing. `inFlight` and `hasMore` are the two guards: without the first a fling sends four identical requests, and without the second the end of a list asks for ever;
- **every fetch on `Work.run`**, never the UI thread, with the result posted back with a token compared on arrival — the same shape `LibraryActivity.load()` uses, and for the same reason: somebody types a second search before the first has answered.

- [ ] **Step 5: Run the test and watch it pass**

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/ui/CatalogueAdapter.java \
        app/src/main/java/dev/ldlab/zedex/library/ui/CatalogueView.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/CatalogueScreenTest.java \
        app/src/main/res/values*/strings.xml
git commit -m "feat: the catalogue, on screen

Shelves you descend into, items you tap, and paging that happens because you
scrolled - nothing fetched speculatively, which is the difference between a
client and a crawler.

Its own adapter rather than EntryAdapter's: that one is written throughout in
terms of an Entry, which is a file with a uri, a size and a modified time, and
a catalogue item is none of those. Forcing one into the other means four
fields that lie.

An unavailable entry stays on the list, greyed, with the service's own word
for why. A catalogue that silently omits things looks broken, and 'announced
and cancelled' is a fact about a game worth reading. A failed page is a row
with a retry under it, so what already arrived stays."
```

---

### Task 11: `CataloguePane` — Import, other versions, play the recording

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/library/ui/CataloguePane.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/library/ui/CatalogueView.java`
- Modify: `app/src/main/res/values/strings.xml` and all eight `values-*/strings.xml`
- Create: `app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ImportFlowTest.java`

- [ ] **Step 1: Add the strings, in nine files**

| Key | English | cs | de | es | fr | it | pl | ru | uk |
|---|---|---|---|---|---|---|---|---|---|
| `catalogue_import` | Import | Importovat | Importieren | Importar | Importer | Importa | Importuj | Импортировать | Імпортувати |
| `catalogue_versions` | Other versions… | Další verze… | Andere Versionen… | Otras versiones… | Autres versions… | Altre versioni… | Inne wersje… | Другие версии… | Інші версії… |
| `catalogue_recording` | Play the recording | Přehrát záznam | Aufzeichnung abspielen | Reproducir la grabación | Lire l'enregistrement | Riproduci la registrazione | Odtwórz nagranie | Воспроизвести запись | Відтворити запис |
| `catalogue_importing` | Importing %1$s… | Importuje se %1$s… | %1$s wird importiert… | Importando %1$s… | Importation de %1$s… | Importazione di %1$s… | Importowanie %1$s… | Импорт %1$s… | Імпорт %1$s… |
| `catalogue_imported` | %1$s imported | %1$s importováno | %1$s importiert | %1$s importado | %1$s importé | %1$s importato | Zaimportowano %1$s | %1$s импортирован | %1$s імпортовано |
| `catalogue_already` | %1$s is already in your library | %1$s už v knihovně je | %1$s ist bereits in der Bibliothek | %1$s ya está en tu biblioteca | %1$s est déjà dans votre bibliothèque | %1$s è già nella tua libreria | %1$s jest już w bibliotece | %1$s уже в библиотеке | %1$s уже в бібліотеці |
| `catalogue_nothing_to_get` | Nothing here the Spectrum can open. | Nic, co by Spectrum otevřelo. | Nichts, was der Spectrum öffnen kann. | Nada que el Spectrum pueda abrir. | Rien que le Spectrum puisse ouvrir. | Niente che lo Spectrum possa aprire. | Nic, co Spectrum mógłby otworzyć. | Здесь нет ничего, что откроет Spectrum. | Тут немає нічого, що відкриє Spectrum. |

`%1$s` is `%1$s` in every one of the nine — `check-strings.py` fails on a disagreeing specifier, and `%1$s` against `%1$d` is a `ClassCastException` only a non-English reader ever sees.

- [ ] **Step 2: Write the failing test**

`ImportFlowTest`: search, tap a result, tap Import, and assert the file appears under `Downloaded/Games/`. Skip on no network, on the fact. Clear leftovers on the way **in** as well as out.

- [ ] **Step 3: Write `CataloguePane`**

Its own class, positioned where `DetailPane` sits and styled to match — see *Two places this plan differs from the spec*. It shows the item's picture, title, year, publisher, kind and availability, and up to three buttons:

- **Import** — enabled when `Pick.forGame(item) != null`, replaced by `catalogue_nothing_to_get` when not. Runs `Imports.game` then `Imports.describe` on `Work.run`, with a progress line; on return, `catalogue_imported` or `catalogue_already`, and tells the host to refresh.
- **Other versions…** — shown only when `item.versions().size() > 1`; a list of `Version.label()`/`year()`, each importing `Pick.forGame(version)`.
- **Play the recording** — shown only when `Pick.recording(item) != null`. Imports it to `Kinds.RECORDINGS` and opens it, which starts playback: `utils_open_file` hands an RZX to `rzx_start_playback_from_buffer`.

`item(id)` is fetched **when the pane opens**, not when the row is drawn: versions and files are what make that call expensive and a list does not need them.

- [ ] **Step 4: Run the test and watch it pass**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/library/ui/CataloguePane.java \
        app/src/main/java/dev/ldlab/zedex/library/ui/CatalogueView.java \
        app/src/androidTest/java/dev/ldlab/zedex/library/catalogue/ImportFlowTest.java \
        app/src/main/res/values*/strings.xml
git commit -m "feat: import what you are looking at

One tap takes the best file of the original release; two take a re-release or
a 128K remake, for the people who have a view on that and without an extra
screen for the people who do not.

And the recording is offered in its own right, since this app can play one
back - verified on a device, 10th Frame bowled a frame with nobody touching
the controls. Never as the game, which would be a game you cannot play.

The versions and files are fetched when the pane opens rather than when the
row is drawn: they are what makes that call expensive and a list does not need
them."
```

---

### Task 12: The fourth tab

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java`
- Create: `app/src/main/java/dev/ldlab/zedex/library/catalogue/Catalogues.java`
- Create: `app/src/main/res/drawable/ic_catalogue.xml`

- [ ] **Step 1: Write `Catalogues`**

`Scrapers`' opposite number, and deliberately the same shape so the two read as siblings: `all(Context)`, `preferred(Context)`, `any(Context)`. Registration is a hand-written list inside `all(...)`, exactly as `Scrapers.all` is — not discovery. Today it returns one `ZxInfoCatalogue(new Http.Real(context))`.

Add the preference key to `storage/Prefs.java` beside `KEY_SCRAPER`, so it is declared where every other one is:

```java
    /** Which catalogue the library browses, by its own name(). */
    public static final String KEY_CATALOGUE = "catalogueProvider";
```

`preferred` matches it against `catalogue.name()` and falls back to `all(context).get(0)`, the way `Scrapers.chosen` does.

- [ ] **Step 2: Add the tab**

In `LibraryActivity`:

```java
    private enum Tab { BROWSE, FAVORITES, RECENTS, CATALOGUE }
```

Append `R.string.library_tab_catalogue` to `TAB_LABELS` and `R.drawable.ic_catalogue` to `TAB_ICONS`. **Both arrays are indexed by `Tab.ordinal()`**, so the new entry must be last in both, matching the enum.

`buildRail(boolean)` and `paintTabs()` both iterate `Tab.values()`, so the button appears with no further change — which is the problem. **The tab is built only when a catalogue is configured**, so `buildRail`'s loop gains a skip:

```java
        for (Tab candidate : Tab.values()) {
            if (candidate == Tab.CATALOGUE && !Catalogues.any(this)) continue;
            ...
        }
```

and `tabViews` stops being indexable by `ordinal()`. Keep a `Map<Tab, View>` instead and change `paintTabs()` to walk it — `tabViews.get(candidate.ordinal())` is wrong the moment one tab is missing, and the failure is that the wrong button lights up, which reads as a tap that went to the wrong place.

**One predicate answers one question.** `Catalogues.any()` decides whether the tab exists, and nothing else. Do not reuse it to decide anything about where the library starts — `startsInLibrary` gated both "where does the app start" and "is there a library at all", and turning the setting off removed the only way back in.

- [ ] **Step 3: The other five places that ask which tab it is**

The map of this activity is not just `show` and `load`. Every one of these switches on the tab and needs the new value handled, and a missed one is a screen that behaves as though it were still on Browse:

| Where | What it needs |
|---|---|
| `show(Tab)` | hide the list, the breadcrumb and the up button; show `CatalogueView`. Hide it again for the other three. |
| `load()` | return early — the view loads itself, and running the Browse walk here would list the user's folder behind the catalogue. |
| `finishLoad(...)` | the empty-message choice (`tab == Tab.FAVORITES ? … : …`) must not fall through to `library_empty` for a tab that draws its own. |
| `padNav.tab(int delta)` | gamepad left/right across the rail must skip the tab when it is not built, or the pad selects a button that is not there. |
| `filtering()` / `updateFilterChips()` | the library filter does not apply to the catalogue; the chips must not appear over it. |
| `toggleFavorite(...)` | its `if (tab == Tab.FAVORITES) load();` is unaffected, but check it still reads correctly with four values. |

`syncBackCallback()` must ask `catalogueView.onBack()` first when that tab is showing, so Back walks up the shelf stack before it leaves the screen.

- [ ] **Step 4: Refresh after an import**

The `CatalogueView.Host` callback calls the activity's existing `metadataChanged()` **and** re-runs `load()` for Browse, so a game imported while standing in Browse is there when you switch back. The tree listing is keyed by path and does not know about a file it did not list.

- [ ] **Step 5: Check the preference**

Run `python3 scripts/check-prefs.py`.
Expected: `every preference is read as it is written`, with `catalogueProvider  String  String` in the table.

- [ ] **Step 6: Build, install, and drive it by hand**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
scripts/ui-tap.py list
scripts/ui-tap.py "Catalogue"
```
Confirm: the tab is there, the shelves are there, a search returns rows, Back walks up the shelves rather than closing the screen.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java \
        app/src/main/java/dev/ldlab/zedex/library/catalogue/Catalogues.java \
        app/src/main/res/drawable/ic_catalogue.xml
git commit -m "feat: a fourth tab, beside Browse, Favourites and Recent

The library's three tabs are three views of what you have; this is the one
view of what you do not. A tab rather than a screen off Options, which would
bury it beside the maintenance actions, and rather than folding it into
library search, which would be one screen answering two questions - the shape
this codebase has already been bitten by twice.

The activity gains forty lines and not six hundred: CatalogueView owns its own
stack, paging and pane, and LibraryActivity only decides which of the two is
showing.

The tab is built only when a catalogue is configured, and Catalogues.any()
answers that one question and nothing else - startsInLibrary once gated both
'where does the app start' and 'is there a library at all', and turning it off
removed the only way back in."
```

---

### Task 13: Verify on the device, write down what was learned

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md` (one line)
- Modify: `docs/INTERNALS.md`

- [ ] **Step 1: Run the whole instrumentation suite, once**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew --no-daemon assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

**Read the first failure, not the count** — one flake cascades into every later class failing the same way. Set `secondScreen` false and `ANDROID_SERIAL=emulator-5554` first.

- [ ] **Step 2: Verify by hand, and write down what was actually checked**

On a device, with the app installed: search for a game, import it, confirm it appears in Browse with its cover, open it and confirm it loads. Import a recording and confirm it plays itself back. Import something that is already there and confirm it says so rather than making a second copy. Note which of these you actually did — the README says where something is unverified, and this repo's convention is that a feature claim names the device it was checked on.

- [ ] **Step 3: Add the rules to `CLAUDE.md`**

Under the hard rules, only the ones that are expensive to rediscover and are not already there. At minimum:

- **`Pace` counts per host, not per object** — two objects each waiting 500ms and neither waiting for the other is the real spacing halving exactly as the traffic doubles, against an address that blocks on behaviour patterns.
- **A `Catalogue` page carries sub-shelves as well as items** — that is how a category tree fits through a seam with no method for one, and it is what zxart needs.
- **A catalogue's file urls are absolute and may be on a third-party host** — ZXDB's recordings are on `archive.org`; joining every path onto one base is the mistake already made once with loading screens.
- **`Downloaded/<kind>/`, and a recording outranks its entry's genre** — the folder scheme answers "what kind of thing is this file".
- **SAF's uniqueness is the document id, not the display name** — find before create, or somebody gets `Games` and `Games (1)`.
- **An unrecognised zxart filter is ignored rather than refused** — a guessed name returned all 58,032 entries and read as a search that matched everything.

- [ ] **Step 4: One line in the README and a paragraph in `INTERNALS.md`**

The README gets a line or two, never paragraphs — reasoning goes in `INTERNALS.md`. Say what the feature does and, if any part is unverified, say so.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md README.md docs/INTERNALS.md
git commit -m "docs: what the catalogue cost to learn

The rules that are expensive to rediscover and cheap to write down: pacing
counts per host rather than per object, a page carries sub-shelves so a
category tree fits through a seam with no method for one, file urls are
absolute and may be on a host neither of us chose, and SAF's uniqueness is the
document id rather than the display name.

And what was actually run on a device, named, so the README's claims are ones
somebody checked."
```

---

## Three places this plan differs from the spec

Two of them are the same shape: the spec says "reuse the thing next door", and the thing next door turns out to be written entirely in terms of a type the catalogue does not have. The third is one method.

**0. The interface is six methods, not five.** The spec writes it with `name`, `shelves`, `open`, `item` and `refusalFor`. This plan adds `configured()`, because `Catalogues.any()` has to answer "is there a catalogue to build a tab for" and `Scrapers` answers exactly that question by asking each provider. ZXInfo returns true unconditionally — it needs no credentials — so today it buys nothing; it exists so that a catalogue which *does* need credentials hides its tab rather than offering something that can only fail, which is how every scrape row in this app already behaves. Say so and it comes out.

**1. The pane.** The spec says results "fill the grid the library already draws" and "tapping one fills the same detail pane". `EntryAdapter` and `DetailPane` are written throughout in terms of `Entry` — a file with a `Uri`, a size, a modified time and a path inside an archive — and `DetailPane.show(Entry)` is 805 lines built on that. A catalogue item has none of those four fields. Reusing them means either widening `Entry` with four fields that lie for half its uses, or widening `DetailPane.Host` — whose own javadoc says its three methods are "deliberately three and not more… If this ever wants a fourth, that is the signal the seam has moved". So `CatalogueAdapter` and `CataloguePane` are separate classes that **look** identical on screen and sit in the same places. The user-visible result is what the spec describes; the sharing is of appearance, not of code.

**2. `Downloads` is not reused for the import.** The spec's numbered import steps read like `Downloads.fetch`, and `Downloads` does download-check-unzip already. But it writes into the app's *media* folder through `Artwork.fileFor`, keyed by a game's relative path, and its unzip is hard-wired to take one `.ay` out. The import writes into the *user's* content tree through SAF, keyed by nothing yet, and its unzip may yield a folder of several files. They share four verbs and no destination. `Imports` is its own class.

Neither changes what gets built or how it behaves. If you would rather the spec be amended to match, say so and it will be.

## What this plan does not build

Carried straight from the spec, unchanged:

- **zxart.** It shaped the seam — the category tree is why a page carries sub-shelves — and is not implemented here. Its vocabulary is recorded in the spec's *Not in this piece*.
- **Downloading anything that is not a program.** Music and pictures are reachable through scraping already.
- **Bulk import.** One thing at a time; a queue is a different feature with different manners.
- **`structureDateModified` sync.** Recorded in the spec as the thing to reach for if a cached catalogue is ever wanted, so that nobody builds one by polling.
