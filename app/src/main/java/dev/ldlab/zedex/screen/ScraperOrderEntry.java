package dev.ldlab.zedex.screen;

import android.app.Activity;

import java.util.List;
import java.util.function.Consumer;

/**
 * The one way into {@link ScraperOrder} from outside this package - today
 * that is {@code welcome.pages.ScrapingPage}.
 *
 * {@link ScraperOrder}, its {@link ScraperOrder.Chosen} callback and its
 * {@code show} stay package-private; this is the seam instead. A stock
 * {@code Consumer<List<String>>} rather than {@link ScraperOrder.Chosen}
 * itself, because a caller outside {@code screen} can build a lambda against
 * a public functional interface from the standard library without either of
 * this package's own types ever needing to be public - {@link #show} is
 * where the adapting from one to the other happens, once, in a class whose
 * only job is that adapting.
 */
public final class ScraperOrderEntry {

    private ScraperOrderEntry() {
    }

    public static void show(Activity activity, List<String> available, List<String> enabled,
                            Consumer<List<String>> onChosen) {
        ScraperOrder.show(activity, available, enabled, onChosen::accept);
    }
}
