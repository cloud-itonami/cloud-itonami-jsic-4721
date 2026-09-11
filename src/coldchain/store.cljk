(ns coldchain.store
  "Store abstraction for refrigerated-warehousing client tenants and
  storage lots. Current implementation operates on plain data
  (`{:tenants {...} :lots {...} :facts [...]}`); production should
  migrate this seam to Datomic/kotoba-server (the same seam point all
  cloud-itonami-vertical actors use) while keeping the same
  pure-function surface. Mirrors cloud-itonami-isic-1075's
  `mealops.store` / cloud-itonami-isic-5210's `terminal.store`.

  A client tenant is a warehouse customer with an allocated share of
  total capacity (see coldchain.governor's capacity-concentration-limit
  invariant, which validates `:capacity/allocate` proposals BEFORE
  they reach this store -- the store itself does not enforce the
  limit, it only persists already-approved allocations).

  A storage lot is the minimal unit of physical custody: one batch of
  goods tracked from inbound receiving through storage, outbound
  staging, and dispatch (see coldchain.phase).

  The ledger (`:facts`) is a separate append-only vector of audit
  facts, kept alongside `:tenants`/`:lots` in the same store value."
  )

(defn tenant
  "Retrieve a tenant by id, or nil if not yet registered."
  [st tenant-id]
  (get-in st [:tenants tenant-id]))

(defn tenant-registered?
  [st tenant-id]
  (some? (tenant st tenant-id)))

(defn register-tenant
  "Register/update `tenant-data` under `tenant-id`."
  [st tenant-id tenant-data]
  (assoc-in st [:tenants tenant-id] tenant-data))

(defn storage-lot
  "Retrieve a storage lot by id, or nil if it does not exist."
  [st lot-id]
  (get-in st [:lots lot-id]))

(defn log-lot
  "Register/update `lot-data` under `lot-id`."
  [st lot-id lot-data]
  (assoc-in st [:lots lot-id] lot-data))

(defn audit-trail
  "Return the append-only audit ledger (empty vector if none yet)."
  [st]
  (get st :facts []))

(defn append-fact
  "Append `fact` to the store's audit ledger."
  [st fact]
  (update st :facts (fnil conj []) fact))

;; ───────────────────────── Equipment-Asset Registry ─────────────────────────
;;
;; Mirrors the tenant/lot pattern exactly above -- `coldchain.governor`'s
;; `:register-equipment-asset` double-registration guard reads a
;; `:equipment-asset/registered-ids` set from its governor-supplied
;; `registry` context (the governor itself is stateless/pure and never
;; queries a store). `coldchain.operation`'s actor is what supplies that
;; context, computed from THIS store's own registered ids -- see
;; `registered-equipment-asset-ids` below.

(defn equipment-asset
  "Retrieve a registered equipment asset by id, or nil if not yet
  registered."
  [st equipment-asset-id]
  (get-in st [:equipment-assets equipment-asset-id]))

(defn equipment-asset-registered?
  [st equipment-asset-id]
  (some? (equipment-asset st equipment-asset-id)))

(defn register-equipment-asset
  "Register/update `asset-data` under `equipment-asset-id`."
  [st equipment-asset-id asset-data]
  (assoc-in st [:equipment-assets equipment-asset-id] asset-data))

(defn registered-equipment-asset-ids
  "All currently-registered equipment-asset ids, as a set -- the exact
  shape `coldchain.governor/check`'s `registry` context expects under
  `:equipment-asset/registered-ids`."
  [st]
  (set (keys (get st :equipment-assets {}))))

(defn all-equipment-assets
  "Every registered equipment-asset record -- grounding source for
  `coldchain.advisor`'s `:reconcile-power-metering` proposals'
  `:equipment-assets` list (this actor's OWN registered assets, per
  `coldchain.governor`'s own docstring -- never fabricated, always read
  off this store)."
  [st]
  (vec (vals (get st :equipment-assets {}))))

;; ───────────────────────── MemStore (durable-across-runs) ─────────────────────────
;;
;; Everything above this point is pure (plain-map in, plain-map out) --
;; deliberately kept that way so `store_test.cljc`'s existing coverage
;; never needs a mutable fixture. `coldchain.operation`'s langgraph-clj
;; actor, though, needs state that survives across separate graph
;; `run*` invocations on separate thread-ids (e.g. a `:register-
;; equipment-asset` commit on one thread must be visible to the
;; double-registration guard on a LATER thread) -- a thin atom wrapper
;; around the SAME pure functions above, not a reimplementation of
;; their logic. Production should migrate this seam to Datomic/
;; kotoba-server (unchanged from this ns's original docstring) while
;; keeping the same read/write surface below.

(defrecord MemStore [a])

(defn mem-store
  "A fresh atom-backed MemStore, empty until proposals commit against
  it."
  [] (->MemStore (atom {})))

(defn snapshot
  "The current plain-map store value -- feed this to any of the pure
  functions above (`tenant`, `storage-lot`, `equipment-asset`,
  `registered-equipment-asset-ids`, `audit-trail`, ...)."
  [mem-store]
  @(:a mem-store))

(defn mem-register-tenant!
  [mem-store tenant-id tenant-data]
  (swap! (:a mem-store) register-tenant tenant-id tenant-data))

(defn mem-log-lot!
  [mem-store lot-id lot-data]
  (swap! (:a mem-store) log-lot lot-id lot-data))

(defn mem-register-equipment-asset!
  [mem-store equipment-asset-id asset-data]
  (swap! (:a mem-store) register-equipment-asset equipment-asset-id asset-data))

(defn mem-append-fact!
  [mem-store fact]
  (swap! (:a mem-store) append-fact fact)
  fact)
