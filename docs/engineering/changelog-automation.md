# Automated CHANGELOG.md Maintenance

This document details VoicePulse's automated changelog mechanism, adhering to [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).

---

## 1. Overview & Rationale

VoicePulse follows Specification-Driven Development (SDD), where each discrete feature, bugfix, or architectural task maps directly to a tracked GitHub issue. Maintaining `CHANGELOG.md` manually inside feature pull requests frequently causes merge conflicts across parallel branches and introduces formatting inconsistencies.

To eliminate this friction:
- `CHANGELOG.md` updates automatically whenever a tracked issue is completed and closed.
- Changes are committed directly to `develop` by `github-actions[bot]`.
- Feature PR branches remain clean of changelog merge conflicts.

---

## 2. GitHub Actions Automation Workflow

The workflow is defined at [`.github/workflows/update-changelog-on-issue-close.yml`](../../.github/workflows/update-changelog-on-issue-close.yml) and backed by the Node.js module [`.github/scripts/update-changelog.js`](../../.github/scripts/update-changelog.js).

### Trigger & Guardrails
- **Trigger:** `on: issues: types: [closed]`
- **Condition:** Runs only when `github.event.issue.state_reason == 'completed'`.
- **Exclusion Filters:** Issues closed as `not_planned` or tagged with triage labels (`duplicate`, `wontfix`, `invalid`) are ignored.
- **Idempotency Safeguard:** Checks if `\b#<issueNumber>\b` or `/issues/<issueNumber>\b` already exists in `CHANGELOG.md`. Repeated issue closing or manual re-runs will never produce duplicate entries.
- **CI Loop Defense:** The automated commit includes `[skip ci]` to prevent recursive triggering of build and test workflows.

---

## 3. Issue Categorization Matrix

The script extracts the Conventional Commit prefix from the issue title (priority), falling back to GitHub labels if the title lacks a prefix. Leading bracketed tags (e.g., `[VP-08]`, `[EPIC-01]`) are automatically stripped.

| Conventional Commit Prefix | Fallback GitHub Label | Target Section in `CHANGELOG.md` |
|:---|:---|:---|
| `feat`, `feature` | `enhancement`, `feature` | `### Added` |
| `fix`, `bug` | `bug` | `### Fixed` |
| `refactor`, `perf`, `performance` | `architecture`, `refactor`, `performance` | `### Changed` |
| `deprecate`, `deprecated` | — | `### Deprecated` |
| `remove`, `removed` | — | `### Removed` |
| `sec`, `security` | `security` | `### Security` |
| `ci`, `chore`, `tooling`, `infra`, `build` | `ci-cd`, `tooling`, `infrastructure` | `### Tooling & Infrastructure` |
| `docs`, `doc` | `documentation` | `### Documentation` |
| *(Default if unmatched)* | — | `### Changed` |

### Entry Format
```markdown
- <cleanedTitle> ([#<issueNumber>](<issueUrl>))
```

Example:
```markdown
- feat(ai): define rig-core client adapter & gemini 2.5 flash integration ([#5](https://github.com/pedrazamiguez/voice-pulse/issues/5))
```

---

## 4. Release Cutover Procedure

When cutting a new formal release (e.g., `v0.1.0`):

1. **Create Release Branch:**
   ```bash
   git checkout -b release/v0.1.0 develop
   ```

2. **Update `CHANGELOG.md`:**
   Convert the active `## [Unreleased]` section into the new version header with the ISO 8601 date, and re-create a clean `## [Unreleased]` block at the top:
   ```markdown
   ## [Unreleased]

   ## [0.1.0] - 2026-11-08

   ### Added
   ...
   ```

3. **Verify & Merge:**
   Open a pull request from `release/v0.1.0` into `main`, and back-merge to `develop`.

---

## 5. Local Verification

Developers can verify the changelog script and unit tests locally:

```bash
# Run unit tests
node --test .github/scripts/update-changelog.test.js

# Or via Makefile
make test-changelog
```
