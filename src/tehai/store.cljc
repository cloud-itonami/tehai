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
    invoice    — a committed invoice. Its entry keys join the billed set
                 permanently, which is what makes double-billing
                 detectable rather than merely unlikely.
    ledger     — append-only audit trail of every proposal/verdict/
                 disposition, commit or hold.")

(defprotocol Store
  (client [s client-id])
  (project-of [s project-id])
  (projects-of [s client-id])
  (person [s person-id])
  (rate-cards [s])
  (capacities [s])
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
  (rate-cards [_] (:rate-cards @a))
  (capacities [_] (:capacities @a))
  (assignments [_] (:assignments @a))
  (entries [_] (:entries @a))
  (billed-keys [_] (:billed @a))
  (invoices [_] (:invoices @a))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s c] (swap! a assoc-in [:clients (:client/id c)] c) s)
  (register-project! [s p] (swap! a assoc-in [:projects (:project/id p)] p) s)
  (register-person! [s p] (swap! a assoc-in [:people (:person/id p)] p) s)
  (register-rate-card! [s card] (swap! a update :rate-cards conj card) s)
  (register-capacity! [s cap] (swap! a update :capacities conj cap) s)
  (register-entry! [s entry] (swap! a update :entries conj entry) s)
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
                                    :rate-cards [] :capacities [] :assignments []
                                    :entries [] :invoices [] :billed #{}
                                    :records [] :ledger []}
                                   seed)))))
