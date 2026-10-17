# ---------------------------------------------------------------------------
# Build stage
#
# Compiles and tests with the full JDK. The test suite runs here on purpose: a
# revision that cannot pass its own tests must not produce an image.
# ---------------------------------------------------------------------------
# Maven is preinstalled here instead of being bootstrapped by ./mvnw.
#
# The wrapper's first act is to download a Maven distribution of its own, so
# anything that goes wrong at the repository happens before a single source file
# is compiled. That is also what "wget: Failed to fetch
# .../apache-maven-3.9.16-bin.tar.gz" was: wget treats every non-2xx as a failed
# fetch and --quiet hid the status, so a repository rate-limiting this builder
# surfaces as an unexplained download failure instead of the 429 it really is.
# Nothing is downloaded when Maven is already on the image, so the whole failure
# mode disappears. ./mvnw stays in the repo for host-side use.
FROM maven:3.9.16-eclipse-temurin-17 AS build

WORKDIR /build

# Maven Central rate-limits by client IP, and a hosted builder does not own its
# address — everything on the platform egresses through one, so "429 Too Many
# Requests" is a normal outcome here, not an incident, and no amount of retrying
# fixes it. Resolve through a mirror that does not share that limit. Point this
# at an internal proxy instead if you have one, e.g.
#   docker build --build-arg MAVEN_MIRROR_URL=https://nexus.internal/repository/maven-public/ .
ARG MAVEN_MIRROR_URL=https://maven-central.storage-download.googleapis.com/maven2/
# Generated rather than committed so the build arg above is the single place the
# repository is named. mirrorOf=central leaves every other repository alone.
RUN printf '%s\n' \
        '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
        '  <mirrors>' \
        '    <mirror>' \
        '      <id>build-mirror</id>' \
        '      <name>Maven Central mirror</name>' \
        '      <url>'"${MAVEN_MIRROR_URL}"'</url>' \
        '      <mirrorOf>central</mirrorOf>' \
        '    </mirror>' \
        '  </mirrors>' \
        '</settings>' \
    > /build/settings.xml

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
    mvn -B -ntp -s /build/settings.xml dependency:go-offline \
        || echo "dependency pre-fetch skipped (exit $?); the package step will resolve them"

COPY src/ src/
# Retried, but only for transport failures. A missing artifact, a compile error
# or a red test fails on the first attempt and is reported as-is; a dropped
# connection or a 5xx/429 gets two more tries with a widening backoff.
#
# Each attempt first clears Maven's *.lastUpdated markers: a 429 leaves one
# behind, and from then on Maven refuses to re-ask the repository for that
# artifact, so a retry would fail instantly with the stale cached error.
RUN --mount=type=cache,id=roadwise-m2,target=/root/.m2 bash -c 'set -o pipefail; for attempt in 1 2 3; do \
        find /root/.m2/repository -name "*.lastUpdated" -delete 2>/dev/null || : ; \
        if mvn -B -ntp -s /build/settings.xml clean package 2>&1 | tee /build/mvn.log; then \
            rm -f /build/mvn.log ; exit 0 ; \
        fi ; \
        grep -qE "429|Could not transfer|Connection reset|Read timed out|status code: 5" /build/mvn.log || exit 1 ; \
        [ "$attempt" -lt 3 ] || exit 1 ; \
        echo "repository rate-limited or unreachable (attempt $attempt/3), retrying in $((attempt * 20))s" >&2 ; \
        sleep $((attempt * 20)) ; \
    done'

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
