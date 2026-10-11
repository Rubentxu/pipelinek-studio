# R0.3.5 / R6.2 (2026-10-12): multi-stage Dockerfile for
# pipelinek-studio, with the PipelineK SDK 0.48.0-rc1 bundled
# in the runtime image so the B5.4 cert can exercise the full
# run path (the SDK subprocess is alive inside the container).
#
# Build:
#   podman build -t pipelinek-studio:1.0.0-rc.7 -f docker/Dockerfile .
#   (or use the helper: ./docker/build.sh)
#
# Run (server):
#   podman run -d -p 7778:7778 -p 7777:7777 \
#       -v studio-data:/var/lib/pipelinek-studio \
#       --name studio pipelinek-studio:1.0.0-rc.7
#
# Run (cert — exercises the SDK subprocess end-to-end):
#   podman run --rm -v studio-data:/var/lib/pipelinek-studio \
#       pipelinek-studio:1.0.0-rc.7 studio cert
#
# Then:
#   podman exec studio studio doctor
#   podman exec studio studio --version

# ----- builder -----
# The builder compiles the standalone tar
# from source. JDK 21 + Gradle are required
# only at build time; the runtime image
# does NOT inherit this stage's classpath.
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
# R6.2 (2026-10-12): the eclipse-temurin:21-jdk
# base image already has a user with UID 1000
# (the `ubuntu` user). The previous `groupadd
# --gid 1000 studio` failed with "GID 1000
# already exists". We reuse the existing
# UID/GID 1000 (the canonical "first human
# user" id) and just ensure the workdir is
# owned by it.
RUN id 1000 >/dev/null 2>&1 || (groupadd --system --gid 1000 studio && \
    useradd --system --uid 1000 --gid studio --create-home studio)
COPY --chown=1000:1000 . /build
USER 1000
# The standalone tar bundles a jlink JRE
# + the studio CLI + a Node binary. Building
# it produces the self-contained artifact
# the runtime image copies in.
RUN ./gradlew --no-daemon :studio-cli:standaloneDist
# The artifact lands at
# /build/studio-cli/build/standalone/studio-cli-standalone-X.tar.

# ----- runtime -----
# The runtime image uses the eclipse-temurin
# JRE 21 alpine as the base. The studio
# tar's bundled jlink JRE is enough by
# itself, but the alpine base gives us
# the standard glibc/musl + busybox + a
# writable root filesystem.
#
# R6.2 follow-up (2026-10-12): the PipelineK
# SDK 0.48.0-rc1 jar is also copied into
# the runtime image so the B5.4 cert can
# exercise the real SDK subprocess path
# (without it, the studio falls back to
# `RuntimeUnavailable` per R-VER-001).
FROM eclipse-temurin:21-jre-alpine AS runtime
LABEL org.opencontainers.image.title="pipelinek-studio" \
      org.opencontainers.image.description="PipelineK Studio — local control plane for experiments, snapshots, runs" \
      org.opencontainers.image.source="https://github.com/Rubentxu/pipelinek-studio" \
      org.opencontainers.image.licenses="Apache-2.0"

# The studio data dir lives under
# /var/lib/pipelinek-studio. A volume at
# this path persists runs + snapshots +
# the lock file.
RUN mkdir -p /var/lib/pipelinek-studio && \
    mkdir -p /opt/pipelinek-studio/sdk && \
    # The eclipse-temurin alpine images already
    # include a `nonroot` user with UID 1000; reuse
    # it so the operator's host UIDs map cleanly.
    addgroup -g 1000 -S studio 2>/dev/null || true && \
    adduser -u 1000 -G studio -s /bin/sh -D studio 2>/dev/null || true && \
    chown -R 1000:1000 /var/lib/pipelinek-studio /opt/pipelinek-studio
VOLUME ["/var/lib/pipelinek-studio"]
WORKDIR /opt/pipelinek-studio

# Copy the standalone tar from the builder
# stage. The version is read from
# gradle.properties:studio.version at build
# time; we pass --build-arg to make it
# explicit in the build command.
ARG STUDIO_VERSION=1.0.0-rc.7
COPY --from=builder --chown=1000:1000 /build/studio-cli/build/standalone/studio-cli-standalone-${STUDIO_VERSION}/ ./

# Bundle the PipelineK SDK so the B5.4 cert
# has a real subprocess path. The SHA is
# pinned in studio-infra-sdk/src/main/resources/sdk.lock.json
# (version 0.48.0-rc1, sha256
# 0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace).
# The path used here matches what the studio
# runtime probes (PipelineKRuntimeProbe); see
# ADR-002 + the R-22 audit.
#
# The jar is expected at /build/pipeline-application-0.48.0-rc1.jar
# (mounted or COPY'd by the build script). If
# absent, the cert can still run but with
# `RuntimeUnavailable` (the studio will report
# it via /api/version — operators compare the
# SHA to detect drift).
ARG SDK_JAR=pipeline-application-0.48.0-rc1.jar
COPY --from=builder --chown=1000:1000 /build/${SDK_JAR} /opt/pipelinek-studio/sdk/${SDK_JAR}
ENV STUDIO_SDK_PATH=/opt/pipelinek-studio/sdk/${SDK_JAR}

USER 1000
ENV STUDIO_HOME=/opt/pipelinek-studio
ENV JAVA_HOME=/opt/pipelinek-studio/jre
ENV PATH="${STUDIO_HOME}/bin:${PATH}"

# HTTPS (default per C-1) + HTTP (opt-in
# via --insecure-http on the entrypoint).
EXPOSE 7778 7777

# Default entrypoint: start the server on
# all interfaces so the operator can
# reach it from the host. The data dir
# is the volume mounted at
# /var/lib/pipelinek-studio; we pass
# --data-dir so the lock file lives
# there.
#
# Override example (HTTP):
#   podman run -e STUDIO_INSECURE=1 \
#       -p 7777:7777 pipelinek-studio:1.0.0-rc.7 \
#       studio start --insecure-http \
#           --host 0.0.0.0 \
#           --data-dir /var/lib/pipelinek-studio
ENTRYPOINT ["studio"]
CMD ["start", "--host", "0.0.0.0", "--data-dir", "/var/lib/pipelinek-studio"]
