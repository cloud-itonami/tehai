(ns tehai.shiwake
  "仕訳 — an ISSUED invoice as a journal entry request.

  This actor decides whether an invoice may be sent. Deciding is not
  bookkeeping: **an invoice that was issued and never became a journal
  entry is revenue nobody's books show.** `cloud-itonami-isco-4311` owns
  the ledger; this namespace produces the value that actor accepts at
  `POST /api/entry`.

  ## It produces a value, it does not make a call

  No HTTP, no client, no reference to 4311, and no reference to
  `tehai.store` or `tehai.actor` either — this namespace requires exactly
  `clojure.string`, asserted by a test that reads its own source. Two
  reasons, and the second is the load-bearing one:

  1. This actor's ceiling is that it proposes. `:issue-invoice` already
     always escalates to a human; reaching past that to write into another
     actor's ledger would be the actuation the whole design refuses.
  2. **A call would make the accounts this actor's business.** They are
     not. Which account a consulting fee credits is the client's chart,
     and `kotoba-lang/shohyo` refuses to guess what an account is
     precisely because a statement that guessed still balances. So the
     mapping is an argument here too.

  ## Direction: this is the other side from an expense

  An issued invoice RECOGNISES REVENUE. 売掛金 (accounts receivable) is
  debited and 売上 (revenue) credited — the mirror of `keihi.shiwake`,
  where a reimbursable expense debits a cost account and credits 未払金.
  The debit is a receivable and not cash on purpose: issuing an invoice is
  not receipt of money. Collection is a later, different entry, and
  posting cash here would show the firm holding money nobody has paid it.

  ## Consumption tax: split, but only on a figure somebody stated

  tehai already reads インボイス制度 on the ISSUING side (`:tax` on the
  verdict; `:invoice-not-creditable` and `:unchecked-invoice-jurisdiction`
  as HARD holds), so an invoice its recipient could not credit never
  reaches `:commit` and is not re-checked here. What is checked here is
  whether the tax FIGURE travelled.

  `:invoice/tax` on the invoice is required, and is one of:

      a number > 0   the tax contained in `:invoice/total`. Three lines:
                     dr 売掛金 total / cr 売上 (total − tax) /
                     cr 仮受消費税 tax.
      0              a stated zero. Two lines, `:tax-treatment :none`.
      :none          stated as carrying no consumption tax component
                     (out of scope, exempt, 免税). Two lines, and the
                     request SAYS `:tax-treatment :none`, so the absence
                     travels as an assertion rather than as a silence.
      absent / nil   `:tax-not-stated`. Not zero.

  `kotoba.psa/invoice` computes no tax at all, so the figure can only come
  from whoever issued the invoice. This namespace will not derive one:
  multiplying a total by a rate read off a jurisdiction table produces an
  entry that balances and is wrong, and 「we applied 10%」 and 「the issuer
  told us the tax was ¥24,000」 are not the same claim. An unstated figure
  is unstated, not zero — the same rule the governor applies to an
  uncatalogued jurisdiction.

  ## Every way the hand-off can lose an invoice is a named status

  Never nil, and never a suspense account. See `entry-request`."
  (:require [clojure.string :as str]))

(defn- positive-amount? [x] (and (number? x) (pos? x)))
(defn- blank-account? [a] (or (not (string? a)) (str/blank? a)))

(defn- tax-component
  "`:invoice/tax` -> `{:amount n}` | `{:unstated true}` | `{:bad reason}`.

  `total` is needed because a tax component that is not strictly less than
  the total leaves no revenue: the entry would still balance, having lost
  the sale."
  [tax total]
  (cond
    (= :none tax) {:amount 0}
    (nil? tax) {:unstated true}
    (not (number? tax)) {:bad (str "tax must be a number or :none; got " (pr-str tax))}
    (neg? tax) {:bad (str "tax may not be negative; got " (pr-str tax))}
    (>= tax total) {:bad (str "tax " (pr-str tax) " is not less than the total "
                              (pr-str total) "; the entry would balance with no 売上 in it")}
    :else {:amount tax}))

(defn entry-request
  "An issued invoice as the `:draft-entry` request `isco-4311` accepts, or
  the reason there is none.

      {:shiwake/status :draft-only}       a draft was committed; nothing was sent
      {:shiwake/status :not-issued}       an issue was held or awaits sign-off
      {:shiwake/status :unusable-invoice} total, id, currency or tax figure unusable
      {:shiwake/status :tax-not-stated}   no tax amount and no `:none`
      {:shiwake/status :no-mapping}       a required account is not mapped
      {:shiwake/status :ok
       :shiwake/request {:op :draft-entry :source-doc … :lines [...]}}

  `issued` is `{:disposition … :op … :client-id … :invoice {…}}`, the
  committed run of one operation. `mapping` is the client's chart:

      {:receivable {\"c-1\" \"売掛金\"}
       :revenue    {:consulting \"売上高\"}
       :tax        {\"c-1\" \"仮受消費税\"}}

  Receivable and tax are keyed by CLIENT, not globally: a firm sub-ledgers
  受取債権 per client, and which liability account holds 仮受消費税
  follows the client's jurisdiction — the governor already establishes
  that the client's jurisdiction is the one that decides, because the
  client is who would claim the credit.

  ## Why five statuses and not nil

  `:draft-only` and `:not-issued` are separate values because they need
  opposite responses. A committed `:draft-invoice` produced no receivable
  and nobody has to act on it. An `:issue-invoice` that was held, or is
  sitting at `:request-approval`, is work in flight that somebody must
  look at — and a caller treating \"no entry\" as \"nothing to do\" would
  skip exactly those. Collapsing them into one absence would either send
  an operator to an empty queue or leave a real one unwatched.

  `:no-mapping` never falls back to a suspense account. Posting to 仮受金
  would make the entry appear and the missing decision disappear, which is
  the worse of the two failures. A HALF-FILLED mapping is no mapping for
  the same reason: an entry missing one line balances by having lost it.
  Only the accounts the emitted lines actually need are required, so a
  zero-tax invoice does not demand a 仮受消費税 account it will not use.

  `:tax-not-stated` is separate from `:unusable-invoice` because a missing
  tax figure is a question for whoever issued the invoice, while a
  malformed total is a bug in whatever produced it; one queue for both
  merges two different pieces of work."
  [{:keys [disposition op client-id invoice] :as _issued} mapping]
  (let [{total :invoice/total id :invoice/id currency :invoice/currency
         category :invoice/category tax :invoice/tax} invoice
        receivable (get-in mapping [:receivable client-id])
        revenue (get-in mapping [:revenue category])]
    (cond
      (not= :issue-invoice op)
      {:shiwake/status :draft-only :shiwake/op op
       :shiwake/why (str "op " (pr-str op) " sends no invoice, so it recognises no "
                         "revenue; a draft is not a receivable")}

      (not= :commit disposition)
      {:shiwake/status :not-issued :shiwake/disposition disposition}

      (or (not (positive-amount? total))
          (blank-account? id)
          (blank-account? currency))
      {:shiwake/status :unusable-invoice
       :shiwake/why (str "an issued invoice needs a positive total, an id to cite as the "
                         "source document, and a currency — 4311 keys its trial balance on "
                         "[account currency], so an entry with none lands in a nil bucket. "
                         "got " (pr-str {:invoice/total total :invoice/id id
                                         :invoice/currency currency}))}

      :else
      (let [{:keys [amount unstated bad]} (tax-component tax total)]
        (cond
          unstated
          {:shiwake/status :tax-not-stated
           :shiwake/why (str "the invoice states neither a 消費税 amount nor :none. "
                             "This actor will not derive one from a rate: an unstated "
                             "figure is unstated, not zero")}

          bad
          {:shiwake/status :unusable-invoice :shiwake/why bad}

          :else
          (let [taxed? (pos? amount)
                tax-account (get-in mapping [:tax client-id])
                missing (cond-> []
                          (blank-account? receivable) (conj :receivable)
                          (blank-account? revenue) (conj :revenue)
                          (and taxed? (blank-account? tax-account)) (conj :tax))]
            (if (seq missing)
              {:shiwake/status :no-mapping
               :shiwake/missing missing
               :shiwake/client client-id
               :shiwake/category category
               :shiwake/why (str "no account mapped for " (pr-str missing)
                                 " (client " (pr-str client-id) ", category "
                                 (pr-str category) "); this actor does not choose them, "
                                 "and it does not post to a suspense account instead")}
              {:shiwake/status :ok
               :shiwake/request
               ;; NOTE: no :client-id key, deliberately. 4311 REJECTS a body
               ;; that names a client (400) rather than ignoring it — the
               ;; caller's client is derived there from the verified DID, so a
               ;; body that could name one could name someone else's ledger.
               {:op :draft-entry
                ;; The invoice id is the source document on BOTH sides of the
                ;; hand-off: 4311 holds an entry citing a document its own
                ;; registry does not know, so an invoice issued here against an
                ;; id never registered there is refused rather than posted.
                ;; That is the right way round — the ledger's registry counts.
                :source-doc id
                :tax-treatment (if taxed? :stated :none)
                :lines (cond-> [{:side :dr :account receivable
                                 :amount total :currency currency}
                                {:side :cr :account revenue
                                 :amount (- total amount) :currency currency}]
                         taxed? (conj {:side :cr :account tax-account
                                       :amount amount :currency currency}))}})))))))

(defn entry-requests
  "`entry-request` over many issued invoices, keeping the refusals.

  Returns `{:ok [...] :skipped [...]}` rather than filtering, and each
  refusal carries `:shiwake/issued` — the invoice it refused. A batch that
  quietly dropped what it could not convert would report a clean run and
  leave unbilled revenue invisible, which is the shape this workspace
  keeps finding."
  [issued mapping]
  (let [rs (map #(assoc (entry-request % mapping) :shiwake/issued %) issued)]
    {:ok (vec (filter #(= :ok (:shiwake/status %)) rs))
     :skipped (vec (remove #(= :ok (:shiwake/status %)) rs))}))
