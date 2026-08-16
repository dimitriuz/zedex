package dev.ldlab.zedex.screen;

/**
 * The project's own addresses, in one place - for the wizard's last page and
 * {@link AboutActivity}, which both want two or three of them.
 *
 * <b>Not a {@code releases} url anywhere.</b> CI greps every Play dex for the
 * literal {@code github.com/dimitriuz/zedex/releases} and fails the build if it
 * finds one - the Play build cannot update itself and must not look as though
 * it could. {@code Updater} builds that path at runtime for exactly this
 * reason, and nothing here may spell it out.
 */
public final class Links {

    private Links() {
    }

    public static final String SOURCE = "https://github.com/dimitriuz/zedex";
    public static final String ISSUES = "https://github.com/dimitriuz/zedex/issues";

    /**
     * Where to put something in the hat, if this was worth anything to you.
     *
     * Two of them because the two services reach different people - one of
     * them is unavailable in whole countries. They appear on the About screen
     * and on the last page of the first-run wizard, and nowhere else; nothing
     * is gated behind them and nothing is counted.
     */
    public static final String KO_FI = "https://ko-fi.com/W3Q224VFOR";
    public static final String COFFEE = "https://www.buymeacoffee.com/dmitriileshchenko";
}
