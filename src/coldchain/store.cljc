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
