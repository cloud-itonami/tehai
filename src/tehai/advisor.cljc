(ns tehai.advisor
  "TehaiAdvisor — proposes a professional-services operation: staff a
  person onto a project, draft an invoice from approved time, report a
  project's margin. Swappable: `mock-advisor` (deterministic, default) or
  `llm-advisor`. Either way the advisor ONLY produces a PROPOSAL;
  `tehai.governor` independently redrafts the invoice via `kotoba.psa`
  and holds any mismatch. Modeled on cloud-itonami-isco-4313's
  payroll.advisor.

  A proposal is a map:
    {:op :draft-invoice|:issue-invoice|:assign-person|:report-margin
     :effect :propose
     :project str
     :entry-keys [[worker date project role] ...]
     :total n :margin n-or-:unknown
     :assignment {kotoba.psa/assignment}
     :confidence 0.0-1.0
     :rationale str}"
  (:require [kotoba.psa :as psa]
            [tehai.store :as store]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- billable-entries
  "The approved, priced, not-yet-billed entries for a project. An honest
  advisor proposes only these; the governor checks that it did."
  [store project]
  (let [cards (store/rate-cards store)
        billed (store/billed-keys store)]
    (->> (store/entries store)
         (filter #(= project (:ts/project %)))
         (filter :ts/approved?)
         (filter #(psa/billable? cards %))
         (remove #(contains? billed (psa/entry-key %))))))

(defn- infer
  [store {:keys [op project] :as request}]
  (let [base {:op op
              :effect :propose
              :confidence 0.9
              :rationale (str "proposed " (name op) " for client " (:client-id request))}]
    (case op
      (:draft-invoice :issue-invoice)
      (let [items (billable-entries store project)
            draft (psa/invoice (:invoice-id request) project items
                               (store/rate-cards store) (store/billed-keys store))
            m (psa/margin (map #(psa/price-entry (store/rate-cards store) %) items))]
        (assoc base
               :project project
               :entry-keys (:invoice/entries draft)
               :lines (:invoice/lines draft)
               :total (:invoice/total draft)
               ;; Passed through as :unknown when psa says so. An advisor
               ;; that "helpfully" substitutes revenue here is exactly
               ;; what the governor's :fabricated-margin rule catches.
               :margin (:margin/amount m)
               :rationale (let [gap (psa/describe-gap draft)]
                            (if (seq gap)
                              (str "invoice drafted; " gap)
                              "invoice drafted; nothing left out"))))

      :assign-person
      (assoc base
             :project project
             :assignment (:assignment request))

      :report-margin
      (let [items (filter #(= project (:ts/project %)) (store/entries store))
            m (psa/margin (map #(psa/price-entry (store/rate-cards store) %) items))]
        (assoc base :project project :margin (:margin/amount m) :margin-detail m))

      base)))

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a professional-services advisor. Given an operation request,
   propose the :op, the cited :project, and for an invoice the entry keys
   to bill. Never invent a rate, never bill unapproved time, never
   re-bill time already invoiced, and never state a margin when the cost
   side is unknown — say :unknown. Amounts are recomputed independently;
   a number you guess will be held, not corrected.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`; decoupled from any concrete model
  beyond the protocol. The model sees the project's entry keys and the
  rate card roles — never the client's contract terms and never another
  client's projects."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ store request]
      (let [project (:project request)
            visible (mapv psa/entry-key (billable-entries store project))
            msgs [{:role :system :content system-prompt}
                  {:role :user
                   :content (str "project: " (pr-str project)
                                 "\nbillable entry keys: " (pr-str visible)
                                 "\nrequest: " (pr-str (dissoc request :client-id)))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (assoc (parse-proposal (:content resp)) :project project)))))
