(ns tehai.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.psa :as psa]
            [tehai.advisor :as advisor]
            [tehai.store :as store]
            [tehai.actor :as actor]))

(defn- ts [worker date project role hours & {:keys [approved?]}]
  {:ts/worker worker :ts/date date :ts/project project :ts/role role
   :ts/hours hours :ts/approved? (boolean approved?)})

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client/id "c-1" :client/name "Acme"})
    (store/register-project! st {:project/id "alpha" :project/client "c-1"})
    (store/register-person! st {:person/id "w-1"})
    (store/register-rate-card! st (psa/rate-card "alpha" :engineer 15000 :cost 9000))
    (store/register-entry! st (ts "w-1" "2026-01-01" "alpha" :engineer 8 :approved? true))
    (store/register-entry! st (ts "w-1" "2026-01-02" "alpha" :engineer 8 :approved? true))
    st))

(defn- disposition [r] (get-in r [:state :disposition]))
(defn- proposal [r] (get-in r [:state :proposal]))

;; ---------------------------------------------------------------------------
;; Drafting
;; ---------------------------------------------------------------------------

(deftest a-draft-commits-without-locking-the-hours-away
  (let [st (fresh-store)
        g  (actor/build-graph {:store st})
        r  (actor/run-request! g {:client-id "c-1" :op :draft-invoice
                                  :project "alpha" :invoice-id "inv-1"}
                               {} "t-draft")]
    (is (= :done (:status r)))
    (is (= :commit (disposition r)))
    (is (= 240000 (:total (proposal r))))
    (is (= 96000 (:margin (proposal r))))
    (testing "a draft that is never issued must not bill its hours"
      (is (empty? (store/billed-keys st)))
      (is (empty? (store/invoices st))))))

(deftest a-draft-reports-what-it-could-not-bill
  (let [st (fresh-store)]
    (store/register-entry! st (ts "w-1" "2026-01-03" "alpha" :architect 8 :approved? true))
    (let [g (actor/build-graph {:store st})
          r (actor/run-request! g {:client-id "c-1" :op :draft-invoice
                                   :project "alpha" :invoice-id "inv-1"}
                                {} "t-gap")]
      (testing "the advisor excludes unpriced time itself, and says so"
        (is (= :commit (disposition r)))
        (is (= 240000 (:total (proposal r))))
        (is (= "invoice drafted; nothing left out" (:rationale (proposal r))))))))

(deftest unapproved-time-never-reaches-a-draft
  (let [st (fresh-store)]
    (store/register-entry! st (ts "w-1" "2026-01-03" "alpha" :engineer 8))  ;; unapproved
    (let [g (actor/build-graph {:store st})
          r (actor/run-request! g {:client-id "c-1" :op :draft-invoice
                                   :project "alpha" :invoice-id "inv-1"}
                                {} "t-unapproved")]
      (is (= 240000 (:total (proposal r))))
      (is (= 2 (count (:entry-keys (proposal r))))))))

;; ---------------------------------------------------------------------------
;; Issuing
;; ---------------------------------------------------------------------------

(deftest issuing-waits-for-a-human-then-locks-the-hours
  (let [st (fresh-store)
        g  (actor/build-graph {:store st})
        interrupted (actor/run-request! g {:client-id "c-1" :op :issue-invoice
                                           :project "alpha" :invoice-id "inv-1"}
                                        {} "t-issue")]
    (is (= :interrupted (:status interrupted)))
    (testing "nothing is billed while the thread waits"
      (is (empty? (store/billed-keys st))))
    (let [resumed (actor/approve! g "t-issue")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/invoices st))))
      (is (= 2 (count (store/billed-keys st)))))))

(deftest the-same-hours-cannot-be-issued-twice
  (let [st (fresh-store)
        g  (actor/build-graph {:store st})]
    (actor/run-request! g {:client-id "c-1" :op :issue-invoice
                           :project "alpha" :invoice-id "inv-1"} {} "t-1")
    (actor/approve! g "t-1")
    (testing "the second run finds nothing left to bill, so the total is zero"
      (let [r (actor/run-request! g {:client-id "c-1" :op :issue-invoice
                                     :project "alpha" :invoice-id "inv-2"} {} "t-2")]
        (is (zero? (:total (proposal r))))
        (is (empty? (:entry-keys (proposal r))))))
    (testing "and an advisor that insists on the old keys is held outright"
      (let [dishonest (reify advisor/Advisor
                        (-advise [_ _ _]
                          {:op :draft-invoice :effect :propose :project "alpha"
                           :entry-keys (vec (store/billed-keys st))
                           :total 240000 :confidence 0.9}))
            g2 (actor/build-graph {:store st :advisor dishonest})
            r (actor/run-request! g2 {:client-id "c-1" :op :draft-invoice
                                      :project "alpha"} {} "t-3")]
        (is (= :hold (disposition r)))
        (is (some #(= :double-billing (:rule %))
                  (get-in r [:state :verdict :violations])))))))

;; ---------------------------------------------------------------------------
;; Margin
;; ---------------------------------------------------------------------------

(deftest a-project-with-no-cost-rate-reports-unknown-margin-not-profit
  (let [st (store/mem-store)]
    (store/register-client! st {:client/id "c-1"})
    (store/register-project! st {:project/id "beta" :project/client "c-1"})
    (store/register-rate-card! st (psa/rate-card "beta" :engineer 10000))
    (store/register-entry! st (ts "w-1" "2026-01-01" "beta" :engineer 10 :approved? true))
    (let [g (actor/build-graph {:store st})
          r (actor/run-request! g {:client-id "c-1" :op :report-margin :project "beta"}
                                {} "t-margin")]
      (is (= :unknown (:margin (proposal r))))
      (is (= 100000 (get-in (proposal r) [:margin-detail :margin/revenue])))
      (is (= 1 (get-in (proposal r) [:margin-detail :margin/uncosted-count]))))))

;; ---------------------------------------------------------------------------
;; The unconditional invariant
;; ---------------------------------------------------------------------------

(deftest the-advisor-cannot-commit-what-the-governor-refuses
  (doseq [[label request]
          [["unregistered client" {:client-id "ghost" :op :draft-invoice :project "alpha"}]
           ["another client's project" {:client-id "c-1" :op :draft-invoice :project "zeta"}]]]
    (let [st (fresh-store)
          _ (store/register-project! st {:project/id "zeta" :project/client "c-2"})
          g (actor/build-graph {:store st})
          r (actor/run-request! g request {} (str "t-" (hash label)))]
      (is (= :hold (disposition r)) label)
      (is (empty? (store/records-of st (:client-id request))) label)
      (is (empty? (store/billed-keys st)) label)
      (is (= [:hold] (mapv :disposition (store/ledger st))) label))))
