(ns tehai.shiwake-test
  "An issued invoice becoming a journal entry — and every way that hand-off
  can quietly lose revenue.

  The suite pins three things the expense-side equivalent (`keihi.shiwake`)
  cannot: that the entry runs the OTHER WAY (売掛金 debit, 売上 credit),
  that 消費税 is split out only from a figure somebody stated, and that the
  emitted body never names a client — `cloud-itonami-isco-4311` REJECTS a
  body carrying `:client-id` rather than ignoring it."
  (:require [clojure.test :refer [deftest is testing]]
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
                           [:shiwake/request :tax-treatment])))))

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
  (testing "and it reads nothing either — no store, no actor, no psa"
    (let [reqs (->> (read-string (slurp "src/tehai/shiwake.cljc"))
                    (filter list?)
                    (filter #(= :require (first %)))
                    first rest (map first) set)]
      (is (= #{'clojure.string} reqs)
          (str "shiwake must stay a pure value function; requires " (pr-str reqs))))))
