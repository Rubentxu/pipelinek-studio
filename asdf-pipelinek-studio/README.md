# asdf-pipelinek-studio

[asdf-vm](https://asdf-vm.com/) plugin for
[pipelinek-studio](https://github.com/Rubentxu/pipelinek-studio).

## Why

The single-binary distribution (B6.0) is a self-contained
tarball: it bundles a custom JRE + the studio CLI. The
operator extracts + runs. With this asdf plugin, the install
becomes:

```bash
asdf plugin add pipelinek-studio https://github.com/Rubentxu/asdf-pipelinek-studio.git
asdf install pipelinek-studio latest
asdf global pipelinek-studio latest
studio --version
```

No JDK 21 install. No Node install (the studio is honest about
WARN when `studio mcp` is invoked and Node is missing — a
B6.x follow-up bundles Node inside the tar).

The plugin also works with [mise](https://mise.jdx.dev/)
(mise is asdf-compatible since 2024).

## Files

- `bin/list-all` — lists GitHub release tags (`v*`).
- `bin/download` — downloads the B6.0 standalone tarball.
- `bin/install` — extracts the tarball into `$ASDF_INSTALL_PATH`.
- `bin/list-bin` — returns `studio` (the binary the plugin exposes).

## Overrides

- `PIPELINEK_STUDIO_REPO` — the GitHub repo to read releases
  from. Default: `Rubentxu/pipelinek-studio`.
- `PIPELINEK_STUDIO_DOWNLOAD_URL` — full URL to the tarball
  (skips the GitHub releases API). Useful for testing
  against a local mirror or a private GitHub Enterprise.

## Testing the scripts

```bash
# list-all (no auth required for public repos)
./bin/list-all

# download + install in a sandbox
VERSION=0.1.0-alpha.1
ASDF_INSTALL_PATH=/tmp/studio-sandbox \
ASDF_INSTALL_VERSION=$VERSION \
    sh -c './bin/download && ./bin/install'

/tmp/studio-sandbox/bin/studio --version
```

## Move-out

This plugin lives inside the main `pipelinek-studio` repo
for convenience. The asdf-vm convention is a **separate
repo** (e.g. `Rubentxu/asdf-pipelinek-studio`). The
operator moves the `asdf-pipelinek-studio/` directory to
its own repo at release time. The `bin/` scripts are
self-contained and need no edits.
