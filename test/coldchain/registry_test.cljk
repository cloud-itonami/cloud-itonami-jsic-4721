(ns coldchain.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [coldchain.registry :as registry]))

(deftest storage-temp-out-of-range-detects-both-directions
  (testing "below the window"
    (is (true? (registry/storage-temp-out-of-range? -25.0 -20.0 -10.0))))
  (testing "above the window"
    (is (true? (registry/storage-temp-out-of-range? -5.0 -20.0 -10.0))))
  (testing "within the window"
    (is (false? (registry/storage-temp-out-of-range? -15.0 -20.0 -10.0))))
  (testing "boundary values are within range (inclusive)"
    (is (false? (registry/storage-temp-out-of-range? -20.0 -20.0 -10.0)))
    (is (false? (registry/storage-temp-out-of-range? -10.0 -20.0 -10.0)))))

(deftest power-outage-exceeds-max-strict-greater-than
  (is (true? (registry/power-outage-exceeds-max? 130 120)))
  (is (false? (registry/power-outage-exceeds-max? 120 120)))
  (is (false? (registry/power-outage-exceeds-max? 60 120))))

(deftest grid-outage-duration-mismatch-symmetric-tolerance
  (testing "within tolerance (self-report longer) is not a mismatch"
    (is (false? (registry/grid-outage-duration-mismatch? 70 60 15))))
  (testing "within tolerance (self-report shorter) is not a mismatch"
    (is (false? (registry/grid-outage-duration-mismatch? 50 60 15))))
  (testing "exactly at the tolerance boundary is not a mismatch (inclusive)"
    (is (false? (registry/grid-outage-duration-mismatch? 75 60 15))))
  (testing "beyond tolerance (self-report much longer) is a mismatch"
    (is (true? (registry/grid-outage-duration-mismatch? 200 60 15))))
  (testing "beyond tolerance (self-report much shorter) is a mismatch"
    (is (true? (registry/grid-outage-duration-mismatch? 10 60 15))))
  (testing "exact agreement is never a mismatch"
    (is (false? (registry/grid-outage-duration-mismatch? 60 60 15)))))
