# syntax=docker/dockerfile:1
# Backend image (ADR-0012): distroless Java 25, non-root, no shell, read-only-root-filesystem friendly.
# Build context: repository root. CI builds the jar first (./gradlew :backend:bootJar) and passes its path.
#   docker build -f backend/Dockerfile --build-arg JAR_FILE=backend/build/libs/backend.jar -t memo-backend .
# Base images are pinned by digest; Renovate updates the digests.

ARG LAYERS_IMAGE=eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
ARG RUNTIME_IMAGE=gcr.io/distroless/java25-debian13:nonroot@sha256:28a3986989d7d74cb5cfd6ba369e64d3d634f83ac9fa562dac8db2d20827f90e

FROM ${LAYERS_IMAGE} AS layers
ARG JAR_FILE=backend/build/libs/backend.jar
WORKDIR /work
COPY ${JAR_FILE} app.jar
# Spring Boot layered extraction: dependencies change rarely, application often → better image layer reuse.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM ${RUNTIME_IMAGE}
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="memo-revamp-backend" \
      org.opencontainers.image.source="https://github.com/nazihar/memo-revamp" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="LicenseRef-Proprietary"
WORKDIR /app
COPY --from=layers /work/extracted/dependencies/ ./
COPY --from=layers /work/extracted/spring-boot-loader/ ./
COPY --from=layers /work/extracted/snapshot-dependencies/ ./
COPY --from=layers /work/extracted/application/ ./
# /tmp must be a writable volume (emptyDir/tmpfs) because the root filesystem is mounted read-only.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp -Dfile.encoding=UTF-8"
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
