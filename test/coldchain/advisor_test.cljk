(ns coldchain.advisor-test
  "Coverage for coldchain.advisor's proposal drafting -- the grounding
  discipline under test (mirrors cloud-itonami-isic-0899's
  quarryops.advisor-test / cloud-itonami-isic-2813's
  pressureequip.pressureequipadvisor's own grounding tests): every
  proposal only echoes/normalizes request or store data, it never
  fabricates a reading, and `:reconcile-power-metering`'s
  `:equipment-assets` list is pulled from the STORE, never trusted off
  the request."
  (:require [clojure.test :refer [deftest is testing]]
            [coldchain.advisor :as advisor]
            [coldchain.store :as store]
            [coldchain.kernels.power-metering-verdict :as pmv]))

;; ── :tenant/onboard ──

(deftest onboard-echoes-patch-without-inventing-fields
  (let [p (advisor/infer {} {:kind :tenant/onboard :subject "acme-foods"
                             :patch {:name "Acme Foods K.K."}})]
    (is (= :tenant/onboard (:kind p)))
    (is (= "acme-foods" (:subject p)))
    (is (= :tenant/register (:effect p)))
    (is (= "Acme Foods K.K." (get-in p [:value :name])))
    (is (<= 0.0 (:confidence p) 1.0))))

(deftest onboard-lowers-confidence-when-tenant-already-registered
  (let [db (store/register-tenant {} "acme-foods" {:name "Acme Foods K.K."})
        p (advisor/infer db {:kind :tenant/onboard :subject "acme-foods" :patch {}})]
    (is (< (:confidence p) 0.9))))

;; ── :capacity/allocate ──

(deftest capacity-allocate-echoes-request-figures-and-computes-informational-ratio
  (let [p (advisor/infer {} {:kind :capacity/allocate :subject "acme-foods"
                             :patch {:capacity/allocated-units 100 :capacity/total-units 1000}})]
    (is (= 100 (:capacity/allocated-units p)))
    (is (= 1000 (:capacity/total-units p)))
    (is (re-find #"0\.1" (:rationale p)))
    (is (< 0.5 (:confidence p)))))

(deftest capacity-allocate-over-limit-lowers-confidence-but-still-echoes-figures
  (testing "the advisor's own confidence signal is informational only -- coldchain.governor is authoritative"
    (let [p (advisor/infer {} {:kind :capacity/allocate :subject "acme-foods"
                               :patch {:capacity/allocated-units 500 :capacity/total-units 1000}})]
      (is (= 500 (:capacity/allocated-units p)))
      (is (< (:confidence p) 0.5)))))

(deftest capacity-allocate-missing-fields-does-not-crash
  (let [p (advisor/infer {} {:kind :capacity/allocate :subject "acme-foods" :patch {}})]
    (is (nil? (:capacity/allocated-units p)))
    (is (= 0.5 (:confidence p)))))

;; ── :log-inbound-shipment / :log-outbound-shipment ──

(deftest inbound-shipment-echoes-governor-recognized-fields-verbatim
  (let [handoff {:handoff/id "h-1" :handoff/cold-chain-temp-min-c 0.0 :handoff/cold-chain-temp-max-c 3.0}
        p (advisor/infer {} {:kind :log-inbound-shipment :subject "lot-001"
                             :patch {:lot/commodity-class :coldchain/c3-chilled
                                     :lot/storage-temp-c 3.0
                                     :lot/power-outage-minutes 10
                                     :handoff handoff}})]
    (is (= :coldchain/c3-chilled (:lot/commodity-class p)))
    (is (= 3.0 (:lot/storage-temp-c p)))
    (is (= 10 (:lot/power-outage-minutes p)))
    (is (= handoff (:handoff p)))
    (is (= :lot/log-inbound (:effect p)))
    (is (= :inbound (get-in p [:value :lot/phase])))))

(deftest outbound-shipment-sets-outbound-phase-in-value
  (let [p (advisor/infer {} {:kind :log-outbound-shipment :subject "lot-003"
                             :patch {:lot/commodity-class :coldchain/c3-chilled}})]
    (is (= :lot/log-outbound (:effect p)))
    (is (= :outbound (get-in p [:value :lot/phase])))))

(deftest lot-confidence-drops-on-out-of-range-temperature
  (testing "the advisor's own confidence signal reuses coldchain.registry's own predicate -- informational only"
    (let [in-range (advisor/infer {} {:kind :log-inbound-shipment :subject "lot-001"
                                      :patch {:lot/commodity-class :coldchain/c3-chilled :lot/storage-temp-c 3.0}})
          out-of-range (advisor/infer {} {:kind :log-inbound-shipment :subject "lot-002"
                                          :patch {:lot/commodity-class :coldchain/f4-deep-frozen :lot/storage-temp-c -5.0}})]
      (is (> (:confidence in-range) (:confidence out-of-range))))))

;; ── :flag-temperature-excursion ──

(deftest temperature-excursion-flag-falls-back-to-stores-own-commodity-class
  (testing "a bare excursion report with no :lot/commodity-class in the request still carries the lot's ALREADY-KNOWN class from the store into the proposal -- grounded, not fabricated"
    (let [db (store/log-lot {} "lot-001" {:lot/commodity-class :coldchain/c3-chilled})
          p (advisor/infer db {:kind :flag-temperature-excursion :subject "lot-001"
                               :patch {:lot/storage-temp-c 15.0}})]
      (is (= :coldchain/c3-chilled (:lot/commodity-class p)))
      (is (= 15.0 (:lot/storage-temp-c p)))
      (is (= :flag-temperature-excursion (:kind p))))))

(deftest temperature-excursion-flag-with-no-store-record-and-no-patch-class-is-nil-not-fabricated
  (let [p (advisor/infer {} {:kind :flag-temperature-excursion :subject "lot-999" :patch {:lot/storage-temp-c 15.0}})]
    (is (nil? (:lot/commodity-class p)))))

;; ── :register-equipment-asset ──

(def ^:private valid-patch
  {:equipment-asset/unit-type-id :unit/industrial-refrigeration-compressor
   :equipment-asset/source-actor "cloud-itonami-isic-2813"
   :equipment-asset/dispatch-ref "JPN-PEQ-000000"
   :equipment-asset/installed-at-iso "2026-01-01T00:00:00Z"})

(deftest equipment-asset-registration-echoes-request-fields-and-sets-id-from-subject
  (let [p (advisor/infer {} {:kind :register-equipment-asset :subject "ea-1" :patch valid-patch})]
    (is (= "ea-1" (:equipment-asset/id p)))
    (is (= :unit/industrial-refrigeration-compressor (:equipment-asset/unit-type-id p)))
    (is (= :equipment-asset/register (:effect p)))
    (is (> (:confidence p) 0.5))))

(deftest equipment-asset-registration-missing-required-field-lowers-confidence
  (let [p (advisor/infer {} {:kind :register-equipment-asset :subject "ea-1"
                             :patch (dissoc valid-patch :equipment-asset/dispatch-ref)})]
    (is (= 0.3 (:confidence p)))))

(deftest equipment-asset-registration-already-registered-lowers-confidence-informationally
  (let [db (store/register-equipment-asset {} "ea-1" valid-patch)
        p (advisor/infer db {:kind :register-equipment-asset :subject "ea-1" :patch valid-patch})]
    (is (= 0.4 (:confidence p)))
    (is (re-find #"既に登録済み" (:rationale p)))))

;; ── :reconcile-power-metering ──

(def ^:private one-clean-compressor
  {:equipment-asset/id "ea-1"
   :equipment-asset/unit-type-id :unit/industrial-refrigeration-compressor
   :equipment-asset/source-actor "cloud-itonami-isic-2813"
   :equipment-asset/dispatch-ref "JPN-PEQ-000000"
   :equipment-asset/installed-at-iso "2026-01-01T00:00:00Z"})

(deftest power-metering-reconciliation-pulls-equipment-assets-from-store-never-from-request
  (testing "a request that tries to smuggle a fabricated :equipment-assets list is ignored -- this actor's OWN registered assets (from the store) are what governs the cross-check, per coldchain.governor's own docstring"
    (let [db (store/register-equipment-asset {} "ea-1" one-clean-compressor)
          fabricated [{:equipment-asset/id "fake" :equipment-asset/unit-type-id :unit/fake
                       :equipment-asset/installed-at-iso "2020-01-01T00:00:00Z"}]
          p (advisor/infer db {:kind :reconcile-power-metering :subject "feeder-1"
                               :patch {:power-metering/period-start-iso "2026-07-01T00:00:00Z"
                                       :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                                       :power-metering/consumed-kwh 30000.0
                                       :equipment-assets fabricated}})]
      (is (= [one-clean-compressor] (:equipment-assets p)))
      (is (not= fabricated (:equipment-assets p))))))

(deftest power-metering-reconciliation-computes-the-same-expected-figure-the-governor-will-independently-rederive
  (let [db (store/register-equipment-asset {} "ea-1" one-clean-compressor)
        p (advisor/infer db {:kind :reconcile-power-metering :subject "feeder-1"
                             :patch {:power-metering/period-start-iso "2026-07-01T00:00:00Z"
                                     :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                                     :power-metering/consumed-kwh 30000.0}})
        expected (pmv/expected-consumption-kwh [one-clean-compressor]
                                                (fn [_] 90.0)
                                                "2026-07-01T00:00:00Z" "2026-07-08T00:00:00Z")]
    (is (re-find (re-pattern (str expected)) (:rationale p)))
    (is (< (:confidence p) 0.5) "far off expectation -> lower informational confidence")))

(deftest power-metering-reconciliation-with-no-registered-assets-cannot-compute-an-expectation
  (let [p (advisor/infer {} {:kind :reconcile-power-metering :subject "feeder-1"
                             :patch {:power-metering/consumed-kwh 30000.0}})]
    (is (= [] (:equipment-assets p)))
    (is (= 0.6 (:confidence p)))))

;; ── closed vocabulary / protocol plumbing ──

(deftest unrecognized-kind-is-a-safe-zero-confidence-noop
  (let [p (advisor/infer {} {:kind :control-reefer-compressor :subject "x"})]
    (is (= :noop (:effect p)))
    (is (zero? (:confidence p)))
    (is (= [] (:cites p)))))

(deftest mock-advisor-routes-through-infer
  (let [a (advisor/mock-advisor)
        p (advisor/-advise a {} {:kind :tenant/onboard :subject "acme-foods" :patch {}})]
    (is (= :tenant/onboard (:kind p)))
    (is (= :tenant/register (:effect p)))))

(deftest trace-carries-decision-grounded-fields
  (let [request {:kind :capacity/allocate :subject "acme-foods"
                 :patch {:capacity/allocated-units 100 :capacity/total-units 1000}}
        proposal (advisor/infer {} request)
        t (advisor/trace request proposal)]
    (is (= :capacity/allocate (:kind t)))
    (is (= "acme-foods" (:subject t)))
    (is (= (:confidence proposal) (:confidence t)))))
