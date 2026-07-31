package dev.ldlab.zedex;

/**
 * A short list of things that can be done, wherever it is being shown.
 *
 * There are two places: the ☰ sheet down the side of the window, and the list
 * a quick bar group drops under its icon. They are different shapes and they
 * are built from different views, but a list of *Screenshot · GIF · MP4* is the
 * same list in both — and it was written twice, once as
 * {@code fillCapture(MenuDrawer)} and once as {@code fillCaptureBar(QuickBar)},
 * because {@link MenuDrawer#addItem} and {@link QuickBar#addToRow} take the
 * same three things in a different order.
 *
 * They drifted, which is what always happens. The sheet's capture page offered
 * <i>Open recordings folder</i> and the bar's did not; the sheet drew MP4 with
 * the record icon and the bar with a reel of film; the machine page ordered its
 * rows one way in the sheet and another on the bar. None of it was decided.
 *
 * So the shared lists are written once against this, and both surfaces
 * implement it. What is <em>not</em> shared stays where it is: the sheet can
 * carry submenus, fields, notes and headings, and a group's list cannot, so
 * anything that needs those is a sheet page and says so by taking a
 * {@link MenuDrawer}.
 */
interface Rows {

    /** One thing that can be done: what it looks like, what it says, what it does. */
    void item(int icon, String text, Runnable action);

    /** A line, for a list that holds two kinds of thing. */
    void rule();
}
