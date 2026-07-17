(ns coldchain.operation
  "OperationActor -- the pure-function driver for a single proposal
  through coldchain.governor. Mirrors cloud-itonami-isic-1075's
  `mealops.operation`, adapted to coldchain.governor's
  `{:status :pass|:hold :reasons [...]}` verdict shape (the
  cloud-itonami security-governor/kernels convention this actor's
  governor is styled after, rather than mealops/terminal's older
  `{:ok? :hard? :escalate?}` shape).")

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
  "The audit fact written when a proposal is rejected (HOLD)."
  [_registry proposal verdict]
  {:t :governor-hold
   :kind (:kind proposal)
   :disposition :hold
   :reasons (:reasons verdict)})
