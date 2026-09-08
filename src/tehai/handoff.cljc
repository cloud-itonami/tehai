(ns tehai.handoff
  "受領確認 — what `cloud-itonami-isco-4311` DID with an entry this actor
  emitted, as a ledger fact.

  `tehai.shiwake` converts an issued invoice into the `:draft-entry`
  request that actor accepts. **Converted is not posted.** The ledger actor
  can commit the entry, find it already there, hold it against a rule,
  park it for a human, or refuse the body outright — and until this
  namespace existed all five looked identical from here, because nothing
  wrote down which one happened. An invoice that was issued, converted,
  submitted and REFUSED is revenue nobody's books show, and it was
  indistinguishable from one that posted.

  So this namespace turns one response into a value the actor appends with
  `tehai.store/append-ledger!`, next to the `:commit` and `:hold` facts the
  graph already writes.

  ## It produces a value, it does not make a call

  Same ceiling as `tehai.shiwake`, and for the same reason: this actor
  proposes. It does not submit the entry and it does not read the reply off
  a socket — it is handed a response somebody else obtained. This namespace
  requires exactly `clojure.string`, asserted by a test that reads its own
  source.

  ## The good outcome is recorded too

  A ledger that records only refusals cannot answer 「これは計上されたか」,
  which is the only question the hand-off exists to close. `:posted` is a
  fact and is written down like any other.

  ## `:duplicate` is not `:posted`

  One call WROTE the posting; the other found it already there. 4311 is
  idempotent on the posting id and reports which happened, and folding the
  two would mean a re-send and a first post are the same entry in this
  ledger — they are not, to anyone reconciling. They stay two outcomes.

  ## An unreadable response is never a success

  A status this namespace does not know, a body that is not a map, a 200
  that does not say whether it duplicated: all `:unreadable`, and the fact
  carries the response so somebody can see what actually arrived. The
  failure mode being refused here is the one this workspace keeps finding —
  a step that could not be read reporting the same value as a step that
  went well.

  ## The fact can be joined back

  Every fact names the invoice it is about (the source document cited on
  both sides of the hand-off), the client, and 4311's posting id where
  there is one. A reconciliation record that cannot be joined to the thing
  it reconciles is not one."
  (:require [kotoba.lang.text :as str]))

(def outcomes
  "Every value `:handoff/outcome` can take. Named as a set so a reader can
  see the whole vocabulary in one place, and so a test can assert the
  functions never invent one outside it.

  The first seven mirror what 4311 answers. The last four are this side's:
  `:not-submitted` (there was no request, so this response answers
  something else), `:misattributed` (the reply cites a different source
  document from the one it was paired with), `:length-mismatch` (the batch
  could not be paired at all) and `:unreadable`."
  #{:posted :duplicate :awaiting-approval :held :rejected
    :not-permitted :unavailable
    :not-submitted :misattributed :length-mismatch :unreadable})

(def posted-outcomes
  "The outcomes that mean the entry is IN the ledger. Two, not one, and
  never folded: one of them wrote it and the other confirmed it was already
  written."
  #{:posted :duplicate})

(defn- status-code
  "The numeric status, or nil. Normalised through `long` so a status that
  arrived as 200.0 out of a decoder is the same 200 the tables below match —
  `(= 200 200.0)` is false, and a response read as a double would otherwise
  fall through to `:unreadable`."
  [s]
  (when (number? s) (long s)))

(defn- absent? [x] (str/blank? (str x)))

(defn- single-outcome
  "One `POST /api/entry` reply -> an outcome.

  400, 403 and 503 stay three answers where 4311's own batch collapses them
  to `:rejected`. They send whoever reads this ledger to three different
  places: 400 is the body this actor emitted, 403 is who it authenticated
  as, 503 is a ledger deployment with no store or no allow-list. 4311 makes
  exactly this argument about its own 503 — misattributed blame sends an
  operator to look at the wrong thing."
  [status body]
  (if-not (map? body)
    :unreadable
    (condp = status
      200 (cond
            (not (true? (:ok body))) :unreadable
            ;; A 200 that does not say whether it duplicated cannot be
            ;; called `:posted`: that would claim this call wrote the
            ;; posting, which is precisely what such a body does not say.
            (true? (:duplicate? body)) :duplicate
            (false? (:duplicate? body)) :posted
            :else :unreadable)
      202 :awaiting-approval
      409 :held
      400 :rejected
      403 :not-permitted
      503 :unavailable
      :unreadable)))

(defn- detail
  "The part of the reply worth keeping next to the outcome. `:unreadable`
  carries the WHOLE response, because the point of that outcome is that
  nobody yet knows which part of it matters."
  [outcome status body response]
  (cond-> {}
    (= :held outcome) (assoc :handoff/violations (vec (:violations body)))
    (= :awaiting-approval outcome) (assoc :handoff/reason (:reason body))
    (and (map? body) (:error body)) (assoc :handoff/error (:error body))
    (= :unreadable outcome) (assoc :handoff/response response
                                   :handoff/raw-status status)))

(defn- identity-of
  "What the fact is about. `:handoff/client-id` is read from
  `:shiwake/issued`, which `tehai.shiwake/entry-requests` attaches to every
  conversion; a fact built from a bare `entry-request` result carries nil
  there and is still joinable through the invoice."
  [converted]
  {:handoff/invoice (get-in converted [:shiwake/request :source-doc])
   :handoff/client-id (get-in converted [:shiwake/issued :client-id])})

(defn- not-submitted
  [converted response why]
  (merge (identity-of converted)
         {:handoff/outcome :not-submitted
          :handoff/conversion (:shiwake/status converted)
          :handoff/why why
          :handoff/response response}))

(defn fact
  "One 4311 reply as a ledger fact.

      {:handoff/outcome :posted | :duplicate | :awaiting-approval | :held
                        | :rejected | :not-permitted | :unavailable
                        | :not-submitted | :unreadable
       :handoff/status  200
       :handoff/invoice \"inv-1\"
       :handoff/client-id \"c-1\"
       :handoff/posting \"pst-…\"   ;; nil when the entry produced none
       …}

  `converted` is a `tehai.shiwake` conversion — the `:shiwake/status :ok`
  map whose `:shiwake/request` was submitted. `response` is what came back:
  `{:status n :body {…}}`.

  A conversion that is not `:ok` never became a request, so a reply cannot
  belong to it; that is `:not-submitted` and it keeps the response, rather
  than being scored against an entry nobody sent. Same for a conversion
  with no source document: with nothing to cite, the fact could not be
  joined to an invoice, and an unjoinable reconciliation record is not one.

  `:handoff/posting` is present even when nil. 4311 reports it the same way
  and for the same reason: an entry that committed without producing a
  posting is exactly what a reader has to be able to see, and a key that
  vanishes when the answer is interesting is a key nobody can query on.

  Pure. It is handed a response; it does not go and get one."
  [converted response]
  (let [invoice (get-in converted [:shiwake/request :source-doc])]
    (cond
      (not= :ok (:shiwake/status converted))
      (not-submitted converted response
                     (str "conversion status " (pr-str (:shiwake/status converted))
                          " never produced a request, so this reply answers "
                          "something else; scoring it here would attribute an "
                          "outcome to an invoice nobody submitted"))

      (absent? invoice)
      (not-submitted converted response
                     (str "the conversion cites no source document, so this "
                          "outcome could not be joined back to an invoice"))

      :else
      (let [status (status-code (:status response))
            body (:body response)
            outcome (single-outcome status body)]
        (merge (identity-of converted)
               {:handoff/outcome outcome
                :handoff/status status
                :handoff/posting (when (map? body) (:posting body))}
               (detail outcome status body response))))))

;; ---------------------------------------------------------------------------
;; the batch
;; ---------------------------------------------------------------------------

(defn- result-outcome
  "One entry of a 207 `:results` -> an outcome.

  A batch result carries BOTH a status and 4311's own `:outcome`, and this
  reads both. It has to read `:outcome`, because a batch result does not
  carry `:duplicate?` and that keyword is the only place the posted /
  duplicate distinction survives the batch. It also has to read `:status`,
  because taking `:outcome` on its own would let a 500 arrive labelled
  `:posted`. Where the two disagree nobody knows what happened, and
  `:unreadable` is what that is called here."
  [status outcome]
  (let [permitted (condp = status
                    200 #{:posted :duplicate}
                    202 #{:awaiting-approval}
                    409 #{:held}
                    400 #{:rejected}
                    403 #{:rejected}
                    503 #{:rejected}
                    nil)]
    (if (and permitted (contains? permitted outcome))
      (condp = status
        403 :not-permitted
        503 :unavailable
        outcome)
      :unreadable)))

(defn- result-fact
  [converted result]
  (let [invoice (get-in converted [:shiwake/request :source-doc])
        echoed (:source-doc result)]
    (cond
      (or (not= :ok (:shiwake/status converted)) (absent? invoice))
      (not-submitted converted result
                     "a conversion that produced no request cannot own a result")

      ;; 4311 echoes the source document it read out of the entry. If the
      ;; echo is not the document this conversion submitted, the pairing is
      ;; wrong — and an outcome written against the wrong invoice is worse
      ;; than none, because it reads as an answer.
      (and (some? echoed) (not= echoed invoice))
      (merge (identity-of converted)
             {:handoff/outcome :misattributed
              :handoff/answered-for echoed
              :handoff/why (str "the ledger answered for source document "
                                (pr-str echoed) " where " (pr-str invoice)
                                " was submitted; the results are out of order "
                                "and no outcome here can be attributed")
              :handoff/response result})

      :else
      (let [status (status-code (:status result))
            outcome (result-outcome status (:outcome result))]
        (merge (identity-of converted)
               {:handoff/outcome outcome
                :handoff/status status
                :handoff/posting (:posting result)}
               (cond-> {}
                 (= :held outcome) (assoc :handoff/violations
                                          (vec (:violations result)))
                 (:error result) (assoc :handoff/error (:error result))
                 (= :unreadable outcome) (assoc :handoff/response result
                                                :handoff/raw-status status)))))))

(defn facts
  "A 207 batch reply as one ledger fact per submitted entry.

      {:handoff/status :paired | :length-mismatch | :unreadable-batch
       :handoff/facts  [fact …]
       :handoff/summary {outcome count}}

  `converted` is the sequence of conversions that were submitted, IN
  SUBMISSION ORDER — 4311 returns `:results` in that order and that is the
  only thing joining a result to its entry.

  ## A length mismatch is refused, not zipped

  If the counts differ, pairing by position misattributes EVERY outcome
  after the first missing one, and the entries that fall off the end of the
  shorter sequence get no outcome at all while the batch reports a clean
  run. There is no partial pairing that is safe here, so there is none.

  ## The refusal is itself a fact

  A refused batch still returns `:handoff/facts` with one fact in it,
  describing the mismatch. Returning an empty vector would mean a caller
  looping over the facts and appending them wrote NOTHING to the ledger,
  and a hand-off that failed would look exactly like a hand-off nobody
  attempted — which is the defect this namespace exists to remove,
  reproduced by the namespace itself."
  [converted batch]
  (let [converted (vec converted)
        results (get-in batch [:body :results])
        wrap (fn [status f] {:handoff/status status
                             :handoff/facts [f]
                             :handoff/summary {(:handoff/outcome f) 1}})]
    (cond
      (not (sequential? results))
      (wrap :unreadable-batch
            {:handoff/outcome :unreadable
             :handoff/status (status-code (:status batch))
             :handoff/submitted (count converted)
             :handoff/why (str "the batch reply carries no :results sequence, "
                               "so not one of the " (count converted)
                               " submitted entries has a known outcome")
             :handoff/response batch})

      (not= (count converted) (count results))
      (wrap :length-mismatch
            {:handoff/outcome :length-mismatch
             :handoff/status (status-code (:status batch))
             :handoff/submitted (count converted)
             :handoff/answered (count results)
             :handoff/why (str "submitted " (count converted) " entries and got "
                               (count results) " results; results are joined to "
                               "entries by position only, so pairing these would "
                               "attribute outcomes to the wrong invoices")
             :handoff/response batch})

      :else
      (let [fs (mapv result-fact converted (vec results))]
        {:handoff/status :paired
         :handoff/facts fs
         :handoff/summary (frequencies (map :handoff/outcome fs))}))))
