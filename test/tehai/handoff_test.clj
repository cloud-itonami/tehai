(ns tehai.handoff-test
  "What the ledger did with the entry — and every way that answer can be
  lost on the way back.

  `tehai.shiwake` has a suite because converting an invoice into a journal
  entry can silently drop revenue. This one exists because SUBMITTING that
  entry can silently drop it too: `cloud-itonami-isco-4311` posts, or finds
  a duplicate, or holds, or parks, or refuses — and from this side all five
  arrive as a value nobody wrote down.

  The suite pins four things:

  1. the good outcome is recorded, not only the refusals;
  2. `:duplicate` never collapses into `:posted`;
  3. nothing unreadable is ever scored as a success;
  4. a batch whose results cannot be paired to its entries is refused
     rather than zipped."
  (:require [clojure.test :refer [deftest is testing]]
            [tehai.handoff :as handoff]
            [tehai.shiwake :as shiwake]
            [tehai.store :as store]))

(def ^:private mapping
  {:receivable {"c-1" "売掛金"}
   :revenue    {:consulting "売上高"}
   :tax        {"c-1" "仮受消費税"}})

(defn- issued
  [id]
  {:disposition :commit :op :issue-invoice :client-id "c-1"
   :invoice {:invoice/id id :invoice/total 264000 :invoice/currency "JPY"
             :invoice/category :consulting :invoice/tax 24000}})

(defn- conversions
  "Real `tehai.shiwake` output, so this suite exercises the value the actor
  actually submits rather than a hand-built stand-in of it."
  [& ids]
  (:ok (shiwake/entry-requests (map issued ids) mapping)))

(defn- conv [id] (first (conversions id)))

(defn- reply
  ([status body] {:status status :body body}))

(defn- posted-reply
  ([] (posted-reply false "pst-1"))
  ([duplicate? posting]
   (reply 200 {:ok true :client "c-1" :ephemeral true
               :duplicate? duplicate? :posting posting
               :posting-count 1 :balanced? true})))

(defn- result
  "One entry of a 207 `:results`, shaped as `bookkeeping.edge.endpoints`
  builds it."
  [source-doc status outcome & {:keys [posting violations error]}]
  (cond-> {:status status :outcome outcome :source-doc source-doc}
    posting (assoc :posting posting)
    violations (assoc :violations violations)
    error (assoc :error error)))

(defn- batch-reply [results]
  {:status 207
   :body {:client "c-1" :submitted (count results)
          :summary (frequencies (map :outcome results))
          :results results}})

;; ---------------------------------------------------------------------------
;; the good outcome is a fact too
;; ---------------------------------------------------------------------------

(deftest a-posted-entry-is-recorded
  (testing "a ledger holding only refusals cannot answer 「計上されたか」,
            which is the one question the hand-off exists to close"
    (let [f (handoff/fact (conv "inv-1") (posted-reply))]
      (is (= :posted (:handoff/outcome f)))
      (is (= 200 (:handoff/status f)))
      (is (= "pst-1" (:handoff/posting f))))))

(deftest a-duplicate-is-its-own-outcome
  (testing "one call WROTE the posting and the other found it already
            there; to whoever reconciles those are different events"
    (let [f (handoff/fact (conv "inv-1") (posted-reply true "pst-1"))]
      (is (= :duplicate (:handoff/outcome f)))
      (is (not= :posted (:handoff/outcome f)))
      (is (= "pst-1" (:handoff/posting f)))))
  (testing "and both still mean the entry is in the ledger"
    (is (= handoff/posted-outcomes
           (set (for [d [false true]]
                  (:handoff/outcome (handoff/fact (conv "inv-1")
                                                  (posted-reply d "pst-1")))))))))

(deftest every-status-4311-can-answer-with-becomes-a-named-outcome
  (testing "no status this actor's own entry endpoint can return may arrive
            here as nil — a nil outcome in the ledger is the silence this
            namespace was written to remove"
    (doseq [[status body expected]
            [[200 {:ok true :duplicate? false :posting "p"} :posted]
             [200 {:ok true :duplicate? true :posting "p"} :duplicate]
             [202 {:ok false :disposition :request-approval
                   :reason "external send"} :awaiting-approval]
             [409 {:ok false :disposition :hold
                   :violations [{:rule :unknown-source-doc :detail "inv-1"}]} :held]
             [400 {:ok false :error "invalid request body"} :rejected]
             [403 {:ok false :error "caller not permitted"} :not-permitted]
             [503 {:ok false :error "no store configured"} :unavailable]]]
      (let [f (handoff/fact (conv "inv-1") (reply status body))]
        (is (= expected (:handoff/outcome f))
            (str "status " status))
        (is (= status (:handoff/status f)))))))

(deftest a-refusal-carries-what-it-was-refused-for
  (testing "violations, so the queue this fact creates can be worked"
    (let [v [{:rule :unknown-source-doc :detail "inv-1 not registered"}]
          f (handoff/fact (conv "inv-1") (reply 409 {:ok false :violations v}))]
      (is (= v (:handoff/violations f)))))
  (testing "the escalation reason, so a parked entry says what it waits on"
    (let [f (handoff/fact (conv "inv-1")
                          (reply 202 {:ok false :reason "external send"}))]
      (is (= "external send" (:handoff/reason f)))))
  (testing "and the error text, which is the only place a 400 says which
            part of the body the ledger would not take"
    (is (= "invalid request body"
           (:handoff/error (handoff/fact (conv "inv-1")
                                         (reply 400 {:ok false
                                                     :error "invalid request body"})))))))

;; ---------------------------------------------------------------------------
;; nothing unreadable becomes a success
;; ---------------------------------------------------------------------------

(deftest an-unrecognised-response-is-never-a-success
  (doseq [[label response]
          [["a status nobody here knows" (reply 500 {:ok false})]
           ["a redirect" (reply 301 {})]
           ["no status at all" {:body {:ok true :duplicate? false}}]
           ["a status that is not a number" (reply "200" {:ok true :duplicate? false})]
           ["a body that is not a map" (reply 200 "OK")]
           ["no body at all" {:status 200}]
           ["a 200 that says it did not succeed" (reply 200 {:ok false :posting "p"})]]]
    (let [f (handoff/fact (conv "inv-1") response)]
      (is (= :unreadable (:handoff/outcome f)) label)
      (is (not (contains? handoff/posted-outcomes (:handoff/outcome f))) label)
      (testing "and it carries the response, or nobody can diagnose it"
        (is (= response (:handoff/response f)) label)))))

(deftest a-200-that-does-not-say-whether-it-duplicated-is-unreadable
  (testing "calling it :posted would claim THIS call wrote the posting,
            which is exactly what such a body does not say. Folding it into
            :posted is how a silent duplicate becomes a first post"
    (doseq [body [{:ok true :posting "p"}
                  {:ok true :posting "p" :duplicate? nil}
                  {:ok true :posting "p" :duplicate? "false"}]]
      (is (= :unreadable
             (:handoff/outcome (handoff/fact (conv "inv-1") (reply 200 body))))))))

;; ---------------------------------------------------------------------------
;; the fact can be joined back
;; ---------------------------------------------------------------------------

(deftest the-fact-names-what-it-is-about
  (testing "a reconciliation record that cannot be joined to the thing it
            reconciles is not one"
    (let [f (handoff/fact (conv "inv-77") (posted-reply false "pst-9"))]
      (is (= "inv-77" (:handoff/invoice f)))
      (is (= "c-1" (:handoff/client-id f)))
      (is (= "pst-9" (:handoff/posting f)))))
  (testing "the invoice and the client are named on a REFUSAL too — a
            refusal nobody can attribute is a refusal nobody can act on"
    (doseq [r [(reply 409 {:ok false :violations []})
               (reply 400 {:ok false :error "invalid request body"})
               (reply 500 {})]]
      (let [f (handoff/fact (conv "inv-77") r)]
        (is (= "inv-77" (:handoff/invoice f)))
        (is (= "c-1" (:handoff/client-id f))))))
  (testing "and :handoff/posting is PRESENT even when there is none — a key
            that vanishes when the answer is interesting cannot be queried"
    (let [f (handoff/fact (conv "inv-1") (reply 200 {:ok true :duplicate? false}))]
      (is (contains? f :handoff/posting))
      (is (nil? (:handoff/posting f))))))

(deftest a-conversion-that-produced-no-request-is-not-scored
  (testing "a reply cannot belong to an entry nobody sent; attributing one
            would record an outcome for an invoice that never left"
    (let [skipped (first (:skipped (shiwake/entry-requests
                                    [(assoc (issued "inv-2") :disposition :hold)]
                                    mapping)))
          f (handoff/fact skipped (posted-reply))]
      (is (= :not-submitted (:handoff/outcome f)))
      (is (= :not-issued (:handoff/conversion f)))
      (is (not (contains? handoff/posted-outcomes (:handoff/outcome f))))))
  (testing "and a conversion citing no source document is refused for the
            same reason: the fact could not be joined to an invoice"
    (let [f (handoff/fact {:shiwake/status :ok :shiwake/request {:source-doc "  "}}
                          (posted-reply))]
      (is (= :not-submitted (:handoff/outcome f)))))
  (testing "the STATUS is what says whether it was submitted, not the
            presence of a request-shaped map. A refusal carrying a leftover
            request must not be scored: measured — without this case, making
            the status check unconditional reddened nothing, because every
            refusal shiwake itself emits also lacks a source document"
    (doseq [s [:no-mapping :tax-not-stated :unusable-invoice :not-issued]]
      (let [stale {:shiwake/status s
                   :shiwake/request {:op :draft-entry :source-doc "inv-5" :lines []}
                   :shiwake/issued {:client-id "c-1"}}
            f (handoff/fact stale (posted-reply))
            b (handoff/facts [stale] (batch-reply [(result "inv-5" 200 :posted)]))]
        (is (= :not-submitted (:handoff/outcome f)) (str s))
        (is (= s (:handoff/conversion f)))
        (is (= :not-submitted (:handoff/outcome (first (:handoff/facts b)))) (str s))))))

;; ---------------------------------------------------------------------------
;; the batch
;; ---------------------------------------------------------------------------

(deftest a-batch-pairs-each-result-with-the-entry-it-answers
  (let [cs (conversions "inv-1" "inv-2" "inv-3")
        r (handoff/facts cs (batch-reply [(result "inv-1" 200 :posted :posting "p1")
                                          (result "inv-2" 200 :duplicate :posting "p1")
                                          (result "inv-3" 409 :held
                                                  :violations [{:rule :unbalanced}])]))]
    (is (= :paired (:handoff/status r)))
    (is (= 3 (count (:handoff/facts r))))
    (is (= ["inv-1" "inv-2" "inv-3"] (mapv :handoff/invoice (:handoff/facts r))))
    (is (= [:posted :duplicate :held] (mapv :handoff/outcome (:handoff/facts r))))
    (is (= {:posted 1 :duplicate 1 :held 1} (:handoff/summary r)))
    (testing "and the held one keeps its violations"
      (is (= [{:rule :unbalanced}] (:handoff/violations (nth (:handoff/facts r) 2)))))))

(deftest a-length-mismatch-is-refused-rather-than-zipped
  (testing "results are joined to entries by POSITION only. One missing
            result misattributes every outcome after it, and the entries
            that fall off the end get no outcome at all"
    (doseq [[submitted answered]
            [[["inv-1" "inv-2" "inv-3"] ["inv-1" "inv-2"]]
             [["inv-1"] ["inv-1" "inv-2"]]
             [["inv-1" "inv-2"] []]]]
      (let [cs (apply conversions submitted)
            r (handoff/facts cs (batch-reply (mapv #(result % 200 :posted :posting "p")
                                                   answered)))]
        (is (= :length-mismatch (:handoff/status r))
            (str submitted " vs " answered))
        (is (= (count submitted) (:handoff/submitted (first (:handoff/facts r)))))
        (is (= (count answered) (:handoff/answered (first (:handoff/facts r)))))))))

(deftest a-refused-batch-still-yields-a-fact-to-append
  (testing "returning no facts would mean a caller looping over them wrote
            NOTHING, so a failed hand-off would look exactly like one nobody
            attempted — the very defect this namespace removes"
    (doseq [r [(handoff/facts (conversions "inv-1" "inv-2")
                              (batch-reply [(result "inv-1" 200 :posted)]))
               (handoff/facts (conversions "inv-1") {:status 500 :body {:ok false}})
               (handoff/facts (conversions "inv-1") {:status 207 :body {:results "nope"}})]]
      (is (= 1 (count (:handoff/facts r))))
      (is (some? (:handoff/outcome (first (:handoff/facts r)))))
      (is (not (contains? handoff/posted-outcomes
                          (:handoff/outcome (first (:handoff/facts r))))))
      (is (some? (:handoff/why (first (:handoff/facts r)))))
      (is (= 1 (reduce + 0 (vals (:handoff/summary r))))))))

(deftest a-batch-with-no-results-sequence-is-unreadable-not-empty
  (testing "an empty run and an unreadable one must not be the same value:
            the first says every entry was answered, the second that none
            of them was"
    (let [r (handoff/facts (conversions "inv-1") {:status 207 :body {:ok false}})]
      (is (= :unreadable-batch (:handoff/status r)))
      (is (= :unreadable (:handoff/outcome (first (:handoff/facts r)))))
      (is (= 1 (:handoff/submitted (first (:handoff/facts r))))))
    (testing "whereas submitting nothing and being answered nothing pairs"
      (let [r (handoff/facts [] (batch-reply []))]
        (is (= :paired (:handoff/status r)))
        (is (= [] (:handoff/facts r)))))))

(deftest a-result-answering-a-different-document-is-misattributed
  (testing "4311 echoes the source document it read. If the echo is not the
            one this conversion submitted the results are out of order, and
            an outcome written against the wrong invoice reads as an answer"
    (let [cs (conversions "inv-1" "inv-2")
          r (handoff/facts cs (batch-reply [(result "inv-2" 200 :posted :posting "p")
                                            (result "inv-1" 200 :posted :posting "p")]))]
      (is (= [:misattributed :misattributed]
             (mapv :handoff/outcome (:handoff/facts r))))
      (is (= "inv-2" (:handoff/answered-for (first (:handoff/facts r)))))
      (is (= "inv-1" (:handoff/invoice (first (:handoff/facts r))))))))

(deftest a-batch-result-whose-status-and-outcome-disagree-is-unreadable
  (testing "taking :outcome alone would let a 500 arrive labelled :posted;
            taking :status alone would lose the posted/duplicate
            distinction, which a batch result carries nowhere else"
    (doseq [[status outcome] [[500 :posted] [200 :held] [409 :posted]
                              [200 nil] [400 :duplicate] [202 :posted]]]
      (let [r (handoff/facts (conversions "inv-1")
                             (batch-reply [(result "inv-1" status outcome)]))
            f (first (:handoff/facts r))]
        (is (= :unreadable (:handoff/outcome f)) (str status " / " outcome))
        (is (= (result "inv-1" status outcome) (:handoff/response f))))))
  (testing "and where they agree, the outcome is kept"
    (doseq [[status outcome] [[200 :posted] [200 :duplicate] [409 :held]
                              [202 :awaiting-approval] [400 :rejected]]]
      (let [r (handoff/facts (conversions "inv-1")
                             (batch-reply [(result "inv-1" status outcome)]))]
        (is (= outcome (:handoff/outcome (first (:handoff/facts r)))))))))

;; ---------------------------------------------------------------------------
;; the vocabulary, the ledger, and the ceiling
;; ---------------------------------------------------------------------------

(deftest no-outcome-is-invented-outside-the-declared-vocabulary
  (let [singles (for [status [200 202 409 400 403 503 500 nil]
                      body [{:ok true :duplicate? false :posting "p"}
                            {:ok true :duplicate? true}
                            {:ok false :error "x"}
                            "not a map"]]
                  (handoff/fact (conv "inv-1") (reply status body)))
        batched (mapcat #(:handoff/facts %)
                        [(handoff/facts (conversions "inv-1")
                                        (batch-reply [(result "inv-1" 200 :posted)]))
                         (handoff/facts (conversions "inv-1")
                                        (batch-reply [(result "inv-9" 200 :posted)]))
                         (handoff/facts (conversions "inv-1") (batch-reply []))
                         (handoff/facts (conversions "inv-1") {:status 500})])
        all (concat singles batched)]
    (testing "the scan actually looked at something"
      (is (>= (count all) 30) (str "scored " (count all) " responses")))
    (doseq [f all]
      (is (contains? handoff/outcomes (:handoff/outcome f))
          (str "undeclared outcome " (pr-str (:handoff/outcome f)))))))

(deftest the-fact-is-what-the-ledger-already-takes
  (testing "it goes in beside the graph's own :commit and :hold facts, with
            no new store call and no new schema"
    (let [st (store/mem-store)
          f (handoff/fact (conv "inv-1") (posted-reply))]
      (store/append-ledger! st f)
      (is (= [f] (vec (store/ledger st))))
      (is (= :posted (:handoff/outcome (first (store/ledger st)))))))
  (testing "and a whole refused batch appends by the same loop"
    (let [st (store/mem-store)
          r (handoff/facts (conversions "inv-1" "inv-2")
                           (batch-reply [(result "inv-1" 200 :posted)]))]
      (doseq [f (:handoff/facts r)] (store/append-ledger! st f))
      (is (= 1 (count (store/ledger st))))
      (is (= :length-mismatch (:handoff/outcome (first (store/ledger st))))))))

(deftest this-namespace-reaches-nothing
  (testing "it records what happened; obtaining the response would be the
            actuation this actor refuses, and would put the hand-off's own
            transport inside the thing that reports on it"
    (let [src (slurp "src/tehai/handoff.cljc")]
      (doseq [tok ["http" "fetch" "slurp" "4311" "client/" "js/"]]
        (is (not (re-find (re-pattern (str "\\(" tok)) src))
            (str "handoff must not call out: found " tok)))))
  (testing "and it depends on nothing — no store, no actor, no shiwake"
    (let [reqs (->> (read-string (slurp "src/tehai/handoff.cljc"))
                    (filter list?)
                    (filter #(= :require (first %)))
                    first rest (map first) set)]
      (is (= #{'clojure.string} reqs)
          (str "handoff must stay a pure value function; requires " (pr-str reqs))))))
