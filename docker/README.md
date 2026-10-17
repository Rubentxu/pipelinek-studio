# docker + podman — PipelineK Studio container image

**Block:** R0.3.5 / R6.2 (2026-10-12). The B6.4 S5
follow-up Dockerfile is updated to a Podman-friendly
multi-stage build that bundles the PipelineK SDK
when available, so the B5.4 cert can exercise the
full run path inside the container.

## Why

The host sandbox could not exercise R0.3.5 (kill-9
smoke) and the B5.4 cert because the SDK subprocess
needs a real lifecycle (R-VER-001). A Podman
container gives the SDK a real subprocess, a
real /health endpoint, and a way to SIGKILL the
studio backend while the run is in flight.

## Build

```bash
# From the project root (auto-stages the SDK if found):
./docker/build.sh [TAG] [SDK_JAR]

# Defaults (derived from the checkout, never a release literal baked
# into the README; see scripts/uat-podman-001.sh for the same pattern):
#   TAG     = pipelinek-studio:$(sed -n 's/^studio\.version=//p' gradle.properties)
#   SDK_JAR = pipeline-application-$(sed -n 's/^pipelinek\.version=//p' gradle.properties).jar
#             (looked up under ~/.asdf/installs/pipelinek
#              or the repo root; the build is allowed to
#              proceed WITHOUT the SDK for the cert's
#              RuntimeUnavailable path)
```

The build is multi-stage:
1. `builder` — `eclipse-temurin:21-jdk`, compiles the
   standalone tar via `./gradlew :studio-cli:standaloneDist`.
2. `runtime` — `eclipse-temurin:21-jre-alpine`, copies
   the tar + (optionally) the SDK jar at
   `/opt/pipelinek-studio/sdk/`, exposes 7778 (HTTPS)
   + 7777 (HTTP), entrypoint = `studio start --host
   0.0.0.0 --data-dir /var/lib/pipelinek-studio`.

The builder needs ~4 GB heap (the standalone tar
includes a jlink JRE + all jars + a Node binary).
On small hosts, build with `--memory=6g`.

## Run

```bash
# HTTPS (default per C-1)
podman run -d -p 7778:7778 \
    -v studio-data:/var/lib/pipelinek-studio \
    --name studio "$TAG"

# Verify
podman exec studio studio --version
podman exec studio studio doctor

# Trust the certificate the container generated for itself via the
# documented helper: it exports the cert through `keytool -exportcert`
# and exports `STUDIO_CA_CERT` + `CURL_CA_BUNDLE` so every subsequent
# request validates the loopback TLS anchor (not `-k`, not
# `--insecure`). See scripts/lib/studio-http.sh for the contract.
#
# shellcheck source=lib/studio-http.sh disable=SC1091
. "$(dirname "$0")/../scripts/lib/studio-http.sh"
TRUST_DIR="$(mktemp -d -t studio-trust.XXXXXX)"
studio_http_trust_podman_container studio "$TRUST_DIR"
STUDIO_HTTP="https://localhost:7778" curl -sSf "$STUDIO_HTTP/health"
rm -rf "$TRUST_DIR"
```

## Podman Compose (R6.2)

The compose file in this directory provides a
sidecar setup with a healthcheck. Use it for local
dev or for the B5.4 cert.

```bash
podman-compose -f docker/podman-compose.yml up -d
podman-compose -f docker/podman-compose.yml down
```

## e2e / UAT scripts

| Script | Purpose |
|---|---|
| `scripts/e2e-podman.sh` | B5.4 cert: end-to-end against a Podman-launched studio. Asserts /health, /api/version, createExperiment + sealSnapshot + startRun, /events SSE, studio doctor. |
| `scripts/uat-podman-001.sh` | UAT-001: install headless, create + run a real pipeline. |
| `scripts/uat-podman-002.sh` | UAT-002: query snapshot + artifacts (digest, runtime, SDK, plugin, profile). |
| `scripts/uat-podman-013.sh` | UAT-013: HTTP API + MCP stdio produce equivalent outcomes. |
| `scripts/uat-podman-014.sh` | UAT-014: idempotency (same key → same run; new key → new run). |
| `scripts/uat-podman-015.sh` | UAT-015: unsupported profile → clear refusal (no silent fallback). |
| `scripts/uat-podman-016.sh` | UAT-016: snapshot immutability (run references the sealed source). |
| `scripts/uat-podman-019.sh` | UAT-019: kill -9 of backend during in-flight run. |
| `scripts/uat-podman-all.sh` | UAT-039/040 regression aggregator (runs all uat-podman-*.sh). |
| `scripts/e2e-host.sh` | Host-mode e2e (no Podman). Asserts the HTTP surface; the SDK subprocess checks are soft-FAIL. |

The Podman scripts all start a fresh container per
run (unique name `uat-NNN-$$` or `e2e-$$`), exercise
the surface, and clean up via `trap ... EXIT`.

## SDK bundling

The SDK jar is OPTIONAL:
- **With SDK:** the studio reports `studio: <this checkout's version>` and `sdk: <pipelinek.version from gradle.properties>` in `/api/version`. The B5.4 cert exercises the real subprocess.
- **Without SDK:** the studio reports `RuntimeUnavailable`. The HTTP surface still works; the cert's subprocess checks are soft-FAIL.

The Dockerfile's `COPY --from=builder ... ${SDK_JAR}` is
conditional on the SDK_JAR arg being present. The
build script's smart staging looks for the SDK under
`~/.asdf/installs/pipelinek` first.

## Image size

The standalone tar is ~35 MB (jlink JRE + lib jars).
The alpine base is ~80 MB. The runtime image is
~115 MB compressed. With the SDK bundled it grows
by ~30 MB.
