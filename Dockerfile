# syntax=docker/dockerfile:1
# ============================================================
# BUSINESS-026 multi-stage Dockerfile
# - Builder: eclipse-temurin:21-jdk
# - Runtime: eclipse-temurin:21-jre + non-root aistudy/1001
# ============================================================

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Keep Maven wrapper files in the image so the build works from the
# published source tree without requiring Maven on the runner.
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./
RUN chmod +x mvnw

# Download dependencies first so later src changes don't invalidate
# the dependency cache unless the pom actually changes.
RUN ./mvnw -DskipTests dependency:go-offline

COPY src/ src/
RUN ./mvnw -DskipTests package

# ============================================================
# Runtime image
# ============================================================
FROM eclipse-temurin:21-jre
WORKDIR /app

# -----------------------------------------------------------
# OCR is deliberately NOT part of this image (F-10 / I-15).
#
# A PaddleOCR runtime plus its detection/recognition models is 3-5 GB, needs
# its own CPU / memory / process limits, and can take a minute to become
# usable. Shipping it inside the backend image would make every backend
# deploy that size and would put the OCR cold start on the critical path of
# the app's own health check.
#
# It therefore runs as a sidecar: deploy/ocr (Dockerfile + adapter) and the
# `ocr` profile in deploy/docker-compose.yml. The backend reaches it over the
# documented HTTP contract in ingestion/ocr/HttpOcrEngine.java. Without that
# service the app still starts and serves: PDFs use their text layer, image
# assets ingest with no text, and /actuator/health reports the `ocr` detail as
# DEGRADED. Operator steps: docs/operations-ocr-docker.md.
#
# To opt in at runtime (no rebuild):
#   AISTUDY_OCR_ENABLED=true AISTUDY_OCR_ENGINE=http \
#   AISTUDY_OCR_HTTP_BASE_URL=http://ocr:8891
# -----------------------------------------------------------

# Create non-root runtime user/group with fixed uid/gid so bind-
# mounted persistent volumes are owned consistently on Linux.
RUN groupadd -g 1001 aistudy && \
    useradd -m -u 1001 -g aistudy aistudy

# Copy the fat jar from the builder stage.
# The jar name is resolved from the built artifact; fall back to
# the Spring Boot default layout if the exact name changes.
COPY --from=builder /workspace/target/*.jar /app/server.jar

# Runtime directories owned by the non-root app user.
RUN mkdir -p /var/lib/aistudy/resources && \
    chown -R aistudy:aistudy /var/lib/aistudy/resources

# Do not bake host paths or secrets into the image layers.
# The service binds to 8080 inside the container; the outside
# mapping is defined by deployment / orchestration.
EXPOSE 8080

USER aistudy:aistudy

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=80.0", "-jar", "/app/server.jar"]
