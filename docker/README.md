# docker — PipelineK Studio container image

**Block:** B6.4 (S5 follow-up: distribution channels).

## Why

B6.0 ships a self-contained tar with a custom jlink JRE +
the studio CLI. B6.4 packages that tar into a Docker image
so the operator gets:

```bash
docker run -d \
    -p 7778:7778 -p 7777:7777 \
    -v studio-data:/var/lib/pipelinek-studio \
    --name studio \
    pipelinek-studio:X
```

No JDK 21 install on the host. The image carries the
B6.0 standalone tar (custom JRE bundled) on top of an
alpine base. The data dir is a named volume so runs +
snapshots + the lock file persist across container
restarts.

## Build

```bash
# From the project root:
docker build -t pipelinek-studio:X -f docker/Dockerfile .
```

The build is multi-stage:
1. `builder` — `eclipse-temurin:21-jdk`, compiles the
   standalone tar via `./gradlew :studio-cli:standaloneDist`.
2. `runtime` — `eclipse-temurin:21-jre-alpine`, copies
   the tar, exposes 7778 (HTTPS) + 7777 (HTTP),
   entrypoint = `studio start --host 0.0.0.0 --data-dir
   /var/lib/pipelinek-studio`.

## Run

```bash
# HTTPS (default per C-1)
docker run -d -p 7778:7778 \
    -v studio-data:/var/lib/pipelinek-studio \
    --name studio pipelinek-studio:X

# Verify
docker exec studio studio --version
docker exec studio studio doctor
curl -k https://localhost:7778/health

# HTTP (opt-in via --insecure-http)
docker run -d -p 7777:7777 \
    -v studio-data:/var/lib/pipelinek-studio \
    --name studio pipelinek-studio:X \
    studio start --insecure-http --host 0.0.0.0
```

## Image size

The standalone tar is ~94MB (60MB jlink JRE + 33MB
lib jars). The alpine base is ~80MB. The final image
is ~180MB compressed.

## Move-out

The Dockerfile lives in the main repo for convenience.
The CI matrix typically moves it to a separate
`docker-pipelinek-studio` repo or to a build pipeline
that pushes the image to a registry. The Dockerfile is
self-contained and needs no edits.
