(ns coldchain.governor-test
  "Regression coverage for coldchain.governor's own invariants.

  NOTE on scope (see coldchain.governor's docstring): this actor is
  standalone / zero-dependency on cloud-itonami core (ADR-2607176501
  precedent). cloud-itonami's SecurityIncidentGovernor
  bcp-precondition path for a JSIC 4721 tenant (`physical-ops-jsics`)
  is tested in the cloud-itonami repo itself
  (`cloud-itonami.security-governor-test/tenant-onboard-for-a-
  physical-ops-jsic-requires-a-manual-fallback-ref`), not here -- this
  repo has no code path that calls cloud-itonami at all."
  (:require [clojure.test :refer [deftest is testing]]
            [coldchain.governor :as governor]))

;; ── closed allowlist ──

(deftest kind-not-allowed-is-a-hard-hold
  (testing "a proposal kind outside the closed allowlist is held unconditionally"
    (let [r (governor/check {:kind :control-reefer-compressor})]
      (is (= :hold (:status r)))
      (is (some #(re-find #"allowlist" %) (:reasons r)))))
  (testing "every kind in allowed-kinds is accepted by the allowlist gate itself"
    (doseq [k governor/allowed-kinds]
      (let [r (governor/check {:kind k})]
        ;; :tenant/onboard etc. may still hold for OTHER reasons (e.g.
        ;; capacity math), but never for reason-kind-not-allowed.
        (is (not (some #(= % governor/reason-kind-not-allowed) (:reasons r))))))))

;; ── capacity-concentration-limit ──

(deftest capacity-allocate-below-limit-passes
  (is (= :pass (:status (governor/check {:kind :capacity/allocate
                                         :capacity/allocated-units 100
                                         :capacity/total-units 1000})))))

(deftest capacity-allocate-above-limit-holds
  (let [r (governor/check {:kind :capacity/allocate
                           :capacity/allocated-units 300
                           :capacity/total-units 1000})]
    (is (= :hold (:status r)))
    (is (some #(re-find #"concentration" %) (:reasons r)))))

(deftest capacity-allocate-exactly-at-limit-passes
  (testing "boundary is inclusive -- exactly 25% never holds"
    (is (= :pass (:status (governor/check {:kind :capacity/allocate
                                           :capacity/allocated-units 250
                                           :capacity/total-units 1000}))))))

(deftest capacity-allocate-missing-fields-does-not-crash-or-hold
  (testing "a proposal with no capacity fields is not held on the concentration basis (nil ratio never exceeds)"
    (is (= :pass (:status (governor/check {:kind :capacity/allocate}))))))

;; ── lot physical-discipline checks (facts + registry wiring) ──

(deftest outbound-shipment-storage-temp-out-of-range-holds
  (let [r (governor/check {:kind :log-outbound-shipment
                           :lot/commodity-class :coldchain/c3-chilled
                           :lot/storage-temp-c 12.0})]
    (is (= :hold (:status r)))
    (is (some #(re-find #"temperature" %) (:reasons r)))))

(deftest outbound-shipment-storage-temp-in-range-passes
  (is (= :pass (:status (governor/check {:kind :log-outbound-shipment
                                         :lot/commodity-class :coldchain/c3-chilled
                                         :lot/storage-temp-c 3.0})))))

(deftest inbound-shipment-power-outage-exceeds-max-holds
  (let [r (governor/check {:kind :log-inbound-shipment
                           :lot/commodity-class :coldchain/f4-deep-frozen
                           :lot/power-outage-minutes 200})]
    (is (= :hold (:status r)))
    (is (some #(re-find #"outage" %) (:reasons r)))))

(deftest inbound-shipment-power-outage-within-max-passes
  (is (= :pass (:status (governor/check {:kind :log-inbound-shipment
                                         :lot/commodity-class :coldchain/f4-deep-frozen
                                         :lot/power-outage-minutes 60})))))

(deftest lot-proposal-without-physical-fields-passes
  (testing "the physical-discipline checks are optional -- absent fields never hold"
    (is (= :pass (:status (governor/check {:kind :log-inbound-shipment}))))))

;; ── cross-actor handoff (isic-1075 -> jsic-4721) ──

(def ^:private chilled-poultry-handoff
  "A handoff record matching cloud-itonami-isic-1075's :meal/cook-chill-
  poultry cold-storage safety margin (0.0C-3.0C)."
  {:handoff/id "h-1"
   :handoff/source-actor "cloud-itonami-isic-1075"
   :handoff/batch-id "batch-001"
   :handoff/product-type-id :meal/cook-chill-poultry
   :handoff/cold-chain-temp-min-c 0.0
   :handoff/cold-chain-temp-max-c 3.0
   :handoff/quantity-kg 120.5
   :handoff/dispatched-at-iso "2026-07-17T00:00:00Z"})

(deftest inbound-shipment-handoff-incompatible-with-commodity-class-holds
  (testing "a chilled handoff assigned to an f4-deep-frozen lot holds (temperature-tier mismatch)"
    (let [r (governor/check {:kind :log-inbound-shipment
                             :lot/commodity-class :coldchain/f4-deep-frozen
                             :handoff chilled-poultry-handoff})]
      (is (= :hold (:status r)))
      (is (some #(re-find #"handoff" %) (:reasons r))))))

(deftest inbound-shipment-handoff-compatible-with-commodity-class-passes
  (testing "a chilled handoff assigned to a c3-chilled lot passes"
    (is (= :pass (:status (governor/check {:kind :log-inbound-shipment
                                           :lot/commodity-class :coldchain/c3-chilled
                                           :handoff chilled-poultry-handoff}))))))

(deftest outbound-shipment-handoff-incompatible-with-commodity-class-holds
  (testing "the same handoff-compatibility check also applies to outbound shipments"
    (let [r (governor/check {:kind :log-outbound-shipment
                             :lot/commodity-class :coldchain/f4-deep-frozen
                             :handoff chilled-poultry-handoff})]
      (is (= :hold (:status r)))
      (is (some #(re-find #"handoff" %) (:reasons r))))))

(deftest lot-proposal-without-handoff-passes
  (testing "a proposal without a :handoff record is not held on this basis (backward compatible)"
    (is (= :pass (:status (governor/check {:kind :log-inbound-shipment
                                           :lot/commodity-class :coldchain/c3-chilled
                                           :lot/storage-temp-c 3.0}))))))

(deftest handoff-with-optional-unspsc-gtin-pass-through-fields-passes
  (testing "a handoff carrying the optional :handoff/unspsc-code and :handoff/gtin pass-through fields still passes when otherwise compatible -- neither field is validated"
    (let [handoff (assoc chilled-poultry-handoff
                          :handoff/unspsc-code "50192701"
                          :handoff/gtin "0211075000011")]
      (is (= :pass (:status (governor/check {:kind :log-inbound-shipment
                                             :lot/commodity-class :coldchain/c3-chilled
                                             :handoff handoff})))))))

;; ── inbound quantity-kg wired into capacity-concentration-limit ──

(deftest inbound-shipment-quantity-kg-exceeds-concentration-limit-holds
  (testing "a received quantity-kg above 25% of total warehouse capacity holds"
    (let [r (governor/check {:kind :log-inbound-shipment
                             :lot/quantity-kg 300
                             :capacity/total-units 1000})]
      (is (= :hold (:status r)))
      (is (some #(re-find #"concentration" %) (:reasons r))))))

(deftest inbound-shipment-quantity-kg-within-concentration-limit-passes
  (is (= :pass (:status (governor/check {:kind :log-inbound-shipment
                                         :lot/quantity-kg 100
                                         :capacity/total-units 1000})))))

(deftest inbound-shipment-quantity-kg-missing-total-units-does-not-hold
  (testing "quantity-kg alone (no :capacity/total-units) is not held on this basis"
    (is (= :pass (:status (governor/check {:kind :log-inbound-shipment
                                           :lot/quantity-kg 900}))))))

(deftest outbound-shipment-quantity-kg-is-not-checked-against-concentration-limit
  (testing "the inbound-only concentration wiring does not apply to outbound shipments"
    (is (= :pass (:status (governor/check {:kind :log-outbound-shipment
                                           :lot/quantity-kg 900
                                           :capacity/total-units 1000}))))))

;; ── outbound-destination concentration limit (downstream client fan-out) ──
;;
;; Mirror-image of the inbound wiring above, superproject ADR-2800000500:
;; reuses the SAME kernel, but reads the cross-actor :handoff/quantity-kg
;; payload (not the pre-existing top-level :lot/quantity-kg the test just
;; above this section confirms remains unchecked).

(def ^:private downstream-outbound-handoff
  "A handoff record THIS actor issues to a downstream retail/food-service
  actor (e.g. cloud-itonami-isic-4711), :handoff/source-actor is jsic-4721
  itself -- mirrors chilled-poultry-handoff's shape but the other
  direction."
  {:handoff/id "h-out-1"
   :handoff/source-actor "cloud-itonami-jsic-4721"
   :handoff/batch-id "lot-001"
   :handoff/product-type-id :coldchain/c3-chilled
   :handoff/cold-chain-temp-min-c 2.0
   :handoff/cold-chain-temp-max-c 10.0
   :handoff/quantity-kg 300
   :handoff/dispatched-at-iso "2026-07-18T00:00:00Z"})

(deftest outbound-shipment-handoff-quantity-exceeds-concentration-limit-holds
  (testing "a single outbound handoff whose quantity-kg alone exceeds 25% of total capacity holds"
    (let [r (governor/check {:kind :log-outbound-shipment
                             :capacity/total-units 1000
                             :handoff downstream-outbound-handoff})]
      (is (= :hold (:status r)))
      (is (some #(re-find #"concentration" %) (:reasons r))))))

(deftest outbound-shipment-handoff-quantity-within-concentration-limit-passes
  (is (= :pass (:status (governor/check {:kind :log-outbound-shipment
                                         :capacity/total-units 1000
                                         :handoff (assoc downstream-outbound-handoff
                                                          :handoff/quantity-kg 100)})))))

(deftest outbound-shipment-handoff-missing-total-units-does-not-hold-on-concentration-basis
  (testing "a :handoff carrying :handoff/quantity-kg with no :capacity/total-units is not held on this basis"
    (is (= :pass (:status (governor/check {:kind :log-outbound-shipment
                                           :handoff downstream-outbound-handoff}))))))

(deftest outbound-shipment-without-handoff-is-not-held-on-outbound-concentration-basis
  (testing "no :handoff at all -- unaffected, same as before this wiring existed"
    (is (= :pass (:status (governor/check {:kind :log-outbound-shipment
                                           :capacity/total-units 1000}))))))

(deftest outbound-shipment-handoff-issued-by-jsic-4721-itself-still-checked-for-cold-chain-compatibility
  (testing "the pre-existing temperature-tier-overlap check applies identically whether jsic-4721 receives or issues the handoff"
    (let [r (governor/check {:kind :log-outbound-shipment
                             :lot/commodity-class :coldchain/f4-deep-frozen
                             :handoff downstream-outbound-handoff})]
      (is (= :hold (:status r)))
      (is (some #(re-find #"handoff" %) (:reasons r))))))

;; ── cross-actor grid-outage reference (isic-3510 -> jsic-4721) ──
;;
;; SOFT escalation, not a hard hold -- mirrors cloud-itonami-isic-1075's
;; :supplier-not-verified soft-escalation precedent. See
;; coldchain.governor/grid-outage-duration-mismatch-escalations.

(deftest inbound-shipment-grid-outage-duration-mismatch-escalates-not-holds
  (testing "a self-report far outside the grid-operator record's duration escalates, does not hold"
    (let [r (governor/check {:kind :log-inbound-shipment
                             :lot/commodity-class :coldchain/f4-deep-frozen
                             :lot/power-outage-minutes 60
                             :grid-outage/source-actor "cloud-itonami-isic-3510"
                             :grid-outage/event-id "outage-1"
                             :grid-outage/duration-minutes 200})]
      (is (= :escalate (:status r)))
      (is (some #(re-find #"grid-outage" %) (:escalations r)))
      (is (not= :hold (:status r))))))

(deftest outbound-shipment-grid-outage-duration-mismatch-also-escalates
  (testing "the same cross-check also applies to outbound shipments"
    (let [r (governor/check {:kind :log-outbound-shipment
                             :lot/power-outage-minutes 10
                             :grid-outage/source-actor "cloud-itonami-isic-3510"
                             :grid-outage/event-id "outage-2"
                             :grid-outage/duration-minutes 60})]
      (is (= :escalate (:status r)))
      (is (seq (:escalations r))))))

(deftest inbound-shipment-grid-outage-duration-within-tolerance-passes
  (testing "a self-report within tolerance of the grid-operator record passes cleanly"
    (is (= :pass (:status (governor/check {:kind :log-inbound-shipment
                                           :lot/power-outage-minutes 65
                                           :grid-outage/source-actor "cloud-itonami-isic-3510"
                                           :grid-outage/event-id "outage-1"
                                           :grid-outage/duration-minutes 60}))))))

(deftest lot-proposal-without-grid-outage-fields-passes
  (testing "the grid-outage cross-check is optional -- absent reference fields never escalate (backward compatible, isic-3510 not required)"
    (is (= :pass (:status (governor/check {:kind :log-inbound-shipment
                                           :lot/commodity-class :coldchain/f4-deep-frozen
                                           :lot/power-outage-minutes 60}))))
    (testing "partial reference fields (missing :grid-outage/event-id) also never escalate"
      (is (= :pass (:status (governor/check {:kind :log-inbound-shipment
                                             :lot/power-outage-minutes 200
                                             :grid-outage/source-actor "cloud-itonami-isic-3510"
                                             :grid-outage/duration-minutes 60})))))))

(deftest grid-outage-duration-mismatch-never-overrides-a-hard-hold
  (testing "a proposal that ALSO hard-holds on its own physical checks stays :hold, not :escalate"
    (let [r (governor/check {:kind :log-inbound-shipment
                             :lot/commodity-class :coldchain/c3-chilled
                             :lot/storage-temp-c 30.0 ;; out of range -> hard hold
                             :lot/power-outage-minutes 200
                             :grid-outage/source-actor "cloud-itonami-isic-3510"
                             :grid-outage/event-id "outage-1"
                             :grid-outage/duration-minutes 60})]
      (is (= :hold (:status r))))))

;; ── equipment-asset linkage (isic-2813 -> jsic-4721) ──

(def ^:private valid-equipment-asset
  {:kind :register-equipment-asset
   :equipment-asset/id "ea-1"
   :equipment-asset/unit-type-id :unit/industrial-refrigeration-compressor
   :equipment-asset/source-actor "cloud-itonami-isic-2813"
   :equipment-asset/dispatch-ref "JPN-PEQ-000000"
   :equipment-asset/installed-at-iso "2026-07-18T00:00:00Z"})

(deftest register-equipment-asset-with-all-required-fields-passes
  (is (= :pass (:status (governor/check valid-equipment-asset)))))

(deftest register-equipment-asset-missing-fields-holds
  (testing "missing :equipment-asset/dispatch-ref holds"
    (let [r (governor/check (dissoc valid-equipment-asset :equipment-asset/dispatch-ref))]
      (is (= :hold (:status r)))
      (is (some #(re-find #"missing" %) (:reasons r)))))
  (testing "missing :equipment-asset/id holds"
    (let [r (governor/check (dissoc valid-equipment-asset :equipment-asset/id))]
      (is (= :hold (:status r)))))
  (testing "missing :equipment-asset/unit-type-id holds"
    (let [r (governor/check (dissoc valid-equipment-asset :equipment-asset/unit-type-id))]
      (is (= :hold (:status r)))))
  (testing "missing :equipment-asset/source-actor holds"
    (let [r (governor/check (dissoc valid-equipment-asset :equipment-asset/source-actor))]
      (is (= :hold (:status r))))))

(deftest register-equipment-asset-duplicate-id-holds
  (testing "an :equipment-asset/id already present in the registry context's :registered-ids holds"
    (let [r (governor/check {:equipment-asset/registered-ids #{"ea-1"}} valid-equipment-asset)]
      (is (= :hold (:status r)))
      (is (some #(re-find #"already" %) (:reasons r))))))

(deftest register-equipment-asset-different-id-does-not-hold-on-duplicate-basis
  (testing "a NEW :equipment-asset/id, even with a non-empty :registered-ids context, never holds on the duplicate basis"
    (let [r (governor/check {:equipment-asset/registered-ids #{"ea-9"}} valid-equipment-asset)]
      (is (not (some #(re-find #"already" %) (:reasons r)))))))

(deftest register-equipment-asset-with-no-registry-context-passes
  (testing "omitting the registry context entirely (default {}) never holds on the duplicate basis"
    (is (= :pass (:status (governor/check valid-equipment-asset))))))

;; ── maintenance-notice cross-check (isic-2813 -> jsic-4721, soft escalation) ──

(deftest maintenance-notice-referencing-registered-asset-escalates
  (testing "a :log-inbound-shipment carrying a :maintenance-notice for an already-registered asset escalates"
    (let [r (governor/check {:equipment-asset/registered-ids #{"ea-1"}}
                            {:kind :log-inbound-shipment
                             :lot/commodity-class :coldchain/c3-chilled
                             :lot/storage-temp-c 3.0
                             :maintenance-notice {:maintenance-notice/source-actor "cloud-itonami-isic-2813"
                                                   :maintenance-notice/dispatch-ref "JPN-PEQ-000000"
                                                   :maintenance-notice/equipment-asset-id "ea-1"}})]
      (is (= :escalate (:status r)))
      (is (some #(re-find #"maintenance-notice" %) (:escalations r))))))

(deftest maintenance-notice-referencing-unregistered-asset-does-not-escalate
  (testing "a :maintenance-notice referencing an asset NOT (yet) registered here does not escalate on this basis"
    (let [r (governor/check {:equipment-asset/registered-ids #{"ea-9"}}
                            {:kind :log-outbound-shipment
                             :maintenance-notice {:maintenance-notice/equipment-asset-id "ea-1"}})]
      (is (not (some #(re-find #"maintenance-notice" %) (:escalations r)))))))

(deftest lot-proposal-without-maintenance-notice-field-passes
  (testing "a shipment-logging proposal without :maintenance-notice is unaffected (backward compatible)"
    (is (= :pass (:status (governor/check {:equipment-asset/registered-ids #{"ea-1"}}
                                          {:kind :log-inbound-shipment
                                           :lot/commodity-class :coldchain/c3-chilled
                                           :lot/storage-temp-c 3.0}))))))

(deftest maintenance-notice-escalation-does-not-apply-outside-shipment-logging-kinds
  (testing "the maintenance-notice cross-check is only wired into :log-inbound-shipment/:log-outbound-shipment, not e.g. :capacity/allocate"
    (is (= :pass (:status (governor/check {:equipment-asset/registered-ids #{"ea-1"}}
                                          {:kind :capacity/allocate
                                           :capacity/allocated-units 100
                                           :capacity/total-units 1000
                                           :maintenance-notice {:maintenance-notice/equipment-asset-id "ea-1"}}))))))

;; ── power-metering reconciliation (isic-3510 -> jsic-4721) ──
;;
;; SOFT escalation, not a hard hold -- mirrors the grid-outage
;; duration-mismatch cross-check's own posture above, but for
;; STEADY-STATE consumption reconciliation. See
;; coldchain.governor/power-metering-deviation-escalations.

(def ^:private one-clean-compressor
  "One registered compressor (90.0kW rated, see coldchain.facts/
  unit-type-power-kw), installed well before any test period so it
  is presumed running for the FULL period in every test below."
  [{:equipment-asset/id "ea-1"
    :equipment-asset/unit-type-id :unit/industrial-refrigeration-compressor
    :equipment-asset/source-actor "cloud-itonami-isic-2813"
    :equipment-asset/dispatch-ref "JPN-PEQ-000000"
    :equipment-asset/installed-at-iso "2026-01-01T00:00:00Z"}])

(deftest power-metering-within-threshold-passes-cleanly
  (testing "expected = 90.0kW x 168h = 15120kWh; a self-report within +/-20% passes"
    (is (= :pass (:status (governor/check {:kind :reconcile-power-metering
                                           :power-metering/id "feeder-1-MTR-2026-07-01T00:00:00Z"
                                           :power-metering/feeder-ref "feeder-1"
                                           :power-metering/client-actor "cloud-itonami-jsic-4721"
                                           :power-metering/period-start-iso "2026-07-01T00:00:00Z"
                                           :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                                           :power-metering/consumed-kwh 16000.0
                                           :equipment-assets one-clean-compressor}))))))

(deftest power-metering-deviation-beyond-threshold-escalates-not-holds
  (testing "a self-reported consumption far above the expected 15120kWh escalates, does not hold"
    (let [r (governor/check {:kind :reconcile-power-metering
                             :power-metering/id "feeder-1-MTR-2026-07-01T00:00:00Z"
                             :power-metering/feeder-ref "feeder-1"
                             :power-metering/client-actor "cloud-itonami-jsic-4721"
                             :power-metering/period-start-iso "2026-07-01T00:00:00Z"
                             :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                             :power-metering/consumed-kwh 30000.0
                             :equipment-assets one-clean-compressor})]
      (is (= :escalate (:status r)))
      (is (some #(re-find #"power-metering" %) (:escalations r)))
      (is (not= :hold (:status r))))))

(deftest power-metering-deviation-below-threshold-also-escalates
  (testing "symmetric -- a self-report far BELOW the expectation also escalates"
    (let [r (governor/check {:kind :reconcile-power-metering
                             :power-metering/period-start-iso "2026-07-01T00:00:00Z"
                             :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                             :power-metering/consumed-kwh 100.0
                             :equipment-assets one-clean-compressor})]
      (is (= :escalate (:status r)))
      (is (seq (:escalations r))))))

(deftest power-metering-missing-equipment-assets-never-escalates-on-this-basis
  (testing "an empty/absent :equipment-assets list is not enough information to compute an expectation -- never escalates (asymmetric-optional)"
    (is (= :pass (:status (governor/check {:kind :reconcile-power-metering
                                           :power-metering/period-start-iso "2026-07-01T00:00:00Z"
                                           :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                                           :power-metering/consumed-kwh 30000.0}))))
    (is (= :pass (:status (governor/check {:kind :reconcile-power-metering
                                           :power-metering/period-start-iso "2026-07-01T00:00:00Z"
                                           :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                                           :power-metering/consumed-kwh 30000.0
                                           :equipment-assets []}))))))

(deftest power-metering-missing-consumed-kwh-never-escalates-on-this-basis
  (testing "no self-reported :consumed-kwh at all -- never escalates (backward compatible)"
    (is (= :pass (:status (governor/check {:kind :reconcile-power-metering
                                           :power-metering/period-start-iso "2026-07-01T00:00:00Z"
                                           :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                                           :equipment-assets one-clean-compressor}))))))

(deftest power-metering-unrecognized-unit-type-contributes-zero-expected-and-never-escalates
  (testing "an equipment-asset whose unit-type-id this actor has no local power-kw reference for contributes 0.0 expected kWh -- expected becomes 0, deviation-ratio is nil (can't divide by zero-or-negative expected), so this never escalates on the power-metering basis, it does not fabricate a figure"
    (is (= :pass (:status (governor/check {:kind :reconcile-power-metering
                                           :power-metering/period-start-iso "2026-07-01T00:00:00Z"
                                           :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                                           :power-metering/consumed-kwh 30000.0
                                           :equipment-assets [{:equipment-asset/id "ea-9"
                                                               :equipment-asset/unit-type-id :unit/unknown-widget
                                                               :equipment-asset/installed-at-iso "2026-01-01T00:00:00Z"}]}))))))

(deftest power-metering-deviation-never-overrides-a-hard-hold
  (testing ":reconcile-power-metering itself has no hard check, but an out-of-allowlist kind still hard-holds regardless"
    (is (= :hold (:status (governor/check {:kind :control-reefer-compressor
                                           :power-metering/period-start-iso "2026-07-01T00:00:00Z"
                                           :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                                           :power-metering/consumed-kwh 30000.0
                                           :equipment-assets one-clean-compressor})))))
  (testing "a genuinely allowlisted, clean :reconcile-power-metering proposal escalates rather than holds"
    (is (= :escalate (:status (governor/check {:kind :reconcile-power-metering
                                               :power-metering/period-start-iso "2026-07-01T00:00:00Z"
                                               :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                                               :power-metering/consumed-kwh 30000.0
                                               :equipment-assets one-clean-compressor}))))))

;; ── censor ──

(deftest censor-buckets-every-proposal-exactly-once
  (let [{:keys [approved held]}
        (governor/censor [{:kind :capacity/allocate :capacity/allocated-units 100 :capacity/total-units 1000}
                          {:kind :capacity/allocate :capacity/allocated-units 500 :capacity/total-units 1000}
                          {:kind :control-reefer-compressor}])]
    (is (= 1 (count approved)))
    (is (= 2 (count held)))))

(deftest censor-escalated-bucket-for-grid-outage-mismatch
  (let [{:keys [approved held escalated]}
        (governor/censor [{:kind :log-inbound-shipment
                           :lot/power-outage-minutes 200
                           :grid-outage/source-actor "cloud-itonami-isic-3510"
                           :grid-outage/event-id "outage-1"
                           :grid-outage/duration-minutes 60}
                          {:kind :capacity/allocate :capacity/allocated-units 100 :capacity/total-units 1000}])]
    (is (= 1 (count escalated)))
    (is (= 1 (count approved)))
    (is (= 0 (count held)))
    (is (some #(re-find #"grid-outage" %) (:escalations (first escalated))))))
