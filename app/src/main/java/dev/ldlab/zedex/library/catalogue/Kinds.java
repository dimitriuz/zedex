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
 * <b>42,828 is not the 39,666 the rest of this feature counts, and neither is
 * wrong.</b> They are two populations of one table: 44,215 entries in the
 * dump, of which 39,666 carry a {@code machinetype_id} - the figure {@code
 * /metadata/} reports and every comment about the size of the catalogue uses -
 * and 42,828 carry a {@code genretype_id}, which is the only one that means
 * anything here. More entries have a genre than a machine because a book, a
 * piece of hardware or an advertisement is a kind of thing without being a
 * kind of computer. Counted from the offline dump at github.com/zxdb/ZXDB;
 * the coarse genre of each entry is its {@code genretypes.text} up to the
 * colon, which is what collapses that table's 112 rows to the 23 below.
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
    public static final String MUSIC = "Music";
    public static final String GRAPHICS = "Graphics";

    /** Every folder this feature can create, for the test that says nothing
     *  lands outside them. */
    public static final String[] ALL = {
        GAMES, APPLICATIONS, COMPILATIONS, MAGAZINES, DEMOSCENE, RECORDINGS, OTHER,
        MUSIC, GRAPHICS,
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
        { MAGAZINES, "electronic magazine", "e-book", "book", "press" },
        { DEMOSCENE, "demoscene", "tech demo", "animation" },
        // "application" is zxart's own root word ("Applications") and reaches
        // here directly - none of ZXDB's genre words are "Application"
        // anything, so this row alone would otherwise never see it and the
        // root's own name would fall through to Other.
        { APPLICATIONS, "utility", "programming", "emulator", "replacement rom",
                        "application" },
        { GAMES, "arcade game", "adventure game", "puzzle game", "casual game",
                 "sport game", "strategy game", "game" },

        // After GAMES, and that ordering is asserted: zxart's "Educational"
        // root is an application, and ZXDB's "Educational Game" is a game. The
        // greedy "game" row above has to see the phrase first, which is the
        // opposite of the specific-first reading the rest of this table uses.
        { APPLICATIONS, "system software", "educational" },

        // Last, and for the same reason "educational" is late: these two words
        // are whole kinds from zxart's own zxMusic and zxPicture entities -
        // "Music", "Graphics", nothing else in them - so nothing above can
        // catch either, while ZXDB's compound genres put them inside a phrase
        // whose FIRST word is the real kind. "Utility: Music" is a tracker,
        // a program, and it landed in Music beside zxart's .pt3 tunes while
        // these rows sat above APPLICATIONS. See KindsTest.
        { MUSIC, "music" },
        { GRAPHICS, "graphics" },
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
     * Adventure". The bare {@code "game"} row is deliberately greedy: it is
     * meant to swallow any {@code "<something> Game"}, recorded or not, since
     * a holographic game or a board game is still a game. The ordering above
     * is what keeps that honest - {@code "emulator"} and {@code "electronic
     * magazine"} are tried first, so "Gameboy Emulator" lands in Applications
     * and "Electronic Magazine Game" in Magazines rather than both being
     * caught by {@code "game"}. This is not the machine table's mistake repeating:
     * there, {@code "16"} matched a numeric fragment inside an unrelated
     * machine name; here {@code "game"} is a whole word of the domain
     * matching something that genuinely is one. Checked exhaustively against
     * {@link #ZXDB_VOCABULARY}: every overlap between table words - "book" in
     * "e-book", "game" in each specific game phrase - resolves to the same
     * folder regardless of which word fires, which is the evidence the
     * ordering claim rests on. {@code KindsTest} pins both the greediness and
     * that evidence.
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
}
