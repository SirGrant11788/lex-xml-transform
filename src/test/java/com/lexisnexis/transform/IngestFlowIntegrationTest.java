package com.lexisnexis.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexisnexis.transform.config.LexIngestProperties;
import com.lexisnexis.transform.ingest.IngestOutcome;
import com.lexisnexis.transform.ingest.IngestService;
import com.lexisnexis.transform.publish.FileSystemPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that wires the full Spring context and exercises the
 * happy path, duplicate submission, and invalid input.
 */
@SpringBootTest
@ActiveProfiles("default")
class IngestFlowIntegrationTest {

    @Autowired IngestService ingestService;
    @Autowired FileSystemPublisher publisher;
    @Autowired ObjectMapper mapper;
    @Autowired LexIngestProperties props;

    @BeforeEach
    void cleanOutput() throws IOException {
        // Clear any state from prior runs so duplicates are detectable.
        if (Files.exists(props.outputPath())) {
            try (var s = Files.walk(props.outputPath())) {
                s.filter(Files::isRegularFile).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    @Test
    void happyPathPublishesNormalizedArtifact() throws IOException {
        byte[] xml = Files.readAllBytes(Path.of("examples/sample-judgment.xml"));
        IngestOutcome out = ingestService.process(xml);

        assertThat(out.status().name()).isEqualTo("PUBLISHED");
        assertThat(out.contentId()).isEqualTo("FR-2024-CA-000123");
        assertThat(out.artifactPath()).isNotNull();
        assertThat(out.sha256()).isNotBlank().hasSize(64);

        Path published = Path.of(out.artifactPath());
        assertThat(Files.exists(published)).isTrue();

        // Verify the JSON document round-trips into the domain model
        var judgment = mapper.readValue(published.toFile(),
                com.lexisnexis.transform.model.NormalizedJudgment.class);
        assertThat(judgment.citations()).hasSize(2);
        assertThat(judgment.citations().get(0).type()).isEqualTo("ECLI");
        assertThat(judgment.paragraphs()).hasSize(4);
        assertThat(judgment.fullText()).contains("litige");
    }

    @Test
    void duplicateSubmissionIsSuppressed() throws IOException {
        byte[] xml = Files.readAllBytes(Path.of("examples/sample-judgment.xml"));
        IngestOutcome first = ingestService.process(xml);
        assertThat(first.status().name()).isEqualTo("PUBLISHED");

        IngestOutcome second = ingestService.process(xml);
        assertThat(second.status().name()).isEqualTo("DUPLICATE");
        assertThat(second.sha256()).isEqualTo(first.sha256());
    }

    @Test
    void invalidXmlDoesNotPublish() throws IOException {
        byte[] xml = Files.readAllBytes(Path.of("examples/sample-batch/04-invalid.xml"));
        IngestOutcome out = ingestService.process(xml);

        assertThat(out.status().name()).isEqualTo("INVALID");
        assertThat(out.diagnostics()).isNotEmpty();
    }
}
