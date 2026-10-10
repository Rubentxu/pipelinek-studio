# Transport Security (v0.2.0-alpha.2)

**Audit reference**: C-1 (2026-10-10 senior technical audit). The studio
previously served plain HTTP on `127.0.0.1:7777` with no transport
encryption. The new contract is **HTTPS by default** with a self-signed
cert generated on first boot.

## Defaults

| Surface | Scheme | Port | Notes |
|---------|--------|------|-------|
| Studio backend (loopback) | `https` | `7778` | Self-signed cert at `<dataDir>/keystore.jks` |
| Studio backend (opt-out) | `http` | `7777` | Set `--insecure-http` on `studio start` |
| CLI client | `https` | `7778` | Trusts the loopback self-signed cert (operator's local machine) |

## How the cert is generated

`dev.pipelinek.studio.app.TlsConfig.buildNettySslContext(dataDir)` runs
on every boot:

1. If `<dataDir>/keystore.jks` exists, load it.
2. Otherwise generate a fresh self-signed cert via Ktor's
   `io.ktor.network.tls.certificates.buildKeyStore` with SAN entries
   for `127.0.0.1` and `localhost`.
3. Persist the keystore to `<dataDir>/keystore.jks` with permissions
   `OWNER_READ | OWNER_WRITE` on POSIX. The password is
   `studio-loopback-change-me` — change it in a future S5 hardening
   pass via env var.

The cert is reused on subsequent boots so the operator's
`curl --insecure` / trust-on-first-use decision remains valid.

## How to opt out

For local development without TLS:

```bash
studio start --insecure-http
```

The CLI's HTTPS client (`StudioHttpClient`) installs a
`trustAll` `X509TrustManager` because the cert is self-signed.
A real deployment must replace this with a truststore pinned to the
studio's CA-issued cert.

## Why HTTPS even for loopback

The studio carries state that an on-host attacker could read or
forge over plain HTTP:

- the **Idempotency-Key** header (operator identity, replay state);
- the **X-Studio-Lease** header that gates destructive admin ops
  (AAT-SEC-010);
- the **source bytes** the runner writes to disk, which may include
  secrets in code.

Loopback is still loopback — the attacker needs to be on the same
machine — but the on-host threat model includes any process running
as another user, browser extensions, and accidentally-bound interfaces
(`0.0.0.0` instead of `127.0.0.1`). TLS closes these.

## Known limits

- Self-signed cert → browsers will warn. CLI is the primary client
  and trusts the cert by design.
- No ACME / Let's Encrypt automation. A future S5 hardening pass
  can add a proper cert flow.
- The actual SSL termination in the running distribution is wired
  through Ktor's HOCON path (see `application.conf` if present, or
  the Ktor docs at https://ktor.io/docs/server-ssl.html). The
  embedded DSL in Ktor 3.0.1 does not expose `sslContext` directly.
