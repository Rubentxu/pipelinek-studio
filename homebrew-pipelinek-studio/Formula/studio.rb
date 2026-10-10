class Studio < Formula
  desc "PipelineK Studio — local control plane for experiments, snapshots, runs"
  homepage "https://github.com/Rubentxu/pipelinek-studio"
  url "https://github.com/Rubentxu/pipelinek-studio/releases/download/v0.1.0-alpha.1/studio-cli-standalone-0.1.0-alpha.1.tar"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  version "0.1.0-alpha.1"

  # B6.2 (2026-10-10): the studio ships a
  # self-contained tar with a custom JRE
  # (jlink) + the studio CLI. The operator
  # does NOT need a JDK 21 install. The
  # Node binary is NOT bundled (B6.x
  # follow-up); the doctor surfaces
  # 'node missing' as WARN and the operator
  # installs Node only if they want to use
  # `studio mcp`.
  #
  # The formula installs to libexec/
  # (Homebrew convention for self-contained
  # tools) and symlinks bin/studio to the
  # user's PATH. The launcher is self-
  # contained: it sets JAVA_HOME to
  # $INSTALL_DIR/jre/ and execs the bundled
  # java.

  livecheck do
    url :stable
    strategy :github_latest
  end

  def install
    # The tar's root is `studio-cli-standalone-<version>/`.
    # Homebrew's libexec.install Dir["*"] extracts
    # every entry into libexec/ — that's exactly
    # what we want (bin/, lib/, jre/ all end up
    # under libexec/).
    libexec.install Dir["*"]
    # Symlink the launcher into the user's PATH.
    # The launcher is self-contained; no
    # Homebrew wrapper is needed.
    bin.install_symlink libexec/"bin/studio"
  end

  test do
    # The launcher must print the build
    # identity. No JDK 21 install required.
    assert_match "studio", shell_output("#{bin}/studio --version")
  end
end
