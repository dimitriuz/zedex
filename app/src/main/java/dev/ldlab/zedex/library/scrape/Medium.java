package dev.ldlab.zedex.library.scrape;

/**
 * One picture, video or manual a provider has, and where to get it.
 *
 * {@link #folder} is one of ES-DE's own folder names, because that is what
 * this app's media folder mirrors - see {@code Artwork}. Translating a
 * provider's own vocabulary into those names is the provider's job, so that
 * everything downstream handles all three services identically.
 *
 * {@link #md5} is what the provider says the file should hash to, and it is
 * the difference between a download that arrived short and a broken cover
 * nobody can tell from a real one. Null when the provider does not say.
 */
public final class Medium {

    public final String folder;
    public final String url;
    public final String extension;
    public final String md5;

    public Medium(String folder, String url, String extension, String md5) {
        this.folder = folder;
        this.url = url;
        this.extension = extension;
        this.md5 = md5;
    }
}
