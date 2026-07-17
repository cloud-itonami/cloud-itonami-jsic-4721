# Security Policy

## Reporting a Vulnerability

If you discover a security issue in cloud-itonami-jsic-4721, **do not open a
public issue**. Instead, please report it to the cloud-itonami security team via
private disclosure:

- Email: [security contact to be added]
- GitHub Security Advisory: [will be set up]

Include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Any mitigation or workaround

## Security Scope

This repository implements the **Governor** for refrigerated-warehousing
(cold-chain 3PL) client-tenant operations — a deterministic
compliance-checking layer that gates all proposals from the LLM/advisor.

Security-critical concerns:
1. **Holds in Governor rules must never be overridable** — `capacity-
   concentration-limit` and the cold-chain physical-discipline checks
   (`storage-temp-out-of-range?` / `power-outage-exceeds-max?`) block the
   proposal unconditionally when exceeded
2. **Escalation gates must trigger without race conditions** — a held
   proposal always requires human sign-off before any action
3. **Audit ledger integrity** — facts written to the store must be append-only
   and tamper-evident (in production deployment with external ledger backend)

## Testing

All Governor changes must:
- Have comprehensive test coverage (facts, registry, governor rules, kernels)
- Pass `clojure -M:test` and `clojure -M:lint`
- Include documentation of the cold-chain-safety rationale

## Dependencies

This repo has minimal dependencies:
- `cognitect-labs/test-runner` (testing only)
- `clj-kondo` (linting only)
- (Optional for production: `langchain`, `langgraph` from workspace)

This repo does **not** depend on `cloud-itonami` core (see README.md's
"Design boundary" section) — its own Governor invariants are fully
standalone pure logic.

Dependency updates are reviewed for known CVEs before merge.
