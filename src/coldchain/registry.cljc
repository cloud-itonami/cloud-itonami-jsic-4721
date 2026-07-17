(ns coldchain.registry
  "Pure validation predicates for refrigerated-warehousing storage-lot
  discipline. Same shape/discipline as cloud-itonami-isic-1075's
  `mealops.registry` and cloud-itonami-isic-5210's `terminal.registry`
  -- no I/O, no host-clock calls (callers pass epoch-ms/durations in
  themselves)."
  )

(defn storage-temp-out-of-range?
  "Independently verify that a stored lot's actual temperature falls
  within its commodity class's safe storage window. Out of range is a
  cold-chain break -- the physical failure mode this actor exists to
  coordinate around (ADR-2607176500's case study: a cold-storage
  warehouse whose IT-incident response had no bearing on whether the
  reefers themselves stayed in range)."
  [actual-celsius min-celsius max-celsius]
  (or (< actual-celsius min-celsius)
      (> actual-celsius max-celsius)))

(defn power-outage-exceeds-max?
  "Independently verify that a reefer/compressor power-outage duration
  did not exceed the commodity class's maximum tolerable outage before
  the stored lot's safety margin is considered exhausted."
  [actual-outage-minutes max-outage-minutes]
  (> actual-outage-minutes max-outage-minutes))

(def default-grid-outage-tolerance-minutes
  "How many minutes of disagreement between this actor's own
  self-reported reefer/compressor power-outage duration (`:lot/power-
  outage-minutes`) and an independently-reported grid-transmission
  outage-event duration (`:grid-outage/duration-minutes`, e.g. from
  cloud-itonami-isic-3510) is tolerated before treating the two as a
  genuine mismatch. This actor's own operational choice (not a cited
  legal/regulatory number, unlike `coldchain.facts`'s commodity-class
  temperature bands): on-site power-outage duration is typically
  reported by warehouse staff to the nearest 5-10 minutes off a wall
  clock, while a grid operator's own record is typically derived from
  SCADA/telemetry timestamps -- a small gap is expected clock/rounding
  noise, not evidence of an actual discrepancy."
  15)

(defn grid-outage-duration-mismatch?
  "Independently verify whether a lot's self-reported reefer/
  compressor power-outage duration disagrees with an independently
  reported grid-transmission-operator outage-event duration by more
  than `tolerance-minutes`. Symmetric (works for the self-report being
  either longer or shorter than the grid record) -- a genuine
  disagreement in either direction is equally a traceability concern."
  [self-reported-minutes grid-reported-minutes tolerance-minutes]
  (let [delta (- self-reported-minutes grid-reported-minutes)
        abs-delta (if (neg? delta) (- delta) delta)]
    (> abs-delta tolerance-minutes)))
