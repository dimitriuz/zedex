package dev.ldlab.zedex.screen;

import java.util.HashSet;
import java.util.Set;

/**
 * Whether the panel has to be out of the way, and what is asking it to be.
 *
 * A {@link SecondScreen} is a {@code Presentation}, and one of those draws
 * above every activity window on its display - so anything opened onto the
 * panel's screen is behind it until the panel hides. Two different things ask
 * for that and they overlap in time: screens of the app's own, watched through
 * the application's lifecycle callbacks, and a foreign window - a manual's
 * viewer - which reaches no callback of ours at all and has to say so itself.
 *
 * The rule is one line and has been wrong twice, once in each direction, which
 * is why it is a class of its own now rather than the same ten lines in {@link
 * Panels} and {@link LibraryPanel}:
 *
 * <ul>
 * <li>A panel that never came back left Android's second-display launcher
 *     where the app had been, because only foreign screens were watched and
 *     nothing noticed one of ours closing.</li>
 * <li>A panel that came back too eagerly drew over the folder picker: our
 *     screen is <em>stopped</em> when a foreign window covers it, and a count
 *     kept on started/stopped read that as "our screen has gone". The picker
 *     flashed up and the app appeared to swallow it - and once Back on the
 *     panel was refused, there was no way out of that at all.</li>
 * </ul>
 *
 * So the question this answers is "is one of our screens still <em>there</em>",
 * not "is one of them in front": {@link #closed} belongs on destroyed, the one
 * callback that really means gone. A set rather than a counter for the same
 * reason - a screen covered and then uncovered is started twice and destroyed
 * once, and a counter would come out one short and hide the panel for ever.
 *
 * Keyed on {@code Object} rather than on {@code Activity} so this can be
 * tested without a device: what it does with the key is identity, and nothing
 * here is about activities beyond that.
 */
final class StepAside {

    /** Our own screens that exist right now, whether or not they are in
     *  front. Cleared entry by entry as each is destroyed; the whole thing
     *  dies with the panel's owner, which unregisters the callbacks that
     *  fill it. */
    private final Set<Object> ourScreens = new HashSet<>();

    private boolean foreignUp;

    /** One of the app's own screens is up on the panel's display. */
    void opened(Object screen) {
        ourScreens.add(screen);
    }

    /** ...and is really gone. Destroyed, never merely stopped - see the class
     *  comment for what stopped costs. */
    void closed(Object screen) {
        ourScreens.remove(screen);
    }

    /** A window that is not ours landed on that display - a manual's viewer,
     *  which no lifecycle callback of ours ever sees. */
    void foreignOpened() {
        foreignUp = true;
    }

    /** ...and the front of that display is the app's again, which is the
     *  nearest thing to a signal that a foreign activity has gone: there is
     *  no callback for one closing. */
    void foreignClosed() {
        foreignUp = false;
    }

    /** The panel itself went away. Whatever it was waiting to come back from
     *  is not something the next panel inherits - but our own screens are
     *  still there, and still count against whatever replaces it. */
    void panelClosed() {
        foreignUp = false;
    }

    /** The whole answer, from both reasons at once, so that neither can leave
     *  the panel in a state the other did not want. */
    boolean hidden() {
        return !ourScreens.isEmpty() || foreignUp;
    }
}
