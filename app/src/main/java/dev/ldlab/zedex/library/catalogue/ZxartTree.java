package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.library.scrape.ZxartApi;

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

            tree.titles.put(id, ZxartApi
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
