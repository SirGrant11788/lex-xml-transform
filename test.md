# Test Data & API Test Cases

Everything here is copy-paste ready for **Swagger UI** or `curl`. The service
must be running first (see README): `mvn spring-boot:run` on `:8080`, or the
packaged jar.

## Swagger UI endpoints

| Path | What |
|------|------|
| `http://localhost:8080/swagger-ui.html` | Interactive Swagger UI (try it out in the browser) |
| `http://localhost:8080/v3/api-docs` | Raw OpenAPI 3 JSON spec |
| `http://localhost:8080/actuator/health` | Health / readiness |

> In Swagger UI: pick an operation → **Try it out** → paste a payload → **Execute**.
> For the `POST /api/v1/documents` (single) endpoint, set the request body type
> to raw XML and paste one of the documents below.

---

## Test Case 1 — Ingest a valid document (happy path)

**Endpoint:** `POST /api/v1/documents` — `Content-Type: application/xml`

**Request body:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<judgment xmlns="urn:lex:content:1">
    <header>
        <content_id>FR-2024-CA-000123</content_id>
        <title>Cour d'appel de Paris, 12 mars 2024, n° 20/01234</title>
        <court>Cour d'appel de Paris</court>
        <jurisdiction>FR</jurisdiction>
        <decision_date>2024-03-12</decision_date>
        <citations>
            <citation type="ECLI">ECLI:FR:CA12345</citation>
            <citation type="NOR">NOR:ABCD1234567</citation>
        </citations>
        <parties>
            <party role="appellant">Société ABC</party>
            <party role="respondent">M. Dupont</party>
        </parties>
    </header>
    <body>
        <section type="facts">
            <p id="p1">Le litige porte sur l'exécution d'un contrat de fourniture conclu entre les parties le 15 juin 2021.</p>
        </section>
        <section type="reasons">
            <p id="p2">Considérant que la société ABC a manqué à son obligation de livraison dans les délais contractuels.</p>
            <p id="p3">Attendu que M. Dupont a subi un préjudice direct et certain du fait de cette inexécution.</p>
        </section>
        <section type="disposition">
            <p id="p4">Par ces motifs, la cour infirme partiellement le jugement et condamne la société ABC à payer la somme de 45 000 euros.</p>
        </section>
    </body>
</judgment>
```

**Expected response (HTTP 202):**
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

**curl:**
```bash
curl -X POST -H 'Content-Type: application/xml' \
  --data-binary @examples/sample-judgment.xml \
  http://localhost:8080/api/v1/documents
```

---

## Test Case 2 — Retrieve status + artifact

**Endpoint:** `GET /api/v1/documents/{contentId}`

**Path param:** `contentId = FR-2024-CA-000123`

**Expected response (HTTP 200):**
```json
{
  "content_id": "FR-2024-CA-000123",
  "status": "PUBLISHED",
  "artifact_path": "./out/valid/FR-2024-CA-000123.json",
  "sha256": "2e9d50882fdb901770dd5354a1dbc3b7a3440c3b77db24ad4dd909f631c12bee"
}
```

**curl:**
```bash
curl http://localhost:8080/api/v1/documents/FR-2024-CA-000123
```

---

## Test Case 3 — Duplicate submission (idempotency)

Submit Test Case 1's document **again** (same bytes).

**Expected response (HTTP 202):**
```json
{
  "status": "DUPLICATE",
  "content_id": "FR-2024-CA-000123",
  "message": "Duplicate — identical artifact already published",
  "sha256": "2e9d50882fdb901770dd5354a1dbc3b7a3440c3b77db24ad4dd909f631c12bee",
  "diagnostics": []
}
```

Verify no second file appeared under `out/valid/` and the manifest still has
exactly one entry for this id.

---

## Test Case 4 — Invalid document (XSD failure, no publish)

**Endpoint:** `POST /api/v1/documents` — `Content-Type: application/xml`

**Request body (deliberately broken — missing `content_id`, bad date, no body):**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<judgment xmlns="urn:lex:content:1">
    <header>
        <title>This will fail XSD validation</title>
        <court>Test Court</court>
        <jurisdiction>FR</jurisdiction>
        <decision_date>not-a-date</decision_date>
    </header>
</judgment>
```

**Expected response (HTTP 202, status INVALID, diagnostics populated):**
```json
{
  "status": "INVALID",
  "content_id": "unknown-<timestamp>",
  "message": "Invalid — failed XSD validation",
  "diagnostics": [
    "Missing <content_id>",
    "line 5 col 16: cvc-complex-type.2.4.a: Invalid content was found starting with element '{urn:lex:content:1}:title'.",
    "line 8 col 50: cvc-datatype-valid.1.2.1: 'not-a-date' is not a valid value for 'date'.",
    "line 10 col 12: cvc-complex-type.2.4.b: The content of element 'judgment' is not complete. One of '{urn:lex:content:1}:body' is expected."
  ]
}
```

Verify nothing was written under `out/valid/` — only `out/invalid/<...>.errors.json`.

**curl:**
```bash
curl -X POST -H 'Content-Type: application/xml' \
  --data-binary @examples/sample-batch/04-invalid.xml \
  http://localhost:8080/api/v1/documents
```

---

## Test Case 5 — Unknown content_id (404)

**Endpoint:** `GET /api/v1/documents/DOES-NOT-EXIST`

**Expected:** HTTP `404 Not Found`

**curl:**
```bash
curl -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/documents/DOES-NOT-EXIST
```

---

## Test Case 6 — Batch (server-side folder)

**Endpoint:** `POST /api/v1/documents/batch` — `Content-Type: application/json`

**Request body (note: snake_case `folder_path`):**
```json
{
  "folder_path": "/absolute/path/to/lexisnexis-xml-transform/examples/sample-batch"
}
```

**Expected response (HTTP 200):**
```json
{
  "total": 4,
  "published": 3,
  "invalid": 1,
  "duplicate": 0,
  "failed": 0,
  "files": [
    ".../01-lyon.xml",
    ".../02-versailles.xml",
    ".../03-bordeaux.xml",
    ".../04-invalid.xml"
  ]
}
```

**curl:**
```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"folder_path":"/root/projects/lexisnexis-xml-transform/examples/sample-batch"}' \
  http://localhost:8080/api/v1/documents/batch
```

---

## Test Case 7 — Batch (zip upload)

**Endpoint:** `POST /api/v1/documents/batch` — `Content-Type: multipart/form-data`

**curl:**
```bash
cd examples/sample-batch && zip -j /tmp/batch.zip *.xml
curl -X POST -F 'file=@/tmp/batch.zip' \
  http://localhost:8080/api/v1/documents/batch
```

Same aggregate shape as Test Case 6.

---

## Test Case 8 — Metrics sanity

**Endpoint:** `GET /actuator/metrics/lex.ingest.documents.published`

**Expected:** a JSON counter with a `COUNT` measurement reflecting how many
documents have been published so far.

**curl:**
```bash
curl http://localhost:8080/actuator/metrics/lex.ingest.documents.published
curl http://localhost:8080/actuator/metrics/lex.ingest.documents.invalid
curl http://localhost:8080/actuator/metrics/lex.ingest.transform.duration
```

---

## Expected aggregate after running cases 1–8 once

| Metric | Value |
|--------|-------|
| documents.received | 7 |
| documents.valid | 5 |
| documents.invalid | 2 |
| documents.published | 4 |
| documents.duplicates | 1 |

*(These match the live curl smoke run during development; your numbers will
vary with how many times you re-run each case.)*
