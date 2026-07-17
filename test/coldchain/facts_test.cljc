(ns coldchain.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [coldchain.facts :as facts]))

(deftest commodity-class-by-id-known-classes
  (is (= -20.0 (:storage-temp-max-c (facts/commodity-class-by-id :coldchain/f4-deep-frozen))))
  (is (= 10.0 (:storage-temp-max-c (facts/commodity-class-by-id :coldchain/c3-chilled)))))

(deftest commodity-class-by-id-unknown-returns-nil
  (is (nil? (facts/commodity-class-by-id :coldchain/nonexistent))))

(deftest jurisdiction-by-id-known-jurisdiction
  (testing "jp/mlit requires warehouse-business-license evidence"
    (is (= [:warehouse-business-license :cold-storage-facility-inspection]
           (:required-evidence (facts/jurisdiction-by-id :jp/mlit))))))

(deftest jurisdiction-by-id-unknown-returns-nil
  (is (nil? (facts/jurisdiction-by-id :xx/nonexistent))))

;; ──────────────── Cross-Actor Handoff (isic-1075 -> jsic-4721) ────────────────

(deftest handoff-compatible-with-commodity-class-test
  (testing "chilled handoff window overlapping a c3-chilled class is compatible"
    (let [c (facts/commodity-class-by-id :coldchain/c3-chilled)]
      (is (true? (facts/handoff-compatible-with-commodity-class? 0.0 3.0 c)))))

  (testing "chilled handoff window assigned to an f4-deep-frozen class is incompatible (temperature-tier mismatch)"
    (let [c (facts/commodity-class-by-id :coldchain/f4-deep-frozen)]
      (is (false? (facts/handoff-compatible-with-commodity-class? 0.0 3.0 c)))))

  (testing "frozen-fish handoff window overlapping f3-frozen is compatible"
    (let [c (facts/commodity-class-by-id :coldchain/f3-frozen)]
      (is (true? (facts/handoff-compatible-with-commodity-class? -22.0 -18.0 c)))))

  (testing "frozen-fish handoff window assigned to c3-chilled is incompatible"
    (let [c (facts/commodity-class-by-id :coldchain/c3-chilled)]
      (is (false? (facts/handoff-compatible-with-commodity-class? -22.0 -18.0 c)))))

  (testing "inverted handoff window (min > max) is incompatible"
    (let [c (facts/commodity-class-by-id :coldchain/c3-chilled)]
      (is (false? (facts/handoff-compatible-with-commodity-class? 3.0 0.0 c)))))

  (testing "nil commodity class is incompatible"
    (is (false? (facts/handoff-compatible-with-commodity-class? 0.0 3.0 nil)))))
