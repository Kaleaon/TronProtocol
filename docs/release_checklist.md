# Release Checklist (SLO-Enforced)

This checklist is a hard release gate. Builds are **not releasable** when any SLO fails.

## Service-Level Objectives (SLOs)

1. **Startup success**
   - Target: `>= 99.5%` successful service startups over rolling 7 days.
   - Source: `tron_protocol_service.startup` telemetry events.

2. **Retrieval quality**
   - Target: `>= 0.65` average top retrieval score and `>= 95%` non-empty retrieval responses.
   - Source: `rag_store.retrieve` telemetry + retrieval metrics sink.

3. **Tool success rate**
   - Target: `>= 98.0%` plugin execution success (excluding explicit policy-denied paths).
   - Source: `plugin_execution.execute` telemetry events.

4. **Crash-free sessions**
   - Target: `>= 99.0%` crash-free sessions.
   - Source: fatal telemetry snapshots and runtime crash analytics.

## Mandatory pre-release checks

- [ ] Collect local dashboard export (`tron.telemetry.local.v1`) from a staging run.
- [ ] Verify no fatal-path snapshot burst in the last `N=25` captured fatal paths.
- [ ] Confirm startup SLO meets threshold.
- [ ] Confirm retrieval quality SLO meets threshold.
- [ ] Confirm tool success SLO meets threshold.
- [ ] Confirm crash-free sessions SLO meets threshold.
- [ ] Attach telemetry dashboard artifact to release notes.

## Enforcement notes

- Any SLO miss requires a release-blocking incident ticket and explicit sign-off from engineering owner.
- Fatal-path snapshots must be triaged before re-running release candidate validation.
