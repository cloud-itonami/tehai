(ns tehai.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.psa :as psa]
            [tehai.store :as store]
            [tehai.governor :as governor]))

(def ^:private day 86400000)
(def ^:private t0 1767225600000)
(defn- d [n] (+ t0 (* n day)))

(defn- ts [worker date project role hours & {:keys [approved?]}]
  {:ts/worker worker :ts/date date :ts/project project :ts/role role
   :ts/hours hours :ts/approved? (boolean approved?)})

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client/id "c-1" :client/name "Acme"})
    (store/register-client! st {:client/id "c-2" :client/name "Other"})
    (store/register-project! st {:project/id "alpha" :project/client "c-1"})
    (store/register-project! st {:project/id "zeta" :project/client "c-2"})
    (store/register-person! st {:person/id "w-1"})
    (store/register-rate-card! st (psa/rate-card "alpha" :engineer 15000 :cost 9000))
    (store/register-entry! st (ts "w-1" "2026-01-01" "alpha" :engineer 8 :approved? true))
    (store/register-entry! st (ts "w-1" "2026-01-02" "alpha" :engineer 8 :approved? true))
    st))

(def ^:private approved-keys
  [(psa/entry-key (ts "w-1" "2026-01-01" "alpha" :engineer 8))
   (psa/entry-key (ts "w-1" "2026-01-02" "alpha" :engineer 8))])

(defn- clean-proposal []
  {:op :draft-invoice :effect :propose :project "alpha"
   :entry-keys approved-keys :total 240000 :margin 96000 :confidence 0.9})

(defn- check [request proposal store] (governor/check request {} proposal store))

;; ---------------------------------------------------------------------------
;; Baseline
;; ---------------------------------------------------------------------------

(deftest ok-on-a-clean-redraftable-invoice
  (let [v (check {:client-id "c-1"} (clean-proposal) (fresh-store))]
    (is (:ok? v))
    (is (not (:hard? v)))))

(deftest hard-on-unregistered-client
  (let [v (check {:client-id "nobody"} (clean-proposal) (fresh-store))]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [v (check {:client-id "c-1"} (assoc (clean-proposal) :effect :direct-write) (fresh-store))]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

;; ---------------------------------------------------------------------------
;; Project provenance
;; ---------------------------------------------------------------------------

(deftest hard-on-an-unregistered-project
  (let [v (check {:client-id "c-1"} (assoc (clean-proposal) :project "ghost") (fresh-store))]
    (is (:hard? v))
    (is (some #(= :unknown-project (:rule %)) (:violations v)))))

(deftest hard-on-billing-one-client-for-anothers-project
  (let [v (check {:client-id "c-1"} (assoc (clean-proposal) :project "zeta") (fresh-store))]
    (is (:hard? v))
    (is (some #(= :project-wrong-client (:rule %)) (:violations v)))))

;; ---------------------------------------------------------------------------
;; Unpriced / unapproved / double-billed
;; ---------------------------------------------------------------------------

(deftest hard-on-invoicing-unpriced-time
  (let [st (fresh-store)
        e (ts "w-1" "2026-01-03" "alpha" :architect 8 :approved? true)]
    (store/register-entry! st e)
    (let [v (check {:client-id "c-1"}
                   (assoc (clean-proposal) :entry-keys (conj approved-keys (psa/entry-key e)))
                   st)]
      (testing "no rate card for :architect — held, not billed at zero"
        (is (:hard? v))
        (is (some #(= :unpriced-time (:rule %)) (:violations v)))))))

(deftest hard-on-invoicing-unapproved-time
  (let [st (fresh-store)
        e (ts "w-1" "2026-01-03" "alpha" :engineer 8)]  ;; not approved
    (store/register-entry! st e)
    (let [v (check {:client-id "c-1"}
                   (assoc (clean-proposal)
                          :entry-keys (conj approved-keys (psa/entry-key e))
                          :total 360000)
                   st)]
      (is (:hard? v))
      (is (some #(= :unapproved-time (:rule %)) (:violations v))))))

(deftest hard-on-billing-the-same-hours-twice
  (let [st (fresh-store)]
    (store/commit-invoice! st {:invoice/id "inv-1" :invoice/project "alpha"
                               :invoice/total 240000 :invoice/entries approved-keys})
    (let [v (check {:client-id "c-1"} (clean-proposal) st)]
      (is (:hard? v))
      (is (some #(= :double-billing (:rule %)) (:violations v))))))

;; ---------------------------------------------------------------------------
;; Arithmetic integrity
;; ---------------------------------------------------------------------------

(deftest hard-when-the-total-does-not-match-a-redraft
  (testing "an advisor inflating the invoice is held, not corrected"
    (let [v (check {:client-id "c-1"} (assoc (clean-proposal) :total 900000) (fresh-store))]
      (is (:hard? v))
      (is (some #(= :total-mismatch (:rule %)) (:violations v))))))

(deftest hard-when-a-margin-is-stated-over-missing-cost-data
  (let [st (store/mem-store)]
    (store/register-client! st {:client/id "c-1"})
    (store/register-project! st {:project/id "beta" :project/client "c-1"})
    ;; billable rate but no cost rate
    (store/register-rate-card! st (psa/rate-card "beta" :engineer 10000))
    (store/register-entry! st (ts "w-1" "2026-01-01" "beta" :engineer 10 :approved? true))
    (let [k [(psa/entry-key (ts "w-1" "2026-01-01" "beta" :engineer 10))]
          v (check {:client-id "c-1"}
                   {:op :draft-invoice :effect :propose :project "beta"
                    :entry-keys k :total 100000 :margin 100000 :confidence 0.9}
                   st)]
      (testing "revenue substituted for margin reads as pure profit"
        (is (:hard? v))
        (is (some #(= :fabricated-margin (:rule %)) (:violations v)))))
    (testing ":unknown is the admissible answer"
      (let [k [(psa/entry-key (ts "w-1" "2026-01-01" "beta" :engineer 10))]
            v (check {:client-id "c-1"}
                     {:op :draft-invoice :effect :propose :project "beta"
                      :entry-keys k :total 100000 :margin :unknown :confidence 0.9}
                     st)]
        (is (:ok? v))))))

;; ---------------------------------------------------------------------------
;; Staffing
;; ---------------------------------------------------------------------------

(deftest hard-on-assigning-past-declared-capacity
  (let [st (fresh-store)]
    (store/register-capacity! st (psa/capacity "w-1" (d 0) (d 5) 40))
    (store/commit-assignment! st (psa/assignment "a-1" "w-1" "alpha" :engineer (d 0) (d 5) 30))
    (let [v (check {:client-id "c-1"}
                   {:op :assign-person :effect :propose :project "alpha" :confidence 0.9
                    :assignment (psa/assignment "a-2" "w-1" "alpha" :engineer (d 0) (d 5) 20)}
                   st)]
      (is (:hard? v))
      (is (some #(= :over-allocation (:rule %)) (:violations v))))))

(deftest an-undeclared-capacity-is-not-an-over-allocation
  (testing "absent capacity is unknown, not exceeded — unlike absent price,
            which is a refusal to guess"
    (let [v (check {:client-id "c-1"}
                   {:op :assign-person :effect :propose :project "alpha" :confidence 0.9
                    :assignment (psa/assignment "a-1" "w-1" "alpha" :engineer (d 0) (d 5) 500)}
                   (fresh-store))]
      (is (:ok? v)))))

;; ---------------------------------------------------------------------------
;; Escalation
;; ---------------------------------------------------------------------------

(deftest issuing-an-invoice-always-escalates
  (let [v (check {:client-id "c-1"} (assoc (clean-proposal) :op :issue-invoice) (fresh-store))]
    (is (not (:hard? v)))
    (is (:escalate? v))
    (is (not (:ok? v)))))

(deftest escalate-on-low-confidence
  (let [v (check {:client-id "c-1"} (assoc (clean-proposal) :confidence 0.2) (fresh-store))]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest a-hard-violation-outranks-escalation
  (let [v (check {:client-id "c-1"}
                 (assoc (clean-proposal) :op :issue-invoice :total 1)
                 (fresh-store))]
    (is (:hard? v))
    (is (not (:escalate? v)))))
