package com.lexisnexis.transform.ingest;

import com.lexisnexis.transform.metrics.IngestMetrics;
import com.lexisnexis.transform.model.DocumentStatus;
import com.lexisnexis.transform.model.NormalizedJudgment;
import com.lexisnexis.transform.publish.FileSystemPublisher;
import com.lexisnexis.transform.transform.SaxonTransformer;
import com.lexisnexis.transform.validate.XsdValidator;
import io.micrometer.core.instrument.Timer;
import net.sf.saxon.s9api.SaxonApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the four-step pipeline:
 *   validate → transform → publish
 *
 * <p>Single-document flow is synchronous (caller is the HTTP thread); batch
 * flow fans out across a fixed-size thread pool. Every public method returns
 * an {@link IngestOutcome} so the caller can render an appropriate response
 * without re-parsing the document.</p>
 */
@Service
public class IngestService {
    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final XsdValidator validator;
    private final SaxonTransformer transformer;
    private final FileSystemPublisher publisher;
    private final IngestMetrics metrics;
    private final ExecutorService batchPool;

    public IngestService(XsdValidator validator,
                         SaxonTransformer transformer,
                         FileSystemPublisher publisher,
                         IngestMetrics metrics,
                         com.lexisnexis.transform.config.LexIngestProperties props) {
        this.validator = validator;
        this.transformer = transformer;
        this.publisher = publisher;
        this.metrics = metrics;
        this.batchPool = Executors.newFixedThreadPool(props.batchConcurrency(), r -> {
            Thread t = new Thread(r, "lex-ingest-worker");
            t.setDaemon(true);
            return t;
        });
        log.info("IngestService batch pool ready (concurrency={})", props.batchConcurrency());
    }

    /** Process a single XML document end-to-end. */
    public IngestOutcome process(byte[] xml) {
        metrics.documentsReceived().increment();

        // Stream-parse to surface content_id without loading the whole tree
        String contentId = peekContentId(xml);

        Timer.Sample validateSample = Timer.start();
        List<XsdValidator.Diagnostic> diagnostics = validator.validate(xml);
        validateSample.stop(metrics.validationTimer());

        if (!diagnostics.isEmpty() || contentId == null) {
            metrics.invalid().increment();
            String fileId = contentId != null ? contentId : "unknown-" + System.nanoTime();
            List<String> messages = new ArrayList<>();
            if (contentId == null) messages.add("Missing <content_id>");
            for (var d : diagnostics) {
                messages.add(String.format("line %d col %d: %s", d.line(), d.column(), d.message()));
            }
            try {
                publisher.recordInvalid(fileId, messages);
            } catch (IOException ioe) {
                log.error("Failed to record invalid doc {}: {}", fileId, ioe.getMessage());
            }
            metrics.documentsByStatus(DocumentStatus.INVALID).increment();
            return IngestOutcome.invalid(fileId, messages);
        }

        metrics.valid().increment();

        Timer.Sample transformSample = Timer.start();
        NormalizedJudgment judgment;
        try {
            judgment = transformer.transform(xml);
        } catch (SaxonApiException e) {
            log.error("Transform failed for content_id={}: {}", contentId, e.getMessage());
            metrics.transformFailures().increment();
            return IngestOutcome.failed(contentId, "Transform failed: " + e.getMessage());
        }
        transformSample.stop(metrics.transformTimer());

        try {
            var result = publisher.publish(judgment);
            if (result instanceof FileSystemPublisher.Duplicate dup) {
                metrics.duplicates().increment();
                metrics.documentsByStatus(DocumentStatus.DUPLICATE).increment();
                return IngestOutcome.duplicate(judgment.contentId(), dup.existingSha256());
            }
            metrics.published().increment();
            metrics.documentsByStatus(DocumentStatus.PUBLISHED).increment();
            var p = (FileSystemPublisher.Published) result;
            return IngestOutcome.published(judgment.contentId(), p.jsonPath().toString(), p.sha256());
        } catch (IOException e) {
            log.error("Publish failed for content_id={}: {}", contentId, e.getMessage());
            metrics.publishFailures().increment();
            return IngestOutcome.failed(contentId, "Publish failed: " + e.getMessage());
        }
    }

    /** Process every {@code *.xml} file under {@code folder} concurrently. */
    public BatchOutcome processFolder(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            throw new IOException("Not a directory: " + folder);
        }
        List<Path> files;
        try (var stream = Files.list(folder)) {
            files = stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xml"))
                    .sorted()
                    .toList();
        }
        if (files.isEmpty()) {
            return new BatchOutcome(0, 0, 0, 0, 0, List.of());
        }
        AtomicReference<Integer> published = new AtomicReference<>(0);
        AtomicReference<Integer> invalid = new AtomicReference<>(0);
        AtomicReference<Integer> duplicate = new AtomicReference<>(0);
        AtomicReference<Integer> failed = new AtomicReference<>(0);

        List<CompletableFuture<Void>> futures = files.stream()
                .map(file -> CompletableFuture.runAsync(() -> {
                    try {
                        byte[] bytes = Files.readAllBytes(file);
                        IngestOutcome outcome = process(bytes);
                        switch (outcome.status()) {
                            case PUBLISHED -> published.set(published.get() + 1);
                            case INVALID -> invalid.set(invalid.get() + 1);
                            case DUPLICATE -> duplicate.set(duplicate.get() + 1);
                            default -> failed.set(failed.get() + 1);
                        }
                    } catch (IOException e) {
                        log.error("Batch read failed for {}: {}", file, e.getMessage());
                        failed.set(failed.get() + 1);
                    }
                }, batchPool))
                .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return new BatchOutcome(files.size(), published.get(), invalid.get(), duplicate.get(),
                failed.get(), files.stream().map(Path::toString).toList());
    }

    /** Stream-parse just enough to read {@code <header>/<content_id>}. */
    private String peekContentId(byte[] xml) {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // Defensive: disable DTD/external entity expansion
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader r = null;
        try {
            r = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            boolean inHeader = false;
            boolean inContentId = false;
            while (r.hasNext()) {
                int evt = r.next();
                switch (evt) {
                    case XMLStreamReader.START_ELEMENT -> {
                        String local = r.getLocalName();
                        if ("header".equals(local)) inHeader = true;
                        else if (inHeader && "content_id".equals(local)) inContentId = true;
                    }
                    case XMLStreamReader.CHARACTERS -> {
                        if (inContentId) {
                            String text = r.getText().trim();
                            if (!text.isEmpty()) {
                                r.close();
                                return text;
                            }
                        }
                    }
                    case XMLStreamReader.END_ELEMENT -> {
                        String local = r.getLocalName();
                        if ("content_id".equals(local)) inContentId = false;
                        else if ("header".equals(local)) inHeader = false;
                    }
                    default -> {}
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            log.debug("peekContentId failed: {}", e.getMessage());
        }
        return null;
    }
}
