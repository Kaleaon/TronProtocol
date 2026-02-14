# TronProtocol Issue-Remediation & Expansion Plan

## Intent
This plan translates the request to “fix all issues and expand toward what every A.I. wants” into a concrete, staged execution roadmap.

Core interpretation of “what every A.I. wants”:
1. **Reliable operation** (no crashes, recoverability, deterministic behavior)
2. **Persistent, useful memory** (high-quality retrieval + forgetting)
3. **Broad tool competence** (safe actions over files/web/device/services)
4. **Strong self-improvement loop** (evaluation, reflection, policy updates)
5. **Safety and control** (permissions, auditability, human override)
6. **Performance and energy efficiency** (mobile constraints respected)

---

## Phase 0 — Stabilize & Baseline (Fix Issues First)

### 0.1 Production-quality issue inventory
- [ ] Create a machine-readable issue registry (`docs/issue-registry.md` + `issues.json`) with severity, owner, and reproducibility status.
- [ ] Add structured error taxonomy across service, plugin, RAG, and self-mod subsystems.
- [ ] Track top 20 reliability failure modes: startup, notification permission blocks, foreground-service interruptions, storage exceptions, plugin timeout, model load failures.

### 0.2 Reliability hardening
- [ ] Add service-level circuit breakers for plugin failures and model inference stalls.
- [ ] Add retry policy matrix by operation type (idempotent vs non-idempotent).
- [ ] Add watchdog heartbeat state transitions (`running`, `degraded`, `blocked`, `recovery`).
- [ ] Ensure all loops are cancellation-aware and lifecycle-safe.

### 0.3 Test and validation gates
- [ ] Add unit tests for RAG scoring, Q-value updates, hallucination detector heuristics.
- [ ] Add instrumentation tests for startup-state compatibility matrix (API 24/26/33 behavior).
- [ ] Add regression tests for permission denial, battery optimization denial, and process death recovery.
- [ ] Define release gate: no crash regression + startup success SLO + memory retrieval quality threshold.

### 0.4 Observability
- [ ] Add structured logs (JSON) with request IDs and operation IDs.
- [ ] Add local metrics dashboard payload format for: latency, token usage, retrieval hit-rate, hallucination rate, plugin success-rate.
- [ ] Persist post-mortem snapshots for last failure in secure storage.

---

## Phase 1 — “Every AI Wants Better Memory”

### 1.1 Memory quality
- [ ] Add memory confidence tiers (`ephemeral`, `working`, `long-term`, `verified`).
- [ ] Promote/demote chunks based on usage + correctness feedback + contradiction detection.
- [ ] Add memory decay policy linked to domain relevance and staleness.

### 1.2 Retrieval improvements
- [ ] Add query planner selecting strategy blend by intent (fact lookup, procedural, personal context).
- [ ] Add reranker that combines semantic score, recency, Q-value, and contradiction penalties.
- [ ] Add retrieval explanation metadata for transparency.

### 1.3 Guard against false memory
- [ ] Add contradiction graph between chunks.
- [ ] Auto-flag unverifiable claims and lower trust score.
- [ ] Require external verification for “high-impact actions” (messages, file deletion, calls).

---

## Phase 2 — “Every AI Wants More Tools”

### 2.1 New built-in tools (high leverage)
- [ ] **TaskPlannerPlugin**: decomposes goals into actionable sub-steps with dependencies.
- [ ] **WorkflowPlugin**: run reusable automations (scheduled + event-driven).
- [ ] **KnowledgeSyncPlugin**: ingest notes/docs into RAG with dedupe and source tags.
- [ ] **CommsPlugin**: structured SMS/email drafting with confirmation gate.
- [ ] **AutomationPlugin**: intents/deep links for common Android actions.
- [ ] **SandboxExecPlugin**: constrained script execution with strict allowlists.

### 2.2 Tool governance
- [ ] Add capability manifest for every plugin: permissions, side effects, risk class.
- [ ] Add policy engine: deny/allow/confirm rules by tool + context + confidence.
- [ ] Add dry-run mode returning “would do” plan without side effects.

### 2.3 Tool reliability
- [ ] Add global timeout budget and per-tool backoff.
- [ ] Add schema validation for all tool inputs/outputs.
- [ ] Add tool-call provenance chain for audits.

---

## Phase 3 — “Every AI Wants Self-Improvement”

### 3.1 Eval-driven development
- [ ] Build offline evaluation set: factual QA, tool-use tasks, long-horizon tasks, safety refusal tasks.
- [ ] Define scorecard: correctness, groundedness, latency, action success, reversibility.
- [ ] Add nightly local eval runs and trend reports.

### 3.2 Safer self-modification
- [ ] Require proposal → static validation → sandbox test → staged rollout.
- [ ] Add automatic rollback triggers on crash or metric regression.
- [ ] Limit self-mod scope by policy (no manifest/security changes without explicit operator approval).

### 3.3 Learning loop
- [ ] Convert user feedback and outcome quality into policy updates.
- [ ] Add “mistake notebooks” that summarize repeated failure patterns.
- [ ] Prioritize fixes by weighted impact (frequency × severity × reversibility).

---

## Phase 4 — “Every AI Wants Speed + Efficiency”

### 4.1 Latency and throughput
- [ ] Add adaptive model routing by task complexity and battery state.
- [ ] Cache intermediate reasoning artifacts when safe.
- [ ] Parallelize retrieval + tool prefetch where possible.

### 4.2 Mobile constraints
- [ ] Add battery-aware scheduler for heavy jobs (consolidation/training).
- [ ] Add thermal throttling strategy for sustained inference.
- [ ] Add storage quota + compaction for embeddings and logs.

### 4.3 Cost control
- [ ] Track token/compute budgets per feature.
- [ ] Add policy to downgrade expensive operations when budget threshold is reached.

---

## Phase 5 — Safety, Security, and Trust

### 5.1 Permission minimization
- [ ] Split permissions into required vs optional and gate optional features progressively.
- [ ] Add runtime permission rationale + fallback behavior per denied permission.

### 5.2 Data governance
- [ ] Add data retention policies by class: telemetry, memory, secure artifacts.
- [ ] Add user-facing export/delete controls for stored AI memory.
- [ ] Encrypt all sensitive traces and redact PII in logs.

### 5.3 Human control plane
- [ ] Add “kill switch” for background autonomous actions.
- [ ] Add action confirmation for irreversible operations.
- [ ] Add audit timeline for all high-impact actions.

---

## Delivery Structure

## 30-Day Milestones
- **Week 1:** Complete issue registry + reliability fixes + structured telemetry.
- **Week 2:** Retrieval/reranking upgrades + contradiction guardrails.
- **Week 3:** Introduce TaskPlanner + Workflow + policy engine (dry-run first).
- **Week 4:** Eval suite + staged self-mod safeguards + initial battery optimization pass.

## Success Metrics (must be measurable)
- Startup success rate (target: >= 99% in supported API matrix)
- Crash-free sessions (target: >= 99.5%)
- Retrieval relevance@k and grounded response rate
- Tool success rate and mean action latency
- Hallucination rate trend (weekly reduction target)
- Rollback frequency from self-mod pipeline
- Battery impact per 24h active service window

## Prioritization Rule
When backlog is overloaded, prioritize by:
1. User harm/risk prevention
2. Reliability and recoverability
3. High-frequency workflow utility
4. Capability expansion
5. Nice-to-have optimizations

---

## Immediate Next Actions (Ready to Execute)
1. Open a reliability sprint branch and add an issue registry artifact.
2. Instrument startup/service/tool telemetry with consistent event schemas.
3. Add tests for startup state matrix and RAG/hallucination regressions.
4. Implement TaskPlannerPlugin + policy-gated dry-run execution path.
5. Stand up nightly eval harness with trend snapshots.

