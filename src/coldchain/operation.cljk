(ns coldchain.operation
  "OperationActor -- one refrigerated-warehousing coordination request =
  one supervised actor run, expressed as a langgraph-clj StateGraph.
  ColdChainAdvisor is sealed into a single node (:advise); its
  proposal is ALWAYS routed through coldchain.governor (:govern) before
  anything commits to the store. Mirrors cloud-itonami-isic-0899's
  `quarryops.operation` / cloud-itonami-isic-2813's
  `pressureequip.operation` graph shape (:intake/:advise/:govern/
  :decide/:request-approval/:commit/:hold, `interrupt-before
  #{:request-approval}`), adapted to `coldchain.governor/check`'s own
  richer verdict shape (`{:status :pass|:hold|:escalate}`, see below)
  instead of those siblings' `{:ok? .. :hard? .. :escalate? ..}` shape.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (coldchain.store/MemStore; production seam is
                    Datomic/kotoba-server, see coldchain.store ns
                    docstring) - `mem-store` arg
    - the Advisor  (mock | real LLM)                                  - :advisor opt

  One graph run = one coordination request (intake -> advise -> govern
  -> decide -> commit | hold | approval). No unbounded inner loop --
  each operation is auditable and checkpointed.

  No separate rollout-phase gate here (unlike quarryops.phase/
  pressureequip.phase's 0->3 staged-auto-commit gate): this actor's own
  `coldchain.phase` is a DIFFERENT thing entirely (the physical
  storage-lot lifecycle :inbound->:storage->:outbound->:dispatched->
  :archived, see that ns), not an advisor-rollout maturity ladder, so
  it is not reused for that purpose here -- inventing a second,
  unrelated staged-rollout concept this governor has no notion of
  would be new domain policy this governor does not encode. Instead,
  `:decide` routes directly off `coldchain.governor/check`'s own
  three-way verdict, which is ALREADY the richer signal those siblings'
  phase layer exists to bolt on top of a binary hard/ok? governor:
    - :hold      -> straight to :hold, no human override (a hard
                    governor violation always wins, exactly like every
                    sibling's 'HARD governor violations -> HOLD').
    - :escalate  -> `:request-approval` (interrupt-before pauses here
                    for a human operator) -- this actor's own soft
                    signals (grid-outage-duration-mismatch,
                    equipment-asset-maintenance-notice, power-metering
                    deviation) are exactly 'whichever ops/stakes this
                    domain's governor treats as escalation-worthy'.
    - :pass      -> auto-commit.

  Human-in-the-loop = real approval workflow: `interrupt-before
  #{:request-approval}` pauses the actor and hands the decision to a
  human warehouse operator. The approver resumes with
  `{:approval {:status :approved}}` (or :rejected)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [coldchain.advisor :as advisor]
            [coldchain.governor :as governor]
            [coldchain.store :as store]))

(defn run-operation
  "Drive a single proposal through Governor validation.
  Returns {:ok? bool :facts [..] :verdict ..}."
  [registry proposal governor-check-fn hold-fact-fn]
  (let [verdict (governor-check-fn registry proposal)]
    (if (= :pass (:status verdict))
      {:ok? true
       :facts []}
      {:ok? false
       :facts [(hold-fact-fn registry proposal verdict)]
       :verdict verdict})))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD) or
  soft-escalated (ESCALATE). `:disposition` reflects the verdict's own
  `:status` rather than being hardcoded to `:hold` -- a soft escalation
  (e.g. `coldchain.governor`'s `:grid-outage-duration-mismatch`) must
  not be mislabeled as a hard hold in the audit trail, and a hold
  verdict has no `:escalations` key (an escalate verdict has no
  `:reasons` key) -- `(or ...)` picks up whichever one this verdict
  actually carries."
  [_registry proposal verdict]
  {:t :governor-hold
   :kind (:kind proposal)
   :disposition (:status verdict)
   :reasons (or (:reasons verdict) (:escalations verdict))})

;; ----------------------------- store effects -----------------------------

(defn- apply-effect!
  "Persist a committed proposal's `:effect` to `mem-store` -- the ONLY
  place this actor's store is mutated, and only ever reached after
  `coldchain.governor/check` has already returned `:pass` (or a human
  has approved an `:escalate`). `:effect` is an OPERATION-layer
  vocabulary `coldchain.advisor` defines (see that ns's own
  docstring) -- `coldchain.governor` never reads or validates it, so
  an unrecognized value is a no-op here rather than a governor
  concern: fails towards 'nothing persisted', same discipline as every
  optional check elsewhere in this actor."
  [mem-store {:keys [effect subject value]}]
  (case effect
    :tenant/register
    (store/mem-register-tenant! mem-store subject value)

    :capacity/allocate
    (store/mem-register-tenant!
     mem-store subject
     (merge (store/tenant (store/snapshot mem-store) subject) value))

    :lot/log-inbound
    (store/mem-log-lot! mem-store subject value)

    :lot/log-outbound
    (store/mem-log-lot! mem-store subject value)

    :lot/flag-excursion
    (store/mem-log-lot!
     mem-store subject
     (merge (store/storage-lot (store/snapshot mem-store) subject) value))

    :equipment-asset/register
    (store/mem-register-equipment-asset! mem-store subject value)

    ;; :power-metering/reconcile has nothing durable to persist beyond
    ;; the commit-fact already written by the :commit node below --
    ;; a reconciliation reading is a point-in-time cross-check, not a
    ;; store record this actor owns.
    nil))

;; ----------------------------- graph nodes -----------------------------

(defn- commit-fact [proposal]
  {:t :committed
   :kind (:kind proposal)
   :subject (:subject proposal)
   :disposition :commit
   :basis (:cites proposal)
   :summary (:summary proposal)})

(defn- approval-rejected-fact [proposal]
  {:t :approval-rejected
   :kind (:kind proposal)
   :subject (:subject proposal)
   :disposition :hold
   :reasons ["human reviewer rejected the escalated proposal"]})

(defn build
  "Compiles an OperationActor graph bound to `mem-store` (a
  `coldchain.store/MemStore`).
  opts:
    :advisor      -- a `coldchain.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [mem-store & [{:keys [advisor checkpointer]
                 :or   {advisor      (advisor/mock-advisor)
                        checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; ColdChainAdvisor inference (the contained intelligence node) -- proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [db (store/snapshot mem-store)
                p (advisor/-advise advisor db request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; coldchain.governor -- independent censor (separate system than the advisor).
      ;; `registry` is computed LIVE off the current store on every call, so the
      ;; equipment-asset double-registration guard sees anything committed by an
      ;; EARLIER run on this same mem-store (see coldchain.store ns docstring).
      (g/add-node :govern
        (fn [{:keys [proposal]}]
          (let [db (store/snapshot mem-store)
                registry {:equipment-asset/registered-ids (store/registered-equipment-asset-ids db)}]
            {:verdict (governor/check registry proposal)})))

      ;; Decide: coldchain.governor's own three-way verdict IS the disposition,
      ;; no extra phase-gate layer (see ns docstring).
      (g/add-node :decide
        (fn [{:keys [proposal verdict]}]
          (case (:status verdict)
            :hold
            {:disposition :hold :audit [(hold-fact {} proposal verdict)]}

            :escalate
            {:disposition :escalate
             :audit [{:t :approval-requested :kind (:kind proposal) :subject (:subject proposal)
                      :escalations (:escalations verdict)}]}

            :pass
            {:disposition :commit})))

      ;; Approval handoff -- paused by interrupt-before; a human warehouse
      ;; operator resumes with :approval. Then route commit/hold.
      (g/add-node :request-approval
        (fn [{:keys [proposal approval]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :audit [{:t :approval-granted :kind (:kind proposal)
                      :subject (:subject proposal) :by (:by approval)}]}
            {:disposition :hold
             :audit [(approval-rejected-fact proposal)]})))

      ;; Commit -- the ONLY node that writes the SSoT + audit ledger.
      (g/add-node :commit
        (fn [{:keys [proposal]}]
          (apply-effect! mem-store proposal)
          (let [f (commit-fact proposal)]
            (store/mem-append-fact! mem-store f)
            {:audit [f]})))

      ;; Hold -- write the rejection to the ledger; no store mutation.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/mem-append-fact! mem-store hf))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
