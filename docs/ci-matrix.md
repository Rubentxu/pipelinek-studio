# CI matrix — PipelineK Studio

**Block:** B7.x (cross-platform Node bundle + standalone tar)

## Why

The B6.0 standalone tar bundles a custom jlink JRE + the studio
CLI, so the operator does not need a system JDK 21. The B7.0
follow-up bundles a Node 20 binary inside the same tar, so the
operator also does not need a system Node install. Both pieces
are **host-specific**: a Linux build produces a Linux JRE + a
Linux Node binary; a macOS build produces a macOS JRE + a macOS
Node binary; a Windows build produces a Windows JRE + a Windows
Node binary.

The CI matrix is the set of host platforms that build + publish
the standalone tar. Each host runs the same Gradle target
(`:studio-cli:standaloneDist`); the host's OS + arch pick the
right jlink modules + the right Node archive from the fetch
table.

## Supported platforms

| Platform     | JDK 21 host         | Node archive     | Tar suffix       |
|--------------|---------------------|------------------|------------------|
| `linux-x64`  | `eclipse-temurin:21-jdk` on amd64 | `node-vX.Y.Z-linux-x64.tar.xz`     | `…-linux-x64.tar` |
| `linux-arm64` | `eclipse-temurin:21-jdk` on arm64 | `node-vX.Y.Z-linux-arm64.tar.xz`   | `…-linux-arm64.tar` |
| `darwin-x64` | macOS 14 (amd64)    | `node-vX.Y.Z-darwin-x64.tar.xz`   | `…-darwin-x64.tar` |
| `darwin-arm64` | macOS 14 (M1/M2/M3 arm64) | `node-vX.Y.Z-darwin-arm64.tar.xz` | `…-darwin-arm64.tar` |
| `win-x64`    | Windows Server 2022 | `node-vX.Y.Z-win-x64.zip`         | `…-win-x64.zip`   |

Total: 5 hosts. Each host builds exactly one tar.

## Per-host build steps

On every host, the build runs the same commands:

```bash
# 1. Pre-fetch the Node archive
#    (the script picks the right
#    platform tag from uname; the
#    SHA-256 pin in the table
#    prevents a hostile mirror from
#    serving a modified binary).
scripts/b7.0-fetch-node.sh

# 2. Build the standalone tar
#    (jlink bundles the JDK 21 JRE
#    for the host's arch; nodeBundle
#    extracts the Node binary; the
#    result is a single self-
#    contained tar/zip).
./gradlew :studio-cli:standaloneDist
```

The CI step is host-agnostic — the same script runs on every
matrix entry. The only host-specific inputs are (1) the JDK 21
install (each host must have a JDK 21 available; Docker
containers can use `eclipse-temurin:21-jdk` as the base) and
(2) the platform tag (picked from `uname -s` + `uname -m` in
the fetch script + Gradle task).

## Per-platform artifacts

Each host produces one tar at `studio-cli/build/standalone/`:

| Host        | Artifact path                                |
|-------------|----------------------------------------------|
| linux-x64   | `studio-cli/build/standalone/studio-cli-standalone-<ver>/` (tar at `…-standalone-<ver>.tar`) |
| linux-arm64 | same path, different host-built JRE + Node   |
| darwin-x64  | same path, different host-built JRE + Node   |
| darwin-arm64 | same path, different host-built JRE + Node |
| win-x64     | same path, different host-built JRE + Node + `.bat` launcher (B6.3) |

The install channels (asdf / brew / scoop / Docker) wrap this
tar. The `darwin-x64` tar is what `brew install studio` picks
up; the `linux-x64` tar is what `asdf install pipelinek-studio`
picks up; the `win-x64` tar is what `scoop install studio` picks
up. Docker is host-agnostic (the B6.4 multi-stage image uses
the `linux-x64` tar as the runtime payload).

## SHA-256 pins (supply-chain)

The `NODE_SHA256` table in `scripts/b7.0-fetch-node.sh` carries
a 64-char SHA-256 for every supported `.tar.xz` platform. The
table is cross-checked against the live upstream `SHASUMS256.txt`
in `NodeBundleMatrixB7Test` — a version bump that updates the
upstream file without updating the table fails the test.

The Windows `.zip` pipeline does not yet have a SHA pin (B7.x
follow-up). When the unzip path lands, add a `[win-x64]` entry
to the table from the upstream `SHASUMS256.txt`'s
`node-vX.Y.Z-win-x64.zip` line.

## Adding a new platform

To add (e.g.) `linux-riscv64`:

1. Add the platform tag to the
   `when {}` block in
   `studio-cli/build.gradle.kts`
   `:studio-cli:nodeBundle` task.
2. Add the SHA-256 entry to the
   `NODE_SHA256` table in
   `scripts/b7.0-fetch-node.sh`,
   sourced from
   `https://nodejs.org/dist/v<X>/SHASUMS256.txt`.
3. Add a CI host (e.g. a `linux-riscv64`
   runner) and a matrix entry that
   runs the same two commands.
4. Run the test suite to verify
   the cross-check still passes.

## Why a CI matrix, not a cross-compile

The jlink step needs a JDK 21 that
matches the target arch (a Linux
JDK produces a Linux JRE; a macOS
JDK produces a macOS JRE; there is
no cross-jlink). Same for the Node
binary — Node's prebuilt tarballs
are host-specific. So each platform
must build on a host of the same
arch. A `qemu-user` cross-build is
out of scope (the studio is a
server, not an embedded binary;
the perf + correctness overhead is
not worth the matrix savings).

## Move-out

This doc lives in the main repo for
convenience. The CI workflow
itself (`.github/workflows/release.yml`
or equivalent) typically lives in a
separate `pipelinek-studio-ci` repo
or a build-pipeline config. The
matrix is self-contained and needs
no edits when the build pipeline
moves.
