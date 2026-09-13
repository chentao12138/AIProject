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
