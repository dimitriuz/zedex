package dev.ldlab.zedex.storage;

import android.util.Log;

import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.StringReader;

/**
 * Document builders that read the file and nothing else.
 *
 * Every XML file this app parses is one anybody can write. ES-DE's own
 * {@code gamelist.xml}
 * and {@code es_settings.xml} sit in shared storage; {@code es_systems.xml} and
 * {@code es_find_rules.xml} belong to ES-DE, and this app edits them in place —
 * parse, add ours, write back.
 *
 * A parser left at its defaults resolves external entities, so a
 * {@code <!DOCTYPE x SYSTEM "file:///...">} planted in any of them is read when
 * we parse it and written back out when we serialise the document again. The
 * release and sideload builds hold All files access, so that is another app
 * borrowing this one's permission to read a file it could not open itself — and
 * it needs nothing but for the user to tap <em>Add to ES-DE</em> once. The
 * cheaper version of the same hole is a nest of entity definitions that expands
 * until the process dies.
 *
 * The usual answer is to refuse the doctype, and it does not work here:
 * Android's parser is {@code org.apache.harmony.xml.parsers}, which supports
 * none of the Xerces feature names and throws for every one of them. What it
 * does support is an {@link org.xml.sax.EntityResolver}, so that is the guard
 * — external entities resolve to nothing rather than to a file. The features
 * are still set, in a try each, for any implementation that honours them.
 *
 * {@code XmlTest} therefore checks the behaviour and not the settings. That
 * distinction is not academic: it is how the throw above was found at all.
 */
public final class Xml {

    private Xml() {
    }

    private static final String TAG = "Zedex";

    /** With no doctype there is nothing to declare an entity in, so this one
     *  closes the hole by itself; the rest are depth rather than substitutes. */
    private static final String NO_DOCTYPE =
            "http://apache.org/xml/features/disallow-doctype-decl";

    private static final String[] ALSO_OFF = {
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities",
        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
    };

    /**
     * A builder that will not fetch anything, whatever the file asks for.
     *
     * Each feature is set on its own and a refusal is survivable, because a
     * parser is entitled to throw {@link ParserConfigurationException} for a
     * name it does not know and the set of names Android's recognises is not
     * promised anywhere. Letting one unknown name out of this method would
     * turn "the file might be hostile" into "no XML file parses at all", which
     * is a worse bug than the one being fixed and would show up as ES-DE
     * integration quietly ceasing to work — which is precisely what the first
     * version of this method did, on every Android device.
     *
     * The resolver at the end is the part that is not allowed to fail.
     */
    public static DocumentBuilder builder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        // Android's parser (org.apache.harmony.xml.parsers) supports none of
        // the Xerces feature names and throws for every one of them, so this
        // is depth for other implementations rather than the guard here. The
        // guard here is the EntityResolver at the bottom. Logged at debug
        // rather than warn because on Android it is the expected outcome and a
        // warning every time would be noise in every bug report.
        try {
            factory.setFeature(NO_DOCTYPE, true);
        } catch (ParserConfigurationException | RuntimeException | AbstractMethodError e) {
            Log.d(TAG, "this parser will not disallow a doctype: " + e);
        }

        for (String feature : ALSO_OFF) {
            try {
                factory.setFeature(feature, false);
            } catch (ParserConfigurationException | RuntimeException
                     | AbstractMethodError e) {
                // Unknown to this parser. NO_DOCTYPE above is the guard that
                // counts; these only matter if it did not take.
            }
        }

        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException | RuntimeException
                 | AbstractMethodError e) {
            // Bounds entity expansion where it is honoured. Not required here,
            // since a document with no doctype cannot define an entity.
        }

        try {
            // Android's parser throws UnsupportedOperationException for both of
            // these - "This parser does not support specification Unknown
            // version 0.0" - rather than the checked exception the other
            // settings raise. Found by XmlTest, which is the whole reason it
            // runs on a device: uncaught, this broke every XML file the app
            // reads, and the symptom would have been ES-DE integration and
            // library metadata quietly ceasing to work.
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (UnsupportedOperationException | AbstractMethodError e) {
            // Neither is load-bearing; the resolver below is.
        }

        DocumentBuilder builder = factory.newDocumentBuilder();

        // What actually closes it on Android, since nothing above takes.
        //
        // Every external entity resolves to an empty document instead of being
        // fetched, so a planted <!ENTITY x SYSTEM "file:///..."> yields nothing
        // whatever the parser's own default may be. Android's happens to be not
        // to fetch either, but "happens to" is not a guarantee to rest a file
        // read on - and this app hands the parsed document straight back out to
        // disk, so a resolved entity would be written where its planter can
        // read it. XmlTest checks the behaviour rather than the setting for the
        // same reason.
        builder.setEntityResolver((publicId, systemId) -> {
            Log.w(TAG, "refused an external entity: " + systemId);
            return new InputSource(new StringReader(""));
        });

        return builder;
    }
}
