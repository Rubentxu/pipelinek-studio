# Release Runbook — v0.1.0-alpha.1

This runbook is the exact sequence the operator follows when
authorising the D-007 push. It is intentionally a single page so
the authorisation + execution is a 5-minute job, not a 50-minute
investigation. **Nothing in this runbook runs without explicit
operator authorisation** (D-007).

## Pre-flight (operator, on this machine)

1. **Confirm the working tree is clean.**

   ```bash
   cd /var/home/rubentxu/Proyectos/kotlin/PipelinekStudio
   git status --porcelain
   # expect: empty
   ```

2. **Confirm the verify harness is green.**

   ```bash
   ./scripts/verify-from-clean-clone.sh
   # expect: ALL FIVE CHECKS PASSED. S5 E5.7 from-clean-clone verification: READY.
   ```

3. **Confirm tests are green.**

   ```bash
   ./gradlew test
   find . -path "*/build/test-results/*.xml" \
     | xargs grep -h '<testsuite ' 2>/dev/null \
     | grep -oP 'tests="\d+"|failures="\d+"|errors="\d+"|skipped="\d+"' \
     | awk -F'"' '{a[$1]+=$2} END {for (k in a) print k, a[k]}'
   # expect: tests= 140   failures= 0   errors= 0   skipped= 1
   ```

4. **Confirm the bundled version is what you want to release.**

   ```bash
   studio --version
   # expect: studio 0.1.0-alpha.1 (kotlin 2.1.0, ktor 3.0.1)
   ```

5. **Confirm the upstream SDK is what the matrix declares.**

   ```bash
   grep pipelinek.version gradle.properties
   # expect: pipelinek.version=0.48.0-rc1
   ```

## Authorisation

Authorise the D-007 push **explicitly, in writing**, in the chat.
Something like:

> "Authorise D-007: push to Rubentxu/pipelinek-studio, tag
> v0.1.0-alpha.1, and open the GitHub Release with the contents of
> RELEASE_NOTES.md."

D-007 demands explicit, not implicit, authorisation. "Go ahead"
without naming the operation is not sufficient.

## Execution (after authorisation)

1. **Configure the remote (only if not already set).**

   ```bash
   git remote add origin git@github.com:Rubentxu/pipelinek-studio.git
   git remote -v
   ```

2. **Push the main branch.**

   ```bash
   git push -u origin main
   ```

3. **Create the annotated tag.**

   ```bash
   git tag -a v0.1.0-alpha.1 -m "v0.1.0-alpha.1 — S0..S5 bootstrap"
   git push origin v0.1.0-alpha.1
   ```

4. **Verify the tag on the remote.**

   ```bash
   git ls-remote --tags origin | grep v0.1.0-alpha.1
   ```

5. **Create the GitHub Release.** This needs the `gh` CLI or a
   manual upload via the GitHub web UI. Either is fine; the CLI is
   faster.

   ```bash
   gh release create v0.1.0-alpha.1 \
       --title "PipelineK Studio v0.1.0-alpha.1" \
       --notes-file RELEASE_NOTES.md \
       --target main
   ```

6. **Attach the SBOM and checksums to the release.** The
   `dist/sbom.cdx.json` and `dist/checksums.{txt,json}` artefacts
   produced by the S5 scripts are the canonical release artefacts.

   ```bash
   gh release upload v0.1.0-alpha.1 dist/sbom.cdx.json dist/checksums.txt dist/checksums.json
   ```

## Post-release verification (operator)

1. **Confirm the release page lists the artefacts.**

   ```bash
   gh release view v0.1.0-alpha.1
   ```

2. **Run the from-clean-clone harness on a fresh clone.** This
   is the certification step that proves the release is not just
   the latest commit, but a verified artefact.

   ```bash
   cd /tmp && rm -rf verify-release && mkdir verify-release && cd verify-release
   git clone git@github.com:Rubentxu/pipelinek-studio.git
   cd pipelinek-studio
   ./scripts/verify-from-clean-clone.sh
   # expect: ALL FIVE CHECKS PASSED.
   ```

3. **Announce.** Pin a thread in `#pipelinek-studio` (or your
   team channel) with the release URL, the S0_RECEIPT link, and
   the verify SHA.

## Rollback

If a release-blocking issue is found after the tag is pushed:

1. **Mark the release as pre-release** (do NOT delete the tag —
   git history is immutable per D-007).

   ```bash
   gh release edit v0.1.0-alpha.1 --prerelease
   ```

2. **Cut a new patch version** (`v0.1.0-alpha.2`) with the fix.

3. **Document the issue** in the new release's body, referencing
   the SHA of the broken alpha.1 tag.

## What this runbook does NOT do

- It does not push automatically. The push waits for explicit
  authorisation.
- It does not modify the working tree.
- It does not delete tags or rewrite history.
- It does not run on `main` without the operator present.
