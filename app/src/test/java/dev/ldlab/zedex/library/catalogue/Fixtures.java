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
 * Trimmed only by dropping whole rows - never by dropping fields - so what is
 * left is still shaped like a reply. {@code RELEASES_LICENCE_TO_KILL} keeps 3
 * of 24 releases; {@code PROD_FORBIDDEN} is built by wrapping one real,
 * complete row (a genuine {@code legalStatus:"forbidden"} entry) in the same
 * wrapper shape a single-id lookup answers with, because {@code
 * prod-categories.json} itself is a 285-category sweep with no single-prod
 * reply of its own to copy.
 *
 * The originals are in review/zxart/, which is gitignored, so they are on one
 * machine only; review/zxart/probe.py fetches any that are missing and skips
 * the ones that are not.
 */
public final class Fixtures {

    private Fixtures() {
    }

    /** review/zxart/prod-by-id.json - export:zxProd, filter:zxProdId=92668.
     *  Copied whole: the reply already holds exactly one row. */
    public static final String PROD_LICENCE_TO_KILL =
            "{\"totalAmount\":1,\"start\":0,\"limit\":2,\"responseData\":{\"zxProd\":[{\"id\":92668,\"title\""
            + ":\"Licence to Kill\",\"dateCreated\":1479491025,\"dateModified\":1759422576,\"language\":[\"en\""
            + "],\"year\":1989,\"youtubeId\":\"r1U9U1MMn6g\",\"legalStatus\":\"unknown\",\"groupsIds\":[311548]"
            + ",\"publishersIds\":[176138],\"releasesIds\":[92671,92672,92673,92674,92675,257199,257200,257201,"
            + "257202,257203,257204,257206,328337,328340,438953,438957,438961,438962,454085,454086,497413,49741"
            + "4,586601,587045],\"imagesUrls\":[\"https:\\/\\/zxart.ee\\/zximages\\/id=92669;pal=srgb;type=stan"
            + "dard;zoom=1\",\"https:\\/\\/zxart.ee\\/screenshot\\/id:575815\\/ltk_01.gif\",\"https:\\/\\/zxart"
            + ".ee\\/screenshot\\/id:575814\\/ltk_02.gif\",\"https:\\/\\/zxart.ee\\/screenshot\\/id:575813\\/lt"
            + "k_03.gif\",\"https:\\/\\/zxart.ee\\/screenshot\\/id:575812\\/ltk_04.gif\",\"https:\\/\\/zxart.ee"
            + "\\/screenshot\\/id:92670\\/LicenceToKill.gif\"],\"maps\":[\"https:\\/\\/zxart.ee\\/release\\/id:"
            + "240641\\/mode:download\\/filename:LicenceToKill.jpg\",\"https:\\/\\/zxart.ee\\/release\\/id:2406"
            + "42\\/mode:download\\/filename:LicenceToKill_2.jpg\",\"https:\\/\\/zxart.ee\\/release\\/id:240643"
            + "\\/mode:download\\/filename:LicenceToKill_3.png\"],\"authorsInfo\":[{\"id\":1056,\"authorId\":66"
            + "61,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"unknown\"],\"type\":\"prod\"},{\"id\":1055,"
            + "\"authorId\":90609,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"gamedesign\"],\"type\":\"pro"
            + "d\"},{\"id\":1057,\"authorId\":176100,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"unknown\""
            + "],\"type\":\"prod\"},{\"id\":1058,\"authorId\":176101,\"startDate\":\"\",\"endDate\":\"\",\"role"
            + "s\":[\"unknown\"],\"type\":\"prod\"},{\"id\":51086,\"authorId\":178388,\"startDate\":\"\",\"endD"
            + "ate\":\"\",\"roles\":[\"graphics\"],\"type\":\"prod\"},{\"id\":51087,\"authorId\":178742,\"start"
            + "Date\":\"\",\"endDate\":\"\",\"roles\":[\"graphics\"],\"type\":\"prod\"},{\"id\":25200,\"authorI"
            + "d\":197059,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"illustrating\"],\"type\":\"prod\"},{"
            + "\"id\":51088,\"authorId\":310183,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"illustrating\""
            + "],\"type\":\"prod\"}],\"importIds\":{\"maps\":\"LicencetoKill\",\"zxdb\":\"1\",\"wos\":\"0000001"
            + "\",\"vt\":\"494dbb9e6811a757f1af2b93198526ff\"},\"votes\":4,\"votesAmount\":3,\"rzx\":[\"https:"
            + "\\/\\/zxart.ee\\/release\\/id:554313\\/mode:download\\/filename:licencetokill.zip\"],\"connected"
            + "CategoriesIds\":[523395,523425,523413],\"categoriesString\":\"\\u0418\\u0433\\u0440\\u044b\\/\\u"
            + "042d\\u043a\\u0448\\u0435\\u043d\\/\\u0428\\u0443\\u0442\\u0435\\u0440\\u044b\\/Shoot &#039;em u"
            + "p (Shmups)\\/\\u0412\\u0435\\u0440\\u0442\\u0438\\u043a\\u0430\\u043b\\u044c\\u043d\\u044b\\u043"
            + "5 \\u0448\\u043c\\u0430\\u043f\\u044b\"}]},\"responseStatus\":\"success\"}";

    /** review/zxart/af-release-by-prod.json - export:zxRelease,
     *  filter:zxProdId=92668 - trimmed to the first 3 of its 24 releases
     *  (ids 92671, 92672, 92673), whole releases dropped rather than fields.
     *  totalAmount stays 24, the service's real count, even though only 3
     *  rows are here - the same shape a shorter page of the same query would
     *  answer with. */
    public static final String RELEASES_LICENCE_TO_KILL =
            "{\"totalAmount\":24,\"start\":0,\"limit\":50,\"responseData\":{\"zxRelease\":[{\"id\":92671,\"ti"
            + "tle\":\"Licence to Kill\",\"dateCreated\":1479491025,\"dateModified\":1756585161,\"file\":\"http"
            + "s:\\/\\/zxart.ee\\/releasefile\\/id:92671\\/LicenceToKill.tzx.zip\",\"fileName\":\"LicenceToKill"
            + ".tzx.zip\",\"year\":1989,\"publishersIds\":[176138],\"hardwareRequired\":[\"zx128\",\"ay\",\"kem"
            + "pston\",\"int2_2\"],\"releaseType\":\"original\",\"releaseFormat\":[\"tzx\"],\"inlays\":[\"https"
            + ":\\/\\/zxart.ee\\/release\\/id:205509\\/mode:download\\/filename:LicenceToKill.jpg\",\"https:\\/"
            + "\\/zxart.ee\\/release\\/id:554314\\/mode:download\\/filename:LicenceToKill_Back.jpg\",\"https:"
            + "\\/\\/zxart.ee\\/release\\/id:554315\\/mode:download\\/filename:LicenceToKill_Media.jpg\"],\"ads"
            + "\":[\"https:\\/\\/zxart.ee\\/release\\/id:554316\\/mode:download\\/filename:LicenceToKill.jpg\"]"
            + ",\"instructions\":[\"https:\\/\\/zxart.ee\\/release\\/id:205510\\/mode:download\\/filename:Licen"
            + "ceToKill.txt\"],\"releaseStructure\":[{\"id\":1,\"md5\":\"1f302ec4275e3ee5f97ad1d656e11178\",\"p"
            + "arentId\":0,\"fileName\":\"LicenceToKill.tzx.zip\",\"size\":41330,\"elementId\":92671,\"type\":"
            + "\"zip\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false,\"items\":[{\"id\":"
            + "2,\"md5\":\"ea37a787becbdb2c74dada8e668b8f37\",\"parentId\":1,\"fileName\":\"Licence To Kill - 4"
            + "8k.tzx\",\"size\":48672,\"elementId\":92671,\"type\":\"tzx\",\"encoding\":\"none\",\"internalTyp"
            + "e\":\"binary\",\"viewable\":false,\"items\":[{\"id\":3,\"md5\":\"7438f6124a7baec7da24e5969c62236"
            + "8\",\"parentId\":2,\"fileName\":\"LTK48.B\",\"size\":144,\"elementId\":92671,\"type\":\"file\","
            + "\"encoding\":\"none\",\"internalType\":\"zx_basic\",\"viewable\":true},{\"id\":4,\"md5\":\"25fec"
            + "a22e133f9f75434ca35083785e2\",\"parentId\":2,\"fileName\":\"loader.C\",\"size\":512,\"elementId"
            + "\":92671,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false}"
            + ",{\"id\":5,\"md5\":\"628d564b9a54a5dddbb77dee621c1123\",\"parentId\":2,\"fileName\":\"data01\","
            + "\"size\":6912,\"elementId\":92671,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"zx_"
            + "image_standard\",\"viewable\":true},{\"id\":6,\"md5\":\"9d30e985e8c969c2e847ec4e749f8b8f\",\"par"
            + "entId\":2,\"fileName\":\"data02\",\"size\":40960,\"elementId\":92671,\"type\":\"file\",\"encodin"
            + "g\":\"none\",\"internalType\":\"binary\",\"viewable\":false}]},{\"id\":7,\"md5\":\"574179686e1b8"
            + "a03bf393a3230f98b5c\",\"parentId\":1,\"fileName\":\"Licence To Kill - 128k.tzx\",\"size\":48674,"
            + "\"elementId\":92671,\"type\":\"tzx\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewabl"
            + "e\":false,\"items\":[{\"id\":8,\"md5\":\"410f77539e20d8d59655fc73720f1fa0\",\"parentId\":7,\"fil"
            + "eName\":\"LTK128.B\",\"size\":146,\"elementId\":92671,\"type\":\"file\",\"encoding\":\"none\",\""
            + "internalType\":\"zx_basic\",\"viewable\":true},{\"id\":9,\"md5\":\"a1eeed2bea7a72c0d37be2614bf94"
            + "783\",\"parentId\":7,\"fileName\":\"loader.C\",\"size\":512,\"elementId\":92671,\"type\":\"file"
            + "\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":10,\"md5\":\"ab"
            + "45f7987a86629d693a71179d6e00a2\",\"parentId\":7,\"fileName\":\"data01\",\"size\":6912,\"elementI"
            + "d\":92671,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"zx_image_standard\",\"viewa"
            + "ble\":true},{\"id\":11,\"md5\":\"570a241c5494e6ddf94e2be4df57dcec\",\"parentId\":7,\"fileName\":"
            + "\"data02\",\"size\":40960,\"elementId\":92671,\"type\":\"file\",\"encoding\":\"none\",\"internal"
            + "Type\":\"binary\",\"viewable\":false}]}]}],\"prodId\":92668},{\"id\":92672,\"title\":\"Licence t"
            + "o Kill\",\"dateCreated\":1479491025,\"dateModified\":1756585161,\"file\":\"https:\\/\\/zxart.ee"
            + "\\/releasefile\\/id:92672\\/LicenceToKill.dsk.zip\",\"fileName\":\"LicenceToKill.dsk.zip\",\"yea"
            + "r\":1989,\"publishersIds\":[176138],\"hardwareRequired\":[\"zx+3\",\"ay\",\"kempston\",\"int2_2"
            + "\"],\"releaseType\":\"original\",\"releaseFormat\":[\"dsk\"],\"instructions\":[\"https:\\/\\/zxa"
            + "rt.ee\\/release\\/id:205512\\/mode:download\\/filename:LicenceToKill.txt\"],\"releaseStructure\""
            + ":[{\"id\":12,\"md5\":\"33058b5c3c4265ef282025d1d900bf9d\",\"parentId\":0,\"fileName\":\"LicenceT"
            + "oKill.dsk.zip\",\"size\":24497,\"elementId\":92672,\"type\":\"zip\",\"encoding\":\"none\",\"inte"
            + "rnalType\":\"binary\",\"viewable\":false,\"items\":[{\"id\":13,\"md5\":\"fa17086134b4fdf6240e158"
            + "912ee7614\",\"parentId\":12,\"fileName\":\"Licence To Kill.dsk\",\"size\":204544,\"elementId\":9"
            + "2672,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false}]}],"
            + "\"prodId\":92668},{\"id\":92673,\"title\":\"Licence to Kill\",\"dateCreated\":1479491025,\"dateM"
            + "odified\":1756585161,\"file\":\"https:\\/\\/zxart.ee\\/releasefile\\/id:92673\\/LicenceToKill128"
            + ".tap.zip\",\"fileName\":\"LicenceToKill128.tap.zip\",\"year\":1989,\"publishersIds\":[176138],\""
            + "hardwareRequired\":[\"zx128\",\"ay\",\"kempston\",\"int2_2\"],\"releaseType\":\"original\",\"rel"
            + "easeFormat\":[\"tap\"],\"version\":\"128\",\"instructions\":[\"https:\\/\\/zxart.ee\\/release\\/"
            + "id:205514\\/mode:download\\/filename:LicenceToKill.txt\"],\"releaseStructure\":[{\"id\":14,\"md5"
            + "\":\"d2d328290a5277b46b16f59d7ef2b085\",\"parentId\":0,\"fileName\":\"LicenceToKill128.tap.zip\""
            + ",\"size\":24506,\"elementId\":92673,\"type\":\"zip\",\"encoding\":\"none\",\"internalType\":\"bi"
            + "nary\",\"viewable\":false,\"items\":[{\"id\":15,\"md5\":\"62c3709d6ea8b02b9824da8a438f93e8\",\"p"
            + "arentId\":14,\"fileName\":\"LTK128.TAP\",\"size\":48133,\"elementId\":92673,\"type\":\"tap\",\"e"
            + "ncoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false,\"items\":[{\"id\":16,\"md5\":"
            + "\"b01982e8fc9a64950fab70d750738112\",\"parentId\":15,\"fileName\":\"LTK 128k.B\",\"size\":186,\""
            + "elementId\":92673,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"zx_basic\",\"viewab"
            + "le\":true},{\"id\":17,\"md5\":\"d7231f90e2463816c01df66ac39059c3\",\"parentId\":15,\"fileName\":"
            + "\"$.C\",\"size\":6912,\"elementId\":92673,\"type\":\"file\",\"encoding\":\"none\",\"internalType"
            + "\":\"zx_image_standard\",\"viewable\":true},{\"id\":18,\"md5\":\"32781ba33aa2ecd80bc00ac79252a8e"
            + "b\",\"parentId\":15,\"fileName\":\"code128.C\",\"size\":40960,\"elementId\":92673,\"type\":\"fil"
            + "e\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false}]},{\"id\":19,\"md5\":"
            + "\"4e49eaa7f4e0d43b5873625b910af4cf\",\"parentId\":14,\"fileName\":\"SCRSHOT\",\"size\":40,\"elem"
            + "entId\":92673,\"type\":\"folder\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\""
            + ":false,\"items\":[{\"id\":20,\"md5\":\"181fc933d50daeba9b163083b03d1d4e\",\"parentId\":19,\"file"
            + "Name\":\"LTK128.SCR\",\"size\":6912,\"elementId\":92673,\"type\":\"file\",\"encoding\":\"none\","
            + "\"internalType\":\"zx_image_standard\",\"viewable\":true}]},{\"id\":21,\"md5\":\"a97d7fb20c23b79"
            + "2b1840d85962c7bb7\",\"parentId\":14,\"fileName\":\"POKES\",\"size\":38,\"elementId\":92673,\"typ"
            + "e\":\"folder\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false,\"items\":[{"
            + "\"id\":22,\"md5\":\"83737c8851c8721a471693d98797bb57\",\"parentId\":21,\"fileName\":\"LTK128.POK"
            + "\",\"size\":154,\"elementId\":92673,\"type\":\"file\",\"encoding\":\"UTF-8\",\"internalType\":\""
            + "plain_text\",\"viewable\":true}]}]}],\"prodId\":92668}]},\"responseStatus\":\"success\"}";

    /** review/zxart/af-categories-eng.json - export:zxProdCategory,
     *  filter:zxProdCategoryAll, language:eng. Copied whole and untrimmed:
     *  it is a graph, not a list of independent rows - a child id trimmed out
     *  would dangle, and {@code ZxartTree}'s nine roots and the walk up to
     *  them (Task 4) depend on every one of the 285 categories being present. */
    public static final String CATEGORY_TREE =
            "{\"totalAmount\":285,\"start\":0,\"limit\":1000,\"responseData\":{\"zxProdCategory\":[{\"id\":92"
            + "159,\"title\":\"Demo\"},{\"id\":92160,\"title\":\"Megademo\"},{\"id\":92161,\"title\":\"Trackmo"
            + "\"},{\"id\":92162,\"title\":\"Dentro\"},{\"id\":92163,\"title\":\"Intro\",\"categories\":[417094"
            + ",262450,262451,262452,262453,92169,92168,92167,92166,92165,92164,315119,315120,364978,92171]},{"
            + "\"id\":92164,\"title\":\"16K intro\"},{\"id\":92165,\"title\":\"8K intro\"},{\"id\":92166,\"titl"
            + "e\":\"4K intro\"},{\"id\":92167,\"title\":\"1K intro\"},{\"id\":92168,\"title\":\"512b intro\"},"
            + "{\"id\":92169,\"title\":\"256b intro\"},{\"id\":92171,\"title\":\"Cracktro\"},{\"id\":92172,\"ti"
            + "tle\":\"Gift\"},{\"id\":92173,\"title\":\"Invitation\"},{\"id\":92175,\"title\":\"Music Collecti"
            + "ons\"},{\"id\":92176,\"title\":\"Graphics Collections\",\"categories\":[523371]},{\"id\":92177,"
            + "\"title\":\"Games\",\"categories\":[92504,92505,92522,92541,92546,92555,92556,92560,92509,523379"
            + "]},{\"id\":92179,\"title\":\"Electronic Magazine\"},{\"id\":92180,\"title\":\"Charts\"},{\"id\":"
            + "92181,\"title\":\"Info\"},{\"id\":92182,\"title\":\"Newspaper\"},{\"id\":92183,\"title\":\"Syste"
            + "m Software\",\"categories\":[92537,92551,92567,92573,92576,92578,92580,92581,92583,92590,202587,"
            + "204150,244885,523372,244875,523508,523510]},{\"id\":92186,\"title\":\"Data Compression\",\"categ"
            + "ories\":[244887,244888]},{\"id\":92187,\"title\":\"Commanders\"},{\"id\":92188,\"title\":\"Misc"
            + "\"},{\"id\":92504,\"title\":\"Adventure\",\"categories\":[92507,523380,523381,523512]},{\"id\":9"
            + "2505,\"title\":\"Action\",\"categories\":[92512,92513,92514,92515,92516,92517,92518,92520,523408"
            + ",523419,536682,536379,537145,538283]},{\"id\":92506,\"title\":\"Dungeon Crawls\",\"categories\":"
            + "[523386,523387,523388]},{\"id\":92507,\"title\":\"Graphic Adventures\"},{\"id\":92508,\"title\":"
            + "\"Icon\\/Menu Driven Adventures\"},{\"id\":92509,\"title\":\"RPG\",\"categories\":[92506,523389]"
            + "},{\"id\":92510,\"title\":\"Text Adventures\"},{\"id\":92511,\"title\":\"Illustrated Text Advent"
            + "ures\"},{\"id\":92512,\"title\":\"Arcade\"},{\"id\":92513,\"title\":\"Adventure\"},{\"id\":92514"
            + ",\"title\":\"Beat 'em up\",\"categories\":[523392,523393,523394]},{\"id\":92515,\"title\":\"Maze"
            + "\",\"categories\":[523404,523405,523406,536655]},{\"id\":92516,\"title\":\"Pinball\"},{\"id\":92"
            + "517,\"title\":\"Platformers\",\"categories\":[523400,523401,523402,523403]},{\"id\":92518,\"titl"
            + "e\":\"Racing\",\"categories\":[523425,523426,523427,524248,524250,524249]},{\"id\":92519,\"title"
            + "\":\"Shoot 'em up (Shmups)\",\"categories\":[523413,523414,523417,523418,535914,536383,543924]},"
            + "{\"id\":92520,\"title\":\"Fighting Games\",\"categories\":[523409,523410]},{\"id\":92522,\"title"
            + "\":\"Card and Board Games\",\"categories\":[92539,523492,523493,523494,523495,523496,523497,5235"
            + "01,523502,523503,525128,525130,525131,525132,92540,525151]},{\"id\":92533,\"title\":\"Covertape"
            + "\"},{\"id\":92534,\"title\":\"Educational\",\"categories\":[92595,92596,92535,92542]},{\"id\":92"
            + "535,\"title\":\"Educational Game\"},{\"id\":92537,\"title\":\"Emulator\"},{\"id\":92538,\"title"
            + "\":\"Flight Simulators\"},{\"id\":92539,\"title\":\"Poker\"},{\"id\":92540,\"title\":\"Dice Game"
            + "s\"},{\"id\":92541,\"title\":\"Gambling\",\"categories\":[523498,523499,523500,525129,525134]},{"
            + "\"id\":92542,\"title\":\"Maths\"},{\"id\":92544,\"title\":\"Quiz\"},{\"id\":92545,\"title\":\"Wo"
            + "rd Games\"},{\"id\":92546,\"title\":\"Undetermined\"},{\"id\":92547,\"title\":\"Business\"},{\"i"
            + "d\":92548,\"title\":\"Domestic\",\"categories\":[418663]},{\"id\":92549,\"title\":\"Industrial\""
            + "},{\"id\":92550,\"title\":\"Simulation\"},{\"id\":92551,\"title\":\"Programming\",\"categories\""
            + ":[92552,92553,92554,244863,244882,244893]},{\"id\":92552,\"title\":\"Assembler\\/Mcode\"},{\"id"
            + "\":92553,\"title\":\"BASIC\"},{\"id\":92554,\"title\":\"General\"},{\"id\":92555,\"title\":\"Puz"
            + "zle\",\"categories\":[523490,92544,92545,523504,523505,523506,523507,543857,543864,543865]},{\"i"
            + "d\":92556,\"title\":\"Simulators\",\"categories\":[523471,523478,523479,523480,92557,538509]},{"
            + "\"id\":92557,\"title\":\"Sports Simulators\",\"categories\":[92558,92559,523482,523483,523484,52"
            + "3486,523487,523488,538817,538818,538819,538820,538821,538822]},{\"id\":92558,\"title\":\"Other S"
            + "ports Simulators\"},{\"id\":92559,\"title\":\"Sports Management Simulators\"},{\"id\":92560,\"ti"
            + "tle\":\"Strategy\",\"categories\":[92561,92562,523428,523431,523432,92563]},{\"id\":92561,\"titl"
            + "e\":\"Management\"},{\"id\":92562,\"title\":\"Wargames (Board)\"},{\"id\":92563,\"title\":\"Tact"
            + "ical Combat\"},{\"id\":92564,\"title\":\"Astronomy\"},{\"id\":92565,\"title\":\"Calendar\"},{\"i"
            + "d\":92566,\"title\":\"Clipart\"},{\"id\":92567,\"title\":\"Copy\\/Backup\",\"categories\":[24486"
            + "7,244868,244869]},{\"id\":92568,\"title\":\"Cryptography\"},{\"id\":92569,\"title\":\"Database"
            + "\\/Filing\"},{\"id\":92571,\"title\":\"Educational Utility\"},{\"id\":92572,\"title\":\"Electron"
            + "ics\",\"categories\":[244876,244878]},{\"id\":92573,\"title\":\"Fonts\\/UDGs\"},{\"id\":92574,\""
            + "title\":\"Gambling\"},{\"id\":92575,\"title\":\"Game Editor\"},{\"id\":92576,\"title\":\"Graphic"
            + "s\",\"categories\":[244860,244862,244866,92566,92587,244883,543961]},{\"id\":92577,\"title\":\"D"
            + "ata Protection\",\"categories\":[244890,244891]},{\"id\":92578,\"title\":\"I\\/O Handling\"},{\""
            + "id\":92579,\"title\":\"Maths\\/Science\"},{\"id\":92580,\"title\":\"Media Admin\",\"categories\""
            + ":[92187]},{\"id\":92581,\"title\":\"Sound\",\"categories\":[244870,244871,244872,92585,244892]},"
            + "{\"id\":92582,\"title\":\"Prediction\"},{\"id\":92583,\"title\":\"Print\"},{\"id\":92584,\"title"
            + "\":\"Simulation\"},{\"id\":92585,\"title\":\"Sound\\/Speech utilities\",\"categories\":[244873,2"
            + "44874,244881]},{\"id\":92586,\"title\":\"Spreadsheet\"},{\"id\":92587,\"title\":\"Graphic utilit"
            + "ies\"},{\"id\":92589,\"title\":\"Texts\",\"categories\":[244865,244877,244884,244889,418662]},{"
            + "\"id\":92590,\"title\":\"undetermined\"},{\"id\":92591,\"title\":\"Electronic Book\"},{\"id\":92"
            + "595,\"title\":\"Misc\"},{\"id\":92596,\"title\":\"General\"},{\"id\":202586,\"title\":\"Game Cre"
            + "ator\"},{\"id\":202587,\"title\":\"Telecom\\/Network\"},{\"id\":202588,\"title\":\"Compilation\""
            + ",\"categories\":[202589,202590,202591,202592,202593,92533]},{\"id\":202589,\"title\":\"Education"
            + "al\"},{\"id\":202590,\"title\":\"Games\"},{\"id\":202591,\"title\":\"Magazines\"},{\"id\":202592"
            + ",\"title\":\"Scene Demos\"},{\"id\":202593,\"title\":\"Utilities\"},{\"id\":204150,\"title\":\"B"
            + "oot\"},{\"id\":204819,\"title\":\"Demoscene\",\"categories\":[92160,92159,92161,92162,92163,3151"
            + "36,92172,92173,315137,315121,537695,538831]},{\"id\":244858,\"title\":\"Press\",\"categories\":["
            + "92179,92180,92181,92182,92591,323045]},{\"id\":244860,\"title\":\"Graphic editors\"},{\"id\":244"
            + "862,\"title\":\"Graphic viewers\"},{\"id\":244863,\"title\":\"Debuggers\"},{\"id\":244864,\"titl"
            + "e\":\"Disk utilities\",\"categories\":[523509]},{\"id\":244865,\"title\":\"Notepads\"},{\"id\":2"
            + "44866,\"title\":\"Graphic converters\"},{\"id\":244867,\"title\":\"MS-DOS Copiers\"},{\"id\":244"
            + "868,\"title\":\"Disk\\/tape copiers\"},{\"id\":244869,\"title\":\"Disk copiers\"},{\"id\":244870"
            + ",\"title\":\"Music players\"},{\"id\":244871,\"title\":\"AY Music Editors\"},{\"id\":244872,\"ti"
            + "tle\":\"Beeper music editors\"},{\"id\":244873,\"title\":\"AY utilities\"},{\"id\":244874,\"titl"
            + "e\":\"Digital sound utilities\"},{\"id\":244875,\"title\":\"Operating systems\"},{\"id\":244876,"
            + "\"title\":\"Programmers (hardware)\"},{\"id\":244877,\"title\":\"Text viewers\"},{\"id\":244878,"
            + "\"title\":\"ROM\"},{\"id\":244880,\"title\":\"Applications\",\"categories\":[92547,92548,92549,9"
            + "2550,92564,92565,92568,92571,92572,92574,92575,92577,92579,92582,92584,92586,92589,202586,92569,"
            + "244886]},{\"id\":244881,\"title\":\"Sound editors\"},{\"id\":244882,\"title\":\"Tests\"},{\"id\""
            + ":244883,\"title\":\"Sprite editors\"},{\"id\":244884,\"title\":\"Word Processor\"},{\"id\":24488"
            + "5,\"title\":\"General viewers\"},{\"id\":244886,\"title\":\"Archivers\"},{\"id\":244887,\"title"
            + "\":\"Data Compression\"},{\"id\":244888,\"title\":\"Screen Compression\"},{\"id\":244889,\"title"
            + "\":\"Text utilities\"},{\"id\":244890,\"title\":\"Data protection utilities\"},{\"id\":244891,\""
            + "title\":\"Protection removal utilities\"},{\"id\":244892,\"title\":\"Digital Music Editors\"},{"
            + "\"id\":244893,\"title\":\"Programming languages\"},{\"id\":262450,\"title\":\"16b intro\"},{\"id"
            + "\":262451,\"title\":\"32b intro\"},{\"id\":262452,\"title\":\"64b intro\"},{\"id\":262453,\"titl"
            + "e\":\"128b intro\"},{\"id\":315119,\"title\":\"32K intro\"},{\"id\":315120,\"title\":\"128k intr"
            + "o\"},{\"id\":315121,\"title\":\"Art pack\",\"categories\":[92175,92176,315126]},{\"id\":315126,"
            + "\"title\":\"Demopack\"},{\"id\":315136,\"title\":\"Fast demo\"},{\"id\":315137,\"title\":\"Proce"
            + "dural graphics\"},{\"id\":323045,\"title\":\"Electronic letter\"},{\"id\":364978,\"title\":\"BBS"
            + "tro\"},{\"id\":417094,\"title\":\"8b intro\"},{\"id\":418662,\"title\":\"Publishing systems\"},{"
            + "\"id\":418663,\"title\":\"Tests\"},{\"id\":523371,\"title\":\"Photoalbum\"},{\"id\":523372,\"tit"
            + "le\":\"Source Code\",\"categories\":[523373,523374,523375,523376]},{\"id\":523373,\"title\":\"Ga"
            + "mes\"},{\"id\":523374,\"title\":\"Demos\"},{\"id\":523375,\"title\":\"Applications\"},{\"id\":52"
            + "3376,\"title\":\"E-Papers\"},{\"id\":523379,\"title\":\"Interactive Fiction\",\"categories\":[92"
            + "508,92510,92511,523390,523391]},{\"id\":523380,\"title\":\"Point-and-Click Adventures\"},{\"id\""
            + ":523381,\"title\":\"Puzzle Adventures\"},{\"id\":523386,\"title\":\"First-Person Dungeon Crawler"
            + "s\"},{\"id\":523387,\"title\":\"Top-Down Dungeon Crawlers\"},{\"id\":523388,\"title\":\"Isometri"
            + "c Dungeon Crawlers\"},{\"id\":523389,\"title\":\"Roguelike\"},{\"id\":523390,\"title\":\"CYOA (C"
            + "hoice-based IF)\"},{\"id\":523391,\"title\":\"Visual Novels\"},{\"id\":523392,\"title\":\"Side-s"
            + "crolling beat 'em up\"},{\"id\":523393,\"title\":\"Arena beat 'em up\"},{\"id\":523394,\"title\""
            + ":\"Hack and Slash\"},{\"id\":523395,\"title\":\"Run 'n' Gun\",\"categories\":[523396,523397,5233"
            + "98,523399]},{\"id\":523396,\"title\":\"Classic Run 'n' Gun\"},{\"id\":523397,\"title\":\"Top-Dow"
            + "n Run 'n' Gun\"},{\"id\":523398,\"title\":\"Vertical Scrolling Run 'n' Gun\"},{\"id\":523399,\"t"
            + "itle\":\"Arena Run 'n' Gun\"},{\"id\":523400,\"title\":\"Classic Platformers\"},{\"id\":523401,"
            + "\"title\":\"Puzzle Platformers\"},{\"id\":523402,\"title\":\"Action Platformers\"},{\"id\":52340"
            + "3,\"title\":\"Metroidvania\"},{\"id\":523404,\"title\":\"Classic Maze Games\"},{\"id\":523405,\""
            + "title\":\"Isometric Maze Games\"},{\"id\":523406,\"title\":\"3D Maze Games\"},{\"id\":523408,\"t"
            + "itle\":\"Endless Runners\"},{\"id\":523409,\"title\":\"One-on-One Fighters\"},{\"id\":523410,\"t"
            + "itle\":\"Weapon Fighters\"},{\"id\":523413,\"title\":\"Vertical Shmups\"},{\"id\":523414,\"title"
            + "\":\"Horizontal Shmups\",\"categories\":[537707,538440]},{\"id\":523417,\"title\":\"Multidirecti"
            + "onal Shmups\",\"categories\":[537711]},{\"id\":523418,\"title\":\"Bullet Hell\"},{\"id\":523419,"
            + "\"title\":\"Shooters\",\"categories\":[523421,523422,523423,92519,523395,536161,536696,537706,54"
            + "3856]},{\"id\":523421,\"title\":\"First-Person Shooter (FPS)\"},{\"id\":523422,\"title\":\"Third"
            + "-Person Shooter (TPS)\"},{\"id\":523423,\"title\":\"Rail Shooter\"},{\"id\":523425,\"title\":\"T"
            + "op-Down Racing\"},{\"id\":523426,\"title\":\"Behind View Racing\"},{\"id\":523427,\"title\":\"Fi"
            + "rst-Person Racing\"},{\"id\":523428,\"title\":\"Turn-Based Strategies\",\"categories\":[523430,5"
            + "36378]},{\"id\":523430,\"title\":\"Tactical Turn-Based\"},{\"id\":523431,\"title\":\"Real-Time S"
            + "trategies (RTS)\"},{\"id\":523432,\"title\":\"Tower Defense\"},{\"id\":523471,\"title\":\"Vehicl"
            + "e Simulators\",\"categories\":[523473,523474,523475,523476,523477,92538]},{\"id\":523473,\"title"
            + "\":\"Space Simulators\"},{\"id\":523474,\"title\":\"Racing Simulators\"},{\"id\":523475,\"title"
            + "\":\"Submarine Simulators\"},{\"id\":523476,\"title\":\"Ship Simulators\"},{\"id\":523477,\"titl"
            + "e\":\"Tank Simulators\"},{\"id\":523478,\"title\":\"Life Simulations\"},{\"id\":523479,\"title\""
            + ":\"Economic Simulations\"},{\"id\":523480,\"title\":\"Construction and Management Simulation\"},"
            + "{\"id\":523482,\"title\":\"Football Simulators\"},{\"id\":523483,\"title\":\"Basketball Simulato"
            + "rs\"},{\"id\":523484,\"title\":\"Billiard Simulators\"},{\"id\":523486,\"title\":\"Golf Simulato"
            + "rs\"},{\"id\":523487,\"title\":\"Bowling Simulators\"},{\"id\":523488,\"title\":\"Darts Simulato"
            + "rs\"},{\"id\":523490,\"title\":\"Logic Games\"},{\"id\":523492,\"title\":\"Solitaire\"},{\"id\":"
            + "523493,\"title\":\"Traditional Card Games\"},{\"id\":523494,\"title\":\"Chess\"},{\"id\":523495,"
            + "\"title\":\"Checkers\"},{\"id\":523496,\"title\":\"Backgammon\"},{\"id\":523497,\"title\":\"Othe"
            + "r Board Games\"},{\"id\":523498,\"title\":\"Casino\"},{\"id\":523499,\"title\":\"Roulette\"},{\""
            + "id\":523500,\"title\":\"Slots\"},{\"id\":523501,\"title\":\"Reversi\"},{\"id\":523502,\"title\":"
            + "\"Tic-Tac-Toe\"},{\"id\":523503,\"title\":\"Connect 4\"},{\"id\":523504,\"title\":\"Falling Bloc"
            + "k Games\"},{\"id\":523505,\"title\":\"Tile-Matching Games\"},{\"id\":523506,\"title\":\"Crosswor"
            + "ds\"},{\"id\":523507,\"title\":\"Sudoku\"},{\"id\":523508,\"title\":\"System Utilities\",\"categ"
            + "ories\":[244864]},{\"id\":523509,\"title\":\"Disk Editors\"},{\"id\":523510,\"title\":\"Data Com"
            + "pression and Archiving\",\"categories\":[92186,523511]},{\"id\":523511,\"title\":\"File Archiver"
            + "s\"},{\"id\":523512,\"title\":\"First-person adventure game\"},{\"id\":524248,\"title\":\"Side-S"
            + "crolling Racing\"},{\"id\":524249,\"title\":\"Top-Down Circuit Racing\"},{\"id\":524250,\"title"
            + "\":\"Maze Racing\"},{\"id\":525128,\"title\":\"21 Card Games\"},{\"id\":525129,\"title\":\"Betti"
            + "ng Games\"},{\"id\":525130,\"title\":\"Roll-and-Move Games\"},{\"id\":525131,\"title\":\"Economi"
            + "c Board Games\"},{\"id\":525132,\"title\":\"Battleship board games\"},{\"id\":525134,\"title\":"
            + "\"Guessing Games\"},{\"id\":525151,\"title\":\"Dominoes\"},{\"id\":535914,\"title\":\"Isometric "
            + "Shoot 'em Up\"},{\"id\":536161,\"title\":\"Shooting Gallery\"},{\"id\":536378,\"title\":\"Artill"
            + "ery games\"},{\"id\":536379,\"title\":\"City Bomber Games\"},{\"id\":536383,\"title\":\"Fixed Sh"
            + "ooter\",\"categories\":[551694,552112]},{\"id\":536655,\"title\":\"Puzzle Maze\"},{\"id\":536682"
            + ",\"title\":\"Snake\"},{\"id\":536696,\"title\":\"Scrolling Ground Shooters\"},{\"id\":537145,\"t"
            + "itle\":\"Block Breaker\"},{\"id\":537695,\"title\":\"Game Outro\"},{\"id\":537706,\"title\":\"Ba"
            + "se Defense Shooter\"},{\"id\":537707,\"title\":\"Scramble Shooters\"},{\"id\":537711,\"title\":"
            + "\"Asteroids Shooters\"},{\"id\":538283,\"title\":\"Rescue Lander\"},{\"id\":538440,\"title\":\"D"
            + "efender shooters\"},{\"id\":538509,\"title\":\"Political Simulations\"},{\"id\":538817,\"title\""
            + ":\"Tennis Simulator\"},{\"id\":538818,\"title\":\"Baseball Simulator\"},{\"id\":538819,\"title\""
            + ":\"Cricket Simulator\"},{\"id\":538820,\"title\":\"Rugby Simulator\"},{\"id\":538821,\"title\":"
            + "\"Boxing Simulator\"},{\"id\":538822,\"title\":\"Cycling Simulator\"},{\"id\":538831,\"title\":"
            + "\"Promo Demo\"},{\"id\":543856,\"title\":\"Maze Shooter\"},{\"id\":543857,\"title\":\"Sokoban\"}"
            + ",{\"id\":543864,\"title\":\"Minesweeper\"},{\"id\":543865,\"title\":\"Memory\"},{\"id\":543924,"
            + "\"title\":\"Arena shmup\"},{\"id\":543961,\"title\":\"Video Players\"},{\"id\":551694,\"title\":"
            + "\"Pang-inspired\"},{\"id\":551860,\"title\":\"Series\"},{\"id\":552112,\"title\":\"Centipede\"}]"
            + "},\"responseStatus\":\"success\"}";

    /**
     * review/zxart/af-prodsearch.json - export:zxProd,
     * filter:zxProdSearch=... - copied whole, all 3 of the returned rows
     * (totalAmount 6, limit 3 - a real first page of a real second one).
     *
     * Built with a {@link StringBuilder} rather than the {@code +}-chunked
     * literal every other fixture here uses: the escaped body is ~142 KB, and
     * javac folds a chain of literal concatenation into one constant at
     * compile time - {@code constant string too long} past 65535 bytes of
     * modified UTF-8, measured directly against this file's size. Trimming
     * the two large rows (magazine issues, each carrying dozens of scanned
     * pages) to fit the literal form would have thrown away real, captured
     * bytes to work around a javac limit instead of the reply being too big
     * to keep - so the reply stays whole and the encoding works around the
     * limit instead.
     */
    public static final String PROD_SEARCH;

    static {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"totalAmount\":6,\"start\":0,\"limit\":3,\"responseData\":{\"zxProd\":[{\"id\":100938,\"title\":\"Head over Heels\",\"dateCreated\":1479491346,\"dateModified\":1786309961,\"language\":[\"en\"],\"yea");
        sb.append("r\":1987,\"youtubeId\":\"PdRuvdvLbjg\",\"legalStatus\":\"unknown\",\"groupsIds\":[176471],\"publishersIds\":[176471],\"releasesIds\":[100941,100942,100943,100944,256055,256056,256057,256058,256059,256");
        sb.append("060,256063,329442,329443,437366,437371,453832,453833,453834,497492],\"imagesUrls\":[\"https:\\/\\/zxart.ee\\/zximages\\/id=100939;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/screenshot\\/");
        sb.append("id:549905\\/head.gif\",\"https:\\/\\/zxart.ee\\/screenshot\\/id:100940\\/HeadOverHeels.gif\"],\"maps\":[\"https:\\/\\/zxart.ee\\/release\\/id:241668\\/mode:download\\/filename:HeadOverHeels.jpg\",\"ht");
        sb.append("tps:\\/\\/zxart.ee\\/release\\/id:241669\\/mode:download\\/filename:HeadOverHeels_2.jpg\",\"https:\\/\\/zxart.ee\\/release\\/id:241670\\/mode:download\\/filename:HeadOverHeels_3.jpg\",\"https:\\/\\/zx");
        sb.append("art.ee\\/release\\/id:241671\\/mode:download\\/filename:HeadOverHeels_4.png\",\"https:\\/\\/zxart.ee\\/release\\/id:375816\\/mode:download\\/filename:HeadoverHeels.png\"],\"authorsInfo\":[{\"id\":4514");
        sb.append(",\"authorId\":5912,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"unknown\"],\"type\":\"prod\"},{\"id\":4515,\"authorId\":5926,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"unknown\"],\"type\":\"");
        sb.append("prod\"},{\"id\":4516,\"authorId\":6110,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"loading_screen\"],\"type\":\"prod\"},{\"id\":4517,\"authorId\":42683,\"startDate\":\"\",\"endDate\":\"\",\"roles");
        sb.append("\":[\"unknown\"],\"type\":\"prod\"},{\"id\":25328,\"authorId\":197085,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"illustrating\"],\"type\":\"prod\"}],\"importIds\":{\"maps\":\"HeadoverHeels\",\"z");
        sb.append("xdb\":\"2259\",\"wos\":\"0002259\",\"vt\":\"cae49924d18d8c8a04c2e09ce1d40e0c\"},\"votes\":4.07,\"votesAmount\":5,\"rzx\":[\"https:\\/\\/zxart.ee\\/release\\/id:372594\\/mode:download\\/filename:head.r");
        sb.append("zx\"],\"connectedCategoriesIds\":[523405],\"categoriesString\":\"Games\\/Action\\/Maze\\/Isometric Maze Games\"},{\"id\":349650,\"title\":\"Fred issue 59\",\"dateCreated\":1588720233,\"dateModified\":");
        sb.append("1786311446,\"language\":[\"en\"],\"year\":1995,\"description\":\"<p><strong>Issue 59<\\/strong><\\/p>\\n<table class=\\\"table_component\\\">\\n<thead>\\n<tr>\\n<th>Item<\\/th>\\n<th>Author<\\/th>\\n<");
        sb.append("th>Description<\\/th>\\n<\\/tr>\\n<\\/thead>\\n<tbody>\\n<tr>\\n<td>Menu<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:terry-ekins\\\" title=\\\"Click to view a local no");
        sb.append("de.\\\">Terry Ekins<\\/a><\\/span> <span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:steven-ekins\\\" title=\\\"Click to view a local node.\\\">Steven Ekins<\\/a><\\/span><\\/td>\\n<td>");
        sb.append("\\u00a0<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Editorial<\\/td>\\n<td>\\u00a0<\\/td>\\n<td>Spectrum Discs, Interrupts, Basic Bugs<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Letters<\\/td>\\n<td>\\u00a0<\\/td>\\n<td>Fred Im");
        sb.append("provements, Scrabble, <span><a href=\\\"\\/route\\/type:prod\\/importOrigin:worldofsam\\/importId:sam-c\\\" title=\\\"Click to view a local node.\\\">Sam C<\\/a><\\/span><\\/td>\\n<\\/tr>\\n<tr>\\n<td");
        sb.append(">Gem-X<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:andrew-chandler\\\" title=\\\"Click to view a local node.\\\">Andrew Chandler<\\/a><\\/span><\\/td>\\n<td>Puzzle-Ish");
        sb.append(" Game By Andrew Chandler<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Space Demo<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:david-laundon\\\" title=\\\"Click to view a local node.");
        sb.append("\\\">David Laundon<\\/a><\\/span><\\/td>\\n<td>M\\/C Space Demo With St:Tng Sample<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Sprite Utility<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/impo");
        sb.append("rtId:marc-broster\\\" title=\\\"Click to view a local node.\\\">Marc Broster<\\/a><\\/span><\\/td>\\n<td>M\\/C Routines For Building Sprite Data<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Sports Game<\\/td>\\n<td>");
        sb.append("<span><span>Search: \\u201cJames Curry\\u201d<\\/span><\\/span><\\/td>\\n<td>Two Player, Future Footy Game<\\/td>\\n<\\/tr>\\n<tr>\\n<td>'The' Interview<\\/td>\\n<td><span><a href=\\\"\\/route\\/impor");
        sb.append("tOrigin:worldofsam\\/importId:colin-anderton\\\" title=\\\"Click to view a local node.\\\">Colin Anderton<\\/a><\\/span><\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:ma");
        sb.append("tt-round\\\" title=\\\"Click to view a local node.\\\">Matt Round<\\/a><\\/span> Reveals All (Fnar!)<\\/td>\\n<\\/tr>\\n<tr>\\n<td>E-Tunes<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldo");
        sb.append("fsam\\/importId:roger-hartley\\\" title=\\\"Click to view a local node.\\\">Roger Hartley<\\/a><\\/span> <span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:jack-bailey\\\" title=\\\"Click");
        sb.append(" to view a local node.\\\">Jack Bailey<\\/a><\\/span><\\/td>\\n<td>Music Written On <span><a href=\\\"\\/route\\/type:prod\\/importOrigin:worldofsam\\/importId:e-tracker\\\" title=\\\"Click to view a ");
        sb.append("local node.\\\">E-Tracker<\\/a><\\/span><\\/td>\\n<\\/tr>\\n<tr>\\n<td>Modules<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:martin-fitzpatrick\\\" title=\\\"Click to vi");
        sb.append("ew a local node.\\\">Martin Fitzpatrick<\\/a><\\/span><\\/td>\\n<td>Converted Amiga Modules<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Immortal Combat<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldof");
        sb.append("sam\\/importId:ben-yates\\\" title=\\\"Click to view a local node.\\\">Ben Yates<\\/a><\\/span><\\/td>\\n<td>Simple 2-Player Beat-Em-Up<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Rachel 13&amp;14<\\/td>\\n<td><spa");
        sb.append("n><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:andrew-hodgkinson\\\" title=\\\"Click to view a local node.\\\">Andrew Hodgkinson<\\/a><\\/span><\\/td>\\n<td>Continuing Tales...<\\/td>\\n<");
        sb.append("\\/tr>\\n<tr>\\n<td>Miall Help<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:andrew-chandler\\\" title=\\\"Click to view a local node.\\\">Andrew Chandler<\\/a><\\/span>");
        sb.append("<\\/td>\\n<td>Help For Miall<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Upward Scroll<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:martin-fitzpatrick\\\" title=\\\"Click to view a l");
        sb.append("ocal node.\\\">Martin Fitzpatrick<\\/a><\\/span><\\/td>\\n<td>How To Write An Upward Scrolly<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Universe<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/");
        sb.append("importId:darren-martin\\\" title=\\\"Click to view a local node.\\\">Darren Martin<\\/a><\\/span><\\/td>\\n<td>Simulate Planetary Behaviour!<\\/td>\\n<\\/tr>\\n<\\/tbody>\\n<\\/table>\\n<h3>Magazine<");
        sb.append("\\/h3>\\n<pre>CA                         Editorial\\n\\nMy exams are over, I've nothing to do and summer is here.  What\\na life.\\n\\nAnyway, enough about me.  How are you all?  It feels like years");
        sb.append("\\nsince I last did any work on FRED.  You must have been bored\\nstupid having to wait a whole 5 weeks for issue 59.  Four weeks\\nis a hard enough struggle as it is.  Well, you'll be pleased to\\nkn");
        sb.append("ow that I'm literally bubbling with energy and I'm ready to\\njibber on for hours.  Make yourself comfy.\\n\\nBeing my usual egotistical (Christ, I've used a word with 5\\nsyllables) self, I'll begin ");
        sb.append("with what has happened to me in the\\nlast 5 weeks.  After I run out of things to say, I'll probably\\nmake up a few lies, rant on about something that has nothing to\\ndo with anything and then go on");
        sb.append(" to the news.  Think you can\\nhandle all that?  Of course you can.\\n\\nBy the way, incase anyone REALLY thick reads FRED, try pressing\\nthe right cursor - you'll find there's more than 1 page!\\nCA");
        sb.append("       I Love Myself (but not as much as others do)\\n\\nWhy have I stopped using capital letter in my titles?  It's\\nbecause it takes up too much effort typing it all in in capital\\nletters.\\n\\nA");
        sb.append("nyway, June has been a rather hectic month for me.  After doing\\nvery little revision during exam leave, I had a last minute\\nRimmer-like realisation that there was no way I could cram\\nenough revi");
        sb.append("sion into the few remaining days to get me through to\\nthe next weekend.  Consequently, I accidently let my physics\\nnotes blow out the window so couldn't revise it (I don't need\\nphysics to get to");
        sb.append(" university, by the way).  I then had just the\\nright amount of days and hours before exams to do adequate\\nrevision.\\n\\nThe exams went alright - a couple of nasty ones chucked in, but\\nthat was ");
        sb.append("expected.  I think I've done enough to get to either\\nNottingham or Warwick, but I'm not going to mention it until I\\nget my results, just in case I haven't - I couldn't stand\\nhundreds of people w");
        sb.append("riting in just so they can laugh at me.\\nCA                   Following my exams...\\n\\nOn the morning of June 26th, I had my last exam.  On the night,\\nI begun night-shift at Advanced (ahem) Marke");
        sb.append("ting Systems (AMS)\\npacking strawberries, just to earn some proper money (I do hope\\nColin Macdonald isn't feeling guilty).  It's not that bad there,\\nthe night goes really quickly and a lot of peo");
        sb.append("ple who go are\\nuniversity students who I knew from the year before.  And the\\nsupervisor gives me lots of really easy jobs.\\n\\nOn Thursday and Friday (29th and 30th) I had the nights off\\nbecaus");
        sb.append("e lots of us A-level students who did maths were having a\\nbalti and the 30th was my birthday and celebrations were called\\nfor.  After the maths balti (and the pub), we went down by the\\nriver and");
        sb.append(" waited for my birthday.  An hour later I was 18, and\\nto celebrate I hugged everyone and then leapt in the river.  It\\nwas a perfectly rational decision and had nothing to do with\\ncertain mind af");
        sb.append("fecting liquids.  I had to walk 6 miles home in\\nmy wet shorts and T-shirt as well - good job it was warm.\\n\\nThe next night, I had a planned celebration, with a barbeque at\\nCA                   ");
        sb.append("  Sweet Eighteen...\\n\\nmy house.  For no reason whatsoever, we had a game of rugby in\\nthe garden half way through the night, and all ended up bleeding\\neverywhere (I've got a few spikey bushes in");
        sb.append(" my garden - and the\\nground is rock solid).  I smashed my jaw into someones head and\\nthe next day I could hardly eat, which was a bummer because I'd\\ngot so much food for my birthday.  It still h");
        sb.append("urts and I've got a\\nmassive scratch going from my mouth to my ear from where I ran\\ninto a bush AND my knee is cut to shreds.  Ah, what fun.\\n\\nSo that's the story up until now, the 2nd of July. ");
        sb.append(" It's 1:50 in\\nthe morning.  You see, after I decided to take Thursday and\\nFriday off, I then found out that there won't be any work until\\nMonday.  Still, it's a good job really because I've done");
        sb.append(" no work\\nto FRED and I've only got a few days left.  It just means that\\nI'm still working night-shift, just doing FRED rather than\\npacking red fruit.\\n\\nAnd now the lie.  I also found six thou");
        sb.append("sand pound on the way\\nhome and spent it all on some magic beans.  They didn't grow.\\nCA              Happy Birthday to You (Nearly)\\n\\nA last reminder to all FRED readers that the next issue, iss");
        sb.append("ue\\n60, will be FREDs 5th birthday.  I know the actual birthday was\\ntwo months ago, but I'm a mathematician, and I'll only accept\\nthat 5 times 12 is 60, not 57 or 58 (or 11).  Anyway, can I ask");
        sb.append("\\nall those programmers out there to make a really special effort\\nto write something for issue 60.  We need some really special\\ncontributions that people will remember, as well as the usual\\nspl");
        sb.append("attering of your wonderful games and utilities.  The deadline\\nfor contributions is July 31st (which is a little later than\\nusual, but we're giving you a bit longer to make your program\\nthat litt");
        sb.append("le bit better).  And as a big thankyou, every item that\\nwe would normally give a \\u00a35 voucher will receive a \\u00a310 voucher\\ninstead.  What more can we say, except GET A MOVE ON!!!\\n\\nReme");
        sb.append("mber also that the 'Incredibly Rubbish Game Competition'\\nwill be decided for issue 60.  We've got a couple of hilariously\\nbad games already, and we need more.  Those people who were\\nworried that");
        sb.append(" it'll make the issue tacky, don't despair - it will\\nonly be replacing either Bits n Bobs or MODs, not slot D.\\nCA                            News\\n\\nWant to meet the SAM programmers and other SA");
        sb.append("M owners?  Don't\\nhave the time to do that at the shows?  Feel like a bit of fun?\\n\\nWell, FRED and Crashed are organising a SAM owners day out at\\nAlton Towers.  Alton Towers, for those who don't");
        sb.append(" know, is a\\nlarge amusement\\/attractions park with loads of roller coasters,\\nsome excellent water rides, a big Kiddies Kingdom (I'll be going\\nthere!), a Ghost House, Pirate Ship and the slightl");
        sb.append("y calmer Tea\\nCup ride and Swan Ride, as well as dozens and dozens of other\\nrides and acres of beautiful gardens.  It's a day out for all\\nthe family, not to mention lots of mad computer owners.");
        sb.append("\\n\\nThe date will be August 27th, which is a Sunday.  National\\nExpress coaches do some very cheap journeys to Alton Towers on a\\nSunday and there are parking spaces for thousands of cars.\\nAltho");
        sb.append("ugh it is strictly for SAM owners, please bring your family\\nand friends.  I am trying to arrange some discount tickets, but\\ndon't count on it - you know what people can be like.\\n\\nCA           ");
        sb.append("      Where Wonders Never Cease?\\n\\nThe day will obviously be spent having a great deal of fun,\\nalthough I'm sure the computer will be talked about a lot in\\nthose long queues.  We will try to st");
        sb.append("ick together (no matter how\\ndifficult it will be) and already a number of SAM personalities\\nhave said they'll be coming.  There will be two meeting times\\nand places that will be arranged, but I'");
        sb.append("ll give full details in\\nthe next issue.  However, the date (August 27th 1995) is fixed,\\nso book your time off work (if you work on a Sunday) and arrange\\nsome transport.  Remember, it's easiest t");
        sb.append("o drive there (Alton\\nTowers is between Derby and Stoke-on-Trent) but National Express\\ncoaches provide a number of services from everywhere so give\\nthem a ring to book a place.\\n\\nIf anyone has");
        sb.append(" any intelligent suggestions, or has thought of\\nsomething that Alan Clarkson (from Crashed) or I may have\\nmissed, please leave a message with Colin Macdonald and I'll get\\nin touch.\\n\\nFull det");
        sb.append("ails next month.  Get planning!\\nCA                            News\\n\\nGraham Burtenshaw (SAMPaint author) will soon begin work on a\\nSAMPaint upgrade disc which will include dozens of new feature");
        sb.append("s\\nthat will work alongside your current SAMPaint disc.\\nUnfortunately, Graham has made SAMPaint so perfect that he needs\\nsome more ideas!  This is also the reason that this disc has\\ntaken so lo");
        sb.append("ng to come out.  If there is anything that you would\\nlike to see implemented as an add-on, give Colin a ring at the\\nusual number.\\n\\nThanks to everyone who has ordered SAM C so far - it has now");
        sb.append("\\nbecome one of the biggest selling FRED titles.  That isn't to\\nsay that the other people shouldn't buy it.  We're also on the\\nlook out for any games or utilities that anyone has written in C\\no");
        sb.append("r ported from another computer.  Apart from two wonderful\\nmenus, we haven't seen much yet.  There are a few games being\\nworked on, but we want more. And all you mums and dads out there\\n- remembe");
        sb.append("r it's the summer and you'll have your kids under your\\nfeet for 8 weeks.  So why not buy them SAM C?  Make them learn a\\nnew programming language, that'll get them out of your way!\\nCA            ");
        sb.append("                News\\n\\nRetros, the Thrust type game by Matt Round, has been delayed due\\nto difficulty getting some of the levels right - it really is\\ngoing to be that accurate!  Couple this wit");
        sb.append("h his other\\ncommitments (see \\\"the\\\" interview), and you can understand why it\\nhas been delayed.  However, if all goes well, the game should be\\nready for release in about a month.\\n\\nColin");
        sb.append(" tells me that not many people are content with the mystery\\nof ordering a Spectrum games disc and seeing what is on it when\\nthey get it.  Miserable lot.\\n\\n                   DISC 1 - THOSE PESK");
        sb.append("Y KIDS\\n\\nJack The Nipper 1           Bobby Bearing     Bumpy\\nJack The Nipper 2           Bak 2 Skool       Cosmos\\nSkool Daze                  Olli &amp; Lisa       Megabucks\\nLittle Puff In Dr");
        sb.append("agon Land  Clumsy Colin      Paperboy\\nGregory Loses His Clock     Space Harrier\\n\\nCA                            News\\n\\n               DISC 2 - SAVING THE WORLD... AGAIN\\n\\n   Rygar       Sta");
        sb.append("rquake      Universal Hero        Driller\\n   Guardian 2  Starglider     Nuclear Countdown     Hysteria\\n   Silkworm    Rex            Switchblade           Darkstar\\n\\n                   DISC 3 -");
        sb.append(" JUST DOING MY JOB\\n\\nSoul of a Robot        Mailstrom          Ikari Warriors\\nSweevos World          Deactivators       Peter Pack Rat\\nStrike Force Cobra     Turbo Esprit       Spooked\\n3D Gra");
        sb.append("nd Prix          Rolling Thunder    City Slicker\\nHydrofool              North Star\\n\\n            DISC 4 - SUPERHEROS (MAINLY CALLED DAN)\\n\\nBatman 3d         Soldier One  Strontium Dog       Ro");
        sb.append("gue Trooper\\nHead Over Heels   Bombjack     Bomber Bob          Transformers\\nDan Dare 1,2 &amp; 3  Bombjack 2   Dynamite Dan 1 &amp; 2\\nCA                            News\\n\\nSo there you go.  No");
        sb.append("w stop being so selfish and buy all four\\ndiscs.\\n\\nAnd finally, that useless boy Macdonald made a huuuuuuuuuge\\nmistake in his section last month when he reviewed the SAMdac.\\nThe address to sen");
        sb.append("d cash to (or Eurocheques for 70 Guilders) is\\nnot Stefan's address, but is in fact:\\n\\n                          [redacted]\\n\\nSome people, eh?  Tell you what, I'll give Colin one more chance\\n");
        sb.append("to get his act together.\\n\\nGo on Colin...\\n\\n\\nCM                        He's Back!\\n\\nYes, my exams are finally over! I don't have to worry about\\ngetting up early in the mornings to study, ");
        sb.append("or stay at University\\nuntil late at night! Those were my degree exams, so providing\\nI've passed them, I now have a normal degree in Computing!! The\\nresults don't come out in time for this issue,");
        sb.append(" but I'll keep you\\ninformed!\\n\\nOf course, the most important aspect of this is that I can now\\nreturn to answering the FRED phone! I know this was a bit of an\\ninconvenience for some of you, an");
        sb.append("d thanks for putting up with\\nit, but normal service can now resume!\\n\\nI have until October to enjoy my free time - sunbathing, working\\non FRED, studying for next year (cough) and I might even f");
        sb.append("ind\\nthe time to catch up on a bit of socialising!  [COUGH, COUGH,\\nSPLUTTER, COUGH, SPLUTTER, CHOKE!!!!!!! - CA]\\n\\n\\n\\nCM                    Been away.... again\\n\\nYou wouldn't have thought ");
        sb.append("that I could have done much seeing as\\nmy exams finished only the other week would you?\\n\\nHowever, late in June, Adrian Parker got married to his fiancee\\nof three years, Catherine. I've known Ad");
        sb.append("rian for about four and\\na half years - firstly because when he ran Blue Alpha\\nElectronics I was always pestering for exclusives, news and\\nreview copies - although the news was always forthcoming");
        sb.append(", the\\nnearest I got to a review copy was two pounds off a sound\\nsampler! Later, I had the pleasure of working with Adrian at\\nSAMCo during which time I learnt a lot from him about hardware,\\nand");
        sb.append(" just possibly he learnt a bit about software from me! We\\nalso spent many an hour brainstorming hundreds of ideas for new\\nSAM \\/ Computing devices - the only one to appear was the\\nill-fated Kal");
        sb.append("eidoscope. And then when I wasn't beating him at\\nOthello, we actually managed to get quite a bit of work done!\\n\\n\\n\\nCM                     The happy couple\\n\\nAs \\\"exclusively\\\" reported");
        sb.append(" in FRED three years ago (FRED23?)\\nAdrian proposed to Catherine at a Karaoke night in a Swansea pub\\n- in front of all the SAMCo team, as well as several hundred\\ndrunken revellers!\\n\\nSince the");
        sb.append("n, Adrian and Catherine have been very happy together,\\nand they finally tied the knot just a few weeks ago. Not being\\none to miss out on free drink, I travelled the 500-odd miles for\\nthe occasio");
        sb.append("n. And yes, I did wear my kilt. In Wales. And I had\\nto walk along a high street on a Saturday morning with it on.\\nBut I wasn't embarressed, no really, I wasn't!\\n\\nBearing in mind that I've know");
        sb.append("n Adrian for less than five years,\\nwhen we were sitting down for the meal in the afternoon, one of\\nAdrian's old school friends leaned across the table and said\\n\\\"God Colin, you've changed a lo");
        sb.append("t in seven years\\\" , slightly\\ndumbstruck I told him that I would barely have been a teenager\\nseven years ago - let alone one travelling to Wales! We\\neventually worked out that the sole occasio");
        sb.append("n I had met this\\nCM\\n\\nguy was at a raft race less than two years ago! And I thought I\\nwas the one going mad?!\\n\\nWearing a kilt at such an occasion attracts quite a lot of\\nattention - mainl");
        sb.append("y by the way of \\\"Are you a true Scotsman?\\\" and\\n\\\"So, you're from Scotland then?\\\". Neither of which warranted an\\nanswer. However, there was one guy that I started speaking to\\nafter a k");
        sb.append("ilt-related remark (well, he refused to let me go until\\nhe'd bought me a drink - who was I to refuse?). He was telling\\nme that the next time I was in Wales I should look him up and\\nhe'd show me ");
        sb.append("some of the sights - which was an attractive offer\\nanyway, but after X drinks, it seemed positively brilliant.\\n\\nAnyway, there was a huge rush for me to get the train the next\\nmorning - it was ");
        sb.append("the last train that I could get that would get\\nme back in time for one of my exams! So I woke up the next\\nmorning, and immediately panicked - assumming I'd slept in and\\nwould need to rush for th");
        sb.append("e train. But after I'd woken up\\nproperly it turned out I had three hours until my train.\\nCM\\n\\nExcellent, I thought. I'd have a nice long lie in, get a proper\\nbreakfast, have a wander along Sw");
        sb.append("ansea beach and get back to the\\ntrain station in ample time.\\n\\nTen minutes later there's a knock on the door - it's none other\\nthan Geoff who'd been offering to show me the sights of Wales.\\n");
        sb.append("\\\"Seeing as you've come all the way from Scotland, I couldn't let\\nyou get a taxi to the train station\\\" he says (looking remarkably\\nless hungover than I was). \\\"That's great Geoff\\\", I rep");
        sb.append("lied, \\\"but\\nmy train isn't for another three hours\\\".\\n\\n\\\"Oh\\\". This puzzled him as much as his arrival had puzzled me.\\n\\\"No problem though, I'll show you a bit of Wales now!\\\". So ");
        sb.append("off\\nwe went, on a high-speed tour of the sights of South Wales -\\nbeaches, piers, cliffs, magnificent views of Swansea and vast\\nstretches of beautiful countryside - and then, before we knew,\\nmy");
        sb.append(" train was in 15 minutes. \\\"No problem Colin\\\" - as we travelled\\nacross Wales in half the time it was supposed to take, until we\\narive back in the centre of Swansea with five minutes to go.\\n");
        sb.append("CM\\n\\nJust as I was thinking that the five minutes would give us ample\\ntime to get parked and get on the train, Geoff pipes up \\\"I'll\\njust show you one more thing\\\" as we tear up the hill Sw");
        sb.append("ansea has\\nexpanded up. We reach half way up (three minutes to go) where\\nyou can see over the whole of Swansea as well as a magnificent\\nview of the surrounding bays and towns. With two minutes to");
        sb.append(" go,\\nwe head station-ward, once we arrive at the station (with about\\n30 seconds left) there's nowhere to park, but being in a massive\\nlandrover, it gets bumped up a kerb and left sitting with al");
        sb.append("l\\nfour wheels on the pavement! Just in time for me to thank Geoff\\nfor the excellant, but slightly worrying, morning and jump on\\nthe train SECONDS before it left!\\n\\nMy lack of breakfast soon c");
        sb.append("aught up with me (well, it was an\\neleven hour train journey!) and I found myself spending a\\nfortune to BR's profit. So when we pulled into Edinburgh and\\nthe inspector announced a twenty minute d");
        sb.append("elay, I jumped off the\\ntrain, down the high street, found a Burger King, and ran back\\nto catch the train for Dundee!\\nCM\\n\\nI got back in about midnight on the Sunday night, and with a\\ndegree");
        sb.append(" exam the next day, most people would have spent the vast\\nmajority of the time studying. Not me. I spent 12 hours sleeping\\nbefore getting an hour's revision in and going in to the exam.\\nStill, I");
        sb.append("'m quietly confident that I'll pass.\\n\\nAt least, I'm confident that I'll be quiet if I don't pass.\\n\\nAnyway, that was the tale of Adrian and Catherine's happy day,\\nso I hope you'll all wish th");
        sb.append("em every happiness, and that I'll\\nget invited to any anniversary do's they have - as long as they\\ndon't put them the day before my first degree exam!\\n\\nAs mentioned in last month's newsletter, ");
        sb.append("although there is still\\na waiting list for Disc Drives, SAMBuses and Mice, we do have\\nother hardware in stock - 1 Megs, 256Ks, Printer Interfaces,\\nPower Supplies and Keyboards. If you're interes");
        sb.append("ted in any of\\nthese, give me a ring to check I've still got them.\\n\\nCA           Lemmings Codes - The Final Chapter...\\n\\nSorry about that really cruel thing I did last month.  Has\\nanyone had");
        sb.append(" any luck in completing ON!ML?  If not, then here are\\nthe last three codes for Havoc.  I'm not going to print the\\ncompletion screen password ever, so you'll have to at least\\ncomplete the last le");
        sb.append("vel by yourselves.\\n\\nLevel         Name                               Code\\n\\n 18      Lemmings in a situation                 OBAIGJML\\n 19      Looks a Big Nippy Out There             RHQXIJJC");
        sb.append("\\n 20      LOoK BeFoRe YoU LeAp!                   QHZWIJPI\\n\\nAnd there you have it.  You can now play every level to the\\nsuperb Oh No! More Lemmings.  Value for money or what?\\n\\n\\nOn the ne");
        sb.append("xt page is an article on solving problems with\\nE-tracker, Mousedriver and BASIC.  I don't know who did it, but\\nit is VERY useful, especially the last bit about SAM BASIC.\\n??                   Ti");
        sb.append("resome Interruptions\\n\\nSome of you have found difficulty in installing and running the\\ninterrupt driven player from the E-tracker disc with the new\\nmousedriver (though the same applies to the o");
        sb.append("ld mousedriver).\\n\\nThere are two solutions, depending if you have MasterBASIC\\nloaded or not:\\n\\nSAMDOS: Run the interrupt poker routine\\n        Enter this poke: DPOKE &amp;5BC8,16384+130\\n  ");
        sb.append("      Load Mousedriver\\n\\nIt moves the end of the system heap, protecting the player so\\nits not overwritten.  The player HAS to go in first otherwise it\\ncrashes.  I've tried relocating the playe");
        sb.append("r, but it doesn't like\\nit anywhere else.\\n\\nMASTERBASIC:\\n        Run the interrupt poker routine\\n        Enter LET j=RESERVED(130)\\n??                     E-Tracker Cracker!\\n\\n        Load");
        sb.append(" Mousedriver\\n\\nThis does the same as above, but in a neater way.\\n\\nAnother point is that the interrupt E-tracker player corrupts\\nlarge BASIC files if you have the tune running while trying to");
        sb.append("\\nSAVE or LOAD.  You must turn the tune off to make sure you don't\\ncorrupt your file.  But the moral is KEEP BACKUPS!  I found to\\nmy horror that my backup was quite a few versions behind.\\n\\nOn");
        sb.append("e trick with E-tracker is if you wish to pause the interrupt\\ntune to say, play another, then resume it do this:\\nOnce the tune is playing enter:\\n                  DPOKE 23408,73 to stop it.\\n   ");
        sb.append("               DPOKE 23408,16436 to restart it.\\n\\n(The above poke is to a vector which is called 50 times a\\nsecond.  It normally calls address 73 in the ROM but in the\\ntechnical manual it just ");
        sb.append("says reserved.  Anyone know what it\\n??                      BASIC Solutions\\n\\ndoes?)\\n\\nWhen you load the new mousedriver, place a short pause, say\\nPAUSE 5 after loading it, or it may default");
        sb.append(" to keys\\nautomatically even if the mouse is connected, if you have POKEs\\nafter the LOAD.\\n\\nLastly, has anyone else had problems with SAM Basic [Has anyone\\nNOT had problems! - CA] with the ver");
        sb.append("sion 3.0 ROM fitted.  SAM\\noccasionally starts to behave strange.  For example, the line:\\n\\nPRINT AT 1,5;a$ would give me the error 'NONSENSE IN BASIC' when\\nrun, but when entered from the keyboa");
        sb.append("rd it worked OK.  The\\nsolution, if you have this problem, is to make sure there is\\nonly ONE command per line.  DO NOT separate commands with a\\ncolon.  If this still persists, enter some rem stat");
        sb.append("ements at the\\nstart of the program to push the BASIC away from a page\\nboundary.\\n\\nCA                       Disc Contents\\n\\nThis months selection should keep you all contented (puke),\\nbecau");
        sb.append("se once again we've got a wide selection of things so you\\nmust like something (as well as my editorial, of course).\\n\\nThe slot D tenner goes to Andrew Chandler this month with a\\nreally nice loo");
        sb.append("king puzzle\\/strategy game called 'Gem-X'.  The\\nidea of the game is to match the colour of the tiles on the left\\nside of the screen with those on the right.  This is done by\\nselcting a gem.  Th");
        sb.append("is will then increase it's colour up the\\nscale by two and the surrounding ones will go up by one colour.\\nSounds easy, doesn't it?  Well, the first few levels are, but\\nthey soon get harder.  Than");
        sb.append("kfully, there is a password option,\\nas well as proper instructions AND a level designer!  Mouse or\\ncursors can be used, and it is presented beautifully.  Thanks\\nAndrew.\\n\\nIn slot E is somethi");
        sb.append("ng that FRED has been missing for a long\\ntime - a demo with sampled speech.  The demo is in two parts.\\nThe first part is a Star Trek style starfield which you can\\nCA                       Disc C");
        sb.append("ontents\\n\\ndrive around using the controls given to you at the start.  By\\npressing space, you will hear Captain Picard saying a rather\\nfamous speech in the Star Trek community.  Everything is wr");
        sb.append("itten\\nin machine code and it looks and sounds terrific.  David Laundon\\nis the brains behind it all, so a big thanks goes to him.\\n\\nSlot F brings us a utility from Marc 'same Birthday and age as");
        sb.append("\\nColin Anderton, and lives in his birthplace - spooky' Broster.\\nAfter having to ignore this utility for months because I\\ncouldn't understand it and I just didn't have the time, I\\nfinally got r");
        sb.append("ound to ringing him, and now I think I've got it\\nsorted.  It's a useful utility for machine code programmers\\nbecause there are two ways in which you can play with sprites -\\nas a whole or individ");
        sb.append("ually.  Doingt it individually is much\\nquicker, and this is a program which creates the mask and\\ndetails and code necessary for this procedure.  Unfortunately,\\nit'7s completely useless for us no");
        sb.append("n machine coders, but is\\napparently very clever, and extremely useful for machine coders\\nall around the country.  So, thanks on behalf of them, Marc.\\nCA                       Disc Contents\\n\\n");
        sb.append("James Curry appears to be doing his best to get a game on every\\nissue of FRED for a year with a nice little two player sports\\ngame.  The aim of the game is to smash the little ball thing\\ninto th");
        sb.append("e other players goal, a sort of futuristic footy game I\\nsuppose.  Anyway, invite a friend over (or grab your sister) and\\nbet hundreds of pounds on every game.\\n\\nThe menu (please note how I reme");
        sb.append("mbered this time, and I haven't\\njust tagged a two line 'Cheers' on the end!) has been written by\\nthose busy brothers down at Jupiter Software.  You may have\\nnoticed that it has been written in S");
        sb.append("AM C and looks lovely.  If\\nyou want the source code for it, see their letter in the letters\\nsection.  I may talk them into doing a C article for next month\\nactually.\\n\\nE-tunes are once again ");
        sb.append("from Mr Hartley and Mr Bailey.  They're\\nvery good, too.  And there are eight.\\n\\nIn slot K we have a beat-em-up.  Possibly the first on the SAM,\\nCA                       Disc Contents\\n\\nbut w");
        sb.append("ho knows.  It's written in BASIC, but is really well\\npresented and quite fun to play (despite the occasional key\\ncollision).  If you play on your SAM all on your own, just\\npretend the other pers");
        sb.append("on is Colin Macdonald.  I did that and\\nI've never had so much fun on a beat-em-up (I played it for 45\\nhours until I collapsed).  Ben Yates is the genius behind this.\\n\\nRachel makes its penultim");
        sb.append("ate appearance on FRED as the story\\nhots up to an unbeatable climax.  I really must read one of them\\nsome time.\\n\\nI've got millions of adverts from everyone since I said they\\nwere free.  So, ");
        sb.append("God knows which will get on.\\n\\nBits n Bobs next.  Well, firstly there's a help thing for MIALL,\\nthe artificial intelligence program on issue 56.  It explains\\nhow it works and what you can do.  ");
        sb.append("Andrew Chandler, another of\\nthose hard workers (I love you all) is responsible for this.\\n\\nCA                       Disc Contents\\n\\nMartin Fitzpatrick has written a simple upward scroller prog");
        sb.append("ram,\\nafter being influenced by the Debut demo.  Have a look and you\\ntoo will be able to write BASIC upwards scrollies.\\n\\nAnd finally, there's universe by Darren Martin - an update to\\nthe one ");
        sb.append("years ago by Andrew Collier.  Thanks Darren.\\n\\nAnd I'll dig out some screens and some mods.\\n\\nWell, don't misbehave while I'm gone.  I'll be back to check up\\non you in four weeks in issue 60. ");
        sb.append(" Please write something for\\nit, even if it's only an entry to the 'Incredibly Rubbish Game\\nCompetition'.  And I want lots of letters of congratulations and\\ngeneral nonsense (as well as a couple ");
        sb.append("of normal ones).\\n\\nSee you then.  I'll leave you now in the capable hands of FRED's\\nvery own film critic, Darren Martin.\\n\\n\\nCA                         Thank you\\n\\nManager  : COLIN 'Made h");
        sb.append("is editor PAY for SAM C' MACDONALD\\nDogsbody : COLIN 'Specialist strawberry packer!' ANDERTON\\n\\n\\nAnd thank you to the very hard workers, namely:\\n\\n\\nANDREW CHANDLER &amp; JACK BAILEY      A.");
        sb.append(" HODGEKINSON &amp; MATT ROUND\\n    STEVE EKINS &amp; ROGER HARTLEY    JAMES HORSFALL &amp; JAMES CURRY\\n    TERRY EKINS &amp; STEVEN PICK        DIGGORY GRAY &amp; DEREK MORGAN\\n   MARC BROSTER &am");
        sb.append("p; BEN YATES\\n  DAVID LAUNDON &amp; MARTIN FITZPATRICK\\n\\n                 FRED is available from:- [redacted]\\n\\nNext Month: The best in software\\n            Regular PD section\\n            B");
        sb.append("IG Anonimity section\\n            Double Colin's interview\\nCA                       PD Section...\\n\\nAs of next month, I hope to have a regular PD slot after the\\nthanks.  If you have a PD libra");
        sb.append("ry and would like us to review\\nyour stuff, get in touch with Colin and he'll pass your phone\\nnumber on to me.  We'll provide the discs and postage, you just\\nprovide the software.\\n\\nSimilarly,");
        sb.append(" if anyone reading this has just bought some PD\\nsoftware and would like to review it, please do.  Rather than a\\nfull review, I'm just after an explanation of what it is and a\\nfew lines saying wh");
        sb.append("at you think of it (\\\"I like it because...\\\",\\n\\\"it's a dreadful waste of money...\\\",\\\"It's good but...\\\",etc.).  I\\nshould then be able to fit them into a nice little section.\\n\\nIn t");
        sb.append("he mean-time, SAM PD have made a catalogue disc, which has a\\ncouple of demos on as well as the complete listing of software.\\nIt will cost you a pound, but is well worth it.  Their address\\nis in ");
        sb.append("the adverts section.\\n\\n\\nDM                        Film Reviews\\n\\nReviewed this month are Don Juan de Marco,Demon Knight,Kiss of\\nDeath,Bad Boys and Tank Girl....\\n\\n-Don Juan de Marco (15)");
        sb.append("\\nStarring: Johnny Depp,Faye Dunaway and Marlon Brando\\n\\nWe start in modern day America where a pyschiatrist(Brando) is\\ncalled to help talk down a suicidal Johnny Depp,claiming to be\\nthe legen");
        sb.append("dary Don Juan de Marco,the worlds greatest lover.Not to\\nbe swayed by this claim,Brando talks Depp down and admits him to\\na mental institute.Although near retirement,Brando persuades his\\nboss to ");
        sb.append("allow him to take on Depp's case.He is given ten days to\\ncure Depp of this dellusion,when he must either be admitted or\\nset free.\\n\\nThe film then sets into a nice gentle pattern of talks betwee");
        sb.append("n\\nBrando and Depp(complete with outfit,tan and beard).Don Juan\\ngoes on to explain the history of his mother and father in his\\nisolated village in Mexico,the duelling and disgraced death of\\n\\n");
        sb.append("his father led him to wear his black eye mask,his departure from\\nMexico into the hands of a sultans harem,etc.\\n\\nThrough these talks with Don Juan,Brando begins to realise that\\nif Depp believes");
        sb.append(" he is Don Juan in his exoticly romantic worlds,\\nthen so be it.And with an insight into Don Juan's heart,Brando's\\nown romantic spark becomes rekindled.This contagious effect of\\nDon Juan isnt jus");
        sb.append("t limited to Brando,soon the women and some of\\nthe most unlikely males become wrapped up.\\n\\nThe film is paced gently,not in-yer-face stuff.The photography\\nof Don Juan's past exploits is shot wi");
        sb.append("th an wonderful exotic\\nfeel.Bryan Adams soundtrack is little heard,thank god,and you\\ncome out feeling good.Definately a movie to go and see with your\\npartner(thats as politically correct as I ge");
        sb.append("t)and not too taxing\\non the brain.\\nA romance of lovely,gentle...nice proportions.\\n\\n\\n-Tales from the Crypt:Demon Knight (18)\\n\\nStarring:Billy Zane,William Sadler,Jada Pinkett and Brenda Ba");
        sb.append("kke\\n\\nThe American horror TV series finally reaches the movies with a\\ngory,frightening and often hilarious tale.After the introduction\\nfrom the Crypt Keeper(a demonic wise-cracking fiend,for th");
        sb.append("ose\\nwho havent seen him on Sky One),we are plunged into the action\\n(or hack-tion,as the Crypt Keeper would have it).\\n\\nIt begins with a car chase in which Zane is chasing Sadler on a\\ndark cou");
        sb.append("ntry road.After a close escape Sadler befriends a tramp\\nand ends up at a boarding house\\/whore house.Accommanying the\\npolice,Zane arrives to retrive an ancient relic stolen by Sadler\\nIt soon tu");
        sb.append("rns out that Zane is the one to be feared after\\nsummoning up hideous demons to get Sadler.\\n\\nWhat forms is a battle between good and evil in their extremist\\nof cases(Jesus Christ and a devilish");
        sb.append(" minion,no less).The demons\\nonly need one more of seven keys to unleash darkness upon the\\nworld.It is this last key that Sadler holds,one that has been\\npassed down from the time of Christ.\\n\\n");
        sb.append("Using the power of the key Sadler must defend the residents and\\nprevent the key from falling into the Collecters hands(Zane).\\nThis turns out to be no easy task as the body count rises and\\ndemoni");
        sb.append("c pocessions begin to reduce the cast.Perhaps the deadlist\\nthreat is Zanes charisma,which adds imensely to the enjoyment of\\nthe film.\\n\\nThis is a very typical horror movie at its best.The tongu");
        sb.append("e in\\ncheek humour easliy outweighs the unoriginal plot and the low\\nbudget they seem to have to have contended with.Billy Zanes'\\nperformance is funny,but always with a fearsome edge(a little\\nli");
        sb.append("ke his character in Dead Calm).Dont forget this is an eighteen\\ncertificate and the gore and sudden frights certainly live up to\\nit.Although,if your anything like me,the gore is one of the\\nfunny ");
        sb.append("elements.Especially when (if your going to watch this then\\nskip ahead) Zane turns to punch a sheriff in the face and his\\nfist burts straight through and becomes stuck on his arm!\\n\\nThis is must");
        sb.append(" if you are missing this kind of horror,or are sick\\nto death of movies like Don Juan above.\\n\\n(There was also a trailer for Die Hard with a Vengence.Wow! That\\nis gonna be one hell of a movie)");
        sb.append("\\n\\n\\n-Kiss of Death (18)\\nStarring: Nicholas Cage,David Caruso and Samuel L. Jackson\\n\\nThis is a film that suprised me because I didnt think Hollywood\\nproduced pictures this dire anymore.We ");
        sb.append("have a wonderful cast,\\nalthough Cage seems way out of place as the nasty bad guy.David\\nCaruso,the carrot-top from NYPD Blue,manages reasonably well on\\nthe silver screen.He plays our hero:an out ");
        sb.append("of luck,lifes-a-bitch\\nfamily man.While helping out a desperate friend to transport\\ncars,there is a police raid in which we meet the Man himself,\\nSamuel L. Jackson.After a tragic shooting inciden");
        sb.append("t,Caruso finds\\nhimself in jail.Soon tragedy after tragedy strikes his family\\nand he decides to help the police break up the gangs and car\\nstealing racket.\\n\\nAided by Jackson he goes undercove");
        sb.append("r into a gang whose main heavy\\n\\nis Cage.What follows is a series of cover-ups,loyalties and\\nviolence.\\nLooking at the plot of this movie makes it sound intresting,but\\nquite frankly its crap w");
        sb.append("ith a capital S! From the very beginning\\nI got a feeling in my bowels that I'd wasted \\u00a35.It reminded me\\nof the sort of film that ITV show at 2 am when you crawl in from\\nthe pub.Its full of");
        sb.append(" cliches and cringe-worthy lines.Everyone\\nseems to be trying too hard at their part,and they all play it\\ntough when theres not always the need.Mixed in with all this is\\nthe main driving force of");
        sb.append(" the plot,Caruso's child.Sentimental,\\nyes;bearable,no.\\n\\nThe only saviour of this film is Samuel L. Jackson,who is\\nexcellent in whatever he does.His good-guy but scorned cop is a\\nnice role,an");
        sb.append("d Jackson can pull off any old lines with ease.\\n\\nTake my advice:keep away from this film like you would with\\nsomeone who has Ebola Zaire.\\n(Anyone who has seen this movie,maybe you could shed a");
        sb.append(" little\\nlight on the mystery of why the title \\\"Kiss of Death\\\"? Its got\\n\\nme foxed.)\\n\\n(Another super trailer fo Batman Returns.What a summer of movies\\nits going to be!)\\n\\n\\n-Bad Bo");
        sb.append("ys (18)\\nStarring: Will\\\"Fresh Prince\\\"Smith,Martin Lawrence,Lisa Boyle\\n\\nDetectives Burnett and Lowrey are our stars,played by Lawrence\\nand Smith who must retrieve a million dollar haul of ");
        sb.append("herione.The\\ndrugs were snatched from under the polices noses by the crooks,\\nand now the police department faces closure if the embarrasment\\ncannot be prevented.Following the crooks is a trail of");
        sb.append(" murder,\\nand the cops only hope is a witness to one of the shootings.The\\nproblem being that she will only talk to the temporarily absent\\nLowrey.Desperate for the witness Burnett,Lowreys partner,");
        sb.append("\\npretends to be the cool,rich womanising Lowrey.It soon happens\\nthat Burnett must keep up this charade throughout the case!\\n\\n\\nThis is a good movie with plenty of laughs,the situation\\nswapp");
        sb.append("ing of Burnett into Lowreys home and lifestyle,and vice\\nversa,with Lowrey moving in to look after Burnetts wife and kids\\nis hilarious.The source of the films main humour is the\\nbickering between");
        sb.append(" Burnett and Lowrey and it puts you in mind of\\nLethal Weapons Mel Gibson and Danny Glover.The \\\"brother\\\" thing\\nis resemblant of White Men Cant Jump with some smashin' one\\nliners.\\n\\nBeing");
        sb.append(" an action movie,its got the guns,car chases,explosions and\\nviolence,that no film maker would be without.But an action\\nmovie is a tried and tested formula and theres not much new that\\ncan be don");
        sb.append("e.Therefore there are some beautiful cliches that Kiss\\nof Death would be proud of.\\n\\nIf you can shrug off that feeling of deja vu then this film is\\nvery good,with some very funny moments that w");
        sb.append("ill give you\\nabdominal pains.\\n\\n\\n-Tank Girl (15)\\nStarring: Lori Petty,Ice T,Malcolm McDowell,Iggy Pop\\n\\nThis is the first main comic book to movie film of the year,\\nfollowing closely beh");
        sb.append("ind are Judge Dredd and Batman.Although\\nthis is probably the worst it deserves alot more respect for its\\nBritish creators,Jamie Hewlett and Alan Martin.(no relative)\\nIt is set in the year 2033,w");
        sb.append("here a comet has devastated the\\nenviroment and made water the rarest and most sought after\\nresource.Water and Power are the company who ruthlessly own most\\nof the water.Malcolm McDowell is the m");
        sb.append("an in charge.He owns 95%\\nof the desert and he wants that other 5%.Unluckly for him it\\nhappens to be where our heroine lives,Rebecca Buck(Petty).Shes\\na cigarette smoking,beer drinking,gun shootin");
        sb.append("g,bad dressed,foul\\nmouthed,sexy anti-heroine.\\n\\nWhen Water and Power come to take the land she is captured and\\nmost of her loved ones are killed.She is tortured by McDowell\\nand put to work in");
        sb.append(" the mines.Here she meets the techical super\\nbrain xxxxx (Im sorry but I cant remember her name).Together\\nthey escape with a plane and a tank and stumble upon the\\n\\ngenetically altered half man");
        sb.append(",half kangaroo rebels.Now they have\\nan attack force with which to strike revenge upon Water and\\nPower.\\n\\nThis film is a riot,very off beat and different from anything\\nthats come before.The li");
        sb.append("ve action is intermixed with anmimated\\nshots of the manga comic.The soundtrack is fairly good with L7\\nand Bjork supplying some of the sounds for the faster parts.If\\nyou happen to be in a good ci");
        sb.append("nema then you'll motice some good\\nsurround sound effects.Some parts are very funny and the half\\nman,half kangaroos are excellent.But all the limelight must go\\nto Lori Petty for here portrayal of");
        sb.append(" Tank Girl,although toned\\ndown from the comic,she is very convincing,playing her wild and\\na little mad.\\n\\nSadly,though,the film is lacking in areas and tends to drag on\\nin places.Even for a m");
        sb.append("anga origin it is a little odd in places\\nand you may find yourself smirking AT it,not laughing WITH it.\\nYour enjoyment of the film will me improved if you go in not\\nquite serious with yourself(I");
        sb.append(" find a couple of pints help).\\n<\\/pre><h3>Letters &amp; Reviews<\\/h3>\\n<pre>                  Letter From James Horsfall\\n\\nDear Sirs, [ooh - CA]\\n    I am writing to ask if you or anyone else");
        sb.append(" to your knowledge\\nmay be considering a SAM version of Scrabble?\\n\\nI have the Spectrum Psion version [me too - CA] but the 48K\\ncapacity for vocabulary is extremely limited and not very up to\\n");
        sb.append("date.\\n\\nScrabble is increasingly popular, I think, and Chambers, whose\\ndictionary is used in tournaments, now publish a full list of\\nallowable words - OSW (Official Scrabble Words).  You alread");
        sb.append("y\\nhave SpellMaster at your disposal.\\n\\nI enclose an anagram solver for 7-letter words, and have one for\\n8 letter words which I would willingly give you if you were to\\nstart a new vocabulary a");
        sb.append("nd be prepared to write\\/convert the\\ngame.  I would gladly supply more.\\n\\n                                   James Horsfall\\nCA                  Reply to James Horsfall\\n\\nDid anyone get the ");
        sb.append("hint about a scrabble game there?  If anyone\\nis writing one, and would like an anagram solver, get in touch.\\n\\nIf anyone is considering writing a scrabble game, but thinks\\nthat BASIC would not ");
        sb.append("be sufficient and machine code is\\nunlearnable, just remember that a certain new widely used\\nlanguage recently released by FRED would be the ideal language\\nto use - it's fast at everything (espec");
        sb.append("ially maths), it's easy\\nto program and a bargain at just \\u00a319.99.  And it's called SAM C\\nincase no-one caught on.\\n\\n\\n\\n\\n\\n\\n\\n\\n\\nCA        Letter From Diggory Gray &amp; FRED Im");
        sb.append("provements\\n\\nThis is a bit confusing now.  I'll include Diggory's ideas, and\\nthen interrupt him to stick in any other ideas that people had,\\nthen comment on them.  My bits are in square bracket");
        sb.append("s, Diggory\\nhas said the rest.\\n\\n                    Letter From Diggory Gray\\n\\nHello Colin &amp; Colin,\\n    I'm writing to tell you my change of address, and while I'm\\nwriting, I've rememb");
        sb.append("ered that you wanted some comments on the\\ncontents of FRED, etc.\\n\\nThe items on FRED are great!  [Generally agreed with, although\\nlots of people are getting a bit sick of standard shoot-em-ups,");
        sb.append("\\na point which programmers may want to bear in mind.  The other\\nlittle moan was that the number of text articles has increased.\\nCome on, FRED is primarily a magazine!  We will try to keep them");
        sb.append("\\nmore SAM related, but it makes an alternative read.  Rachel\\nfinishes soon, anyway. - CA]\\n          Letter From Diggory Gray &amp; FRED Improvements\\n\\nI think the A-N menu is alright, but I t");
        sb.append("hink some different\\ntypes of menus might be a good idea.  [I get the feeling that\\nI'd be strung up if I changed the A-N style!  The problem\\nprogrammers have is that they can't think of new ideas");
        sb.append(" for menus\\nor games or demos or anything.  They seem to have the ability\\nbut no ideas.  Therefore, if anyone has any ideas for a game,\\nmenu or demo, send it in and I'll pass it on to a very grat");
        sb.append("eful\\nprogrammer.  You'll no doubt get a big mention when he (or she?)\\ngets it written. - CA]\\n\\nThe editorial is O.K., but couldn't you include an E-tune\\/MOD\\nplayer into the text reader?  Th");
        sb.append("is would make this part of the\\ndisc more... er... \\\"asounding\\\".\\n\\n[Having music playing with the editorial was surprisingly\\npopular.  A new reader was being called for as well.  There are");
        sb.append("\\ncurrently two text viewers being written, but they do seem to be\\ntaking a while, I know.  Writing a good viewer takes a long\\ntime, but they are being worked on.  One I know will be a\\n        ");
        sb.append("  Letter From Diggory Gray &amp; FRED Improvements\\n\\nhyper-text viewer, but this could take a while (unless the\\nwriter has a sudden burst of inspiration).  The other is going\\nto include dozens ");
        sb.append("of extra features over the current one and\\nboasts excellent compression.  This should come first, I only\\nhope it comes soon.  If by some ironic disaster (but a wonderful\\ndisaster to have to cope");
        sb.append(" with) I get them both at the same time,\\nor within a couple of months of each other, I'll either\\nalternate, or use one for the editorial and the other for\\nletters and other text articles.\\n\\nH");
        sb.append("aving music playing with the text sections would be great, but\\nI can't afford to lose the E-tunes section completely.  It's\\nimportant to have lots of regular slots so that there is no need\\nto as");
        sb.append("k for another program each month.  This can only result in\\nlower programming standards as I moan at people to finish their\\nstuff quickly.  The idea, however, has been suggested to both\\nprogramme");
        sb.append("rs and it is really up to them to decide. - CA]\\n\\nThe big money issue - I think the tokens for buying FRED stuff\\n          Letter From Diggory Gray &amp; FRED Improvements\\n\\nwhen you send in p");
        sb.append("rograms is a good idea, as when you sent me\\n\\u00a310 for 'Rain', I just went to the show and bought 5 back issues\\nof FRED with it.  Also, I'm not too bothered about getting vast\\namounts of mone");
        sb.append("y to program things &amp; send them in to FRED.\\n\\n[Programmers in general seemed to be concerned about the loss of\\ncontribution payment, but everyone who has so far written is\\nhappy with the ");
        sb.append("\\u00a35 and \\u00a310 vouchers that are sent out as payment.\\nI think most people program for the pleasure of it. - CA]\\n\\n[The other issue that a number of people mentioned was that they\\nwanted");
        sb.append(" a new Bits and Bobs front end.  So do I!  Come on\\nsome-body, get writing one.  I could use the menus that people\\nsend that I can't use because I get so many of them.  What do\\nyou think? - CA]");
        sb.append("\\n\\nThat's about it.  FRED is great [Unanimous decision! - CA]\\n\\n                                               Diggory Gray\\n                 Letter From Martin Fitzpatrick\\n\\nDear Lots Of Pe");
        sb.append("ople,\\n                    Yes, after a little spell away from the\\nkeyboard, I'm back by popular demand (Of my dog, who realised\\nthis was the only time I wouldn't kick him (aaaaawww, that was a");
        sb.append("\\nbit cruel)).\\n\\nHowards Way is now back on TV, it was bad enough when it was\\nfirst around and even then people laughed at the clothes they\\nwore and the bad acting.\\n\\nWell whats happened si");
        sb.append("nce the last little bit of writing was\\nhere?\\n\\nQuazar, now thats a good word, in the proper dosage and under\\nprescription of course (KEEP OUT OF REACH OF CHILDREN).  At last\\nit's finally arri");
        sb.append("ved... 60 blooooody quid, I beggar your pardon\\nI must be going a tiny bit deaf.\\n\\nI suppose it's not actually that bad, PC cards are a lot more\\n                 Letter From Martin Fitzpatrick");
        sb.append("\\n\\n(but then so are the computers).  I forked out \\u00a349.99 for a\\nSamBus just so the printer lead would reach - but I'm just daft.\\n\\nWhat I think is needed for the Quazar to take off is Sof");
        sb.append("tware.\\nIt's okay saying more softwares on the way - but are people\\never going to believe that?\\n\\nIf demo's on FRED started using Quazar sound FX then people will\\nprobably buy one, though that");
        sb.append("s not a certainty.  Sound on the PC\\nneeded a SoundCard, the only other option was the Beepy speaker.\\nBut the Sam can still produce good sound as it is.\\n\\nWell there you go (Uncle Bulgaria).\\n");
        sb.append("\\nAlso on the way are Hard Drives...  I think it will need a cool\\nname to be worth buying.  Something like 'Little Buggar' or\\n'Disk Masher' or something that will get people of all ages down\\nto");
        sb.append(" the shops to buy one.\\n\\n                 Letter From Martin Fitzpatrick\\n\\nNow what is there to talk about...\\n\\nWell usually when I get to this stage I tell a joke.  Right a\\nlittle story to");
        sb.append(" explain how this one came about....\\n\\nIn our school we have a teacher called Mr Stones (who looks like\\nevery member of ABBA rolled into one).  Anyway he wants us to\\njoin the 'Drama Club' - He ");
        sb.append("doesn't seem to realise that me and\\nmy mates have no acting skills, but he says we do, and thats\\nonly because of our 'Hitler' plays last year, which I'm sure he\\ndidn't undersand (if he had we wo");
        sb.append("uld have probably got expelled)\\nSo, Mr Stones came up to us in the corridor one break and\\nsaid...\\n\\n   \\\"So I take it you boys have joined the drama club then?\\\"\\n   \\\"No\\\"\\n   \\\"Wh");
        sb.append("y Not?\\\"\\n   \\\"We dont want to\\\"\\n   \\\"Why Not?\\\"\\n                 Letter From Martin Fitzpatrick\\n\\n   \\\"Okay, we'll think about it, just leave us alone\\\"\\n\\nSo then he started ");
        sb.append("to walk off, and walk back.  He came up to me\\nand said...\\n\\n   \\\"You've got a sense of humour haven't you?\\\"\\n   \\\"Depends\\\"\\n   \\\"Here's a joke\\\"\\n   \\\"Hooray\\\"\\n\\n   \\\"Ho");
        sb.append("w do you make a Penguin jump higher?\\\"\\n\\n   \\\"I dont know, How do you make a Penguin jump higher?\\\"\\n\\n   \\\"Give him rubber legs\\\"\\n\\n   \\\"Ha Ha hahahaha\\\"\\n\\n   \\\"Haa haaaha ");
        sb.append("aa (Hysterics)\\\"\\n                 Letter From Martin Fitzpatrick\\n\\nHe then walked away, while me and my mates looked confused.\\n\\nWell there you go.  I'm off now (well theres a funny smell\\n");
        sb.append("anyway (ha ha ha)).\\n\\nNow I'm off to find my rubber legs.\\n\\nOh and Colin Anderpants, hows the Queen, her dog told me you and\\nher were good friends and went out every night.\\n\\n\\nCA         ");
        sb.append("      Reply to Martin Fitz-cat-sick\\n\\nWHAT DID YOU CALL ME????  COLIN ANDERPANTS???  YOU DIE, BOY.\\n\\nSixty quid does seem a bit steep when you're only improving the\\nsound quality of the SAM.  ");
        sb.append("However, the increase is incredible\\nand eventually software will be written for Quazar.  The only\\nproblem is that programmers won't buy it until programs are\\nCA                Reply to Martin Fi");
        sb.append("tzpatrick\\n\\nwritten for it, which they won't write because they don't have\\nit, so they won't write it, and, aaarrrrggghhhh - a looping\\ndouble thingumy.  Having said that though, Colin Piggot is");
        sb.append(" doing\\na good job of talking people into sticking Quazar support in\\ngames, and plenty should follow.  I guess you'll have to wait\\nand see...\\n\\nThose hard drive names were very catchy, I must ");
        sb.append("admit.  What if\\nit was called 'The Swazzer', though?  I'd buy two if it was\\ncalled that.\\n\\nThe Queen's fine, thanks.  She sends her love.  You'll never\\nguess what she bought me for my Birthda");
        sb.append("y.  Hong Kong.  Straight\\nup, seriously, guv.  Come round and visit one day.\\n\\n\\n\\n\\n\\n                    Letter From Derek Morgan\\n\\nDear Colin and Colin\\n\\nMany thanks for issue 57, an ");
        sb.append("excellent issue.\\n\\nI'm not one for doing reviews, but I would like to mention the\\nSAMdac that you reported on last month.\\n\\nThe SAMdac, being the ready made version of the EDDAC that was\\nin ");
        sb.append("FRED 41 as a build it yourself sound board.\\n\\nI was really impressed by the quality  of  the  sound  from  the\\nSAMdac.  This  little  sound  interface  plugs  into  a  printer\\ninterface and put");
        sb.append("s the sound back  into  the  computer,  so  the\\nsound comes out of your TV  or  monitor.  You  can  connect  the\\nSAMdac to external Speakers if you have them.\\n\\nMODs played through the SAMdac a");
        sb.append("re simply  incredible.  I  found\\nthem to be equal to the PC and Amiga Mods, which they are.\\n\\n                    Letter From Derek Morgan\\n\\nAs the Mods are played though the SAMdac with the s");
        sb.append("creen  on.  I\\nhope that we will soon see programs and games with SAMdac sounds\\non them.\\n\\nJust incase some of your readers skip though what's new  on  the\\nSam Public Domain scene,  I  would  ");
        sb.append("like  to  inform  them  that\\nRevelation software have made the SAM COUPE  ARCADE  DEVELOPMENT\\nSYSTEM (SCADS) Public Domain.\\n\\n                          Derek Morgan\\n\\nCA                   Re");
        sb.append("ply To Derek Morgan\\n\\nCheers, Derek.  You had to get your little plug in at the end\\ndidn't you?  I bet that was the whole reason you wrote.  The\\ncomments on SAMdac were just there so I'd stick ");
        sb.append("the letter in.\\n\\nAnd now I've drawn even more attention to your plug.  Darn.\\n\\n                    Letter From Marc Broster\\n\\nhi,\\n\\ni thought it might be cool to type all this text in lowe");
        sb.append("r case,\\nbut this just makes it real hard to read (snigger).\\n\\n[Lots of info on the Sprite Util was here, but I've stuck that\\nwith the program in slot F, just for your convenience - CA]\\n\\nto ");
        sb.append("be honest, i've really lost interest in coding, i really\\nenjoy finding soloutions to problems and writing algorthims to\\nimplement them (what i will probaly be doing for the rest of my\\nlife) but ");
        sb.append("i don't enjoy searching through source code to spot\\nstupid mistakes. actually, i don't think, well i know, that i am\\nany good at coding, i am the kind of person that makes loads of\\nmistakes (i a");
        sb.append("ctually got sacked from a job in marks and spencer\\nfor this!) and that's not what you need when you want to write\\ncode quickly. if you've got several months to write a routine\\nhowever, no proble");
        sb.append("m.\\n\\n                    Letter From Marc Broster\\n\\nfollowing on from that, i was pleased to read that fred is\\npublising a c complier (who's responsible for coding it?) which\\ni am really loo");
        sb.append("king forward to using. as colin said, c is more\\nof a step up from assembly then a full high level language, but\\nis definitely a great improvement, as it allows you to\\nconcentrate on programming ");
        sb.append("rather than coding. i'am also pleased\\nto see that it's being realsed for \\u00a320.\\n\\nhowever, i don't think it will result in demos or games that\\nreally push sam to the limits, as you lose a l");
        sb.append("ot of control\\nover the processor, neither do i think it will have the same\\neffect as gamesmaster, which resulted in lots of resonable\\ngames, as it isn't all that easy to use. i could be wrong.");
        sb.append("\\n[We'll PROVE that you're wrong.  Give us time.  - CA]\\n\\nmarc broster\\n\\n\\n\\nCA                   Reply To Marc Broster\\n\\nYou're right, lower case is annoying and hard to read.  Don't do");
        sb.append("\\nit in future.\\n\\nSorry to hear that you're sick of coding.  Some of the stuff\\nyou've done is really great.  Have a little rest, I'm sure\\nyou'll one day suddenly be hit with the enthusiasm to ");
        sb.append("write\\nsomething amazing.\\n\\nWe're pleased you're looking forward to using SAM C, but why are\\nyou so negative about it's capabilities?  The whole point is\\nthat it will constantly be expanded an");
        sb.append("d there will be a massive\\nflurry of PC programs and new SAM programs coming over the next\\nfew years.  They have the potential of being very good - better\\nand faster than gamesmaster has produced");
        sb.append(".  I guess you'll have\\nto believe us until people begin writing things.\\n\\n\\n\\n\\n                  Letter From Jupiter Software\\n\\nDear Mr Colin, After your hint for a review copy of AMALTHEA");
        sb.append("\\n(the game that sold out at the last show!) and all the nice\\nthings you said about MEGABLAST (rated at 80%+ by other\\nmagazines!) how could we refuse.\\n\\nThe menu was written in SAM C, If anyon");
        sb.append("e wants a copy of the\\nsource code (also available in ascii for non C owners), send us\\na blank FORMATTED disk and an SAE and we will send it to you as\\nwell as a load of free games &amp; demos.\\n");
        sb.append("\\nOur address is in the scrolly, but for slow writers, here it is\\nagain.\\n         JUPITER SOFTWARE\\n         [redacted]\\n\\nAlso is there any demand for an advanced SCADs manual? Now that\\n   ");
        sb.append("               Letter From Jupiter Software\\n\\nSCADs is PD, we may finish our manual. If more than a couple of\\npeople would like one, write in and let us know.\\n\\nThats all from me, now its Mr C");
        sb.append("olins turn I think!\\n\\nPS  Hello to everyone We met at the sam shows.\\n\\n\\nCA                 Reply to Jupiter Software\\n\\nI wish I knew whether I was speaking to Terry or Steve.  There's\\ntwo");
        sb.append(" brothers in Jupiter Software you see and they insist on\\nmaking you guess which one is writing.\\n\\nYou're not going to make me feel guilty about giving Megablast a\\nlow mark, not matter how hard ");
        sb.append("you try.\\n\\nOK, I'M SORRY.  SORRY, SORRY, SORRY, SORRY.  It's not my fault\\nit's a load of rubbish.  No, only joking.  Seriously though,\\nCA                 Reply to Jupiter Software\\n\\nremember");
        sb.append(" my reviews are simply my opinions.  That's why I'd like\\nmore people to review things.  Besides, I liked Booty.\\n\\nPersonally, I think SCADs is an amazing program.  The language\\nis relatively ea");
        sb.append("sy to learn and the editor is the best I've\\never seen for a Games creating package.  The manual it has with\\nit is absolutely brilliant in my opinion.  Before I read it, I\\nknew very little about ");
        sb.append("games creating, and it filled me in\\ncompletely.\\n\\nRather than a more advanced manual, I want to see a SCADs\\ncompiler.  I know it's a large project, but it really is a\\nnecessity.  SCADs runs f");
        sb.append("ar too slow, but a compiler would shoot\\nit way above Gamesmaster (which with-out the speed, it falls\\nbehind).  Someone please write a compiler.\\n\\nIn the mean-time, I'm always after any handy ar");
        sb.append("ticles on\\nprogramming either SCADs or Gamesmaster or SAM C.  By the way,\\ngreat menu Jupiter.\\nCA                   Review of Safari Sam\\n\\n        METROPOLIS SOFTWARE : \\u00a35.50 : MARTIN FIT");
        sb.append("ZPATRICK\\n\\nSam (unfortunately not the robot - I don't think) is going on\\nholiday.  Whilst taking a number of short-cuts to the airport,\\nhe ends up three hours late.  Luckily (ahem), the plane h");
        sb.append("asn't\\nleft yet due to engine trouble.  Sam boards the (very dodgey)\\nplane and after a couple of attempts, it is skyborne.  Living up\\nto its reputation, the plane conks out and begins to fall out");
        sb.append(" of\\nthe sky.\\n\\nBy a lucky coincidence (which is necessary else it wouldn't be\\nmuch of a game!) Sam survives the fall with only a bang on the\\nhead.  A gun materialises in front of him as Sam f");
        sb.append("inds himself\\nin the jungle.  He picks up the gun and begins his journey home.\\n\\nSafari Sam is a platformer (hooray).  It's a relatively small\\ngame with only three levels and has been written in");
        sb.append(" SCADs by\\nMartin Fitzpatrick.  Sam must collect the key on each level then\\nreach the exit, killing nasty hunters that get in the way and\\nCA                         Safari Sam\\n\\ndoing his best");
        sb.append(" not to kill the little animals.  The controls are\\nthe usual for a simple platformer - left, right, jump and fire.\\n\\nTo review the game properly, let's go back to the start.  Safari\\nSam comes o");
        sb.append("n one disc.  Upon booting there's a simple, but very\\nvery nice introduction.  The menu is excellent with plenty of\\noptions including difficulty, toggle FX, change keys (which can\\nalso be done in");
        sb.append(" the game), story and instrcutions.  The menu is\\nsimple to use, with a reasonable e-tracker tune (by Rik Moore)\\nplaying, the instructions are clear enough, the story is long\\nbut humorous enough ");
        sb.append("to stop you getting bored and the grammar is\\nnearly perfect.\\n\\nAfter you've decided on what options to select, you can start\\nthe game.  A different little picture greets you at the start\\nand ");
        sb.append("finish of each level, all adding to the professional feel of\\nthe program.\\n\\nThen the game starts.  The sprites are average sized (about 8 by\\nCA                         Safari Sam\\n\\n12 I thin");
        sb.append("k), the graphics are nothing amazing, but adequate and\\neasily recognisable (and all done by Martin).  The enemies are\\nvery nice in that they fire back a lot (as they would if it were\\nrealistic) ");
        sb.append("and don't just walk around like lemons waiting to be\\nshot.  Your life is in terms of energy, so one shot won't kill\\nyou.\\n\\nThe idea is to jump about, shoot all the men in your way\\n(without wa");
        sb.append("sting all your ammo) and reach the end of the level.\\nIt is a really simple game, and there isn't much to it apart\\nfrom that.  You die when your energy runs out or when you fall\\noff the bottom of");
        sb.append(" the screen (very annoying).  It's definitely\\nnot a game which will stun you with it's new ideas, but it does\\nhave a strange addictive quality to it.  You have only one life,\\nso if you die on le");
        sb.append("vel 3, you go back to the start of level 1.\\nWhen you complete the game, you start again on a higher\\ndifficulty.\\n\\nIt is nice to have another platformer released, even if it is a\\nCA           ");
        sb.append("              Safari Sam\\n\\nSCADs game.  It's also nice to see that F9 software are\\nreleasing plenty of decent budget games, although I hope they\\ndon't release too many that aren't up to the req");
        sb.append("uired standard.\\n\\nOverall, it is pretty obvious that Safari Sam is a pretty\\nstandard average game.  Martin has done a very good job of\\nturning what could have beena pile of rubbish into somethi");
        sb.append("ng\\nthat is nice to look at and enjoyable to play.  I doubt its\\nlastng qualities after you have completed it, and I also doubt\\nit'll take long before you do get through the game (remember, it\\no");
        sb.append("nly has 3 levels).  However, if you like the odd platformer or\\nare looking for a little budget game, have a look at it.\\n\\n                      Playability : 61%\\n                      Lastabili");
        sb.append("ty : 43%\\n                         Graphics : 55%\\n                            Sound : 50%\\n\\n                          Overall : 57%\\nCA                    Review Of Amalthea\\n\\n        JUPITE");
        sb.append("R SOFTWARE : \\u00a39.99 : STEVEN &amp; TERRY EKINS\\n\\nFlying through the solar system is a busy job.  So busy in fact,\\nthat it's just so easy to over-look that tiny detail of\\nmaintenance.  Henc");
        sb.append("e, if a leaking fuel valve occurs, don't be\\nsurprised.  Just follow the procedure and make an emergency\\nlanding and re-fuel.\\n\\nThis problem faces you, the elegant hero of the game.  The\\nneare");
        sb.append("st fuel station is several billion miles away (a few hours\\njourney in a space ship).  You have no choice but to make your\\nway to the automated mining station called...\\n\\n                       ");
        sb.append("    AMALTHEA\\n\\nAmalthea is stocked with the resources you need, although ships\\nhas been banned from using the station and no reason was given.\\nWell, boarding the station reveals just why.  Hund");
        sb.append("reds of blood\\nthirsty aliens, that walk in very straight lines backwards and\\nCA                          Amalthea\\n\\nforwards, await you.  You have no option other than to bring\\nalong your gun");
        sb.append(" and risk your life to save the ship.\\n\\nThe object of the game is to complete the seven zones of alien\\nmayhem while collecting the power crystals necessary to allow\\ntake off.  As well as the po");
        sb.append("wer crystals, scattered around are\\nkeys, ammo, first aid and credits (which are required to buy\\nextra weapons and ammo).  Aliens scout the area - these can be\\nkilled by repeated shooting, althou");
        sb.append("gh ammo is limited, so it may\\nbe a better idea to avoid them where possible.  Collect the\\npower crystals (although don't shoot them or they'll blow up)\\nand locate the exit to go onto the next le");
        sb.append("vel.\\n\\nThe game is programmed by Terry and Steven Ekins, aka Jupiter\\nSoftware.  It comes on 3 discs (cripes) and has a manual.  The\\nmanual is unfortunately a couple of photocopied sheets folded");
        sb.append("\\ninto a booklet, but it's a nice touch and does make it feel\\nslightly more professional.  Amalthea boasts the fact that it is\\nthe first commercial game to support Quazar Surround Sound.  The\\nC");
        sb.append("A                          Amalthea\\n\\nintroduction is simply a couple of screens, followed by a\\nmachine coded menu with music by Roger Hartley.\\n\\nThe game has two different types of zones - ov");
        sb.append("erhead and side\\non.  You begin in an overhead zone, so that's where I'll start.\\nThe controls are simple enough.  It's just an 8 directional,\\nmove in the direction you press thing and you shoot s");
        sb.append("traight\\nforward.  The graphics are wierd because some are really\\nexcellent (an alien with a swinging tail is great), but some are\\nfar too plain and some are too small (namely the objects).\\nAli");
        sb.append("ens follow simple paths in the first level but these get more\\ndevious as you progress.  There is a time limit, and you can\\nonly find this out by using a computer, which is a bit annoying.\\nAt the");
        sb.append(" computers, you can buy better weapons, ammo, first aid\\nand look at your time.  They've got the time limits perfect, but\\nI would definitely have preferred an on screen display of it.\\n\\nIf you h");
        sb.append("ave ever used SCADs, you should be able to tell that the\\ngame has been written using it by playing the first level.  I'm\\nCA                          Amalthea\\n\\nnot sure how you can tell - they'");
        sb.append("ve done a good job of hiding\\nthe fact, and the game is really smooth, but there's just\\nsomething that says 'SCADs'.\\n\\nThe second type of zone is my favourite.  It's side on, but you\\nstill hav");
        sb.append("e to do the same thing - collect the pods, shoot the\\naliens.  The difference is that now it's more of a platformer.\\nThe grpahics are pretty big, which is good, and are by Steven\\nPick so you know");
        sb.append(" they're really good.  I'd have preferred a\\nflying creature to the grey spaceship thing that comes for you,\\nbut that's just me being picky.  Apart from that, the gameplay\\non this level is really");
        sb.append(" excellent.  On all the zones, the levels\\nhave been planned out beautifully, so that there's a large\\nenough map which takes a few goes to get used to.  The\\ndifficulty rating is absolutely perfec");
        sb.append("t and a lot of time and\\neffort seems to have gone into working out maps, times and all\\nthese sorts of little things.\\n\\nWhen you die you go back to the start of the zone and try again\\nCA      ");
        sb.append("                    Amalthea\\n\\nuntil you run out of lives or patience.\\n\\nI had someone watching me when I played this, and they thought\\nit looked really boring.  The overhead views could do wi");
        sb.append("th more\\naction (or larger graphics) but the game plays really well.\\nApparently, it's like Alien Breed on the Amiga, but I've never\\nplayed it, so don't take my word for it.  The Quazar Surround");
        sb.append("\\nSound?  Well, I don't have Quazar, so I can't comment on it\\nfully, but a few people heard it at the show and said it was\\nvery clear and didn't interrupt the game-play.\\n\\nThe major gripe unfo");
        sb.append("rtunately is the price.  Charging \\u00a310 for a\\nSCADs game sounds too much, and in my opinion is.  After all,\\nit's just a simple game that's been written on SCADs and made\\nbig and smooth.  How");
        sb.append("ever, it is the best SCADs game on the SAM\\nand all the small details, like the map and difficulty curve,\\nare just right.  I read a review where someone called it a\\nshoot-em-up, but I can't help ");
        sb.append("feeling that it's too tactical to\\nbe called a plain shoot-em-up.  If you're feeling rich, then buy\\nCA                          Amalthea\\n\\nit.  It's a good game, just slightly over-priced.\\n\\n");
        sb.append("              Playability : 79%\\n              Lastability : 69%        *****************\\n                 Graphics : 84%        *               *\\n   Sound (Without Quazar) : 59%        *    AWAR");
        sb.append("D :    *\\n            (With Quazar) : 90%        * BRONZE FREDAL *\\n                                       \",\"legalStatus\":\"allowed\",\"releasesIds\":[349651],\"imagesUrls\":[\"https:\\/\\/zxart");
        sb.append(".ee\\/image\\/type:prodImage\\/id:577973\\/filename:simc0131.webp\"],\"importIds\":{\"worldofsam\":\"fred-59\",\"zxdb\":\"35068\"},\"votes\":3.91,\"votesAmount\":1,\"connectedCategoriesIds\":[92179],");
        sb.append("\"categoriesString\":\"Press\\/Electronic Magazine\"},{\"id\":349658,\"title\":\"Fred issue 63\",\"dateCreated\":1588720236,\"dateModified\":1786311446,\"language\":[\"en\"],\"year\":1995,\"descriptio");
        sb.append("n\":\"<p><strong>Issue 63<\\/strong><\\/p>\\n<p>\\u00a0<\\/p>\\n<table class=\\\"table_component\\\">\\n<thead>\\n<tr>\\n<th>Item<\\/th>\\n<th>Author<\\/th>\\n<th>Description<\\/th>\\n<\\/tr>\\n<\\/th");
        sb.append("ead>\\n<tbody>\\n<tr>\\n<td>Menu<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:tim-paveley\\\" title=\\\"Click to view a local node.\\\">Tim Paveley<\\/a><\\/span><\\/td");
        sb.append(">\\n<td>\\u00a0<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Editorial<\\/td>\\n<td>\\u00a0<\\/td>\\n<td>Show Report, Macdonald Types, Pd<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Letters<\\/td>\\n<td>\\u00a0<\\/td>\\n<td>Iff C");
        sb.append("onversion, Scum!, <span><a href=\\\"\\/route\\/type:prod\\/importOrigin:worldofsam\\/importId:booty\\\" title=\\\"Click to view a local node.\\\">Booty<\\/a><\\/span> Review<\\/td>\\n<\\/tr>\\n<tr>\\n");
        sb.append("<td>Screen Archiver<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:scott-inwood\\\" title=\\\"Click to view a local node.\\\">Scott Inwood<\\/a><\\/span><\\/td>\\n<td>Arc");
        sb.append("hiver With Nice Selection Option<\\/td>\\n<\\/tr>\\n<tr>\\n<td>E-Tunes+<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:dan-zambonini\\\" title=\\\"Click to view a local n");
        sb.append("ode.\\\">Dan Zambonini<\\/a><\\/span> <span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:andrew-collier\\\" title=\\\"Click to view a local node.\\\">Andrew Collier<\\/a><\\/span> <span><");
        sb.append("a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:roger-hartley\\\" title=\\\"Click to view a local node.\\\">Roger Hartley<\\/a><\\/span><\\/td>\\n<td>Superb New E-Tune Player from <span><a hr");
        sb.append("ef=\\\"\\/route\\/importOrigin:worldofsam\\/importId:andrew-collier\\\" title=\\\"Click to view a local node.\\\">Andrew Collier<\\/a><\\/span> Elastica, Chaos II (2), Chaos II (4) \\/ Illegal Alien, ");
        sb.append("Can\\u2019t Play Your Game, Walking on Broken Glass, Captain Zlogg, Dies Irae \\/ Driving Force, Monday, Nautilus Chip, Last Ninja 2, Dead on Time, Selector<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Driver Mines<");
        sb.append("\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:allan-skillman\\\" title=\\\"Click to view a local node.\\\">Allan Skillman<\\/a><\\/span><\\/td>\\n<td>It's Mines Again, B");
        sb.append("ut For Driver<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Chaos<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:julian-gollop\\\" title=\\\"Click to view a local node.\\\">Julian Gollop");
        sb.append("<\\/a><\\/span><\\/td>\\n<td>The Classic Speccy Game, I Love It!<\\/td>\\n<\\/tr>\\n<tr>\\n<td>'The' Interview<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:colin-andert");
        sb.append("on\\\" title=\\\"Click to view a local node.\\\">Colin Anderton<\\/a><\\/span><\\/td>\\n<td>With <span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:tim-paveley\\\" title=\\\"Click to view");
        sb.append(" a local node.\\\">Tim Paveley<\\/a><\\/span><\\/td>\\n<\\/tr>\\n<tr>\\n<td>Mod -&gt; E-Tracker<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:stefan-drissen\\\" title=");
        sb.append("\\\"Click to view a local node.\\\">Stefan Drissen<\\/a><\\/span><\\/td>\\n<td>Very Fast Mod To <span><a href=\\\"\\/route\\/type:prod\\/importOrigin:worldofsam\\/importId:e-tracker\\\" title=\\\"Clic");
        sb.append("k to view a local node.\\\">E-Tracker<\\/a><\\/span> Converter<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Jellytext<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:matt-round\\\" title");
        sb.append("=\\\"Click to view a local node.\\\">Matt Round<\\/a><\\/span> <span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:graham-goring\\\" title=\\\"Click to view a local node.\\\">Graham Goring");
        sb.append("<\\/a><\\/span><\\/td>\\n<td>Tee Hee Hee Hee<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Lair of the I-Spy Bugs<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:victor-cooper\\\" title=");
        sb.append("\\\"Click to view a local node.\\\">Victor Cooper<\\/a><\\/span><\\/td>\\n<td><span><a href=\\\"\\/route\\/type:prod\\/importOrigin:worldofsam\\/importId:scads\\\" title=\\\"Click to view a local node");
        sb.append(".\\\">Scads<\\/a><\\/span> Arcade Shoot-Em-Up<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Modules<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importId:stewart-skardon\\\" title=\\\"Click to ");
        sb.append("view a local node.\\\">Stewart Skardon<\\/a><\\/span><\\/td>\\n<td>Converted Amiga Mods<\\/td>\\n<\\/tr>\\n<tr>\\n<td>Fredex<\\/td>\\n<td><span><a href=\\\"\\/route\\/importOrigin:worldofsam\\/importI");
        sb.append("d:colin-anderton\\\" title=\\\"Click to view a local node.\\\">Colin Anderton<\\/a><\\/span><\\/td>\\n<td>Fred Index<\\/td>\\n<\\/tr>\\n<\\/tbody>\\n<\\/table>\\n<p>\\u00a0<\\/p>\\n<h3>Magazine<\\/h3>");
        sb.append("\\n<pre>CA           Nottingham, Nottingham, Nottingham...\\n\\nWelcome to FRED 63 - the first of many to come to you from sunny\\nNottingham.  I've finally got round to dusting the old SAM off\\nand ");
        sb.append("loading Outwrite to do some work.  Hopefully now I can\\nsettle down into the normal run of things - FRED 62 was a rushed\\nfinish with bugs galore that needed to be corrected the night\\nbefore I wen");
        sb.append("t to university, and with an urgent appointment\\nwaiting for me in town (ahem).\\n\\nMy room is pretty good.  It's big enough to live in, and it's\\ngot a huge desk to, erm, work on.  The only proble");
        sb.append("m is that the\\nguy before me left a big iron mark in the middle of the floor\\nwhich I keep getting blamed for.  Anyway, that's not what\\nmatters, what matters is that SAM has got a nice desk to sit");
        sb.append(" on,\\nand he seems perfectly happy.\\n\\nWe had to fill in an electrical form the other day stating what\\nelectrical items we had and what power they used.  Rather\\nembarrassingly, it turns out tha");
        sb.append("t my electric shaver uses more\\npower than my SAM!  Blub.\\nCA                         Editorial\\n\\nThe other surprise happened within an hour of arriving.  When I\\ngot to Nottingham, a block rep.");
        sb.append(" from my block had to show me to\\nmy room and helped me carry all my stuff up.  He asked me what\\ncomputer I had and I did the usual, \\\"Oh, you won't have heard of\\nit\\\" routine.  However, it t");
        sb.append("urns out that he used to own a SAM\\nCoupe!  He got rid of his when SAMCo went bust, but was dying to\\nsee what was happening on it.  Although he's not going to buy a\\nnew one, the swine.\\n\\nLectu");
        sb.append("res are alright.  I've only got ten a week for some reason\\nwhile everybody else seems to have about twenty five.  The only\\nproblem is that we've got some really irritating **** in our\\nlectures w");
        sb.append("ho shouts out every single answer.  This just means\\nthat nobody else understands anything and the lecturer assumes\\nwe do.  I wish he'd go to Oxford and leave us all alone.\\n\\nI'll shut up about ");
        sb.append("how great life is.  Who wants to know how\\ncheap the beer is (Caffrey's \\u00a31.15 a pint, lager \\u00a31.00) or how\\nour hall has its own computer room?  (Triumphant laugh)\\nCA                   ");
        sb.append("      Editorial\\n\\nI'm now on the net, so if anyone wants to e-mail me telling me\\nhow much they love my editorials because they're really sensible\\nand informative, my address is:\\n\\n          ");
        sb.append("          [redacted]\\n\\nBit of a long, weird address isn't it?  The \\\"pmy\\\" bit stands\\nfor maths (?), \\\"l\\\" is my year, \\\"jja\\\" are supposed to be my\\ninitials (the maths department t");
        sb.append("hought I was called James James\\nAnderton - hmm), hhn1 is my hall's server, and you can guess the\\nrest.\\n\\nIf I can pull myself away from my studies, I'll check my mail\\nregularly and I'll proba");
        sb.append("bly reply (it's all dead exciting).  I\\nusually go to the computer room every two or three days, so\\ndon't get miffed if I don't reply immediately.\\n\\nColin Macdonald's e-mail address has changed,");
        sb.append(" but he'll tell you\\nabout that later (yes, he's actually written something).\\nCA                      Spectrum's Rule\\n\\nIt turns out that everybody at Nottingham University used to own\\na Spect");
        sb.append("rum when they were kids (or at least a Commodore) and\\nduring my explanations of what a SAM is, I naturally mention the\\nword Spectrum.  I've now had hordes of people coming in to play\\nall their f");
        sb.append("avourite Speccy classics!  I've had Atic Atac, Manic\\nMiner (they were impressed by the SAM version), Dizzy, Bruce\\nLee, Ghostbusters, Head Over Heels and countless others played\\nto death.  There'");
        sb.append("s been lots of people saying that they wished\\nthey hadn't sold their Speccy because they had a thousand games\\nfor it.  None of them want to buy a SAM though, the swines.\\n\\nIf anyone knows a pok");
        sb.append("e to give you infinite lives on Dizzy 4,\\nplease send it in.  There's so many things I can do that I\\nalways end up drowning before I can try new things.\\n\\nAs you can see then, it's been a bit of");
        sb.append(" a hectic few weeks, but\\nit's given me loads to talk about, so I might change\\nuniversities every month.\\n\\nCA                  The Gloucester Show...\\n\\nRather late on Friday night (or Saturda");
        sb.append("y morning), I set my\\nalarm for 5:15 and went to bed.  The plan was to wake up, eat a\\ncold pizza and have a 45 minute stroll to Nottingham station,\\nready in plenty of time to catch the 6:45 train");
        sb.append(".  Simple.\\n\\nAt 6:15 I opened my eyes and looked at my watch.  Aaaarrgghhh.\\nWhat had happened?  Why hadn't my alarm gone off?  Oh, I'd set\\nit for 5:15 pm.  Oops.  Anyway, there was no time to w");
        sb.append("orry about\\nthat - I threw all my stuff into a bag and began running.  I got\\ninto the city centre at 6:40, and promptly got lost.  I gave up\\nrunning (actually, to be honest I couldn't run for mor");
        sb.append("e than 20\\nseconds anyway) and strolled about looking for somewhere I\\nrecognised.  I eventually turned up at the station at 7:10 and\\nthe train had been on time (for the first time in history).  T");
        sb.append("he\\nnext train wasn't until 8:22, so I spent 35 minutes reading\\nevery magazine in the newsagents, bought one of them and sat\\ndown reading.\\n\\nThe train journey wasn't so bad, just an hour and a");
        sb.append(" half late.\\nCA        Today Quedgeley, Tommorrow, The World (ahem)\\n\\nI was chatting to some bird from Nottingham Trent Uni for most\\nof the journey, so it passed swiftly.  All this time, Colin h");
        sb.append("ad\\narranged for someone to wait for me at Gloucester train station\\n- sorry Gav.\\n\\nI eventually turned up at 11:30, to be greeted with lots of\\njokes at my expense.  Unfortunately, the number o");
        sb.append("f people\\nattending didn't seem to be very good compared to usual, but\\nhopefully this is down to this month's Scottish show.  All the\\nusual programmers turned up and chatted away about stuff they");
        sb.append("\\nare working on.  I deliberately avoided talking to Christina\\n(the one girl there) because of the stick I got last time.\\nColin Macdonald didn't seem to be worried about it though, the\\nsly old ");
        sb.append("fox.\\n\\nRevelation were showing off their latest release, Grubbing For\\nGold.  If you actually read what I write, then you should know\\nabout this little gem.  It looks really great, and Dave Hand");
        sb.append("ley\\nwas proud to show it off to the onlooking crowd.\\nCA            Hardware, Hardware and More Hardware\\n\\nNev's hard drive was up and running at last.  All it was able to\\ndo was load and disp");
        sb.append("lay screens, but all of the hardware is\\nfinished and Nev was taking orders.  Loading was damn quick and\\nthankfully 100% accurate (eg. no dodgy flashing pixels).  You\\ncan be sure we'll be the 1st");
        sb.append(" with any news.  Or maybe 2nd.\\n\\nAs ever Jupiter, Zodiac, Atomik Software and SAM PD were there\\nin their full glory selling their latest, erm, stuff.  Phoenix\\nturned up late (surprise, surprise");
        sb.append(") (joke).\\n\\nThe main shock of the show happened while I was stood talking to\\nDerek Morgan at the SAM PD stand.  Simon Cooke spotted me from a\\nmillion miles away, and like an eagle homed in on m");
        sb.append("e and pulled\\nme towards his SAM.  What was he showing off?  Had he finally\\nfinished 'Statues of Ice' after five years?  Well, not quite as\\nbig a surprise as that actually.  Simon had a prototype");
        sb.append(" of a SAM\\naccelerator board with Lemmings running at a very fast pace.\\nThe only problem was that the picture was all weird - there were\\ndots everywhere where it wasn't updating the screen.  Simo");
        sb.append("n\\nCA                     Bargains Galore...\\n\\noffered some clever 'Not my fault, honestly' explanation for\\nthis and assured us that all would be fixed.  Hopefully Simon is\\ngoing to pull his f");
        sb.append("inger out and get this project finished, but\\nit could be a looong time so don't hassle us about it.  If\\nsomething happens, we'll tell you.\\n\\nThat disc company were there again offering discs at");
        sb.append(" even\\ncheaper prices than before - just 18p for a SAM disc, so once\\nagain, I stocked up on blank discs for another 6 months.\\n\\nColin Piggot was showing off his Quasar board again, in\\nparticul");
        sb.append("ar a Star Trek demo which looked pretty impressive.  The\\nsound was a bit fuzzy, but that could be because his speakers\\nwere two boxes with holes banged in them with a pencil.  Colin\\ndidn't seem ");
        sb.append("to be having a very good day selling Quasars, so I\\nquietly sneaked off.\\n\\nAs well as all these people were a few Speccy companies or\\npeople selling lots of games, but I'm at the stage where I c");
        sb.append("an't\\nCA                       The FRED Stand\\n\\nbe bothered to get the Speccy out any more.\\n\\nAnd finally, there was the most important company in the\\nuniverse - FRED.  We were selling dozens");
        sb.append(" of classic programs as\\nusual.  Unfortunately, the C library hasn't been checked\\nthoroughly enough for a release, but that's now being looked at,\\nso be ready for a near release.  Colin was sport");
        sb.append("ing a new FRED\\nT-shirt, which incidentally are on sale for just \\u00a315.  They're\\nnot easy to describe, but if you imagine the FRED logo in 3D,\\nraytraced, turned a sexy milky blue, rotated rou");
        sb.append("nd a bit and\\nplaced in front of a reddy background (get the idea?  I doubt\\nit).  Also on sale were bargains galore, such as our back issues\\nat superb prices and discounts on a number of games an");
        sb.append("d\\nutilities.  And let's not forget that SAMSprite was released at\\nthe show with a huge A4 manual to accompany it.\\n\\nStefan Drissen had made the long journey once again and was\\ndoing his usual");
        sb.append(" trick of attaching loads of SAMDacs around his\\nneck and attempting to sell them to the unsuspecting crowd.\\nCA                   A Very, Very Nice Man\\n\\nAt about 12:30 I was stood minding my ow");
        sb.append("n business when someone\\ncame up to me and said, \\\"Are you the editor?\\\".  \\\"Yes\\\", I proudly\\nreplied.  \\\"Why do you have to write such rubbish?\\\", he asked.\\nWhat a nice man he was.");
        sb.append("\\n\\nThe show went on until about 2:30 - 3:00.  The attendance didn't\\nreally seem to pick up at all, but most people hung around for a\\ngood few hours.  Colin packed up with a smile on his face, s");
        sb.append("o it\\nwas clear then that FRED had covered their costs.\\n\\nAfter the show finished, half the SAM community took the pub by\\nstorm, and Lee Willis beat everyone at pool (git, git, git).\\nEveryone ");
        sb.append("collapsed when Colin offered to buy about 12 people a\\ndrink!!!  He's obviously been spending too much time away from\\nScotland.  That night, the FRED team spent a night in\\nGloucester, accompanied");
        sb.append(" by Stefan \\\"Do You Come To Gloucester\\nOften?\\\" Drissen (good chat up line, Stef), Martijn Groen and Rob\\nTyler (who no-one knows).\\n                                            MACDONALD'S BIT");
        sb.append(".....\\nCM                 SAM tackles a bigger wave\\n\\nBy now, you're probably well aware that there is a collection of\\nSAM users that communicate prolifically over the Internet - it's\\nnot unus");
        sb.append("ual for those of us with email accounts to receive well\\nover a hundred messages a week. These same users have also set\\nup a World Wide Web site - the Web has had a huge amount of hype\\nin the las");
        sb.append("t 12 months, and many claim it to be the future of the\\nInternet. Anyway, on this site there is a complete listing of\\nall hardware and software ever released on SAM, a who's who of\\nthe SAM world,");
        sb.append(" technical and non-technical information about SAM\\n- in fact, anything someone notices isn't there, usually soon\\nis! Also using the wonders of free University Internet access,\\nSAM piccies, MODs,");
        sb.append(" games and utilities can all be downloaded.\\n\\nThis is great for us students who have the time during the day,\\nbut the one thing SAM was missing was a Bulletin Board. Acessing\\na Bulletin Board (");
        sb.append("BBS) involves getting your computer (with a\\nmodem) on a phone line and dialling up. Once into the BBS you\\ncan swap messages with other callers from all over the world,\\ndownload files, upload you");
        sb.append("r own files for others to download,\\nCM                         BBS-city\\n\\nplay games against the other callers, or simply spend a bit of\\ntime having a browse around. The bad news is that we don");
        sb.append("'t have\\na modem for SAM yet, so you'll need a PC, Amiga or ST - with a\\nmodem - with a phone line (and the money to pay the phone\\nbills!). It does seem a bit ironic that you need a PC to access");
        sb.append("\\na BBS for SAM, but until someone produces a modem for SAM,\\nthere's no other way - we'll simply have to put up with\\ntransferring files to and from another computer.\\n\\nAnyway, the whole point ");
        sb.append("of this little article is to announce\\nthe availability of the first Bulletin Board for SAM! Most of\\nyou will remember Dave Whitmore's name from the SAM Adventure\\nClub, but now, Dave has started ");
        sb.append("up Dalmation BBS - and if he\\nputs in half as much effort to Dalmation as he did to SAM's\\nadventures, I'm sure we'll have a thoroughly excellent BBS for\\neveryone to enjoy.\\n\\nOK - I know that t");
        sb.append("here are perhaps only a few FRED readers that\\nhave access to the kind of equipment needed to access Dave's\\nCM                         BBS-city\\n\\nBBS at the moment, but if you do, you'll need a ");
        sb.append("few details :\\n\\nThe number is 01744 614150 but don't call yet! Because this\\nphone line is Dave's home phone, he can only operate the BBS on\\nSaturday's from noon to midnight at present. He is ho");
        sb.append("ping to\\nextend the operating period if things go well, but if you have\\nany queries about Dalmation BBS or want to arrange a BBS session\\noutside these hours, give Dave a ring using a normal phone");
        sb.append("\\n(although, obviously, not on Saturday afternoons or evenings!!)\\n\\nThe board officially launched on the 21st October, and I thought\\nI would log on to wish Dave all the best - as well as scope o");
        sb.append("ut\\nwhat was there. Well, everything worked perfectly - Dave has\\nmade provisions for all manner of conversation topics and file\\nareas. The only problem was that being only the second ever\\ncalle");
        sb.append("r (I believe Simon Cooke was the first), there wasn't a\\ngreat deal of stuff. However, I did download a few files - a PC\\nconverted piccy of the SAM logo, a photo of Simon Cooke (guess\\nwho uploade");
        sb.append("d THAT file...?!), and a handful of text files\\nCM                         BBS-city\\n\\ncovering topics from file compression on SAM to how to make best\\nuse of the BBS and swap files between your ");
        sb.append("SAM and another\\ncomputer.\\n\\nIf you have access to a modem setup, I would strongly recommend\\nchecking out Dalmation - the SAM needs to keep expanding into as\\nmany new areas as possible, and it");
        sb.append("'s great to see Dave doing his\\nbit to help things along. If you do log on, remember to give\\nDave a bit of encouragement and thanks - oh, and send me a mail\\nas well - I promise to reply!\\n\\nDal");
        sb.append("mation BBS - run by Dave Whitmore\\n\\n01744 614150 on Saturdays noon-midnight, until extension\\nannounced. Dave can be contacted by phone on the same number at\\nother times (usually early evening i");
        sb.append("s the best time to catch him\\nin).\\n\\n\\nCM                 It's all change ... maybe\\n\\nI know everytime I write, the word 'degree' tends to occur far\\ntoo often. But in case any of you have be");
        sb.append("en fortunate enough to\\nforget, I am now in my fourth year - Honours year. The first\\nthree years of my computing degree were relatively straight\\nforward - we got lectures, we got given coursework");
        sb.append("s, we studied,\\nwe got exams. This suited me fine - I am a reasonably quick\\nlearner when it comes to computing, so if I couldn't make it\\ninto University for a few days because of FRED, no problem");
        sb.append(" - I\\ncaught up easily enough. Everyone knows that scheduling and\\norganisation is not my strong point, so leaving courseworks to\\nthe last possible minute (while I got on with FRED work!),\\nworke");
        sb.append("d fine.\\n\\nThis was because at the end of each of the first three years we\\nare told either 'pass' or 'fail'. Simple as that. All I had to\\ndo was scrape a pass each year. For example, my attendin");
        sb.append("g a\\ncertain Welshman's wedding the weekend of my final degree exams\\n- I had already almost amassed a pass level from my courseworks,\\nso I only had to get a minimal amount of points in each of my");
        sb.append("\\nCM              Does BSc stand for Biscuit-head?\\n\\nexams in order to pass the year. And if you remember me proudly\\nadding a few letters after my name in July, you'll know that I\\ndid indeed p");
        sb.append("ass.\\n\\nHowever. With fourth year, everything changes. I already have a\\nstandard degree, whereas this year I am studying to turn that\\ninto an Honours degree, and unfortunately, an Honours degree");
        sb.append(" is\\ngraded - whilst a 3rd class Honours degree is a pass, sadly, it\\nis not of much use. A lower second class is very acceptable,\\nwhilst an upper second is excellent. Remaining, is of course the");
        sb.append("\\naward to which I am setting my sights, the first class.\\nAdmittedly, it's probably a bit unattainable for me - but I'm\\ncertainly going to try for the best possible grade.\\n\\nSacrifices have to");
        sb.append(" be made. Indeed, a lot already have. The one\\ntime legendary socialising status has been hung, drawn and\\nquartered. Already just in my third week back this year, I have\\nbeen seen going into Univ");
        sb.append("ersity on days off, during evenings,\\nand, shock horror, even on Sundays!\\nCM       Glorious Dundee, on the banks of the river.tay\\n\\nI don't predict any alteration to the usual FRED service in th");
        sb.append("e\\nfuture - either the magazine, the software or simply the orders\\nside. However, the phone line must once again bear the brunt of\\nmy trialling education. I will still answer the phone when I\\nc");
        sb.append("an, but these may be rare occasions and then only for brief\\nperiods. Can I ask that if at all possible, all correspondance\\nis carried out via the post. It's not so bad - the Royal Mail\\nare actua");
        sb.append("lly behaving reasonably well at the moment (apart from\\na few FRED61 discs arriving suspiciously faulty!), and you'll\\nprobably get a reply quicker than if you were waiting for me to\\nanswer the ph");
        sb.append("one! For those cursed with email facilities, I can\\nbe reached on [redacted]\\n\\nI do apologise for this inconvenience because I've always liked\\nto be easily contactable in need of a problem, but ");
        sb.append("I hope you\\nunderstand. Subscriptions to FRED are simply subscriptions to a\\nmagazine, and while I am, as always, very happy if I can help -\\nit would be easier if I knew that the phone only rang i");
        sb.append("f it was\\nsomething urgent.\\nCM                          Pee-cee?\\n\\nI mentioned the other month that FRED was trying out something\\nnew. To put it simply, in late Easter I was put in touch with ");
        sb.append("a\\nPC programmer. After a few meetings (although he's from\\nLiverpool, I've only been there once to meet him - we've also\\nmet in Carlisle and London ... don't ask!) we started discussing\\nthe ide");
        sb.append("a of his team developing a racing game for the PC, with\\nFRED as publishers.\\n\\nThe project has gone through stormy patches - one minute\\neverything's going fine, the next minute, everything seems");
        sb.append(" to\\nhave taken a downward spiral. At present, the game is\\nprogressing nicely - we were aiming for a Christmas launch, but\\nshort of a few minor miracles, it looks like things will be\\ndelayed sl");
        sb.append("ightly.\\n\\nI wish I could give more details, but there isn't really much\\nmore to tell which is definite. At present, we don't have a\\ndistribution deal for the UK or an outside publishing deal fo");
        sb.append("r\\nthe other PC markets - what we do have is a potentially great\\nCM                       ar? rsvp asap.\\n\\ngame, with a unique twist for a racing game - a storyline! As\\nwell as a few attention");
        sb.append(" grabbing tricks up my sleeve...\\n\\nSadly, I don't think I'll be able to launch it under the FRED\\nlabel, so a division of FRED will be created for the PC launch -\\nat the moment we're thinking of");
        sb.append(" naming it Actual Reality, but\\nthis hasn't been finalised. Because of this, the answering\\nmachine may change message but that doesn't mean anything on the\\nSAM side will change.\\n\\nThe long and");
        sb.append(" short of the PC project is that if it succeeds,\\nFRED \\/ Actual Reality will live long and prosper, continue to\\nsupport SAM and release more PC titles. If for some reason, all\\nthe big companies");
        sb.append(" that make billions out of the games industry\\ndo a good job of stopping us being successful, I'll be applying\\nfor jobs come Summer! So, for you, worst case scenario is that\\nFRED continues as nor");
        sb.append("mal, best case is that FRED suddenly\\nbecomes a successful company, and who knows....?!\\n\\nCA                           News\\n\\nFRED would like to apologise for the slight delay with SAM C\\nupda");
        sb.append("te discs and the SAM Vision library.  However, FRED has\\ncommissioned Jupiter Software to fully test these discs.\\nHopefully, the update discs should reach you soon, if they\\nhaven't already.  The ");
        sb.append("SAM Vision library should follow soon.\\nJupiter are said to be VERY impressed with the new library, so\\nyou can be sure it'll be worth the wait.\\n\\nAs mentioned in the show report, FRED are now se");
        sb.append("lling some\\nrather sexy T-shirts.  If you read the description in the\\nreport, you'll see that by buying one, you can walk the streets\\nin style!  And what will one of these exclusive pieces of\\nc");
        sb.append("lothing cost you?  Just \\u00a314.99.  OK, it's a little expensive,\\nbut that's because not many will been done.  You'll be one of an\\nelite (or coupe?).  Cheques to the usual address.\\n\\n\\n\\n  ");
        sb.append("                                         Huge Bargain &gt;&gt;&gt;&gt;&gt;&gt;&gt;&gt;\\nCA        ****************  BARGAIN  ****************\\n\\nHowever, if you're looking for a superb Christmas ba");
        sb.append("rgain, Colin\\nMacdonald has just told me of a FRED pack which he will be\\nputting together.  The pack will contain a number of FRED\\nmomentoes for you to keep and remind you of FRED, or for you or");
        sb.append("\\nfriends to use for their correct reasons!  Of course, as this\\nwill be another souvenir, FRED will not be making any profit on\\nit.\\n\\nThe FRED pack contains: A FRED T-shirt\\n                 ");
        sb.append("       A FRED Pen\\n                        A FRED Calculator\\n                        A FRED Diary\\n                        A FRED Badge\\n                        A Best of FRED disc (compiled by m");
        sb.append("e!)\\n\\nAll of this will cost just \\u00a320!  I hope you'll agree that it's\\nan absolute bargain, so please, whether it's for the sentimental\\nvalue, or the damn useful value, buy it (either with ");
        sb.append("your own\\nmoney, or tell Santa it's the best present you could ever want).\\nCA                            News\\n\\nOne thing we must stress.  This is a separate thing to the other\\nFRED products, ");
        sb.append("so **PLEASE PAY FOR THE PACK WITH A SEPARATE\\nCHEQUE**.\\n\\nOf course, you can also see the practical advantage.  If you\\nwant to buy your dad a present, you could give him the pen or\\nthe diary a");
        sb.append("nd have all the rest to yourself!  And you could give\\nyour really irritating little brother the badge and just take it\\nback once he's stabbed himself a few times!\\n\\nThe Best of FRED disc contai");
        sb.append("ns the very best programs to ever\\nhave appeared on FRED in the last five years.  It has been\\ncompiled by me, so it's a bit like \\\"FRED - The Editor's Cut\\\"!\\nI've taken a lot of input from ot");
        sb.append("her people, and I'm sure you'll\\nsee it is the best SAM disc you can get your hands on.\\n\\nI'd better make this clear once more - make cheques for the FRED\\npack payable to FRED Publishing for \\u");
        sb.append("00a320, but make the cheque\\nseparate from any other payment.  Thanks, we hope you like it.\\nCA                       Disc Contents\\n\\nThe other day, I was looking through all my old FRED discs wh");
        sb.append("ich\\nI was given when I became editor when I came across a disc from\\nScott Inwood.  I haven't a clue why this utility got missed, but\\nit really is very impressive.  It's a complete screen archive");
        sb.append("r\\nand viewer.  It was originally intended for viewing FRED\\nscreens, but due to time and memory, this isn't possible.\\nHowever, for your own personal collection of screens, this is\\npossibly the ");
        sb.append("best storage program I've ever seen.  There are\\nfull instructions to get you going, so you shouldn't have any\\ntrouble.  The three example screens are from the Best Of FRED\\nDisc - the only new se");
        sb.append("ction on the \\\"Best Of\\\" disc.  Many thanks\\nfor this, Scott, and sorry for the delay in getting it on FRED.\\n\\nAndrew Collier has been asking about why we don't put E-tunes in\\nsection E (E f");
        sb.append("or E-tunes).  Well, it's because we all know and\\nlove slot I as the E-tunes slot.  However, this month only, I've\\nput it in slot E.  This is because Andrew has written a new\\nE-tunes player, and ");
        sb.append("what a cracker it is too!  Far too many\\nscroller effects to talk about (I'll probably stick with the\\nCA                       Disc Contents\\n\\nslower readable one when I write the scrolly from n");
        sb.append("ow on), a\\nparallax starfield, and mainly it doesn't load each tune one at\\na time!  It loads them all at once and operates on a CD type\\nsystem.  Load it up, and drool at it's newness and magnific");
        sb.append("ence.\\nThanks Andrew - another plus in the Mnemotech box.  I wonder if\\nEntropy can fight back (what a cunning way of getting\\ncontributions).\\n\\nOK, OK.  I know that the next item is another min");
        sb.append("es game.  I\\nknow I said that Andrew Collier's mines game couldn't be beaten\\nand would be the last one for a very long time.  However, you'll\\nunderstand me putting this on when I tell you it is f");
        sb.append("or Driver\\nand it is superb.  It is almost an exact copy of the PC version,\\nand let's face it, a desktop system isn't a desktop system\\nwithout a version of Minesweeper!  Unfortunately, I don't ha");
        sb.append("ve\\nthe name of the person who wrote this.  It was given to Colin M\\nat the show, so if the author is reading this, please write in\\nand make yourself known, otherwise I can't pay you!  Many, many");
        sb.append("\\nthanks.\\nCA                        Chaos Rules\\n\\nAnd next, at last, we are able to bring FRED readers the BEST\\nSpectrum game ever.  Yes, it's Chaos by Julian Gollop.  You may\\nremember a few");
        sb.append(" months ago that Julian declared all his games on\\nthe Spectrum to be public domain.  Colin and I chatted about\\nputting something on FRED, and now we have decided that Chaos\\nshould go on.  Puttin");
        sb.append("g Spectrum games on won't be a regular\\nthing - in fact, it probably won't happen again, but Chaos is\\nsuch a superb game that it doesn't matter.  Many thanks go to\\nJulian Gollop - the best progra");
        sb.append("mmer who ever lived.\\n\\nAnyway, the Chaos rules (crikey, this won't be easy).\\n\\nOK, you are a wizard.  Simple enough.  Your mission is simply to\\ndestroy the other wizards that are alive, and th");
        sb.append("ese will either\\nbe controlled by your friends or by the computer.\\n\\nWhen you start, you can select the number of wizards you want to\\nuse - 2 to 8.  Then select the computer's intelligence level");
        sb.append(" 1-8\\n(8 being the most powerful).  Then, one at a time, you must type\\nCA                        Chaos Rules\\n\\nin the name of your wizard, choose whether he should be computer\\ncontrolled or no");
        sb.append("t and then his graphic and colour.  It doesn't\\nmatter which graphic or colour you choose - it makes no\\ndifference.  I'm not sure if this is a common thing, but on my\\ncomputer, some keys tend to ");
        sb.append("repeat.  If a key does, press\\nSHIFT+0 to delete one character.\\n\\nThe first human player will then be given a menu, consisting of\\n1 - Examine Spells, 2 - Select Spells, 3 - Examine Board, 4 -\\n");
        sb.append("Continue.  If you've played before, you'll know what to do.  If\\nyou haven't, select 3 to examine the board.  This is the main\\nplaying area and at the moment should only contain the number of\\nwiz");
        sb.append("ards you selected.  The keys to move the cursor are the ones\\naround the letter 'S'.  Q is up-left, W is up, E is up-right, A\\nis left, D is right, Z is down-left, X is down, C is down-right.\\nS is");
        sb.append(" the select, or fire button.  Key 'I' will tell you\\ninformation about the wizard or creature the cursor is over, 0\\nwill end what you are doing, and K is the cancel key.  Press 'I'\\nover your wiza");
        sb.append("rd.  You will notice a number of statistics.  The\\nCA                        Chaos Rules\\n\\nkey ones are DEFENCE (How well you can defend yourself), COMBAT\\n(How well you can attack), MOVEMENT ALL");
        sb.append("OWANCE (How far you can\\nmove) and ABILITY (Whether you are really good at casting spells\\nor not).  The rest do mean things, but there isn't any need to\\nworry about them for the moment.  Press 0 ");
        sb.append("to get off the\\ninformation screen and 0 to go back to the menu.\\n\\nPressing 1 will let you examine your spells.  Once you've played\\nthe game a few times, you will rarely bother with this.  You");
        sb.append("\\nshould now see a list of spells, beginning with A-Disbelieve.\\nApart from Disbelieve, you have one of each of the spells.  You\\nhave an infinite amount of disbelieves.  You will also notice\\ntha");
        sb.append("t the spells are different colours and all have either a\\nstar, an arrow or a dash by them.\\n\\nThe colour tells you how likely a spell is to be cast.  By\\nselecting the spell when in the 'Examine ");
        sb.append("Spell' menu, you can\\nsee the percentage chance as well.  In general, these are the\\npercentage probabilities (+ or - 10%)\\nCA                        Chaos Rules\\n\\nWHITE - 100%, YELLOW - 80%, LI");
        sb.append("GHT BLUE - 60%, GREEN - 50%,\\nMAGENTA - 30%, RED - 10\\/20%\\n\\nThere are far too many spells to explain here, but here is a\\nbrief run-down of some of them:\\n\\nORC, ZOMBIE,   - These are all cre");
        sb.append("atures.  When a creature is\\nGOBLIN,          cast, it can be used to kill other creatures\\nCENTAUR,         or wizards.  This is the main tactic of the\\nDRAGONS,         game, so it is useful to b");
        sb.append("uild up an army of\\nVAMPIRE,         creatures.  You know if a spell is a creature\\nSKELETON,        by examining the spell and if a load of\\nWRAITH, HORSE,   statistics come up where movement is g");
        sb.append("reater\\nLION, HYDRA,     than 0, it's probably a creature.  More about\\nEtc.             creatures later.\\n\\nMAGIC FIRE - Starts a fire, which spreads and destroys enemy\\n              creatures ");
        sb.append("and wizards.\\nGOOEY BLOB - A gooey slime spreads across the map, devouring\\nCA                        Chaos Rules\\n\\n              creatures and wizards.  However, unlike FIRE,\\n              thi");
        sb.append("s can be attacked and destroyed.\\n\\nMAGIC BOW, WINGS, ARMOUR, KNIFE, SWORD, SHIELD - These give your\\n              wizard extra defence, attack or a new skill.\\n\\nMAGIC CASTLE, MAGIC CITADEL - Y");
        sb.append("ou can hide in these until they\\n              disappear.\\n\\nSUBVERSION - You can attempt to cast this on a creature and if\\n              you overcome their magic resistance, they become\\n      ");
        sb.append("        yours.\\n\\nVENGEANCE, JUSTICE, DARK POWER - This will make the selected\\n              wizard's creations disappear if his magic\\n              resistance is overcome.  Alternatively, it ca");
        sb.append("n be\\n              cast on single creatures.\\n\\nMAGIC WOOD - Eight trees are cast if there is room.  Then, you\\nCA                        Chaos Rules\\n\\n              or any other wizard can go");
        sb.append(" inside the tree and\\n              wait until it gives you a new spell.\\n\\nSHADOW WOOD - You choose where to cast eight trees.  These trees\\n              can attack enemies creations.\\n\\nMAGIC");
        sb.append(" BOLT, LIGHTNING - Zaps something close by.\\n\\nA Word About Chaos and Law - Chaos and Law are like good and\\nevil in Chaos.  Spells which are Chaotic have an asterix (star -\\n*) by them, spells wh");
        sb.append("ich are Lawful have an arrow (\\u2191) by them,\\nspells which are neutral have a dash (-) by them.  When more of\\na certain spell is cast, the 'atmosphere' becomes more of that\\ntype.  This is show");
        sb.append("n on the main menu.  Eg. above the options,\\nit may say (CHAOS ***) denoting 3 extra chaos.  When there is a\\nlot of Chaos, Chaotic spells become easier to cast, and vice\\nversa.\\n\\nCreatures Tak");
        sb.append("e 2 - There are 2 types of creature, real and\\nCA                        Chaos Rules\\n\\nundead.  Skeletons, Zombies, Ghosts, Etc. are undead and can\\nonly be attacked by other undead things, but c");
        sb.append("an themselves\\nattack anything.  Real creatures can only attack other real\\ncreatures.  It will all become clear with practice.\\n\\nWhen you cast a creature, it will say  ILLUSION (Y\\/N).  If you");
        sb.append("\\nselect Y, then the spell will definitely be cast, but it can be\\nDISBELIEVED by other wizards.  If N is selected, then their\\ndifficulty depends on the colour of the spell, but if the spell\\nsuc");
        sb.append("ceeds, it can't be disbelieved.\\n\\nThere are lots of other tricks to Chaos, but these are too\\nnumerous to explain.  Some creatures can be ridden by wizards\\n(Eg. Horse, Unicorn), which is useful.");
        sb.append("  All of these sorts of\\nthings are best learnt by playing the game.\\n\\nOn each of your goes, you get to move each of your characters\\nonce.  Do this by moving the cursor with the keys around S, a");
        sb.append("nd\\nS to select.  It will then say 'MOVEMENT - x' where x is a\\nCA                  Last Page Of Chaos Rules\\n\\nnumber.  That's how far it can move.  Roughly, diagonals count\\nas 1, then 2, then ");
        sb.append("1, then 2, etc..  Once he has moved, he may\\nbe able to fire.  If so, move the cursor to where you want him\\nto shoot, and press S. If you don't wish to move the full\\nditance, or don't want to sho");
        sb.append("ot, PRESS K TO CANCEL.\\n\\nIf I haven't made much obvious, sorry.  Stick with the game, it\\nreally is superb.  The best way to learn Chaos is to play it,\\nand if you get stuck, write in and I would");
        sb.append(" only be too pleased\\nto help.  Maybe at some show in the future, we could have a\\nSAMmers Chaos competition like Y.S. did....\\n\\nOnce again, can FRED say many thanks to Julian Gollop for\\nallowi");
        sb.append("ng Chaos to be made wide-spread.  THANK YOU!\\n\\n\\n\\n\\n\\nCA                       Disc Contents\\n\\n\\\"The\\\" Interview this month contains Tim 'WWWeb Man' Paveley.\\nThere's quite a bit of in");
        sb.append("terest in here, so read on...  Which\\nreminds me, if there's anyone you want to see interviewed, write\\nin and we'll see what we can do.\\n\\nIn I, we have something that you may say 'OI! That's bee");
        sb.append("n on\\nFRED before' to.  Well, yes, but this is a different version.\\nIt converts modules to a readable E-tracker format.  You still\\nneed to create instruments and tidy it up, but it's very useful.");
        sb.append("\\nFor starters, it's not Shareware so everyone can use this\\nwithout having to pay.  Secondly, it's written in machine code,\\nso is quicker than Colin Piggot's version.  Thirdly, it has a\\nnumber ");
        sb.append("of new things, including no bugs and it recognises more\\nmodule functions.  It's by Solar Flare and is the first FRED\\nprogram to have been sent along the miraculous e-mail system.\\nThanks, Stefan.");
        sb.append("\\n\\nCRIPES!  I've actually used Stefan's converter, and it runs at\\nlightening speed.  That's amazing.  Erm, well done Stef!\\nCA                       Disc Contents\\n\\nJellytext this month has a");
        sb.append(" new helper, and consequently Matt's\\nmind has been affected and he's pushing the family mag thing a\\nbit too far in places.  Of course, it's even more hilariously\\nfunny, but if you're liable to b");
        sb.append("e offended by occasional\\nswearing, and, erm, a blocky picture (say no more), then don't\\nload this.  Matt Round (and Graham Goring) will get slapped\\nwrists for this, don't worry.  Thanks Matt and");
        sb.append(" Graham, keep up\\nthe good work - even if I do read it and just say \\\"Ho ho ho, OH\\nNO, HE CAN'T PUT THAT!  Ho ho ho.  OR THAT!  OH NO.  Ho ho ho.\\\"\\n\\nWhile I'm on the subject of Jellytext, p");
        sb.append("lease write some\\narticles for it if you have time.  I know a few people have used\\nthe editor and decided their stuff isn't good enough, but I'm\\nsure that's not true.  Besides, this is your chanc");
        sb.append("e to get the\\nsort of discussions that you want in FRED.  Even though it's all\\njokey, we will welcome serious matters and talk about them in\\nthe light they are intended.  It's your chance to shap");
        sb.append("e FRED!\\nSend contributions to Matt or FRED.  Thanks.\\n\\nCA                       Disc Contents\\n\\nThis months adverts are the first that have been paid for, so\\nplease do look at them.  The peo");
        sb.append("ple who have paid for adverts\\ndeserve their fair share of responses, so please have a look,\\nand if anything interests you, write for more information - you\\ndon't have to buy anything until you'r");
        sb.append("e sure, but if our\\nadvertisers get a response, they'll keep coming back.\\n\\nAnd another 6 issues have passed, so it's about time FREDEX got\\nupdated.  If you haven't seen FREDEX before, it's basi");
        sb.append("cally a\\ncatalogue of items that have been on FRED with descriptions and\\na handy search option.  Search for your fave things, or just\\nbrowse through at leisure.  Thanks should go to Brian McConne");
        sb.append("ll\\nfor this as he did write it quite a while ago.  Cheers.\\n\\nAnd let us not forget the absolutely stunning menu.  The first\\none ever to have a game in it, and please note that it is\\nwritten i");
        sb.append("n SAM C.  We have the man in the interview to thank for\\nthe menu.  Better late than never.  Many thanks, Tim Paveley.\\n\\nCA                      Competition Time\\n\\nIt's about time we had anothe");
        sb.append("r competition.  This time, it's an\\nidea I had.  This may seem like we're turning into Format, but\\nbelieve me, it's only being run for my selfish reasons.\\n\\nAnyway, the competition is a programm");
        sb.append("ing one.  The challenge is\\nto create the best text converter.  That is, the best utility\\nthat can convert PC (or any other format) text that isn't 64\\ncolumn and is full of character codes into S");
        sb.append("AM 64 column text\\n(MODE 3: CSIZE 8,8).  The winner will get copies of Klax,\\nDefenders Of The Earth and SAM Strikes Out, as well as the FRED\\nvoucher when the winner gets put on FRED.  And just to");
        sb.append(" clarify,\\nwhen I say 'copies', I do mean LEGAL copies.  Sigh.  Why does\\nthe word 'copies' mean two very different things?\\n\\nThe deadline for this competition is 20th January, but the\\nearlier ");
        sb.append("you can get things in, the better.\\n\\n\\n\\nCA                          Thanks\\n\\nEditor (and debugger extraordinaire) : COLIN ANDERTON\\nCompany Director (and contributor!!) : COLIN MACDONALD\\n");
        sb.append("\\n         An Infinite Amount Of Gratitude Goes Out To:\\n\\n  Julian Gollop                              Driver Mines Bloke\\n     Scott Inwood                          Andrew Collier\\n        Tim ");
        sb.append("Paveley                      Stefan Drissen\\n           Matt Round                  Graham Goring\\n          Victor Cooper              Doug Young\\n             John Hancock          Dean Nicholas");
        sb.append("\\n              Martin Wilson      Brian McConnell\\n                Nigel Ackroyd  Alan Groves\\n\\nFRED (issues 1-63) are available from FRED Publishing,\\n                                      [re");
        sb.append("dacted]\\n\\n  FRED's PD section, plus 3\\n    film reviews follow\\nCA                        It's Back...\\n\\nBefore Dean starts, can I apologise for missing out last months\\nPD section.  Disc err");
        sb.append("ors won, I'm afraid.  However, FRED's\\nregular PD reviewer, Dean Nicholas is back with this months\\ninstallment, and I hear next months is already waiting at\\nColin's house.  Blimey.  Take it away,");
        sb.append(" Dean...\\n\\nDN                         PD Section\\n\\nHello again, and welcome to FREDs amazing PD nit, where we find\\nout what's out for those of you who are a bit bit out of pocket.\\nThis month");
        sb.append(" we have a couple of newies from SAM PD, and guess\\nwhat? They are both great (well, what did you expect with them\\none being written by Matt Round, the other by Stefan Drissen?).\\nEnough rubbish, ");
        sb.append("on with the reviews.\\n\\nVCR by Matt Round\\n\\nVCR stands for Video Casette Recorder.Eh? Well, it is basically\\na program that plays a selection of Grab\\/Put blocks in quick\\n\\nsuccession, and a");
        sb.append("llows you to view them and things like\\nthat.Only one sequence comes with it, but more are (apparently)\\non the way.The one that does come is a Star Trek:The Next\\nGeneration action sequence.The bl");
        sb.append("ocks can be shown in either\\nfull screen size or quarter screen size.The full size screen is\\na little confusing to say the least, and it is very difficult to\\ndetermine exactly what is going on.Ho");
        sb.append("wever, simply switch it\\ndown to quarter size and you can really appreciate what is\\nhappening.The quality of the clip (which is roughly 5-10 seconds\\nlong) really is superb, and the speed at which");
        sb.append(" it is  played is\\nvery impressive.Other things that you can do with the clips are\\nplay it backwards or forwards, advance each frame one by one,\\nchange the palette scale, and change the size and ");
        sb.append("speed.The\\ncontrol system is also very user friendly, as it utilises a menu\\nbar at the bottom of the screen.\\n\\nOverall, it is a very impressive piece of PD, and well worth\\nyour  1.50.I suggest");
        sb.append(" that you order it today.\\n\\nRating: 85%\\n\\nSolar Flare by Stefan Drissen\\n\\nNext up we have a compilation offering from Stefan Drissen.It\\ncontains quite a lot of stuff in it, mostly unseen, a");
        sb.append("nd is as\\nsuch rather excellent value for money.First is an E-Player, the\\nsame one that used to be on Fred before Andrew Collier's effort\\ntook over.It contains 8 tunes, the majority of which have");
        sb.append(" been\\nheard before in some place or another.Next there is a program\\ncalled Ditherer which changes the cyan colours in supposedly\\ngrey screens to proper greys, to enhance the look of them.It\\ndo");
        sb.append("es produce a noticeable difference, but would probably not be\\nused that much.Next we have 4 converted Speccy demoes.The best\\non is The Lyra II, the forerunner to the Coupe's III.It is\\namazing, a");
        sb.append("nd puts SAM demoes to shame, especially the dancing\\nwoman and the digitized music.The other ones (Silly Demo, Living\\nS**t and Rende-vous) are okay but not memorable.After this is\\nStefans contrib");
        sb.append("ution to the infamous Statues of Ice, which is a\\nnice but not really amazing demo featuring a wraparound Entropy\\nlogo.Windows demo is an insight into what could have been a fine\\nversion of Windo");
        sb.append("ws to SAM.Larry demo is a demo of what could\\n\\nbeen Leisure Suit Larry for SAM.It is funny, especially the\\nquestions asked before the game to make sure you are over\\n18!Lastly on the disk is the");
        sb.append(" new Mod player demo, which was on\\nFRED 58.\\n\\nOverall, this disk is great value for money and features some\\ngood stuff on it.Get it.\\n\\nRating:88%\\n\\n\\n\\n\\n\\n\\n\\n\\n\\n\\n            ");
        sb.append("                                    Keep Reading....\\nSAM PD Catalogue\\n\\nIt may seem a bit strange to review catalogue, but  that's  what\\nI'm going to do.SAM PD have just released their entire c");
        sb.append("atalogue\\non disk just like the SCPDSA did  in  their  later  years.On  it\\nthere is a  list  of  their  entire  collection,  along  with  a\\ndescription of each product.There is also a demo of the");
        sb.append("  (rather\\nawful) game Safari Sam, and a demo of the latest Mod player like\\nthe one on Fred 58 and the Solar Flare demo.The Screens  section\\nis basically several screens advertising Sam  PD  prod");
        sb.append("ucts,  and\\nthere are also small demo\\/adverts for SAM2SAM and Network Sigma.\\nI guess that because it is a catalogue I can't give it a mark,\\nbut I do suggest you get it as it is much better than");
        sb.append(" the paper\\nversion in my opinion.\\n\\n\\n\\n\\n\\n\\n\\n\\nAll of the above are available from:\\n\\n                             Sam PD\\n                           [redacted]\\n\\nSolar Flare and");
        sb.append(" VCR are priced at \\u00a31.50, while the catalogue is\\nonly a quid or free if you send a blank disk and an SAE.\\n\\nThats all for this month, hopefully next month we should have\\nmore than two and");
        sb.append(" a half reviews.If you feel like writing a PD\\nreview then send it to FRED and Colin will put it in (it doesn't\\nmatter if it's already been covered, it always help to get more\\nthan one point of v");
        sb.append("iew).Goodbye.\\n\\n\\n                            Doug Young's Film Reviews Follow....\\nDY                        FILM REVIEWS\\n\\nReview of POCHAHONTAS - Disney's latest blockbuster\\nCertificate  ");
        sb.append("         - Universal ( U ) Playing length - 81 mins\\nDirector              - Mike Gabriel, Eric Goldberg\\nStar Voices           - Mel Gibson, Irene Bedard, Russel  Means,\\n.                       L");
        sb.append("inda Hunt and Billy Connolly\\n\\nPochahontas is Disney's 33rd full-length animated extravaganza.\\nPochahohtas tells the extraordinary romantic tale of an American\\nIndian princess who saved an Engl");
        sb.append("ishman's life - Pochanohtas  is\\nDisney's first dramatisation on an actual historical event.\\n\\nThe story is set in 1607 and focuses on the conflict between the\\nBritish, arriving in  the  New  Wo");
        sb.append("rld  to  find  gold,  and  the\\nresident Native Americans.\\n\\nThe original tale  has  it  that  Pocahontas,  daughter  of  the\\nAlgonquin chief Powhatan, took such a  liking  to  the  handsome\\ne");
        sb.append("nglish officer Captain John Smith that she was prepared to swop\\nplaces with him when he was threatened with execution.\\n\\nNow Disney has added its own distinct flavour to the  epic  yarn\\nand fro");
        sb.append("m the opening credit sequence, when we're  introduced  to\\nthe lyrical lifestlye of the Indians - set  to  the  captivating\\nnumber Steady As the Beating Drun - to the thrilling climax, the\\nmovie ");
        sb.append("casts its own magic spell.\\n\\nAll of the animation throughout the  film  is  excellent  and  a\\npleasure to the eye, all though this  is  quite  common  to  all\\nDisney films, e.g Aladdin, Beauty ");
        sb.append("and the Beast and  The  Little\\nMermaid.\\n\\nThe voices of all of the characters have been excellently chosen\\n, the two best voices are ; Mel Gibson as John Smith  and  Irene\\nBedard as Pocahonta");
        sb.append("s.\\n\\nThis film is a must for all, but it will  be  most  offering  to\\nsmall children. An excellent  script  supported  with  excellent\\nanimation and  a  good  choice  of  voices  makes  this  a");
        sb.append("  very\\nenjoyable film.\\n\\n\\nOVERALL 94% ( If you don't like feature  length  cartoons,  this\\nfilm might surprise you and make you change your mind )\\n\\n              -------------------------");
        sb.append("----------\\n\\nMORTAL KOMBAT\\nDirector   - Paul Anderson\\nStars      - Linden Ashby, Robin Shou, Bridgette Wilson, Cary -\\nHiroyuki Tagawa, Christopher Lambert\\nClassification - 15\\n\\nBased on ");
        sb.append("what's said to be the most successful video game ever,\\nthis all\\/action adventure pits the skills of three contrasting\\nhuman combatants against a bizarre and deadly foe in the\\nforbidding Outwor");
        sb.append("ld.\\n\\nThere the good guys , SONYA BLADE, JOHNNY CAGE AND LIU KANG must\\npool there considerable physical talents and mental abilities to\\ndefeat the evil sorcerer SHANG TSUNG before he can claim ");
        sb.append("the\\nrealm of Earth for his own dark master.\\n\\nAll of the characters from the first two games have been\\nincluded and most of them have been portrayed by good actors,\\nthe best being Christopher");
        sb.append(" Lambert as the God of Thunder - Lord\\nRayden and Robin Shou as Liu Kang.\\n\\nIf you liked the game you will absolutely love this film,\\nstreetfighter who ?\\n\\nAn altogether martial arts film wit");
        sb.append("h a bit of a bizarre twist\\nincluded.\\n\\nOVERALL 90% ( A must for all action film lovers )\\n\\n                -------------------------------\\n\\nSPECIES\\nDirector - Roger Donaldson\\nStars - B");
        sb.append("en Kingsley, Michael Madsen,  Forest  Whitaker,  Alfred\\nMolina, Natasha Henstridge, Margaret Helgenberger.\\nClassification - 18\\n\\nIn short, it's a suspense thriller that combines the  very  best");
        sb.append("\\nelements  of  classic  sci\\/fi  plots   down   the   years   with\\nstate-of-the-art effects and thrilling designs.\\n\\nThe design was done by ocsar winner H.R.Geiger ( the person  who\\nthought ");
        sb.append("of the alien in the most successful film of 1979,  ALIEN\\n), a Swiss-based designer,artist scupltor and architect. He  has\\nonce again successfully acheived an astonishing fusion of  flesh\\nand  ma");
        sb.append("chine  -  a  form  that  Geiger  has   described   as   '\\nBIOMECHANICAL '.\\n\\nThe plot of the film is that  scientists  inject  a  mixture  of\\ndifferent dna sources in to a young girl, hoping  t");
        sb.append("o  acheive  a\\nbetter life form. Unfortually fo the scientists every thing goes\\ntotally wrong when the injected DNA combines with  the  original\\nDna of the young girl and produces a new  organism");
        sb.append(",  the  result\\nbeing a stunning 21-year old woman whose only  objective  is  to\\nmate with human beings and propogate her aggressive species.\\n\\nSo there you have it across between Basic Instinct");
        sb.append("  and  Aliens,\\n\\nbut what does it actually come out like? well it is  fabulous  !\\nFor the last few years I didn't think anything would be able  to\\nbetter Aliens, but this film puts in firmly in");
        sb.append(" its place.\\n\\nIts got everything violence, sex, aliens and even a plot  to  go\\nwith it what more could you want. The  film  definetly  deserves\\nthe 18 certificate with it's explicit sex  scenes");
        sb.append("  and  gruesome\\nviolence. I reckonmend this film to  anyone  who  likes  actions\\nfilms, sci-fi films or erotic thrillers.\\n\\nOne things for sure, you  won'T  be  able  to  get  bored  while\\nwa");
        sb.append("tching this film, because everything is set  at  such  a  fast\\npace, basically it's excellent, go see it.\\n\\nOne point to end, this film isn't supposed to be thought  of  as\\nthe next Alien film ");
        sb.append("of Predator film, but  it  is  a  very  good\\ncontender, Alien 4 ( scheduled for a christmas 96 release, while\\nAlien vs Predator has been terminated with a project  gap,  make\\nany sense to you? )");
        sb.append(" is going to have to a brilliant film to take\\nback it's crown of being the best sci-fi film, as by  the  looks\\n\\nof this film it has well and truly lost it.\\n\\nOVERALL   97%  (  ESPECIALLY  IF ");
        sb.append(" YOU  LIKE  ANY  OF  THE  ABOVE\\nMENTIONED TYPE OF FILMS, ESPECIALLY ALIEN AND PREDATOR ).\\n\\nOVERALL 90% (IF YOU DON'T REALLY GO FOR THE ALIEN TYPE OF FILM )\\n\\n\\n                   -----------");
        sb.append("-------------\\n\\nCA                           Ta-ra\\n\\nMore next month from Dean and Doug, plus a show report or two\\nfrom some of the other people who were there.\\n\\nSee you then - I'll do my ");
        sb.append("best to get FRED 64 out in time for\\nChristmas.\\n<\\/pre><h3>Letters &amp; Reviews<\\/h3>\\n<pre>                    Letter From John Hancock\\n\\nDear FRED Publishing,\\n              CONFESSION: I");
        sb.append(" have just bought a second-hand\\nAmiga. [FOOL! - CA]  Sorry but hey (!) it was dirt cheap.  What\\nI'd like to know is if it might still be possible to get my\\nhands on an IFF picture converter for ");
        sb.append("my still-much-cherished-\\nif-slightly-p***ed off Coupe (FACT: ever since I got the A500 my\\nSAM's 256K expansion has had a tendancy to wobble out of its\\nsockets and cause a reset.  Bloody fiddly t");
        sb.append("hing it is too.  Has\\nanyone else had the same problem?)  I remember an advert for one\\nsuch piece of software having been advertised in the long since\\ndead PUBLIC and maybe even in Fred itself.  ");
        sb.append("(I had to mention\\nPublic as it is about the only time I've ever had any sizeable\\ndemo published in a Sam diskmag - For anyone who can remember\\nVectorbobs from Hell etc. I am truly sorry!)  Anywa");
        sb.append("y do either\\nof the two Colins have any idea what I'm going on about?  And do\\nthey really care?\\n\\nI've been messing about a lot in Sam C.  It's a flippin' good\\nprogram, well worth whatever I p");
        sb.append("aid for it.  But when will we\\n                    Letter From John Hancock\\n\\nget those extra libs?  Personally I can't wait.\\n\\n    That's all for now then.  Thanks in advance for the Freds\\na");
        sb.append("nd hope you all enjoyed Alton Towers.  (oh and sorry this is\\nnot on a disk).\\n\\n                             John Hancock\\n\\n\\n\\n\\n\\n\\n\\n\\n\\n\\n\\n\\nCA                   Reply to John H");
        sb.append("ancock\\n\\nAre you surprised that your poor little SAM is feeling a bit\\nrejected?  Not only do you buy a different computer, but you had\\nto buy an Amiga of all things.  It's not even a better com");
        sb.append("puter.\\nWhen will people learn?  Actually, erm, John, friend, I'll sell\\nyou my Spectrum for the bargain price of just \\u00a3960.  Deal?\\n\\nAnyway, of course we care about your problem (the IFF c");
        sb.append("onverter,\\nnot your tendancy to buy rubbish computers).  FRED is the caring\\ncompany.  This won't be the only way to convert pictures, but\\nwhat most people do is to get an IFF-BMP converter for th");
        sb.append("eir\\nother computer and then use the BMP-SAM converter to get it onto\\nyour SAM.  Someone may have written an IFF-SAM converter, but I\\ndon't think it's been on FRED.  If anyone else can help, plea");
        sb.append("se\\nwrite.\\n\\nGlad you're getting to grips with SAM C.  If you've read the\\nnews section, you should know what's going on.\\n\\n\\n                  Letter From Martin R Wilson\\n\\nDear Ed\\n\\nK");
        sb.append("eep  the  Fred  diskzines  coming as at the moment its the only\\ntime   my  neglected  Sam  gets a taste of electricity. Actually\\ntell  a  lie it gets powered up for Sam Supplement as well. I've\\n");
        sb.append("started a 'C' programming course on Wednesday evenings at Yeovil\\nCollege but after using the Sam version and then using Turbo 'C'\\non the Colleges PCs I get a bit confused about syntax. As I also");
        sb.append("\\nhave  a  PC at home I'm concentrating on that to help my course.\\nI'm  not knocking the Sam version its just simpler for me to use\\nthe  same  version  at  home and college for the course. The Sa");
        sb.append("m\\nwill  be  phased in once I get a grip on 'C'. The home version I\\nuse  on  the  PC is not a full compiler it only runs the program\\nusing the IDE.  [Tut, tut, dodgy - CA]\\n\\nThere  seems  to b");
        sb.append("e a lot of hardware projects happening for the\\nSAM  to  improve  sound  and storage but like many what I really\\nwould  appreciate would be an accelerator. What ever happened to\\nthe  Z800  16bit ");
        sb.append(" Z80  chip  that could run at 12mhz and which I\\n                Letter From Martin Robert Wilson\\n\\nthink  was used in the MSX-3 that only sold in Japan? Could that\\nbe  used  as  the  basis  of ");
        sb.append(" an  accelerator?  How  about a 2nd\\nprocessor that plugs into the expansion port?\\n\\nAnother query is how do you improve the output of the scart RGB?\\nI  connect  my  SAM upto a PACE MS1000 satel");
        sb.append("lite receiver to get\\nquadraphonic sound but the picture on my television is too dark.\\nTo  be  honest  if  I  connect  it to my Loewe tv(as seen in Red\\nDwarf) directly its even darker. However wh");
        sb.append("en I connect it to my\\nMicrovitec  Multisync  monitor  theres plenty of brightness. But\\nthats   normally   downstairs  connected  to  my  PC.  Is  there\\nsomething  I can adjust in the PSU to prov");
        sb.append("ide a bit power to the\\nPCB?  Obviously  I  wouldn't  try  this  without  some  informed\\nknowledge.\\n\\nLastly  the  game  many  want converted to the Sam is Elite. How\\nabout it? If you ever con");
        sb.append("vert it to the Sam I'll be the first to\\nbuy  a copy even if its \\u00a325. Why not suggest the project to that\\nbrilliant  programmer  of  Sam  C.  I'm  sure David Braben would\\n                Le");
        sb.append("tter From Martin Randy Wilson\\n\\nconsider  allowing  it.  No  one  reasonable is expecting filled\\npolygon  graphics  or  sampled  sound just an improvement on the\\nspectrum  version  with  more  ");
        sb.append("colour, better spot effects, more\\nships  and  missions.  Hidden  line  vectors are fine. One thing\\nwhich  would  be nice is a network option. Then myself and other\\nSam  users  could organise the");
        sb.append(" occasional meeting for some grand\\nplaying sessions.\\n\\nAnother  game  which  would be a lot simpler to convert would be\\nTime  Pilot.  I've  always been a great fan of this game. As you\\nprobab");
        sb.append("ly  know  its plays in a similar way to asteroids with you\\nstuck  in  the  middle and able to move in any direction. Theres\\nclouds  which  appear above and below you. You never stop moving\\nin  y");
        sb.append("our  chosen direction and each wave is based in a different\\ntime zone with a large enemy at the end of each wave. Its a well\\nold  format but very successful versions have been  done for the\\nC64 ");
        sb.append(" and  BBC.  I  don't  remember  ever  coming across a decent\\nspectrum version.\\n\\n                 Letter From Martin Ruby Wilson\\n\\nYours faithfully\\n\\nMartin R Wilson\\n\\n\\nNow to merge in");
        sb.append(" my sales list.\\n\\n[Martin has a number of things to sell, but I can't take up 300K\\nof disc space listing it all.  Martin has a number of Spectrums\\n(48K, 128K, Plus 2, etc.), spares for all Spec");
        sb.append("trums and Amstrad\\nNC100, Multiprint, an Amstrad CTM644 monitor for use with the\\ncoupe (for \\u00a345 but no speakers) and a number of other speccy\\nrelated things.  Tel 01935 25974 for details. -");
        sb.append(" CA]\\n\\nWANTED.\\n\\nElite for the Spectrum Plus 3(not the cassette versions).\\nElite for the Opus Discovery plus Discovery drive.\\n\\nCA                Reply to Martin Rover Wilson\\n\\nHave I go");
        sb.append("t your middle name right yet?\\n\\nPleased to hear so many people are keen to learn C.  Make sure\\nyou do play with SAM C, we're always after C programs and the\\nnew library that will be coming out ");
        sb.append("in the near future will\\nrevolutionize the language on the SAM.\\n\\nAs you'll know if you saw my show report, an accelerator is\\nbeing worked on, but once again I must stress that it could be a\\nv");
        sb.append("ery long time before we see it.  We don't want to seem as if\\nwe're not supporting it, but many things haven't made it and\\nhave left people disappointed.\\n\\nI had the same problem about darkness ");
        sb.append("on my TV.  The trick is to\\nfind the knob with a picture of a sun thing on it and turn it.\\nAs if by magic, your picture will brighten!\\n\\nOf course, I suppose your problem could be slightly more");
        sb.append("\\ntechnical than that, but Colin hasn't offered an explanation.\\nCA                Reply to Martin Rrrrr Wilson\\n\\nIf anyone else can help, please write in.\\n\\nIn most cases now, a programmer wi");
        sb.append("ll decide what he would like\\nto write and then FRED (or another publisher) will sign a deal\\nfor that game.  It's not very often that the publisher asks\\nsomeone to write a certain game.  I'd like");
        sb.append(" to see a SAM version\\nof Chaos, but no-one ever listens to me...\\n\\nThanks for your suggestions anyway.  If anyone can help Martin\\non any of his points, please write in and we'll include the\\nl");
        sb.append("etter.\\n\\n\\n\\n\\n\\n\\n\\n\\n                     Letter From Doug Young\\n\\nDear Fred\\/Colin,\\n\\nWell hello to another letter from me to you, along with the\\nother 1000 odd Sam readers. Well");
        sb.append(" if this letter gets in the\\nletter section on Fred 63, I will have made it three months in a\\nrow, hope so.\\n\\nFirst off, thanks for the answer to my questions, and in return\\nI have enclosed a ");
        sb.append("disk full of goodies for you, and they belt\\nyou straight in the face in the form of reviews (you said you\\nwanted them last issue, so I've done a few on Sam products, a\\nfew on music items, a few ");
        sb.append("on videos and probably a few more on\\nother things)\\n\\nI will be releasing a complete disk of all of the animated\\ncharacters in Mortal Kombat after Christmas for a couple of\\nquid. The only prob");
        sb.append("lem I'm having is with the amount of frames\\nof animation I can have (I'm using Sam paint initially), with\\nthe four screens I can only have 12-16 movement frames, as the\\n                     Lett");
        sb.append("er From Doug Young\\n\\nsprites are 2\\/3 screen size, which leaves about 7 frames per\\ncharacter, and that's a bit jerky. So what I'm wondering is...\\ndoes the new sprite maker, Samsprite allow you");
        sb.append(" to create\\nanimation of up to 200k?\\n\\nOh as you asked I filled in that questionaire thing and I would\\nalso like to say that I will most certainly be buying a hard\\ndrive.\\n\\nWell hope u like");
        sb.append(" the disk contents, keep up the x-cellent\\neditorial work and I will probably write another letter\\/other\\nstuff for Fred 64, as for now, adios Amigos\\n\\nDouglas Young -M.D.L. Software\\n\\nP.S. ");
        sb.append("not a single plug required this month, makes a change!!!\\n(then again I should have an advert in the advert sction so\\nthat's a bit of a double negative, isn't it?)\\n\\nCA                    Reply ");
        sb.append("To Doug Young\\n\\nWatch it, Doug - if you keep writing letters to FRED, you'll end\\nup as the editor after a while!  Or is that your plan?  Did you\\nrealise that was the way I became editor, and yo");
        sb.append("u've decided to\\noverthrow me?  Is that it?  Or do you have nothing better to do\\nwith your life?\\n\\nJust kidding, Doug.  What would a letters page be without your\\nblatant plugs for your own pro");
        sb.append("grams?  It wouldn't be worth\\nreading.\\n\\nNo plugs???  What do you mean?  What was that huge \\\"I'm\\nreleasing this disc for one quid, keep reading the letters\\nsection for more information\\\" ");
        sb.append("bit about if it wasn't a plug?\\nStill, as you've got an advert, I'll forgive you (again).\\n\\nSAMSprite can handle 255 frames of animation.  The only problem\\nat the moment is that it can only work");
        sb.append(" with 8x8 and 16x16\\nsprites and backgrounds.  Add-ons will follow, but I'm not sure\\nwhen.  See ya' next month.\\n                    Letter From Alan Groves\\n\\nDear Colins,\\n         Just writi");
        sb.append("ng to say how much I enjoyed the latest\\nGloucester show and to congratulate you and all the other\\ncompanies on producing such an excellent display.  It has been\\nthe first show I have attended; D");
        sb.append("evon is a long haul by bus,\\nbut I'm pleased to say it was worthwhile.  It's nice to finally\\nmeet the Fred team, I'm just sorry that Brian was once again\\nunable to attend.\\n\\n    Unfortunately,");
        sb.append(" I won't be attending the Scottish show, but\\nhope to come to the next Gloucester one.\\n\\n    I was writing mainly to enquire when the excellent Retros by\\nMatthew Round will be released.  There h");
        sb.append("ave been a few mentions\\nof an imminent release, but no further news.\\n\\n                        Many thanks,\\n\\n                             A. Groves\\nCA                   Reply To Alan Groves");
        sb.append("\\n\\nGlad you enjoyed the show.  Sorry I couldn't talk to you for\\nlonger, but there were people wanting to give me money, so how\\ncould I refuse?!?\\n\\nRetros IS being worked on.  Now, how do I h");
        sb.append("andle this one?  Do I\\ntell them the truth and say that Matt is a lazy swine, or do I\\nsay that there are a number of technical problems which are\\nbeing worked on?\\n\\nSeriously though, Retros is");
        sb.append(" being written.  I rang Matt the\\nother week and he was programming it at the time.  It will be\\nreleased soon, we promise.\\n\\n\\n\\n\\n\\n\\n\\n                Letter From Nigel Arthur Ackroyd\\n");
        sb.append("\\nDear FRED EDITOR\\n\\n my name is Nigel Ackroyd, you might have heard that name before\\nif you regually read SCUM (but i left last yeear).  I am writing\\nto tell you about some sad news (but with");
        sb.append(" a happyish ending!!!)\\n\\nSCUMitor  (SCUM editor) Kevin Smythe (or Kev for short) (Im Nige\\nfor  short  by  the   way)  has  been  in   a  bit  of   presure\\nrecently.His cousin Eddie thinks SCUM ");
        sb.append("has been going down hill\\ncoz I left and less and less people buy it (but still lots).\\nlast month it got all too much and Kev tried to hang himself\\nuseing the lead on the SAM power suply he got c");
        sb.append("heap from Colin\\nAnderson (is it still you that is the editor or was you\\nsacked????) but luckily the wire broke ++he just broke his ankel\\nwhen he fell. now hes just got back home but is a bit qui");
        sb.append("te coz\\ni think they gave him some tablets., WHen i talked to him about\\nmy new Amiga demo he just dribbelled.\\n\\n\\n                Letter From Nigel Arthur Ackroyd\\n\\nIm asking FREDDERS (if th");
        sb.append("ats wot you call them!!) to send him\\nsome nice cards to cheer him up. Just send them to Mister\\nMcDonald and hell pass them ,on. write something in it about how\\ngreat SCUm is and do it in pername");
        sb.append("nt marker so  then itll still\\nbe O.K. if he dribbels on ita bit.\\n\\nI will be SCUMitor un till Kev gets better so therell be lots\\nmore of my MEGADEMOS to look forward too!!!!\\n\\nyours sincerly");
        sb.append("-Nigel Arthur Ackroyd\\n\\nPPS Eddie sez you should do Windows\\\"95 on SAM then Micrasoft\\ncan buy West Coast  Computers for lots of money!!\\n\\n\\n\\n\\n\\n\\nCA                      Reply to \\\"");
        sb.append("Nige\\\"\\n\\nYou're not very intelligent, are you Nigel?\\n\\nI'm dreadfully sorry to hear about Kev.  So sorry that I stopped\\nlaughing after only ten minutes.  Could you tell him that he can\\npic");
        sb.append("k up another of my dodgy power packs for just \\u00a387 - a whole\\n\\u00a310 less than the last one (but only because I feel so guilty).\\n\\nMy get well card is in the post, and I'm sure a number of");
        sb.append(" caring\\nFRED readers will send Kev one.  Send any to the usual FRED\\naddress.\\n\\nI really look forward to receiving the free FRED copy of SCUM.\\nNo really.  I'm not being sarcastic.  Don't even ");
        sb.append("know what the\\nword means.\\n\\nTell Eddie that he'd really enjoy sticking his head in a fire.\\n\\n\\n\\nDY                      Review Of Booty\\n\\nThe  following  text   contains   a   review   o");
        sb.append("f   BOOTY   from\\nJupitor\\/Phoenix software and has been written by D Young ( M.D.L\\nSoftware ). This is a perfectly non biased review of the game, I\\nam saying this as I happen to think that this");
        sb.append(" type  of  game  is\\nthe best type of game that you can play ( a.k.a a platform\\ngame ), well here it is.................\\n\\n......Booty -Jupitor\\/Phoenix software.....Price 5.00........\\n\\nYou");
        sb.append(" are Jim the cabin boy and you are on a haunted pirate  ship,\\nyour task is to travel around the 30 decks of the s\",\"legalStatus\":\"allowed\",\"releasesIds\":[349659],\"imagesUrls\":[\"https:\\/");
        sb.append("\\/zxart.ee\\/image\\/type:prodImage\\/id:577977\\/filename:simc0135.webp\"],\"importIds\":{\"worldofsam\":\"fred-63\",\"zxdb\":\"35072\"},\"votes\":3.91,\"votesAmount\":1,\"connectedCategoriesIds\":[");
        sb.append("92179],\"categoriesString\":\"Press\\/Electronic Magazine\"}]},\"responseStatus\":\"success\"}");
        PROD_SEARCH = sb.toString();
    }

    /**
     * review/zxart/releases-headoverheels.json - export:zxRelease,
     * filter:zxProdId=100938 - trimmed to the first 3 of its 19 releases (ids
     * 100941 {@code HeadOverHeels.tzx.zip}, 100942 {@code
     * HeadOverHeels.tap.zip}, 100943 {@code
     * HeadOverHeels(ErbeSoftwareS.A.).tzx.zip}), whole releases dropped
     * rather than fields. {@code totalAmount} stays 19.
     *
     * <b>Pairs with {@link #PROD_SEARCH}, and only with it</b> - both
     * describe entry 100938, so a candidate this app confirms against this
     * release list is confirmed against its own entry's files. See {@code
     * Zxart}'s own class javadoc: an earlier draft of the plan this fixture
     * comes from paired {@code PROD_SEARCH} with Licence to Kill's releases
     * instead and asserted a handle of 92668 - a canned queue does not check
     * that a search reply and a release reply describe the same entry, so
     * that would have passed while confirming a candidate against another
     * entry's files entirely.
     *
     * {@code b95d7490a4258bfbd6782af62a862602} is release 100941's own
     * {@code HeadOverHeels.tzx.zip} archive; {@code
     * 82bb33587530d337323ef3cd4456d4c4} is {@code Head Over Heels.tzx}
     * inside it - the pair {@code ZxartTest} confirms against for "the
     * archive" and "a file inside the archive" respectively.
     */
    public static final String RELEASES_HEAD_OVER_HEELS =
            "{\"totalAmount\":19,\"start\":0,\"limit\":50,\"responseData\":{\"zxRelease\":[{\"id\":100941,\""
            + "title\":\"Head over Heels\",\"dateCreated\":1479491346,\"dateModified\":1786309961,\"file\":\"h"
            + "ttps:\\/\\/zxart.ee\\/releasefile\\/id:100941\\/HeadOverHeels.tzx.zip\",\"fileName\":\"HeadOver"
            + "Heels.tzx.zip\",\"year\":1987,\"publishersIds\":[176471],\"hardwareRequired\":[\"cursor\",\"kem"
            + "pston\",\"int2_2\"],\"releaseType\":\"original\",\"releaseFormat\":[\"tzx\"],\"inlays\":[\"http"
            + "s:\\/\\/zxart.ee\\/release\\/id:210835\\/mode:download\\/filename:HeadOverHeels.jpg\",\"https:"
            + "\\/\\/zxart.ee\\/release\\/id:556464\\/mode:download\\/filename:HeadOverHeels_Media.jpg\",\"htt"
            + "ps:\\/\\/zxart.ee\\/release\\/id:556465\\/mode:download\\/filename:HeadOverHeels_Media_2.jpg\"]"
            + ",\"ads\":[\"https:\\/\\/zxart.ee\\/release\\/id:210839\\/mode:download\\/filename:HeadOverHeels"
            + ".jpg\"],\"instructions\":[\"https:\\/\\/zxart.ee\\/release\\/id:210836\\/mode:download\\/filena"
            + "me:HeadOverHeels.pdf\",\"https:\\/\\/zxart.ee\\/release\\/id:210837\\/mode:download\\/filename:"
            + "HeadOverHeels.txt\",\"https:\\/\\/zxart.ee\\/release\\/id:210838\\/mode:download\\/filename:Hea"
            + "dOverHeels_2.txt\",\"https:\\/\\/zxart.ee\\/release\\/id:556466\\/mode:download\\/filename:Head"
            + "OverHeels(EN).pdf\"],\"releaseStructure\":[{\"id\":50646,\"md5\":\"b95d7490a4258bfbd6782af62a86"
            + "2602\",\"parentId\":0,\"fileName\":\"HeadOverHeels.tzx.zip\",\"size\":38570,\"elementId\":10094"
            + "1,\"type\":\"zip\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false,\"items"
            + "\":[{\"id\":50647,\"md5\":\"82bb33587530d337323ef3cd4456d4c4\",\"parentId\":50646,\"fileName\":"
            + "\"Head Over Heels.tzx\",\"size\":50595,\"elementId\":100941,\"type\":\"tzx\",\"encoding\":\"non"
            + "e\",\"internalType\":\"binary\",\"viewable\":false,\"items\":[{\"id\":50648,\"md5\":\"cc589adda"
            + "9bd4134f02e1bfacd3c38a9\",\"parentId\":50647,\"fileName\":\"HEAD1.B\",\"size\":205,\"elementId"
            + "\":100941,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"zx_basic\",\"viewable\":tr"
            + "ue},{\"id\":50649,\"md5\":\"d6a6fbac59355a5c4cd5ba6202af647e\",\"parentId\":50647,\"fileName\":"
            + "\"over.B\",\"size\":1770,\"elementId\":100941,\"type\":\"file\",\"encoding\":\"none\",\"interna"
            + "lType\":\"zx_basic\",\"viewable\":true},{\"id\":50650,\"md5\":\"785d512be4316d578e6650613b45e93"
            + "4\",\"parentId\":50647,\"fileName\":\"data01\",\"size\":1,\"elementId\":100941,\"type\":\"file"
            + "\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":50651,\"md5\":"
            + "\"bf0832914fcbe5972c0e172ef54e6bc7\",\"parentId\":50647,\"fileName\":\"data02\",\"size\":16,\"e"
            + "lementId\":100941,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewabl"
            + "e\":false},{\"id\":50652,\"md5\":\"785d512be4316d578e6650613b45e934\",\"parentId\":50647,\"file"
            + "Name\":\"data03\",\"size\":1,\"elementId\":100941,\"type\":\"file\",\"encoding\":\"none\",\"int"
            + "ernalType\":\"binary\",\"viewable\":false},{\"id\":50653,\"md5\":\"07b6af726aaf17f8d9c5dd900cd0"
            + "7252\",\"parentId\":50647,\"fileName\":\"data04\",\"size\":6910,\"elementId\":100941,\"type\":"
            + "\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":50654,\""
            + "md5\":\"89e74e640b8c46257a29de0616794d5d\",\"parentId\":50647,\"fileName\":\"data05\",\"size\":"
            + "1,\"elementId\":100941,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"vi"
            + "ewable\":false},{\"id\":50655,\"md5\":\"2c12e4eec537076dfb529a503b93b4ea\",\"parentId\":50647,"
            + "\"fileName\":\"data06\",\"size\":9006,\"elementId\":100941,\"type\":\"file\",\"encoding\":\"non"
            + "e\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":50656,\"md5\":\"89e74e640b8c46257a2"
            + "9de0616794d5d\",\"parentId\":50647,\"fileName\":\"data07\",\"size\":1,\"elementId\":100941,\"ty"
            + "pe\":\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":506"
            + "57,\"md5\":\"9b1b69f1ed4c890f51c5e6528d5dae79\",\"parentId\":50647,\"fileName\":\"data08\",\"si"
            + "ze\":9176,\"elementId\":100941,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"binar"
            + "y\",\"viewable\":false},{\"id\":50658,\"md5\":\"89e74e640b8c46257a29de0616794d5d\",\"parentId\""
            + ":50647,\"fileName\":\"data09\",\"size\":1,\"elementId\":100941,\"type\":\"file\",\"encoding\":"
            + "\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":50659,\"md5\":\"cce1c96015194e"
            + "319c932350c33705f5\",\"parentId\":50647,\"fileName\":\"data10\",\"size\":2,\"elementId\":100941"
            + ",\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id"
            + "\":50660,\"md5\":\"0cec9409e3f0f3b4d63d760750f92f57\",\"parentId\":50647,\"fileName\":\"data11"
            + "\",\"size\":2,\"elementId\":100941,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"b"
            + "inary\",\"viewable\":false},{\"id\":50661,\"md5\":\"89e74e640b8c46257a29de0616794d5d\",\"parent"
            + "Id\":50647,\"fileName\":\"data12\",\"size\":1,\"elementId\":100941,\"type\":\"file\",\"encoding"
            + "\":\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":50662,\"md5\":\"ca1f6bb0247"
            + "c4aae815081b0ef38007f\",\"parentId\":50647,\"fileName\":\"data13\",\"size\":12116,\"elementId\""
            + ":100941,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false}"
            + ",{\"id\":50663,\"md5\":\"89e74e640b8c46257a29de0616794d5d\",\"parentId\":50647,\"fileName\":\"d"
            + "ata14\",\"size\":1,\"elementId\":100941,\"type\":\"file\",\"encoding\":\"none\",\"internalType"
            + "\":\"binary\",\"viewable\":false},{\"id\":50664,\"md5\":\"5ec44400ea016bb1ce016c18c635b370\",\""
            + "parentId\":50647,\"fileName\":\"data15\",\"size\":24,\"elementId\":100941,\"type\":\"file\",\"e"
            + "ncoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":50665,\"md5\":\"89e7"
            + "4e640b8c46257a29de0616794d5d\",\"parentId\":50647,\"fileName\":\"data16\",\"size\":1,\"elementI"
            + "d\":100941,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":fal"
            + "se},{\"id\":50666,\"md5\":\"42cc9f51092e0bb38785f4d86cf206ba\",\"parentId\":50647,\"fileName\":"
            + "\"data17\",\"size\":1708,\"elementId\":100941,\"type\":\"file\",\"encoding\":\"none\",\"interna"
            + "lType\":\"binary\",\"viewable\":false},{\"id\":50667,\"md5\":\"89e74e640b8c46257a29de0616794d5d"
            + "\",\"parentId\":50647,\"fileName\":\"data18\",\"size\":1,\"elementId\":100941,\"type\":\"file\""
            + ",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":50668,\"md5\":\""
            + "3d2b7ff92daa5d9bdc662fcaef849393\",\"parentId\":50647,\"fileName\":\"data19\",\"size\":7998,\"e"
            + "lementId\":100941,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewabl"
            + "e\":false},{\"id\":50669,\"md5\":\"89e74e640b8c46257a29de0616794d5d\",\"parentId\":50647,\"file"
            + "Name\":\"data20\",\"size\":1,\"elementId\":100941,\"type\":\"file\",\"encoding\":\"none\",\"int"
            + "ernalType\":\"binary\",\"viewable\":false},{\"id\":50670,\"md5\":\"4e31f1b9214a16611c7b95221158"
            + "b747\",\"parentId\":50647,\"fileName\":\"data21\",\"size\":899,\"elementId\":100941,\"type\":\""
            + "file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false}]}]}],\"prodId\":10"
            + "0938},{\"id\":100942,\"title\":\"Head over Heels\",\"dateCreated\":1479491346,\"dateModified\":"
            + "1786298515,\"file\":\"https:\\/\\/zxart.ee\\/releasefile\\/id:100942\\/HeadOverHeels.tap.zip\","
            + "\"fileName\":\"HeadOverHeels.tap.zip\",\"year\":1987,\"publishersIds\":[176471],\"hardwareRequi"
            + "red\":[\"zx48\",\"zx128\",\"cursor\",\"kempston\",\"int2_2\"],\"releaseType\":\"original\",\"re"
            + "leaseFormat\":[\"tap\"],\"releaseStructure\":[{\"id\":50671,\"md5\":\"98dbdfdc73347c8498ab9f814"
            + "00ac828\",\"parentId\":0,\"fileName\":\"HeadOverHeels.tap.zip\",\"size\":37132,\"elementId\":10"
            + "0942,\"type\":\"zip\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false,\"it"
            + "ems\":[{\"id\":50672,\"md5\":\"a550220f26615e452d2e27384801cd18\",\"parentId\":50671,\"fileName"
            + "\":\"Head Over Heels .tap\",\"size\":49966,\"elementId\":100942,\"type\":\"tap\",\"encoding\":"
            + "\"none\",\"internalType\":\"binary\",\"viewable\":false,\"items\":[{\"id\":50673,\"md5\":\"98c4"
            + "774fdf2bd8fdb62e7294c981a16b\",\"parentId\":50672,\"fileName\":\"HEAD1.B\",\"size\":226,\"eleme"
            + "ntId\":100942,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"zx_basic\",\"viewable"
            + "\":true},{\"id\":50674,\"md5\":\"77b3d899fe93efd1780dc04b238d4271\",\"parentId\":50672,\"fileNa"
            + "me\":\"over.C\",\"size\":1770,\"elementId\":100942,\"type\":\"file\",\"encoding\":\"none\",\"in"
            + "ternalType\":\"binary\",\"viewable\":false},{\"id\":50675,\"md5\":\"bc0a548c5cbe604c530f2aff52c"
            + "52f03\",\"parentId\":50672,\"fileName\":\"data01\",\"size\":18,\"elementId\":100942,\"type\":\""
            + "file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":50676,\"md"
            + "5\":\"9ce8b2d53cd49ed8341a39a8de6c2e1c\",\"parentId\":50672,\"fileName\":\"data02\",\"size\":69"
            + "12,\"elementId\":100942,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"zx_image_sta"
            + "ndard\",\"viewable\":true},{\"id\":50677,\"md5\":\"32199a7987e1fec4bee8a0329ca58dca\",\"parentI"
            + "d\":50672,\"fileName\":\"data03\",\"size\":9008,\"elementId\":100942,\"type\":\"file\",\"encodi"
            + "ng\":\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":50678,\"md5\":\"845211dd7"
            + "c605c9b68b0ec603dc84961\",\"parentId\":50672,\"fileName\":\"data04\",\"size\":9178,\"elementId"
            + "\":100942,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":fals"
            + "e},{\"id\":50679,\"md5\":\"428fdd3ef564c2b03f837ccb77d73f2e\",\"parentId\":50672,\"fileName\":"
            + "\"data05\",\"size\":2,\"elementId\":100942,\"type\":\"file\",\"encoding\":\"none\",\"internalTy"
            + "pe\":\"binary\",\"viewable\":false},{\"id\":50680,\"md5\":\"9d57823531e7bae9beb589eec4e2db97\","
            + "\"parentId\":50672,\"fileName\":\"data06\",\"size\":4,\"elementId\":100942,\"type\":\"file\",\""
            + "encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":50681,\"md5\":\"5b4"
            + "cdd83251e58f90f8880df663f59ca\",\"parentId\":50672,\"fileName\":\"data07\",\"size\":12118,\"ele"
            + "mentId\":100942,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable"
            + "\":false},{\"id\":50682,\"md5\":\"f0111beb28f0619a8d0112ad173c3791\",\"parentId\":50672,\"fileN"
            + "ame\":\"data08\",\"size\":26,\"elementId\":100942,\"type\":\"file\",\"encoding\":\"none\",\"int"
            + "ernalType\":\"binary\",\"viewable\":false},{\"id\":50683,\"md5\":\"e39d430323eb5c6fe071a485efa8"
            + "e27a\",\"parentId\":50672,\"fileName\":\"data09\",\"size\":1710,\"elementId\":100942,\"type\":"
            + "\"file\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false},{\"id\":50684,\""
            + "md5\":\"28ffbda9f85afd130d90f2bb31d8db79\",\"parentId\":50672,\"fileName\":\"data10\",\"size\":"
            + "8000,\"elementId\":100942,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"binary\","
            + "\"viewable\":false},{\"id\":50685,\"md5\":\"20591f0994e977f617beb0e871a0ac37\",\"parentId\":506"
            + "72,\"fileName\":\"data11\",\"size\":900,\"elementId\":100942,\"type\":\"file\",\"encoding\":\"n"
            + "one\",\"internalType\":\"binary\",\"viewable\":false}]}]}],\"prodId\":100938},{\"id\":100943,\""
            + "title\":\"Head over Heels\",\"dateCreated\":1479491346,\"dateModified\":1786298515,\"file\":\"h"
            + "ttps:\\/\\/zxart.ee\\/releasefile\\/id:100943\\/HeadOverHeels(ErbeSoftwareS.A.).tzx.zip\",\"fil"
            + "eName\":\"HeadOverHeels(ErbeSoftwareS.A.).tzx.zip\",\"year\":1987,\"publishersIds\":[184186],\""
            + "hardwareRequired\":[\"zx48\",\"zx128\",\"cursor\",\"kempston\",\"int2_2\"],\"releaseType\":\"re"
            + "release\",\"releaseFormat\":[\"tzx\"],\"inlays\":[\"https:\\/\\/zxart.ee\\/release\\/id:210840"
            + "\\/mode:download\\/filename:HeadOverHeels(ErbeSoftwareS.A.).jpg\",\"https:\\/\\/zxart.ee\\/rele"
            + "ase\\/id:381180\\/mode:download\\/filename:HeadOverHeels(ErbeSoftwareSA).jpg\"],\"instructions"
            + "\":[\"https:\\/\\/zxart.ee\\/release\\/id:210841\\/mode:download\\/filename:HeadOverHeels(ErbeS"
            + "oftwareS.A.).pdf\"],\"releaseStructure\":[{\"id\":50686,\"md5\":\"77c61b1993bb49dfc1aa5151abd9c"
            + "9e1\",\"parentId\":0,\"fileName\":\"HeadOverHeels(ErbeSoftwareS.A.).tzx.zip\",\"size\":37158,\""
            + "elementId\":100943,\"type\":\"zip\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewabl"
            + "e\":false,\"items\":[{\"id\":50687,\"md5\":\"5fd7c57bafd42a2c47f30761ab9138be\",\"parentId\":50"
            + "686,\"fileName\":\"Head Over Heels (Erbe).tzx\",\"size\":49324,\"elementId\":100943,\"type\":\""
            + "tzx\",\"encoding\":\"none\",\"internalType\":\"binary\",\"viewable\":false,\"items\":[{\"id\":5"
            + "0688,\"md5\":\"dd693867ee7e4c0d239f86b67fd73348\",\"parentId\":50687,\"fileName\":\"HEAD.B\",\""
            + "size\":1144,\"elementId\":100943,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"zx_"
            + "basic\",\"viewable\":true},{\"id\":50689,\"md5\":\"a72334f06d2b9a0fcac30edf46ed85f2\",\"parentI"
            + "d\":50687,\"fileName\":\"cargador.B\",\"size\":87,\"elementId\":100943,\"type\":\"file\",\"enco"
            + "ding\":\"none\",\"internalType\":\"zx_basic\",\"viewable\":true},{\"id\":50690,\"md5\":\"9ce8b2"
            + "d53cd49ed8341a39a8de6c2e1c\",\"parentId\":50687,\"fileName\":\"SCR.C\",\"size\":6912,\"elementI"
            + "d\":100943,\"type\":\"file\",\"encoding\":\"none\",\"internalType\":\"zx_image_standard\",\"vie"
            + "wable\":true},{\"id\":50691,\"md5\":\"961445c83116802d0275c8188e1dd5ff\",\"parentId\":50687,\"f"
            + "ileName\":\"CM.C\",\"size\":40876,\"elementId\":100943,\"type\":\"file\",\"encoding\":\"none\","
            + "\"internalType\":\"binary\",\"viewable\":false}]}]}],\"prodId\":100938}]},\"responseStatus\":\""
            + "success\"}";

    /**
     * review/zxart/ord-search-votes.json - export:zxProd,
     * filter:zxProdSearch=head, order:votes,desc, limit:5 - a votes-ordered
     * page of the same search {@code af-prodsearch.json}/{@link #PROD_SEARCH}
     * draws from, {@code totalAmount} 271 either way (the same "head alone is
     * 271" {@code ZxartTest} cites for why confirmation has to be bounded).
     *
     * <b>Four of the five rows, not five.</b> The fifth, id 357800 "Void",
     * carries a 16 KB description and is dropped whole - the fixture rule
     * here is trimming by row, never by field, and a huge unused field is
     * still a reason to drop the whole row rather than gut it. Four is what
     * the bound this fixture exists to prove actually needs: {@code
     * ZxartTest.onlyTheFirstFewCandidatesAreConfirmed} has to see a fourth
     * candidate the code declines to confirm, which {@link #PROD_SEARCH}'s
     * own three rows cannot demonstrate - three candidates confirmed out of
     * three is indistinguishable from "there were only three".
     */
    public static final String PROD_SEARCH_MANY =
            "{\"totalAmount\":271,\"start\":0,\"limit\":5,\"responseData\":{\"zxProd\":[{\"id\":587748,\"tit"
            + "le\":\"GronGift25\",\"dateCreated\":1766672052,\"dateModified\":1786312024,\"year\":2025,\"yout"
            + "ubeId\":\"1uw_yvexgnc\",\"description\":\"  ______  __ _________ __ _______________   _\\n  \\"
            + "\\__  \\/\\\\\\/ \\/\\/ __\\/ ___\\/\\/ \\/\\/ __\\/ _  \\/ ___\\/ |_| |\\n    \\/ \\/\\\\  \\/"
            + "\\/ \\/__| __\\\\  \\/\\/ \\/  | \\/ \\/| __\\\\| | | |\\n   \\/ \\/_\\/  \\\\\\\\ \\\\| | | \\"
            + "/  \\\\\\\\ \\\\__| \\\\ \\\\| |__|  _  |\\n  \\/_____\\/\\\\_\\\\\\\\___|_|\\/_\\/\\\\_\\\\\\"
            + "\\___\\\\_|\\\\_\\\\____\\\\_\\/ \\\\_|\\n     \\n                   GronGift25\\n   ---===  Ha"
            + "ndcrafted in three weeks ===---\\n             for Grongy's birthday \\n                   2025"
            + "\\/12\\/24\\n                                           \\nRELEASE NOTES:\\n    \\n   Credits:"
            + "\\n\\u0412\\u0441\\u044f \\u0433\\u0440\\u0430\\u0444\\u0438\\u043a\\u0430 \\u2014 * Grongy *\\"
            + "n00. \\u0414\\u0432\\u0438\\u0436\\u043e\\u043a \\u2014 RCL\\n01. \\u041f\\u043e\\u0437\\u0434"
            + "\\u0440\\u0430\\u0432\\u043b\\u044f\\u043b\\u043a\\u0430 \\\"\\u0422\\u0435\\u043b\\u0435\\u043"
            + "3\\u0440\\u0430\\u043c\\\" \\u2014 RCL, UriS, LeMIC\\n02. C\\u0435\\u043c\\u0438\\u0441\\u0435"
            + "\\u0433\\u043c\\u0435\\u043d\\u0442\\u043d\\u044b\\u0439 \\u0434\\u0438\\u0441\\u043f\\u043b\\u"
            + "0435\\u0439 \\u2014 UriS\\n03. \\u0413\\u043e\\u0440\\u043e\\u0434, \\u043e\\u0442\\u0440\\u043"
            + "0\\u0436\\u0435\\u043d\\u0438\\u0435 \\u2014 UriS\\n04. \\u041a\\u043e\\u0448\\u043a\\u0430 \\u"
            + "043d\\u0430 \\u0437\\u0430\\u0431\\u043e\\u0440\\u0435 \\u2014 nodeus, RCL, Art-top\\n05. \\u04"
            + "11\\u043e\\u043b\\u0442 \\u2014 Art-top\\n06. \\u0414\\u043e\\u0436\\u0434\\u044c, \\u0433\\u04"
            + "40\\u043e\\u0437\\u0430 \\u2014 UriS, RCL\\n07. \\u0421\\u043e\\u0434\\u0430 \\u2014 moroz1999,"
            + " nodeus, diver, wbcbz7, RCL\\n08. Soda legend \\u2014 nodeus\\n09. \\u0411\\u044d\\u0442\\u043c"
            + "\\u0435\\u043d flash \\u2014 RCL\\n10. \\u0411\\u0443\\u043a\\u0432\\u044b GRONGY \\u2014 Art-t"
            + "op\\n11. \\u041f\\u0430\\u0434\\u0430\\u044e\\u0449\\u0438\\u0435 \\u043d\\u0438\\u0448\\u0442"
            + "\\u044f\\u043a\\u0438 \\u2014 UriS\\n12,13,14 \\u041f\\u043b\\u0430\\u0437\\u043c\\u0430, \\u04"
            + "3e\\u0433\\u043e\\u043d\\u044c, \\u043f\\u0430\\u0440\\u0442\\u0438\\u043a\\u043b\\u044b \\u201"
            + "4 moroz1999\\n15. \\u0417\\u043e\\u043c\\u0431\\u0438 \\u2014 UriS, RCL, LeMIC\\n16. \\u0423\\u"
            + "0412\\u0411-76 \\u2014 Art-top, UriS\\n17. \\u0414\\u043e\\u0441\\u043a\\u0430 \\u043e\\u0431\\"
            + "u044a\\u044f\\u0432\\u043b\\u0435\\u043d\\u0438\\u0439 \\u2014 UriS, RCL, sq\\n18. \\u0414\\u04"
            + "38\\u0441\\u043a\\u043e \\u043f\\u0443\\u0437\\u044b\\u0440\\u0438 \\u2014 Gogin\\n19. \\u041a"
            + "\\u043e\\u0442 \\u0438 \\u0418\\u0440\\u0430 \\u2014 Joe Vondayl, Gogin\\n\\n\\u0421\\u0430\\u0"
            + "443\\u043d\\u0434\\u0442\\u0440\\u0435\\u043a: n1k-o\\n(Radiohead - \\\"Creep\\\" cover, extend"
            + "ed version)\\n\\n\\u0421\\u0431\\u043e\\u0440\\u043a\\u0430 \\u0438 \\u0440\\u0435\\u043b\\u043"
            + "8\\u0437: RCL, Gogin\\nASCII logo in .diz by diver\\n\\n\\n\\n   Credits:\\nAll gfx by * Grongy"
            + " *\\n00. Engine \\u2014 RCL\\n01. Telegram Wishes \\u2014 RCL, UriS, LeMIC\\n02. 7 segment disp"
            + "lay \\u2014 UriS\\n03. City and reflection \\u2014 UriS\\n04. The Cat on the fence \\u2014 node"
            + "us, RCL, Art-top\\n05. Bolt \\u2014 Art-top\\n06. Thunderstorm \\u2014 UriS, RCL\\n07. Soda \\u"
            + "2014 moroz1999, nodeus, wbcbz7, diver, RCL\\n08. Soda legend \\u2014 nodeus\\n09. Batman flash "
            + "\\u2014 RCL\\n10. GRONGY Letters \\u2014 Art-top\\n11. Falling sweets \\u2014 UriS\\n12,13,14 P"
            + "lasma, Fire, particles \\u2014 moroz1999\\n15. Zombies \\u2014 UriS, RCL, LeMIC\\n16. UVB-76 \\"
            + "u2014 Art-top, UriS\\n17. Bulletin board\\u2014 UriS, RCL, sq\\n18. Disco bubbles \\u2014 Gogin"
            + "\\n19. Cat and Iris \\u2014 Joe Vondayl, Gogin\\n\\n\\nSoundtrack by n1k-o\\n(Radiohead - \\\"C"
            + "reep\\\" cover, extended version)\\n\\nLinking and release: RCL, Gogin\\nASCII logo in .diz by "
            + "diver\\n\\n\\n\\n\\u0421 \\u0434\\u043d\\u0435\\u043c \\u0440\\u043e\\u0436\\u0434\\u0435\\u043"
            + "d\\u0438\\u044f, Grongy!\\n                       Happy birthday to Grongy!\\n                 "
            + "                     \\n                        \\n\",\"legalStatus\":\"allowed\",\"groupsIds\""
            + ":[587791],\"releasesIds\":[587751,587930],\"imagesUrls\":[\"https:\\/\\/zxart.ee\\/screenshot\\"
            + "/id:597806\\/ezgif.com-optimize.gif\",\"https:\\/\\/zxart.ee\\/zximages\\/id=587755;pal=srgb;ty"
            + "pe=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=587756;pal=srgb;type=standard;zoom="
            + "1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=587758;pal=srgb;type=standard;zoom=1\",\"https:\\/\\"
            + "/zxart.ee\\/zximages\\/id=587761;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zxima"
            + "ges\\/id=587764;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=587766;p"
            + "al=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=587767;pal=srgb;type=stan"
            + "dard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=587771;pal=srgb;type=standard;zoom=1\",\"h"
            + "ttps:\\/\\/zxart.ee\\/zximages\\/id=587773;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart."
            + "ee\\/zximages\\/id=587775;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/i"
            + "d=587776;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=587779;pal=srgb"
            + ";type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=587782;pal=srgb;type=standard;zo"
            + "om=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=587785;pal=srgb;type=standard;zoom=1\",\"https:\\"
            + "/\\/zxart.ee\\/zximages\\/id=587790;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zx"
            + "images\\/id=587787;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=58778"
            + "8;pal=srgb;type=standard;zoom=1\"],\"authorsInfo\":[{\"id\":83031,\"authorId\":2254,\"startDate"
            + "\":\"\",\"endDate\":\"\",\"roles\":[\"code\",\"graphics\"],\"type\":\"prod\"},{\"id\":83035,\"a"
            + "uthorId\":2275,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"code\"],\"type\":\"prod\"},{\"i"
            + "d\":83039,\"authorId\":2329,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"ascii\"],\"type\":"
            + "\"prod\"},{\"id\":83032,\"authorId\":3417,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"code"
            + "\"],\"type\":\"prod\"},{\"id\":83029,\"authorId\":7123,\"startDate\":\"\",\"endDate\":\"\",\"ro"
            + "les\":[\"code\"],\"type\":\"prod\"},{\"id\":83033,\"authorId\":8319,\"startDate\":\"\",\"endDat"
            + "e\":\"\",\"roles\":[\"graphics\",\"ascii\"],\"type\":\"prod\"},{\"id\":83030,\"authorId\":8764,"
            + "\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"code\"],\"type\":\"prod\"},{\"id\":83027,\"aut"
            + "horId\":28216,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"code\",\"release\",\"graphics\"]"
            + ",\"type\":\"prod\"},{\"id\":83037,\"authorId\":86189,\"startDate\":\"\",\"endDate\":\"\",\"role"
            + "s\":[\"music\"],\"type\":\"prod\"},{\"id\":83036,\"authorId\":193090,\"startDate\":\"\",\"endDa"
            + "te\":\"\",\"roles\":[\"graphics\"],\"type\":\"prod\"},{\"id\":83034,\"authorId\":308697,\"start"
            + "Date\":\"\",\"endDate\":\"\",\"roles\":[\"3dmodels\"],\"type\":\"prod\"},{\"id\":83025,\"author"
            + "Id\":357252,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"graphics\"],\"type\":\"prod\"},{\""
            + "id\":83028,\"authorId\":357596,\"startDate\":\"\",\"endDate\":\"\",\"roles\":[\"code\",\"graphi"
            + "cs\",\"font\"],\"type\":\"prod\"}],\"importIds\":{\"pouet\":\"105458\"},\"votes\":4.5,\"votesAm"
            + "ount\":14,\"connectedCategoriesIds\":[92172],\"categoriesString\":\"Demoscene\\/Gift\"},{\"id\""
            + ":590237,\"title\":\"Sweet Fightin' +2\",\"dateCreated\":1772871838,\"dateModified\":1786312026,"
            + "\"language\":[\"en\"],\"year\":2025,\"youtubeId\":\"SfOHoNo-nzg\",\"description\":\"<pre>Choose"
            + " from 12 legendary fighters and battle your way across the world in a series of one-on-one, bes"
            + "t-of-three fights to achieve the glory and title of Sweet Fightin' Champion... or go head to he"
            + "ad with your friends in 2-player mode!\\n\\nUnofficial homage for the ZX Spectrum 128K, celebra"
            + "ting 35 years of the two-player fighting game which shaped the genre!\\n\\n\\u26a0\\ufe0f Pleas"
            + "e note that this game requires either an original ZX Spectrum 128K computer or suitable emulato"
            + "r to run.<\\/pre>\",\"legalStatus\":\"donationware\",\"publishersIds\":[362186],\"releasesIds\""
            + ":[590239,590675,596821],\"imagesUrls\":[\"https:\\/\\/zxart.ee\\/zximages\\/id=590244;pal=srgb;"
            + "type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/screenshot\\/id:590676\\/SwF2.gif\",\"https:\\/"
            + "\\/zxart.ee\\/zximages\\/id=590245;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zxi"
            + "mages\\/id=590242;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=590240"
            + ";pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=590241;pal=srgb;type=st"
            + "andard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=590243;pal=srgb;type=standard;zoom=1\"],"
            + "\"authorsInfo\":[{\"id\":83209,\"authorId\":362186,\"startDate\":\"\",\"endDate\":\"\",\"roles"
            + "\":[\"code\",\"release\"],\"type\":\"prod\"}],\"votes\":4.25,\"votesAmount\":6,\"externalLink\""
            + ":\"https:\\/\\/zxpresh.itch.io\\/sweet-fightin-plus-2\",\"connectedCategoriesIds\":[523409],\"c"
            + "ategoriesString\":\"Games\\/Action\\/Fighting Games\\/One-on-One Fighters\"},{\"id\":538581,\"t"
            + "itle\":\"Marlow: In Apocalyptic Acid World\",\"dateCreated\":1728235231,\"dateModified\":174541"
            + "1359,\"language\":[\"en\"],\"year\":2024,\"youtubeId\":\"up888gP0nVU\",\"description\":\"Marlow"
            + " in Apocalyptic Acid World is a platform game for ZX Spectrum 128k, inspired by &quot;The Great"
            + " Giana Sisters.&quot; What? You thought the inspiration came from a certain mustached plumber? "
            + "No way\\u2026\\r\\n\\r\\nNavigate this vast apocalyptic world by jumping, stomping on enemies, "
            + "collecting crystals, throwing Molotov cocktail, and breaking blocks with your head across 4 wor"
            + "lds, in a total of 17 levels and 5 Boss battles.\\r\\n\\r\\nYou can download the Beta Demo for "
            + "free to test the game before you buy the full package.\\r\\n\\r\\nMarlow Key Features:\\r\\n\\r"
            + "\\n\\u2022 5 Bosses and 17 stages Spread through 2 Episodes (2 programs)\\r\\n\\u2022 7 catch s"
            + "oundtracks (more info on readme.txt)\\r\\n\\u2022 10 Full screen illustrations (loading, title,"
            + " intro cutscenes and endings)\\r\\n\\u2022 Fluid gameplay mechanics in a style rarely seen on t"
            + "he ZX Spectrum\\r\\n\\r\\n\\r\\nThis project was only possible thanks to MPAGD Gen2, modified v"
            + "ersion of the engine by Xavisan, that expands memory usage beyond the 48kb, without which the c"
            + "omplex title screens, introduction, and ending, as well as the large number of music tracks, sc"
            + "reens, and code, would not have been possible.\\r\\n\\r\\nMore info on the Readme.txt file that"
            + " came along the game package.\\r\\n\\r\\nHave fun!\",\"legalStatus\":\"insales\",\"publishersId"
            + "s\":[409518],\"releasesIds\":[538583,538602,546445],\"imagesUrls\":[\"https:\\/\\/zxart.ee\\/zx"
            + "images\\/id=538584;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=53858"
            + "8;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=538590;pal=srgb;type=s"
            + "tandard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=538591;pal=srgb;type=standard;zoom=1\","
            + "\"https:\\/\\/zxart.ee\\/zximages\\/id=538585;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxa"
            + "rt.ee\\/zximages\\/id=538586;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages"
            + "\\/id=538587;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=538592;pal="
            + "srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=538593;pal=srgb;type=standar"
            + "d;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=538594;pal=srgb;type=standard;zoom=1\",\"http"
            + "s:\\/\\/zxart.ee\\/zximages\\/id=538595;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee"
            + "\\/zximages\\/id=538597;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id="
            + "538599;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=546443;pal=srgb;t"
            + "ype=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/zximages\\/id=546444;pal=srgb;type=standard;zoom"
            + "=1\"],\"authorsInfo\":[{\"id\":78313,\"authorId\":409518,\"startDate\":\"\",\"endDate\":\"\",\""
            + "roles\":[\"code\",\"music\",\"graphics\",\"loading_screen\"],\"type\":\"prod\"}],\"importIds\":"
            + "{\"zxdb\":\"43703\"},\"votes\":4.2,\"votesAmount\":5,\"externalLink\":\"https:\\/\\/amaweks.itc"
            + "h.io\\/marlow-zx\",\"connectedCategoriesIds\":[523402,92512],\"categoriesString\":\"Games\\/Act"
            + "ion\\/Arcade\"},{\"id\":418134,\"title\":\"Eldritch Force\",\"dateCreated\":1697889634,\"dateMo"
            + "dified\":1786311581,\"language\":[\"en\"],\"year\":2023,\"description\":\"Although dismissed by"
            + " my peers, my study of the Voidstone Talisman - a curiously nebulous artifact - has not only le"
            + "d me to believe I have discovered its potential location, but also driven me to an uncontrollab"
            + "le desire to travel there in person and discover it for myself.\\n\\nIt has been all I can thin"
            + "k about for several months now, even invading my dreams; dreams of strange shapes in the mist, "
            + "or eyes peering at me from the darkness. Although the mythology of the Talisman says it has a p"
            + "ower to open a gate from another reality, I'm sure that this is just fanciful thinking, althoug"
            + "h I do not doubt that it has some sort of power over the mind.\\n\\nFrom my research I believe "
            + "that it has been broken up into several tight-fitting pieces, and the last credible sighting of"
            + " one of these was in Hydanford - a small, somewhat unheard of town to the north-east. This is w"
            + "here I am headed now, thankful for the night train that speeds me to the conclusion of my studi"
            + "es.\\n\\nEldritch Force is a Lovecraftian game of exploration, suitable for those who like mapp"
            + "ing. There is no action and no Game Over.\\n\\nControls are Q A O P to move. M will interact - "
            + "usually this will involve searching an object at the back wall in a screen (such as a barrel, c"
            + "igarette vending machine, box or dustbin).\\n\\nThe central eye indicates your state of mind. T"
            + "his will change with strange occurrences. Below and to the left are items that you have collect"
            + "ed - what are they used for? To the right will show parts of the Voidstone Talisman. The Diary "
            + "area is where messages will appear.\\n\\nDepending on certain factors, you may experience one o"
            + "f three different endings.\",\"legalStatus\":\"unknown\",\"groupsIds\":[200792],\"publishersIds"
            + "\":[200792],\"releasesIds\":[418140,595943],\"imagesUrls\":[\"https:\\/\\/zxart.ee\\/image\\/ty"
            + "pe:prodImage\\/id:418141\\/filename:title.webp\",\"https:\\/\\/zxart.ee\\/image\\/type:prodImag"
            + "e\\/id:418143\\/filename:2U3nhU_result.webp\",\"https:\\/\\/zxart.ee\\/image\\/type:prodImage\\"
            + "/id:418142\\/filename:i1a.webp\",\"https:\\/\\/zxart.ee\\/image\\/type:prodImage\\/id:418144\\/"
            + "filename:AcZXTJ_result.webp\",\"https:\\/\\/zxart.ee\\/image\\/type:prodImage\\/id:418145\\/fil"
            + "ename:kN B r_result.webp\",\"https:\\/\\/zxart.ee\\/image\\/type:prodImage\\/id:418146\\/filena"
            + "me:mjgnGt_result.webp\",\"https:\\/\\/zxart.ee\\/image\\/type:prodImage\\/id:418147\\/filename:"
            + "ZOxyzS_result.webp\",\"https:\\/\\/zxart.ee\\/image\\/type:prodImage\\/id:418148\\/filename:end"
            + "1.webp\",\"https:\\/\\/zxart.ee\\/image\\/type:prodImage\\/id:595940\\/filename:EldritchForce-R"
            + "UN-1.webp\",\"https:\\/\\/zxart.ee\\/image\\/type:prodImage\\/id:595941\\/filename:EldritchForc"
            + "e-RUN-2.webp\",\"https:\\/\\/zxart.ee\\/image\\/type:prodImage\\/id:595942\\/filename:EldritchF"
            + "orce-RUN-3.webp\"],\"authorsInfo\":[{\"id\":66719,\"authorId\":200791,\"startDate\":\"\",\"endD"
            + "ate\":\"\",\"roles\":[\"code\",\"release\"],\"type\":\"prod\"}],\"importIds\":{\"zxdb\":\"45141"
            + "\"},\"votes\":4.19,\"votesAmount\":6,\"externalLink\":\"https:\\/\\/sunteam.itch.io\\/eldritchf"
            + "orce\",\"connectedCategoriesIds\":[92507],\"categoriesString\":\"Games\\/Adventure\\/Graphic Ad"
            + "ventures\"}]},\"responseStatus\":\"success\"}";

    /** review/zxart/af-music-by-id.json - export:zxMusic,
     *  filter:zxMusicId=19636. Copied whole: one row already. */
    public static final String MUSIC_ROW =
            "{\"totalAmount\":1,\"start\":0,\"limit\":2,\"responseData\":{\"zxMusic\":[{\"id\":19636,\"title"
            + "\":\"Beyond Time\",\"internalTitle\":\"BeYoNd TiMe ... h! f4nz!\",\"url\":\"https:\\/\\/zxart.ee"
            + "\\/eng\\/authors\\/m\\/mmcm1\\/beyond-time\\/\",\"dateCreated\":1388784658,\"dateModified\":1772"
            + "312799,\"time\":\"3:14.88\",\"partyId\":\"17066\",\"compo\":\"ay\",\"partyPlace\":\"1\",\"author"
            + "Ids\":[7744],\"type\":\"PT3\",\"rating\":\"4.54\",\"plays\":\"1072\",\"year\":2013,\"originalUrl"
            + "\":\"https:\\/\\/zxart.ee\\/file\\/id:19636\\/filename:MmcM_-_Beyond_Time_%282013%29_%28Chaos_Co"
            + "nstructions_2013%2C_1%29.mt3\",\"originalFileName\":\"MmcM_-_Beyond_Time_%282013%29_%28Chaos_Con"
            + "structions_2013%2C_1%29.mt3\",\"mp3FilePath\":\"https:\\/\\/music.zxart.ee\\/music\\/19636_MmcM_"
            + "Beyond_Time.ogg\"}]},\"responseStatus\":\"success\"}";

    /** review/zxart/af-picture-by-id.json - export:zxPicture,
     *  filter:zxPictureId=2232. Copied whole: one row already. */
    public static final String PICTURE_ROW =
            "{\"totalAmount\":1,\"start\":0,\"limit\":2,\"responseData\":{\"zxPicture\":[{\"id\":2232,\"title"
            + "\":\"Girl &amp; Sea\",\"url\":\"https:\\/\\/zxart.ee\\/eng\\/authors\\/l\\/liza\\/girl-sea\\/\","
            + "\"dateCreated\":1249075813,\"dateModified\":1707045199,\"partyId\":\"2201\",\"compo\":\"standard"
            + "\",\"partyPlace\":\"1\",\"authorIds\":[2202],\"tags\":[\"Female\",\"Glasses\",\"Landscape\",\"Po"
            + "rtrait\"],\"type\":\"standard\",\"rating\":\"3.82\",\"views\":\"894\",\"year\":2009,\"descriptio"
            + "n\":\"1st at ArtField'2009\",\"imageUrl\":\"https:\\/\\/zxart.ee\\/zximages\\/id=2232;border=0;p"
            + "al=srgb;type=standard;zoom=1\",\"originalUrl\":\"https:\\/\\/zxart.ee\\/file\\/id:2232\\/filenam"
            + "e:Liza_-_Girl_&_Sea_(2009)_(ArtField_2009,_1).scr\"}]},\"responseStatus\":\"success\"}";

    /** review/zxart/music-search.json - export:zxMusic,
     *  filter:zxMusicSearch=beyond, limit:3. Copied whole: three of the ten
     *  rows the measured filter actually returned, real and untrimmed. */
    public static final String MUSIC_SEARCH =
            "{\"totalAmount\":10,\"start\":0,\"limit\":3,\"responseData\":{\"zxMusic\":[{\"id\":19636,\"title"
            + "\":\"Beyond Time\",\"internalTitle\":\"BeYoNd TiMe ... h! f4nz!\",\"url\":\"https:\\/\\/zxart.ee"
            + "\\/eng\\/authors\\/m\\/mmcm1\\/beyond-time\\/\",\"dateCreated\":1388784658,\"dateModified\":1772"
            + "312799,\"time\":\"3:14.88\",\"partyId\":\"17066\",\"compo\":\"ay\",\"partyPlace\":\"1\",\"author"
            + "Ids\":[7744],\"type\":\"PT3\",\"rating\":\"4.54\",\"plays\":\"1072\",\"year\":2013,\"originalUrl"
            + "\":\"https:\\/\\/zxart.ee\\/file\\/id:19636\\/filename:MmcM_-_Beyond_Time_%282013%29_%28Chaos_Co"
            + "nstructions_2013%2C_1%29.mt3\",\"originalFileName\":\"MmcM_-_Beyond_Time_%282013%29_%28Chaos_Con"
            + "structions_2013%2C_1%29.mt3\",\"mp3FilePath\":\"https:\\/\\/music.zxart.ee\\/music\\/19636_MmcM_"
            + "Beyond_Time.ogg\"},{\"id\":45930,\"title\":\"Something from beyond\",\"internalTitle\":\"somethi"
            + "ng from beyond\",\"url\":\"https:\\/\\/zxart.ee\\/eng\\/authors\\/g\\/gibson\\/something-from-be"
            + "yond\\/\",\"dateCreated\":1400516201,\"dateModified\":1772314045,\"time\":\"3:04.03\",\"partyId"
            + "\":\"2301\",\"compo\":\"standard\",\"partyPlace\":\"8\",\"authorIds\":[20210,30637],\"type\":\"P"
            + "T3\",\"rating\":\"4.06\",\"plays\":\"92\",\"year\":2007,\"originalUrl\":\"https:\\/\\/zxart.ee\\"
            + "/file\\/id:45930\\/filename:Gibson%2C_Ch41ns4w_-_Something_from_beyond_%282007%29_%28Chaos_Const"
            + "ructions_Antique_2007%2C_8%29.pt3\",\"originalFileName\":\"Gibson%2C_Ch41ns4w_-_Something_from_b"
            + "eyond_%282007%29_%28Chaos_Constructions_Antique_2007%2C_8%29.pt3\",\"mp3FilePath\":\"https:\\/\\"
            + "/music.zxart.ee\\/music\\/45930_Gibson_Ch41ns4w_Something_from_beyond.ogg\"},{\"id\":47085,\"tit"
            + "le\":\"Beyond The Road\",\"internalTitle\":\"beyond the road (ABC 1.77)\",\"url\":\"https:\\/\\/"
            + "zxart.ee\\/eng\\/authors\\/w\\/wbcbz7\\/beyond-the-road\\/\",\"dateCreated\":1405282747,\"dateMo"
            + "dified\":1772314476,\"time\":\"2:47.85\",\"partyId\":\"47042\",\"compo\":\"standard\",\"partyPla"
            + "ce\":\"8\",\"authorIds\":[308697],\"type\":\"TS\",\"rating\":\"3.78\",\"plays\":\"74\",\"year\":"
            + "2014,\"originalUrl\":\"https:\\/\\/zxart.ee\\/file\\/id:47085\\/filename:wbcbz7_-_Beyond_The_Roa"
            + "d_%282014%29_%283BM_OpenAir_2014%2C_8%29.pt3\",\"originalFileName\":\"wbcbz7_-_Beyond_The_Road_%"
            + "282014%29_%283BM_OpenAir_2014%2C_8%29.pt3\",\"mp3FilePath\":\"https:\\/\\/music.zxart.ee\\/music"
            + "\\/47085_wbcbz7_Beyond_The_Road.ogg\"}]},\"responseStatus\":\"success\"}";

    /** review/zxart/picture-search.json - export:zxPicture,
     *  filter:zxPictureSearch=girl, limit:3. Copied whole: three of the 124
     *  rows the measured filter actually returned, real and untrimmed. */
    public static final String PICTURE_SEARCH =
            "{\"totalAmount\":124,\"start\":0,\"limit\":3,\"responseData\":{\"zxPicture\":[{\"id\":2232,\"tit"
            + "le\":\"Girl &amp; Sea\",\"url\":\"https:\\/\\/zxart.ee\\/eng\\/authors\\/l\\/liza\\/girl-sea\\/"
            + "\",\"dateCreated\":1249075813,\"dateModified\":1707045199,\"partyId\":\"2201\",\"compo\":\"stand"
            + "ard\",\"partyPlace\":\"1\",\"authorIds\":[2202],\"tags\":[\"Female\",\"Glasses\",\"Landscape\","
            + "\"Portrait\"],\"type\":\"standard\",\"rating\":\"3.82\",\"views\":\"894\",\"year\":2009,\"descri"
            + "ption\":\"1st at ArtField'2009\",\"imageUrl\":\"https:\\/\\/zxart.ee\\/zximages\\/id=2232;border"
            + "=0;pal=srgb;type=standard;zoom=1\",\"originalUrl\":\"https:\\/\\/zxart.ee\\/file\\/id:2232\\/fil"
            + "ename:Liza_-_Girl_&_Sea_(2009)_(ArtField_2009,_1).scr\"},{\"id\":2288,\"title\":\"Car &amp; girl"
            + "\",\"url\":\"https:\\/\\/zxart.ee\\/eng\\/authors\\/t\\/tractor\\/car-girl\\/\",\"dateCreated\":"
            + "1249116520,\"dateModified\":1707045202,\"partyId\":\"2252\",\"compo\":\"standard\",\"partyPlace"
            + "\":\"12\",\"authorIds\":[2266],\"tags\":[\"Black&amp;white\",\"Car\",\"Nude\",\"Surfing\"],\"typ"
            + "e\":\"standard\",\"rating\":\"3.76\",\"views\":\"1152\",\"year\":2002,\"imageUrl\":\"https:\\/\\"
            + "/zxart.ee\\/zximages\\/id=2288;border=7;pal=srgb;type=standard;zoom=1\",\"originalUrl\":\"https:"
            + "\\/\\/zxart.ee\\/file\\/id:2288\\/filename:Tractor_-_Car_&_girl_(2002)_(Shit_Compo_2002,_12).scr"
            + "\"},{\"id\":2571,\"title\":\"RizcGirl\",\"url\":\"https:\\/\\/zxart.ee\\/eng\\/authors\\/p\\/phe"
            + "el1\\/rizcgirl\\/\",\"dateCreated\":1275853147,\"dateModified\":1707045211,\"compo\":\"standard"
            + "\",\"authorIds\":[2270],\"tags\":[\"Eye\",\"Face\",\"Female\",\"Hair\",\"Hidden Pixels\",\"Portr"
            + "ait\"],\"type\":\"standard\",\"rating\":\"4.61\",\"views\":\"1621\",\"year\":2000,\"imageUrl\":"
            + "\"https:\\/\\/zxart.ee\\/zximages\\/id=2571;border=0;pal=srgb;type=standard;zoom=1\",\"originalU"
            + "rl\":\"https:\\/\\/zxart.ee\\/file\\/id:2571\\/filename:PheeL_-_RizcGirl_(2000).scr\"}]},\"respo"
            + "nseStatus\":\"success\"}";

    /**
     * A real {@code legalStatus:"forbidden"} row, out of review/zxart/
     * prod-categories.json (id 93056, "Afterburner" - one of the 21 forbidden
     * entries in that 285-category sweep, found with the query the task
     * brief gives). The row itself is copied verbatim, byte for byte; only
     * the wrapper around it - {@code totalAmount}, {@code start}, {@code
     * limit}, {@code responseStatus} - is not from that file, because a
     * 285-category sweep has no single-prod reply of its own to copy. It is
     * built to the same shape {@code prod-by-id.json} actually answers with
     * for a {@code filter:zxProdId} lookup of one prod: {@code
     * totalAmount:1}, {@code start:0}, {@code limit:2}, {@code
     * responseStatus:"success"} - a real reply shape, applied rather than
     * invented, to a row that could not otherwise be isolated.
     */
    public static final String PROD_FORBIDDEN =
            "{\"totalAmount\":1,\"start\":0,\"limit\":2,\"responseData\":{\"zxProd\":[{\"id\":93056,\"title\""
            + ":\"Afterburner\",\"dateCreated\":1479491172,\"dateModified\":1780657902,\"language\":[\"en\"],\""
            + "year\":1988,\"youtubeId\":\"JG4qqpggRKM\",\"legalStatus\":\"forbidden\",\"groupsIds\":[176302,17"
            + "6299,176298,321030,322039],\"publishersIds\":[184271],\"releasesIds\":[328384,328398,328400,3284"
            + "04,360045,360059,360060,251752,251754,251755,251756,422440,431688,431691,431693,513120,513121,51"
            + "5091],\"imagesUrls\":[\"https:\\/\\/zxart.ee\\/screenshot\\/id:599701\\/afterb.gif\",\"https:\\/"
            + "\\/zxart.ee\\/screenshot\\/id:599700\\/afterb1.gif\",\"https:\\/\\/zxart.ee\\/zximages\\/id=9305"
            + "7;pal=srgb;type=standard;zoom=1\",\"https:\\/\\/zxart.ee\\/screenshot\\/id:93058\\/Afterburner.g"
            + "if\"],\"maps\":[\"https:\\/\\/zxart.ee\\/release\\/id:240695\\/mode:download\\/filename:Afterbur"
            + "ner.png\"],\"authorsInfo\":[{\"id\":1362,\"authorId\":5278,\"startDate\":\"\",\"endDate\":\"\","
            + "\"roles\":[\"unknown\"],\"type\":\"prod\"},{\"id\":1360,\"authorId\":13787,\"startDate\":\"\",\""
            + "endDate\":\"\",\"roles\":[\"unknown\"],\"type\":\"prod\"},{\"id\":1361,\"authorId\":176300,\"sta"
            + "rtDate\":\"\",\"endDate\":\"\",\"roles\":[\"unknown\"],\"type\":\"prod\"}],\"importIds\":{\"maps"
            + "\":\"Afterburner\",\"zxdb\":\"103\",\"wos\":\"0000103\",\"vt\":\"6dde54e4338006e1d8366e2682caa48"
            + "b\"},\"votes\":3.91,\"votesAmount\":1,\"rzx\":[\"https:\\/\\/zxart.ee\\/release\\/id:554443\\/mo"
            + "de:download\\/filename:Afterburner.rzx.zip\"],\"connectedCategoriesIds\":[523423],\"categoriesSt"
            + "ring\":\"\\u0418\\u0433\\u0440\\u044b\\/\\u042d\\u043a\\u0448\\u0435\\u043d\\/\\u0428\\u0443\\u0"
            + "442\\u0435\\u0440\\u044b\\/\\u0420\\u0435\\u043b\\u044c\\u0441\\u043e\\u0432\\u044b\\u0439 \\u04"
            + "48\\u0443\\u0442\\u0435\\u0440\"}]},\"responseStatus\":\"success\"}";

    /**
     * review/zxart/af-author-entity.json - export:author,
     * filter:authorId=6661. Copied whole: one row already.
     *
     * <b>A stand-in, exactly as {@code PROD_SEARCH} is for the category and
     * "similar" tests above</b> - nothing was captured for authorId 7744 (
     * {@code MUSIC_ROW}'s own author) or 2202 ({@code PICTURE_ROW}'s), and a
     * probe against either would be inventing a reply from memory rather than
     * measuring one, which this file's own class javadoc rules out. This is a
     * real {@code export:author} reply for a real id, in the one shape that
     * matters to the code under test - one row, a {@code title} to read - so
     * it proves {@code authorNameOf} parses what the service actually sends
     * back, which is the fact worth pinning; {@code Fixtures.Canned} does not
     * check what a queued reply was asked for, only what order it was queued
     * in.
     */
    public static final String AUTHOR_RAFFAELE_CECCO =
            "{\"totalAmount\":1,\"start\":0,\"limit\":2,\"responseData\":{\"author\":[{\"id\":6661,\"title\":"
            + "\"Raffaele Cecco\",\"url\":\"https:\\/\\/zxart.ee\\/eng\\/authors\\/r\\/raffaele-cecco\\/\",\"date"
            + "Created\":1287774884,\"dateModified\":1783618899,\"realName\":\"Raffaele Cecco\",\"picturesQuant"
            + "ity\":\"9\",\"country\":\"United Kingdom\",\"importIds\":{\"sc\":\"11996\"}}]},\"responseStatus\""
            + ":\"success\"}";

    /**
     * An {@code Http} that answers from a list and remembers what it was
     * asked.
     *
     * The same shape as {@code ZxInfoCatalogueTest.Canned}, which is
     * deliberate: two fakes that differ only in style are two things to read.
     * It lives here rather than in each test class because four classes need
     * it now.
     *
     * <b>Strict, on purpose, since review round 1 of Task 11: an exhausted
     * queue throws rather than answering a silent empty success.</b> It used
     * to hand back {@code 200 {}} once {@link #replies} ran out, which is a
     * trap disguised as a convenience - {@code ZxartApi.rows} reads an
     * unrecognised body as zero rows rather than failing, so a caller that
     * makes one request more than a test queued for got an empty page back
     * and kept going. Nothing this class's own assertions checked would
     * notice: a test that asserts on the rows it got, and not on {@code
     * asked.size()}, cannot tell "the code made the one request I queued
     * for" from "the code made that request, then one more nobody asked
     * about, which came back empty and was silently swallowed." That is
     * exactly the shape a regression moving a per-item request (an author
     * lookup, say) into a per-row loop would take: every field on every row
     * would still be right, because the extra author lookups would each
     * resolve to null and nothing downstream reads that as a failure.
     * Thirty rows becoming thirty paced requests against an archive that
     * blocks on behaviour patterns is precisely the kind of regression that
     * must not be able to hide behind a green run.
     *
     * Tried strict against the whole JVM tier before keeping it: nothing in
     * {@code ZxartCatalogueTest} or {@code ZxartApiTest} - the only two
     * classes that use this fake today - depends on an exhausted queue
     * answering anything at all, so there was nothing to revert. A test that
     * legitimately wants to allow more requests than it cares to name replies
     * for should queue enough real replies (or a generic one repeated), not
     * lean on this falling through - the whole point of a canned fake is
     * that every request it answers is one somebody wrote down.
     *
     * {@code ZxInfoCatalogueTest}'s own, separate {@code Canned}
     * (`app/src/androidTest`) is untouched and stays lenient - it is a
     * different class in a different tier, outside what this fix addressed,
     * and carries the same hazard this javadoc now names for whoever next
     * has reason to look at it.
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

        /** @throws IllegalStateException once every queued reply is spent -
         *  see this class's own javadoc for why that is a feature. */
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
}
