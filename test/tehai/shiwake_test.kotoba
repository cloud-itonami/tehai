(ns tehai.shiwake-test
  "An issued invoice becoming a journal entry — and every way that hand-off
  can quietly lose revenue.

  The suite pins three things the expense-side equivalent (`keihi.shiwake`)
  cannot: that the entry runs the OTHER WAY (売掛金 debit, 売上 credit),
  that 消費税 is split out only from a figure somebody stated, and that the
  emitted body never names a client — `cloud-itonami-isco-4311` REJECTS a
  body carrying `:client-id` rather than ignoring it."
  (:require [kotoba.lang.text] [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [kotoba.taxlaw :as taxlaw]
            [tehai.shiwake :as shiwake]))

(def ^:private mapping
  {:receivable {"c-1" "売掛金"}
   :revenue    {:consulting "売上高" :development "売上高"}
   :tax        {"c-1" "仮受消費税"}})

(defn- issued
  [& {:keys [disposition op client-id id total tax currency category]
      :or {disposition :commit op :issue-invoice client-id "c-1" id "inv-1"
           total 264000 tax 24000 currency "JPY" category :consulting}}]
  {:disposition disposition
   :op op
   :client-id client-id
   :invoice (cond-> {:invoice/id id :invoice/total total
                     :invoice/currency currency :invoice/category category}
              (some? tax) (assoc :invoice/tax tax))})

(defn- lines [r] (get-in r [:shiwake/request :lines]))

;; ---------------------------------------------------------------------------
;; 4311's own admission rule, copied deliberately
;; ---------------------------------------------------------------------------

(defn- accepted-by-4311?
  "The predicate `bookkeeping.edge.endpoints/parse-entry-body` applies, held
  here as a copy rather than a dependency — this actor must not reach for
  4311's code any more than for its socket. If that endpoint tightens, this
  copy is what has to be updated, and the divergence is the point: an entry
  this actor emits and that ledger refuses is revenue lost between two green
  test suites."
  [m]
  (and (map? m)
       (not (contains? m :client-id))
       (string? (:source-doc m))
       (vector? (:lines m)) (seq (:lines m))
       (every? #(and (#{:dr :cr} (:side %))
                     (string? (:account %))
                     (number? (:amount %)))
               (:lines m))))

;; ---------------------------------------------------------------------------
;; direction, and the tax split
;; ---------------------------------------------------------------------------

(deftest an-issued-invoice-recognises-revenue-the-other-way-round
  (testing "売掛金 debit / 売上 credit — the mirror of an expense claim, and
            a receivable rather than cash because issuing is not collecting"
    (let [r (shiwake/entry-request (issued) mapping)
          req (:shiwake/request r)]
      (is (= :ok (:shiwake/status r)))
      (is (= :draft-entry (:op req)))
      (is (= "inv-1" (:source-doc req)))
      (is (= [["売掛金" :dr 264000] ["売上高" :cr 240000] ["仮受消費税" :cr 24000]]
             (mapv (juxt :account :side :amount) (:lines req))))
      (is (= ["JPY" "JPY" "JPY"] (mapv :currency (:lines req)))))))

(deftest the-entry-balances
  (doseq [c [(issued) (issued :tax :none) (issued :tax 0)]]
    (let [ls (lines (shiwake/entry-request c mapping))
          side (fn [s] (reduce + 0 (map :amount (filter #(= s (:side %)) ls))))]
      (is (= (side :dr) (side :cr))))))

(deftest a-stated-tax-is-split-out-of-the-total
  (testing "the total is what the client owes; 売上 is what the firm earned.
            Crediting the whole receipt to 売上 would overstate revenue by
            the tax the firm is only holding"
    (let [ls (lines (shiwake/entry-request (issued :total 110000 :tax 10000) mapping))]
      (is (= 100000 (:amount (second ls))))
      (is (= 10000 (:amount (nth ls 2)))))))

(deftest an-unstated-tax-is-refused-not-assumed-to-be-zero
  (testing "deriving 10% from a jurisdiction table would produce an entry
            that balances and is wrong; `we applied a rate` and `the issuer
            told us the tax` are not the same claim"
    (let [r (shiwake/entry-request (issued :tax nil) mapping)]
      (is (= :tax-not-stated (:shiwake/status r)))
      (is (nil? (:shiwake/request r)))))
  (testing "and it is NOT emitted as a silent two-line entry"
    (is (empty? (lines (shiwake/entry-request (issued :tax nil) mapping))))))

(deftest a-stated-absence-of-tax-travels-as-an-assertion
  (testing ":none is two lines and SAYS so, so a reader can tell `no
            consumption tax component` from `nobody said`"
    (let [r (shiwake/entry-request (issued :tax :none) mapping)]
      (is (= :ok (:shiwake/status r)))
      (is (= :none (get-in r [:shiwake/request :tax-treatment])))
      (is (= [["売掛金" :dr 264000] ["売上高" :cr 264000]]
             (mapv (juxt :account :side :amount) (lines r))))))
  (testing "a stated zero is the same statement"
    (let [r (shiwake/entry-request (issued :tax 0) mapping)]
      (is (= :ok (:shiwake/status r)))
      (is (= :none (get-in r [:shiwake/request :tax-treatment])))
      (is (= 2 (count (lines r))))))
  (testing "and a split entry says the other thing"
    (is (= :stated (get-in (shiwake/entry-request (issued) mapping)
                           [:shiwake/request :tax-treatment]))))
  (testing "the RESULT says it too, and not only the emitted body. Measured
            2026-08-18: a mutation collapsing the absence into :stated
            survived the whole suite — every assertion here was reading
            :tax-treatment, which is what travels to 4311, and nothing was
            reading what this actor tells its own caller"
    (is (= :none (:shiwake/tax-source (shiwake/entry-request (issued :tax :none) mapping))))
    (is (= :none (:shiwake/tax-source (shiwake/entry-request (issued :tax 0) mapping))))
    (is (= :stated (:shiwake/tax-source (shiwake/entry-request (issued) mapping))))))

(deftest a-tax-figure-that-cannot-be-a-tax-figure-is-refused
  (doseq [t ["24000" -1 264000 300000]]
    (let [r (shiwake/entry-request (issued :tax t) mapping)]
      (is (= :unusable-invoice (:shiwake/status r))
          (str "tax " (pr-str t) " must not produce an entry"))
      (is (nil? (:shiwake/request r))))))

;; ---------------------------------------------------------------------------
;; nothing is ever nil
;; ---------------------------------------------------------------------------

(deftest an-invoice-that-was-not-issued-yields-a-named-refusal
  (testing "not nil — a caller treating `no entry` as `nothing to do` would
            skip exactly the invoices somebody has to look at"
    (doseq [d [:hold :request-approval]]
      (let [r (shiwake/entry-request (issued :disposition d) mapping)]
        (is (= :not-issued (:shiwake/status r)))
        (is (= d (:shiwake/disposition r)))
        (is (nil? (:shiwake/request r)))))))

(deftest a-committed-draft-is-its-own-status
  (testing "a draft produced no receivable and nobody has to act on it —
            merging it with :not-issued would either send an operator to an
            empty queue or leave a real one unwatched"
    (doseq [op [:draft-invoice :record-expense :assign-person]]
      (let [r (shiwake/entry-request (issued :op op) mapping)]
        (is (= :draft-only (:shiwake/status r)))
        (is (= op (:shiwake/op r)))
        (is (nil? (:shiwake/request r))))))
  (testing "and it is a different status from the held issue"
    (is (not= (:shiwake/status (shiwake/entry-request (issued :op :draft-invoice) mapping))
              (:shiwake/status (shiwake/entry-request (issued :disposition :hold) mapping))))))

(deftest an-unmapped-client-or-category-is-refused-not-suspensed
  (testing "posting to 仮受金 would make the entry appear and the missing
            decision disappear"
    (let [r (shiwake/entry-request (issued :client-id "c-9") mapping)]
      (is (= :no-mapping (:shiwake/status r)))
      (is (= [:receivable :tax] (:shiwake/missing r)))
      (is (nil? (:shiwake/request r))))
    (let [r (shiwake/entry-request (issued :category :retainer) mapping)]
      (is (= :no-mapping (:shiwake/status r)))
      (is (= [:revenue] (:shiwake/missing r)))
      (is (= :retainer (:shiwake/category r)))))
  (testing "an invoice with no category at all is unmapped, not defaulted"
    (is (= :no-mapping (:shiwake/status
                        (shiwake/entry-request (issued :category nil) mapping)))))
  (testing "a half-filled mapping is no mapping — an entry missing one line
            balances by having lost it"
    (is (= :no-mapping (:shiwake/status
                        (shiwake/entry-request (issued) (dissoc mapping :tax)))))
    (is (= :no-mapping (:shiwake/status
                        (shiwake/entry-request (issued) (assoc-in mapping [:tax "c-1"] "")))))
    (is (= :no-mapping (:shiwake/status
                        (shiwake/entry-request (issued)
                                               (assoc-in mapping [:receivable "c-1"] " "))))))
  (testing "but an account the emitted lines do not need is not demanded —
            a zero-tax invoice needs no 仮受消費税 account"
    (is (= :ok (:shiwake/status
                (shiwake/entry-request (issued :tax :none) (dissoc mapping :tax)))))))

(deftest an-unusable-invoice-is-refused
  (doseq [c [(issued :total 0) (issued :total -5) (issued :total nil)
             (issued :total "264000") (issued :id nil) (issued :id "")
             (issued :currency nil) (issued :currency "")]]
    (is (= :unusable-invoice (:shiwake/status (shiwake/entry-request c mapping)))))
  (testing "and with the tax stated as :none, so the total is checked on its
            own account. Measured: without these two cases, weakening the
            total check to `is a number` reddened NOTHING — every taxed case
            was caught downstream by `tax >= total`, and a zero-amount entry
            would have gone out balanced"
    (doseq [c [(issued :total 0 :tax :none) (issued :total -5 :tax :none)
               (issued :total 0 :tax 0)]]
      (is (= :unusable-invoice (:shiwake/status (shiwake/entry-request c mapping))))
      (is (nil? (:shiwake/request (shiwake/entry-request c mapping))))))
  (testing "the currency is required because 4311 keys its trial balance on
            [account currency] — an entry with none lands in a nil bucket"
    (is (nil? (:shiwake/request (shiwake/entry-request (issued :currency nil) mapping))))))

;; ---------------------------------------------------------------------------
;; the shape the ledger actually accepts
;; ---------------------------------------------------------------------------

(deftest the-emitted-body-is-one-4311-accepts
  (doseq [c [(issued) (issued :tax :none) (issued :tax 0)]]
    (is (accepted-by-4311? (:shiwake/request (shiwake/entry-request c mapping))))))

(deftest the-emitted-body-never-names-a-client
  (testing "4311 REJECTS a body naming a client (400) rather than ignoring
            it — the caller's client is derived there from the verified DID.
            Passing the client through would 400 every entry this actor
            emits, which is the whole hand-off lost to one key"
    (let [req (:shiwake/request (shiwake/entry-request (issued) mapping))]
      (is (not (contains? req :client-id)))
      (is (not-any? #(re-find #"c-1" (str %)) (vals req))))))

(deftest the-invoice-id-is-carried-as-the-source-document
  (testing "4311 holds an entry citing a document its own registry does not
            know, so an invoice issued here against an unregistered id is
            refused there rather than posted — the ledger's registry counts"
    (is (= "inv-99" (get-in (shiwake/entry-request (issued :id "inv-99") mapping)
                            [:shiwake/request :source-doc])))))

;; ---------------------------------------------------------------------------
;; batch, and reach
;; ---------------------------------------------------------------------------

(deftest a-batch-keeps-what-it-could-not-convert
  (testing "filtering would report a clean run and leave unbilled revenue
            invisible"
    (let [b (shiwake/entry-requests
             [(issued)
              (issued :disposition :hold)
              (issued :op :draft-invoice)
              (issued :category :retainer)
              (issued :tax nil)]
             mapping)]
      (is (= 1 (count (:ok b))))
      (is (= 4 (count (:skipped b))))
      (is (= #{:not-issued :draft-only :no-mapping :tax-not-stated}
             (set (map :shiwake/status (:skipped b)))))
      (is (every? :shiwake/issued (:skipped b))
          "each refusal carries the invoice it refused, or it cannot be acted on"))))

(deftest this-namespace-reaches-nothing
  (testing "it produces a value; reaching across to write into another
            actor's ledger would be the actuation this actor refuses —
            especially here, where :issue-invoice already always escalates
            to a human"
    (let [src (slurp "src/tehai/shiwake.cljc")]
      (doseq [tok ["http" "fetch" "slurp" "4311" "client/" "js/"]]
        (is (not (re-find (re-pattern (str "\\(" tok)) src))
            (str "shiwake must not call out: found " tok)))))
  (testing "and it reads nothing that could tell it about THIS engagement —
            no store, no actor, no governor, no psa.

            CHANGED 2026-08-18, from `(= #{kotoba.lang.text} reqs)`. That
            assertion was an exact set standing in for a prohibition its own
            `testing` string states as `no store, no actor, no psa`, and
            adding `kotoba.taxlaw` — a pure, dependency-free statute catalog
            with no I/O — reddened it without violating anything it was
            protecting. Two assertions replace it and together they are
            STRICTER than the one they replace: the allow-list still refuses
            anything not named, and the prohibition is now written down
            instead of being inferable only from a comment."
    (let [reqs (->> (read-string (slurp "src/tehai/shiwake.cljc"))
                    (filter list?)
                    (filter #(= :require (first %)))
                    first rest (map first) set)
          ;; Each of these is a pure value library. Adding to this list is a
          ;; decision, not a formality: a namespace that can look something
          ;; up can be wrong about this engagement in particular, and that
          ;; is the class this test exists to keep out.
          allowed '#{kotoba.lang.text kotoba.taxlaw}]
      (is (empty? (set/difference reqs allowed))
          (str "shiwake must stay a pure value function; requires "
               (pr-str reqs)))
      (is (empty? (set/intersection
                   reqs
                   '#{tehai.store tehai.actor tehai.governor tehai.advisor
                      tehai.handoff kotoba.psa langchain.db}))
          "shiwake must not be able to look up the ledger, and must not price
           anything — the accounts and the amounts are both arguments"))))

;; ---------------------------------------------------------------------------
;; 消費税額等 — 消費税法施行令 第七十条の十, when the issuer states its three
;; decisions and the arithmetic is therefore not a guess
;; ---------------------------------------------------------------------------

(defn- with-basis
  "An issued invoice whose tax figure is left to its basis unless `:tax` is
  passed. Defaults reproduce the suite's running example: ¥240,000 net, 10%
  standard, ¥264,000 owed."
  [& {:keys [method rounding subtotals total tax jurisdiction]
      :or {method :tax-exclusive rounding :floor
           subtotals {:standard 240000} total 264000 jurisdiction :jp}
      :as opts}]
  (-> (issued :total total :tax tax)
      (assoc-in [:invoice :invoice/tax-basis]
                (cond-> {:jurisdiction jurisdiction}
                  (contains? (or opts {}) :method) (assoc :method method)
                  (contains? (or opts {}) :rounding) (assoc :rounding rounding)
                  (contains? (or opts {}) :subtotals) (assoc :subtotals subtotals)
                  (not (contains? (or opts {}) :method)) (assoc :method method)
                  (not (contains? (or opts {}) :rounding)) (assoc :rounding rounding)
                  (not (contains? (or opts {}) :subtotals)) (assoc :subtotals subtotals)))))

(defn- bare-basis
  "An invoice whose basis is EXACTLY `basis` — used to leave a key out."
  [basis & {:keys [total tax] :or {total 264000}}]
  (assoc-in (issued :total total :tax tax) [:invoice :invoice/tax-basis] basis))

(deftest a-stated-basis-yields-a-figure-nobody-had-to-hand-over
  (testing "the issuer stated the method, the rounding and the grouping —
            the three things 第七十条の十 leaves to it. Nothing is left to
            guess, so the arithmetic is the statute's and not a rate applied
            to a total"
    (let [r (shiwake/entry-request (with-basis) mapping)]
      (is (= :ok (:shiwake/status r)))
      (is (= :derived (:shiwake/tax-source r)))
      (is (= [["売掛金" :dr 264000] ["売上高" :cr 240000] ["仮受消費税" :cr 24000]]
             (mapv (juxt :account :side :amount) (lines r)))))))

(deftest a-derived-figure-is-distinguishable-from-a-handed-in-one
  (testing "the two produce IDENTICAL lines. If the result did not say
            which, `the issuer told us ¥24,000` and `we computed ¥24,000`
            would be the same output — and only the second is reproducible"
    (let [handed (shiwake/entry-request (issued :tax 24000) mapping)
          computed (shiwake/entry-request (with-basis) mapping)]
      (is (= (lines handed) (lines computed)))
      (is (= :stated (:shiwake/tax-source handed)))
      (is (= :derived (:shiwake/tax-source computed)))
      (is (not= (:shiwake/tax-source handed) (:shiwake/tax-source computed)))))
  (testing "and only the computed one carries the working"
    (is (nil? (:shiwake/tax-derivation (shiwake/entry-request (issued :tax 24000) mapping))))
    (let [d (:shiwake/tax-derivation (shiwake/entry-request (with-basis) mapping))]
      (is (= [:jp] (:jurisdiction d)))
      (is (= "消費税法施行令 第七十条の十" (:provision d)))
      (is (= :tax-exclusive (:method d)))
      (is (= "第一号（税抜価額）" (:method-statute d)))
      (is (= :floor (:rounding d)))
      (is (= :tax-category-subtotal (:rounds-per d)))
      (is (= {:standard 240000} (:subtotals d)))
      (is (= {:standard 24000} (:tax-by-category d)))
      (is (= 24000 (:tax d)))))
  (testing "reproducible means REPRODUCIBLE: the recorded inputs, handed
            back to taxlaw by a third party, give the recorded figure"
    (let [d (:shiwake/tax-derivation (shiwake/entry-request (with-basis) mapping))]
      (is (= (:tax d)
             (taxlaw/consumption-tax (:jurisdiction d)
                                     (select-keys d [:method :rounding :subtotals])))))))

(deftest an-invoice-with-neither-a-figure-nor-a-basis-is-still-tax-not-stated
  (testing "unchanged, and the refusal that must survive: deriving from a
            rate and a total is still not something this actor does"
    (let [r (shiwake/entry-request (issued :tax nil) mapping)]
      (is (= :tax-not-stated (:shiwake/status r)))
      (is (nil? (:shiwake/request r)))
      (is (nil? (:shiwake/tax-source r))))))

(deftest neither-the-method-nor-the-rounding-is-defaulted
  (testing "「いずれかとする」 and 「端数を処理するものとする」 are both choices
            the article hands the issuer. A library that picks one is wrong
            by ¥1 per rate on every invoice, forever"
    (doseq [[what basis] [[:no-method {:jurisdiction :jp :rounding :floor
                                       :subtotals {:standard 240000}}]
                          [:no-rounding {:jurisdiction :jp :method :tax-exclusive
                                         :subtotals {:standard 240000}}]
                          [:neither {:jurisdiction :jp :subtotals {:standard 240000}}]]]
      (let [r (shiwake/entry-request (bare-basis basis) mapping)]
        (is (= :tax-not-derivable (:shiwake/status r)) (str what))
        (is (nil? (:shiwake/request r)) (str what))
        (is (seq (:taxlaw/choices (:shiwake/taxlaw r)))
            (str what " — the refusal must name the choices, or the issuer
                 cannot act on it")))))
  (testing "and a rounding this article does not offer is not coerced into
            one it does"
    (is (= :tax-not-derivable
           (:shiwake/status
            (shiwake/entry-request
             (bare-basis {:jurisdiction :jp :method :tax-exclusive
                          :rounding :bankers :subtotals {:standard 240000}})
             mapping))))))

(deftest this-actor-does-not-group-lines-into-subtotals
  (testing "which rate a line falls under is the entity's judgement about
            its own supply. A basis with no :subtotals is refused rather
            than filled in from the total — that grouping is exactly what
            the 政令's shape exists to make visible"
    (let [r (shiwake/entry-request
             (bare-basis {:jurisdiction :jp :method :tax-exclusive :rounding :floor})
             mapping)]
      (is (= :tax-not-derivable (:shiwake/status r)))
      (is (re-find #"subtotals" (str (:taxlaw/why (:shiwake/taxlaw r)))))))
  (testing "and a category the article does not name is not sorted into one
            it does"
    (is (= :tax-not-derivable
           (:shiwake/status
            (shiwake/entry-request
             (bare-basis {:jurisdiction :jp :method :tax-exclusive :rounding :floor
                          :subtotals {:standard 240000 :exempt 1000}})
             mapping))))))

(deftest the-multiplication-is-on-the-subtotal-and-not-per-line
  (testing "two ¥1,005 lines. Taxed per line and summed: 100 + 100 = 200.
            Taxed on the ¥2,010 subtotal, which is what 「税率の異なるごとに
            区分して合計した金額に…を乗じて」 says: 201. Per-line is a THIRD
            method and the article offers two"
    (let [r (shiwake/entry-request
             (with-basis :subtotals {:standard 2010} :total 2211) mapping)]
      (is (= :ok (:shiwake/status r)))
      (is (= 201 (:amount (nth (lines r) 2))))
      (is (not= 200 (:amount (nth (lines r) 2)))))))

(deftest the-rounding-is-rounded-once-and-the-direction-is-the-issuers
  (testing "one ¥1,005 subtotal, three legal answers. This actor produces
            whichever the issuer declared and never picks"
    (doseq [[rounding tax total] [[:floor 100 1105]
                                  [:ceil 101 1106]
                                  [:round-half-up 101 1106]]]
      (let [r (shiwake/entry-request
               (with-basis :subtotals {:standard 1005} :rounding rounding :total total)
               mapping)]
        (is (= :ok (:shiwake/status r)) (str rounding))
        (is (= tax (:amount (nth (lines r) 2))) (str rounding))))))

(deftest each-rate-is-rounded-separately-and-then-summed
  (testing "標準 and 軽減 are 「税率の異なるごとに区分して合計した」 — two
            subtotals, each multiplied and rounded once, then added.
            floor(1005×10/100)=100 and floor(1005×8/100)=80"
    (let [r (shiwake/entry-request
             (with-basis :subtotals {:standard 1005 :reduced 1005} :total 2190) mapping)]
      (is (= :ok (:shiwake/status r)))
      (is (= 180 (:amount (nth (lines r) 2))))
      (is (= {:standard 100 :reduced 80}
             (:tax-by-category (:shiwake/tax-derivation r)))))))

(deftest a-grouping-method-this-actor-does-not-know-is-not-reconciled-as-one-it-does
  (testing "第一号 groups net amounts and 第二号 groups gross ones, so the
            total is `sum + tax` under one and `sum` under the other. A third
            clause groups some third way and there is no safe branch to pick;
            `case` would throw and `or` would silently choose.

            Measured directly because it CANNOT be reached through
            `entry-request` — taxlaw echoes back only the method it accepted,
            and it accepts two. A mutation turning `:else nil` into
            `:else (+ sum tax)` survived the entire suite until this test
            existed, which is a fact about the code's shape and not about the
            mutation"
    (is (= 240000 (shiwake/reconciled-total :tax-inclusive 240000 24000)))
    (is (= 264000 (shiwake/reconciled-total :tax-exclusive 240000 24000)))
    (doseq [m [:tax-included :per-line nil :floor]]
      (is (nil? (shiwake/reconciled-total m 240000 24000))
          (str "method " (pr-str m) " was reconciled as if it were known")))))

(deftest a-tax-inclusive-basis-groups-amounts-that-already-contain-the-tax
  (testing "第二号（税込価額）: the subtotals ARE the total, and the tax comes
            out of it at 10/110. Same invoice as 第一号 above, stated the
            other way, same three lines"
    (let [r (shiwake/entry-request
             (with-basis :method :tax-inclusive :subtotals {:standard 264000}
                         :total 264000)
             mapping)]
      (is (= :ok (:shiwake/status r)))
      (is (= "第二号（税込価額）" (:method-statute (:shiwake/tax-derivation r))))
      (is (= [["売掛金" :dr 264000] ["売上高" :cr 240000] ["仮受消費税" :cr 24000]]
             (mapv (juxt :account :side :amount) (lines r)))))))

(deftest subtotals-that-do-not-add-to-the-total-are-refused
  (testing "a basis that computes cleanly against a total it does not
            describe is the dangerous one, because the figure it produces
            looks right. Under 税抜 the subtotals plus the tax must BE the
            total; under 税込 they must be it"
    (doseq [[what inv] [[:exclusive-short (with-basis :subtotals {:standard 100000})]
                        [:exclusive-long (with-basis :subtotals {:standard 900000})]
                        [:inclusive-mismatch (with-basis :method :tax-inclusive
                                                         :subtotals {:standard 240000}
                                                         :total 264000)]]]
      (let [r (shiwake/entry-request inv mapping)]
        (is (= :tax-basis-unreconciled (:shiwake/status r)) (str what))
        (is (nil? (:shiwake/request r)) (str what))
        (is (re-find #"balance and be wrong" (:shiwake/why r)) (str what)))))
  (testing "and the reconciled one is not refused — the check discriminates"
    (is (= :ok (:shiwake/status (shiwake/entry-request (with-basis) mapping))))))

(deftest a-stated-figure-that-contradicts-its-own-basis-is-refused
  (testing "two figures on one invoice, one of them wrong, and this actor
            does not get to pick. Posting either would balance"
    (let [r (shiwake/entry-request (with-basis :tax 24001) mapping)]
      (is (= :tax-disagrees (:shiwake/status r)))
      (is (= 24001 (:shiwake/stated r)))
      (is (= 24000 (:shiwake/derived r)))
      (is (nil? (:shiwake/request r)))))
  (testing "reconciliation is against the DERIVED figure, so an invoice
            whose total moved with the stated tax is unreconciled rather
            than contradictory. Two different fixes: one is arithmetic, the
            other is which invoice these subtotals belong to"
    (is (= :tax-basis-unreconciled
           (:shiwake/status
            (shiwake/entry-request (with-basis :tax 24001 :total 264001) mapping))))))

(deftest a-stated-figure-that-agrees-with-its-basis-is-both
  (testing "the strongest case: the issuer told us AND it reproduces.
            Collapsing it into :stated would throw the reproducibility away"
    (let [r (shiwake/entry-request (with-basis :tax 24000) mapping)]
      (is (= :ok (:shiwake/status r)))
      (is (= :stated-and-derived (:shiwake/tax-source r)))
      (is (= 24000 (:tax (:shiwake/tax-derivation r))))
      (is (= 3 (count (lines r)))))))

(deftest a-basis-that-fails-is-not-quietly-dropped-for-the-stated-figure
  (testing "using the handed-in figure and ignoring the basis would let an
            issuer believe its own arithmetic had been checked when it had
            not. A stated figure does not rescue a broken declaration"
    (let [r (shiwake/entry-request
             (bare-basis {:jurisdiction :jp :method :tax-exclusive
                          :subtotals {:standard 240000}}
                         :tax 24000)
             mapping)]
      (is (= :tax-not-derivable (:shiwake/status r)))
      (is (nil? (:shiwake/request r)))))
  (testing "and a MALFORMED stated figure is still refused as malformed,
            before anything is computed — deriving past it would replace a
            bad figure with a good one and never report it"
    (is (= :unusable-invoice
           (:shiwake/status (shiwake/entry-request (with-basis :tax "24000") mapping))))
    (is (= :unusable-invoice
           (:shiwake/status (shiwake/entry-request (with-basis :tax -1) mapping))))))

(deftest a-stated-absence-contradicted-by-a-basis-is-refused
  (testing ":none says there is no consumption tax component. A basis that
            computes ¥24,000 says there is. Both cannot be true"
    (is (= :tax-disagrees
           (:shiwake/status (shiwake/entry-request (with-basis :tax :none) mapping))))))

;; ---------------------------------------------------------------------------
;; outside Japan there is nothing here to compute
;; ---------------------------------------------------------------------------

(deftest no-tax-figure-is-derived-outside-japan-and-the-refusal-says-why
  (testing "施行令 第七十条の十 is Japanese. The EU catalog holds no
            Union-level analogue (Article 226(10) requires the amount to
            appear but fixes no rounding) and the United States has no
            federal consumption tax to compute. Both refuse, and the
            catalog's own reason rides along so an operator is not told
            `nobody read this` about a facet somebody read and left out"
    (doseq [[j pattern] [[:eu #"rounding method is not fixed"]
                         [:us #"no federal consumption tax"]]]
      (let [r (shiwake/entry-request (with-basis :jurisdiction j) mapping)]
        (is (= :tax-not-derivable (:shiwake/status r)) (str j))
        (is (= :jurisdiction/qualified-invoice-tax-amount
               (:taxlaw/out-of-scope (:shiwake/taxlaw r))) (str j))
        (is (re-find pattern (:shiwake/why r)) (str j)))))
  (testing "and a jurisdiction nobody catalogued gets no invented reason"
    (let [r (shiwake/entry-request (with-basis :jurisdiction :atlantis) mapping)]
      (is (= :tax-not-derivable (:shiwake/status r)))
      (is (nil? (:taxlaw/out-of-scope (:shiwake/taxlaw r))))))
  (testing "an EU invoice can still become a journal entry — the issuer
            just has to state the figure, because this actor cannot compute
            it there"
    (is (= :ok (:shiwake/status (shiwake/entry-request (issued :tax 24000) mapping))))))

;; ---------------------------------------------------------------------------
;; what travels, and what does not
;; ---------------------------------------------------------------------------

(deftest the-emitted-body-is-unchanged-by-where-the-figure-came-from
  (testing "this actor cannot change what 4311 accepts, so the provenance
            stays on the result map and never enters the request. A new key
            in a body whose acceptor is copied here rather than depended on
            is two green suites with an entry lost between them"
    (let [handed (:shiwake/request (shiwake/entry-request (issued :tax 24000) mapping))
          computed (:shiwake/request (shiwake/entry-request (with-basis) mapping))]
      (is (= handed computed))
      (is (= #{:op :source-doc :tax-treatment :lines} (set (keys computed))))
      (is (accepted-by-4311? computed)))))

(deftest a-batch-keeps-each-tax-refusal-under-its-own-name
  (testing "four different fixes: ask for a figure, finish the declaration,
            fix the grouping, resolve the contradiction. One queue for all
            four sends three of them to the wrong person"
    (let [b (shiwake/entry-requests
             [(with-basis)
              (issued :tax nil)
              (bare-basis {:jurisdiction :jp :subtotals {:standard 240000}})
              (with-basis :subtotals {:standard 100000})
              (with-basis :tax 24001)]
             mapping)]
      (is (= 1 (count (:ok b))))
      (is (= #{:tax-not-stated :tax-not-derivable :tax-basis-unreconciled :tax-disagrees}
             (set (map :shiwake/status (:skipped b)))))
      (is (every? :shiwake/issued (:skipped b)))
      (is (every? :shiwake/why (:skipped b))
          "a refusal nobody can read is a refusal nobody can act on"))))
