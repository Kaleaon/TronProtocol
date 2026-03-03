# Data Classification, Retention, and Deletion Controls

## Classification Policy
| Domain | Classification | Rationale |
|---|---|---|
| Logs (audit, policy, security) | Confidential | May contain actor/action metadata and security context. |
| Memory (RAG/session/continuity) | Restricted | Can include highly sensitive user content and inferred state. |
| Model artifacts (weights, metadata, manifests) | Internal | Operational assets; generally non-user PII but integrity-sensitive. |

## Retention Controls
| Domain | Default Retention | Delete on User Request | Secure Delete Required |
|---|---|---|---|
| Logs | 90 days | Yes | Yes |
| Memory | 30 days | Yes | Yes |
| Model artifacts | 365 days | No (unless policy override) | No |

## Deletion Enforcement
- Time-based deletion is driven by policy TTL checks.
- User-request deletion must be honored for logs and memory domains.
- Secure deletion required domains should use encrypted-at-rest + key rotation and best-effort wipe semantics.

## Implementation Reference
- Runtime policy definitions live in `DataGovernancePolicy`.
- Consumers should call:
  - `policyFor(domain)` for classification/retention metadata.
  - `shouldDelete(domain, createdAtMillis, nowMillis)` for lifecycle enforcement.
