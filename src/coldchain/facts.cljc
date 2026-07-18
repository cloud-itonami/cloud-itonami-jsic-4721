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

;; ────── Outbound Handoff Issuance (jsic-4721 -> isic-5610/isic-4711/isic-4719) ──────
;;
;; The identical `:handoff` wire shape above is reused, unmodified, in the
;; OTHER direction: `:log-outbound-shipment` proposals MAY carry a `:handoff`
;; record THIS actor itself issues to a downstream food-service/retail actor
;; (superproject ADR-2800000500) -- cloud-itonami-isic-5610 (community
;; food-service operations, ~KFC-like), cloud-itonami-isic-4711 (community
;; retail operations, ~Aeon-like supermarket), cloud-itonami-isic-4719
;; (other non-specialized-store retail, ~Kura-Sushi-like). This is the
;; direct-downstream fan-out side of the 2026-07 Nichirei case study (root
;; cause #2: ~4-day outbound stoppage cascading to 15+ downstream client
;; organizations) -- isic-1075 hands work IN to this warehouse, this
;; warehouse hands work OUT to these three.
;;
;; No new field, no new shape -- only `:handoff/source-actor` differs:
;;
;;   {:handoff/id "..."
;;    :handoff/source-actor "cloud-itonami-jsic-4721"   ; <- THIS actor, not isic-1075
;;    :handoff/batch-id "..."                            ; this actor's own storage-lot/batch id
;;    :handoff/product-type-id :coldchain/c3-chilled     ; this actor's own commodity-class id
;;    :handoff/cold-chain-temp-min-c 2.0
;;    :handoff/cold-chain-temp-max-c 10.0
;;    :handoff/quantity-kg 120.5
;;    :handoff/dispatched-at-iso "..."
;;    :handoff/unspsc-code "50192701"    ; OPTIONAL, pass-through only, same as inbound
;;    :handoff/gtin "0211075000011"}     ; OPTIONAL, pass-through only, same as inbound
;;
;; `lot-physical-violations` (below, via `coldchain.governor`) validates a
;; self-issued outbound handoff with the EXACT SAME predicate
;; (`handoff-compatible-with-commodity-class?`) it already uses for a
;; received inbound one -- the check does not read `:handoff/source-actor`,
;; so no new predicate is needed here. `coldchain.governor/outbound-
;; concentration-violations` additionally reuses the capacity-concentration-
;; limit kernel (unchanged, no new kernel code) against this same record's
;; `:handoff/quantity-kg` -- see that function's own docstring and
;; superproject ADR-2800000500 for the downstream-client-concentration
;; rationale. Each of isic-5610/isic-4711/isic-4719 independently validates
;; ITS OWN half of this contract against ITS OWN storage-temperature
;; requirements (no shared code, no shared store, same asymmetric-optional
;; design as every cross-actor reference in this file).

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

;; ───────────── Equipment-Asset Linkage (isic-2813 -> jsic-4721) ─────────────
;;
;; This actor's own `:register-equipment-asset` proposals register WHICH
;; manufactured unit (e.g. a cloud-itonami-isic-2813 industrial-
;; refrigeration-compressor) this warehouse actually operates -- the
;; superproject `:equipment-asset` shared shape (equipment-asset-linkage
;; ADR, no shared code, no shared store, same asymmetric-optional
;; design as the two cross-actor references above):
;;
;;   {:equipment-asset/id "..."                                        ; correlation id
;;    :equipment-asset/unit-type-id :unit/industrial-refrigeration-compressor  ; == isic-2813's pressureequip.facts/unit-types key
;;    :equipment-asset/source-actor "cloud-itonami-isic-2813"
;;    :equipment-asset/dispatch-ref "JPN-PEQ-000000"                    ; == isic-2813's :actuation/dispatch-unit dispatch/batch id
;;    :equipment-asset/installed-at-iso "..."}
;;
;; An inbound/outbound lot proposal MAY ALSO carry an optional
;; `:maintenance-notice` reference -- a DIFFERENT wire shape (a nested
;; map, like `:handoff`) populated from a downstream isic-2813
;; `:issue-maintenance-notice` event:
;;
;;   {:maintenance-notice/source-actor "cloud-itonami-isic-2813"
;;    :maintenance-notice/dispatch-ref "JPN-PEQ-000000"
;;    :maintenance-notice/equipment-asset-id "..."}                     ; == an :equipment-asset/id THIS actor may have registered
;;
;; This actor cross-checks the maintenance-notice's
;; `:maintenance-notice/equipment-asset-id` against the equipment
;; assets it has ALREADY registered (`equipment-asset-maintenance-
;; notice-for-registered-asset?` below, wired into
;; `coldchain.governor/equipment-asset-maintenance-notice-escalations`)
;; -- when it matches, that is useful information worth a human
;; glance ("the equipment that just received a maintenance notice is
;; actually equipment we operate"), a SOFT escalation, never a hard
;; hold, same reasoning as the grid-outage cross-check above.

;; ───────── Equipment Power-Requirement Reference (for :reconcile-power-metering) ─────────
;;
;; This actor's OWN small, LOCAL reference for how many kW of continuous
;; power draw a registered `:equipment-asset/unit-type-id` requires --
;; deliberately NOT a shared-library import from cloud-itonami-isic-2813's
;; own `pressureequip.facts/unit-types` catalog (this repo has zero
;; dependency on any other cloud-itonami repo, ADR-2607011000/
;; ADR-2607176501; see `coldchain.governor` ns docstring's own "Design
;; boundary" section) -- the SAME no-shared-code, only-a-shared-id-
;; convention discipline every cross-actor reference in this file uses.
;; Honest-coverage discipline (same as `commodity-classes`/
;; `jurisdictions` above): an `:equipment-asset/unit-type-id` absent
;; from this catalog contributes 0.0 kW to `coldchain.kernels.power-
;; metering-verdict/expected-consumption-kwh`, it is never fabricated.
;; The one seeded entry cites the REAL figure from cloud-itonami-
;; isic-2813's own `pressureequip.facts/unit-types` catalog at the time
;; this was written (90.0 kW for `:unit/industrial-refrigeration-
;; compressor`, the same unit-type-id this repo's own equipment-asset
;; test fixtures already reference) -- extending coverage is additive:
;; add one entry, cite the real isic-2813 figure, never invent a number
;; to make coverage look complete.

(def unit-type-power-kw
  "unit-type-id -> {:power-requirement-kw <double>}."
  {:unit/industrial-refrigeration-compressor {:power-requirement-kw 90.0}})

(defn unit-type-power-kw-of
  "The rated continuous power draw (kW) for `unit-type-id`, or nil if
  this actor's own local reference has no entry for it (never
  fabricated -- see this section's own comment above)."
  [unit-type-id]
  (:power-requirement-kw (get unit-type-power-kw unit-type-id)))

(defn equipment-asset-maintenance-notice-for-registered-asset?
  "Positive-sense convenience predicate: does `maintenance-notice`'s
  `:maintenance-notice/equipment-asset-id` name an equipment asset
  THIS actor has already registered (any of `registered-ids`)? A
  `nil`/non-map `maintenance-notice`, or one with no
  `:maintenance-notice/equipment-asset-id`, is never a match (this
  cross-check is entirely optional, same discipline as
  `handoff-compatible-with-commodity-class?`)."
  [maintenance-notice registered-ids]
  (boolean
   (and (map? maintenance-notice)
        (some? (:maintenance-notice/equipment-asset-id maintenance-notice))
        (contains? (set registered-ids) (:maintenance-notice/equipment-asset-id maintenance-notice)))))
