(ns coldchain.sim
  "Simulation driver for testing the refrigerated-warehousing
  (cold-chain 3PL, JSIC 4721) coordination actor end-to-end.

  For CLI: clojure -M:dev:run

  Example flow:
    1. Start with empty store
    2. Register a client tenant
    3. Propose a :capacity/allocate for that tenant
    4. Governor validates against capacity-concentration-limit
    5. If valid, audit fact is committed
    6. CLI prints audit trail")

(defn -main [& _args]
  (println "coldchain simulation: not yet implemented.")
  (println "TODO: integrate langgraph-clj StateGraph when available."))
