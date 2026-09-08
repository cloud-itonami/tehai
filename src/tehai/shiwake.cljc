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
  `clojure.string` and `kotoba.taxlaw`, asserted by a test that reads its
  own source. Two reasons, and the second is the load-bearing one:

  1. This actor's ceiling is that it proposes. `:issue-invoice` already
     always escalates to a human; reaching past that to write into another
     actor's ledger would be the actuation the whole design refuses.
  2. **A call would make the accounts this actor's business.** They are
     not. Which account a consulting fee credits is the client's chart,
     and `kotoba-lang/shohyo` refuses to guess what an account is
     precisely because a statement that guessed still balances. So the
     mapping is an argument here too.

  `kotoba.taxlaw` is a pure, dependency-free statute catalog — reading it
  is not reaching. What the test forbids is `tehai.store`, `tehai.actor`,
  `tehai.governor` and `kotoba.psa`: this namespace must not be able to
  look up the ledger, and must not price anything.

  ## Direction: this is the other side from an expense

  An issued invoice RECOGNISES REVENUE. 売掛金 (accounts receivable) is
  debited and 売上 (revenue) credited — the mirror of `keihi.shiwake`,
  where a reimbursable expense debits a cost account and credits 未払金.
  The debit is a receivable and not cash on purpose: issuing an invoice is
  not receipt of money. Collection is a later, different entry, and
  posting cash here would show the firm holding money nobody has paid it.

  ## Consumption tax: two ways to get a figure, and one that stays refused

  tehai already reads インボイス制度 on the ISSUING side (`:tax` on the
  verdict; `:invoice-not-creditable` and `:unchecked-invoice-jurisdiction`
  as HARD holds), so an invoice its recipient could not credit never
  reaches `:commit` and is not re-checked here. What is settled here is
  where the tax FIGURE came from.

  `:invoice/tax` on the invoice is one of:

      a number > 0   the tax contained in `:invoice/total`. Three lines:
                     dr 売掛金 total / cr 売上 (total − tax) /
                     cr 仮受消費税 tax.
      0              a stated zero. Two lines, `:tax-treatment :none`.
      :none          stated as carrying no consumption tax component
                     (out of scope, exempt, 免税). Two lines, and the
                     request SAYS `:tax-treatment :none`, so the absence
                     travels as an assertion rather than as a silence.
      absent / nil   `:tax-not-stated`, UNLESS the invoice carries a
                     `:invoice/tax-basis`. Not zero.

  ### What is still refused, and always will be

  **Multiplying a total by a rate read off a jurisdiction table.** That
  produces an entry that balances and is wrong, and 「we applied 10%」 and
  「the issuer told us the tax was ¥24,000」 are not the same claim. An
  invoice that states neither a figure nor a basis is `:tax-not-stated`.
  Unstated is unstated, not zero — the same rule the governor applies to
  an uncatalogued jurisdiction.

  ### What is now computed, and why that is a different act

  消費税法施行令 第七十条の十 leaves the issuer exactly three decisions:
  which of the two methods (第一号 税抜価額 / 第二号 税込価額), which way
  「端数を処理する」 rounds, and **how the lines were grouped by rate**
  (「税率の異なるごとに区分して合計した金額」). When an invoice states all
  three, nothing is left to guess and the arithmetic is the statute's:

      :invoice/tax-basis {:jurisdiction :jp
                          :method       :tax-exclusive
                          :rounding     :floor
                          :subtotals    {:standard 240000}}

  That goes to `kotoba.taxlaw/consumption-tax-amount` verbatim, which
  multiplies **the per-rate subtotal** once and rounds **that one figure**
  once. This namespace neither defaults the method nor defaults the
  rounding — taxlaw refuses an unstated one and so does the result here.
  And it never groups lines into subtotals itself: which rate a line
  falls under is the entity's judgement about its own supply, and
  inventing it is the failure the 政令's shape exists to prevent.

  The jurisdiction on the basis is **the ISSUER's**, and it is not the one
  the governor used. The governor asks whether the recipient could claim a
  credit, so it reads the CLIENT's jurisdiction. 施行令 第七十条の十 governs
  what a 適格請求書発行事業者 writes on the invoice it issues, so it is the
  issuer's law. Two different questions that happen to both be about tax,
  and merging them would answer one with the other's statute.

  ### A derived figure and a handed-in figure are not the same claim

  Only the second sentence below is reproducible, so the result says which
  one it is at `:shiwake/tax-source`:

      :stated              the issuer told us ¥24,000
      :derived             we computed ¥24,000 from stated subtotals under
                           a stated method and rounding
      :stated-and-derived  both, and they agree
      :none                the issuer stated there is no tax component

  `:derived` and `:stated-and-derived` carry `:shiwake/tax-derivation` —
  the jurisdiction, provision, method, statute clause, rounding, the
  subtotals as given and the per-category figures — which is enough to
  recompute the number without this actor.

  **The provenance does not travel to 4311.** The emitted `:shiwake/request`
  is byte-for-byte the shape it has always been. This actor cannot change
  what another actor accepts, and inventing a key in a body whose acceptor
  is copied here rather than depended on is precisely the failure this
  repo keeps naming: two green suites and an entry lost between them.

  ## Every way the hand-off can lose an invoice is a named status

  Never nil, and never a suspense account. See `entry-request`."
  (:require [kotoba.lang.text :as str]
            [kotoba.taxlaw :as taxlaw]))

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

(defn- subtotal-sum
  "The per-rate subtotals added up, or nil when they are not all numbers.

  nil rather than 0: a subtotals map this actor could not add is not a
  zero-yen invoice, and reconciling against 0 would refuse for the wrong
  reason. taxlaw has already refused a non-integer subtotal by this point,
  so reaching nil here means the shape changed under us."
  [subtotals]
  (when (and (map? subtotals) (every? number? (vals subtotals)))
    (reduce + 0 (vals subtotals))))

(defn reconciled-total
  "What `:invoice/total` must be, given the method taxlaw ACCEPTED.

  Public only so the third-clause guard below can be measured. It is not
  reachable through `derive-tax` today — taxlaw echoes back only the method
  it accepted, and it accepts two — and a mutation flipping `:else nil` to
  `:else (+ sum tax)` therefore survived the whole suite on 2026-08-18. The
  answer to an unreachable guard is to reach it, not to delete it: a third
  clause is exactly the thing that would arrive without anyone here noticing.

  Read off the two clauses' own names. 第二号（税込価額）groups amounts
  that already contain the tax, so the subtotals ARE the total. 第一号
  （税抜価額）groups amounts that do not, so the tax is added on top.

  nil for any other method — a third clause groups some third way and this
  actor will not guess which. `case` would throw and `or` would pick a
  branch; both are worse than saying so."
  [method sum tax]
  (cond
    (= :tax-inclusive method) sum
    (= :tax-exclusive method) (+ sum tax)
    :else nil))

(defn- derive-tax
  "The 消費税額等 the basis computes to, or a refusal.

  `{:amount n :derivation {...}}` | `{:status kw :why s :shiwake/taxlaw m}`.

  Two things are checked and they fail differently. taxlaw answers whether
  the article can be applied at all — an uncatalogued jurisdiction, an
  unstated method, an unstated rounding, an unknown tax category. This
  function then checks the one thing taxlaw cannot see: that the subtotals
  and the invoice total are describing the same invoice. A basis that
  computes cleanly against a total it does not add up to is the more
  dangerous of the two, because the number it produces looks right."
  [basis total]
  (let [j (:jurisdiction basis)
        answer (taxlaw/consumption-tax-amount j basis)
        ;; MEASURED 2026-08-18: `consumption-tax-amount`'s `:none` branch
        ;; builds its map inline instead of going through taxlaw's own
        ;; `uncovered`, so — unlike `credit-support` — it does NOT carry the
        ;; `:taxlaw/out-of-scope` reason even where one is catalogued. Both
        ;; `[:eu]` and `[:us]` declare one for this facet and neither reaches
        ;; the caller. Asking `out-of-scope` directly recovers it. This is a
        ;; workaround in the consumer, not a fix: the right home for it is
        ;; taxlaw, and it should be removed when that lands.
        why (or (:taxlaw/why answer)
                (taxlaw/out-of-scope j :jurisdiction/qualified-invoice-tax-amount))]
    (if (not= :checked (:taxlaw/coverage answer))
      {:status :tax-not-derivable
       :shiwake/taxlaw (cond-> answer
                         (and why (nil? (:taxlaw/why answer)))
                         (assoc :taxlaw/out-of-scope :jurisdiction/qualified-invoice-tax-amount
                                :taxlaw/why why))
       :why (str "the invoice states a 消費税額等 basis that "
                 "消費税法施行令 第七十条の十 could not be applied to: "
                 (or why
                     (str "coverage " (pr-str (:taxlaw/coverage answer))
                          " for jurisdiction " (pr-str j)))
                 ". Neither the method nor the rounding is defaulted here — "
                 "the article hands both to the issuer, and picking one "
                 "answers a question nobody asked, by ¥1 per rate forever")}
      (let [tax (:taxlaw/tax answer)
            method (:taxlaw/method answer)
            sum (subtotal-sum (:subtotals basis))
            expected (when sum (reconciled-total method sum tax))]
        (cond
          (nil? expected)
          {:status :tax-basis-unreconciled
           :shiwake/taxlaw answer
           :why (str "cannot reconcile a basis using method " (pr-str method)
                     " against a total: this actor reads 第一号（税抜価額）and "
                     "第二号（税込価額）and refuses to guess how a third clause "
                     "groups; subtotals " (pr-str (:subtotals basis)))}

          (not= expected total)
          {:status :tax-basis-unreconciled
           :shiwake/taxlaw answer
           :why (str "the per-rate subtotals " (pr-str (:subtotals basis))
                     " come to " sum ", which under " (pr-str method)
                     " makes a total of " expected ", not the stated "
                     (pr-str total) ". Either the grouping omits part of the "
                     "invoice or the total is not the one these subtotals "
                     "describe; deriving a tax figure from one and crediting "
                     "売上 from the other would balance and be wrong")}

          :else
          {:amount tax
           :derivation {:jurisdiction (:taxlaw/jurisdiction answer)
                        :provision (:taxlaw/provision answer)
                        :method method
                        :method-statute (:taxlaw/method-statute answer)
                        :rounding (:taxlaw/rounding answer)
                        :rounds-per (:taxlaw/rounds-per answer)
                        :subtotals (:subtotals basis)
                        :tax-by-category (:taxlaw/tax-by-category answer)
                        :tax tax}})))))

(defn- resolve-tax
  "Where this invoice's tax figure comes from.

  `{:amount n :source kw :derivation m?}` | `{:status kw :why s ...}`."
  [invoice total]
  (let [basis (:invoice/tax-basis invoice)
        stated (tax-component (:invoice/tax invoice) total)]
    (cond
      ;; A malformed stated figure is refused before anything is computed.
      ;; Deriving past it would let a bad figure be replaced by a good one
      ;; and never reported.
      (:bad stated)
      {:status :unusable-invoice :why (:bad stated)}

      (and (:unstated stated) (nil? basis))
      {:status :tax-not-stated
       :why (str "the invoice states neither a 消費税 amount, nor :none, nor a "
                 ":invoice/tax-basis to compute one from. This actor will not "
                 "derive a figure from a rate and a total: an unstated figure "
                 "is unstated, not zero")}

      (nil? basis)
      {:amount (:amount stated)
       :source (if (pos? (:amount stated)) :stated :none)}

      :else
      (let [derived (derive-tax basis total)]
        (cond
          (:status derived) derived

          (:unstated stated)
          {:amount (:amount derived) :source :derived
           :derivation (:derivation derived)}

          (not= (:amount stated) (:amount derived))
          {:status :tax-disagrees
           :shiwake/stated (:amount stated)
           :shiwake/derived (:amount derived)
           :shiwake/taxlaw (:derivation derived)
           :why (str "the invoice states a tax of " (pr-str (:amount stated))
                     " and its own basis computes " (pr-str (:amount derived))
                     " under 消費税法施行令 第七十条の十. One of them is wrong and "
                     "this actor does not get to pick; posting either would "
                     "balance")}

          :else
          {:amount (:amount derived) :source :stated-and-derived
           :derivation (:derivation derived)})))))

(defn entry-request
  "An issued invoice as the `:draft-entry` request `isco-4311` accepts, or
  the reason there is none.

      {:shiwake/status :draft-only}        a draft was committed; nothing sent
      {:shiwake/status :not-issued}        an issue was held or awaits sign-off
      {:shiwake/status :unusable-invoice}  total, id, currency or tax unusable
      {:shiwake/status :tax-not-stated}    no tax amount, no `:none`, no basis
      {:shiwake/status :tax-not-derivable} a basis 第七十条の十 does not apply to
      {:shiwake/status :tax-basis-unreconciled}
                                           the subtotals do not add to the total
      {:shiwake/status :tax-disagrees}     stated figure ≠ what its basis computes
      {:shiwake/status :no-mapping}        a required account is not mapped
      {:shiwake/status :ok
       :shiwake/tax-source :stated | :derived | :stated-and-derived | :none
       :shiwake/tax-derivation {…}         only when a basis was applied
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

  ## Why these statuses and not nil

  `:draft-only` and `:not-issued` are separate values because they need
  opposite responses. A committed `:draft-invoice` produced no receivable
  and nobody has to act on it. An `:issue-invoice` that was held, or is
  sitting at `:request-approval`, is work in flight that somebody must
  look at — and a caller treating \"no entry\" as \"nothing to do\" would
  skip exactly those. Collapsing them into one absence would either send
  an operator to an empty queue or leave a real one unwatched.

  The four tax refusals are separate for the same reason: each is a
  different piece of work. `:tax-not-stated` asks the issuer for a figure.
  `:tax-not-derivable` asks it to finish the declaration it started —
  taxlaw would not apply the article, usually because the method or the
  rounding is missing. `:tax-basis-unreconciled` says the grouping and the
  total describe different invoices. `:tax-disagrees` says two figures on
  one invoice contradict each other. One queue for all four merges four
  different fixes, and three of them are not the issuer's arithmetic.

  A basis that fails is refused **even when a figure was also stated**.
  Ignoring the basis and using the stated figure would let an issuer
  believe its own arithmetic had been checked when it had not.

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
         category :invoice/category} invoice
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
      (let [resolved (resolve-tax invoice total)]
        (if-let [refusal (:status resolved)]
          (-> (dissoc resolved :status :why)
              (assoc :shiwake/status refusal :shiwake/why (:why resolved)))
          (let [amount (:amount resolved)
                taxed? (pos? amount)
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
              (cond-> {:shiwake/status :ok
                       :shiwake/tax-source (:source resolved)
                       :shiwake/request
                       ;; NOTE: no :client-id key, deliberately. 4311 REJECTS a body
                       ;; that names a client (400) rather than ignoring it — the
                       ;; caller's client is derived there from the verified DID, so a
                       ;; body that could name one could name someone else's ledger.
                       ;; And no provenance key either: see the ns docstring. The
                       ;; derivation stays on THIS map, where the caller reads it.
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
                                               :amount amount :currency currency}))}}
                (:derivation resolved)
                (assoc :shiwake/tax-derivation (:derivation resolved))))))))))

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
