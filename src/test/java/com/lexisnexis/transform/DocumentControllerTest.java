package com.lexisnexis.transform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST-layer integration test. Exercises the controller via TestRestTemplate
 * against a randomly-assigned port so the test does not clash with a
 * developer-run server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentControllerTest {

    @Autowired TestRestTemplate http;
    @LocalServerPort int port;

    @Test
    void singleIngestThenStatusReturns202Then200() throws Exception {
        byte[] xml = Files.readAllBytes(Path.of("examples/sample-judgment.xml"));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<String> ingest = http.exchange(
                "/api/v1/documents", HttpMethod.POST, new HttpEntity<>(xml, h), String.class);

        assertThat(ingest.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ingest.getBody()).contains("\"PUBLISHED\"").contains("FR-2024-CA-000123");

        ResponseEntity<String> status = http.getForEntity(
                "/api/v1/documents/FR-2024-CA-000123", String.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).contains("\"content_id\":\"FR-2024-CA-000123\"");
    }

    @Test
    void statusReturns404ForUnknownContentId() {
        ResponseEntity<String> status = http.getForEntity(
                "/api/v1/documents/UNKNOWN-XXX", String.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidXmlReturnsAcceptedWithInvalidStatus() throws Exception {
        byte[] xml = Files.readAllBytes(Path.of("examples/sample-batch/04-invalid.xml"));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<String> ingest = http.exchange(
                "/api/v1/documents", HttpMethod.POST, new HttpEntity<>(xml, h), String.class);

        assertThat(ingest.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ingest.getBody()).contains("\"INVALID\"");
    }

    @Test
    void actuatorHealthExposesLiveness() {
        ResponseEntity<String> health = http.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
