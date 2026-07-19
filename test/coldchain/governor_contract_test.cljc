(ns coldchain.governor-contract-test
  "The governor contract as executable end-to-end tests, driven through
  the full langgraph-clj `coldchain.operation` StateGraph (intake ->
  advise -> govern -> decide -> commit | hold | request-approval).
  Mirrors cloud-itonami-isic-0899's `quarryops.governor-contract-test`
  / cloud-itonami-isic-2813's `pressureequip.governor_contract_test`
  structure. The single invariant under test:

    ColdChainAdvisor never commits a proposal coldchain.governor would
    reject; a HARD hold never mutates the store; a SOFT escalation
    pauses for a human and only commits after approval (never
    auto-approves); every decision leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [coldchain.store :as store]
            [coldchain.operation :as op]))

(defn- fresh []
  (let [ms (store/mem-store)]
    [ms (op/build ms)]))

(defn- exec! [actor tid request]
  (g/run* actor {:request request} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "warehouse-op-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected}} {:thread-id tid :resume? true}))

;; ── clean auto-commit paths ──

(deftest clean-equipment-asset-registration-auto-commits
  (let [[ms actor] (fresh)
        res (exec! actor "t1"
                   {:kind :register-equipment-asset :subject "ea-1"
                    :patch {:equipment-asset/unit-type-id :unit/industrial-refrigeration-compressor
                            :equipment-asset/source-actor "cloud-itonami-isic-2813"
                            :equipment-asset/dispatch-ref "JPN-PEQ-000000"
                            :equipment-asset/installed-at-iso "2026-01-01T00:00:00Z"}})]
    (is (= :done (:status res)))
    (is (= :commit (get-in res [:state :disposition])))
    (is (store/equipment-asset-registered? (store/snapshot ms) "ea-1") "SSoT actually updated")
    (is (= 1 (count (store/audit-trail (store/snapshot ms)))))))

(deftest clean-capacity-allocation-within-limit-auto-commits
  (let [[ms actor] (fresh)
        res (exec! actor "t1"
                   {:kind :capacity/allocate :subject "acme-foods"
                    :patch {:capacity/allocated-units 100 :capacity/total-units 1000}})]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 100 (:capacity/allocated-units (store/tenant (store/snapshot ms) "acme-foods"))))))

;; ── HARD hold paths -- never mutate the store, no human override ──

(deftest duplicate-equipment-asset-registration-hard-holds-and-does-not-overwrite
  (let [[ms actor] (fresh)
        patch {:equipment-asset/unit-type-id :unit/industrial-refrigeration-compressor
               :equipment-asset/source-actor "cloud-itonami-isic-2813"
               :equipment-asset/dispatch-ref "JPN-PEQ-000000"
               :equipment-asset/installed-at-iso "2026-01-01T00:00:00Z"}]
    (exec! actor "t1" {:kind :register-equipment-asset :subject "ea-1" :patch patch})
    (let [res (exec! actor "t2" {:kind :register-equipment-asset :subject "ea-1"
                                 :patch (assoc patch :equipment-asset/dispatch-ref "JPN-PEQ-000001")})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= "JPN-PEQ-000000" (:equipment-asset/dispatch-ref (store/equipment-asset (store/snapshot ms) "ea-1")))
          "the FIRST registration's data must survive unchanged -- a hard hold never mutates the store")
      (is (some #(= :governor-hold (:t %)) (store/audit-trail (store/snapshot ms)))))))

(deftest capacity-allocation-above-limit-hard-holds-and-does-not-mutate-tenant
  (let [[ms actor] (fresh)
        res (exec! actor "t1"
                   {:kind :capacity/allocate :subject "acme-foods"
                    :patch {:capacity/allocated-units 500 :capacity/total-units 1000}})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (nil? (store/tenant (store/snapshot ms) "acme-foods")) "a hard hold never mutates the store")))

(deftest inbound-shipment-storage-temp-out-of-range-hard-holds
  (let [[ms actor] (fresh)
        res (exec! actor "t1"
                   {:kind :log-inbound-shipment :subject "lot-002"
                    :patch {:lot/commodity-class :coldchain/f4-deep-frozen :lot/storage-temp-c -5.0}})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (nil? (store/storage-lot (store/snapshot ms) "lot-002")))))

(deftest outbound-handoff-quantity-over-concentration-limit-hard-holds
  (let [[ms actor] (fresh)
        res (exec! actor "t1"
                   {:kind :log-outbound-shipment :subject "lot-003"
                    :patch {:capacity/total-units 1000
                            :handoff {:handoff/id "h-out-1" :handoff/source-actor "cloud-itonami-jsic-4721"
                                      :handoff/quantity-kg 300}}})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (nil? (store/storage-lot (store/snapshot ms) "lot-003")))))

;; ── SOFT escalate paths -- pause for a human, only commit after approval ──

(deftest grid-outage-duration-mismatch-escalates-then-human-approves-and-commits
  (let [[ms actor] (fresh)
        res (exec! actor "t1"
                   {:kind :log-inbound-shipment :subject "lot-001"
                    :patch {:lot/commodity-class :coldchain/c3-chilled
                            :lot/storage-temp-c 3.0
                            :lot/power-outage-minutes 60
                            :grid-outage/source-actor "cloud-itonami-isic-3510"
                            :grid-outage/event-id "outage-1"
                            :grid-outage/duration-minutes 200}})]
    (is (= :interrupted (:status res)) "paused for human approval, not auto-committed")
    (is (nil? (store/storage-lot (store/snapshot ms) "lot-001")) "nothing committed yet")
    (let [res2 (approve! actor "t1")]
      (is (= :commit (get-in res2 [:state :disposition])))
      (is (some? (store/storage-lot (store/snapshot ms) "lot-001")) "committed only after approval"))))

(deftest grid-outage-duration-mismatch-escalate-rejected-by-human-never-commits
  (let [[ms actor] (fresh)]
    (exec! actor "t1"
          {:kind :log-inbound-shipment :subject "lot-001"
           :patch {:lot/commodity-class :coldchain/c3-chilled
                   :lot/storage-temp-c 3.0
                   :lot/power-outage-minutes 60
                   :grid-outage/source-actor "cloud-itonami-isic-3510"
                   :grid-outage/event-id "outage-1"
                   :grid-outage/duration-minutes 200}})
    (let [res (reject! actor "t1")]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/storage-lot (store/snapshot ms) "lot-001"))
          "a human rejecting an escalation must never commit, same as a hard hold"))))

(deftest power-metering-deviation-escalates-then-human-approves
  (let [[_ms actor] (fresh)]
    (exec! actor "t1"
          {:kind :register-equipment-asset :subject "ea-1"
           :patch {:equipment-asset/unit-type-id :unit/industrial-refrigeration-compressor
                   :equipment-asset/source-actor "cloud-itonami-isic-2813"
                   :equipment-asset/dispatch-ref "JPN-PEQ-000000"
                   :equipment-asset/installed-at-iso "2026-01-01T00:00:00Z"}})
    (let [res (exec! actor "t2"
                     {:kind :reconcile-power-metering :subject "feeder-1"
                      :patch {:power-metering/period-start-iso "2026-07-01T00:00:00Z"
                              :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                              :power-metering/consumed-kwh 30000.0}})]
      (is (= :interrupted (:status res)))
      (let [res2 (approve! actor "t2")]
        (is (= :commit (get-in res2 [:state :disposition])))))))

;; ── flag-temperature-excursion -- no dedicated governor rule, auto-commits when clean ──

(deftest temperature-excursion-flag-has-no-dedicated-governor-rule-so-auto-commits
  (testing "coldchain.governor has no rule for :flag-temperature-excursion (falls through :default -> always structurally clean on this actor's OWN invariants) -- this actor's own operation layer routes purely off the governor's verdict, so a clean flag commits like any other :pass, see coldchain.operation ns docstring"
    (let [[_ms actor] (fresh)
          res (exec! actor "t1"
                     {:kind :flag-temperature-excursion :subject "lot-001"
                      :patch {:lot/storage-temp-c 15.0}})]
      (is (= :commit (get-in res [:state :disposition]))))))

;; ── closed allowlist ──

(deftest kind-outside-allowlist-hard-holds-through-the-full-graph
  (let [[ms actor] (fresh)
        res (exec! actor "t1" {:kind :control-reefer-compressor :subject "x" :patch {}})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= 1 (count (store/audit-trail (store/snapshot ms))))
        "the hold path still leaves exactly one ledger fact")
    (is (= :governor-hold (:t (first (store/audit-trail (store/snapshot ms))))))))
