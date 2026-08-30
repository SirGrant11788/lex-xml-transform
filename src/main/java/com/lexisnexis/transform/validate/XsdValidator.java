package com.lexisnexis.transform.validate;

import com.lexisnexis.transform.config.LexIngestProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates XML documents against the configured XSD using the JDK's
 * built-in {@link Validator}. Catches every SAX diagnostic and surfaces
 * a structured list of {@link Diagnostic} records.
 */
@Component
public class XsdValidator {
    private static final Logger log = LoggerFactory.getLogger(XsdValidator.class);

    private final LexIngestProperties props;
    private Schema schema;

    public XsdValidator(LexIngestProperties props) {
        this.props = props;
    }

    @PostConstruct
    void loadSchema() throws IOException, SAXException {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        // Disable external DTDs/entities for safety
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (InputStream is = Files.newInputStream(props.xsdPath())) {
            this.schema = factory.newSchema(new StreamSource(is));
        }
        log.info("Loaded XSD schema from {}", props.xsdPath());
    }

    /**
     * Validate the supplied XML bytes against the schema.
     *
     * @return list of diagnostics — empty when valid
     */
    public List<Diagnostic> validate(byte[] xml) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Validator validator = schema.newValidator();
        try {
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (SAXException e) {
            // Properties not supported by this parser impl — non-fatal, proceed.
        }
        validator.setErrorHandler(new CollectingHandler(diagnostics));
        try (InputStream is = new ByteArrayInputStream(xml)) {
            validator.validate(new StreamSource(is));
        } catch (SAXException | IOException e) {
            // A SAXException during validate() is already captured via the handler;
            // only IOExceptions here are unrecoverable stream problems.
            if (e instanceof SAXException se && diagnostics.isEmpty()) {
                diagnostics.add(new Diagnostic(se.getMessage(), -1, -1));
            } else if (e instanceof IOException) {
                diagnostics.add(new Diagnostic("I/O error: " + e.getMessage(), -1, -1));
            }
        }
        return diagnostics;
    }

    /** One XSD validation finding. */
    public record Diagnostic(String message, int line, int column) {
        public static Diagnostic of(SAXParseException ex) {
            return new Diagnostic(ex.getMessage(), ex.getLineNumber(), ex.getColumnNumber());
        }
    }

    /** Captures every error and fatalError reported by the validator. */
    private static final class CollectingHandler implements ErrorHandler {
        private final List<Diagnostic> sink;

        CollectingHandler(List<Diagnostic> sink) {
            this.sink = sink;
        }

        @Override
        public void warning(SAXParseException exception) {
            sink.add(Diagnostic.of(exception));
        }

        @Override
        public void error(SAXParseException exception) {
            sink.add(Diagnostic.of(exception));
        }

        @Override
        public void fatalError(SAXParseException exception) {
            sink.add(Diagnostic.of(exception));
        }
    }
}
