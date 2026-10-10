# B6.4 (2026-10-10): multi-stage Dockerfile
# for pipelinek-studio.
#
# The image bundles the B6.0 standalone tar
# (which already includes a custom jlink JRE)
# on top of a minimal JRE 21 alpine base. The
# operator does NOT need to install a JDK on
# the host; the image is self-contained.
#
# Build:
#   docker build -t pipelinek-studio:X -f docker/Dockerfile .
#   (or use the helper: ./docker/build.sh)
#
# Run:
#   docker run -d -p 7778:7778 -p 7777:7777 \
#       -v studio-data:/var/lib/pipelinek-studio \
#       --name studio pipelinek-studio:X
#
# Then:
#   docker exec studio studio doctor
#   docker exec studio studio --version

# ----- builder -----
# The builder compiles the B6.0 standalone
# tar from source. JDK 21 + Gradle are
# required only at build time. The runtime
# image does NOT inherit this stage's
# classpath.
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
# The studio is a multi-module Gradle
# build. We pre-create the gradle user +
# home so the daemon can write its cache.
RUN groupadd --system --gid 1000 studio && \
    useradd --system --uid 1000 --gid studio --create-home studio
COPY --chown=studio:studio . /build
USER studio
# Build the standalone tar. The wrapper
# is included (./gradlew). The `clean` is
# a no-op the first time and ensures the
# build is reproducible.
RUN ./gradlew --no-daemon :studio-cli:standaloneDist
# The artifact lands at
# /build/studio-cli/build/standalone/studio-cli-standalone-X.tar.

# ----- runtime -----
# The runtime image uses the eclipse-temurin
# JRE 21 alpine as the base (the studio
# tar's bundled jlink JRE is enough by
# itself, but the alpine base gives us
# the standard glibc/musl + busybox + a
# writable root filesystem).
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
    addgroup -S studio -g 1000 && \
    adduser -S studio -G studio -u 1000 && \
    chown -R studio:studio /var/lib/pipelinek-studio
VOLUME ["/var/lib/pipelinek-studio"]
WORKDIR /opt/pipelinek-studio

# Copy the standalone tar from the
# builder stage. We untar it directly
# into the runtime workdir so the
# launcher is at /opt/pipelinek-studio/bin/studio.
COPY --from=builder --chown=studio:studio /build/studio-cli/build/standalone/studio-cli-standalone-0.1.0-alpha.1/ ./

USER studio
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
#   docker run -e STUDIO_INSECURE=1 \
#       -p 7777:7777 pipelinek-studio:X \
#       studio start --insecure-http \
#           --host 0.0.0.0 \
#           --data-dir /var/lib/pipelinek-studio
ENTRYPOINT ["studio"]
CMD ["start", "--host", "0.0.0.0", "--data-dir", "/var/lib/pipelinek-studio"]
