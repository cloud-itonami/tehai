(ns tehai.invoice-tax-test
  "インボイス制度, issuing side.

  `cloud-itonami-isco-4311` checks the receiving side — may this journal
  entry claim 仕入税額控除. This is the other half: is the invoice we are
  about to send something its recipient could credit at all? Both read
  `kotoba-lang/taxlaw`, which is why that library exists rather than the
  catalog being copied.

  ## The case this suite exists to keep honest

  A client that declares no jurisdiction is **not held**. Holding every such
  invoice would stop every existing caller, and the client has asserted
  nothing to check. But it must not be silently passed either — the verdict
  carries `:tax {:taxlaw/coverage :not-declared}`, so a console shows `this
  was not checked` rather than an unqualified approval.

  That is the same device as kintai's `:unevaluated`: a question nobody
  could answer belongs next to the answer, not inside it. The tests below
  pin both halves — that it does not hold, AND that it says so."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [kotoba.psa :as psa]
            [tehai.store :as store]
            [tehai.governor :as governor]))

(defn- ts [worker date project role hours & {:keys [approved?]}]
  {:ts/worker worker :ts/date date :ts/project project :ts/role role
   :ts/hours hours :ts/approved? (boolean approved?)})

(defn- store-with-client [client]
  (let [st (store/mem-store)]
    (store/register-client! st client)
    (store/register-project! st {:project/id "alpha" :project/client "c-1"})
    (store/register-person! st {:person/id "w-1"})
    (store/register-rate-card! st (psa/rate-card "alpha" :engineer 15000 :cost 9000))
    (store/register-entry! st (ts "w-1" "2026-01-01" "alpha" :engineer 8 :approved? true))
    (store/register-entry! st (ts "w-1" "2026-01-02" "alpha" :engineer 8 :approved? true))
    st))

(def ^:private approved-keys
  [(psa/entry-key (ts "w-1" "2026-01-01" "alpha" :engineer 8))
   (psa/entry-key (ts "w-1" "2026-01-02" "alpha" :engineer 8))])

(defn- draft [& {:keys [reg]}]
  (cond-> {:op :draft-invoice :effect :propose :project "alpha"
           :entry-keys approved-keys :total 240000 :margin 96000 :confidence 0.9}
    reg (assoc :issuer-registration-number reg)))

(defn- check [proposal store] (governor/check {:client-id "c-1"} {} proposal store))

(defn- rules [v] (into #{} (map :rule) (:violations v)))

;; ---------------------------------------------------------------------------
;; a jurisdiction that requires a qualified invoice
;; ---------------------------------------------------------------------------

(deftest ok-when-the-issuer-registration-number-is-present-and-valid
  (let [v (check (draft :reg "T1234567890123")
                 (store-with-client {:client/id "c-1" :client/name "Acme"
                                     :client/jurisdiction :jp}))]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (true? (:taxlaw/supported? (:tax v))))))

(deftest hard-when-the-invoice-would-not-be-creditable
  (testing "no registration number at all"
    (let [v (check (draft)
                   (store-with-client {:client/id "c-1" :client/name "Acme"
                                       :client/jurisdiction :jp}))]
      (is (:hard? v))
      (is (contains? (rules v) :invoice-not-creditable))
      (is (re-find #"missing-registration-number"
                   (:detail (first (filter #(= :invoice-not-creditable (:rule %))
                                           (:violations v))))))))
  (testing "a malformed one is not better than none"
    (let [v (check (draft :reg "1234567890123")
                   (store-with-client {:client/id "c-1" :client/name "Acme"
                                       :client/jurisdiction :jp}))]
      (is (:hard? v))
      (is (contains? (rules v) :invoice-not-creditable))
      (is (re-find #"malformed-registration-number"
                   (:detail (first (filter #(= :invoice-not-creditable (:rule %))
                                           (:violations v)))))))))

(deftest hard-when-the-client-asserts-a-jurisdiction-nobody-catalogued
  (testing "an unchecked jurisdiction is a HOLD, not a pass"
    (let [v (check (draft :reg "T1234567890123")
                   (store-with-client {:client/id "c-1" :client/name "Atlantis Co"
                                       :client/jurisdiction :atlantis}))]
      (is (:hard? v))
      (is (contains? (rules v) :unchecked-invoice-jurisdiction))
      (is (= :none (:taxlaw/coverage (:tax v)))))))

;; ---------------------------------------------------------------------------
;; the not-declared case — the one that must not become a silent pass
;; ---------------------------------------------------------------------------

(deftest an-undeclared-jurisdiction-is-not-held
  (testing "the client asserted nothing; holding here would stop every
            existing caller and answer a question nobody asked"
    (let [v (check (draft) (store-with-client {:client/id "c-1" :client/name "Acme"}))]
      (is (:ok? v))
      (is (not (:hard? v)))
      (is (empty? (set/intersection
                   (rules v)
                   #{:invoice-not-creditable :unchecked-invoice-jurisdiction}))))))

(deftest an-undeclared-jurisdiction-is-not-a-silent-pass
  (testing "the verdict must SAY it was not checked — otherwise `we looked
            and it was fine` and `nobody looked` are the same output"
    (let [v (check (draft) (store-with-client {:client/id "c-1" :client/name "Acme"}))]
      (is (= :not-declared (:taxlaw/coverage (:tax v))))
      (is (not (contains? (:tax v) :taxlaw/supported?))
          "no supported? key at all — nil is falsey, so a careless reader is
           safe, and a careful one can tell this from a refusal"))))

(deftest non-invoicing-ops-carry-no-tax-assessment
  (testing "an op that sends no invoice raises no invoice question, so the
            actor does not manufacture one to report on"
    (let [v (check {:op :review :effect :propose :confidence 0.9}
                   (store-with-client {:client/id "c-1" :client/name "Acme"
                                       :client/jurisdiction :jp}))]
      (is (nil? (:tax v))))))

(deftest the-jurisdiction-is-the-clients-not-the-proposals
  (testing "the recipient claims the credit, so the recipient's jurisdiction
            decides — an issuer that could nominate one would nominate a
            jurisdiction it satisfies"
    (let [v (check (assoc (draft :reg "T1234567890123") :jurisdiction :jp)
                   (store-with-client {:client/id "c-1" :client/name "Atlantis Co"
                                       :client/jurisdiction :atlantis}))]
      (is (:hard? v))
      (is (contains? (rules v) :unchecked-invoice-jurisdiction)))))
