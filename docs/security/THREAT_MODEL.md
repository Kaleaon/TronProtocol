# Threat Model

## Assets
- **User secrets**: API keys, local credentials, encrypted preferences.
- **Conversation and memory data**: RAG snapshots, continuity state, reflection artifacts.
- **Model artifacts**: downloaded models, integrity manifests, model metadata.
- **Audit trail**: security/audit events used for investigations and compliance.
- **Execution environment**: plugin runtime, network clients, local storage.

## Trust Boundaries
1. **Device boundary**: external network -> on-device app.
2. **Plugin boundary**: third-party plugin code -> core runtime and privileged APIs.
3. **Model boundary**: externally sourced model binaries -> local model execution.
4. **Storage boundary**: in-memory state -> persisted encrypted storage.
5. **Operator boundary**: user actions -> autonomous/assistant actions.

## Attacker Models
- **Remote network attacker**
  - Attempts SSRF, TLS downgrade, certificate spoofing, or content injection.
- **Malicious plugin attacker**
  - Uses over-broad capabilities or prompt/tool misuse to exfiltrate data.
- **Supply-chain attacker**
  - Ships tampered model or dependency artifacts to gain code execution.
- **Local-device attacker**
  - Tries filesystem tampering, log manipulation, or stale-secret recovery.
- **Insider/abuse attacker**
  - Misuses internal tooling to bypass safety or retention controls.

## Key Mitigations
- **Network protections**
  - URL/IP safety filtering, TLS version floor (1.2+), certificate pin validation, malformed cert rejection.
- **Integrity controls**
  - Model integrity verification, tamper-evident audit hash chaining, secure storage use for persisted logs.
- **Access controls**
  - Capability-gated plugins, policy-driven send/DM pairing decisions, security-event auditing.
- **Data governance**
  - Classification tiers with explicit retention/deletion requirements for logs, memory, and model artifacts.
- **Detection and response**
  - High-severity security audit categories, periodic integrity checks, and forensic export support.

## Residual Risks
- Runtime compromise on rooted devices can still subvert on-device controls.
- DNS-level attacks remain possible where host pin sets are incomplete.
- Legacy persisted audit entries without historical chain data are recoverable but less strongly attestable.
