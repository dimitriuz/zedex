package dev.ldlab.zedex.library.scrape;

/**
 * One game a provider thinks a file might be, and enough about it to choose.
 *
 * The fields are exactly what a person needs to tell two candidates apart on a
 * list - "Batman (1986)" against "Batman (1989)", or the same game in two
 * regions - and nothing else. Everything more comes from {@link
 * Provider#fetch}, which is a second request and is not worth making for six
 * games somebody is going to reject five of.
 *
 * {@code handle} is the provider's own identifier, opaque here. Keeping it
 * rather than re-searching means the fetch asks about exactly the game that
 * was chosen, which a second search by name cannot promise.
 */
public final class Candidate {

    /** Opaque to everything but the provider that made it. */
    public final String handle;

    public final String name;

    /** Four digits, or null - what a person actually tells two versions
     *  apart by. */
    public final String year;

    /** Or null. The other thing that distinguishes a re-release. */
    public final String publisher;

    /** Whether the provider is certain rather than guessing - true for a hash
     *  hit, false for a name search. A single certain candidate is what lets a
     *  scrape fill itself in without asking. */
    public final boolean exact;

    public Candidate(String handle, String name, String year, String publisher, boolean exact) {
        this.handle = handle;
        this.name = name;
        this.year = year;
        this.publisher = publisher;
        this.exact = exact;
    }

    /** "Batman (1989) · Ocean", skipping whichever of the two is unknown -
     *  the same joining the pane's own facts line does. */
    public String describe() {
        StringBuilder line = new StringBuilder(name == null ? "" : name);

        if (year != null && !year.isEmpty()) line.append(" (").append(year).append(")");
        if (publisher != null && !publisher.isEmpty()) line.append(" · ").append(publisher);

        return line.toString();
    }
}
