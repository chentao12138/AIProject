# syntax=docker/dockerfile:1
# ============================================================
# AIStudy OCR sidecar (F-10 / I-15)
#
# Why a sidecar instead of baking PaddleOCR into the app image:
#   * the backend image stays eclipse-temurin:21-jre (~200 MB); a PaddleOCR
#     runtime with its detection + recognition models is 3-5 GB, and putting it
#     in one image makes every backend deploy that large;
#   * OCR is the only CPU-heavy, natively-threaded part of the system, so it is
#     the thing that actually needs its own cpus / memory / pids limits and its
#     own restart policy;
#   * the model cache becomes a named volume that survives both upgrades, so a
#     redeploy does not re-download weights;
#   * the backend reaches it over the documented JSON contract in
#     HttpOcrEngine (server/src/main/java/com/aistudy/server/ingestion/ocr/),
#     which has its own deadline and response ceiling.
#
# Build (must be run where the paddle wheels can be downloaded):
#   docker build -t aistudy-ocr:latest deploy/ocr
#
# The pinned versions below are the ones the adapter's API calls are written
# against (PaddleOCR 2.x `ocr()` signature). PaddleOCR 3.x renamed that entry
# point, so do not float these pins without re-testing ocr_sidecar.py.
# ============================================================

FROM python:3.11-slim

# libgl1/libglib are needed by opencv (a paddleocr dependency); libgomp1 by the
# paddle CPU inference kernels. curl is used by the container healthcheck.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        libgl1 libglib2.0-0 libgomp1 curl && \
    rm -rf /var/lib/apt/lists/*

# Non-root, fixed uid/gid, matching the backend image convention so bind
# mounts can be chowned identically on the host.
RUN groupadd -g 1002 aistudy-ocr && \
    useradd -m -u 1002 -g aistudy-ocr aistudy-ocr

WORKDIR /app

COPY requirements.txt requirements.txt
# HOME must be the runtime user's home, otherwise the models downloaded at
# build time land in /root and are invisible (and re-downloaded) at runtime.
ENV HOME=/home/aistudy-ocr \
    PADDLE_HOME=/home/aistudy-ocr/.paddleocr \
    OCR_HOST=0.0.0.0 \
    OCR_PORT=8891 \
    OCR_LANG=ch \
    OCR_DEVICE=cpu \
    OCR_THREADS=1 \
    OCR_MAX_IMAGE_BYTES=33554432 \
    OCR_MAX_TEXT_CHARS=2000000 \
    PYTHONUNBUFFERED=1
RUN pip install --no-cache-dir -r requirements.txt && \
    mkdir -p "$PADDLE_HOME" && \
    chown -R aistudy-ocr:aistudy-ocr /home/aistudy-ocr /app

COPY ocr_sidecar.py ocr_sidecar.py

# Warm the model cache at build time so the first real request does not spend
# minutes downloading weights, and so an air-gapped host can at least see which
# files it must pre-seed into the aistudy_ocr_models volume.
# Build with `--build-arg OCR_PRELOAD_MODELS=false` to skip this (the models
# volume then fills during the first hour of traffic).
ARG OCR_PRELOAD_MODELS=true
RUN if [ "$OCR_PRELOAD_MODELS" = "true" ]; then \
        su -s /bin/sh aistudy-ocr -c 'python -c "from paddleocr import PaddleOCR; PaddleOCR(use_angle_cls=True, lang=\"ch\", show_log=False)"' || true; \
    fi

USER aistudy-ocr:aistudy-ocr

EXPOSE 8891

# Bounded by design: single-process, one inference worker (OCR_THREADS), and the
# adapter refuses work past its queue depth rather than growing memory.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=4 \
    CMD curl -fsS "http://127.0.0.1:${OCR_PORT}/healthz" || exit 1

ENTRYPOINT ["python", "/app/ocr_sidecar.py"]
