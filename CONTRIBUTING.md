# Contributing to cloud-itonami-jsic-4721

Thank you for your interest in contributing to the Refrigerated Warehousing
(Cold-Chain 3PL) Coordination actor.

## Scope

This repository is a specialization of the cloud-itonami architecture for JSIC
4721 (冷蔵倉庫業, refrigerated warehousing). Contributions should:

1. Extend or correct the **Governor rules** (capacity-concentration-limit,
   cold-chain physical-discipline checks)
2. Add **commodity classes** or **jurisdictional requirements** to the facts registry
3. Improve **test coverage** for cold-chain-warehousing-specific scenarios
4. Clarify **documentation** and ADRs

## Prohibited Changes

Do **not**:

- Add direct reefer/compressor control or forklift/ASRS material-handling
  equipment control (these remain exclusive to warehouse operations staff)
- Modify the Governor to allow LLM confidence to override capacity-
  concentration-limit or physical-discipline holds
- Add a dependency on `cloud-itonami` core (see this repo's own ADR / README
  "Design boundary" section for why — the fleet standalone convention,
  ADR-2607011000/ADR-2607176501)
- Add JVM-only code (all source must be `.cljc` / portable)
- Change the AGPL-3.0-or-later license

## Process

1. Open an issue describing your proposed change
2. Link to the relevant ADR
3. Submit a pull request against `main`
4. Ensure all tests pass: `clojure -M:test`
5. Run linter: `clojure -M:lint`

## Code Style

- Use `.cljc` for all source (no `.clj` or `.cljs` only)
- Follow Clojure conventions (kebab-case, docstrings on public fns)
- Governor rules must be pure, side-effect-free predicates
- Test all new facts and registry entries

## Questions?

File an issue or reach out to the maintainers.
