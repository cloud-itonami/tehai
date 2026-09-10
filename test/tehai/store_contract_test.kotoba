(ns tehai.store-contract-test
  "MemStore ≡ DatomicStore for the tehai store.

  Every assertion runs against BOTH backends. The billed set is the
  reason this matters most here: it is what makes `:double-billing` a
  hard hold rather than an awkward conversation with a client, and a
  backend that answered it differently would quietly turn that hold off."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.psa :as psa]
            [tehai.store :as store]
            [tehai.actor :as actor]
            [tehai.governor :as governor]))

(def ^:private day 86400000)
(def ^:private t0 1767225600000)
(defn- d [n] (+ t0 (* n day)))

(defn- ts [worker date project role hours & {:keys [approved?]}]
  {:ts/worker worker :ts/date date :ts/project project :ts/role role
   :ts/hours hours :ts/approved? (boolean approved?)})

(def backends {:mem store/mem-store :datomic store/datomic-store})

(defn- seeded [make]
  (let [st (make)]
    (store/register-client! st {:client/id "c-1" :client/name "Acme"})
    (store/register-project! st {:project/id "alpha" :project/client "c-1"})
    (store/register-person! st {:person/id "w-1"})
    (store/register-rate-card! st (psa/rate-card "alpha" :engineer 15000 :cost 9000))
    (store/register-entry! st (ts "w-1" "2026-01-01" "alpha" :engineer 8 :approved? true))
    (store/register-entry! st (ts "w-1" "2026-01-02" "alpha" :engineer 8 :approved? true))
    st))

(defn- each-backend [f]
  (doseq [[label make] backends]
    (testing (str "backend " label) (f (seeded make)))))

;; ---------------------------------------------------------------------------
;; Directories
;; ---------------------------------------------------------------------------

(deftest clients-projects-and-people-read-back
  (each-backend
   (fn [st]
     (is (= "Acme" (:client/name (store/client st "c-1"))))
     (is (nil? (store/client st "nobody")))
     (is (= "c-1" (:project/client (store/project-of st "alpha"))))
     (is (some? (store/person st "w-1"))))))

(deftest projects-are-scoped-to-their-client
  (each-backend
   (fn [st]
     (store/register-project! st {:project/id "zeta" :project/client "c-2"})
     (is (= ["alpha"] (mapv :project/id (store/projects-of st "c-1"))))
     (is (= ["zeta"] (mapv :project/id (store/projects-of st "c-2")))))))

(deftest re-registering-a-rate-replaces-it-rather-than-shadowing-it
  (testing "two live cards for one role would make an invoice total depend on
            insertion order"
    (each-backend
     (fn [st]
       (store/register-rate-card! st (psa/rate-card "alpha" :engineer 20000 :cost 9000))
       (let [cards (store/rate-cards st)]
         (is (= 1 (count (filter #(and (= "alpha" (:rate/project %))
                                       (= :engineer (:rate/role %)))
                                 cards))))
         (is (= 20000 (:rate/billable (psa/rate-for cards "alpha" :engineer)))))))))

(deftest re-importing-the-same-timesheet-entry-upserts
  (each-backend
   (fn [st]
     (store/register-entry! st (ts "w-1" "2026-01-01" "alpha" :engineer 8 :approved? true))
     (is (= 2 (count (store/entries st)))))))

;; ---------------------------------------------------------------------------
;; The billed set
;; ---------------------------------------------------------------------------

(def ^:private keys-of
  [(psa/entry-key (ts "w-1" "2026-01-01" "alpha" :engineer 8))
   (psa/entry-key (ts "w-1" "2026-01-02" "alpha" :engineer 8))])

(deftest committing-an-invoice-adds-its-entries-to-the-billed-set
  (each-backend
   (fn [st]
     (is (empty? (store/billed-keys st)))
     (store/commit-invoice! st {:invoice/id "inv-1" :invoice/project "alpha"
                                :invoice/total 240000 :invoice/entries keys-of})
     (is (= (set keys-of) (store/billed-keys st)))
     (is (= 1 (count (store/invoices st)))))))

(deftest two-invoices-accumulate-into-one-billed-set
  (each-backend
   (fn [st]
     (store/commit-invoice! st {:invoice/id "inv-1" :invoice/entries [(first keys-of)]})
     (store/commit-invoice! st {:invoice/id "inv-2" :invoice/entries [(second keys-of)]})
     (is (= (set keys-of) (store/billed-keys st))))))

;; ---------------------------------------------------------------------------
;; Streams
;; ---------------------------------------------------------------------------

(deftest the-ledger-is-append-only-and-ordered
  (each-backend
   (fn [st]
     (doseq [n (range 5)] (store/append-ledger! st {:disposition :hold :n n}))
     (is (= [0 1 2 3 4] (mapv :n (store/ledger st)))))))

(deftest records-are-filtered-by-client
  (each-backend
   (fn [st]
     (store/commit-record! st {:client-id "c-1" :op :draft-invoice})
     (store/commit-record! st {:client-id "c-2" :op :draft-invoice})
     (is (= 1 (count (store/records-of st "c-1"))))
     (is (= 1 (count (store/records-of st "c-2")))))))

(deftest assignments-and-capacities-round-trip
  (each-backend
   (fn [st]
     (store/register-capacity! st (psa/capacity "w-1" (d 0) (d 5) 40))
     (store/commit-assignment! st (psa/assignment "a-1" "w-1" "alpha" :engineer (d 0) (d 5) 30))
     (is (= 40 (:capacity/hours (first (store/capacities st)))))
     (is (= "a-1" (:assign/id (first (store/assignments st)))))
     (testing "and psa/allocation reads them identically on either backend"
       (let [al (psa/allocation (store/assignments st) (store/capacities st)
                                "w-1" [(d 0) (d 5)])]
         (is (= 30.0 (:allocation/committed-hours al)))
         (is (not (:allocation/over? al))))))))

;; ---------------------------------------------------------------------------
;; The actor runs unchanged on either backend
;; ---------------------------------------------------------------------------

(deftest issuing-then-re-issuing-behaves-identically-on-both-backends
  (let [run (fn [make]
              (let [st (seeded make)
                    g (actor/build-graph {:store st})
                    _ (actor/run-request! g {:client-id "c-1" :op :issue-invoice
                                             :project "alpha" :invoice-id "inv-1"} {} "t-1")
                    _ (actor/approve! g "t-1")
                    second-run (actor/run-request! g {:client-id "c-1" :op :issue-invoice
                                                      :project "alpha" :invoice-id "inv-2"}
                                                   {} "t-2")]
                {:billed (count (store/billed-keys st))
                 :invoices (count (store/invoices st))
                 :second-total (get-in second-run [:state :proposal :total])
                 :ledger (mapv :disposition (store/ledger st))}))]
    (is (= (run store/mem-store) (run store/datomic-store)))
    (testing "and the shared answer is that the hours are billed exactly once"
      (is (= {:billed 2 :invoices 1 :second-total 0 :ledger [:commit]}
             (run store/datomic-store))))))

;; ---------------------------------------------------------------------------
;; Expenses, subcontractors, FX rates and revenue contracts
;;
;; Added in the iteration that gave tehai its cost and currency ops.
;; These store methods went in without contract coverage.
;; ---------------------------------------------------------------------------

(deftest an-expense-round-trips-with-its-billable-flag-and-markup
  (each-backend
   (fn [st]
     (let [e (psa/expense "e-1" "alpha" 20000 "USD" :billable? true :markup 0.1
                          :category :travel :incurred-by "w-1")]
       (store/record-expense! st e)
       (let [back (first (store/expenses st))]
         (is (= e back))
         (testing "the flag that decides whether a client pays survives the blob"
           (is (true? (:expense/billable? back)))
           (is (= 22000.0 (psa/expense-billable-amount back)))))))))

(deftest a-non-billable-expense-stays-non-billable-across-the-round-trip
  (testing "false is a value, not an absence — a flag that read back as nil
            would silently start billing the client for absorbed costs"
    (each-backend
     (fn [st]
       (store/record-expense! st (psa/expense "e-1" "alpha" 20000 "USD" :billable? false))
       (let [back (first (store/expenses st))]
         (is (false? (:expense/billable? back)))
         (is (zero? (psa/expense-billable-amount back))))))))

(deftest re-recording-an-expense-id-replaces-it
  (each-backend
   (fn [st]
     (store/record-expense! st (psa/expense "e-1" "alpha" 20000 "USD" :billable? true))
     (store/record-expense! st (psa/expense "e-1" "alpha" 30000 "USD" :billable? true))
     (is (= 1 (count (store/expenses st))))
     (is (= 30000 (:expense/amount (first (store/expenses st))))))))

(deftest a-subcontract-round-trips-with-both-rates
  (each-backend
   (fn [st]
     (let [x (psa/subcontract "s-1" "alpha" "vendor-a" :engineer 10 8000 14000 "USD")]
       (store/record-subcontract! st x)
       (let [back (first (store/subcontracts st))]
         (is (= x back))
         (testing "cost and bill rate are separate negotiations and must both survive"
           (is (= 80000 (:sub/cost (psa/subcontract-margin back))))
           (is (= 140000 (:sub/billable (psa/subcontract-margin back))))))))))

(deftest an-fx-rate-round-trips-and-stays-directional
  (each-backend
   (fn [st]
     (store/register-fx-rate! st (psa/fx-rate "EUR" "USD" 1.08 "2026-01-01"))
     (let [rates (store/fx-rates st)]
       (is (= 1080.0 (:money/amount (psa/convert rates 1000 "EUR" "USD"))))
       (testing "the reverse direction is still absent — storing one rate must
                 not manufacture its inverse"
         (is (:money/unconvertible? (psa/convert rates 1000 "USD" "EUR"))))))))

(deftest re-registering-an-fx-pair-replaces-it
  (testing "two live rates for one pair would make an invoice total depend on
            which one psa/convert happened to see first"
    (each-backend
     (fn [st]
       (store/register-fx-rate! st (psa/fx-rate "EUR" "USD" 1.08 "2026-01-01"))
       (store/register-fx-rate! st (psa/fx-rate "EUR" "USD" 1.12 "2026-02-01"))
       (is (= 1 (count (store/fx-rates st))))
       (is (= 1120.0 (:money/amount (psa/convert (store/fx-rates st) 1000 "EUR" "USD"))))))))

(deftest a-revenue-contract-round-trips-and-is-scoped-to-its-project
  (each-backend
   (fn [st]
     (store/register-revenue-contract!
      st (psa/contract "alpha" :percent-complete :fee 1000000 :budget-hours 100))
     (let [c (store/revenue-contract st "alpha")]
       (is (= :percent-complete (:contract/method c)))
       (is (= 100 (:contract/budget-hours c))))
     (is (nil? (store/revenue-contract st "beta"))))))

(deftest re-registering-a-projects-contract-replaces-it
  (each-backend
   (fn [st]
     (store/register-revenue-contract! st (psa/contract "alpha" :on-completion :fee 500000))
     (store/register-revenue-contract!
      st (psa/contract "alpha" :percent-complete :fee 1000000 :budget-hours 100))
     (is (= :percent-complete (:contract/method (store/revenue-contract st "alpha")))))))

(deftest an-empty-store-answers-empty-for-the-new-collections
  (doseq [[label make] backends]
    (let [st (make)]
      (testing (str "backend " label)
        (is (empty? (store/expenses st)))
        (is (empty? (store/subcontracts st)))
        (is (empty? (store/fx-rates st)))
        (is (nil? (store/revenue-contract st "alpha")))))))

(deftest the-incomplete-total-hold-fires-identically-on-both-backends
  (testing "the rule with no approval route must not depend on which store
            the deployment happens to run"
    (let [run (fn [make]
                (let [st (seeded make)
                      _ (store/record-expense!
                         st (psa/expense "e-1" "alpha" 50000 "JPY" :billable? true))
                      c (governor/total-completeness st "alpha")]
                  {:complete? (:complete? c)
                   :unconvertible (:unconvertible c)
                   :currency (:currency c)}))]
      (is (= (run store/mem-store) (run store/datomic-store)))
      (is (= {:complete? false :unconvertible [[:expense "e-1"]] :currency "USD"}
             (run store/datomic-store))))))
