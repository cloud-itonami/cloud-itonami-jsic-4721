(ns coldchain.sim
  "Simulation driver for the refrigerated-warehousing (cold-chain 3PL,
  JSIC 4721) coordination actor -- end-to-end offline proof that
  `coldchain.operation`'s langgraph-clj actor and `coldchain.governor`
  compose correctly, mirroring cloud-itonami-isic-0899's
  `quarryops.sim` / cloud-itonami-isic-2813's `pressureequip.sim`
  structure (a scripted walk through clean auto-commits, an
  escalate-then-human-approves round trip, and several HARD holds that
  never reach a human).

  For CLI: clojure -M:run

  Walks:
    1. register-equipment-asset ea-1 (clean -- auto-commits)
    2. register-equipment-asset ea-1 AGAIN (double-registration -> HARD hold)
    3. tenant/onboard acme-foods (clean -- auto-commits)
    4. capacity/allocate acme-foods 100/1000 = 10% (clean -- auto-commits)
    5. capacity/allocate acme-foods 500/1000 = 50% (over the 25% cap -> HARD hold)
    6. log-inbound-shipment lot-001, c3-chilled, in-range temp + in-range
       self-reported outage, but a grid-outage-duration-mismatch reference
       (isic-3510) -> SOFT escalate -- human approves -> commits
    7. log-inbound-shipment lot-002, f4-deep-frozen, storage temp out of
       range -> HARD hold, never reaches a human
    8. log-outbound-shipment lot-003, a downstream :handoff whose
       quantity-kg alone exceeds 25% of total capacity -> HARD hold
       (outbound-destination concentration limit)
    9. flag-temperature-excursion lot-001 (no dedicated governor rule for
       this kind -- clean, auto-commits; the store-grounded commodity-class
       fallback picks up lot-001's own already-logged class from step 6)
   10. reconcile-power-metering feeder-1 against ea-1's own registered
       90kW rating -- a self-reported consumption far beyond the
       independently-expected figure -> SOFT escalate -- human approves
       -> commits"
  (:require [langgraph.graph :as g]
            [coldchain.store :as store]
            [coldchain.operation :as op]))

(defn- exec! [actor tid request]
  (g/run* actor {:request request} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "warehouse-op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [mem-store (store/mem-store)
        actor (op/build mem-store)]

    (println "== register-equipment-asset ea-1 (clean; auto-commits) ==")
    (println (exec! actor "t1"
                    {:kind :register-equipment-asset :subject "ea-1"
                     :patch {:equipment-asset/unit-type-id :unit/industrial-refrigeration-compressor
                             :equipment-asset/source-actor "cloud-itonami-isic-2813"
                             :equipment-asset/dispatch-ref "JPN-PEQ-000000"
                             :equipment-asset/installed-at-iso "2026-01-01T00:00:00Z"}}))

    (println "== register-equipment-asset ea-1 AGAIN (double-registration -> HARD hold) ==")
    (println (exec! actor "t2"
                    {:kind :register-equipment-asset :subject "ea-1"
                     :patch {:equipment-asset/unit-type-id :unit/industrial-refrigeration-compressor
                             :equipment-asset/source-actor "cloud-itonami-isic-2813"
                             :equipment-asset/dispatch-ref "JPN-PEQ-000001"
                             :equipment-asset/installed-at-iso "2026-07-01T00:00:00Z"}}))

    (println "== tenant/onboard acme-foods (clean; auto-commits) ==")
    (println (exec! actor "t3"
                    {:kind :tenant/onboard :subject "acme-foods"
                     :patch {:name "Acme Foods K.K." :jurisdiction :jp/mlit}}))

    (println "== capacity/allocate acme-foods 100/1000 = 10% (clean; auto-commits) ==")
    (println (exec! actor "t4"
                    {:kind :capacity/allocate :subject "acme-foods"
                     :patch {:capacity/allocated-units 100 :capacity/total-units 1000}}))

    (println "== capacity/allocate acme-foods 500/1000 = 50% (over 25% cap -> HARD hold) ==")
    (println (exec! actor "t5"
                    {:kind :capacity/allocate :subject "acme-foods"
                     :patch {:capacity/allocated-units 500 :capacity/total-units 1000}}))

    (println "== log-inbound-shipment lot-001, in-range physically, grid-outage-duration-mismatch (SOFT escalate) ==")
    (let [r (exec! actor "t6"
                   {:kind :log-inbound-shipment :subject "lot-001"
                    :patch {:lot/commodity-class :coldchain/c3-chilled
                            :lot/storage-temp-c 3.0
                            :lot/power-outage-minutes 60
                            :grid-outage/source-actor "cloud-itonami-isic-3510"
                            :grid-outage/event-id "outage-1"
                            :grid-outage/duration-minutes 200}})]
      (println r)
      (println "-- human warehouse operator reviews & approves --")
      (println (approve! actor "t6")))

    (println "== log-inbound-shipment lot-002, f4-deep-frozen storage temp out of range -> HARD hold ==")
    (println (exec! actor "t7"
                    {:kind :log-inbound-shipment :subject "lot-002"
                     :patch {:lot/commodity-class :coldchain/f4-deep-frozen
                             :lot/storage-temp-c -5.0}}))

    (println "== log-outbound-shipment lot-003, downstream handoff quantity 300/1000 = 30% (over 25% cap -> HARD hold) ==")
    (println (exec! actor "t8"
                    {:kind :log-outbound-shipment :subject "lot-003"
                     :patch {:capacity/total-units 1000
                             :handoff {:handoff/id "h-out-1"
                                       :handoff/source-actor "cloud-itonami-jsic-4721"
                                       :handoff/batch-id "lot-003"
                                       :handoff/product-type-id :coldchain/c3-chilled
                                       :handoff/cold-chain-temp-min-c 2.0
                                       :handoff/cold-chain-temp-max-c 10.0
                                       :handoff/quantity-kg 300
                                       :handoff/dispatched-at-iso "2026-07-19T00:00:00Z"}}}))

    (println "== flag-temperature-excursion lot-001 (no dedicated governor rule; clean -- auto-commits) ==")
    (println (exec! actor "t9"
                    {:kind :flag-temperature-excursion :subject "lot-001"
                     :patch {:lot/storage-temp-c 15.0 :flagged-at-iso "2026-07-19T08:00:00Z"}}))

    (println "== reconcile-power-metering feeder-1 vs ea-1's own 90kW rating (deviation beyond threshold -> SOFT escalate) ==")
    (let [r (exec! actor "t10"
                   {:kind :reconcile-power-metering :subject "feeder-1"
                    :patch {:power-metering/id "feeder-1-MTR-2026-07-01T00:00:00Z"
                            :power-metering/feeder-ref "feeder-1"
                            :power-metering/client-actor "cloud-itonami-jsic-4721"
                            :power-metering/period-start-iso "2026-07-01T00:00:00Z"
                            :power-metering/period-end-iso "2026-07-08T00:00:00Z"
                            :power-metering/consumed-kwh 30000.0}})]
      (println r)
      (println "-- human warehouse operator reviews & approves --")
      (println (approve! actor "t10")))

    (println "== audit ledger ==")
    (doseq [f (store/audit-trail (store/snapshot mem-store))] (println f))

    (println "== final store snapshot (tenants/lots/equipment-assets) ==")
    (println (dissoc (store/snapshot mem-store) :facts))))
