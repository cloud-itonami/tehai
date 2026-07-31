(ns tehai.edge.endpoints-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.psa :as psa]
            [tehai.store :as store]
            [tehai.edge.endpoints :as edge]))

(defn- ts [worker date project role hours & {:keys [approved?]}]
  {:ts/worker worker :ts/date date :ts/project project :ts/role role
   :ts/hours hours :ts/approved? (boolean approved?)})

(def ^:private allowlist {"did:key:zAcme" "c-1" "did:key:zOther" "c-2"})

(defn- seeded []
  (let [st (store/mem-store)]
    (store/register-client! st {:client/id "c-1" :client/name "Acme"})
    (store/register-client! st {:client/id "c-2" :client/name "Other"})
    (store/register-project! st {:project/id "alpha" :project/client "c-1"})
    (store/register-rate-card! st (psa/rate-card "alpha" :engineer 15000 :cost 9000))
    (store/register-entry! st (ts "w-1" "2026-01-01" "alpha" :engineer 8 :approved? true))
    (store/register-entry! st (ts "w-1" "2026-01-02" "alpha" :engineer 8 :approved? true))
    st))

(deftest an-absent-allowlist-serves-503-not-an-open-endpoint
  (is (= 503 (:status (edge/draft-invoice-core! (seeded) nil "did:key:zAcme"
                                                (pr-str {:project "alpha"}))))))

(deftest an-unlisted-caller-is-refused
  (is (= 403 (:status (edge/draft-invoice-core! (seeded) allowlist "did:key:zMallory"
                                                (pr-str {:project "alpha"}))))))

(deftest the-client-comes-from-the-did-so-cross-client-drafting-is-blocked
  (testing "zOther is c-2; alpha belongs to c-1"
    (let [r (edge/draft-invoice-core! (seeded) allowlist "did:key:zOther"
                                      (pr-str {:project "alpha"}))]
      (is (= 409 (:status r)))
      (is (some #(= :project-wrong-client (:rule %)) (get-in r [:body :violations]))))))

(deftest a-body-with-no-project-is-400
  (doseq [bad ["" "((" "{}" "{:project \"\"}" "[1 2]"]]
    (is (= 400 (:status (edge/draft-invoice-core! (seeded) allowlist "did:key:zAcme" bad)))
        (str "should reject " (pr-str bad)))))

(deftest a-clean-draft-returns-total-lines-and-what-it-could-not-bill
  (let [r (edge/draft-invoice-core! (seeded) allowlist "did:key:zAcme"
                                    (pr-str {:project "alpha" :invoice-id "inv-1"}))]
    (is (= 200 (:status r)))
    (is (= 240000 (get-in r [:body :total])))
    (is (= 1 (count (get-in r [:body :lines]))))
    (is (= 96000 (get-in r [:body :margin])))
    (is (= "invoice drafted; nothing left out" (get-in r [:body :note])))))

(deftest an-unknown-margin-travels-as-unknown-and-is-not-coerced
  (let [st (store/mem-store)]
    (store/register-client! st {:client/id "c-1"})
    (store/register-project! st {:project/id "beta" :project/client "c-1"})
    (store/register-rate-card! st (psa/rate-card "beta" :engineer 10000))   ;; no cost
    (store/register-entry! st (ts "w-1" "2026-01-01" "beta" :engineer 10 :approved? true))
    (let [r (edge/draft-invoice-core! st allowlist "did:key:zAcme" (pr-str {:project "beta"}))]
      (is (= 200 (:status r)))
      (is (= 100000 (get-in r [:body :total])))
      (is (= :unknown (get-in r [:body :margin]))))))

(deftest drafting-never-bills-an-hour
  (testing "a caller hammering this route produces drafts and nothing else"
    (let [st (seeded)]
      (dotimes [_ 5]
        (edge/draft-invoice-core! st allowlist "did:key:zAcme" (pr-str {:project "alpha"})))
      (is (empty? (store/billed-keys st)))
      (is (empty? (store/invoices st)))
      (is (false? (get-in (edge/draft-invoice-core! st allowlist "did:key:zAcme"
                                                    (pr-str {:project "alpha"}))
                          [:body :issued]))))))

(deftest issuing-and-margin-have-no-http-representation
  (let [publics (set (keys (ns-publics 'tehai.edge.endpoints)))]
    (is (contains? publics 'draft-invoice-core!))
    (doseq [absent '[issue-invoice-core! report-margin-core! assign-person-core!]]
      (is (not (contains? publics absent)) (str absent " must not exist")))))
