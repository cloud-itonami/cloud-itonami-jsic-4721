(ns coldchain.advisor
  "ColdChainAdvisor -- the LLM/decision-maker that proposes
  refrigerated-warehousing operations (client-tenant onboarding,
  warehouse-capacity allocation, inbound/outbound shipment logging,
  temperature-excursion flags).

  The advisor operates purely at the proposal level; coldchain.governor
  independently validates all proposals (this actor's own
  capacity-concentration-limit invariant, plus the closed proposal-kind
  allowlist) before any action is committed.

  In production, this is driven by langgraph-clj StateGraph with LLM
  chat turns. For this blueprint, it's a skeleton.")

;; In production deployment, this module provides the StateGraph state
;; machine definition and LLM binding. For this blueprint, it's a skeleton.
