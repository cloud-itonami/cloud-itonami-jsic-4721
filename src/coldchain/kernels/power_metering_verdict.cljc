(ns coldchain.kernels.power-metering-verdict
  "Pure kernel for cloud-itonami-jsic-4721's power-metering reconciliation
  -- the downstream half of the entirely optional, no-shared-code
  isic-3510 <-> jsic-4721 `:power-metering` cross-actor contract
  (superproject ADR-2800001000). Same 'pure kernel behind a governor'
  shape as `coldchain.kernels.concentration-verdict` -- no I/O, no
  store access.

  cloud-itonami-isic-3510 (a distribution-utility actor) logs a
  `:power-metering/*` reading for one of ITS feeders (period +
  `:consumed-kwh`); this actor independently computes what it
  EXPECTS that consumption to have been, from its OWN registered
  `:equipment-asset/*` records (each asset's unit-type's own rated
  `:power-requirement-kw`, see `coldchain.facts/unit-type-power-kw-of`)
  and a SIMPLE operating-hours overlap against the metering period (no
  decommission dates, maintenance downtime or partial-day fractional
  modelling -- deliberately out of scope, see
  `asset-operating-hours-in-period`). A deviation beyond
  `default-deviation-threshold` is useful cross-actor traceability
  (has this warehouse's equipment roster drifted from what the grid
  operator is actually billing/metering for it?), but electricity
  consumption has normal business variance (equipment duty-cycle,
  ambient-load swings, non-continuous operation this actor's own
  simple 24/7-overlap model does not capture) that is NOT grounds to
  hard-hold -- see `coldchain.governor`'s `power-metering-deviation-
  escalations` for the SOFT-escalation-only wiring."
  )

(defn- iso->epoch-ms
  "Parse an ISO-8601 UTC timestamp (e.g. \"2026-07-01T00:00:00Z\") to
  epoch milliseconds, or nil if unparseable/blank. `.cljc`-portable:
  JVM uses `java.time.Instant`, browser/Node uses the native `Date`."
  [iso]
  (when (and (string? iso) (not= iso ""))
    (try
      #?(:clj (.toEpochMilli (java.time.Instant/parse iso))
         :cljs (let [ms (.getTime (js/Date. iso))]
                 (when-not (js/isNaN ms) ms)))
      (catch #?(:clj Exception :cljs :default) _ nil))))

(defn asset-operating-hours-in-period
  "How many hours (double) was an equipment asset installed at
  `installed-at-iso` actually operating within
  [period-start-iso, period-end-iso]? A SIMPLE overlap calc (see ns
  docstring): if the asset was already installed before the period
  started, it ran the FULL period; if it was installed partway
  through, it ran from install to period-end; if it was installed
  AFTER the period ended (or any timestamp is unparseable, or the
  period itself is malformed), it contributes 0.0 -- fail towards
  'no contribution' rather than a fabricated hours figure."
  [installed-at-iso period-start-iso period-end-iso]
  (let [install-ms (iso->epoch-ms installed-at-iso)
        start-ms (iso->epoch-ms period-start-iso)
        end-ms (iso->epoch-ms period-end-iso)]
    (if (and install-ms start-ms end-ms (>= end-ms start-ms))
      (let [effective-start-ms (max install-ms start-ms)]
        (if (<= effective-start-ms end-ms)
          (/ (- end-ms effective-start-ms) 1000.0 60.0 60.0)
          0.0))
      0.0)))

(defn expected-consumption-kwh
  "Sum, over `equipment-assets`, of (this asset's unit-type's own rated
  power-requirement-kw x this asset's own operating-hours overlap with
  the metering period, see `asset-operating-hours-in-period`). Each
  asset is a map carrying at least `:equipment-asset/unit-type-id`/
  `:equipment-asset/installed-at-iso`. `power-kw-of` is a fn
  unit-type-id -> kW-or-nil (this actor's own `coldchain.facts/unit-
  type-power-kw-of` in production, an easily-swappable arg for tests)
  -- an asset whose unit-type-id is unrecognized contributes 0.0, it
  never fabricates a figure. Pure -- no I/O, no store access; the
  caller supplies the equipment-asset list."
  [equipment-assets power-kw-of period-start-iso period-end-iso]
  (reduce
   (fn [total {:keys [equipment-asset/unit-type-id equipment-asset/installed-at-iso]}]
     (let [kw (power-kw-of unit-type-id)
           hours (asset-operating-hours-in-period installed-at-iso period-start-iso period-end-iso)]
       (+ total (if (number? kw) (* (double kw) hours) 0.0))))
   0.0
   equipment-assets))

(def default-deviation-threshold
  "Default maximum FRACTIONAL deviation (0.0-1.0) between an upstream
  distribution-utility's self-reported `:power-metering/consumed-kwh`
  and this actor's own independently-computed `expected-consumption-
  kwh` before a `:reconcile-power-metering` proposal is SOFT-escalated
  for human review. 0.20 = up to +/-20% deviation is treated as normal
  business variance (equipment duty-cycle, ambient-load swings,
  non-continuous operation this actor's own simple 24/7-overlap model
  does not capture) -- NOT grounds to hard-hold, mirroring
  cloud-itonami-isic-1075's own non-hard-hold `:supplier-not-verified`
  escalation posture (see `coldchain.governor` ns docstring)."
  0.20)

(defn deviation-ratio
  "|consumed - expected| / expected, or nil if `expected-kwh` isn't a
  sane positive number -- fail towards 'can't compute' rather than a
  spurious escalation on malformed/zero-expected input (same
  discipline as `coldchain.kernels.concentration-verdict/
  concentration-ratio`)."
  [consumed-kwh expected-kwh]
  (when (and (number? consumed-kwh) (number? expected-kwh) (pos? expected-kwh))
    (let [delta (- (double consumed-kwh) (double expected-kwh))
          abs-delta (if (neg? delta) (- delta) delta)]
      (/ abs-delta (double expected-kwh)))))

(defn deviation-exceeds-threshold?
  "1 iff `ratio` is present AND strictly greater than `threshold`. A
  ratio exactly AT the threshold is NOT exceeded (boundary-inclusive
  pass, same convention as `concentration-limit-exceeded?`)."
  [ratio threshold]
  (if (and (some? ratio) (> ratio threshold)) 1 0))

(def reason-power-metering-deviation-exceeds-threshold
  "an upstream distribution-utility's self-reported :power-metering/consumed-kwh deviates from this actor's own independently-computed expected consumption (registered equipment-assets' rated power-requirement-kw x a simple operating-hours overlap) by more than default-deviation-threshold (default +/-20%) -- SOFT escalation to a human, never a hard hold (electricity consumption has normal business variance, mirrors cloud-itonami-isic-1075's :supplier-not-verified posture)")
