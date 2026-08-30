package com.lexisnexis.transform;

import com.lexisnexis.transform.config.LexIngestProperties;
import com.lexisnexis.transform.ingest.BatchOutcome;
import com.lexisnexis.transform.ingest.IngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BatchProcessingTest {

    @Autowired IngestService ingest;
    @Autowired LexIngestProperties props;

    @BeforeEach
    void cleanOutput() throws IOException {
        if (Files.exists(props.outputPath())) {
            try (var s = Files.walk(props.outputPath())) {
                s.filter(Files::isRegularFile).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    @Test
    void batchFolderProcessesAllValidAndRecordsInvalid(@TempDir Path scratch) throws Exception {
        // Copy the sample batch into the scratch folder
        Path sampleBatch = Path.of("examples/sample-batch");
        try (var s = Files.list(sampleBatch)) {
            s.forEach(src -> {
                try {
                    Files.copy(src, scratch.resolve(src.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        BatchOutcome outcome = ingest.processFolder(scratch);
        assertThat(outcome.total()).isEqualTo(4);
        assertThat(outcome.published()).isEqualTo(3);
        assertThat(outcome.invalid()).isEqualTo(1);
        assertThat(outcome.failed()).isZero();
        assertThat(outcome.duplicate()).isZero();
    }

    @Test
    void duplicateBatchReSubmissionIsSuppressed(@TempDir Path scratch) throws Exception {
        Path sampleBatch = Path.of("examples/sample-batch");
        try (var s = Files.list(sampleBatch)) {
            s.filter(p -> p.getFileName().toString().endsWith(".xml")
                    && !p.getFileName().toString().contains("invalid"))
                    .forEach(src -> {
                        try {
                            Files.copy(src, scratch.resolve(src.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }

        BatchOutcome first = ingest.processFolder(scratch);
        assertThat(first.published()).isEqualTo(3);

        BatchOutcome second = ingest.processFolder(scratch);
        assertThat(second.total()).isEqualTo(3);
        assertThat(second.published()).isZero();
        assertThat(second.duplicate()).isEqualTo(3);
    }
}
