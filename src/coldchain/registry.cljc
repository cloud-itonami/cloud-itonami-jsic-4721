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
