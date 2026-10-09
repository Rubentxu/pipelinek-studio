# Contributing to PipelineK Studio

Thanks for your interest. This document is the operator/maintainer
guide; the developer guide for the Studio itself is below.

## Project shape

- `studio-domain`: typed IDs and value classes. Zero dependencies.
- `studio-application`: hexagonal ports + use cases. The
  `application.usecases` package is the only place that orchestrates
  the ports.
- `studio-infra-storage`: SQLite + Flyway + backup helpers. Implements
  the `StudioMetadataPort`, `IdempotencyPort`.
- `studio-infra-sdk`: bridges to the PipelineK SDK. Implements
  `PipelineRunnerPort`, `RunReadPort`, `OutputReadPort`,
  `PipelineKRuntimePort`, `StepCatalogReadPort`, `DiagnosticsReadPort`,
  `IrExplorerReadPort`, `VariantPort`, `RunComparisonPort`.
- `studio-http`: Ktor module + OpenAPI doc + rate limit + body size
  + UUID validation. Pure transport.
- `studio-cli`: hand-rolled CLI dispatcher + Studio SDK TS codegen.
- `studio-app`: full distribution entry point (depends on everything).
- `apps/web`: React + Vite + Monaco SPA. Consumes the OpenAPI doc.
- `apps/mcp`: Node.js MCP v2 stdio adapter. Consumes the OpenAPI doc.
- `apps/vscode`: VS Code extension. Consumes the generated SDK TS.

## Conventions

- **Commits**: conventional commits. No AI attribution footer.
- **D-007**: no push to GitHub without explicit operator authorization.
- **Tests**: 131/1/0 in this repo. Always add a test for new code;
  never disable a test to make it pass — fix the code instead.
- **No fake PASS**: when a port returns Failure, surface the reason
  to the operator. Refusal codes (OUTPUT_REFUSED, IR_UNAVAILABLE,
  DIAGNOSTICS_UNAVAILABLE, RATE_LIMITED, etc.) are part of the
  contract and must be asserted.
- **No silent network changes**: the Studio never modifies iptables,
  seccomp, or the mount table on the operator's behalf (D-005). The
  SANDBOX_* docs are operator-driven recipes.

## Local build

Requires JDK 21 LTS (pinned via `.tool-versions`) and `node` 18+ for
the web SPA / MCP / VS Code.

```sh
# Kotlin side
./gradlew test                    # 131/1/0
./gradlew verifyCleanClone        # 23/23 end-to-end checks
./gradlew :studio-cli:distTar     # build the CLI distribution
# Node side
(cd apps/mcp && npm install && npm run build)   # MCP stdio adapter
(cd apps/vscode && npm install && npm run build) # VS Code extension
# Codegen — keep the SDK TS in sync with the server
./studio-cli generate-sdk-ts --to apps/vscode/src/sdk.ts
```

## Before opening a PR

- [ ] `./gradlew test` passes locally.
- [ ] `./gradlew verifyCleanClone` passes locally (23/23).
- [ ] If you changed the HTTP surface, regenerated `sdk.ts` for the
      VS Code client (see above) and updated `apps/mcp/src/server.ts`
      to match.
- [ ] If you added a new env var / CLI flag, documented it in
      `--help` output and in the relevant doc in `docs/`.
- [ ] If you changed the OpenAPI document, regenerated
      `studio-http/.../OpenApiDoc.kt` from the canonical source.
- [ ] If you added a new HTTP refusal code, asserted it in
      `scripts/uat-from-distribution.sh` and in the SPA's render path
      (honest rendering, not red unless it's an actual error).
- [ ] No `Co-Authored-By: Claude` / `🤖 Generated with Claude Code`
      footers in commits (per user rule).

## Adding a new port

1. Add the interface in `studio-application/.../ports/Ports.kt` (or
   a new file in `ports/` if the surface is large).
2. Add a no-op implementation in `Noop.kt` for tests.
3. Add a use case in `studio-application/.../usecases/UseCases.kt` that
   composes the port with the other ports; expose it through
   `WiringExt`.
4. Add an HTTP route in `studio-http/.../StudioHttpModule.kt` that
   delegates to the use case.
5. Add tests:
   - unit test in `studio-application` (use case, no real ports)
   - http test in `studio-http` (testApplication block, stub port)
6. If the port carries operator-visible state, add it to
   `apps/web/src/App.tsx` (or a new component) with ARIA + keyboard
   shortcuts consistent with the existing patterns.

## Adding a new MCP tool

1. Update `apps/mcp/src/server.ts`: add the tool to `ListToolsRequestSchema`
   with a documented `inputSchema`, and add a case to the
   `ToolCall` handler that calls the Studio HTTP endpoint.
2. The MCP server is a thin adapter (D-004); no business logic.
3. If the new tool exposes a paginated resource, add a second
   resource (`studio://...`) instead of a query-param on the tool.

## Commit message format

```
<type>(<scope>): <subject>

<body>

<footer>
```

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `build`,
`ci`. Scopes are the module names: `domain`, `application`,
`storage`, `sdk`, `http`, `cli`, `app`, `web`, `mcp`, `vscode`,
`scripts`, `docs`, `verify`, `gradle`.

## Reporting issues

- For security issues, do NOT open a public issue. Email the
  maintainer (see MAINTAINERS.md) with `[SECURITY]` in the subject.
- For everything else, open a GitHub issue with the smallest
  reproduction you can. The verify-from-clean-clone output
  (`./gradlew verifyCleanClone`) is the most useful first paste.

## License

The Studio is released under the same license as the upstream
PipelineK project. See `LICENSE`.
