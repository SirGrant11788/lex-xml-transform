# lex-xml-transform

Production-style Spring Boot service that ingests legal XML documents,
validates them against the LexisNexis judgment XSD, transforms them with
Saxon-HE XSLT 3.0 into a normalized JSON record, and publishes the
artifact locally. Supports single-document and batch ingestion, with
configurable concurrency, idempotency, structured metrics, and
health/readiness endpoints. Container-ready.

---

## TL;DR

```bash
mvn clean verify          # build + tests
mvn spring-boot:run       # run locally on :8080

# In another shell
curl -X POST -H 'Content-Type: application/xml' \
  --data-binary @examples/sample-judgment.xml \
  http://localhost:8080/api/v1/documents
```

---

## Stack

| Layer        | Choice                                         |
|--------------|------------------------------------------------|
| Runtime      | Java 21 (assignment says 17+; 21 is current LTS) |
| Framework    | Spring Boot 3.3.x                              |
| XSLT engine  | Saxon-HE 12.5 (XSLT 3.0)                       |
| JSON         | Jackson 2.17                                   |
| Build        | Maven 3.9                                      |
| Container    | Docker (multi-stage, JRE-only runtime)         |

---

## Quick start

### Prerequisites

- JDK 17 or newer (tested on 21)
- Maven 3.8+
- Docker (only for the container run)

### Local run

```bash
# Build
mvn -DskipTests package

# Run
java -jar target/lex-xml-transform-1.0.0.jar
# or
mvn spring-boot:run
```

The service starts on `http://localhost:8080`. Health is at
`/actuator/health`, metrics at `/actuator/metrics`.

### Run with custom config (env vars)

```bash
SERVER_PORT=8090 \
LEX_INGEST_OUTPUT_DIR=/var/lib/lex/out \
LEX_INGEST_BATCH_CONCURRENCY=8 \
java -jar target/lex-xml-transform-1.0.0.jar
```

All settings in `application.yml` are env-overridable via Spring's
relaxed binding (`LEX_INGEST_*`).

### Docker

```bash
docker build -t lex-xml-transform:1.0.0 .
docker run --rm -p 8080:8080 lex-xml-transform:1.0.0
```

The image runs as non-root `lex` (UID 1001) and has a HEALTHCHECK
pointing at `/actuator/health`.

---

## API

All endpoints return JSON. Bodies are camelCase / snake_case according to
the global Jackson setting (`snake_case` on the wire).

### `POST /api/v1/documents`

Submit a single XML document.

```bash
curl -X POST -H 'Content-Type: application/xml' \
  --data-binary @examples/sample-judgment.xml \
  http://localhost:8080/api/v1/documents
```

**Response (HTTP 202):**
```json
{
  "status": "PUBLISHED",
  "content_id": "FR-2024-CA-000123",
  "message": "Published",
  "artifact_path": "./out/valid/FR-2024-CA-000123.json",
  "sha256": "2e9d50882fdb901770dd5354a1dbc3b7a3440c3b77db24ad4dd909f631c12bee",
  "diagnostics": []
}
```

`status` is one of `PUBLISHED`, `DUPLICATE`, `INVALID`. Resubmitting the
same document returns `DUPLICATE` (no side effects). Invalid XML returns
`INVALID` with `diagnostics[]` describing each XSD violation; the bad
document is parked under `./out/invalid/`.

### `POST /api/v1/documents/batch`

Two shapes:

**(a) Reference a server-side folder:**
```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"folder_path":"/absolute/path/to/xmls"}' \
  http://localhost:8080/api/v1/documents/batch
```

**(b) Upload a zip of XML files:**
```bash
curl -X POST -F 'file=@batch.zip' \
  http://localhost:8080/api/v1/documents/batch
```

**Response (HTTP 200):**
```json
{
  "total": 4,
  "published": 3,
  "invalid": 1,
  "duplicate": 0,
  "failed": 0,
  "files": ["/path/to/xml/01.xml", "..."]
}
```

Files are processed concurrently — pool size is
`LEX_INGEST_BATCH_CONCURRENCY` (default 4).

### `GET /api/v1/documents/{contentId}`

Retrieve the status and artifact location for a previously submitted
document.

```bash
curl http://localhost:8080/api/v1/documents/FR-2024-CA-000123
```

**Response (HTTP 200):**
```json
{
  "content_id": "FR-2024-CA-000123",
  "status": "PUBLISHED",
  "artifact_path": "./out/valid/FR-2024-CA-000123.json",
  "sha256": "2e9d50882fdb901770dd5354a1dbc3b7a3440c3b77db24ad4dd909f631c12bee"
}
```

**HTTP 404** when the `content_id` is unknown.

### Actuator

| Endpoint                        | Purpose                              |
|---------------------------------|--------------------------------------|
| `GET /actuator/health`          | Overall health (`UP`/`DOWN`)         |
| `GET /actuator/health/liveness` | Kubernetes-style liveness probe      |
| `GET /actuator/health/readiness`| Kubernetes-style readiness probe     |
| `GET /actuator/metrics`         | List metric names                    |
| `GET /actuator/metrics/{name}`  | One metric, with measurements + tags |

Custom metrics under `lex.ingest.*`:
- `lex.ingest.documents.received`
- `lex.ingest.documents.valid`
- `lex.ingest.documents.invalid`
- `lex.ingest.documents.published`
- `lex.ingest.documents.duplicates`
- `lex.ingest.documents.by_status{status=...}`
- `lex.ingest.errors{phase=transform|publish}`
- `lex.ingest.validation.duration` (timer)
- `lex.ingest.transform.duration` (timer)

---

## Output layout

```
out/
├── manifest.json
├── valid/
│   ├── FR-2024-CA-000123.json
│   ├── FR-2024-CA-000123.txt
│   └── ...
└── invalid/
    └── <content_id-or-fallback>.errors.json
```

- `valid/<content_id>.json` — normalized JSON record
- `valid/<content_id>.txt` — concatenated paragraph text (for AI / RAG)
- `invalid/<...>.errors.json` — diagnostic list for invalid input
- `manifest.json` — `{entries: [{content_id, sha256, published_at}]}`

Idempotency: re-submitting the same `content_id` with byte-identical
content is suppressed (no overwrite, no duplicate manifest entry). A
re-submission with **different** bytes under the same id overwrites the
artifact and updates the manifest (with a WARN-level log).

---

## Configuration reference

| Property                            | Env var                       | Default                       | Notes                              |
|-------------------------------------|-------------------------------|-------------------------------|------------------------------------|
| `server.port`                       | `SERVER_PORT`                 | `8080`                        |                                    |
| `lex.ingest.xsd-path`               | `LEX_INGEST_XSD_PATH`         | `schemas/judgment.xsd`        |                                    |
| `lex.ingest.xslt-path`              | `LEX_INGEST_XSLT_PATH`        | `xslt/judgment-to-json.xsl`   |                                    |
| `lex.ingest.output-dir`             | `LEX_INGEST_OUTPUT_DIR`       | `./out`                       | Created if missing                 |
| `lex.ingest.batch-concurrency`      | `LEX_INGEST_BATCH_CONCURRENCY`| `4`                           | Threads for batch processing       |
| `lex.ingest.max-xml-bytes`          | `LEX_INGEST_MAX_XML_BYTES`    | `26214400` (25 MiB)           | Per-document cap                   |
| `spring.servlet.multipart.max-file-size` | `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `100MB`        | Zip batch upload limit             |

---

## Development

```bash
mvn test                    # unit + integration tests
mvn verify                  # full build + integration + package

# Quick manual test
mvn spring-boot:run &
sleep 5
curl -X POST -H 'Content-Type: application/xml' \
  --data-binary @examples/sample-judgment.xml \
  http://localhost:8080/api/v1/documents
curl http://localhost:8080/api/v1/documents/FR-2024-CA-000123
```

### Project layout

```
src/main/java/com/lexisnexis/transform/
├── LexXmlTransformApplication.java     # Spring Boot entrypoint
├── api/                                # REST controllers + DTOs
├── config/                             # @ConfigurationProperties + Jackson
├── ingest/                             # IngestService orchestration
├── publish/                            # FileSystemPublisher (idempotency)
├── transform/                          # SaxonTransformer (XSLT 3.0)
├── validate/                           # XsdValidator (SAX-based)
├── metrics/                            # Micrometer counters/timers
└── model/                              # NormalizedJudgment record
src/main/resources/
├── application.yml
├── schemas/judgment.xsd                # Source of truth for input shape
└── xslt/judgment-to-json.xsl           # XSLT 3.0 transform
examples/
├── sample-judgment.xml                 # Mirrors the assignment example
└── sample-batch/                       # 3 valid + 1 invalid document
```

---

## See also

- `SOLUTION.md` — design decisions, AWS deployment plan, RAG evolution
- `intent.md` — original specification the implementation was built against
