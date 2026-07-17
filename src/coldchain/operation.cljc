(ns coldchain.operation
  "OperationActor -- the pure-function driver for a single proposal
  through coldchain.governor. Mirrors cloud-itonami-isic-1075's
  `mealops.operation`, adapted to coldchain.governor's
  `{:status :pass|:hold|:escalate :reasons|:escalations [...]}` verdict
  shape (the cloud-itonami security-governor/kernels convention this
  actor's governor is styled after, rather than mealops/terminal's
  older `{:ok? :hard? :escalate?}` shape). `:escalate` was added
  alongside `coldchain.governor`'s grid-outage-duration-mismatch soft
  signal -- see `hold-fact` below for why it is NOT just treated as a
  second spelling of `:hold`.")

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
