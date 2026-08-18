(ns tehai.non-jp-test
  "Issuing into a jurisdiction that is not Japan.

  `kotoba.taxlaw` gained `[:eu]` and `[:us]` on 2026-08-18, and with them
  coverage that is per FACET rather than per jurisdiction. The library's own
  note on why says it plainly: `credit-support` used to gate on `covered?`,
  and `requires-qualified-invoice?` returns nil for a facet the catalog
  lacks — so `(or (not needs?) ...)` was `true`, and **adding a jurisdiction
  with no invoice rule would have turned a claim there from held into
  approved with no registration number**.

  This suite is that danger stated as an invariant of THIS actor's verdict.
  It is deliberately phrased about `:ok?` / `:hard?` / the disposition the
  graph reaches, and not about what taxlaw returns: the failure mode is not
  a wrong library answer, it is a right library answer that the governor
  reads as permission.

  ## The three shapes

    [:jp]  a 適格請求書 registration number, checked against the published
           T + 13 digit form
    [:eu]  a VAT identification number is REQUIRED (Article 226 is a closed
           list and (3) is that number), but only its ISO 3166 prefix can be
           checked (Article 215 gives nothing else). `\"XX1\"` passes, and
           the verdict must not let that read as `the VAT number is valid`
    [:us]  no federal VAT exists, so the facet is out of scope and the
           invoice is HELD — exactly as it was before the United States was
           in the catalog at all"
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.psa :as psa]
            [kotoba.taxlaw :as taxlaw]
            [tehai.store :as store]
            [tehai.actor :as actor]
            [tehai.advisor :as advisor]
            [tehai.governor :as governor]))

(defn- ts [worker date project role hours]
  {:ts/worker worker :ts/date date :ts/project project :ts/role role
   :ts/hours hours :ts/approved? true})

(defn- store-in
  "A store whose one client declares `j` (or declares nothing when nil)."
  [j]
  (let [st (store/mem-store)]
    (store/register-client! st (cond-> {:client/id "c-1" :client/name "Acme"}
                                 j (assoc :client/jurisdiction j)))
    (store/register-project! st {:project/id "alpha" :project/client "c-1"})
    (store/register-person! st {:person/id "w-1"})
    (store/register-rate-card! st (psa/rate-card "alpha" :engineer 15000 :cost 9000))
    (store/register-entry! st (ts "w-1" "2026-01-01" "alpha" :engineer 8))
    (store/register-entry! st (ts "w-1" "2026-01-02" "alpha" :engineer 8))
    st))

(def ^:private approved-keys
  [(psa/entry-key (ts "w-1" "2026-01-01" "alpha" :engineer 8))
   (psa/entry-key (ts "w-1" "2026-01-02" "alpha" :engineer 8))])

(defn- draft [& {:keys [reg op]}]
  (cond-> {:op (or op :draft-invoice) :effect :propose :project "alpha"
           :entry-keys approved-keys :total 240000 :margin 96000 :confidence 0.9}
    reg (assoc :issuer-registration-number reg)))

(defn- check [proposal j] (governor/check {:client-id "c-1"} {} proposal (store-in j)))
(defn- rules [v] (into #{} (map :rule) (:violations v)))
(defn- violation [v rule] (first (filter #(= rule (:rule %)) (:violations v))))

;; ---------------------------------------------------------------------------
;; the widening that must not have happened
;; ---------------------------------------------------------------------------

(deftest a-us-invoice-is-refused-as-hard-as-one-into-a-jurisdiction-that-does-not-exist
  (testing "cataloguing the United States must not soften a single thing.
            :atlantis is the control — a jurisdiction nobody has read, held
            since before any of this. The two verdicts must be the same
            refusal"
    (let [us (check (draft :reg "T1234567890123") :us)
          nowhere (check (draft :reg "T1234567890123") :atlantis)]
      (is (false? (:ok? us)))
      (is (true? (:hard? us)))
      (is (= (select-keys nowhere [:ok? :hard? :escalate?])
             (select-keys us [:ok? :hard? :escalate?])))
      (is (= (rules nowhere) (rules us)))
      (is (contains? (rules us) :unchecked-invoice-jurisdiction)))))

(deftest cataloguing-the-united-states-did-not-make-an-invoice-issuable
  (testing "the graph's own disposition, not the governor's flags: a US
            invoice reaches :hold and never :request-approval, and no hours
            are locked away by the attempt"
    (let [st (store-in :us)
          reg (reify advisor/Advisor
                (-advise [_ _ _]
                  (assoc (draft :op :issue-invoice)
                         :issuer-registration-number "T1234567890123")))
          g (actor/build-graph {:store st :advisor reg})
          r (actor/run-request! g {:client-id "c-1" :op :issue-invoice
                                   :project "alpha" :invoice-id "inv-us"}
                                {} "t-us")]
      (is (= :done (:status r))
          "not :interrupted — a hold is not an approval waiting to happen")
      (is (= :hold (get-in r [:state :disposition])))
      (is (empty? (store/billed-keys st)))
      (is (empty? (store/invoices st))))))

(deftest no-catalogued-jurisdiction-approves-an-invoice-with-no-registration-number
  (testing "over EVERY jurisdiction the catalog holds, including ones added
            after this test was written. Naming today's three would pass
            today and miss exactly the case taxlaw warns about: the NEXT
            jurisdiction, whose invoice facet nobody wrote"
    (let [js (keys taxlaw/jurisdictions)]
      (is (<= 3 (count js)) "the catalog shrank; this loop measures nothing")
      (doseq [j js]
        (let [v (check (draft) j)]
          (is (false? (:ok? v))
              (str j " approved an invoice carrying no registration number"))
          (is (true? (:hard? v))
              (str j " left the refusal escalatable, so a human could wave it through")))))))

(deftest an-out-of-scope-facet-is-not-read-as-no-requirement
  (testing "`requires-qualified-invoice?` is nil for the US — the facet is
            out of scope, not answered `false`. An actor that read nil as
            `no requirement` would approve. This one holds"
    (is (nil? (taxlaw/requires-qualified-invoice? [:us]))
        "if this becomes false, the read-as-no-requirement bug is live again")
    (is (true? (:hard? (check (draft :reg "T1234567890123") :us))))))

;; ---------------------------------------------------------------------------
;; [:us] — what this actor cannot say, said out loud
;; ---------------------------------------------------------------------------

(deftest the-us-refusal-names-why-there-is-nothing-to-check
  (testing "`nobody has read this` and `there is no federal VAT to read` are
            different facts and call for different work. Held identically,
            explained differently"
    (let [v (violation (check (draft :reg "T1234567890123") :us)
                       :unchecked-invoice-jurisdiction)]
      (is (= :jurisdiction/input-tax-credit (:out-of-scope v)))
      (is (re-find #"no federal VAT" (:why v)))
      (is (re-find #"意図的" (:detail v)))))
  (testing "and an uncatalogued jurisdiction carries neither key — claiming
            a reason there would be inventing one"
    (let [v (violation (check (draft :reg "T1") :atlantis)
                       :unchecked-invoice-jurisdiction)]
      (is (nil? (:out-of-scope v)))
      (is (nil? (:why v))))))

;; ---------------------------------------------------------------------------
;; [:eu] — a required number, and a check that is only a prefix
;; ---------------------------------------------------------------------------

(deftest an-eu-invoice-needs-a-vat-identification-number
  (testing "Article 226 is a closed list of what is required and (3) is the
            supplier's VAT identification number, so this is a real
            requirement and not an analogy"
    (doseq [reg [nil "" "1234" "de123456789"]]
      (let [v (check (draft :reg reg) :eu)]
        (is (true? (:hard? v)) (str "registration " (pr-str reg) " was accepted"))
        (is (contains? (rules v) :invoice-not-creditable))))))

(deftest an-eu-pass-rests-on-a-prefix-and-the-verdict-says-so
  (testing "Article 215 gives the ISO 3166 alpha-2 prefix and NOTHING else,
            so `XX1` passes the only check there is. A verdict that reported
            `supported? true` alone would be read as `the VAT number is
            valid`, which is three claims more than was measured"
    (let [v (check (draft :reg "XX1") :eu)]
      (is (:ok? v) "the prefix check is the check the Directive supports")
      (is (= #{:member-state-is-a-member :body-format :check-digit}
             (:tax-registration-unchecked v)))))
  (testing "a plausible-looking number gets exactly the same caveat — the
            approval is no narrower for XX1 and no wider for DE123456789,
            because the same one thing was checked in both"
    (is (= (:tax-registration-unchecked (check (draft :reg "XX1") :eu))
           (:tax-registration-unchecked (check (draft :reg "DE123456789") :eu)))))
  (testing "EL is Greece's alias in Article 215 and is not a typo to reject"
    (is (:ok? (check (draft :reg "EL123456789") :eu)))))

(deftest the-unchecked-set-is-absent-where-there-is-nothing-to-qualify
  (testing "a refusal qualifies nothing"
    (is (nil? (:tax-registration-unchecked (check (draft) :eu)))))
  (testing "a non-invoicing op asks no invoice question"
    (is (nil? (:tax-registration-unchecked
               (governor/check {:client-id "c-1"} {}
                               {:op :review :effect :propose :confidence 0.9}
                               (store-in :eu))))))
  (testing "and Japan gets nil rather than an EMPTY SET. The catalog
            declares no breakdown for [:jp]; an empty set would read as
            `nothing was left out`, which is a claim nobody made"
    (let [v (check (draft :reg "T1234567890123") :jp)]
      (is (:ok? v))
      (is (nil? (:tax-registration-unchecked v)))
      (is (not (contains? v :tax-registration-unchecked))))))

;; ---------------------------------------------------------------------------
;; retention — nil is the answer
;; ---------------------------------------------------------------------------

(deftest retention-is-nil-for-eu-and-us-and-that-is-the-instrument-speaking
  (testing "Article 247(1) hands the period to the Member State and 26 CFR
            1.6001-1(e) states a condition instead of a number. A future
            pin that turns either into an integer has invented it — the
            widely-repeated `seven years` appears nowhere in the CFR"
    (is (nil? (taxlaw/retention-years [:eu])))
    (is (nil? (taxlaw/retention-years [:us])))
    (is (= 7 (taxlaw/retention-years [:jp]))
        "and Japan's is a number, so nil above is not the function failing"))
  (testing "this actor surfaces no retention period at all, in any
            jurisdiction. The jurisdiction it holds is the CLIENT's, because
            the client claims the credit; how long the ISSUER keeps its copy
            is the issuer's law, and answering one with the other is worse
            than silence"
    (doseq [j [:jp :eu :us nil]]
      (let [v (check (draft :reg "T1234567890123") j)]
        (is (empty? (filter #(re-find #"retention" (name %)) (keys v)))
            (str "the verdict for " j " claims a retention period"))))))
