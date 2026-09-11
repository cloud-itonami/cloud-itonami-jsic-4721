(ns coldchain.kernels.concentration-verdict
  "Pure kernel for cloud-itonami-jsic-4721's capacity-concentration-limit
  invariant -- a direct, actor-specific mitigation for ADR-2607176500
  root cause #3 (Nichirei Logistics Group's ~5,000-client
  single-point-of-failure concentration: one warehouse operator's
  cyber incident fanned out to ~5,000 downstream customers with no
  alternative capacity). No I/O, no store access -- same 'pure kernel
  behind a governor' shape as cloud-itonami.kernels.security-verdict,
  which this repo deliberately does NOT depend on (see coldchain.governor's
  docstring for the standalone-fleet-convention boundary, ADR-2607176501).

  One predicate: a single client tenant's allocated share of this
  warehouse's TOTAL capacity must never exceed
  `default-concentration-limit` without a HOLD + human escalation --
  structurally capping how much of this actor's own physical capacity
  any one client can come to depend on (and, symmetrically, how much
  of the warehouse's continuity any one client's own disruption could
  take down)."
  )

(def default-concentration-limit
  "Default maximum share (0.0-1.0 ratio) of TOTAL warehouse capacity a
  single client tenant may be allocated before a `:capacity/allocate`
  proposal is held for human escalation. 0.25 = no single client may
  ever be allocated more than a quarter of this warehouse's total
  capacity -- a per-tenant cap enforced at EVERY allocation proposal
  (not a portfolio-level metric computed after the fact), deliberately
  far below the aggregate concentration Nichirei Logistics Group's
  single-operator model exhibited across its ~5,000 clients."
  0.25)

(defn concentration-ratio
  "allocated / total, as a double, or nil if either input isn't a sane
  positive number (total must be > 0; allocated must be a
  non-negative number). A nil ratio never triggers
  `concentration-limit-exceeded?` -- fail towards 'can't compute'
  rather than a spurious hold on malformed input; presence/shape
  checks belong to the governor's closed allowlist, not this
  arithmetic kernel."
  [allocated total]
  (when (and (number? allocated) (number? total) (pos? total) (>= allocated 0))
    (/ (double allocated) (double total))))

(defn concentration-limit-exceeded?
  "1 iff `ratio` is present AND strictly greater than `limit`. A ratio
  exactly AT the limit is NOT exceeded (boundary-inclusive pass)."
  [ratio limit]
  (if (and (some? ratio) (> ratio limit)) 1 0))

(def reason-concentration-limit-exceeded
  "single client tenant's allocated warehouse capacity exceeds the capacity-concentration-limit (default 25% of total) -- hold + human escalation (root cause #3, ADR-2607176500)")
