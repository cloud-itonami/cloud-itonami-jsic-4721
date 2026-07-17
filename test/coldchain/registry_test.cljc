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
