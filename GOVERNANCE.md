# Governance

cloud-itonami-jsic-4721 is steered by the [cloud-itonami](https://itonami.cloud)
project under the AGPL-3.0-or-later license.

## Decision Making

- **Architecture & Governor rules**: decided by ADR (Architecture Decision Record)
  in the parent `kotoba-lang/industry` repository (and this repo's own
  `docs/adr` where applicable)
- **Commodity classes & jurisdiction facts**: open for PR contribution with test coverage
- **Code quality & testing**: enforced by CI (clj-kondo, test-runner)
- **Release & registry**: coordinated by the parent repository's manifest

## Maintainers

Contact the cloud-itonami core team via the parent repository.

## Stability

- The Governor rules are considered stable once an ADR is merged
- Commodity classes and jurisdictions can be extended without breaking Governor logic
- Semantic versioning reflects backward compatibility of the Governor interface

## Dispute Resolution

Disagreements about cold-chain-safety constraints or the Governor's decision-making
should be escalated as a formal ADR proposal.
