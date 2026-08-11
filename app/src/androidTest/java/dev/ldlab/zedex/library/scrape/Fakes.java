package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Meta;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Providers and an Http with no service behind them.
 *
 * Shared by {@code SweepTest} and {@code BlendTest} rather than private to
 * one, since both need the same thing for the same reason: every branch a
 * real run can take - a spent quota, refused credentials, a thread limit - is
 * one a fake can produce on demand and none of them can be arranged reliably
 * against a live service.
 */
final class Fakes {

    private Fakes() {
    }

    static Candidate exact(String name) {
        return new Candidate("h-" + name, name, "1987", "Imagine", true);
    }

    static Candidate guess(String name) {
        return new Candidate("h-" + name, name, "1987", "Imagine", false);
    }

    /** What a fake answers a search with: candidates, or a reason it cannot. */
    interface Answer {
        List<Candidate> to(Provider.Game game) throws ScrapeException;
    }

    /** What a fake answers a fetch with. */
    interface Facts {
        Meta about(Candidate candidate);
    }

    /**
     * A provider with no service behind it.
     *
     * Records what it was asked, which is how the tests that care about a
     * request <em>not</em> being made can say so.
     */
    static final class Fake implements Provider {

        final List<String> searched = new ArrayList<>();
        final List<String> fetched = new ArrayList<>();

        /** What {@code game.md5()} answered, once per search - so a test can
         *  tell whether the hash was offered at all, not just what the search
         *  term was. */
        final List<String> md5Asked = new ArrayList<>();

        /** Which folders it was asked to resolve, per fetch. */
        final List<java.util.Set<String>> wantedOf = new ArrayList<>();

        private final String name;

        Answer answer = game -> Collections.singletonList(exact(game.filename()));
        Facts facts = candidate -> Meta.at(null)
                .name(candidate.name).desc("from the fake")
                .developer("Taito").publisher("Imagine")
                .genre("Action").released("19870101T000000")
                .players("1").rating("0.7500")
                .build();

        List<Medium> media = Collections.emptyList();
        Quota quota = Quota.unknown();

        Fake() {
            this("Fake");
        }

        Fake(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public boolean configured() { return true; }
        @Override public Quota quota() { return quota; }

        /** The same arithmetic ScreenScraper uses, since these tests were
         *  written against a provider whose media are requests. */
        @Override public int costPerGame(Wanted wanted) { return 1 + wanted.requests(); }

        @Override
        public List<Candidate> search(Game game) throws ScrapeException {
            searched.add(game.filename());
            md5Asked.add(game.md5());
            return answer.to(game);
        }

        @Override
        public Scraped fetch(Candidate candidate, Wanted wanted) {
            fetched.add(candidate.name);
            wantedOf.add(wanted.folders());

            List<Medium> mine = new ArrayList<>();
            for (Medium medium : media) {
                if (wanted.wants(medium.folder)) mine.add(medium);
            }
            return new Scraped(facts.about(candidate), mine);
        }

        @Override
        public ScrapeException refusalFor(int status) {
            return new ScrapeException(ScrapeException.Kind.NETWORK, "status " + status);
        }
    }

    /** No medium is ever asked for by the tests that use this. */
    static class NoHttp implements Http {
        @Override public Reply get(String url) {
            throw new AssertionError("nothing should be fetching a page itself");
        }
        @Override public String save(String url, File into) {
            throw new AssertionError("no media were wanted");
        }
    }

    /** Writes the url's own bytes, so two sources' pictures differ and one
     *  source's picture is stable. */
    static final class WritesTheUrl implements Http {
        @Override public Reply get(String url) {
            throw new AssertionError("nothing should be fetching a page itself");
        }

        @Override public String save(String url, File into) throws IOException {
            File parent = into.getParentFile();
            if (parent != null) parent.mkdirs();

            try (FileOutputStream out = new FileOutputStream(into)) {
                out.write(url.getBytes("UTF-8"));
            }
            return null;
        }
    }
}
