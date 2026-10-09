# Maintainers — PipelineK Studio

This file lists the contact points for the project. It is intentionally
short and boring: a name, a role, a channel.

## Roles

- **Steward** — owns the release cadence, signs releases, adjudicates
  disputes. Default fallback: the same person as the Backup Steward
  during alpha.
- **Backup Steward** — secondary contact for security disclosures and
  release sign-off when the primary is unreachable (>= 7 days).
- **Ops** — runs the operator-facing infra (release distribution, image
  publishing, docs hosting). Out of scope for S0.
- **Security** — receives private vulnerability reports. Coordinates
  embargo window, write-up, and CVE request when warranted.

## Contacts

| Role            | Owner (alias)        | Channel                          |
|-----------------|----------------------|----------------------------------|
| Steward         | `rubentxu`           | GitHub @Rubentxu + project email |
| Backup Steward  | `rubentxu`           | GitHub @Rubentxu                  |
| Ops             | _vacant_             | —                                 |
| Security        | `rubentxu`           | `SECURITY.md` private disclosure |

> The `rubentxu` alias is the only currently-named contact. As the
> project grows, additional maintainers should be added here AND
> invited as collaborators on the upstream repository.

## Adding a new maintainer

1. Open a PR adding the row to the table above.
2. The new maintainer accepts the contribution terms (`CONTRIBUTING.md`
   is the canonical record) and is added to the repo's `Maintainers`
   team with `maintain` permission.
3. The new maintainer's first merged change is recorded in
   `CHANGELOG.md` under the next release as a contributor credit.

## Removing a maintainer

1. The maintainer opens a PR (preferred) OR a Steward opens a PR.
2. The PR removes the row from the table and revokes the repo team
   membership.
3. Any remaining responsibilities are transferred in the same PR.

## Security disclosures

See `SECURITY.md` (or the canonical `docs/05-testing/SECURITY_POSTURE.md`
during alpha) for the private disclosure channel. Public disclosure
follows a coordinated window of typically 90 days, shorten or lengthen
by mutual agreement.
