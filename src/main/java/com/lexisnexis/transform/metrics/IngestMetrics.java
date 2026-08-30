package com.lexisnexis.transform.metrics;

import com.lexisnexis.transform.model.DocumentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Centralised Micrometer instruments. All metrics live under the
 * {@code lex.ingest.*} prefix and are exposed at {@code /actuator/metrics}.
 */
@Component
public class IngestMetrics {

    private final MeterRegistry registry;
    private final Counter documentsReceived;
    private final Counter valid;
    private final Counter invalid;
    private final Counter published;
    private final Counter duplicates;
    private final Counter transformFailures;
    private final Counter publishFailures;
    private final Timer validationTimer;
    private final Timer transformTimer;
    private final Map<DocumentStatus, Counter> byStatus;

    public IngestMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.documentsReceived = Counter.builder("lex.ingest.documents.received")
                .description("Total documents received by the ingest API")
                .register(registry);
        this.valid = Counter.builder("lex.ingest.documents.valid")
                .description("Documents that passed XSD validation")
                .register(registry);
        this.invalid = Counter.builder("lex.ingest.documents.invalid")
                .description("Documents that failed XSD validation")
                .register(registry);
        this.published = Counter.builder("lex.ingest.documents.published")
                .description("Documents published as normalized artifacts")
                .register(registry);
        this.duplicates = Counter.builder("lex.ingest.documents.duplicates")
                .description("Re-submissions suppressed by idempotency")
                .register(registry);
        this.transformFailures = Counter.builder("lex.ingest.errors")
                .tag("phase", "transform")
                .register(registry);
        this.publishFailures = Counter.builder("lex.ingest.errors")
                .tag("phase", "publish")
                .register(registry);
        this.validationTimer = Timer.builder("lex.ingest.validation.duration")
                .description("Time spent validating XML against the XSD")
                .register(registry);
        this.transformTimer = Timer.builder("lex.ingest.transform.duration")
                .description("Time spent running the XSLT transform")
                .register(registry);
        this.byStatus = new EnumMap<>(DocumentStatus.class);
        for (DocumentStatus s : DocumentStatus.values()) {
            byStatus.put(s, Counter.builder("lex.ingest.documents.by_status")
                    .tag("status", s.name())
                    .register(registry));
        }
    }

    public Counter documentsReceived() { return documentsReceived; }
    public Counter valid() { return valid; }
    public Counter invalid() { return invalid; }
    public Counter published() { return published; }
    public Counter duplicates() { return duplicates; }
    public Counter transformFailures() { return transformFailures; }
    public Counter publishFailures() { return publishFailures; }
    public Timer validationTimer() { return validationTimer; }
    public Timer transformTimer() { return transformTimer; }

    public Counter documentsByStatus(DocumentStatus status) {
        return byStatus.get(status);
    }

    public MeterRegistry registry() { return registry; }
}
