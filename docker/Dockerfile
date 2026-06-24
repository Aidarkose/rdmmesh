# syntax=docker/dockerfile:1.7
# rdmmesh-service — multi-stage build.
# Stage 1 compiles every module + the shaded fat jar inside the official maven image.
# Stage 2 is a slim Temurin JRE that only ships the artifact and a non-root user.

# ── stage 1: build ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# Maven wrapper context — copy poms first so dependency resolution caches well.
COPY pom.xml ./
COPY rdmmesh-spec/pom.xml         rdmmesh-spec/pom.xml
COPY rdmmesh-api/pom.xml          rdmmesh-api/pom.xml
COPY rdmmesh-catalog/pom.xml      rdmmesh-catalog/pom.xml
COPY rdmmesh-authoring/pom.xml    rdmmesh-authoring/pom.xml
COPY rdmmesh-workflow/pom.xml     rdmmesh-workflow/pom.xml
COPY rdmmesh-publishing/pom.xml   rdmmesh-publishing/pom.xml
COPY rdmmesh-distribution/pom.xml rdmmesh-distribution/pom.xml
COPY rdmmesh-identity/pom.xml     rdmmesh-identity/pom.xml
COPY rdmmesh-ownership/pom.xml    rdmmesh-ownership/pom.xml
COPY rdmmesh-audit/pom.xml        rdmmesh-audit/pom.xml
COPY rdmmesh-admin/pom.xml        rdmmesh-admin/pom.xml
COPY rdmmesh-app/pom.xml          rdmmesh-app/pom.xml

# Pre-warm the dependency cache (best-effort — `go-offline` is not perfect for
# multi-module reactor projects, so the real build still validates resolution).
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline -DskipTests || true

COPY rdmmesh-spec/schema           rdmmesh-spec/schema
COPY rdmmesh-spec/codegen          rdmmesh-spec/codegen
COPY rdmmesh-api/src               rdmmesh-api/src
COPY rdmmesh-catalog/src           rdmmesh-catalog/src
COPY rdmmesh-authoring/src         rdmmesh-authoring/src
COPY rdmmesh-workflow/src          rdmmesh-workflow/src
COPY rdmmesh-publishing/src        rdmmesh-publishing/src
COPY rdmmesh-distribution/src      rdmmesh-distribution/src
COPY rdmmesh-identity/src          rdmmesh-identity/src
COPY rdmmesh-ownership/src         rdmmesh-ownership/src
COPY rdmmesh-audit/src             rdmmesh-audit/src
COPY rdmmesh-admin/src             rdmmesh-admin/src
COPY rdmmesh-app/src               rdmmesh-app/src
COPY bootstrap                     bootstrap

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipITs package

# ── stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime

ARG APP_USER=rdmmesh
ARG APP_UID=10001

RUN groupadd --system --gid ${APP_UID} ${APP_USER} \
 && useradd  --system --uid ${APP_UID} --gid ${APP_USER} --no-create-home --shell /usr/sbin/nologin ${APP_USER}

WORKDIR /opt/rdmmesh

COPY --from=build /workspace/rdmmesh-app/target/rdmmesh-service.jar /opt/rdmmesh/app.jar
COPY rdmmesh-app/src/main/resources/config.yml                      /opt/rdmmesh/config.yml

USER ${APP_USER}

EXPOSE 8080 8081

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/rdmmesh/app.jar server /opt/rdmmesh/config.yml"]

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=4 \
    CMD wget -qO- http://localhost:8081/healthcheck || exit 1
