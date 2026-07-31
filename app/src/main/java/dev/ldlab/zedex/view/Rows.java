package dev.ldlab.zedex.view;

/**
 * A short list of things that can be done, in either place that shows one: the
 * ☰ sheet, or the list a quick bar group drops under its icon.
 *
 * It exists because the two took the same three arguments in a different order,
 * so every shared list was written twice and the two copies drifted — different
 * icons for MP4, a row in one and not the other, opposite orders on the machine
 * page.
 *
 * Only what both can do. A sheet page can also carry submenus, fields, notes and
 * headings; anything needing those takes a {@link MenuDrawer} and says so.
 */
public interface Rows {

    /** One thing that can be done: what it looks like, what it says, what it does. */
    void item(int icon, String text, Runnable action);

    /** A line, for a list that holds two kinds of thing. */
    void rule();
}
