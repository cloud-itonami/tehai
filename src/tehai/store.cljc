(ns tehai.store
  "SSoT for the tehai (手配) professional-services actor. Store is a
  protocol injected into the `tehai.actor` StateGraph — `MemStore` is the
  default, deterministic, zero-dep backend (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4313's payroll.store; the PSA-domain records use
  `kotoba.psa`'s shapes verbatim — this actor CONSUMES kotoba-lang/psa,
  it does not reinvent rate lookup, proration or invoice arithmetic.

  Domain:

    client     — a registered client (:client/id, :client/name)
    project    — a project belonging to one client. Attribution and
                 invoicing may only name a registered project.
    person     — a registered person who can be staffed
    rate-card  — a `kotoba.psa/rate-card`. Time with no card is
                 unpriced, and unpriced time is never invoiced.
    capacity   — a `kotoba.psa/capacity`. Undeclared capacity is not
                 infinite capacity; it is an unknown one.
    assignment — a `kotoba.psa/assignment`, written ONLY via commit.
    entry      — a `:ts/*` timesheet entry. `:ts/approved?` gates
                 invoicing: unapproved time is not billable time.
    expense    — a `kotoba.psa/expense`. Recording one is NEVER gated on
                 whether its currency can be converted: the cost was
                 incurred either way, and refusing to record it would
                 erase it. The gate belongs on the invoice.
    subcontract — a `kotoba.psa/subcontract`, carrying both the cost and
                 the bill rate explicitly.
    fx-rate    — a `kotoba.psa/fx-rate`. Directional; never inverted.
    revenue-contract — a `kotoba.psa/contract` (:as-delivered /
                 :percent-complete / :on-completion), one per project.
    invoice    — a committed invoice. Its entry keys join the billed set
                 permanently, which is what makes double-billing
                 detectable rather than merely unlikely.
    ledger     — append-only audit trail of every proposal/verdict/
                 disposition, commit or hold."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (client [s client-id])
  (project-of [s project-id])
  (projects-of [s client-id])
  (person [s person-id])
  (rate-cards [s])
  (capacities [s])
  (expenses [s])
  (subcontracts [s])
  (fx-rates [s])
  (revenue-contract [s project-id])
  (assignments [s])
  (entries [s])
  (billed-keys [s])
  (invoices [s])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s c])
  (register-project! [s p])
  (register-person! [s p])
  (register-rate-card! [s card])
  (register-capacity! [s cap])
  (record-expense! [s e])
  (record-subcontract! [s x])
  (register-fx-rate! [s r])
  (register-revenue-contract! [s c])
  (register-entry! [s entry])
  (commit-assignment! [s a])
  (commit-invoice! [s inv])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (project-of [_ project-id] (get-in @a [:projects project-id]))
  (projects-of [_ client-id]
    (filter #(= client-id (:project/client %)) (vals (:projects @a))))
  (person [_ person-id] (get-in @a [:people person-id]))
  ;; Keyed maps, not vectors. `conj`ing a re-registered rate card would
  ;; leave two live cards for one [project role], and `psa/rate-for` takes
  ;; the first match — so an invoice total would silently depend on
  ;; insertion order. Found by the MemStore ≡ DatomicStore contract test.
  (rate-cards [_] (vec (vals (:rate-cards @a))))
  (capacities [_] (vec (vals (:capacities @a))))
  (expenses [_] (vec (vals (:expenses @a))))
  (subcontracts [_] (vec (vals (:subcontracts @a))))
  (fx-rates [_] (vec (vals (:fx-rates @a))))
  (revenue-contract [_ project-id] (get-in @a [:revenue-contracts project-id]))
  (assignments [_] (:assignments @a))
  (entries [_] (vec (vals (:entries @a))))
  (billed-keys [_] (:billed @a))
  (invoices [_] (:invoices @a))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s c] (swap! a assoc-in [:clients (:client/id c)] c) s)
  (register-project! [s p] (swap! a assoc-in [:projects (:project/id p)] p) s)
  (register-person! [s p] (swap! a assoc-in [:people (:person/id p)] p) s)
  (register-rate-card! [s card]
    (swap! a assoc-in [:rate-cards [(:rate/project card) (:rate/role card)]] card) s)
  (register-capacity! [s cap]
    (swap! a assoc-in [:capacities [(:capacity/person cap) (:capacity/from cap)
                                    (:capacity/to cap)]]
           cap) s)
  (register-entry! [s entry]
    ;; psa/entry-key's identity, so re-importing a timesheet upserts.
    (swap! a assoc-in [:entries [(:ts/worker entry) (:ts/date entry)
                                 (:ts/project entry) (:ts/role entry)]]
           entry) s)
  (record-expense! [s e] (swap! a assoc-in [:expenses (:expense/id e)] e) s)
  (record-subcontract! [s x] (swap! a assoc-in [:subcontracts (:sub/id x)] x) s)
  (register-fx-rate! [s r]
    (swap! a assoc-in [:fx-rates [(:fx/from r) (:fx/to r)]] r) s)
  (register-revenue-contract! [s c]
    (swap! a assoc-in [:revenue-contracts (:contract/project c)] c) s)
  (commit-assignment! [s x] (swap! a update :assignments conj x) s)
  (commit-invoice! [s inv]
    (swap! a #(-> %
                  (update :invoices conj inv)
                  (update :billed into (:invoice/entries inv))))
    s)
  (commit-record! [s record] (swap! a update :records conj record) s)
  (append-ledger! [s fact] (swap! a update :ledger conj fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :projects {} :people {}
                                    :rate-cards {} :capacities {} :assignments []
                                    :expenses {} :subcontracts {} :fx-rates {}
                                    :revenue-contracts {}
                                    :entries {} :invoices [] :billed #{}
                                    :records [] :ledger []}
                                   seed)))))

;; ---------------------------------------------------------------------------
;; DatomicStore (langchain.db)
;;
;; The same protocol over a Datomic-API-compatible EAV store, so the
;; backend is a swap and not a rewrite (cloud-itonami-isic-6511's
;; underwriting.store is the fleet's reference adopter). Pure `.cljc`: it
;; runs offline against langchain.db's in-process DataScript, and the SAME
;; record points at a real Datomic or a kotoba-server pod by swapping
;; langchain.db's `:db-api` (see langchain.kotoba-db).
;;
;; The billed set is the piece that most needs a durable backend. In
;; `MemStore` it lives for as long as the process does; a firm that
;; restarts its actor and loses which hours were already invoiced has
;; lost the only thing making `:double-billing` a hard hold rather than
;; an awkward conversation. Here it is derived from the committed
;; invoices themselves, so it survives whatever the process does.
;; ---------------------------------------------------------------------------

(def ^:private schema
  (ls/identity-schema [:client/id :project/id :person/id
                       :rate/key :capacity/key :entry/key
                       :expense/id :sub/id :fx/key :contract/project
                       :assignment/id :invoice/id :record/seq :ledger/seq]))

(defn- blobs [conn id-attr edn-attr]
  (->> (d/q [:find [(quote ?v) '...] :where ['?e id-attr '_] ['?e edn-attr '?v]] (d/db conn))
       (mapv ls/dec*)))

(defn- next-seq [conn seq-attr]
  (count (d/q [:find '?e :where ['?e seq-attr '_]] (d/db conn))))

(defrecord DatomicStore [conn]
  Store
  (client [_ id]
    (ls/dec* (d/q '[:find ?v . :in $ ?id
                    :where [?e :client/id ?id] [?e :client/edn ?v]] (d/db conn) id)))
  (project-of [_ id]
    (ls/dec* (d/q '[:find ?v . :in $ ?id
                    :where [?e :project/id ?id] [?e :project/edn ?v]] (d/db conn) id)))
  (projects-of [_ client-id]
    (filterv #(= client-id (:project/client %)) (blobs conn :project/id :project/edn)))
  (person [_ id]
    (ls/dec* (d/q '[:find ?v . :in $ ?id
                    :where [?e :person/id ?id] [?e :person/edn ?v]] (d/db conn) id)))
  (rate-cards [_] (blobs conn :rate/key :rate/edn))
  (capacities [_] (blobs conn :capacity/key :capacity/edn))
  (expenses [_] (blobs conn :expense/id :expense/edn))
  (subcontracts [_] (blobs conn :sub/id :sub/edn))
  (fx-rates [_] (blobs conn :fx/key :fx/edn))
  (revenue-contract [_ project-id]
    (ls/dec* (d/q '[:find ?v . :in $ ?p
                    :where [?e :contract/project ?p] [?e :contract/edn ?v]]
                  (d/db conn) project-id)))
  (assignments [_] (blobs conn :assignment/id :assignment/edn))
  (entries [_] (blobs conn :entry/key :entry/edn))
  (billed-keys [s]
    ;; Derived from the committed invoices rather than kept alongside
    ;; them: one source of truth for "already billed", and no way for the
    ;; two to drift apart.
    (into #{} (mapcat :invoice/entries) (invoices s)))
  (invoices [_] (blobs conn :invoice/id :invoice/edn))
  (records-of [_ client-id]
    (filterv #(= client-id (:client-id %)) (ls/read-stream conn :record/seq :record/edn)))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (register-client! [s c]
    (d/transact! conn [{:client/id (:client/id c) :client/edn (ls/enc c)}]) s)
  (register-project! [s p]
    (d/transact! conn [{:project/id (:project/id p) :project/edn (ls/enc p)}]) s)
  (register-person! [s p]
    (d/transact! conn [{:person/id (:person/id p) :person/edn (ls/enc p)}]) s)
  (register-rate-card! [s card]
    ;; Keyed by [project role] so re-registering a rate REPLACES it. Two
    ;; live cards for the same role would make `rate-for` depend on
    ;; insertion order, and an invoice total would silently depend on
    ;; which one won.
    (d/transact! conn [{:rate/key (pr-str [(:rate/project card) (:rate/role card)])
                        :rate/edn (ls/enc card)}]) s)
  (register-capacity! [s cap]
    (d/transact! conn [{:capacity/key (pr-str [(:capacity/person cap) (:capacity/from cap)
                                               (:capacity/to cap)])
                        :capacity/edn (ls/enc cap)}]) s)
  (register-entry! [s entry]
    ;; Keyed by psa/entry-key, so re-importing the same day's work upserts
    ;; instead of duplicating it — the same content-derived identity that
    ;; makes double-billing detectable.
    (d/transact! conn [{:entry/key (pr-str [(:ts/worker entry) (:ts/date entry)
                                            (:ts/project entry) (:ts/role entry)])
                        :entry/edn (ls/enc entry)}]) s)
  (record-expense! [s e]
    (d/transact! conn [{:expense/id (:expense/id e) :expense/edn (ls/enc e)}]) s)
  (record-subcontract! [s x]
    (d/transact! conn [{:sub/id (:sub/id x) :sub/edn (ls/enc x)}]) s)
  (register-fx-rate! [s r]
    (d/transact! conn [{:fx/key (pr-str [(:fx/from r) (:fx/to r)])
                        :fx/edn (ls/enc r)}]) s)
  (register-revenue-contract! [s c]
    (d/transact! conn [{:contract/project (:contract/project c)
                        :contract/edn (ls/enc c)}]) s)
  (commit-assignment! [s x]
    (d/transact! conn [{:assignment/id (:assign/id x) :assignment/edn (ls/enc x)}]) s)
  (commit-invoice! [s inv]
    (d/transact! conn [{:invoice/id (:invoice/id inv) :invoice/edn (ls/enc inv)}]) s)
  (commit-record! [s record]
    (ls/append-blob! conn :record/seq :record/edn (next-seq conn :record/seq) record) s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (next-seq conn :ledger/seq) fact) s))

(defn datomic-store
  "A DatomicStore over a fresh in-process langchain.db connection."
  []
  (->DatomicStore (d/create-conn schema)))
