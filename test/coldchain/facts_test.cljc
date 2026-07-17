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
