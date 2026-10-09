# S5 E5.4 — Read-only filesystem namespace (sample)

The Studio SDK subprocess reads from the operator's filesystem by
default. A minimal hardening pass runs the subprocess in a private
mount + user namespace where the filesystem is read-only, and only
the per-run control root is writable.

The samples below are operator-driven recipes (D-005: the Studio
never modifies the host mount table on the operator's behalf).

## What it does

- Creates a per-run overlay mount: the operator's read-only root is
  the lower layer, a small tmpfs is the upper layer.
- The only writable paths are the SDK subprocess's `TMPDIR`,
  `/dev/shm` (a small tmpfs), and the per-run control root.
- Binds the per-run control root from the host so the Studio JVM
  can read its subprocess's stdout.log, run.db, and exit code.
- Drops capabilities the SDK doesn't need (`CAP_NET_RAW`,
  `CAP_SYS_ADMIN`, `CAP_SYS_PTRACE`, `CAP_SETUID`, `CAP_SETGID`).

## Sample: per-run sandbox script

```sh
#!/usr/bin/env bash
# Usage: sandbox-run.sh <run-id> <control-root-on-host> <sdk-cmd...>
# Example: sandbox-run.sh abc-123 /var/lib/pipelinek-studio/runs/abc-123 \
#   /opt/sdk/bin/pipelinek --db /tmp/abc.db
set -euo pipefail
RUN_ID="$1"
CONTROL_ROOT_HOST="$2"
shift 2
SDK_CMD=("$@")

# Mount a private tmpfs for the subprocess's writables.
WROVERLAY="/tmp/pipelinek-overlay-$RUN_ID"
WORKDIR="$WROVERLAY-work"
UPPER="$WROVERLAY-upper"
mkdir -p "$WORKDIR" "$UPPER"
mount -t tmpfs tmpfs "$UPPER"

# Mount the operator's root read-only as the lower layer. We use
# --bind -o ro because we don't want a private copy of the entire
# filesystem; we just want the subprocess to see / as read-only.
mount --bind / "$WROVERLAY-root-ro" 2>/dev/null || \
    mkdir -p "$WROVERLAY-root-ro" && mount --bind / "$WROVERLAY-root-ro"
mount -o remount,bind,ro "$WROVERLAY-root-ro"

# Overlay: lower is ro, upper is the tmpfs, work is for the kernel.
mount -t overlay overlay \
    -o "lowerdir=$WROVERLAY-root-ro,upperdir=$UPPER,workdir=$WORKDIR" \
    "$WROVERLAY-merged"

# Bind-mount the per-run control root from the host so the JVM can
# observe the subprocess's output.
mount --bind "$CONTROL_ROOT_HOST" "$WROVERLAY-merged${CONTROL_ROOT_HOST}"

# Enter the namespace: private mount, no PID sharing, no network
# (paired with SANDBOX_NETPOLICY.md and SANDBOX_SECCOMP.md).
unshare -m -p -n --mount-proc \
    --map-root-user \
    --drop-caps=all \
    --kill-child \
    "${SDK_CMD[@]}"
```

## How to verify

After applying, attempt a write to `/etc/foo` from inside the
subprocess context. The write should:

- succeed inside the overlay (the tmpfs upper layer), but
- not affect the host's `/etc` (the lower layer is read-only, and the
  overlay is private to the subprocess's mount namespace)

```sh
# Inside the subprocess:
echo "test" > /etc/canary
# Outside (from the JVM / operator shell):
ls -la /etc/canary   # should not exist
```

The mount namespace is destroyed when the subprocess exits (or when
`--kill-child` triggers). The host sees no state change.

## Caveats

- `mount` and `unshare` require root. The Studio cannot apply this
  policy from a non-privileged parent JVM. The pattern is: a setuid
  helper binary, or a systemd unit, that drops to a dedicated UID
  and applies the namespace before `exec`-ing studio-app.
- The overlay is destroyed on subprocess exit. If a future iteration
  needs the per-run control root to survive a JVM crash, capture
  the overlay's upper layer to disk on a `SIGTERM` handler.
- The `--drop-caps=all` is broad. The SDK may need a subset (e.g.
  `CAP_DAC_READ_SEARCH` for some shared libraries). Test the policy
  with the documented happy-path scenario before relying on it.
- macOS doesn't support overlay mounts in the same way; the
  equivalent is `mount_overlay` (third-party) or a chroot + bind
  mount pair, out of scope here.

## Pairing with the other sandbox docs

This doc is the filesystem half. Combined with the net policy
(SANDBOX_NETPOLICY.md) and seccomp (SANDBOX_SECCOMP.md), the SDK
subprocess is constrained to:

- only its overlay + per-run control root filesystem
- only AF_UNIX sockets (no TCP/UDP, no listening)
- only the syscalls it actually needs

Each doc stands on its own; together they form the layered defence.
