(ns tehai.cost-revenue-test
  "Expenses, subcontractors, revenue recognition and multi-currency as
  governed ops.

  The rule worth having here is the currency one: an invoice whose total
  could not price one of its components is wrong by an unknown factor,
  and no approval makes an unconverted currency converted."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.psa :as psa]
            [tehai.store :as store]
            [tehai.actor :as actor]
            [tehai.governor :as governor]))

(defn- ts [worker date project role hours & {:keys [approved?]}]
  {:ts/worker worker :ts/date date :ts/project project :ts/role role
   :ts/hours hours :ts/approved? (boolean approved?)})

(defn- fresh []
  (let [st (store/mem-store)]
    (store/register-client! st {:client/id "c-1" :client/name "Acme"})
    (store/register-project! st {:project/id "alpha" :project/client "c-1"})
    (store/register-rate-card! st (psa/rate-card "alpha" :engineer 15000 :cost 9000))
    (store/register-entry! st (ts "w-1" "2026-01-01" "alpha" :engineer 10 :approved? true))
    st))

(defn- check [st proposal] (governor/check {:client-id "c-1"} {} proposal st))
(defn- disposition [r] (get-in r [:state :disposition]))
(defn- proposal [r] (get-in r [:state :proposal]))

;; ---------------------------------------------------------------------------
;; Recording a cost is never gated on convertibility
;; ---------------------------------------------------------------------------

(deftest a-foreign-currency-expense-records-even-with-no-fx-rate
  (testing "the cost was incurred either way; refusing to record it erases it"
    (let [st (fresh)
          g (actor/build-graph {:store st})
          e (psa/expense "e-1" "alpha" 50000 "JPY" :billable? true)
          r (actor/run-request! g {:client-id "c-1" :op :record-expense
                                   :project "alpha" :expense e} {} "t-1")]
      (is (= :commit (disposition r)))
      (is (= 1 (count (store/expenses st))))
      (testing "no JPY→USD rate exists, and that did not stop the record"
        (is (empty? (store/fx-rates st)))))))

(deftest an-expense-against-an-unregistered-project-is-held
  (let [v (check (fresh) {:op :record-expense :effect :propose :project "ghost"
                          :expense (psa/expense "e-1" "ghost" 100 "USD") :confidence 0.9})]
    (is (:hard? v))
    (is (some #(= :unknown-project (:rule %)) (:violations v)))))

(deftest a-subcontractor-records-with-both-rates
  (let [st (fresh)
        g (actor/build-graph {:store st})
        x (psa/subcontract "s-1" "alpha" "vendor-a" :engineer 10 8000 14000 "USD")
        r (actor/run-request! g {:client-id "c-1" :op :record-subcontract
                                 :project "alpha" :subcontract x} {} "t-1")]
    (is (= :commit (disposition r)))
    (is (= 1 (count (store/subcontracts st))))))

;; ---------------------------------------------------------------------------
;; The rule: an incomplete total may not be issued
;; ---------------------------------------------------------------------------

(defn- issue-proposal [st]
  (let [items (filter :ts/approved? (store/entries st))
        draft (psa/invoice "inv-1" "alpha" items (store/rate-cards st) (store/billed-keys st))]
    {:op :issue-invoice :effect :propose :project "alpha"
     :entry-keys (:invoice/entries draft) :total (:invoice/total draft)
     :margin :unknown :confidence 0.9}))

(deftest an-unconvertible-billable-expense-blocks-issuing
  (let [st (fresh)
        _ (store/record-expense! st (psa/expense "e-1" "alpha" 50000 "JPY" :billable? true))
        v (check st (issue-proposal st))]
    (is (:hard? v))
    (is (some #(= :incomplete-total (:rule %)) (:violations v)))
    (testing "and it is a HOLD — no approval makes an unconverted currency converted"
      (is (not (:escalate? v))))))

(deftest a-declared-fx-rate-unblocks-it
  (let [st (fresh)
        _ (store/record-expense! st (psa/expense "e-1" "alpha" 50000 "JPY" :billable? true))
        _ (store/register-fx-rate! st (psa/fx-rate "JPY" "USD" 0.0064 "2026-01-01"))
        v (check st (issue-proposal st))]
    (is (not (:hard? v)))
    (testing "issuing still escalates — money is still leaving the system"
      (is (:escalate? v)))))

(deftest a-non-billable-foreign-expense-does-not-block-issuing
  (testing "it never reaches the invoice, so it cannot make the total wrong"
    (let [st (fresh)
          _ (store/record-expense! st (psa/expense "e-1" "alpha" 50000 "JPY" :billable? false))
          v (check st (issue-proposal st))]
      (is (not (:hard? v))))))

(deftest an-unconvertible-subcontractor-blocks-issuing
  (let [st (fresh)
        _ (store/record-subcontract! st (psa/subcontract "s-1" "alpha" "v" :engineer 10 800 1400 "EUR"))
        v (check st (issue-proposal st))]
    (is (:hard? v))
    (is (some #(= :incomplete-total (:rule %)) (:violations v)))))

(deftest drafting-is-not-blocked-by-an-incomplete-total
  (testing "a draft is not a commitment; the gate is on issuing"
    (let [st (fresh)
          _ (store/record-expense! st (psa/expense "e-1" "alpha" 50000 "JPY" :billable? true))
          v (check st (assoc (issue-proposal st) :op :draft-invoice))]
      (is (not (:hard? v))))))

(deftest an-ambiguous-invoice-currency-blocks-issuing
  (testing "two rate cards in different currencies leave no single answer to
            'what is this invoice denominated in'"
    (let [st (fresh)
          _ (store/register-rate-card! st (psa/rate-card "alpha" :designer 900 :cost 500
                                                         :currency "EUR"))
          v (check st (issue-proposal st))]
      (is (:hard? v))
      (is (some #(= :incomplete-total (:rule %)) (:violations v))))))

(deftest total-completeness-names-what-it-could-not-price
  (let [st (fresh)
        _ (store/record-expense! st (psa/expense "e-1" "alpha" 50000 "JPY" :billable? true))
        c (governor/total-completeness st "alpha")]
    (is (not (:complete? c)))
    (is (= [[:expense "e-1"]] (:unconvertible c)))
    (is (= "USD" (:currency c)))))

;; ---------------------------------------------------------------------------
;; Margin now covers all three cost sources
;; ---------------------------------------------------------------------------

(deftest report-margin-shows-where-the-money-went
  (let [st (fresh)
        _ (store/record-expense! st (psa/expense "e-1" "alpha" 20000 "USD"
                                                 :billable? true :markup 0.1))
        _ (store/record-subcontract! st (psa/subcontract "s-1" "alpha" "v" :engineer 10 8000 14000 "USD"))
        g (actor/build-graph {:store st})
        r (actor/run-request! g {:client-id "c-1" :op :report-margin :project "alpha"} {} "t-1")
        d (:margin-detail (proposal r))]
    (is (= 312000.0 (:margin/revenue d)))            ;; 150000 labour + 22000 expense + 140000 sub
    (is (= 190000 (:margin/cost d)))
    (is (= 22000.0 (:margin/expense-revenue d)))
    (is (= 140000 (:margin/subcontract-revenue d)))))

(deftest invariant-2-still-holds-across-all-three-sources
  (testing "one uncosted labour entry and the whole project margin is :unknown,
            expenses and subcontracts notwithstanding"
    (let [st (fresh)
          _ (store/register-project! st {:project/id "beta" :project/client "c-1"})
          _ (store/register-rate-card! st (psa/rate-card "beta" :engineer 10000))   ;; no cost
          _ (store/register-entry! st (ts "w-1" "2026-01-01" "beta" :engineer 10 :approved? true))
          _ (store/record-expense! st (psa/expense "e-1" "beta" 100 "USD" :billable? true))
          g (actor/build-graph {:store st})
          r (actor/run-request! g {:client-id "c-1" :op :report-margin :project "beta"} {} "t-1")]
      (is (= :unknown (:margin (proposal r))))
      (is (= 100 (get-in (proposal r) [:margin-detail :margin/expense-revenue]))))))

;; ---------------------------------------------------------------------------
;; Revenue recognition
;; ---------------------------------------------------------------------------

(deftest percent-complete-recognises-against-a-registered-contract
  (let [st (fresh)
        _ (store/register-revenue-contract!
           st (psa/contract "alpha" :percent-complete :fee 1000000 :budget-hours 100))
        g (actor/build-graph {:store st})
        r (actor/run-request! g {:client-id "c-1" :op :recognize-revenue :project "alpha"} {} "t-1")]
    (is (= 0.1 (get-in (proposal r) [:revenue :revenue/progress])))
    (is (= 100000.0 (get-in (proposal r) [:revenue :revenue/amount])))))

(deftest with-no-contract-the-proposal-says-so-rather-than-guessing
  (let [st (fresh)
        g (actor/build-graph {:store st})
        r (actor/run-request! g {:client-id "c-1" :op :recognize-revenue :project "alpha"} {} "t-1")]
    (is (nil? (:revenue (proposal r))))
    (is (= "no revenue contract registered for this project" (:rationale (proposal r))))))

(deftest on-completion-recognises-nothing-until-it-is-done
  (let [st (fresh)
        _ (store/register-revenue-contract! st (psa/contract "alpha" :on-completion :fee 500000))
        g (actor/build-graph {:store st})
        before (actor/run-request! g {:client-id "c-1" :op :recognize-revenue
                                      :project "alpha"} {} "t-1")
        after (actor/run-request! g {:client-id "c-1" :op :recognize-revenue
                                     :project "alpha" :complete? true} {} "t-2")]
    (is (zero? (get-in (proposal before) [:revenue :revenue/amount])))
    (is (= 500000 (get-in (proposal after) [:revenue :revenue/amount])))))
