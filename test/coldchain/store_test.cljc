(ns coldchain.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [coldchain.store :as store]))

(deftest tenant-registration-round-trip
  (let [st (store/register-tenant {} "acme-foods" {:capacity/allocated-units 100})]
    (is (true? (store/tenant-registered? st "acme-foods")))
    (is (= {:capacity/allocated-units 100} (store/tenant st "acme-foods")))
    (is (false? (store/tenant-registered? st "unknown-tenant")))))

(deftest storage-lot-round-trip
  (let [st (store/log-lot {} "lot-001" {:lot/commodity-class :coldchain/c3-chilled})]
    (is (= {:lot/commodity-class :coldchain/c3-chilled} (store/storage-lot st "lot-001")))
    (is (nil? (store/storage-lot st "lot-999")))))

(deftest audit-trail-append-only
  (testing "empty store has an empty trail"
    (is (= [] (store/audit-trail {}))))
  (testing "facts append in order"
    (let [st (-> {}
                 (store/append-fact {:t :fact-1})
                 (store/append-fact {:t :fact-2}))]
      (is (= [{:t :fact-1} {:t :fact-2}] (store/audit-trail st))))))
