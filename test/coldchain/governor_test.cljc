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

;; ── censor ──

(deftest censor-buckets-every-proposal-exactly-once
  (let [{:keys [approved held]}
        (governor/censor [{:kind :capacity/allocate :capacity/allocated-units 100 :capacity/total-units 1000}
                          {:kind :capacity/allocate :capacity/allocated-units 500 :capacity/total-units 1000}
                          {:kind :control-reefer-compressor}])]
    (is (= 1 (count approved)))
    (is (= 2 (count held)))))
