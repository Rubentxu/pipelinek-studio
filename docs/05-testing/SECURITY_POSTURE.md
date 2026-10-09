# S5 E5.4 — Security posture (partial)

The Studio backend runs untrusted PipelineK scripts in a supervised
subprocess. This document captures the current security posture and
the remaining gaps before production-grade isolation. The posture is
intentionally explicit: every decision below is paired with the threat
it addresses and the residual risk we accept.

## Current posture (S0..S5)

### Process isolation
- Per-run control root at `<dataDir>/runs/<runId>/`; the SDK subprocess
  inherits the parent JVM's filesystem and environment.
- The runner is the only path from the HTTP surface to a subprocess.
  No inbound SDK is loaded; only the pinned SHA-256 verified bundle
  is on the classpath.
- ProcessBuilder `--add-opens` / `--add-exports` flags expose only the
  JDK internals the SDK actually uses. No `--agentlib` / native agent.

### Input validation
- 1 MiB request body limit (interceptor in `BodySizeLimit.kt`).
- Strict UUID validation on route param IDs (`RouteParams.requireUuid`).
- Snapshot source is bytes, not a file path: no path-traversal surface.
- `Idempotency-Key` header required for `POST /runs`; uniqueness is
  enforced at the storage layer.

### Output / network
- The Studio server does not open outbound connections on its own.
  Only `fetch` from the CLI/MCP adapters to the local Studio HTTP
  port is allowed.
- The MCP stdio adapter does not bind a port; stdio only.

### Plugin admission (S2)
- The SDK is pinned by SHA-256 in the artifact download step.
- Step classes are discovered via the JAR central directory *without*
  class loading (`StaticManifestInspector`). No plugin code executes
  before the operator explicitly runs a scenario against it.

## Residual risks (TODO before production)

| Risk | Severity | Mitigation today | Plan |
|---|---|---|---|
| Untrusted SDK code runs as the Studio user's UID | high | `trusted` profile only (D-005); SHA-pinned bundle | S5+ add a `restricted` profile: separate UID, seccomp, no network |
| Run subprocess can read arbitrary host files | high | control-root bounds writeable paths | S5+ chroot / namespace; OS-level sandbox characterisation |
| Run subprocess can bind a port | medium | none (no firewall) | S5+ add an outbound-port blocklist via `nftables` / `iptables` |
| Run subprocess can fork / exec other binaries | medium | none | S5+ seccomp filter; or run under `systemd-run --scope --user` |
| HTTP surface has no rate limit | low | body-size cap + UUID strict validation | S5+ `RateLimit` plugin (not yet on classpath) |
| Studio HTTP is plain HTTP (no TLS) | medium | loopback only by default | S5+ TLS termination at the reverse proxy; document the operator requirement |

## Characterisation commands (what an S5 hardening pass should run)

- `unshare -r -n -p --mount-proc` (Linux user namespace + no network + no
  PID namespace) to confirm the SDK subprocess cannot escape.
- `nft list ruleset` to verify the outbound-port blocklist is in place.
- `seccomp-tools dump` to capture the syscall filter at runtime.
- `strace -f -e trace=network,file ./studio start` to confirm no
  unexpected network or file activity.

## Out of scope

- Multi-tenant isolation (the Studio is single-tenant by design; per
  R-08 a single Studio instance serves one operator's experiments).
- Side-channel attacks against the SDK subprocess (Spectre/Meltdown
  are kernel/OS concerns, not Studio's).
- Supply-chain attacks against the SDK distribution (pinned by
  SHA-256; rotation is the operator's call per D-002).
