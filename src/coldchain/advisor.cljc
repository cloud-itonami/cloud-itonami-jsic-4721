(ns coldchain.advisor
  "ColdChainAdvisor -- the *contained intelligence node* for the
  refrigerated-warehousing (cold-chain 3PL, JSIC 4721) coordination
  actor. It drafts proposals for exactly the seven kinds in
  `coldchain.governor/allowed-kinds` (client-tenant onboarding,
  warehouse-capacity allocation, inbound/outbound shipment logging,
  temperature-excursion flagging, equipment-asset registration,
  power-metering reconciliation). CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal*, never a committed record --
  `coldchain.governor` independently validates every proposal (this
  actor's own capacity-concentration-limit invariant, cold-chain
  physical-discipline checks, cross-actor handoff/grid-outage/
  equipment-asset checks, plus the closed `:kind` allowlist) before
  anything touches the store via `coldchain.operation`.

  Grounding discipline (same as every sibling actor's advisor, e.g.
  cloud-itonami-isic-2813's `pressureequip.pressureequipadvisor` /
  cloud-itonami-isic-0899's `quarryops.advisor`): this advisor only
  echoes/normalizes the REQUEST's own fields (an operator's declared
  intent, or an upstream cross-actor reference the request carries
  in) -- it never fabricates a storage temperature, a power-outage
  duration, a capacity figure or an equipment-asset field. The one
  exception is `:reconcile-power-metering`'s `:equipment-assets` list,
  which this advisor deliberately does NOT take from the request at
  all: it reads this actor's OWN registered assets straight off the
  store (`coldchain.store/all-equipment-assets`), because
  `coldchain.governor`'s own docstring defines that list as 'the
  equipment-asset records this actor has registered' -- trusting a
  request's own claim about it would defeat the point of the
  cross-check.

  Proposal shape (all kinds) -- top-level keys are exactly the field
  names `coldchain.governor/check` reads (see that ns's docstring for
  the full per-kind vocabulary); this advisor adds only advisor-only
  metadata alongside them:
    {:kind       kw             ; echoes the request :kind
     :subject    str            ; tenant-id/lot-id/equipment-asset-id -- operation-layer only, the governor never reads this
     ...         ...            ; domain fields, exactly the governor's own vocabulary
     :summary    str            ; human-facing draft -- SCANNED nowhere in this actor (unlike quarryops' scope-exclusion scan -- this governor has no such gate), but kept for audit-trail parity with every sibling advisor
     :rationale  str            ; why -- for the audit trail
     :cites      [kw|str ..]    ; facts/sources this advisor used
     :effect     kw             ; how `coldchain.operation`'s :commit node mutates the store -- an OPERATION-layer vocabulary this advisor defines, NOT read or validated by `coldchain.governor` at all (that governor has no notion of :effect, unlike e.g. quarryops.governor's :effect-not-propose hard check)
     :value      map            ; the draft payload the :commit node persists
     :confidence 0..1}          ; informational only -- this governor has no confidence floor/escalate-on-low-confidence gate (unlike quarryops/pressureequip); kept for audit-trail parity and so an `:reconcile-power-metering`-style informational read of the SAME kernel math the governor will independently re-derive can be surfaced to a human

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [coldchain.facts :as facts]
            [coldchain.registry :as registry]
            [coldchain.store :as store]
            [coldchain.kernels.concentration-verdict :as cv]
            [coldchain.kernels.power-metering-verdict :as pmv]
            [langchain.model :as model]))

;; ----------------------------- proposal generators -----------------------------

(defn- draft-onboard
  "Draft a client-tenant onboarding proposal. `coldchain.governor` has
  no dedicated `:tenant/onboard` rule (falls through its `:default`
  multimethod -> always structurally clean on THIS actor's own
  invariants; the cloud-itonami-platform-layer bcp-precondition check
  for a physical-ops JSIC tenant is enforced elsewhere entirely, see
  `coldchain.governor` ns docstring's own 'Design boundary' section) --
  this advisor only normalizes the request's own patch, it does not
  invent tenant data. Lower confidence, informationally, when a tenant
  record already exists under this id (never blocks -- there is no
  governor rule for this either)."
  [db {:keys [subject patch]}]
  (let [already? (store/tenant-registered? db subject)]
    {:kind       :tenant/onboard
     :subject    subject
     :summary    (str subject " のクライアントテナント登録を提案: " (pr-str (keys patch)))
     :rationale  (if already?
                   "既存テナントレコードの更新提案 -- 新規事実の生成なし"
                   "入力された登録情報の正規化のみ -- 新規事実の生成なし")
     :cites      [subject]
     :effect     :tenant/register
     :value      (merge {:tenant-id subject} patch)
     :confidence (if already? 0.6 0.92)}))

(defn- draft-capacity-allocation
  "Draft a `:capacity/allocate` proposal. `:capacity/allocated-units`/
  `:capacity/total-units` are the request's own declared figures (an
  operator's allocation decision + this warehouse's known total
  capacity) -- this advisor never invents either number, it only
  computes an INFORMATIONAL ratio via the SAME kernel
  (`coldchain.kernels.concentration-verdict`) `coldchain.governor`
  independently re-derives, for the audit-trail rationale/confidence
  signal -- the governor's own check is authoritative regardless of
  what this advisor concludes."
  [_db {:keys [subject patch]}]
  (let [allocated (:capacity/allocated-units patch)
        total (:capacity/total-units patch)
        ratio (cv/concentration-ratio allocated total)
        over? (= 1 (cv/concentration-limit-exceeded? ratio cv/default-concentration-limit))]
    {:kind       :capacity/allocate
     :subject    subject
     :capacity/allocated-units allocated
     :capacity/total-units total
     :summary    (str subject " へ warehouse capacity " allocated "/" total " の割当を提案")
     :rationale  (if (some? ratio)
                   (str "concentration-ratio=" ratio " (limit=" cv/default-concentration-limit ")")
                   "allocated/total の少なくとも一方が不正または未提供 -- 比率計算不可")
     :cites      [subject]
     :effect     :capacity/allocate
     :value      {:capacity/allocated-units allocated :capacity/total-units total}
     :confidence (cond over? 0.35 (some? ratio) 0.9 :else 0.5)}))

(defn- lot-summary-fields
  "Common pass-through of the (optional) governor-recognized lot
  fields off a request's own patch -- echoed verbatim, never
  fabricated. Shared by both `:log-inbound-shipment` and
  `:log-outbound-shipment` drafting below since `coldchain.governor`
  reads the identical field vocabulary for both (`lot-physical-
  violations`/`grid-outage-duration-mismatch-escalations`/
  `equipment-asset-maintenance-notice-escalations` are all shared
  across the two kinds)."
  [patch]
  (select-keys patch [:lot/commodity-class :lot/storage-temp-c :lot/power-outage-minutes
                       :lot/quantity-kg :capacity/total-units :handoff
                       :grid-outage/source-actor :grid-outage/event-id :grid-outage/duration-minutes
                       :maintenance-notice]))

(defn- lot-confidence
  "Informational confidence signal for a lot-logging proposal --
  reuses `coldchain.registry`'s OWN predicates (the same ones
  `coldchain.governor` independently re-runs), same discipline as
  cloud-itonami-isic-2813's `propose-unit-dispatch` citing `registry/
  unit-test-pressure-out-of-range?` for its own confidence. Does not
  affect governor's decision either way -- purely for the audit
  trail."
  [patch]
  (let [commodity (facts/commodity-class-by-id (:lot/commodity-class patch))
        temp (:lot/storage-temp-c patch)
        outage (:lot/power-outage-minutes patch)]
    (cond
      (nil? commodity) 0.6
      (and temp (registry/storage-temp-out-of-range?
                 temp (:storage-temp-min-c commodity) (:storage-temp-max-c commodity))) 0.3
      (and outage (registry/power-outage-exceeds-max?
                   outage (:max-power-outage-minutes commodity))) 0.3
      :else 0.9)))

(defn- draft-inbound-shipment
  [_db {:keys [subject patch]}]
  (let [fields (lot-summary-fields patch)]
    (merge
     {:kind :log-inbound-shipment :subject subject}
     fields
     {:summary   (str subject " の入庫ロット記録を提案 (commodity=" (:lot/commodity-class patch) ")")
      :rationale "入力された入庫実績データ(保管温度/停電時間/ハンドオフ参照)の記録提案のみ -- 新規事実の生成なし"
      :cites     [subject]
      :effect    :lot/log-inbound
      :value     (assoc fields :lot/phase :inbound)
      :confidence (lot-confidence patch)})))

(defn- draft-outbound-shipment
  [_db {:keys [subject patch]}]
  (let [fields (lot-summary-fields patch)]
    (merge
     {:kind :log-outbound-shipment :subject subject}
     fields
     {:summary   (str subject " の出庫ロット記録を提案 (commodity=" (:lot/commodity-class patch) ")")
      :rationale "入力された出庫実績データ(保管温度/停電時間/ハンドオフ参照)の記録提案のみ -- 新規事実の生成なし"
      :cites     [subject]
      :effect    :lot/log-outbound
      :value     (assoc fields :lot/phase :outbound)
      :confidence (lot-confidence patch)})))

(defn- draft-temperature-excursion-flag
  "Surface a temperature-excursion observation for human triage.
  `coldchain.governor` has no dedicated rule for this kind either (see
  `draft-onboard` above) -- this advisor's job is only to package the
  observed reading, never to judge whether it is actually a cold-chain
  break (that determination -- like every sibling actor's `:flag-*`
  op, e.g. quarryops' `:flag-environmental-concern` -- belongs to a
  human, not this advisor). Reads the already-logged lot record from
  the store when the request omits the commodity-class (grounded
  fallback, never fabricated), so a bare 'lot-N is running hot' report
  still carries its actual assigned commodity class into the audit
  trail when this actor already knows it."
  [db {:keys [subject patch]}]
  (let [existing (store/storage-lot db subject)
        commodity-class (or (:lot/commodity-class patch) (:lot/commodity-class existing))]
    {:kind       :flag-temperature-excursion
     :subject    subject
     :lot/storage-temp-c (:lot/storage-temp-c patch)
     :lot/commodity-class commodity-class
     :summary    (str subject " の温度逸脱を報告: " (:lot/storage-temp-c patch) "℃")
     :rationale  "観測された温度逸脱事象の報告のみ -- 冷蔵チェーン破断の判定は行わない、常に人間確認が必要"
     :cites      (cond-> [subject] existing (conj :store-lot-record))
     :effect     :lot/flag-excursion
     :value      {:lot/flagged-temp-c (:lot/storage-temp-c patch)
                  :lot/flagged-at-iso (:flagged-at-iso patch)}
     :confidence 0.85}))

(defn- draft-equipment-asset-registration
  "Draft a `:register-equipment-asset` proposal -- registers WHICH
  manufactured unit (e.g. a cloud-itonami-isic-2813 industrial-
  refrigeration-compressor) this warehouse actually operates. Mirrors
  cloud-itonami-isic-2813's own `propose-register-equipment-asset`
  almost field-for-field (the superproject `:equipment-asset` shared
  shape, no shared code): `subject` doubles as the new asset's
  `:equipment-asset/id`, and this advisor only echoes/normalizes the
  request's own `:equipment-asset/*` fields -- it never invents a
  source-actor, dispatch-ref or unit-type-id. `coldchain.governor`
  INDEPENDENTLY re-verifies required-field presence and the
  double-registration guard (via the operation actor's live
  `registry` context) before anything commits -- this advisor's own
  `already?` read is informational only."
  [db {:keys [subject patch]}]
  (let [ea (-> (select-keys patch [:equipment-asset/unit-type-id
                                   :equipment-asset/source-actor
                                   :equipment-asset/dispatch-ref
                                   :equipment-asset/installed-at-iso])
               (assoc :equipment-asset/id subject))
        present? (every? some? ((juxt :equipment-asset/id :equipment-asset/unit-type-id
                                      :equipment-asset/source-actor :equipment-asset/dispatch-ref)
                                ea))
        already? (store/equipment-asset-registered? db subject)]
    (merge
     {:kind :register-equipment-asset :subject subject}
     ea
     {:summary    (str subject " 設備資産登録を提案"
                       (when-let [src (:equipment-asset/source-actor ea)] (str " (source=" src ")")))
      :rationale  (cond
                    already? (str subject " は既に登録済み -- 二重登録の可能性")
                    present? (str "供給元 " (:equipment-asset/source-actor ea) " のdispatch-ref "
                                  (:equipment-asset/dispatch-ref ea) " を参照して資産登録")
                    :else "必須フィールド(:equipment-asset/id・:unit-type-id・:source-actor・:dispatch-ref)が不足")
      :cites      (if present? [subject (:equipment-asset/source-actor ea) (:equipment-asset/dispatch-ref ea)] [])
      :effect     :equipment-asset/register
      :value      ea
      :confidence (cond (not present?) 0.3 already? 0.4 :else 0.9)})))

(defn- draft-power-metering-reconciliation
  "Draft a `:reconcile-power-metering` proposal. `:power-metering/*`
  fields (id/feeder-ref/client-actor/period/consumed-kwh) are the
  request's own echo of an upstream distribution-utility actor's
  (e.g. cloud-itonami-isic-3510) already-logged reading -- this
  advisor does not invent them. `:equipment-assets`, deliberately,
  is NOT taken from the request at all (see ns docstring): it is
  read straight off this actor's OWN registered equipment assets
  (`coldchain.store/all-equipment-assets`), the exact 'this actor has
  registered' set `coldchain.governor`'s `power-metering-deviation-
  escalations` expects. This advisor also computes the SAME expected-
  consumption figure the governor will independently re-derive
  (`coldchain.kernels.power-metering-verdict/expected-consumption-
  kwh`), purely for an informational rationale/confidence signal."
  [db {:keys [subject patch]}]
  (let [assets (store/all-equipment-assets db)
        start (:power-metering/period-start-iso patch)
        end (:power-metering/period-end-iso patch)
        consumed (:power-metering/consumed-kwh patch)
        expected (when (seq assets)
                   (pmv/expected-consumption-kwh assets facts/unit-type-power-kw-of start end))
        ratio (when expected (pmv/deviation-ratio consumed expected))
        deviates? (= 1 (pmv/deviation-exceeds-threshold? ratio pmv/default-deviation-threshold))]
    {:kind :reconcile-power-metering
     :subject subject
     :power-metering/id (:power-metering/id patch)
     :power-metering/feeder-ref (:power-metering/feeder-ref patch)
     :power-metering/client-actor (:power-metering/client-actor patch)
     :power-metering/period-start-iso start
     :power-metering/period-end-iso end
     :power-metering/consumed-kwh consumed
     :equipment-assets assets
     :summary    (str subject " の電力量突合を提案 (consumed=" consumed "kWh, registered-assets="
                      (count assets) ")")
     :rationale  (if expected
                   (str "expected=" expected "kWh (自機登録設備 x 稼働時間から算出) を参照")
                   "自機に登録済み設備資産なし -- 期待消費量を算出不可")
     :cites      (cond-> [subject] (seq assets) (conj :registered-equipment-assets))
     :effect     :power-metering/reconcile
     :value      {}
     :confidence (cond deviates? 0.4 expected 0.9 :else 0.6)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:kind kw :subject id :patch map ...}"
  [db {:keys [kind] :as request}]
  (case kind
    :tenant/onboard              (draft-onboard db request)
    :capacity/allocate           (draft-capacity-allocation db request)
    :log-inbound-shipment        (draft-inbound-shipment db request)
    :log-outbound-shipment       (draft-outbound-shipment db request)
    :flag-temperature-excursion  (draft-temperature-excursion-flag db request)
    :register-equipment-asset    (draft-equipment-asset-registration db request)
    :reconcile-power-metering    (draft-power-metering-reconciliation db request)
    {:kind kind :subject (:subject request)
     :summary "未対応の操作 (closed allowlist に無い)" :rationale (str kind)
     :cites [] :effect :noop :value {} :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは冷蔵倉庫業(JSIC 4721、コールドチェーン3PL)の運営コーディネーション"
       "助言者です。与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "許可された :kind は :tenant/onboard / :capacity/allocate / "
       ":log-inbound-shipment / :log-outbound-shipment / "
       ":flag-temperature-excursion / :register-equipment-asset / "
       ":reconcile-power-metering の7つのみです。\n"
       "キー: :kind :subject :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect :value :confidence(0..1)。\n"
       "重要: 保管温度・停電時間・容量数値・設備資産フィールドを絶対に創作しては"
       "いけません。与えられた事実に無い数値は空にすること。"))

(defn- facts-for [st {:keys [kind subject]}]
  (case kind
    :tenant/onboard              {:tenant (store/tenant st subject)}
    :log-inbound-shipment        {:lot (store/storage-lot st subject)}
    :log-outbound-shipment       {:lot (store/storage-lot st subject)}
    :flag-temperature-excursion  {:lot (store/storage-lot st subject)}
    :register-equipment-asset    {:equipment-asset (store/equipment-asset st subject)}
    :reconcile-power-metering    {:equipment-assets (store/all-equipment-assets st)}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `coldchain.governor` holds it
  on the closed-allowlist basis -- an LLM hiccup can never bypass
  governance."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:kind :noop :summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :value {} :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "kind: " (:kind req)
                                              "\nsubject: " (:subject req)
                                              "\npatch: " (pr-str (:patch req))
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :kind       (:kind request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
