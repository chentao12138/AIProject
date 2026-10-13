# OCR in Docker (F-10 / I-15)

> Scope: how PaddleOCR is deployed, limited, health-checked and what the
> application does when it is not there. Written for the operator of
> `deploy/docker-compose.yml`. Code under `server/src/main/java/com/aistudy/server/ingestion/ocr/`
> is the authority for the wire contract.

## 1. Shape

```text
docker compose (network aistudy_net)

  backend  (eclipse-temurin:21-jre, /app/server.jar)
     │  HTTP POST http://ocr:8891/ocr      { imageBase64, filename }
     │  HTTP GET  http://ocr:8891/healthz
     ▼
  ocr      (deploy/ocr: python:3.11-slim + paddlepaddle/paddleocr + ocr_sidecar.py)
     │  /home/aistudy-ocr/.paddleocr   ← named volume aistudy_ocr_models
     ▼
  models (detection + recognition, downloaded at build or first start)

  mysql    (unchanged)
```

The OCR runtime is **not** baked into the backend image. A PaddleOCR runtime plus
its models is 3–5 GB, needs its own CPU / memory / pid limits, and has a cold
start measured in tens of seconds — all of which belong to one dependency, not to
the whole API. As a sidecar it gets its own limits, its own restart policy, its
own bounded log stream and a health check that means "can serve", while a backend
redeploy keeps the model volume warm.

## 2. Deploy with OCR

```bash
cd deploy

# 1. OCR must be switched on for the backend, and the ocr profile started.
export AISTUDY_OCR_ENABLED=true
export AISTUDY_OCR_ENGINE=http
export AISTUDY_OCR_HTTP_BASE_URL=http://ocr:8891
# shared secret between backend and sidecar (required if you ever expose the sidecar)
export AISTUDY_OCR_HTTP_TOKEN=genera…here
export MYSQL_ROOT_PASSWORD=...        # existing required vars unchanged

docker compose --profile ocr build ocr     # 3-5 GB, needs network for the wheels
docker compose --profile ocr up -d

# 2. Wait for readiness (models load lazily; /healthz answers 503 until then).
docker compose ps ocr                      # STATUS should reach "healthy"
curl -s http://127.0.0.1:8891/healthz      # or from the backend container
```

`docker compose up -d` **without** `--profile ocr` also works and is the default
shape: `AISTUDY_OCR_ENABLED=false`, PDFs are read from their text layer, image
assets ingest with no text, and nothing queues waiting for a binary that is not
there.

## 3. Limits, logs, restart

| Concern | Setting | Default | Why it is a limit and not a tuning knob |
|---|---|---|---|
| OCR CPU | `AISTUDY_OCR_CPUS` (`deploy.resources.limits.cpus`) | `2.0` | paddle's native kernels otherwise take every core and starve the backend on the same host |
| OCR memory | `AISTUDY_OCR_MEMORY` | `4G` | the inference arena grows silently; a container kill is a cleaner failure than a host OOM |
| OCR processes | `pids_limit` | `256` | bounds native thread fan-out |
| Concurrency | `OCR_THREADS` / `OCR_QUEUE_DEPTH` | `1` / `8` | one predictor at a time; past the queue the sidecar answers **429**, it does not accept work it cannot finish in the caller's deadline |
| Backend per-run deadline | `AISTUDY_OCR_TIMEOUT` | `PT2M` | the OCR child / HTTP call is killed and the job fails `OCR_TIMEOUT`; without it one hung OCR occupied an ingestion worker until the job claim went stale (30 min) |
| stdout ceiling | `AISTUDY_OCR_MAX_STDOUT_BYTES` (`aistudy.ocr.max-stdout-bytes`) | `262144` | stream is drained to EOF (so the child cannot block) then the run fails `OCR_OUTPUT_TOO_LARGE` |
| result ceiling | `aistudy.ocr.max-output-bytes` / `aistudy.ocr.http.max-response-bytes` | `8MB` | the file/body is size-checked before it is read into the heap |
| log ceiling | `aistudy.ocr.max-diagnostics-chars` + compose `logging.max-size: 10m` | `2000` | failure diagnostics are truncated, single-line and have local paths masked; the previous behaviour dumped the whole stdout into `WARN` |
| restart | `restart: unless-stopped` on both services | — | a sidecar that cannot load its models exits non-zero (70) so the restart is visible instead of a process answering 503 forever |

Model storage is the `aistudy_ocr_models` volume mounted at
`/home/aistudy-ocr/.paddleocr`.

**Air-gapped hosts**: build with
`docker build --build-arg OCR_PRELOAD_MODELS=false deploy/ocr`, then fill the
model volume once from a host that already has the weights (`docker compose cp`
needs compose v2.3+):

```bash
# seeding host, after one normal start has downloaded the models:
docker compose --profile ocr cp ocr:/home/aistudy-ocr/.paddleocr ./paddleocr-models
# target host, with the ocr service stopped:
docker compose --profile ocr up -d ocr        # creates aistudy_ocr_models
docker compose --profile ocr stop ocr
docker compose --profile ocr cp ./paddleocr-models ocr:/home/aistudy-ocr/.paddleocr
docker compose --profile ocr up -d ocr
```

Or simply start the sidecar with outbound HTTPS once and let it fill the volume,
then remove the route.

## 4. Health and readiness

* Backend: `/actuator/health` gains detail id **`ocr`**
  (`OcrHealthIndicator`). It reports `UP` when the sidecar answers `/healthz`,
  and the custom status **`DEGRADED`** (with `reason` + `action`) when OCR is
  configured but unreachable. `DEGRADED` is deliberately not `DOWN`: Spring Boot
  ranks an unlisted custom status below `DOWN`, so a cold sidecar cannot flip the
  composite status and get the backend restarted — the failure mode this task had
  to avoid.
* `ocr` is **not** in the `readiness` group by default. To gate the whole API on
  OCR, add it to `management.endpoint.health.group.readiness.include` **and**
  set `AISTUDY_OCR_HEALTH_STRICT=true` (then the indicator reports `DOWN`).
* Sidecar: compose `healthcheck` curls `/healthz`, `start_period: 90s` to cover
  model loading. It returns `200` only after the models are loaded.
* There is intentionally **no** `depends_on: ocr: service_healthy` on the
  backend: OCR is optional and a slow dependency must not block the API.

## 5. What happens when OCR is missing or broken

`AISTUDY_OCR_ON_FAILURE` (`aistudy.ocr.on-failure`) chooses the shape of the
answer. The compose default is `fail` (a deployment that ships OCR promises
text). The application default is `review` (an optional dependency must not
destroy an otherwise readable document).

| Outcome (`OcrOutcome`) | `on-failure=review` (default) | `on-failure=fail` | Operator action |
|---|---|---|---|
| `OK` | page text stored | same | none |
| `NO_TEXT` | job → `NEEDS_REVIEW` + issue `OCR_LOW_CONFIDENCE`; page kept, no text | same (not a failure) | check the scan, not the deployment |
| `LOW_CONFIDENCE` / `CONFIDENCE_UNKNOWN` | `NEEDS_REVIEW` + `OCR_LOW_CONFIDENCE`; confidence stored as SQL `NULL`, never as a made-up 1.0 | same | review the text |
| `SKIPPED_NO_ENGINE` (`AISTUDY_OCR_ENABLED=false`) | job succeeds, no text, no issue | same | expected in the text-only shape |
| `UNAVAILABLE` | `NEEDS_REVIEW` + issue, message `OCR_UNAVAILABLE: …` | job → `FAILED` (all assets) / `PARTIAL_FAILED` (some), safe message starts `OCR_UNAVAILABLE` | start/repair the `ocr` service, or set `enabled=false` |
| `TIMEOUT` | as above, `OCR_TIMEOUT` | `OCR_TIMEOUT: …` | raise `AISTUDY_OCR_TIMEOUT` only together with the CPU limit |
| `PROCESS_FAILED` | `OCR_PROCESS_FAILED` | `OCR_PROCESS_FAILED: …` | sidecar logs (bounded) |
| `OUTPUT_TOO_LARGE` | `OCR_OUTPUT_TOO_LARGE` | `OCR_OUTPUT_TOO_LARGE: …` | the asset is too big for the configured ceilings; raise them deliberately |
| `OUTPUT_UNPARSEABLE` | `OCR_OUTPUT_UNPARSEABLE` | `OCR_OUTPUT_UNPARSEABLE: …` | version skew between `deploy/ocr/requirements.txt` and the adapter |

In every failure row the job **finishes**: the child process is killed at
`AISTUDY_OCR_TIMEOUT` (+ `kill-grace` for SIGTERM→SIGKILL), so a wedged OCR shows
up as a diagnosable `errorCode` on the ingestion job, never as a job that sits in
`EXTRACTING` until its claim expires.

Retry (`POST /api/v1/spaces/{space}/ingestion-jobs/{job}/retry`) is valid for
`FAILED` and `PARTIAL_FAILED`; fix the deployment first, because these failures
are environmental and will repeat.

## 6. Contract summary (backend ⇄ sidecar)

```text
POST /ocr    Authorization: Bearer ${OCR_TOKEN}      (only when OCR_TOKEN is set)
             { "imageBase64": "...", "filename": "page-3.png" }
     -> 200  { "text": "...", "confidence": 0.0-1.0, "model": "ppocr-ch-cpu",
               "outcome": "OK" | "NO_TEXT" }
     -> 429  OCR queue full      -> OcrOutcome.UNAVAILABLE at the backend
     -> 503  models still loading-> OcrOutcome.UNAVAILABLE
     -> 500  inference crash     -> OcrOutcome.PROCESS_FAILED
     -> 413  image over the ceiling
GET  /healthz  200 ready / 503 loading
```

The alternative `engine=process` protocol (`paddleocr --image_dir <file>
--output <file>`, exit code, optional `confidence: <0..1>` in stdout) is
documented on `PaddleOcrProcessEngine` and is what a single-host install without
a sidecar uses.

## 7. Page order and the deferred unique index (F-31, related operational note)

Migrations `V063` (revision-scoped ordering index) and `V064` (backfill of
`source_page.page_order` into a contiguous `1..N` per space/source/revision)
accompany this change. The **unique** index is intentionally not shipped yet: the
TXT/Markdown and DOCX extractors still write `page_order = 1` per asset, and one
ZIP asset expands into several entries extracted by the same job, so a unique
constraint would fail those inserts and the revision publish stamp. Once every
extractor allocates through `SourcePageService.allocateOrderSlots`, the next
migration is exactly:

```sql
ALTER TABLE source_page
    ADD COLUMN order_revision_scope BIGINT
        GENERATED ALWAYS AS (extraction_revision_id) STORED,
    ADD UNIQUE KEY uk_source_page_revision_order
        (space_id, source_id, order_revision_scope, page_order);
```

The generated column keeps in-flight draft pages (`extraction_revision_id IS
NULL`) outside the constraint, which is the one state where orders are still
being assigned.

## 8. Not verified here

No real PaddleOCR run, no model download and no Docker build were performed while
writing this: the pinned `paddlepaddle==2.6.2` / `paddleocr==2.7.3` combination
and the `PaddleOCR(use_angle_cls=..., lang=..., device=...)` call in
`ocr_sidecar.py` must be smoke-tested on the target host before being trusted.
The backend half of the contract is covered by DB-free unit tests
(`server/src/test/java/com/aistudy/server/ingestion/ocr/`).
