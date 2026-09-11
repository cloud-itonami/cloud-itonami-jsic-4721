(ns coldchain.phase
  "Phase machine: the states a refrigerated-warehousing storage lot
  transits through, from inbound receiving to outbound dispatch.

  State machine:
    :inbound -> :storage -> :outbound -> :dispatched -> :archived

  `:inbound` is receiving/putaway into a cold-storage slot; `:storage`
  is held cold-chain custody; `:outbound` is pick/stage for shipment;
  `:dispatched` is handed off to carrier; `:archived` is the terminal
  state. Mirrors cloud-itonami-isic-1075's `mealops.phase` /
  cloud-itonami-isic-5210's `terminal.phase` shape."
  )

(def all-phases
  "All valid phases in the refrigerated-warehousing storage-lot
  lifecycle."
  [:inbound :storage :outbound :dispatched :archived])

(def phase-sequence
  "Ordered phases representing normal lot progression."
  [:inbound :storage :outbound :dispatched :archived])

(defn valid-phase?
  [phase]
  (contains? (set all-phases) phase))

(defn- index-of
  "Portable (Clojure/ClojureScript) index lookup -- `.indexOf` is a
  JVM-only `java.util.List` method that ClojureScript's PersistentVector
  does not implement, so it is avoided here even though `phase-sequence`
  is a plain vector. Returns -1 when `x` is not found."
  [coll x]
  (or (first (keep-indexed (fn [i v] (when (= v x) i)) coll)) -1))

(defn can-transition?
  "Check if a transition from one phase to another is valid
  (must be forward-only in the sequence, no backtracking). Always
  returns a boolean (never nil), including when either phase is
  invalid."
  [from-phase to-phase]
  (boolean
   (and (valid-phase? from-phase) (valid-phase? to-phase)
        (let [from-idx (index-of phase-sequence from-phase)
              to-idx (index-of phase-sequence to-phase)]
          (and (>= from-idx 0) (>= to-idx 0) (< from-idx to-idx))))))
