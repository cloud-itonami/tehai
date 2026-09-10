(ns tehai.conformance-test
  "Every verdict this actor can emit is a well-formed verdict.

  `kotoba-lang/governor` measured 376 hand-copied governors in this fleet and
  found one that had drifted into reporting a HARD violation as escalatable,
  so an approval queue would show a permanently-refused invoice as awaiting
  sign-off. The drift was invisible through the actor's own graph — the
  router tests `:hard?` first — so no ordinary test caught it.

  This suite does not check WHAT the governor decided; the other suites do
  that. It checks that whatever it decided is internally consistent, across
  every disposition this actor has."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.psa :as psa]
            [tehai.store :as store]
            [tehai.governor :as governor]
            [governor.core :as gov]))

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
    ;; unpriced + unapproved time on a second project, so those HARD rules
    ;; have something to fire on.
    (store/register-entry! st (ts "w-1" "2026-02-01" "zeta" :designer 8))
    st))

(def ^:private approved-keys
  [(psa/entry-key (ts "w-1" "2026-01-01" "alpha" :engineer 8))
   (psa/entry-key (ts "w-1" "2026-01-02" "alpha" :engineer 8))])

(def ^:private clean
  {:op :draft-invoice :effect :propose :project "alpha"
   :entry-keys approved-keys :total 240000 :margin 96000 :confidence 0.9})

(def ^:private cases
  [{:name :clean :request {:client-id "c-1"} :proposal clean}
   {:name :hard/no-client :request {:client-id "nobody"} :proposal clean}
   {:name :hard/no-actuation :request {:client-id "c-1"}
    :proposal (assoc clean :effect :direct-write)}
   {:name :hard/unknown-project :request {:client-id "c-1"}
    :proposal (assoc clean :project "no-such-project")}
   ;; the :scope-key shape: a psa project carries :project/client, the
   ;; request carries :client-id.
   {:name :hard/project-wrong-client :request {:client-id "c-1"}
    :proposal (assoc clean :project "zeta")}
   {:name :escalate/low-confidence :request {:client-id "c-1"}
    :proposal (assoc clean :confidence 0.3)}
   ;; a proposal that does not say how confident it is has not said it is
   ;; confident — the absent key must read as 0.0, never as trustworthy.
   {:name :escalate/no-confidence-key :request {:client-id "c-1"}
    :proposal (dissoc clean :confidence)}])

(defn- verdict-for [{:keys [request proposal]}]
  (governor/check request {} proposal (fresh-store)))

(deftest every-verdict-is-well-formed
  (doseq [{:keys [name] :as c} cases]
    (testing (str name)
      (let [v (verdict-for c)]
        (is (empty? (gov/conformance-failures v))
            (str "非適合: " (pr-str (gov/conformance-failures v))))))))

(deftest the-drift-that-happened-elsewhere-cannot-happen-here
  (doseq [{:keys [name] :as c} cases
          :let [v (verdict-for c)]
          :when (:hard? v)]
    (testing (str name)
      (is (not (:escalate? v))
          "an approver cannot be invited to wave through a HARD hold")
      (is (not (:ok? v)))
      (is (seq (:violations v)) "a hold must say what it refused"))))

(deftest the-case-set-actually-covers-the-three-dispositions
  ;; evidence floor: a conformance suite whose cases all landed in one
  ;; disposition would pass while checking almost nothing.
  (let [vs (map verdict-for cases)]
    (is (>= (count (filter :ok? vs)) 1) "no clean case")
    (is (>= (count (filter :hard? vs)) 4) "HARD rules under-covered")
    (is (>= (count (filter :escalate? vs)) 2) "escalation under-covered")))

(deftest escalation-carries-a-reason
  (doseq [{:keys [name] :as c} cases
          :let [v (verdict-for c)]
          :when (:escalate? v)]
    (testing (str name)
      (is (some? (:escalation-reason v))))))
