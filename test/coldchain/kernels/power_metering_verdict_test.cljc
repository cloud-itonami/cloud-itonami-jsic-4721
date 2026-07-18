(ns coldchain.kernels.power-metering-verdict-test
  "1+ tests per pure predicate in power-metering-verdict, same
  discipline as coldchain.kernels.concentration-verdict-test."
  (:require [clojure.test :refer [deftest is testing]]
            [coldchain.kernels.power-metering-verdict :as pmv]))

;; ----------------------------- asset-operating-hours-in-period -----------------------------

(deftest asset-installed-before-period-runs-the-full-period
  (is (= 168.0 (pmv/asset-operating-hours-in-period
                "2026-06-01T00:00:00Z" "2026-07-01T00:00:00Z" "2026-07-08T00:00:00Z"))))

(deftest asset-installed-partway-through-runs-from-install-to-period-end
  (is (= 24.0 (pmv/asset-operating-hours-in-period
               "2026-07-07T00:00:00Z" "2026-07-01T00:00:00Z" "2026-07-08T00:00:00Z"))))

(deftest asset-installed-after-period-end-contributes-zero-hours
  (is (= 0.0 (pmv/asset-operating-hours-in-period
              "2026-08-01T00:00:00Z" "2026-07-01T00:00:00Z" "2026-07-08T00:00:00Z"))))

(deftest asset-with-unparseable-or-missing-timestamps-contributes-zero-hours
  (testing "unparseable install timestamp"
    (is (= 0.0 (pmv/asset-operating-hours-in-period
                "not-a-date" "2026-07-01T00:00:00Z" "2026-07-08T00:00:00Z"))))
  (testing "nil install timestamp"
    (is (= 0.0 (pmv/asset-operating-hours-in-period
                nil "2026-07-01T00:00:00Z" "2026-07-08T00:00:00Z"))))
  (testing "malformed period (end before start)"
    (is (= 0.0 (pmv/asset-operating-hours-in-period
                "2026-06-01T00:00:00Z" "2026-07-08T00:00:00Z" "2026-07-01T00:00:00Z")))))

;; ----------------------------- expected-consumption-kwh -----------------------------

(def ^:private power-kw-of
  {:unit/industrial-refrigeration-compressor 90.0})

(deftest expected-consumption-sums-across-assets
  (testing "two compressors, both installed before the period -> 2 x 90kW x 168h"
    (is (= (* 2 90.0 168.0)
           (pmv/expected-consumption-kwh
            [{:equipment-asset/unit-type-id :unit/industrial-refrigeration-compressor
              :equipment-asset/installed-at-iso "2026-01-01T00:00:00Z"}
             {:equipment-asset/unit-type-id :unit/industrial-refrigeration-compressor
              :equipment-asset/installed-at-iso "2026-01-01T00:00:00Z"}]
            power-kw-of "2026-07-01T00:00:00Z" "2026-07-08T00:00:00Z")))))

(deftest expected-consumption-unrecognized-unit-type-contributes-zero
  (is (= 0.0
         (pmv/expected-consumption-kwh
          [{:equipment-asset/unit-type-id :unit/unknown-widget
            :equipment-asset/installed-at-iso "2026-01-01T00:00:00Z"}]
          power-kw-of "2026-07-01T00:00:00Z" "2026-07-08T00:00:00Z"))))

(deftest expected-consumption-empty-assets-is-zero
  (is (= 0.0 (pmv/expected-consumption-kwh [] power-kw-of "2026-07-01T00:00:00Z" "2026-07-08T00:00:00Z"))))

;; ----------------------------- deviation-ratio / deviation-exceeds-threshold? -----------------------------

(deftest deviation-ratio-basic-arithmetic
  (is (= 0.2 (pmv/deviation-ratio 1200.0 1000.0)))
  (is (= 0.2 (pmv/deviation-ratio 800.0 1000.0)) "symmetric -- consumed below expected too")
  (is (= 0.0 (pmv/deviation-ratio 1000.0 1000.0))))

(deftest deviation-ratio-nil-on-malformed-input
  (is (nil? (pmv/deviation-ratio 1000 0)))
  (is (nil? (pmv/deviation-ratio 1000 -50)))
  (is (nil? (pmv/deviation-ratio nil 1000)))
  (is (nil? (pmv/deviation-ratio 1000 nil))))

(deftest deviation-exceeds-threshold-boundary-discipline
  (testing "below the threshold -> not exceeded (pass)"
    (is (= 0 (pmv/deviation-exceeds-threshold? 0.1 pmv/default-deviation-threshold))))
  (testing "above the threshold -> exceeded (escalate)"
    (is (= 1 (pmv/deviation-exceeds-threshold? 0.3 pmv/default-deviation-threshold))))
  (testing "exactly AT the threshold -> not exceeded (boundary-inclusive pass)"
    (is (= 0 (pmv/deviation-exceeds-threshold? 0.2 pmv/default-deviation-threshold))))
  (testing "nil ratio never triggers an escalation"
    (is (= 0 (pmv/deviation-exceeds-threshold? nil pmv/default-deviation-threshold)))))
