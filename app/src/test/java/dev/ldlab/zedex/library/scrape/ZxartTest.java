package dev.ldlab.zedex.library.scrape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.ldlab.zedex.library.catalogue.Fixtures;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.machine.Suggested;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <b>No {@code Locale} anywhere in this file.</b> Task 13 gave {@code Zxart}
 * a constructor that took one, on the unverified assumption that zxart's
 * {@code language:} segment might affect search matching even where it did
 * not change the fields read back. That is now measured and false - see
 * {@code Zxart}'s own class javadoc for the {@code dizzy-eng.json}/{@code
 * dizzy-rus.json} comparison - so the parameter is gone rather than kept
 * as a value that would never do anything.
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

        List<Candidate> found = new Zxart(http).search(
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

        List<Candidate> found = new Zxart(http).search(
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

        List<Candidate> found = new Zxart(http).search(
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

        List<Candidate> found = new Zxart(http).search(
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

        new Zxart(http).search(
                game("Head.tzx", "00000000000000000000000000000000"));

        assertEquals("one search and three confirmations, never a fourth",
                     4, http.asked.size());
    }

    /**
     * Six is the ceiling and it is stated honestly: one search, up to three
     * confirmations, one prod, one release list. Media are static files and
     * cost nothing beyond that.
     */
    @Test
    public void theCostIsTheCeilingAndNotTheAverage() {
        assertEquals(6, new Zxart(new Fixtures.Canned())
                            .costPerGame(Provider.Wanted.usual()));
    }

    /**
     * {@code fetch} writes {@link dev.ldlab.zedex.library.meta.Meta#genre}
     * into the store, and {@code Filters}/{@code Facets} group a whole
     * collection by that one string - so a genre read in Russian for half a
     * collection and English for the other half would be two facets for one
     * genre, and {@code Merge}'s fill-gaps rule means whichever scrape
     * landed first would keep the bucket for ever. There is no longer a
     * locale this class could even be built with, so this test now pins the
     * simpler fact that survives that removal: every request {@code fetch}
     * makes always says {@code eng}, whatever future change might otherwise
     * reintroduce a second value.
     *
     * <b>Two requests, not one, since Task 15.</b> {@code fetch} now always
     * asks for the release list too - {@code hardwareRequired} lives there -
     * so both the prod and the release requests have to be checked, not just
     * the first.
     */
    @Test
    public void fetchAsksInEnglishWhateverTheLocaleIs() throws Exception {
        Pace.forget();
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.RELEASES_LICENCE_TO_KILL);

        new Zxart(http).fetch(
                new Candidate("92668", "Licence to Kill", "1989", null, true),
                Provider.Wanted.nothing());

        assertEquals("prod and the release list, both in English", 2, http.asked.size());
        for (String url : http.asked) {
            assertTrue("fetch must ask in English", url.contains("language:eng"));
            assertFalse("never any other language", url.contains("language:rus"));
        }
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

        List<Candidate> found = new Zxart(http).search(game);

        assertTrue(found.isEmpty());
        assertEquals("md5() must not be called with nothing to confirm against",
                     0, game.md5Calls);
    }

    // --- hardware -----------------------------------------------------------------------

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
     *  which is what every screen downstream reads.
     *
     *  <b>Not {@code new Zxart(http, Locale.ENGLISH)}, as the brief this test
     *  was written from still had it.</b> The Locale parameter was removed
     *  from this class's constructor before Task 15 was written (see the
     *  class javadoc's own measurement of why) - the brief was drafted
     *  against an older signature and never updated after that removal. */
    @Test
    public void aFetchFillsMachineAndInputs() throws Exception {
        Pace.forget();
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.RELEASES_LICENCE_TO_KILL);

        Meta meta = new Zxart(http).fetch(
                new Candidate("92668", "Licence to Kill", "1989", null, true),
                Provider.Wanted.nothing()).meta;

        assertEquals("ZX-Spectrum 128K", meta.machine);
        assertTrue(meta.inputs.contains("Kempston Joystick"));
        assertTrue(meta.inputs.contains("Interface 2 (right)"));
    }

    /**
     * The "either" row is measured, not invented: entry 100938's {@code .tap}
     * release (id 100942, in {@link Fixtures#RELEASES_HEAD_OVER_HEELS})
     * declares {@code ["zx48","zx128","cursor","kempston","int2_2"]} - both
     * machines, because the tape genuinely runs on either - so taking merely
     * the first token would say 48K and throw the other half away.
     * {@code Suggested.MACHINE_WORDS} already carries {@code "ZX-Spectrum
     * 48K/128K"} for exactly this, and {@code Suggested.machines} already
     * splits it on the slash, so the machine list offered comes out as two
     * rather than one.
     */
    @Test
    public void theEitherMachineComesFromARealRelease() throws Exception {
        JSONObject release = releaseWithId(Fixtures.RELEASES_HEAD_OVER_HEELS, 100942);

        assertEquals("ZX-Spectrum 48K/128K",
                     Zxart.machineFrom(release.optJSONArray("hardwareRequired")));

        List<String> inputs = Zxart.inputsFrom(release.optJSONArray("hardwareRequired"));
        assertTrue(inputs.contains("Cursor"));
        assertTrue(inputs.contains("Kempston Joystick"));
        assertTrue(inputs.contains("Interface 2 (right)"));
    }

    /**
     * A release may name no machine at all, and null is the honest answer:
     * the same entry's {@code .tzx} release (id 100941) declares only
     * {@code ["cursor","kempston","int2_2"]} - three interfaces and no
     * computer - so {@code Zxart.machineFrom} answers null there while
     * {@code Zxart.inputsFrom} still has three entries. A release saying
     * which joysticks it reads has said nothing about which Spectrum, and
     * that must not be turned into a guess.
     */
    @Test
    public void aReleaseNamingOnlyInterfacesLeavesTheMachineNull() throws Exception {
        JSONObject release = releaseWithId(Fixtures.RELEASES_HEAD_OVER_HEELS, 100941);

        assertNull(Zxart.machineFrom(release.optJSONArray("hardwareRequired")));

        List<String> inputs = Zxart.inputsFrom(release.optJSONArray("hardwareRequired"));
        assertEquals(3, inputs.size());
        assertTrue(inputs.contains("Cursor"));
        assertTrue(inputs.contains("Kempston Joystick"));
        assertTrue(inputs.contains("Interface 2 (right)"));
    }

    // --- media --------------------------------------------------------------------------

    /**
     * {@code imagesUrls} holds the rendered loading screen and the
     * screenshots in one array, told apart by their path rather than by
     * index - see {@code Zxart.collectImages}'s own javadoc for the measured
     * shape and why a positional rule would eventually misfile one.
     */
    @Test
    public void theRenderedScreenAndTheScreenshotsAreToldApartByPath() throws Exception {
        Pace.forget();
        List<Medium> media = fetchWith(Provider.Wanted.of("titlescreens", "screenshots"));

        assertEquals("titlescreens", folderOf(media, "zximages"));
        assertEquals("screenshots", folderOf(media, "/screenshot/"));
    }

    /** The three inlay suffixes that land, from the measured vocabulary -
     *  no suffix (the cover), {@code _Back} and {@code _Media}. */
    @Test
    public void theInlaysAreCoverBackAndMedia() throws Exception {
        Pace.forget();
        List<Medium> media = fetchWith(
                Provider.Wanted.of("covers", "backcovers", "physicalmedia"));

        assertEquals("covers", folderOf(media, "LicenceToKill.jpg"));
        assertEquals("backcovers", folderOf(media, "LicenceToKill_Back.jpg"));
        assertEquals("physicalmedia", folderOf(media, "LicenceToKill_Media.jpg"));
    }

    /**
     * <b>An unrecognised suffix is skipped, not guessed at.</b>
     *
     * A fifth of all inlay files carry a marker this app has no folder for,
     * and inventing a fourth folder is not this feature's business. What
     * must not happen is one of them landing in {@code covers} because it
     * happened to be first in the array - which is exactly what a
     * positional rule would do. Driven straight against {@code
     * Zxart.collectRelease} and one captured release row - see {@link
     * Fixtures#RELEASE_WITH_ODD_INLAY}'s own javadoc for which entry it is -
     * rather than through a whole {@code fetch}, since this is a fact about
     * the suffix rule alone.
     */
    @Test
    public void anUnknownInlaySuffixIsSkipped() throws Exception {
        Pace.forget();
        List<Medium> media = mediaFrom(Fixtures.RELEASE_WITH_ODD_INLAY,
                                      Provider.Wanted.of("covers"));

        for (Medium medium : media) {
            assertFalse("a side marker must not be taken for a cover",
                        medium.url.contains("_SideA"));
        }
    }

    /**
     * <b>A front and a back may come from different editions - see {@code
     * Zxart.mediaFrom}'s own javadoc for why that is deliberate.</b> Each
     * folder is filled independently by the first release that has
     * something for it, not by "the first release with anything, taken
     * whole" - so {@code covers} and {@code backcovers} can and here do come
     * from two different releases of the one prod.
     *
     * <p>{@link Fixtures#RELEASE_COVER_ONLY} (release 92876, front only) is
     * queued first and {@link Fixtures#RELEASE_WITH_BACK} (release 92874,
     * front, back and media) second - see that fixture's own javadoc for why
     * this order is assembled rather than a real reply's own. {@code covers}
     * is asserted to come from 92876 and {@code backcovers} from 92874 -
     * different release ids - which is exactly what an implementation that
     * locks all folders to one release could not produce: locked to
     * whichever release is checked first (92876, front only), it would fill
     * {@code covers} and leave {@code backcovers} empty rather than filling
     * it from the other release. I confirmed this directly: briefly
     * rewriting {@code mediaFrom} to stop once a release supplied anything
     * (record the first release with any inlay match, then only ever accept
     * further folders from that same release, skipping every other release
     * entirely) failed this test with an assertion error on {@code
     * oneIn(media, "backcovers")} - "nothing in backcovers" - while every
     * other test in this class still passed, since none of the rest happens
     * to exercise a prod whose front and back live on different releases.
     * Reverted before committing.
     */
    @Test
    public void coversAndBackcoversMayComeFromDifferentEditions() throws Exception {
        Pace.forget();
        List<Medium> media = mediaFromReleases(
                Provider.Wanted.of("covers", "backcovers", "physicalmedia"),
                Fixtures.RELEASE_COVER_ONLY, Fixtures.RELEASE_WITH_BACK);

        assertTrue("covers from the cover-only release",
                   oneIn(media, "covers").url.contains("id:205770"));
        assertTrue("backcovers from the OTHER release, since the first has none",
                   oneIn(media, "backcovers").url.contains("id:554385"));
    }

    /**
     * The maps, the advert and the text manual, from the two places they
     * live: {@code maps} on the prod, {@code adverts} and {@code manuals}
     * off the release list.
     *
     * <b>The obvious test does not work here.</b> On this entry, the prod's
     * {@code maps[0]} and the first release's {@code ads[0]} carry the same
     * basename - both are called {@code LicenceToKill.jpg} - because zxart
     * itself named them that, not because of anything this class does. A
     * helper that finds "the medium whose url contains this filename" cannot
     * tell them apart, so this asserts <em>per folder</em> instead - which
     * one is in {@code maps} and which is in {@code adverts} - using the
     * numeric release ids embedded in each url ({@code id:240641} against
     * {@code id:554316}), which do differ, rather than the filename, which
     * does not.
     */
    @Test
    public void mapsAdvertsAndTheTextManual() throws Exception {
        Pace.forget();
        List<Medium> media = fetchWith(Provider.Wanted.of("maps", "adverts", "manuals"));

        assertTrue("the prod's own map", oneIn(media, "maps").url.contains("id:240641"));
        assertTrue("the release's own advert", oneIn(media, "adverts").url.contains("id:554316"));
        assertEquals("manuals", folderOf(media, "LicenceToKill.txt"));
    }

    /**
     * <b>Only what was asked for.</b>
     *
     * The folder set is what a sweep is priced on - {@code costPerGame}
     * times a collection - so a provider that quietly returned a medium
     * nobody asked for makes that arithmetic a lie, even though these
     * particular media are free static files.
     */
    @Test
    public void onlyTheWantedFoldersComeBack() throws Exception {
        Pace.forget();
        List<Medium> media = fetchWith(Provider.Wanted.of("covers"));

        assertEquals(1, media.size());
        assertEquals("covers", media.get(0).folder);
    }

    /**
     * <b>Superseded by Task 15 - kept as the record of what changed and
     * why.</b> Before hardware was read from a release, a {@code wanted}
     * naming only prod-sourced folders ({@code titlescreens}, {@code maps})
     * skipped the release-list request entirely, and this test pinned that
     * at one request. {@code Zxart.metaFrom} now reads {@code
     * hardwareRequired} off the release list on every {@code fetch}, so
     * there is no longer a {@code wanted} that can skip it - see {@link
     * #everyFetchAsksForTheReleaseListNow}, which replaces this test's
     * assertion with the one that is true now.
     */
    @Test
    public void everyFetchAsksForTheReleaseListNow() throws Exception {
        Pace.forget();
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.RELEASES_LICENCE_TO_KILL);

        new Zxart(http).fetch(
                new Candidate("92668", "Licence to Kill", "1989", null, true),
                Provider.Wanted.of("titlescreens", "maps"));

        assertEquals("prod, then the release list for hardware - even though "
                     + "neither wanted folder needs one of its own", 2, http.asked.size());
    }

    // --- helpers --------------------------------------------------------------------------

    private static List<Medium> fetchWith(Provider.Wanted wanted) throws Exception {
        Fixtures.Canned http = new Fixtures.Canned().then(Fixtures.PROD_LICENCE_TO_KILL)
                                                    .then(Fixtures.RELEASES_LICENCE_TO_KILL);

        Provider.Scraped scraped = new Zxart(http).fetch(
                new Candidate("92668", "Licence to Kill", "1989", null, true), wanted);

        return scraped.media;
    }

    /** One release row out of a whole release-list reply, by its id - so a
     *  hardware test can reach a release other than the one {@code fetch}
     *  itself would choose (the first in the array), the way {@link
     *  #theEitherMachineComesFromARealRelease} and {@link
     *  #aReleaseNamingOnlyInterfacesLeavesTheMachineNull} both need to reach
     *  100938's second release without disturbing the first. */
    private static JSONObject releaseWithId(String releasesJson, int id) throws Exception {
        for (JSONObject release : ZxartApi.rows(new JSONObject(releasesJson), ZxartApi.RELEASE)) {
            if (release.optInt("id") == id) return release;
        }
        throw new AssertionError("no release " + id + " in this fixture");
    }

    /** Drives {@code Zxart.collectRelease} directly against one captured
     *  release row, for a fact that belongs to the suffix rule alone rather
     *  than to a whole {@code fetch}. Package-private in {@code Zxart}
     *  precisely so this can reach it. */
    private static List<Medium> mediaFrom(String releaseJson, Provider.Wanted wanted)
            throws Exception {
        Map<String, Medium> into = new LinkedHashMap<>();
        Zxart.collectRelease(into, new JSONObject(releaseJson), wanted);
        return new ArrayList<>(into.values());
    }

    /** Drives {@code Zxart.mediaFrom} across several release rows, in the
     *  order given, with an empty prod - there is no {@code imagesUrls}/
     *  {@code maps} fact this helper's callers need, only the cross-release
     *  behaviour of {@code collectRelease}'s own folders. Package-private in
     *  {@code Zxart} for the same reason {@code collectRelease} is. */
    private static List<Medium> mediaFromReleases(Provider.Wanted wanted, String... releaseJsons)
            throws Exception {
        List<JSONObject> releases = new ArrayList<>();
        for (String releaseJson : releaseJsons) releases.add(new JSONObject(releaseJson));

        return Zxart.mediaFrom(new JSONObject(), releases, wanted);
    }

    /** The folder of the one medium whose url contains {@code substring} -
     *  fails when none or several do, rather than answering the first match,
     *  which would let two similar urls agree by accident. */
    private static String folderOf(List<Medium> media, String substring) {
        Medium found = null;
        for (Medium medium : media) {
            if (medium.url.contains(substring)) {
                assertNull("more than one medium matched " + substring, found);
                found = medium;
            }
        }
        assertNotNull("no medium matched " + substring, found);
        return found.folder;
    }

    /** The one medium in {@code folder} - fails when none or several are,
     *  the folder-keyed twin of {@link #folderOf} for the cases where two
     *  media share a filename and only their folder tells them apart. */
    private static Medium oneIn(List<Medium> media, String folder) {
        Medium found = null;
        for (Medium medium : media) {
            if (folder.equals(medium.folder)) {
                assertNull("more than one medium in " + folder, found);
                found = medium;
            }
        }
        assertNotNull("nothing in " + folder, found);
        return found;
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
