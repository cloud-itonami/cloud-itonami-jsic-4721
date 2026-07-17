# cloud-itonami-jsic-4721: Refrigerated Warehousing (Cold-Chain 3PL) Coordination Actor

**JSIC 4721** — 冷蔵倉庫業 (Refrigerated Warehousing), Japan Standard Industrial Classification. ISIC Rev. 5 has no dedicated code for this vertical — it folds into the general ISIC 5210 warehousing bucket — so this actor is classified by the Japan-domestic JSIC axis instead. See this repo's ADR for the full classification-gap rationale and a trap worth knowing about before touching the code: **ISIC's own numeric code "4721" means something unrelated** (food/beverage/tobacco retail) — never confuse the two "4721"s.

A distributed actor for autonomous, compliant coordination of refrigerated-warehouse client-tenant operations: client onboarding → warehouse-capacity allocation → inbound goods receiving → cold-chain custody → outbound staging/dispatch. Sealed LLM advisor; independent Governor enforcement; append-only audit ledger. **Not equipment control.** Reefer/compressor control, forklift/ASRS material-handling equipment, and cold-storage facility certification authority remain exclusive to licensed warehouse operations staff and regulators.

Paired with **cloud-itonami-isic-1075** (prepared meals and dishes manufacturing) to model the 2026-07 Nichirei cold-storage cyber-incident case study end to end: isic-1075 ~ Nichirei Foods (the manufacturer), this actor ~ **Nichirei Logistics Group** (the cold-chain 3PL whose ~4-day in/outbound stoppage and ~5,000-client fan-out were the root causes behind `cloud-itonami`'s `SecurityIncidentGovernor`, ADR-2607176500).

## Scope

This actor coordinates **client-facing warehouse-operations workflow** for a refrigerated-warehousing (cold-chain 3PL) business:

- Client tenant onboarding
- Warehouse-capacity allocation to client tenants
- Inbound/outbound storage-lot logging (with cold-chain physical-discipline checks: storage-temperature range, reefer/compressor power-outage duration)
- Temperature-excursion escalation

**Out of scope:**
- Direct reefer/compressor control, forklift/ASRS material-handling equipment control (warehouse operations staff exclusive)
- Cold-storage facility certification authority (human inspector/regulator only)
- Regulatory interpretation

## Design

### Governor (Independent Compliance Layer)

`coldchain-governor` is the separation-of-powers enforcement: it never trusts the advisor's confidence, and it always wins.

- **Closed allowlist** (`:op-not-allowed`-equivalent, `reason-kind-not-allowed`) — the advisor may only ever propose `:tenant/onboard`, `:capacity/allocate`, `:log-inbound-shipment`, `:log-outbound-shipment`, `:flag-temperature-excursion`. Anything else is refused unconditionally.
- **capacity-concentration-limit** (this actor's own new invariant, kernel: `coldchain.kernels.concentration-verdict`) — a single client tenant's allocated share of this warehouse's TOTAL capacity may never exceed 25% (`default-concentration-limit`) without a hold + human escalation. Direct, actor-specific mitigation for Nichirei Logistics Group's ~5,000-client single-point-of-failure concentration (root cause #3 of ADR-2607176500): this actor structurally caps how much of its own physical capacity any one client can come to depend on.
- **Cold-chain physical-discipline checks** (`coldchain.facts` + `coldchain.registry`) — `:log-inbound-shipment` / `:log-outbound-shipment` proposals carrying a storage lot's actual temperature and/or a reefer power-outage duration are independently verified against the commodity class's reference bands (F4 deep-frozen / F3 frozen / C3 chilled).
- **Cross-actor handoff compatibility** (`reason-handoff-cold-chain-window-incompatible-with-assigned-commodity-class`) — when an inbound or outbound lot proposal carries a `:handoff` record (the wire shape cloud-itonami-isic-1075's own `:coordinate-shipment` proposals populate — see superproject ADR-2607177600), this actor independently verifies the handoff's declared cold-chain-temp-min-c/max-c window overlaps the lot's assigned commodity class's storage band — catching a temperature-tier mismatch (e.g. a 0C-3C chilled handoff assigned to an F4 deep-frozen bin) before it reaches this actor's own store. No shared code with isic-1075, just the same field names on both sides.
- **Inbound quantity wired into capacity-concentration-limit** — `:log-inbound-shipment` proposals that carry both `:lot/quantity-kg` and `:capacity/total-units` are also checked against `default-concentration-limit` via the same kernel `:capacity/allocate` uses, so an actually-received quantity — not only a declared allocation — is held to the same 25% cap.
- **Cross-actor grid-outage duration cross-check** (`:grid-outage-duration-mismatch`, SOFT escalation, never a hard hold) — when an inbound or outbound lot proposal carries `:grid-outage/source-actor` + `:grid-outage/event-id` + `:grid-outage/duration-minutes` (a copy of an upstream grid-transmission-operator actor's own committed outage-event record — e.g. cloud-itonami-isic-3510's `grid.facts`, see superproject ADR-2608510000), this actor independently cross-checks that reference against its OWN self-reported `:lot/power-outage-minutes` (`coldchain.registry/grid-outage-duration-mismatch?`, ±15 minutes tolerance). A mismatch routes to human escalation — mirroring cloud-itonami-isic-1075's own `:supplier-not-verified` soft-escalation precedent — rather than an unconditional hold, since a self-report disagreeing with an independent grid-operator record is a traceability concern, not proof either side is wrong. Entirely optional and asymmetric (same design as the isic-1075 handoff above): this actor works standalone with zero, one, or many independent grid operators, and isic-3510 has no code path that depends on this actor either. `coldchain.governor/check`/`censor` gained a third `:escalate`/`:escalated` status/bucket for this signal, alongside the pre-existing `:pass`/`:hold`.

### Design boundary: standalone, zero cloud-itonami dependency

This repo deliberately does **not** depend on `cloud-itonami` (`deps.edn`: `:deps {}`) and `coldchain.governor` does **not** call `cloud-itonami.security-governor/check`. This follows the exact precedent **ADR-2607176501** already established for `cloud-itonami-isic-1075` when the identical question came up for the identical `SecurityIncidentGovernor` (ADR-2607176500):

1. **Fleet standalone convention** (ADR-2607011000) — each `cloud-itonami-isic-*`/`-jsic-*` actor is a separate, forkable repo, ideally with zero dependency on cloud-itonami core.
2. **Layer mismatch** — `SecurityIncidentGovernor`'s 4 invariants (circuit-breaker / pii-precheck / disclosure-guard / bcp-precondition) are cloud-itonami **platform**-layer vocabulary (`:tenant/onboard` / `:incident/declare` / `:disclosure/publish`), enforced when an actor onboards **as a tenant** to the cloud-itonami platform — not vocabulary this actor's own coldchain domain uses.

Concretely: when this actor (or any physical-ops warehousing tenant) onboards to the cloud-itonami platform, the onboarding proposal carries `:tenant/jsic "4721"`, and `cloud-itonami.security-governor`'s bcp-precondition rule (extended with `physical-ops-jsics` for exactly this case, see this repo's ADR) enforces `:bcp/manual-fallback-ref` **there** — not in this repo. See `cloud-itonami.security-governor-test/tenant-onboard-for-a-physical-ops-jsic-requires-a-manual-fallback-ref` in the `cloud-itonami` repo for that enforcement's test coverage.

## Testing

```bash
# Run full test suite
clojure -M:test

# Check code quality
clojure -M:lint

# Run demo simulation
clojure -M:run
```

## Standalone Use

This repo is **forkable outside the workspace**. If cloning standalone (not in the kotoba-lang monorepo), override `:local/root` paths in `deps.edn`'s `:dev` alias:

```clojure
{:aliases {:dev {:override-deps
                 {io.github.kotoba-lang/langchain {:git/url "https://github.com/kotoba-lang/langchain" :git/tag "v0.1.0"}
                  io.github.kotoba-lang/langgraph {:git/url "https://github.com/kotoba-lang/langgraph" :git/tag "v0.1.0"}}}}}
```

## License

AGPL-3.0-or-later. Forking/contribution welcome; see `CONTRIBUTING.md`.

## Security

Report security issues to the issue tracker or private disclosure; see `SECURITY.md`.

---

Part of **cloud-itonami**: autonomous actor fleet for regulated industries. See [github.com/cloud-itonami](https://github.com/cloud-itonami).
