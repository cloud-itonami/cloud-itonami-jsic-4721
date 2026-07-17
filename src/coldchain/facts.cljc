(ns coldchain.facts
  "Reference facts for refrigerated warehousing (JSIC 4721, 冷蔵倉庫業):
  commodity storage-temperature classes this actor's Governor uses to
  independently verify a stored lot's cold-chain discipline. Pure
  lookup data, no I/O -- same shape/discipline as
  cloud-itonami-isic-1075's `mealops.facts`.

  Deliberately narrow -- this is a `:maturity :blueprint` actor (see
  blueprint.edn), matched in scope to cloud-itonami-isic-1075, not the
  full HACCP-style depth of that sibling's own facts registry."
  )

(def commodity-classes
  "Refrigerated-warehousing storage classes and their safe
  storage-temperature windows (日本冷蔵倉庫協会 / Japan Association of
  Refrigerated Warehouses reference bands: F4 (-20C以下, deep-frozen)
  down through C3 (10C~2C, chilled)). `max-power-outage-minutes` is
  this actor's own reference for how long a reefer/compressor outage
  can run before the commodity class's safety margin is considered
  exhausted."
  {:coldchain/f4-deep-frozen
   {:id :coldchain/f4-deep-frozen
    :name "F4級 (-20℃以下、深冷凍)"
    :storage-temp-min-c -30.0
    :storage-temp-max-c -20.0
    :max-power-outage-minutes 120}

   :coldchain/f3-frozen
   {:id :coldchain/f3-frozen
    :name "F3級 (-20℃~-10℃、冷凍)"
    :storage-temp-min-c -20.0
    :storage-temp-max-c -10.0
    :max-power-outage-minutes 90}

   :coldchain/c3-chilled
   {:id :coldchain/c3-chilled
    :name "C3級 (10℃~2℃、チルド)"
    :storage-temp-min-c 2.0
    :storage-temp-max-c 10.0
    :max-power-outage-minutes 60}})

(defn commodity-class-by-id [id]
  (get commodity-classes id))

(def jurisdictions
  "Warehousing-business-license jurisdictions this actor recognizes."
  {:jp/mlit
   {:id :jp/mlit
    :name "日本 (倉庫業法 - 国土交通省; JSIC 4721 冷蔵倉庫業)"
    :required-evidence [:warehouse-business-license :cold-storage-facility-inspection]}})

(defn jurisdiction-by-id [id]
  (get jurisdictions id))

;; ─────────────── Cross-Actor Handoff (isic-1075 -> jsic-4721) ───────────────
;;
;; An inbound (or outbound) lot proposal MAY carry a `:handoff` record --
;; the same wire shape documented in cloud-itonami-isic-1075's
;; `mealops.facts` and superproject ADR-2607177600. This actor validates
;; its own half of that contract independently (no shared code, no
;; shared store): does the handoff's declared cold-chain-temp-min-c/
;; max-c window even OVERLAP the commodity class this lot has been
;; assigned to?
;;
;;   {:handoff/id "..."
;;    :handoff/source-actor "cloud-itonami-isic-1075"
;;    :handoff/batch-id "..."
;;    :handoff/product-type-id :meal/cook-chill-poultry
;;    :handoff/cold-chain-temp-min-c 0.0
;;    :handoff/cold-chain-temp-max-c 3.0
;;    :handoff/quantity-kg 120.5
;;    :handoff/dispatched-at-iso "..."
;;    :handoff/unspsc-code "50192701"    ; OPTIONAL, pass-through only -- see
;;    :handoff/gtin "0211075000011"}     ; superproject UNSPSC/GTIN-linkage
;;                                       ; ADR, mirroring mealops.facts's own
;;                                       ; addendum. This actor's `governor`
;;                                       ; does not read either field (no
;;                                       ; existing predicate does); they
;;                                       ; ride along on the record for
;;                                       ; downstream traceability, the same
;;                                       ; asymmetric-optional, no-new-hard-
;;                                       ; check discipline this actor's own
;;                                       ; grid-outage reference fields
;;                                       ; (below) already establish.

(defn handoff-compatible-with-commodity-class?
  "Positive-sense convenience predicate: does the declared handoff's
  cold-chain-temp-min-c/max-c window OVERLAP `commodity`'s own
  storage-temp-min-c/max-c band at all? Detects a temperature-tier
  mismatch between what a handed-off batch requires and the storage
  class it has been assigned to -- e.g. a 0C-3C chilled product
  assigned to an F4 deep-frozen (-30C to -20C) bin, where the two
  bands don't overlap at all.

  Deliberately OVERLAP rather than a strict subset check in either
  direction: a commodity class describes a whole STORAGE ROOM's
  operating band, not one specific product's declared safety margin
  (that subset check already lives on the isic-1075 side, comparing a
  handoff's window against a PRODUCT TYPE's own proven-safe range --
  see `mealops.facts/handoff-window-within-product-safety-margin?`).
  Requiring the room's whole band to be a subset of the product's
  narrower window (or vice versa) would reject nearly every real
  assignment; the physically meaningful question on THIS side of the
  handoff is simply whether the assigned room can ever actually be at
  a temperature the handoff requires."
  [handoff-min-c handoff-max-c commodity]
  (boolean
   (and (some? commodity)
        (some? handoff-min-c)
        (some? handoff-max-c)
        (<= handoff-min-c handoff-max-c)
        (<= handoff-min-c (:storage-temp-max-c commodity))
        (<= (:storage-temp-min-c commodity) handoff-max-c))))

;; ───────────── Cross-Actor Grid-Outage Reference (isic-3510 -> jsic-4721) ─────────────
;;
;; An inbound (or outbound) lot proposal MAY ALSO carry three flat
;; top-level reference fields (a DIFFERENT wire shape than the nested
;; `:handoff` map above -- see `coldchain.governor`'s own docstring
;; for why): a copy of an upstream grid-transmission-operator actor's
;; own committed outage-event record (e.g. cloud-itonami-isic-3510's
;; `grid.facts`'s own "Cross-Actor Grid-Outage Reference" section --
;; superproject ADR-2608510000 documents the full shared shape both
;; sides independently validate, no shared code, no shared store):
;;
;;   :grid-outage/source-actor "cloud-itonami-isic-3510"
;;   :grid-outage/event-id "outage-1"            ; = the source record's own :grid-outage/id
;;   :grid-outage/duration-minutes 75            ; = the source record's own :grid-outage/duration-minutes
;;
;; This actor cross-checks these three fields against its OWN
;; self-reported `:lot/power-outage-minutes` via
;; `coldchain.registry/grid-outage-duration-mismatch?` -- see
;; `coldchain.governor/grid-outage-duration-mismatch-escalations`.
;; Entirely optional on both sides (a proposal missing any of the
;; three, or missing `:lot/power-outage-minutes`, is never escalated
;; on this basis) and, unlike the handoff-compatibility check above,
;; a mismatch is a SOFT escalation, never a hard hold.
