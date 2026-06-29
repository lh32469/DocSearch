# DocService

Spring Boot 4.0.6 web service (Java 25) for ingesting, OCR-transcribing, and
full-text searching scanned public-record documents (easements, permits, deeds,
etc.). Documents are stored in RavenDB; rendered page images are kept as RavenDB
attachments. Transcription is performed by AI vision models (Anthropic Claude
and Google Gemini).

## Architecture

```
Browser ──► SearchController (Thymeleaf UI)
              └─► SearchService (RQL full-text query, Caffeine cache)

HTTP client ──► DocumentController
                  POST /api/stage           — ingest PDF or image file

Background tasks (k8s profile only)
  ClockScheduler       — schedules PageRefreshTaskKey instances across up to 8
                         Gemini API keys, each in its own 3-hour window
  PageRefreshTaskKey   — Gemini OCR for un-transcribed pages (every minute
                         within the key's window)
  AnthropicPageRefreshTask — Anthropic Claude retry for RECITATION errors
                             (every 10 minutes)
  SummaryTask          — AI-generated transcription progress summary (hourly)
```

**Data model:** each document is a `DocSearchDoc` stored under the key
`Doc/<filename>` in the `DocSearchDocs` RavenDB collection. Per-page OCR
results are held in a `List<DocSearchPage>` on the document; rendered page
images are stored as named attachments (`page-1.png`, `page-2.png`, …).

## Ports

| Port | Purpose |
|------|---------|
| 8085 | Application HTTP |
| 8090 | Spring Boot Actuator (all endpoints exposed) |

## Endpoints

### UI (Thymeleaf)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Redirects to `/search` |
| `GET` | `/search` | Full-text search UI; accepts `q` (query) and `page` params |
| `GET` | `/document` | Document viewer — all pages for one doc; accepts `docId` and `q` |
| `GET` | `/document/attachment` | Single-page viewer with prev/next navigation |

### API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/stage` | Ingest a PDF or image; renders pages to PNG attachments |
| `GET` | `/api/document/attachment` | Stream a page-image attachment (`docId` + `name` params) |

#### `POST /api/stage`

Accepts a scanned document, converts it to PNG page images, and stores the
`DocSearchDoc` in RavenDB. AI transcription runs asynchronously via background
tasks. PDF pages are rendered at 150 DPI; image files are treated as
single-page documents.

**Supported file types:** `.pdf`, `.png`, `.jpg` / `.jpeg`, `.gif`

```bash
curl -F "file=@document.pdf" http://localhost:8085/api/stage
```

## Configuration

| Property | Description |
|----------|-------------|
| `ravendb.urls` | Comma-separated RavenDB node URLs (injected via `RAVENDB_URLS` Jenkins global property) |
| `ravendb.database` | RavenDB database name (default: `DocSearch`) |
| `JASYPT_ENCRYPTOR_PASSWORD` | Decryption key for `ENC(...)` values in `application.yaml` |
| `anthropic.api.key` | Anthropic Claude API key (Jasypt-encrypted in config) |
| `openai.api.key` | OpenAI API key (Jasypt-encrypted in config) |
| `page-refresh.fixed-rate` | PageRefreshTask polling interval in seconds (default: 60) |
| `clock-scheduler.user` | Username of the `GeminiApiKeys` RavenDB document supplying up to 8 Gemini keys |

Sensitive values in `application.yaml` are wrapped as `ENC(...)` and decrypted
at startup by [Jasypt](https://github.com/ulisesbocchio/jasypt-spring-boot).

## Building and Running

```bash
# Build
./mvnw clean package

# Run locally
./mvnw spring-boot:run

# Run tests
./mvnw test

# Check / apply code formatting (Spotless)
./mvnw verify -DskipTests
./mvnw spotless:apply
```

## Docker / Kubernetes

```bash
./mvnw clean package
docker build -t easementservice:latest .
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/deployment.yaml
```

The deployment mounts `easementservice-config` as a ConfigMap at
`/usr/src/config`. The container runs with Shenandoah GC, 2 GB heap, and a
2-CPU limit.

## Profiles

| Profile | Effect |
|---------|--------|
| `k8s` | Enables `ClockScheduler`, `AnthropicPageRefreshTask`, and `SummaryTask` |
| `test` | Used by integration tests; overrides `ravendb.urls` inline |
