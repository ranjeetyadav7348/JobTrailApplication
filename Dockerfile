# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
# Pinned to linux/amd64 rather than the build host's architecture. Two reasons,
# and the second one is the interesting one:
#
#  1. The EC2 target is x86_64, and a silently-arm64 image fails at pull time.
#  2. The embedding model needs native libraries (DJL's HuggingFace tokenizer
#     and ONNX Runtime) that ship for win/linux/macOS x86_64 and linux-aarch64,
#     but NOT win-aarch64. On a Windows-on-ARM development machine the model
#     cannot load at all and retrieval silently drops to keyword-only. Inside
#     this image it loads, so the deployed app gets the full hybrid search the
#     developer's laptop could not run.
FROM --platform=linux/amd64 maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Dependencies resolve in their own layer so a source-only change does not
# re-download the world. This is the single biggest CI time saver here.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q clean package -DskipTests

# Split the fat jar into `lib/` plus a thin application jar. Dependencies change
# far less often than application code, so copying them as separate layers below
# lets Docker reuse the ~125-jar dependency layer across deploys and push only
# the small application layer. (`--layers` here would be a filter naming which
# layers to extract, not a request to split them — the default output is
# already the shape we want.)
RUN java -Djarmode=tools -jar target/jobtrail-1.0.0.jar extract --destination extracted

# ---------------------------------------------------------------------------
# Model fetch
# ---------------------------------------------------------------------------
# The embedding model is baked in rather than downloaded on first run. Left to
# itself the app fetches ~90MB from raw.githubusercontent.com when the first
# pod starts, which means startup depends on GitHub being reachable, every
# fresh pod pays the download again, and a locked-down egress policy breaks the
# feature in a way that looks like a bug. Baking it makes startup deterministic
# and offline.
FROM --platform=linux/amd64 alpine:3.20 AS model
RUN apk add --no-cache curl
WORKDIR /model
ARG MODEL_BASE=https://raw.githubusercontent.com/spring-projects/spring-ai/main/models/spring-ai-transformers/src/main/resources/onnx/all-MiniLM-L6-v2
RUN curl -fsSL -o tokenizer.json "${MODEL_BASE}/tokenizer.json" \
 && curl -fsSL -o model.onnx    "${MODEL_BASE}/model.onnx" \
 && test -s tokenizer.json && test -s model.onnx

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM --platform=linux/amd64 eclipse-temurin:17-jre-jammy AS runtime

# curl is here for the container healthcheck only. tzdata matters more than it
# looks: send windows and "days since applied" are computed in local time, so a
# container stuck on UTC would silently shift the sending window.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/*

# Unprivileged, and owns nothing it does not need to write.
RUN groupadd --system --gid 1001 jobtrail \
 && useradd --system --uid 1001 --gid jobtrail --home /app jobtrail
WORKDIR /app

COPY --from=model --chown=jobtrail:jobtrail /model /app/models

# Ordered least- to most-frequently-changed, so a code-only deploy rebuilds and
# pushes just the final, small layer. The application jar's manifest points at
# lib/, so the two must stay siblings.
COPY --from=build --chown=jobtrail:jobtrail /build/extracted/lib/ ./lib/
COPY --from=build --chown=jobtrail:jobtrail /build/extracted/jobtrail-1.0.0.jar ./

# Writable scratch for the model cache, kept off the read-only image layers.
RUN mkdir -p /app/cache && chown jobtrail:jobtrail /app/cache

USER jobtrail

ENV TZ=Asia/Kolkata \
    SERVER_PORT=8080 \
    AI_MODEL_CACHE=/app/cache \
    EMBEDDING_TOKENIZER_URI=file:/app/models/tokenizer.json \
    EMBEDDING_MODEL_URI=file:/app/models/model.onnx \
    RESUME_PATH=/app/resume/resume.pdf \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

# Kubernetes has its own probes; this one is for `docker run` and compose.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "jobtrail-1.0.0.jar"]
