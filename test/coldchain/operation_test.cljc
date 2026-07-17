(ns coldchain.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [coldchain.operation :as operation]
            [coldchain.governor :as governor]))

(deftest run-operation-commit-test
  (testing "a clean, passing proposal commits with no hold facts"
    (let [proposal {:kind :capacity/allocate :capacity/allocated-units 100 :capacity/total-units 1000}
          result (operation/run-operation {} proposal governor/check operation/hold-fact)]
      (is (true? (:ok? result)))
      (is (= [] (:facts result))))))

(deftest run-operation-hold-test
  (testing "a held proposal (over the concentration limit) produces a hold fact"
    (let [proposal {:kind :capacity/allocate :capacity/allocated-units 500 :capacity/total-units 1000}
          result (operation/run-operation {} proposal governor/check operation/hold-fact)]
      (is (false? (:ok? result)))
      (is (= 1 (count (:facts result))))
      (is (= :governor-hold (:t (first (:facts result)))))
      (is (= :hold (:status (:verdict result)))))))

(deftest run-operation-kind-not-allowed-test
  (testing "an out-of-allowlist kind is a hold, routed through the same hold-fact path"
    (let [proposal {:kind :control-reefer-compressor}
          result (operation/run-operation {} proposal governor/check operation/hold-fact)]
      (is (false? (:ok? result)))
      (is (some #(re-find #"allowlist" %) (:reasons (:verdict result)))))))
