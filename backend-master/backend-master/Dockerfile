# ---------------------------------------------------------------------------
# Build stage
#
# Compiles and tests with the full JDK. The test suite runs here on purpose: a
# revision that cannot pass its own tests must not produce an image.
# ---------------------------------------------------------------------------
# Maven ships in this image instead of being bootstrapped by ./mvnw.
#
# The wrapper has to download a Maven distribution before it can build anything,
# and that download is the most fragile step in the whole Dockerfile: it failed
# here with "wget: Failed to fetch .../apache-maven-3.9.16-bin.tar.gz" and took
# the build down with it. Two things make it brittle. The JDK image has no
# unzip, so the wrapper silently switches its URL to .tar.gz; and it prefers
# wget over curl, whose one-shot connect gives up instead of falling through to
# IPv4/next address the way curl does. Nothing is left to go wrong when Maven is
# already installed. ./mvnw stays in the repo for host-side use.
FROM maven:3.9.16-eclipse-temurin-17 AS build

WORKDIR /build

# pom.xml only: the wrapper and .mvn/ stay out of the image, so an mvnw edit
# (that file is regenerated wholesale) cannot bust the dependency layer below.
#
# pom.xml changes far less often than source, so resolving dependencies in its
# own layer keeps most rebuilds off the network.
COPY pom.xml ./
# The Maven repository is a cache mount rather than part of the layer. Two reasons: a
# rebuild reuses the toolchain, plugins and providers instead of asking Maven Central
# again (a transient 429 there once failed an otherwise-green revision), and nothing
# downloaded ends up in the image. Both Maven steps share the same cache.
#
# Pre-fetching is best-effort on purpose: `dependency:go-offline` is known to fail
# on some plugin resolutions, and a failure there should not sink an otherwise
# buildable revision. Whatever it misses is fetched by the package step below.
RUN --mount=type=cache,id=roadwise-m2,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline \
        || echo "dependency pre-fetch skipped (exit $?); the package step will resolve them"

COPY src/ src/
RUN --mount=type=cache,id=roadwise-m2,target=/root/.m2 mvn -B -ntp clean package

# ---------------------------------------------------------------------------
# Runtime stage
#
# JRE only, non-root, no source tree, no Maven, no test artefacts. The previous
# single-stage image ran the application as root and shipped the whole build
# context (JDK + Maven + full source), which is both unnecessary attack surface
# and a much larger image.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

# Unprivileged system account. No login shell; the app never needs one.
RUN groupadd --system --gid 10001 roadwise \
    && useradd --system --uid 10001 --gid roadwise --no-create-home --shell /usr/sbin/nologin roadwise

WORKDIR /app

COPY --from=build --chown=roadwise:roadwise /build/target/backend-0.0.1-SNAPSHOT.jar /app/app.jar

# RoadReportController/WebConfig serve /uploads/** relative to the working
# directory. Uploads are runtime data — never baked into the image — so the
# directory must exist and be writable by the application user. Mount a volume
# here, or persist evidence in object storage (Supabase is the active store).
RUN mkdir -p /app/uploads && chown -R roadwise:roadwise /app

USER roadwise

EXPOSE 8080

# The JVM is container-aware (UseContainerSupport is the default) and sizes the
# heap from the cgroup limit. MaxRAMPercentage leaves headroom for metaspace,
# threads and direct buffers, which is what tends to OOM a container first when
# the heap is allowed to grow to 100% of the limit.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

CMD ["java", "-jar", "/app/app.jar"]
