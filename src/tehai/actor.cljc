(ns tehai.actor
  "TehaiActor — the tehai (手配) professional-services actor as a
  `langgraph.graph/state-graph` (ADR-2607011000 / CLAUDE.md Actors
  section). One graph run = one PSA operation request
  (intake → advise → govern → decide → commit/hold, with a
  human-approval interrupt for escalated proposals). No infinite internal
  loop; checkpointed per superstep so an interrupted run can resume after
  human sign-off. Modeled on cloud-itonami-isco-4313's payroll.actor.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                            +-> :request-approval  (:escalate? true, interrupt-before)
                                            +-> :hold              (:hard? true)
  ```

  The unconditional invariant: the TehaiAdvisor can never directly
  commit what the TehaiGovernor refuses. `:issue-invoice` always
  escalates, so no invoice reaches a client without a human resuming the
  thread — and committing one adds its entry keys to the billed set,
  which is what makes the next attempt to bill the same hours a hard
  hold rather than an awkward conversation."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [tehai.advisor :as advisor]
            [tehai.governor :as governor]
            [tehai.store :as store]))

(defn build-graph
  "Build a compiled TehaiActor graph. `store` implements
  `tehai.store/Store`. `advisor` implements `tehai.advisor/Advisor`
  (defaults to `mock-advisor`). `checkpointer` defaults to an in-memory
  one."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :audit       {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))
      (g/add-node :advise
                  (fn [{:keys [request]}]
                    (let [p (advisor/-advise advisor store request)]
                      {:proposal p
                       :audit [{:node :advise :request request :proposal p}]})))
      (g/add-node :govern
                  (fn [{:keys [request context proposal]}]
                    (let [v (governor/check request context proposal store)]
                      {:verdict v
                       :audit [{:node :govern :verdict v}]})))
      (g/add-node :decide
                  (fn [{:keys [verdict]}]
                    {:disposition (cond
                                    (:hard? verdict) :hold
                                    (:escalate? verdict) :request-approval
                                    :else :commit)}))
      (g/add-node :request-approval (fn [s] s))
      (g/add-node :commit
                  (fn [{:keys [request proposal]}]
                    (let [record {:client-id (:client-id request)
                                  :op (:op proposal)
                                  :project (:project proposal)
                                  :payload proposal}]
                      ;; Issuing is the one op that mutates the billed
                      ;; set. Drafting deliberately does not: a draft
                      ;; that is never issued must not lock its hours
                      ;; away from a later, correct invoice.
                      (when (= :issue-invoice (:op proposal))
                        (store/commit-invoice!
                         store
                         {:invoice/id (:invoice-id request)
                          :invoice/project (:project proposal)
                          :invoice/lines (:lines proposal)
                          :invoice/total (:total proposal)
                          :invoice/entries (:entry-keys proposal)}))
                      (when (= :assign-person (:op proposal))
                        (store/commit-assignment! store (:assignment proposal)))
                      (store/commit-record! store record)
                      (store/append-ledger! store {:disposition :commit :record record})
                      {:record record
                       :audit [{:node :commit :record record}]})))
      (g/add-node :hold
                  (fn [{:keys [verdict]}]
                    (store/append-ledger! store {:disposition :hold :verdict verdict})
                    {:audit [{:node :hold :verdict verdict}]}))
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :request-approval
           :hold)))
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                        :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one operation request to completion or interrupt. `thread-id`
  scopes checkpointing for resume after human approval."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node
  advances straight to `:commit` on resume (approval is the act of
  resuming the thread)."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))
