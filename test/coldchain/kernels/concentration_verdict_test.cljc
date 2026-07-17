(ns coldchain.kernels.concentration-verdict-test
  "1 test per pure predicate in concentration-verdict, same discipline
  as cloud-itonami.kernels.security-verdict-test."
  (:require [clojure.test :refer [deftest is testing]]
            [coldchain.kernels.concentration-verdict :as cv]))

(deftest concentration-ratio-basic-arithmetic
  (is (= 0.3 (cv/concentration-ratio 300 1000)))
  (is (= 0.25 (cv/concentration-ratio 250 1000)))
  (is (= 0.0 (cv/concentration-ratio 0 1000))))

(deftest concentration-ratio-nil-on-malformed-input
  (testing "zero or negative total -> nil (can't divide meaningfully)"
    (is (nil? (cv/concentration-ratio 100 0)))
    (is (nil? (cv/concentration-ratio 100 -50))))
  (testing "negative allocated -> nil"
    (is (nil? (cv/concentration-ratio -10 1000))))
  (testing "non-numeric inputs -> nil"
    (is (nil? (cv/concentration-ratio nil 1000)))
    (is (nil? (cv/concentration-ratio 100 nil)))
    (is (nil? (cv/concentration-ratio "300" 1000)))))

(deftest concentration-limit-exceeded-boundary-discipline
  (testing "below the limit -> not exceeded (pass)"
    (is (= 0 (cv/concentration-limit-exceeded? 0.1 cv/default-concentration-limit))))
  (testing "above the limit -> exceeded (hold)"
    (is (= 1 (cv/concentration-limit-exceeded? 0.3 cv/default-concentration-limit))))
  (testing "exactly AT the limit -> not exceeded (boundary-inclusive pass)"
    (is (= 0 (cv/concentration-limit-exceeded? 0.25 cv/default-concentration-limit))))
  (testing "nil ratio never triggers a hold"
    (is (= 0 (cv/concentration-limit-exceeded? nil cv/default-concentration-limit)))))
