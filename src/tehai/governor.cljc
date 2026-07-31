(ns tehai.governor
  "TehaiGovernor — the independent safety/traceability layer for the
  tehai (手配) professional-services actor (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4313's payroll.governor, with the PSA-specific
  twist that the governor REDRAFTS the invoice deterministically via
  `kotoba.psa` — the advisor's arithmetic is never trusted, and neither
  is its optimism.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance   — the request's client must be registered.
    2. no-actuation        — proposal :effect must be :propose.
    3. project provenance  — a cited project must be registered AND
                             belong to this client. Billing one client
                             for another's project is the failure mode
                             that ends an engagement.
    4. unpriced time       — an invoice may not include time whose
                             [project role] has no rate card. Not billed
                             at zero, not billed at a default: held.
    5. unapproved time     — an invoice may not include entries without
                             `:ts/approved?`. Approval is what a human
                             already did; the model does not get to
                             supply it.
    6. double billing      — an invoice may not include an entry key
                             already on a committed invoice.
    7. total integrity     — the proposal's :total must EQUAL the total
                             of the invoice redrafted from the ledger's
                             own entries and rate cards.
    8. fabricated margin   — a proposal may not state a numeric margin
                             where `kotoba.psa` says :unknown. Missing
                             cost data reads as profit, so a number
                             invented here is a number that looks like
                             good news.
    9a. incomplete total   — an invoice may not be ISSUED when one of its
                             components could not be converted into the
                             project's billing currency. The total would
                             be wrong by an unknown factor, and approving
                             it means signing a number nobody can check —
                             so there is no approval route, the same way
                             there is none around an unpriced rate.
                             Recording the underlying expense is NOT
                             gated on this: the cost was incurred either
                             way, and refusing to record it would erase
                             it. The gate belongs on the invoice.
   9. over-allocation     — an assignment may not push a person past
                             declared capacity. Undeclared capacity is
                             NOT a violation (there is nothing to
                             exceed), which is why 9 and 4 differ:
                             absent capacity is unknown, absent price
                             is a refusal to guess.
  ESCALATION invariants (:escalate? true, human sign-off):
   10. :op :issue-invoice  — money leaves the system.
   11. low confidence (< `confidence-floor`)."
  (:require [kotoba.psa :as psa]
            [tehai.store :as store]))

(def confidence-floor 0.6)
(def ^:private escalating-ops #{:issue-invoice})

(defn- redraft
  "Redraft the proposal's invoice from what the store actually holds."
  [store project entry-keys]
  (let [wanted (set entry-keys)
        mine (filter #(contains? wanted (psa/entry-key %)) (store/entries store))]
    (psa/invoice :redraft project mine (store/rate-cards store) (store/billed-keys store))))

(defn- invoice-currency
  "The currency an invoice is denominated in: the project's rate cards
  agree on one, or nil when they do not."
  [store project]
  (let [cs (into #{} (comp (filter #(= project (:rate/project %))) (map :rate/currency))
                 (store/rate-cards store))]
    (when (= 1 (count cs)) (first cs))))

(defn total-completeness
  "Can every component of this invoice be expressed in one currency?

  Billable expenses and subcontractor lines may be incurred in a currency
  other than the one the project bills in. `kotoba.psa/total-in` converts
  what it can and NAMES what it cannot; this asks whether anything was
  left out.

  Returns `{:complete? bool :unconvertible [...] :currency c}`."
  [store project]
  (let [ccy (invoice-currency store project)
        items (concat
               (for [e (store/expenses store)
                     :when (and (= project (:expense/project e)) (:expense/billable? e))]
                 {:amount (psa/expense-billable-amount e) :currency (:expense/currency e)
                  :source [:expense (:expense/id e)]})
               (for [x (store/subcontracts store) :when (= project (:sub/project x))]
                 {:amount (:sub/billable (psa/subcontract-margin x)) :currency (:sub/currency x)
                  :source [:subcontract (:sub/id x)]}))]
    (if (nil? ccy)
      {:complete? false :unconvertible [] :currency nil :reason :ambiguous-invoice-currency}
      (let [t (psa/total-in (store/fx-rates store) items ccy)]
        {:complete? (:total/complete? t)
         :unconvertible (mapv :source (:total/unconvertible t))
         :currency ccy}))))

(defn- hard-violations [request proposal store]
  (let [{:keys [op effect project entry-keys total margin assignment]} proposal
        client-id (:client-id request)
        client-record (store/client store client-id)
        project-record (some->> project (store/project-of store))
        invoicing? (contains? #{:draft-invoice :issue-invoice} op)
        cited (when invoicing?
                (let [wanted (set entry-keys)]
                  (filter #(contains? wanted (psa/entry-key %)) (store/entries store))))
        unpriced (when invoicing?
                   (remove #(psa/billable? (store/rate-cards store) %) cited))
        unapproved (when invoicing? (remove :ts/approved? cited))
        already (when invoicing?
                  (filter #(contains? (store/billed-keys store) (psa/entry-key %)) cited))
        draft (when (and invoicing? project) (redraft store project entry-keys))
        recomputed-margin (when invoicing?
                            (psa/margin (map #(psa/price-entry (store/rate-cards store) %) cited)))
        alloc (when (= :assign-person op)
                (psa/allocation (conj (vec (store/assignments store)) assignment)
                                (store/capacities store)
                                (:assign/person assignment)
                                [(:assign/from assignment) (:assign/to assignment)]))]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose effect)
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and project (nil? project-record))
      (conj {:rule :unknown-project :detail (str "未登録 project: " project)})

      (and project-record (not= (:project/client project-record) client-id))
      (conj {:rule :project-wrong-client
             :detail (str "project " project " は client " (:project/client project-record)
                          " のもの（" client-id " ではない）")})

      (seq unpriced)
      (conj {:rule :unpriced-time
             :detail (str (count unpriced) " 件が rate card 無し。0 でも既定単価でもなく hold")})

      (seq unapproved)
      (conj {:rule :unapproved-time
             :detail (str (count unapproved) " 件が :ts/approved? 無し（承認は人がするもの）")})

      (seq already)
      (conj {:rule :double-billing
             :detail (str (count already) " 件が既に committed invoice に載っている")})

      (and draft (some? total) (not= total (:invoice/total draft)))
      (conj {:rule :total-mismatch
             :detail (str "total " total " ≠ 台帳から再作成した invoice の "
                          (:invoice/total draft))})

      (and recomputed-margin (number? margin)
           (= :unknown (:margin/amount recomputed-margin)))
      (conj {:rule :fabricated-margin
             :detail (str "margin " margin " を主張しているが、cost rate 欠落 "
                          (:margin/uncosted-count recomputed-margin) " 件で :unknown")})

      ;; ---- an incomplete total may not be issued ----
      ;;
      ;; A total that could not price one of its components is wrong by
      ;; an unknown factor. Approving it means signing a number nobody
      ;; can check, so this is a HOLD and not an escalation: there is no
      ;; approval that makes an unconverted currency converted.
      (and (= :issue-invoice op) project
           (not (:complete? (total-completeness store project))))
      (conj (let [c (total-completeness store project)]
              {:rule :incomplete-total
               :detail (if (= :ambiguous-invoice-currency (:reason c))
                         "project の rate card が単一通貨に定まらない"
                         (str "換算できない component: " (pr-str (:unconvertible c))
                              "（未知の係数だけ間違った金額に署名させることになる）"))}))

      ;; ---- an expense or subcontract belongs to a real project ----
      (and (contains? #{:record-expense :record-subcontract} op)
           (nil? project-record))
      (conj {:rule :unknown-project
             :detail "費用の付け先 project が未登録"})

      (and alloc (:allocation/over? alloc))
      (conj {:rule :over-allocation
             :detail (str (:allocation/person alloc) " は "
                          (:allocation/committed-hours alloc) "h 割当だが capacity は "
                          (:allocation/capacity-hours alloc) "h")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `tehai.store/Store`. Pure — never mutates the store.
  Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request _context proposal store]
  (let [hard      (hard-violations request proposal store)
        hard?     (boolean (seq hard))
        conf      (or (:confidence proposal) 0.0)
        low?      (< conf confidence-floor)
        risky-op? (contains? escalating-ops (:op proposal))]
    {:ok?        (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard?      hard?
     :escalate?  (and (not hard?) (or low? risky-op?))}))
