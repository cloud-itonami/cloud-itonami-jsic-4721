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

  Composition shape (multimethod `rule` dispatching on `:kind`, closed
  allowlist, `{:status :pass|:hold :reasons [...]}`) mirrors
  cloud-itonami.security-governor's own shape -- the two governors
  look alike by design even though they don't call each other."
  (:require [coldchain.kernels.concentration-verdict :as cv]
            [coldchain.facts :as facts]
            [coldchain.registry :as registry]))

(defn- hold [reasons] {:status :hold :reasons (vec reasons)})
(def ^:private pass {:status :pass})

(def allowed-kinds
  "Closed allowlist of proposal kinds this actor may ever make -- same
  closed-vocabulary discipline as cloud-itonami-isic-1075/-5210's
  `allowed-ops`. Anything outside this set is refused unconditionally."
  #{:tenant/onboard :capacity/allocate :log-inbound-shipment
    :log-outbound-shipment :flag-temperature-excursion})

(def reason-kind-not-allowed
  "proposal :kind is outside this actor's closed allowlist (:tenant/onboard / :capacity/allocate / :log-inbound-shipment / :log-outbound-shipment / :flag-temperature-excursion)")

(def reason-storage-temp-out-of-range
  "storage lot's actual temperature falls outside its commodity class's safe storage window (cold-chain break)")

(def reason-power-outage-exceeds-max
  "reefer/compressor power-outage duration exceeded the commodity class's maximum tolerable outage")

(def reason-handoff-cold-chain-window-incompatible-with-assigned-commodity-class
  "handoff's declared cold-chain-temp-min-c/max-c window does not overlap the storage lot's assigned commodity class's safe storage-temperature band (temperature-tier mismatch, e.g. a chilled handoff assigned to a deep-frozen bin)")

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

(defmethod rule :log-inbound-shipment [_ proposal]
  (into (lot-physical-violations proposal) (inbound-concentration-violations proposal)))
(defmethod rule :log-outbound-shipment [_ proposal] (lot-physical-violations proposal))

(defn check
  "One proposal -> {:status :pass} | {:status :hold :reasons [...]}.
  `registry` is optional context (default {}), forwarded to `rule`
  for parity with cloud-itonami.security-governor's shape even though
  no rule here currently reads it."
  ([proposal] (check {} proposal))
  ([registry proposal]
   (let [kind (:kind proposal)
         allowlist-reasons (if (contains? allowed-kinds kind) [] [reason-kind-not-allowed])
         kind-reasons (rule registry proposal)
         reasons (-> []
                     (into allowlist-reasons)
                     (into kind-reasons))]
     (if (seq reasons) (hold reasons) pass))))

(defn censor
  "All proposals of one tick -> {:approved [...] :held [{:proposal :reasons}]}.
  Order-preserving; every proposal lands in exactly one bucket. Mirrors
  cloud-itonami.security-governor/censor's shape."
  ([proposals] (censor {} proposals))
  ([registry proposals]
   (reduce (fn [acc p]
             (let [r (check registry p)]
               (if (= :hold (:status r))
                 (update acc :held conj {:proposal p :reasons (:reasons r)})
                 (update acc :approved conj p))))
           {:approved [] :held []}
           proposals)))
