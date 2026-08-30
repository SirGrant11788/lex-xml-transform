package com.lexisnexis.transform.transform;

import net.sf.saxon.s9api.Destination;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;

import java.net.URI;
import java.util.function.Function;

/**
 * Routes {@code xsl:result-document} outputs in the stylesheet to in-memory
 * {@link Serializer}s by URI. The XSLT uses {@code href="text:full"} to send
 * the plain-text RAG view here, while the primary JSON output goes to the
 * transformer destination directly.
 */
final class StringResultDocumentHandler implements Function<URI, Destination> {
    private final String targetHref;
    private final Serializer serializer;

    StringResultDocumentHandler(String targetHref, Serializer serializer) {
        this.targetHref = targetHref;
        this.serializer = serializer;
    }

    @Override
    public Destination apply(URI uri) {
        if (uri != null && targetHref.equals(uri.toString())) {
            return serializer;
        }
        return null;
    }
}
