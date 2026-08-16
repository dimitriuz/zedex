package dev.ldlab.zedex.welcome;

/**
 * The wizard's pages, in the order they are asked.
 *
 * An enum and not a list of view classes: which pages apply is a rule with
 * cases in it - see {@link Steps} - and a rule about views is a rule no JVM
 * test can pose. The activity maps one of these to a {@code Step} when it is
 * time to draw it.
 *
 * The order here <em>is</em> the order of the wizard. Nothing else states it.
 */
public enum Page {
    /** Welcome, and the language - one page, so that choosing a language and
     *  seeing the page redraw in it is the same gesture. */
    WELCOME,
    FOLDERS,
    MACHINE,
    CONTROLS,
    SCREEN,
    LIBRARY,
    SCRAPING,
    /** The summary, the intro tape, and the way out. */
    DONE
}
