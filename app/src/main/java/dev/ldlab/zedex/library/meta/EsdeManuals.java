package dev.ldlab.zedex.library.meta;

import androidx.core.content.FileProvider;

/**
 * A subclass with nothing in it, because that is what a second {@link
 * FileProvider} in one app actually needs.
 *
 * This app already had one, {@code Updater}'s own, {@code
 * ${applicationId}.updates}, for the APK it downloads - see {@code
 * update_paths.xml}. Adding a second {@code <provider>} in the manifest that
 * also named {@code android:name="androidx.core.content.FileProvider"}, only
 * with a different {@code android:authorities}, did not make a second
 * provider: the framework keys a running provider on its <em>class</em>, not
 * on the authority string in whichever manifest entry declared it, so both
 * entries resolved to the same {@code FileProvider} instance and it answered
 * only for whichever authority it happened to be constructed with. A Uri
 * built for {@code .esde} handed to that instance threw {@code "The
 * authority ... does not match the one of the contentProvider ..."}, naming
 * the updater's own authority - a provider nobody had asked about, which is
 * what made this worth writing down: the failure names a symptom two steps
 * removed from the actual mistake.
 *
 * A distinct class per authority is what the framework can actually tell
 * apart, and Android's own {@link FileProvider} documentation says so; this
 * one needs no method of its own, since everything {@link FileProvider}
 * does is already what {@link
 * dev.ldlab.zedex.library.ui.Manuals#open} wants. See {@code
 * esde_manual_paths.xml} for the one folder it is allowed to answer for.
 */
public final class EsdeManuals extends FileProvider {
}
