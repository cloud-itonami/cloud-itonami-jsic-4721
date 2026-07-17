(ns coldchain.facts
  "Reference facts for refrigerated warehousing (JSIC 4721, 冷蔵倉庫業):
  commodity storage-temperature classes this actor's Governor uses to
  independently verify a stored lot's cold-chain discipline. Pure
  lookup data, no I/O -- same shape/discipline as
  cloud-itonami-isic-1075's `mealops.facts`.

  Deliberately narrow -- this is a `:maturity :blueprint` actor (see
  blueprint.edn), matched in scope to cloud-itonami-isic-1075, not the
  full HACCP-style depth of that sibling's own facts registry."
  )

(def commodity-classes
  "Refrigerated-warehousing storage classes and their safe
  storage-temperature windows (日本冷蔵倉庫協会 / Japan Association of
  Refrigerated Warehouses reference bands: F4 (-20C以下, deep-frozen)
  down through C3 (10C~2C, chilled)). `max-power-outage-minutes` is
  this actor's own reference for how long a reefer/compressor outage
  can run before the commodity class's safety margin is considered
  exhausted."
  {:coldchain/f4-deep-frozen
   {:id :coldchain/f4-deep-frozen
    :name "F4級 (-20℃以下、深冷凍)"
    :storage-temp-min-c -30.0
    :storage-temp-max-c -20.0
    :max-power-outage-minutes 120}

   :coldchain/f3-frozen
   {:id :coldchain/f3-frozen
    :name "F3級 (-20℃~-10℃、冷凍)"
    :storage-temp-min-c -20.0
    :storage-temp-max-c -10.0
    :max-power-outage-minutes 90}

   :coldchain/c3-chilled
   {:id :coldchain/c3-chilled
    :name "C3級 (10℃~2℃、チルド)"
    :storage-temp-min-c 2.0
    :storage-temp-max-c 10.0
    :max-power-outage-minutes 60}})

(defn commodity-class-by-id [id]
  (get commodity-classes id))

(def jurisdictions
  "Warehousing-business-license jurisdictions this actor recognizes."
  {:jp/mlit
   {:id :jp/mlit
    :name "日本 (倉庫業法 - 国土交通省; JSIC 4721 冷蔵倉庫業)"
    :required-evidence [:warehouse-business-license :cold-storage-facility-inspection]}})

(defn jurisdiction-by-id [id]
  (get jurisdictions id))
