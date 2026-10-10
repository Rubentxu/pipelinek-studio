# homebrew-pipelinek-studio

[Homebrew](https://brew.sh/) tap for
[pipelinek-studio](https://github.com/Rubentxu/pipelinek-studio).

## Why

The single-binary distribution (B6.0) is a self-contained
tarball: it bundles a custom JRE + the studio CLI. With
this Homebrew tap, the install becomes:

```bash
brew tap Rubentxu/pipelinek-studio
brew install studio
studio --version
```

No JDK 21 install. No Node install (the studio is honest
about WARN when `studio mcp` is invoked and Node is missing
— a B6.x follow-up bundles Node inside the tar).

The tap works on macOS (the primary Kotlin dev platform)
and Linux.

## Files

- `Formula/studio.rb` — the formula. Downloads the B6.0
  standalone tarball from GitHub releases, verifies the
  SHA-256, installs to `libexec/`, and symlinks `bin/studio`
  into the user's PATH.

## Updating for a new release

1. Build the new standalone tar:
   ```bash
   gradle :studio-cli:standaloneDist
   ```

2. Compute the SHA-256 of the tar:
   ```bash
   shasum -a 256 studio-cli-standalone-X.tar
   ```

3. Edit `Formula/studio.rb`:
   - Update `url` to the new release tarball URL.
   - Update `sha256` to the computed hash.
   - Update `version` to the new version.

4. Commit + push the change to this tap.

5. Bump the release on GitHub so users get `brew upgrade`.

## Move-out

This tap lives inside the main `pipelinek-studio` repo for
convenience. The Homebrew convention is a **separate repo**
named `homebrew-<tapname>` (e.g.
`Rubentxu/homebrew-pipelinek-studio`). The operator moves
the `homebrew-pipelinek-studio/` directory to its own repo
at release time. The `Formula/studio.rb` is self-contained
and needs no edits.

## Testing the formula locally

```bash
# From the homebrew-pipelinek-studio/ dir:
brew install --build-from-source Formula/studio.rb
studio --version
brew uninstall studio
```
