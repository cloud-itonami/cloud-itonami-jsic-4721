(ns coldchain.governor
  "coldchain-governor -- the independent compliance layer for
  cloud-itonami-jsic-4721 (refrigerated warehousing / 冷蔵倉庫業, JSIC
  4721). Paired with cloud-itonami-isic-1075 (prepared-meals
  manufacturing) to model the 2026-07 Nichirei cold-storage
  cyber-incident case study end to end: isic-1075 ~ Nichirei Foods
  (the manufacturer), this actor ~ Nichirei Logistics Group (the
  cold-chain 3PL whose ~4-day in/outbound stoppage and ~5,000-client
  fan-out were ADR-2607176500's root causes #1/#2/#3). See this repo's
  own ADR for full context.

  ── Design boundary: standalone, zero cloud-itonami dependency ──

  This repo deliberately does NOT depend on `cloud-itonami` (see
  deps.edn: `:deps {}`) and this governor does NOT call
  `cloud-itonami.security-governor/check`. That is not an oversight --
  it follows the EXACT precedent ADR-2607176501 already established
  for cloud-itonami-isic-1075 when the same question came up for the
  same SecurityIncidentGovernor (ADR-2607176500):

    1. Fleet standalone convention (ADR-2607011000) — each
       cloud-itonami-isic-*/-jsic-* actor is a separate, forkable repo
       with (ideally) zero dependency on cloud-itonami core. Adding a
       `:local/root` dependency on `../../gftdcojp/cloud-itonami` here
       would also transitively pull in cloud-itonami's own ~25-deep
       sibling dependency graph (mail/mailer/tayori/teian/koyomi/
       kaisha/denrei/cacao/kotoba-ledger/bonsai/nekko/ipns/
       com-cloudflare/plm/product-party/unspsc/cloud-mamori/langgraph/
       langchain/... -- all `:local/root`) just to reuse ~200 lines of
       pure kernel code that only needs `clojure.string`.
    2. Layer mismatch — SecurityIncidentGovernor's 4 invariants
       (circuit-breaker/pii-precheck/disclosure-guard/bcp-precondition)
       are cloud-itonami PLATFORM-layer vocabulary
       (`:tenant/onboard`/`:incident/declare`/`:disclosure/publish`),
       enforced when an actor onboards AS A TENANT to the cloud-itonami
       platform -- not vocabulary this actor's own coldchain domain
       (inbound/storage/outbound/dispatch of refrigerated goods) uses
       or should re-implement.

  Concretely: when cloud-itonami-jsic-4721 (or any physical-ops
  warehousing tenant) onboards to the cloud-itonami platform, the
  onboarding proposal carries `:tenant/jsic \"4721\"` (this vertical's
  JSIC classification -- ISIC has no dedicated code for 冷蔵倉庫業, see
  this repo's ADR), and cloud-itonami's own
  `cloud-itonami.security-governor`'s bcp-precondition rule (extended
  with `physical-ops-jsics` for exactly this case) enforces
  `:bcp/manual-fallback-ref` there -- see
  `cloud-itonami.security-governor-test/tenant-onboard-for-a-physical-
  ops-jsic-requires-a-manual-fallback-ref` in the cloud-itonami repo
  for that enforcement's own test coverage. This repo's test suite
  does not re-test that path (there is no code here that could -- see
  above), only this repo's own invariant below.

  ── This governor's own invariant ──

  capacity-concentration-limit (kernel:
  coldchain.kernels.concentration-verdict) — a single client tenant's
  allocated share of this warehouse's TOTAL capacity may never exceed
  `cv/default-concentration-limit` (25%) without a hold + human
  escalation. Direct, actor-specific mitigation for ADR-2607176500 root
  cause #3 (Nichirei Logistics Group's ~5,000-client
  single-point-of-failure concentration) -- this actor structurally
  caps how much of ITS OWN physical capacity any one client can come
  to depend on. This same kernel is also wired directly into
  `:log-inbound-shipment` (see `inbound-concentration-violations`) so
  an ACTUALLY received quantity, not just a declared `:capacity/
  allocate` number, is checked against the limit.

  The SAME kernel functions (`cv/concentration-ratio`/`cv/
  concentration-limit-exceeded?`/`cv/default-concentration-limit` --
  no new kernel code) are, symmetrically, also wired into
  `:log-outbound-shipment` (see `outbound-concentration-violations`,
  superproject ADR-2800000500) to flag the MIRROR-IMAGE concentration
  risk on the downstream side: a single outbound `:handoff` whose
  `:handoff/quantity-kg` alone represents more than `default-
  concentration-limit` of this warehouse's `:capacity/total-units` in
  ONE dispatch. Root cause #3 of ADR-2607176500 was Nichirei Logistics
  Group's fan-out concentrating too much of ITS OWN capacity onto any
  one client; this is the same single-point-of-failure shape viewed
  from the opposite end of the pipe -- this warehouse concentrating
  too much of a single dispatch onto any one downstream client (KFC-
  like/Aeon-like/Kura-Sushi-like actors depending disproportionately
  on this one 3PL for one shipment). Deliberately scoped identically
  to the inbound wiring: a single-proposal ratio check, no tenant-
  level cumulative outbound tracking (that would need a store-layer
  schema extension -- explicitly out of scope, see ADR-2607177600's
  own Consequences and ADR-2800000500). Optional on the SAME basis as
  every other check here: a `:log-outbound-shipment` proposal without
  a `:handoff` record, or one whose `:handoff` lacks `:handoff/
  quantity-kg`, is never held on this basis -- and the PRE-EXISTING,
  unrelated `:lot/quantity-kg` field (this actor's own self-reported
  outbound quantity, unchanged, see `outbound-shipment-quantity-kg-is-
  not-checked-against-concentration-limit`) still is not checked
  against this limit -- only the cross-actor `:handoff/quantity-kg`
  payload is, since that is the number that actually describes what
  is being concentrated onto one named downstream recipient.

  ── Cross-actor handoff (isic-1075 -> jsic-4721) ──

  `:log-inbound-shipment`/`:log-outbound-shipment` proposals MAY carry
  a `:handoff` record -- the wire shape cloud-itonami-isic-1075's own
  `:coordinate-shipment` proposals populate (superproject
  ADR-2607177600). This actor independently verifies its own half of
  that contract (no shared code with isic-1075, just the same field
  names): the handoff's declared cold-chain-temp-min-c/max-c window
  must overlap the lot's assigned commodity class's storage band (see
  `lot-physical-violations` and `coldchain.facts/handoff-compatible-
  with-commodity-class?`).

  ── Downstream handoff issuance (jsic-4721 -> isic-5610/isic-4711/isic-4719) ──

  This actor is ALSO, symmetrically, the ISSUING side of the exact
  SAME `:handoff` wire shape on outbound dispatch to downstream
  food-service/retail actors (superproject ADR-2800000500) --
  cloud-itonami-isic-5610 (community food-service operations, ~KFC),
  cloud-itonami-isic-4711 (community retail operations, ~Aeon), and
  cloud-itonami-isic-4719 (other non-specialized-store retail, ~Kura
  Sushi) -- the direct-downstream fan-out the 2026-07 Nichirei
  cold-storage cyber-incident case study's root cause #2 describes.
  `:log-outbound-shipment` needed NO new field to carry this: the SAME
  `:handoff` key `lot-physical-violations` already reads (above) works
  in either direction -- only the record's own `:handoff/source-actor`
  value differs (`\"cloud-itonami-jsic-4721\"` here, vs.
  `\"cloud-itonami-isic-1075\"` on the inbound side), and this
  governor's own temperature-tier-overlap check does not read that
  field, so it validates a self-issued outbound handoff exactly as it
  already validated a received inbound one. No new predicate, no new
  reason string, no new dependency -- see
  `coldchain.facts`'s own \"Outbound Handoff Issuance\" section.

  ── Cross-actor grid-outage reference (isic-3510 -> jsic-4721) ──

  `:log-inbound-shipment`/`:log-outbound-shipment` proposals MAY ALSO
  carry three flat reference fields -- `:grid-outage/source-actor`,
  `:grid-outage/event-id`, `:grid-outage/duration-minutes` -- copied
  from a committed outage-event record published by an upstream
  grid-transmission-operator actor (e.g. cloud-itonami-isic-3510's
  `grid.facts`/`grid.governor`, see superproject ADR-2608510000 for
  the full shared shape). Same asymmetric-optional, no-shared-code
  design as the isic-1075 handoff above, but a DIFFERENT wire shape
  (three flat top-level keys here, vs. one nested `:handoff` map for
  the isic-1075 contract) and a DIFFERENT enforcement tier: a mismatch
  between this actor's own self-reported `:lot/power-outage-minutes`
  and the referenced grid-operator record's `:grid-outage/duration-
  minutes` is a SOFT escalation (`:grid-outage-duration-mismatch`,
  see `grid-outage-duration-mismatch-escalations` below), never a hard
  hold -- mirroring cloud-itonami-isic-1075's own `:supplier-not-
  verified` soft-escalation precedent (mealops.governor), not this
  actor's `lot-physical-violations` hard-hold checks. A self-report
  disagreeing with an independent grid-operator record is real
  cross-actor traceability risk, but it is not proof either side is
  wrong (clock skew, rounding, a partial/preliminary grid-side record,
  etc.) -- unlike a physically-impossible storage-temperature
  excursion, it is not grounds to unconditionally refuse the
  shipment-logging proposal on its own; it always routes to human
  review instead. Like this actor's `:handoff` cross-check, this actor
  runs standalone and works with zero, one, or many independent grid
  operators; isic-3510 is entirely optional and this actor has no code
  path that depends on it.

  ── Power-metering reconciliation (isic-3510 -> jsic-4721) ──

  `:reconcile-power-metering` (new kind, superproject ADR-2800001100)
  receives a `:power-metering/*` reading logged by an upstream
  distribution-utility actor (e.g. cloud-itonami-isic-3510's own
  `grid.gridadvisor/log-metering-reading`/`grid.governor` -- a
  SEPARATE, entirely optional, no-shared-code cross-actor contract on
  the SAME (isic-3510, jsic-4721) pair as the grid-outage reference
  above, but for STEADY-STATE consumption reconciliation rather than
  outage events) plus this proposal's OWN `:equipment-assets` list
  (the equipment-asset records this actor has registered, see
  `:register-equipment-asset` above). `power-metering-deviation-
  escalations` (kernel: `coldchain.kernels.power-metering-verdict`)
  independently computes what consumption THIS actor would EXPECT from
  its registered equipment-assets' rated power draw and a simple
  operating-hours overlap against the metering period, and SOFT-
  escalates (never a hard hold -- electricity consumption has normal
  business variance) when the upstream self-reported `:consumed-kwh`
  deviates from that expectation by more than `pmv/default-deviation-
  threshold` (default +/-20%). This kind has no HARD check of its own
  at all (unlike `:register-equipment-asset`'s required-fields/
  double-registration guards): closed-allowlist membership is its only
  hard gate, the same asymmetric-optional, escalate-only posture
  `grid-outage-duration-mismatch-escalations` establishes for the
  OTHER isic-3510 cross-actor reference.

  Composition shape (multimethod `rule` dispatching on `:kind`, closed
  allowlist, `{:status :pass|:hold|:escalate :reasons [...]}`) mirrors
  cloud-itonami.security-governor's own shape -- the two governors
  look alike by design even though they don't call each other.

  ── Equipment-asset linkage (isic-2813 -> jsic-4721) ──

  `:register-equipment-asset` (new op, superproject equipment-asset-
  linkage ADR) registers WHICH manufactured unit (e.g. a
  cloud-itonami-isic-2813 industrial-refrigeration-compressor) this
  warehouse actually operates -- the `:equipment-asset` shared shape
  (see `coldchain.facts`'s own \"Equipment-Asset Linkage\" section for
  the full field list; no shared code, no shared store, same design
  as the isic-1075 `:handoff`/isic-3510 grid-outage references above).
  TWO independent checks, both additive, neither touching any
  existing rule:

    1. required-fields (HARD hold, `reason-equipment-asset-missing-
       fields`) -- a proposal missing any of `:equipment-asset/id`/
       `:unit-type-id`/`:source-actor`/`:dispatch-ref` is refused; this
       actor never registers a partial/fabricated equipment-asset
       record.
    2. double-registration guard (HARD hold, `reason-equipment-asset-
       already-registered`) -- the SAME 'never register the same
       correlation id twice' discipline every dispatch/certificate-
       style double-commit guard in this fleet establishes (e.g.
       cloud-itonami-isic-2813's `already-dispatched-violations`),
       adapted to THIS governor's stateless/pure shape: `registry`'s
       `:equipment-asset/registered-ids` (governor-supplied context,
       may be `{}`/absent, analogous to `cloud-itonami.security-
       governor`'s `:active-incident?` context key) carries the ids
       already registered -- this governor never queries a store
       itself.

  `:log-inbound-shipment`/`:log-outbound-shipment` proposals MAY ALSO
  carry an optional `:maintenance-notice` reference (a nested map, the
  wire shape a downstream isic-2813 `:issue-maintenance-notice` event
  populates) -- `equipment-asset-maintenance-notice-escalations` below
  cross-checks its `:maintenance-notice/equipment-asset-id` against
  the SAME `registry` `:equipment-asset/registered-ids` context, and
  SOFT-escalates (never a hard hold) when it matches an asset this
  actor has already registered -- 'the equipment that received a
  maintenance notice is actually equipment we operate' is useful
  cross-actor traceability, the same non-hard-hold, escalate-only
  pattern `grid-outage-duration-mismatch-escalations`/cloud-itonami-
  isic-1075's own `mealops.governor/supplier-not-verified-escalation`
  establish. Existing `lot-physical-violations`/`grid-outage-
  duration-mismatch-escalations` are unchanged -- this is additive
  wiring alongside them, not a replacement."
  (:require [coldchain.kernels.concentration-verdict :as cv]
            [coldchain.kernels.power-metering-verdict :as pmv]
            [coldchain.facts :as facts]
            [coldchain.registry :as registry]))

(defn- hold [reasons] {:status :hold :reasons (vec reasons)})
(defn- escalate [escalations] {:status :escalate :escalations (vec escalations)})
(def ^:private pass {:status :pass})

(def allowed-kinds
  "Closed allowlist of proposal kinds this actor may ever make -- same
  closed-vocabulary discipline as cloud-itonami-isic-1075/-5210's
  `allowed-ops`. Anything outside this set is refused unconditionally."
  #{:tenant/onboard :capacity/allocate :log-inbound-shipment
    :log-outbound-shipment :flag-temperature-excursion
    :register-equipment-asset :reconcile-power-metering})

(def reason-kind-not-allowed
  "proposal :kind is outside this actor's closed allowlist (:tenant/onboard / :capacity/allocate / :log-inbound-shipment / :log-outbound-shipment / :flag-temperature-excursion / :register-equipment-asset)")

(def reason-storage-temp-out-of-range
  "storage lot's actual temperature falls outside its commodity class's safe storage window (cold-chain break)")

(def reason-power-outage-exceeds-max
  "reefer/compressor power-outage duration exceeded the commodity class's maximum tolerable outage")

(def reason-handoff-cold-chain-window-incompatible-with-assigned-commodity-class
  "handoff's declared cold-chain-temp-min-c/max-c window does not overlap the storage lot's assigned commodity class's safe storage-temperature band (temperature-tier mismatch, e.g. a chilled handoff assigned to a deep-frozen bin)")

(def reason-grid-outage-duration-mismatch
  "self-reported :lot/power-outage-minutes disagrees (beyond registry/default-grid-outage-tolerance-minutes) with the independently reported :grid-outage/duration-minutes from the referenced grid-transmission-operator outage event -- SOFT escalation to a human, not a hard hold (mirrors cloud-itonami-isic-1075's :supplier-not-verified soft-escalation pattern)")

(def reason-equipment-asset-missing-fields
  "a :register-equipment-asset proposal is missing one or more required fields (:equipment-asset/id / :unit-type-id / :source-actor / :dispatch-ref) -- this actor never registers a partial/fabricated equipment-asset record")

(def reason-equipment-asset-already-registered
  "a :register-equipment-asset proposal's :equipment-asset/id is already present in the governor-supplied registry context's :equipment-asset/registered-ids -- the same double-commit-guard discipline every dispatch/certificate-style guard in this fleet establishes, adapted to this governor's stateless/pure shape")

(def reason-equipment-asset-maintenance-notice-for-registered-asset
  "an inbound/outbound lot proposal's optional :maintenance-notice reference names an :equipment-asset/id this actor has already registered -- SOFT escalation for human visibility (mirrors cloud-itonami-isic-1075's :supplier-not-verified soft-escalation pattern), never a hard hold")

(def reason-power-metering-deviation-exceeds-threshold
  pmv/reason-power-metering-deviation-exceeds-threshold)

(defmulti ^:private rule
  "One proposal (+ registry context) -> seq of hold-reason strings
  (empty = no kind-specific objection). Same dispatch shape as
  cloud-itonami.security-governor/rule."
  (fn [_registry proposal] (:kind proposal)))

(defmethod rule :default [_ _] [])

(defmethod rule :capacity/allocate [_ proposal]
  (let [allocated (:capacity/allocated-units proposal)
        total (:capacity/total-units proposal)
        ratio (cv/concentration-ratio allocated total)]
    (if (= 1 (cv/concentration-limit-exceeded? ratio cv/default-concentration-limit))
      [cv/reason-concentration-limit-exceeded]
      [])))

(defn- lot-physical-violations
  "Shared cold-chain physical-discipline check for inbound/outbound lot
  proposals: the lot's actual storage temperature and (if present) any
  reefer/compressor power-outage duration, independently verified
  against its commodity class's reference bands
  (coldchain.facts/commodity-classes). Both fields are optional on the
  proposal -- a proposal that doesn't carry them is not held on this
  basis (this actor's `:maturity :blueprint` scope doesn't require
  telemetry to already be wired everywhere).

  Also, when the proposal carries a `:handoff` record (the
  isic-1075<->jsic-4721 cross-actor wire shape, see
  `coldchain.facts`'s \"Cross-Actor Handoff\" section), INDEPENDENTLY
  verify via `facts/handoff-compatible-with-commodity-class?` that the
  handoff's declared cold-chain-temp-min-c/max-c window is not a
  temperature-tier mismatch against the lot's assigned commodity
  class -- optional on the same basis as the other two checks."
  [proposal]
  (let [actual-temp (:lot/storage-temp-c proposal)
        outage-minutes (:lot/power-outage-minutes proposal)
        commodity (facts/commodity-class-by-id (:lot/commodity-class proposal))
        handoff (:handoff proposal)
        handoff-min (:handoff/cold-chain-temp-min-c handoff)
        handoff-max (:handoff/cold-chain-temp-max-c handoff)]
    (cond-> []
      (and commodity actual-temp
           (registry/storage-temp-out-of-range?
            actual-temp (:storage-temp-min-c commodity) (:storage-temp-max-c commodity)))
      (conj reason-storage-temp-out-of-range)

      (and commodity outage-minutes
           (registry/power-outage-exceeds-max?
            outage-minutes (:max-power-outage-minutes commodity)))
      (conj reason-power-outage-exceeds-max)

      (and commodity (map? handoff) (some? handoff-min) (some? handoff-max)
           (not (facts/handoff-compatible-with-commodity-class? handoff-min handoff-max commodity)))
      (conj reason-handoff-cold-chain-window-incompatible-with-assigned-commodity-class))))

(defn- grid-outage-duration-mismatch-escalations
  "SOFT escalation, never a hard hold -- colocated here with `lot-
  physical-violations`'s existing self-reported power-outage check
  (`reason-power-outage-exceeds-max`, above) for the same
  `:log-inbound-shipment`/`:log-outbound-shipment` proposals, but kept
  structurally INDEPENDENT of that hard-violations vector: this actor
  runs fully standalone and isic-3510 is entirely OPTIONAL (same
  asymmetric-optional design as the isic-1075 `:handoff` record, see
  this ns's own docstring's \"Cross-actor grid-outage reference\"
  section), and disagreeing with an unverified self-report is a
  cross-actor traceability concern -- not grounds to unconditionally
  block the shipment-logging proposal on its own, same reasoning as
  cloud-itonami-isic-1075's `mealops.governor/supplier-not-verified-
  escalation`.

  Only evaluated when the proposal carries ALL of `:grid-outage/
  source-actor`, `:grid-outage/event-id`, `:grid-outage/duration-
  minutes` (the cloud-itonami-isic-3510 outage-event reference) AND
  its own self-reported `:lot/power-outage-minutes` -- a proposal
  missing any of these four fields is not escalated on this basis
  (asymmetric-optional, same discipline as every check in
  `lot-physical-violations`)."
  [proposal]
  (let [self-reported (:lot/power-outage-minutes proposal)
        grid-source (:grid-outage/source-actor proposal)
        grid-event (:grid-outage/event-id proposal)
        grid-minutes (:grid-outage/duration-minutes proposal)]
    (when (and (some? self-reported) (some? grid-source) (some? grid-event) (some? grid-minutes)
               (registry/grid-outage-duration-mismatch?
                self-reported grid-minutes registry/default-grid-outage-tolerance-minutes))
      [reason-grid-outage-duration-mismatch])))

(defn- equipment-asset-maintenance-notice-escalations
  "SOFT escalation, never a hard hold -- colocated here with `grid-
  outage-duration-mismatch-escalations` for the same `:log-inbound-
  shipment`/`:log-outbound-shipment` proposals, but reading a
  DIFFERENT optional reference field (`:maintenance-notice`, a nested
  map -- see `coldchain.facts`'s own \"Equipment-Asset Linkage\"
  section). When present and its `:maintenance-notice/equipment-
  asset-id` names an equipment asset THIS actor has already
  registered (`registry`'s `:equipment-asset/registered-ids`, the
  SAME context `:register-equipment-asset`'s double-registration
  guard reads), escalate for human visibility: 'the equipment that
  received a maintenance notice is actually equipment we operate' is
  useful cross-actor traceability, not grounds to block the shipment-
  logging proposal on its own -- same reasoning as `grid-outage-
  duration-mismatch-escalations`. A proposal missing `:maintenance-
  notice`, or whose referenced id is not (yet) registered here, is
  never escalated on this basis (asymmetric-optional, same discipline
  as every check in `lot-physical-violations`)."
  [registry proposal]
  (let [registered (:equipment-asset/registered-ids registry #{})]
    (when (facts/equipment-asset-maintenance-notice-for-registered-asset?
           (:maintenance-notice proposal) registered)
      [reason-equipment-asset-maintenance-notice-for-registered-asset])))

(defn- power-metering-deviation-escalations
  "SOFT escalation, never a hard hold -- for `:reconcile-power-
  metering` proposals only. Independently computes what this actor's
  OWN registered equipment-assets (the proposal's own `:equipment-
  assets` list) would be EXPECTED to consume over
  [`:power-metering/period-start-iso`, `:power-metering/period-end-
  iso`], via `coldchain.kernels.power-metering-verdict/expected-
  consumption-kwh` + `coldchain.facts/unit-type-power-kw-of`, and
  compares that against the upstream distribution-utility's own
  self-reported `:power-metering/consumed-kwh`. Only evaluated when the
  proposal carries a numeric `:power-metering/consumed-kwh`, a
  non-empty `:equipment-assets` seq, and both period timestamps -- a
  proposal missing any of these is not escalated on this basis
  (asymmetric-optional, same discipline as every check in `lot-
  physical-violations`/`grid-outage-duration-mismatch-escalations`)."
  [proposal]
  (let [consumed (:power-metering/consumed-kwh proposal)
        assets (:equipment-assets proposal)
        start (:power-metering/period-start-iso proposal)
        end (:power-metering/period-end-iso proposal)]
    (when (and (number? consumed) (seq assets) (some? start) (some? end))
      (let [expected (pmv/expected-consumption-kwh assets facts/unit-type-power-kw-of start end)
            ratio (pmv/deviation-ratio consumed expected)]
        (when (= 1 (pmv/deviation-exceeds-threshold? ratio pmv/default-deviation-threshold))
          [reason-power-metering-deviation-exceeds-threshold])))))

(defn- soft-escalations
  "Kind-specific SOFT signals -- never a hold, but force `:status
  :escalate` (human sign-off) even when the hard checks in `rule` are
  clean. `grid-outage-duration-mismatch-escalations` and
  `equipment-asset-maintenance-notice-escalations` for `:log-inbound-
  shipment`/`:log-outbound-shipment`, `power-metering-deviation-
  escalations` for `:reconcile-power-metering` -- `registry` is the
  SAME governor-supplied context `rule`/`check` already thread through
  (default `{}`), needed here for the equipment-asset cross-check's
  `:equipment-asset/registered-ids`."
  [registry proposal]
  (case (:kind proposal)
    (:log-inbound-shipment :log-outbound-shipment)
    (into (grid-outage-duration-mismatch-escalations proposal)
          (equipment-asset-maintenance-notice-escalations registry proposal))
    :reconcile-power-metering
    (power-metering-deviation-escalations proposal)
    []))

(defn- inbound-concentration-violations
  "Direct, minimal wiring of an ACTUAL received quantity into the
  existing capacity-concentration-limit kernel
  (coldchain.kernels.concentration-verdict), rather than only checking
  the DECLARED allocation at `:capacity/allocate` time. `:capacity/
  allocate`'s own check validates a proposed allocation number; this
  reuses the exact same kernel functions/reason/default limit --
  no new kernel logic, only new wiring -- to also validate the
  physical fact of what was actually received on THIS inbound
  shipment, when the caller supplies both `:lot/quantity-kg` (the
  received amount) and `:capacity/total-units` (this warehouse's total
  capacity, same unit convention the caller uses for its own
  `:capacity/allocate` proposals). Optional, same discipline as
  `lot-physical-violations`: a proposal missing either field is not
  held on this basis."
  [proposal]
  (let [ratio (cv/concentration-ratio (:lot/quantity-kg proposal) (:capacity/total-units proposal))]
    (if (= 1 (cv/concentration-limit-exceeded? ratio cv/default-concentration-limit))
      [cv/reason-concentration-limit-exceeded]
      [])))

(defn- outbound-concentration-violations
  "Mirror-image wiring of `inbound-concentration-violations` into the
  SAME capacity-concentration-limit kernel, but for the downstream/
  outbound side (superproject ADR-2800000500): does a single
  `:log-outbound-shipment` proposal's `:handoff` record's own
  `:handoff/quantity-kg` (the cross-actor payload quantity handed to
  ONE named downstream client -- e.g. cloud-itonami-isic-5610/-4711/
  -4719) represent more than `cv/default-concentration-limit` of this
  warehouse's `:capacity/total-units`? No new kernel logic -- reuses
  `cv/concentration-ratio`/`cv/concentration-limit-exceeded?`/`cv/
  default-concentration-limit` exactly as `inbound-concentration-
  violations` does. Optional, same discipline as every other check in
  this ns: a proposal with no `:handoff`, or a `:handoff` missing
  `:handoff/quantity-kg`, or a proposal missing `:capacity/total-
  units`, is never held on this basis. Deliberately reads `:handoff/
  quantity-kg` (the cross-actor handed-off amount), NOT the pre-
  existing top-level `:lot/quantity-kg` self-report that `:log-
  outbound-shipment` already leaves unchecked against this limit (see
  `outbound-shipment-quantity-kg-is-not-checked-against-concentration-
  limit`) -- those are two different numbers for two different
  purposes, and only the former describes downstream-client
  concentration."
  [proposal]
  (let [handoff (:handoff proposal)
        handoff-qty (:handoff/quantity-kg handoff)
        total (:capacity/total-units proposal)
        ratio (cv/concentration-ratio handoff-qty total)]
    (if (= 1 (cv/concentration-limit-exceeded? ratio cv/default-concentration-limit))
      [cv/reason-concentration-limit-exceeded]
      [])))

(defmethod rule :log-inbound-shipment [_ proposal]
  (into (lot-physical-violations proposal) (inbound-concentration-violations proposal)))
(defmethod rule :log-outbound-shipment [_ proposal]
  (into (lot-physical-violations proposal) (outbound-concentration-violations proposal)))

(defn- equipment-asset-required-fields-present?
  [proposal]
  (every? some? [(:equipment-asset/id proposal)
                 (:equipment-asset/unit-type-id proposal)
                 (:equipment-asset/source-actor proposal)
                 (:equipment-asset/dispatch-ref proposal)]))

(defmethod rule :register-equipment-asset [registry proposal]
  (let [id (:equipment-asset/id proposal)
        registered (:equipment-asset/registered-ids registry #{})]
    (cond-> []
      (not (equipment-asset-required-fields-present? proposal))
      (conj reason-equipment-asset-missing-fields)

      (and (some? id) (contains? (set registered) id))
      (conj reason-equipment-asset-already-registered))))

(defn check
  "One proposal -> {:status :pass} | {:status :hold :reasons [...]}
  | {:status :escalate :escalations [...]}.
  `registry` is optional context (default {}), forwarded to `rule`
  for parity with cloud-itonami.security-governor's shape --
  `:register-equipment-asset`'s double-registration guard and
  `equipment-asset-maintenance-notice-escalations` both now read its
  `:equipment-asset/registered-ids`; every other existing rule still
  ignores it.

  `:escalate` is a strictly weaker signal than `:hold`: it is only
  ever returned when `reasons` (the hard-violation vector) is empty --
  a hard hold always wins. Existing callers that only ever look for
  `:hold` vs. everything-else (this actor's `:maturity :blueprint`
  scope so far never had a third status) see no behaviour change for
  any proposal that doesn't carry the optional grid-outage reference
  fields, since `escalations` is empty for those and this falls
  through to `pass` exactly as before."
  ([proposal] (check {} proposal))
  ([registry proposal]
   (let [kind (:kind proposal)
         allowlist-reasons (if (contains? allowed-kinds kind) [] [reason-kind-not-allowed])
         kind-reasons (rule registry proposal)
         reasons (-> []
                     (into allowlist-reasons)
                     (into kind-reasons))
         escalations (soft-escalations registry proposal)]
     (cond
       (seq reasons) (hold reasons)
       (seq escalations) (escalate escalations)
       :else pass))))

(defn censor
  "All proposals of one tick -> {:approved [...] :held [{:proposal
  :reasons}] :escalated [{:proposal :escalations}]}.
  Order-preserving; every proposal lands in exactly one bucket. Mirrors
  cloud-itonami.security-governor/censor's shape, extended with a
  third `:escalated` bucket for SOFT signals (currently only
  `:grid-outage-duration-mismatch`) that force human sign-off without
  being a hard hold -- any proposal that never produces a soft
  escalation lands in `:approved`/`:held` exactly as before this
  bucket existed."
  ([proposals] (censor {} proposals))
  ([registry proposals]
   (reduce (fn [acc p]
             (let [r (check registry p)]
               (case (:status r)
                 :hold (update acc :held conj {:proposal p :reasons (:reasons r)})
                 :escalate (update acc :escalated conj {:proposal p :escalations (:escalations r)})
                 (update acc :approved conj p))))
           {:approved [] :held [] :escalated []}
           proposals)))
