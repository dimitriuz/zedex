package dev.ldlab.zedex.view;

/**
 * The colours this app is drawn in, in one place.
 *
 * They were declared privately in eleven files, and they had drifted: the
 * library list and its options dialog used one grey for secondary text while
 * the info screen those two open used another, and the info screen sat on a
 * different background again - three screens two taps apart, disagreeing in a
 * way nobody chose. The values here are whichever of each pair was already in
 * the majority, so most screens are unchanged and the odd ones out join them.
 *
 * There is no colours.xml on purpose. Every screen here is built in code, with
 * the geometry computed rather than laid out in XML, so a resource lookup would
 * be a second place to look rather than the only one.
 */
public final class Palette {

    private Palette() {
    }

    /** Primary text on {@link #BACKING}. 15.6:1, well past WCAG AA. */
    public static final int TEXT = 0xffededf2;

    /**
     * Secondary text: a date, a file size, the second line of a row.
     *
     * The lighter of the two greys that were in use. Against BACKING that is
     * 7.4:1 rather than 6.2:1, so the screens that change are the ones that
     * gain contrast, which is the right direction for the one to standardise
     * on when the choice is otherwise arbitrary.
     */
    public static final int MUTED = 0xff9a9aa5;

    /** The background every full screen is drawn on. */
    public static final int BACKING = 0xff14151a;

    /**
     * Over the picture rather than instead of it - the quick bar and the
     * activity lamps sit on the emulated screen and have to stay legible
     * without hiding it. A different job from BACKING, not a different value
     * for the same one.
     */
    public static final int SCRIM = 0x99000000;
}
