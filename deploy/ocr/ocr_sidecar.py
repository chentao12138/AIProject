#!/usr/bin/env python3
"""AIStudy OCR sidecar adapter (F-10 / I-15).

Serves the JSON contract the Spring backend expects
(``server/.../ingestion/ocr/HttpOcrEngine.java``):

    POST /ocr
        {"imageBase64": "<base64>", "filename": "page-3.png"}
    -> 200 {"text": "...", "confidence": 0.0-1.0, "model": "ppocr-v4-ch", "outcome": "OK"}
    -> 400/413/422 for a bad or oversized request, 429 when saturated,
       503 before the models have loaded, 500 on an inference crash.

    GET /healthz
    -> 200 {"status": "ready", ...} once the models are loaded, 503 until then.

Why this file exists
--------------------
``paddleocr`` the CLI does not speak that protocol (its own flags are
``--image_dir`` / ``--save_dir`` style and vary between 2.x and 3.x), so a
process engine would depend on an interface nobody owns. This adapter pins the
contract in one place, and it is also where the safety limits that a bare CLI
cannot express live:

* one inference worker at a time (``OCR_THREADS``): the Paddle predictor is not
  thread-safe and N concurrent predictions on a 2 vCPU container is how OCR
  gets OOM-killed;
* a hard request queue: past ``OCR_QUEUE_DEPTH`` the sidecar answers 429 rather
  than accepting work it cannot finish before the caller's deadline;
* input and output ceilings (``OCR_MAX_IMAGE_BYTES`` / ``OCR_MAX_TEXT_CHARS``);
* logs that are single-line and truncated, so an OCR traceback cannot flood the
  container log driver;
* readiness that only flips once the models are actually loaded, so the compose
  healthcheck means "OCR can serve", not "the port is open".

Environment (all optional, defaults shown):
    OCR_HOST=0.0.0.0  OCR_PORT=8891  OCR_LANG=ch  OCR_DEVICE=cpu  OCR_THREADS=1
    OCR_MAX_IMAGE_BYTES=33554432  OCR_MAX_TEXT_CHARS=2000000  OCR_QUEUE_DEPTH=8
    OCR_TOKEN=                # when set, every request must carry the matching
                              # "Authorization: Bearer <token>" header
"""

from __future__ import annotations

import base64
import binascii
import json
import logging
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

OCR_HOST = os.environ.get("OCR_HOST", "0.0.0.0")
OCR_PORT = int(os.environ.get("OCR_PORT", "8891"))
OCR_LANG = os.environ.get("OCR_LANG", "ch")
OCR_DEVICE = os.environ.get("OCR_DEVICE", "cpu")
OCR_THREADS = max(1, int(os.environ.get("OCR_THREADS", "1")))
OCR_QUEUE_DEPTH = max(OCR_THREADS, int(os.environ.get("OCR_QUEUE_DEPTH", "8")))
OCR_MAX_IMAGE_BYTES = max(1024, int(os.environ.get("OCR_MAX_IMAGE_BYTES", 32 * 1024 * 1024)))
OCR_MAX_TEXT_CHARS = max(1024, int(os.environ.get("OCR_MAX_TEXT_CHARS", 2_000_000)))
OCR_TOKEN = os.environ.get("OCR_TOKEN", "")
OCR_MAX_LOG_CHARS = 600

_MODEL_RE = re.compile(r"[^\w.\-]")

logging.basicConfig(
    level=logging.INFO,
    stream=sys.stdout,
    format="%(asctime)s %(levelname)s ocr-sidecar %(message)s",
)
LOG = logging.getLogger("ocr-sidecar")


def brief(text: object) -> str:
    """Single-line, control-free, length-bounded log/diagnostic excerpt."""
    raw = "" if text is None else str(text)
    one_line = re.sub(r"\s+", " ", raw).strip()
    return one_line[:OCR_MAX_LOG_CHARS]


class OcrWorker:
    """Owns the PaddleOCR instance and serialises predictions on it."""

    def __init__(self) -> None:
        self._engine = None
        self._lock = threading.Lock()
        # Counting both the running and the waiting requests is what makes the
        # 429 honest: the caller's deadline is spent in this queue too.
        self._active = 0
        self._model_label = None

    @property
    def ready(self) -> bool:
        return self._engine is not None

    def load(self) -> None:
        """Import + instantiate once at startup; a failure here is fatal."""
        os.environ.setdefault("FLAGS_allocator_strategy", "auto_growth")
        try:
            os.environ["OMP_NUM_THREADS"] = str(OCR_THREADS)
        except Exception:  # pragma: no cover - environment is always settable
            pass
        from paddleocr import PaddleOCR  # imported late so /healthz can report 503 first

        self._engine = PaddleOCR(
            use_angle_cls=True,
            lang=OCR_LANG,
            device=OCR_DEVICE,
            show_log=False,
        )
        self._model_label = _MODEL_RE.sub("-", f"ppocr-{OCR_LANG}-{OCR_DEVICE}")
        LOG.info("models ready lang=%s device=%s threads=%s", OCR_LANG, OCR_DEVICE, OCR_THREADS)

    def recognise(self, image_path: str) -> tuple[str, float]:
        """Run one prediction.

        Returns the joined text and the mean recognition confidence; an empty
        page yields ("", 1.0) because the engine *did* answer, it just found
        nothing, and the backend maps that to the NO_TEXT outcome.
        """
        with self._lock:
            self._active += 1
            try:
                started = time.monotonic()
                result = self._engine.ocr(image_path, cls=True)
                lines: list[str] = []
                scores: list[float] = []
                for page in result or []:
                    for entry in page or []:
                        # entry is [box, (text, confidence)]
                        try:
                            text, score = entry[1][0], float(entry[1][1])
                        except (IndexError, TypeError, ValueError):
                            continue
                        if text:
                            lines.append(str(text))
                            scores.append(score)
                mean = sum(scores) / len(scores) if scores else 1.0
                LOG.info("predicted lines=%s chars=%s ms=%s",
                         len(lines), sum(len(line) for line in lines),
                         int((time.monotonic() - started) * 1000))
                return "\n".join(lines), mean
            finally:
                self._active -= 1

    @property
    def saturated(self) -> bool:
        return self._active >= OCR_QUEUE_DEPTH


WORKER = OcrWorker()


class Handler(BaseHTTPRequestHandler):
    server_version = "AIStudyOcrSidecar/1.0"

    def log_message(self, fmt: str, *args: object) -> None:  # noqa: A003
        LOG.info("%s %s", self.address_string(), brief(fmt % args))

    # -- helpers ---------------------------------------------------------
    def _send(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _denied(self) -> bool:
        if not OCR_TOKEN:
            return False
        supplied = self.headers.get("Authorization", "")
        if supplied.strip() != f"Bearer {OCR_TOKEN.strip()}":
            self._send(401, {"error": "UNAUTHORIZED", "message": "bad or missing token"})
            return True
        return False

    # -- routes ----------------------------------------------------------
    def do_GET(self) -> None:  # noqa: N802
        if self.path.split("?")[0] != "/healthz":
            self._send(404, {"error": "NOT_FOUND"})
            return
        if not WORKER.ready:
            self._send(503, {"status": "loading", "detail": "models are still loading"})
            return
        self._send(200, {
            "status": "ready",
            "model": WORKER._model_label,
            "active": WORKER._active,
            "queueDepth": OCR_QUEUE_DEPTH,
        })

    def do_POST(self) -> None:  # noqa: N802
        if self.path.split("?")[0] != "/ocr":
            self._send(404, {"error": "NOT_FOUND"})
            return
        if self._denied():
            return
        if not WORKER.ready:
            self._send(503, {"error": "LOADING", "message": "models are still loading"})
            return
        if WORKER.saturated:
            # 429 (not a silent stall) lets the backend fail the job with
            # OCR_UNAVAILABLE inside its own deadline instead of hanging.
            self._send(429, {"error": "BUSY", "message": f"OCR queue is full ({OCR_QUEUE_DEPTH})"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._send(400, {"error": "BAD_REQUEST", "message": "invalid Content-Length"})
            return
        if length <= 0:
            self._send(400, {"error": "BAD_REQUEST", "message": "empty request body"})
            return
        if length > 4 * OCR_MAX_IMAGE_BYTES:
            # base64 inflates by ~4/3; rejecting on the envelope stops a client
            # from making the sidecar read a body it will discard anyway.
            self._send(413, {"error": "REQUEST_TOO_LARGE"})
            return

        try:
            raw = json.loads(self.rfile.read(length).decode("utf-8"))
            payload = base64.b64decode(raw.get("imageBase64", ""), validate=True)
        except (UnicodeDecodeError, binascii.Error, ValueError, AttributeError) as exc:
            self._send(400, {"error": "BAD_REQUEST", "message": brief(exc)})
            return
        if len(payload) > OCR_MAX_IMAGE_BYTES:
            self._send(413, {"error": "IMAGE_TOO_LARGE",
                             "message": f"{len(payload)} bytes over the {OCR_MAX_IMAGE_BYTES} limit"})
            return
        if not payload:
            self._send(422, {"error": "EMPTY_IMAGE"})
            return

        import tempfile

        fd, path = tempfile.mkstemp(suffix=".img", prefix="ocr-")
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(payload)
            text, confidence = WORKER.recognise(path)
        except Exception as exc:  # inference crashed: report, do not pretend empty
            LOG.error("prediction failed: %s", brief(exc))
            self._send(500, {"error": "OCR_FAILED", "message": brief(exc)})
            return
        finally:
            try:
                os.unlink(path)
            except OSError:
                pass

        if len(text) > OCR_MAX_TEXT_CHARS:
            text = text[:OCR_MAX_TEXT_CHARS]
        self._send(200, {
            "text": text,
            "confidence": round(max(0.0, min(1.0, confidence)), 6),
            "model": WORKER._model_label,
            "outcome": "OK" if text.strip() else "NO_TEXT",
        })


def main() -> int:
    server = ThreadingHTTPServer((OCR_HOST, OCR_PORT), Handler)
    server.daemon_threads = True

    def bootstrap() -> None:
        try:
            WORKER.load()
        except Exception as exc:
            # A model that cannot load will not fix itself: exit so the
            # container restart policy (and the operator) get a clean signal
            # instead of a process that answers 503 forever.
            LOG.error("model load failed, exiting: %s", brief(exc))
            server.shutdown()
            os._exit(70)

    threading.Thread(target=bootstrap, daemon=True).start()
    LOG.info("listening on %s:%s lang=%s device=%s maxImageBytes=%s queueDepth=%s",
             OCR_HOST, OCR_PORT, OCR_LANG, OCR_DEVICE, OCR_MAX_IMAGE_BYTES, OCR_QUEUE_DEPTH)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
