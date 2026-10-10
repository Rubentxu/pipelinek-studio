# scoop-bucket

[Scoop](https://scoop.sh/) bucket for
[pipelinek-studio](https://github.com/Rubentxu/pipelinek-studio).

## Why

The single-binary distribution (B6.0) ships with a
cross-platform tarball: the bash launcher for Linux/macOS
plus a Windows `.bat` shim (B6.3). With this Scoop bucket,
Windows users get:

```powershell
scoop bucket add Rubentxu https://github.com/Rubentxu/scoop-bucket.git
scoop install studio
studio --version
```

No JDK 21 install. No Node install (the studio surfaces
"node missing" as WARN when `studio mcp` is invoked — a
B6.x follow-up bundles Node).

## Files

- `bucket/studio.json` — the Scoop manifest. Downloads the
  B6.0 standalone tar from GitHub releases, verifies the
  SHA-256, exposes both `studio` (the bash launcher) and
  `studio.bat` (the Windows shim) on PATH.

## Updating for a new release

1. Build the new standalone tar:
   ```bash
   gradle :studio-cli:standaloneDist
   ```

2. Compute the SHA-256 of the tar:
   ```bash
   # Linux/macOS:
   shasum -a 256 studio-cli-standalone-X.tar
   # Windows (PowerShell):
   Get-FileHash studio-cli-standalone-X.tar -Algorithm SHA256
   ```

3. Edit `bucket/studio.json`:
   - Update `version` to the new version.
   - Update `url` to the new release tarball URL.
   - Update `hash` to the computed hash.
   - Update the `bin` and `extract_dir` arrays to
     reference the new version.

4. Commit + push.

5. Bump the release on GitHub so users get `scoop update`.

## Move-out

This bucket lives inside the main `pipelinek-studio` repo
for convenience. The Scoop convention is a **separate repo**
named `scoop-bucket` (e.g. `Rubentxu/scoop-bucket`). The
operator moves the `scoop-bucket/` directory to its own repo
at release time. The `bucket/studio.json` is self-contained
and needs no edits.
