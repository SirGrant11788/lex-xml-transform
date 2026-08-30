package com.lexisnexis.transform.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexisnexis.transform.config.LexIngestProperties;
import com.lexisnexis.transform.model.NormalizedJudgment;
import jakarta.annotation.PostConstruct;
import net.sf.saxon.s9api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Saxon-HE backed XSLT 3.0 transformer.
 *
 * <p>The pipeline:
 * <ol>
 *   <li>Compiled {@link XsltExecutable} is held as a singleton — Saxon
 *       compilations are expensive and the stylesheet is immutable per process.</li>
 *   <li>Saxon runs the stylesheet over the validated XML.</li>
 *   <li>The primary output is an intermediate XML record with predictable shape.</li>
 *   <li>The intermediate XML is parsed via the JDK DOM and walked to produce the
 *       canonical {@link NormalizedJudgment}.</li>
 *   <li>The plain-text RAG view is captured from a secondary
 *       {@code xsl:result-document} and stored in the {@code full_text} field.</li>
 * </ol>
 *
 * <p><strong>Why DOM walking instead of Jackson XmlMapper?</strong>
 * Saxon-HE 12 does not include the JSON-output emitter from Saxon-PE/EE,
 * and Jackson XmlMapper handles our mixed text/element list shape awkwardly.
 * The intermediate XML is small (one document → one record) so a direct
 * DOM walk is the most reliable path.</p>
 */
@Component
public class SaxonTransformer {
    private static final Logger log = LoggerFactory.getLogger(SaxonTransformer.class);

    private final LexIngestProperties props;
    private final ObjectMapper mapper;

    private Processor processor;
    private XsltExecutable executable;
    private DocumentBuilderFactory domFactory;

    public SaxonTransformer(LexIngestProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @PostConstruct
    void compile() throws Exception {
        this.processor = new Processor(false);
        try (InputStream is = Files.newInputStream(props.xsltPath())) {
            XsltCompiler compiler = processor.newXsltCompiler();
            this.executable = compiler.compile(new StreamSource(is));
        }
        // Secure DOM factory: disable external entities / DTDs.
        this.domFactory = DocumentBuilderFactory.newInstance();
        domFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        domFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        domFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        domFactory.setExpandEntityReferences(false);
        log.info("Compiled XSLT stylesheet from {}", props.xsltPath());
    }

    /**
     * Run the stylesheet against the supplied XML bytes.
     *
     * @param validatedXml bytes of XML already validated against the XSD
     * @return transformed {@link NormalizedJudgment} with {@code full_text} populated
     */
    public NormalizedJudgment transform(byte[] validatedXml) throws SaxonApiException {
        Xslt30Transformer transformer = executable.load30();

        // Capture primary output (intermediate XML).
        StringWriter xmlWriter = new StringWriter();
        Serializer xmlSerializer = processor.newSerializer(xmlWriter);
        xmlSerializer.setOutputProperty(Serializer.Property.METHOD, "xml");
        xmlSerializer.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes");

        // Capture the text sink for the stylesheet's xsl:result-document.
        StringWriter textWriter = new StringWriter();
        Serializer textSerializer = processor.newSerializer(textWriter);
        textSerializer.setOutputProperty(Serializer.Property.METHOD, "text");
        transformer.setResultDocumentHandler(
                new StringResultDocumentHandler("text:full", textSerializer));

        InputStream is = new ByteArrayInputStream(validatedXml);
        try {
            transformer.transform(new StreamSource(is), xmlSerializer);
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {
                // ByteArrayInputStream close is a no-op.
            }
        }

        String intermediateXml = xmlWriter.toString();
        String fullText = textWriter.toString();

        try {
            return parseIntermediate(intermediateXml, fullText);
        } catch (Exception e) {
            throw new SaxonApiException("Failed to parse transformed XML: " + e.getMessage());
        }
    }

    /**
     * Walk the intermediate XML and produce a {@link NormalizedJudgment}.
     * The intermediate XML has the shape produced by {@code judgment-to-json.xsl}:
     *
     * <pre>{@code
     * <normalized>
     *   <contentId>...</contentId>
     *   <title>...</title>
     *   ...
     *   <citations>
     *     <citation><type>...</type><value>...</value></citation>
     *     ...
     *   </citations>
     *   ...
     * </normalized>
     * }</pre>
     */
    private NormalizedJudgment parseIntermediate(String xml, String fullText) throws Exception {
        DocumentBuilder builder = domFactory.newDocumentBuilder();
        // Saxon may emit the namespace declaration on the root; DOM handles it transparently.
        Element root;
        try (InputStream is = new ByteArrayInputStream(xml.getBytes())) {
            root = builder.parse(new InputSource(is)).getDocumentElement();
        }

        return new NormalizedJudgment(
                textOf(root, "contentId"),
                textOf(root, "title"),
                textOf(root, "court"),
                textOf(root, "jurisdiction"),
                parseDate(textOf(root, "decisionDate")),
                parseCitations(child(root, "citations")),
                parseParties(child(root, "parties")),
                parseParagraphs(child(root, "paragraphs")),
                fullText
        );
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String textOf(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().trim();
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            // Fall back to ISO_LOCAL_DATE; if still invalid, surface as null.
            return null;
        }
    }

    private static List<NormalizedJudgment.Citation> parseCitations(Element wrapper) {
        List<NormalizedJudgment.Citation> out = new ArrayList<>();
        if (wrapper == null) return out;
        NodeList children = wrapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "citation".equals(n.getNodeName())) {
                Element c = (Element) n;
                out.add(new NormalizedJudgment.Citation(
                        textOf(c, "type"),
                        textOf(c, "value")));
            }
        }
        return out;
    }

    private static List<NormalizedJudgment.Party> parseParties(Element wrapper) {
        List<NormalizedJudgment.Party> out = new ArrayList<>();
        if (wrapper == null) return out;
        NodeList children = wrapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "party".equals(n.getNodeName())) {
                Element p = (Element) n;
                out.add(new NormalizedJudgment.Party(
                        textOf(p, "role"),
                        textOf(p, "name")));
            }
        }
        return out;
    }

    private static List<NormalizedJudgment.Paragraph> parseParagraphs(Element wrapper) {
        List<NormalizedJudgment.Paragraph> out = new ArrayList<>();
        if (wrapper == null) return out;
        NodeList children = wrapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "paragraph".equals(n.getNodeName())) {
                Element pg = (Element) n;
                out.add(new NormalizedJudgment.Paragraph(
                        textOf(pg, "id"),
                        textOf(pg, "section"),
                        textOf(pg, "text")));
            }
        }
        return out;
    }
}
