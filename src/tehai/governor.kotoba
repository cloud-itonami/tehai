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
   11. low confidence (< `confidence-floor`).

  ## Issuing outside Japan — what this actor can and cannot say

  `kotoba.taxlaw` catalogues `[:jp]`, `[:eu]` and `[:us]`, and coverage is
  per FACET, not per jurisdiction. Being in the catalog says something was
  read about somewhere; it says nothing about the facet being asked after.
  So the three answers are three different shapes, and this actor states
  each one rather than flattening them into `creditable` / `not`.

  ### `[:eu]` — CAN say: a VAT identification number is required, and this
  one is the wrong shape

  Directive 2006/112/EC Article 226 is a CLOSED list — \"only the following
  details are required\" — and item (3) is the supplier's VAT identification
  number. That is a real Union-level analogue of the 適格請求書 registration
  number, so an EU invoice with no number at all, or with one that is not
  even shaped like one, is `:invoice-not-creditable` and HELD.

  ### `[:eu]` — CANNOT say: that a VAT number is valid

  Article 215 gives the number's format as an ISO 3166 alpha-2 prefix (with
  `EL` for Greece) **and nothing else**. The body is Member State law the
  catalog has not read. So the check is a prefix check: **`\"XX1\"` passes.**
  `XX` is not a Member State, `1` is not a body, and there is no check digit
  — and taxlaw says so, naming `:member-state-is-a-member`, `:body-format`
  and `:check-digit` as NOT checked.

  A verdict that reported only `:taxlaw/supported? true` would be read as
  \"the VAT number is valid\", which is more than was measured. So the
  verdict carries `:tax-registration-unchecked` — the set taxlaw declined
  to check — whenever a pass rests on a partial check. A console that shows
  an approval must show that set beside it.

  This is a real widening caused by the 2026-08-18 pin bump and it is worth
  naming: before it, EVERY EU invoice was held as an uncatalogued
  jurisdiction. Now one with a prefix-shaped number is approved. That is
  correct — the Directive is what it is — but the approval is narrower than
  it looks, and `:tax-registration-unchecked` is where the narrowness lives.

  ### `[:us]` — CANNOT say anything about creditability, and says so

  There is no federal VAT or GST, so there is no federal analogue of a
  qualified invoice. taxlaw marks the facet `:out-of-scope` with that
  reason and still answers `:taxlaw/coverage :none`, so this actor holds a
  US invoice with `:unchecked-invoice-jurisdiction` **exactly as hard as it
  did before the United States was catalogued at all**. Adding a
  jurisdiction must not widen a pass, and this is the case that would have.

  What changed is only that the refusal can now be explained: the violation
  carries `:out-of-scope` and `:why`, so an operator reads \"there is no
  federal VAT\" rather than \"nobody has catalogued this\" — which would
  have been false.

  ### Retention: nil is the answer, not a gap — and not this actor's

  `taxlaw/retention-years` is nil for both `[:eu]` and `[:us]`, and in both
  cases that IS the instrument's answer. Article 247(1) says \"Each Member
  State shall determine the period\"; 26 CFR § 1.6001-1(e) says records are
  kept \"so long as the contents thereof may become material\" and states no
  number at all — the widely-repeated seven years appears nowhere in it.

  This actor deliberately surfaces **no** retention period. The jurisdiction
  it holds is the CLIENT's, because the client is who would claim the
  credit; how long the ISSUER must keep its own copy is the issuer's law,
  and answering one with the other would be worse than silence. What the
  suite does pin is that nobody later turns those nils into integers."
  (:require [kotoba.psa :as psa]
            [tehai.store :as store]
            [kotoba.taxlaw :as taxlaw]
            [governor.core :as gov]))

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
        ;; インボイス制度, issuing side. 4311 checks the RECEIVING side (may
        ;; this entry claim 仕入税額控除); this is the other half: is the
        ;; invoice we are about to send something its recipient could
        ;; credit at all? The jurisdiction that decides is the CLIENT's,
        ;; because the client is who would claim the credit.
        tax (when (and invoicing? (:client/jurisdiction client-record))
              (taxlaw/credit-support
               (:client/jurisdiction client-record)
               {:registration-number (:issuer-registration-number proposal)}))
        alloc (when (= :assign-person op)
                (psa/allocation (conj (vec (store/assignments store)) assignment)
                                (store/capacities store)
                                (:assign/person assignment)
                                [(:assign/from assignment) (:assign/to assignment)]))]
    (gov/violations
     ;; --- the fleet's four, from kotoba-lang/governor --------------------
     (gov/missing-subject client-record {:detail "未登録 client"})
     (gov/no-actuation {:effect effect}
                       {:detail "effect は :propose のみ許可（直接書込禁止）"})
     (gov/unknown-scope project-record
                        {:applies? (boolean project)
                         :rule :unknown-project
                         :detail (str "未登録 project: " project)})
     ;; a psa project carries ownership as :project/client, the request as
     ;; :client-id — the shape that made governor grow :scope-key.
     (gov/scope-owner-mismatch project-record {:client-id client-id}
                               {:owner-key :client-id
                                :scope-key :project/client
                                :rule :project-wrong-client
                                :detail (str "project " project " は client "
                                             (:project/client project-record)
                                             " のもの（" client-id " ではない）")})

     ;; --- tehai's own -----------------------------------------------------
     (cond-> []
      ;; a jurisdiction the client asserted and nobody catalogued is an
      ;; unanswered question, not a pass — the same rule kintai applies to
      ;; statutes and 4311 to credit claims.
      ;; The reason divides in two and the DETAIL must not merge them, even
      ;; though the rule and the disposition deliberately do. A jurisdiction
      ;; nobody catalogued and a jurisdiction whose invoice facet was
      ;; considered and left out are both `:none` and both HELD — taxlaw
      ;; keeps them the same value on purpose, so no consumer's behaviour
      ;; changes when a jurisdiction is added. But "nobody has read this"
      ;; and "there is no federal VAT to read" call for different work, and
      ;; an operator told the first about the United States has been told
      ;; something false.
      (= :none (:taxlaw/coverage tax))
      (conj (cond-> {:rule :unchecked-invoice-jurisdiction
                     :detail
                     (if-let [why (:taxlaw/why tax)]
                       (str "client の法域 " (pr-str (:client/jurisdiction client-record))
                            " について kotoba.taxlaw は仕入税額控除の facet を意図的に"
                            "持たない（" why "）。この請求書が受領側で控除可能か判定"
                            "できないことは変わらず、hold のまま")
                       (str "client の法域 " (pr-str (:client/jurisdiction client-record))
                            " は kotoba.taxlaw に無く、この請求書が受領側で控除可能か"
                            "判定できない（未検査は合格ではない）"))}
              (:taxlaw/out-of-scope tax)
              (assoc :out-of-scope (:taxlaw/out-of-scope tax)
                     :why (:taxlaw/why tax))))

      (and (= :checked (:taxlaw/coverage tax))
           (false? (:taxlaw/supported? tax)))
      (conj {:rule :invoice-not-creditable
             :detail (str "適格請求書発行事業者の登録番号が無いか不正（"
                          (name (:taxlaw/reason tax)) "）。この請求書は受領側で"
                          "仕入税額控除に使えない: "
                          (pr-str (:issuer-registration-number proposal)))})

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
                          (:allocation/capacity-hours alloc) "h")})))))

(defn registration-unchecked
  "What a `true` from this tax answer did NOT establish, or nil.

  `credit-support` returns `:taxlaw/supported? true` when the registration
  number satisfies everything the catalog can check **in that
  jurisdiction**, and in the EU that is the ISO 3166 alpha-2 prefix and
  nothing else — Article 215 gives the prefix, and the body is Member State
  law taxlaw has not read. `\"XX1\"` therefore passes. Reporting that as
  \"the VAT number is valid\" claims three things nobody measured.

  So: the set taxlaw declared unchecked, whenever the answer was a PASS
  that rests on a partial check. nil otherwise, and the two nils mean
  different things:

    a refusal          nothing was passed, so there is nothing to qualify
    `[:jp]`            the catalog declares no breakdown at all. That is
                       NOT a claim that the check was complete — it is the
                       absence of a claim either way, and it is reported as
                       today's shape rather than as an empty set, because
                       an empty set reads as `nothing was left out`.

  A non-empty set on an approved invoice is the thing a console must show
  next to the approval."
  [tax]
  (when (true? (:taxlaw/supported? tax))
    (not-empty (:not-checked (:taxlaw/registration-format tax)))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `tehai.store/Store`. Pure — never mutates the store.
  Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`,
  plus `:tax` and `:tax-registration-unchecked` on invoicing ops (see the
  namespace docstring's non-Japan section)."
  [request _context proposal store]
  (let [op (:op proposal)
        invoicing? (contains? #{:draft-invoice :issue-invoice} op)
        client-record (store/client store (:client-id request))
        declared (:client/jurisdiction client-record)
        tax (cond (not invoicing?) nil
                  (nil? declared) {:taxlaw/coverage :not-declared
                                   :taxlaw/why "client declares no jurisdiction"}
                  :else (taxlaw/credit-support
                         declared
                         {:registration-number (:issuer-registration-number proposal)}))]
    (gov/verdict
     {:violations (hard-violations request proposal store)
      :confidence (:confidence proposal)
      :escalating-op? (contains? escalating-ops op)
      :confidence-floor confidence-floor
      ;; A client that declares no jurisdiction is NOT held — it has asserted
      ;; nothing, and holding every such invoice would stop every existing
      ;; caller. But it is not silently passed either: `:tax` says the
      ;; creditability of this invoice was not checked, so a console shows
      ;; that rather than an unqualified approval. Same device as kintai's
      ;; `:unevaluated`, and the same reason — a question nobody could
      ;; answer belongs next to the answer, not inside it.
      :extra (cond-> {:tax tax}
               ;; Only present when a PASS rests on a partial check. Absent
               ;; when there is nothing to qualify, so a caller cannot read
               ;; an empty set as `nothing was left out`.
               (registration-unchecked tax)
               (assoc :tax-registration-unchecked (registration-unchecked tax)))})))
