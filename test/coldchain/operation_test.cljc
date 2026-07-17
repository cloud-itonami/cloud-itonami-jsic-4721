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

(deftest run-operation-escalate-test
  (testing "a soft-escalated proposal (grid-outage-duration-mismatch) is NOT ok, but its
  audit fact is labelled :escalate, not :hold, and carries the escalations (not dropped)"
    (let [proposal {:kind :log-inbound-shipment
                    :lot/power-outage-minutes 200
                    :grid-outage/source-actor "cloud-itonami-isic-3510"
                    :grid-outage/event-id "outage-1"
                    :grid-outage/duration-minutes 60}
          result (operation/run-operation {} proposal governor/check operation/hold-fact)]
      (is (false? (:ok? result)))
      (is (= :escalate (:status (:verdict result))))
      (is (= 1 (count (:facts result))))
      (is (= :escalate (:disposition (first (:facts result))))
        "the fact must not be mislabeled :hold")
      (is (some #(re-find #"grid-outage" %) (:reasons (first (:facts result))))
        "the escalations must not be silently dropped"))))
