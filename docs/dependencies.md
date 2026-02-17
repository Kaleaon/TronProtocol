# Dependency Governance Policy

## Goals
- Reproducible builds with committed lockfiles.
- Continuous awareness of vulnerable and outdated dependencies.
- Controlled and reversible dependency upgrades.

## Source of truth
- Version catalog: `gradle/libs.versions.toml`.
- Lockfiles: each Gradle project must commit `gradle.lockfile` generated via `./gradlew help --write-locks`.

## CI enforcement
- CI enforces `LockMode.STRICT` so dependency graph drift without lockfile updates fails fast.
- CI regenerates lockfiles and fails if `**/gradle.lockfile` changes are uncommitted.
- CI runs `scripts/check_direct_vulns.py` and fails on any known **CRITICAL** CVE affecting direct Maven dependencies.

## Scheduled audit/update workflow
- GitHub workflow `dependency-governance.yml` runs weekly and on demand.
- It generates an outdated dependency report (`dependencyUpdates`) and uploads artifacts.
- It runs direct-dependency CVE checks against OSV.

## Acceptance policy for version bumps

### Security patch SLA
- **Critical (CVSS 9.0+)**: patch, pin, or mitigate within **24 hours**.
- **High (CVSS 7.0-8.9)**: patch within **7 calendar days**.
- **Medium/Low**: patch in regular maintenance windows, at least monthly.

### Required checks before merge
1. `./gradlew compileDebugSources testDebugUnitTest lintDebug` passes under strict lock mode.
2. Lockfiles are refreshed and committed when dependency resolution changes.
3. `scripts/check_direct_vulns.py` reports no critical direct dependency findings.
4. Release notes/changelog entry includes rationale and migration notes for major version updates.

### Rollback plan
- Every dependency bump PR must be isolated and reversible.
- If regressions are detected post-merge:
  1. Revert the dependency bump commit.
  2. Re-run CI to confirm restoration.
  3. Open a follow-up issue documenting root cause and safe re-upgrade criteria.
