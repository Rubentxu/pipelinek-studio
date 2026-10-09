# S5 E5.4 — Outbound network policy (sample)

The Studio SDK subprocess runs with the operator's UID. By default it
can bind outbound TCP to any destination. A minimal hardening pass
restricts the subprocess to:

- local loopback (so it can talk to the Studio HTTP server if the
  server listens on localhost)
- no external network

The sample below is for `iptables` (legacy) and `nftables` (modern
Linux). It is meant to be applied by the operator on the host BEFORE
launching `studio-app`. The Studio itself does not modify iptables
on the operator's behalf (D-005: no silent network changes).

## What it does

- Drops all outbound traffic from the `studio` user/group except to
  loopback (`127.0.0.0/8`, `::1`).
- Logs the drops so the operator can verify the rule fires.
- Does not touch inbound traffic; if the Studio is on a public
  interface, terminate TLS at a reverse proxy (out of scope here).

## iptables (legacy)

```sh
# Create a dedicated chain for the studio user.
iptables -N STUDIO_OUT 2>/dev/null || iptables -F STUDIO_OUT
iptables -A STUDIO_OUT -o lo -j ACCEPT
iptables -A STUDIO_OUT -m owner --uid-owner studio -j LOG --log-prefix "studio-drop: "
iptables -A STUDIO_OUT -m owner --uid-owner studio -j DROP
# Apply at the OUTPUT chain for user-originated traffic.
iptables -I OUTPUT 1 -j STUDIO_OUT
```

Replace `studio` with the actual UID or group name that runs the SDK
subprocess. The `--uid-owner` match requires the `ipt_owner` module.

## nftables (modern)

```sh
nft add table inet studio_out
nft add chain inet studio_out output { type filter hook output priority 0 \; }
nft add rule  inet studio_out output oifname "lo" accept
nft add rule  inet studio_out output meta skuid studio log prefix "studio-drop: " drop
# Or by group:
# nft add rule inet studio_out output meta skgid studio log prefix "studio-drop: " drop
```

## How to verify

After applying the rule, attempt a `fetch` from inside the subprocess
context (e.g. via a one-off test scenario that tries to reach
`https://example.com`). The log line should appear:

```
kernel: [12345.678] studio-drop: ... OUT=... SRC=... DST=... ...
```

If the rule fires, the SDK subprocess is constrained to local traffic.
The Studio HTTP server (bound to 127.0.0.1) remains reachable.

## Caveats

- This is a sample, not a complete security profile. Pair it with:
  - filesystem namespace isolation (per-run chroot or unshare)
  - seccomp syscall filter
  - dedicated UID (not the operator's login user)
- The Studio subprocess runs as a *child* of the Studio JVM. The rule
  matches the child UID, which is inherited from the parent. If the
  parent process spawns the child via `ProcessBuilder` without
  changing UID, both will be matched by the same `--uid-owner`
  clause — the parent's traffic will also be restricted. The
  expectation is the Studio JVM only talks to localhost too (the
  runner reads/writes per-run files under `<dataDir>/runs/<runId>/`
  and never opens external sockets).
- On macOS the equivalent is `pf` (packet filter) and is not covered
  here. A future S5+ pass can include a `pf.conf` snippet.
