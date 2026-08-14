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
