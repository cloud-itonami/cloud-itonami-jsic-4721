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
