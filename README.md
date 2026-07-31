# tehai 手配

**Professional services automation with a governor that redrafts every invoice
before it goes out.** The cloud-itonami fleet's answer to the PSA category
(Kantata / BigTime / Certinia / NetSuite OpenAir / Deltek Vantagepoint), built on
the itonami actor pattern (advisor-LLM ⊣ independent governor, append-only audit
ledger, ADR-2607011000).

手配 = *arranging* — putting the right people on the right work, and being able
to say afterwards what it cost and what it was worth.

```text
approved :ts/* time ──▶ :draft-invoice ──▶ TehaiAdvisor ──▶ TehaiGovernor ──▶ commit | approve | HOLD
capacity + demand   ──▶ :assign-person                            │
                                                                  └──▶ append-only ledger
```

Domain arithmetic lives in [`kotoba-lang/psa`](https://github.com/kotoba-lang/psa)
— rate cards, proration, margin, utilization, invoice drafting, all pure `.cljc`.
This repo is the governed shell around it.

## What the governor refuses

| | HARD hold — never overridable |
|---|---|
| `:no-client` / `:no-actuation` | unregistered client; `:effect` other than `:propose` |
| `:unknown-project` | the cited project is not registered |
| `:project-wrong-client` | billing one client for another's project |
| `:unpriced-time` | an invoice line for time with no rate card — not zero, not a default: held |
| `:unapproved-time` | invoicing entries without `:ts/approved?` |
| `:double-billing` | an entry key already on a committed invoice |
| `:total-mismatch` | the proposal's total ≠ the invoice redrafted from the ledger's own entries |
| `:fabricated-margin` | a numeric margin where `kotoba.psa` says `:unknown` |
| `:over-allocation` | an assignment past declared capacity |

| | escalate — human sign-off |
|---|---|
| `:issue-invoice` | money leaves the system |
| low confidence | below 0.6 |

`:unpriced-time` and `:over-allocation` differ on purpose, and the difference is
the whole design in miniature. **Absent capacity is unknown** — there is nothing
to exceed, so assigning against it is allowed and reported as
`:allocation/capacity-known? false`. **Absent price is a refusal to guess** — so
the invoice is held. One gap means "we do not know"; the other means "we are
about to make something up."

## What the model is allowed to do

Propose which entries to bill. Not what they are worth.

The governor redrafts the invoice from the store's own entries and rate cards via
`kotoba.psa/invoice` and compares totals, so an advisor that inflates a figure is
held rather than corrected. The margin rule is the sharper one: an LLM asked for
a project's profitability will happily return revenue when cost data is missing,
because that is what the numbers look like. `kotoba.psa/margin` returns
`:unknown` in that case and the governor holds any proposal that says otherwise.

## Operations

```clojure
(require '[kotoba.psa :as psa] '[tehai.store :as store] '[tehai.actor :as actor])

(def st (store/mem-store))
(store/register-client! st {:client/id "c-1" :client/name "Acme"})
(store/register-project! st {:project/id "alpha" :project/client "c-1"})
(store/register-rate-card! st (psa/rate-card "alpha" :engineer 15000 :cost 9000))
(store/register-capacity! st (psa/capacity "w-1" from to 40))
(store/register-entry! st {:ts/worker "w-1" :ts/date "2026-01-01" :ts/project "alpha"
                           :ts/role :engineer :ts/hours 8 :ts/approved? true})

(def g (actor/build-graph {:store st}))

(actor/run-request! g {:client-id "c-1" :op :draft-invoice
                       :project "alpha" :invoice-id "inv-1"} {} "t-1")
(actor/run-request! g {:client-id "c-1" :op :issue-invoice
                       :project "alpha" :invoice-id "inv-1"} {} "t-2")  ;; interrupts
(actor/approve! g "t-2")

(actor/run-request! g {:client-id "c-1" :op :assign-person :project "alpha"
                       :assignment (psa/assignment "a-1" "w-1" "alpha" :engineer
                                                   from to 40)} {} "t-3")
(actor/run-request! g {:client-id "c-1" :op :report-margin :project "alpha"} {} "t-4")
```

**Drafting does not lock hours away.** Only `:issue-invoice` adds entry keys to
the billed set — a draft that is never issued must not stop a later, correct
invoice from billing the same work. Once issued, those keys are permanent, which
is what makes a second attempt a hard hold rather than an awkward conversation
with the client.

## Maturity

| | |
|---|---|
| Role | actor (advisor ⊣ governor ⊣ ledger) |
| Capability library | `kotoba-lang/psa` (sibling path) |
| Tests | 40 tests, 128 assertions, all green |
| Store | `MemStore` + `DatomicStore` (langchain.db), proved interchangeable by a contract test |
| Deployment | Cloudflare Pages Functions — `POST /api/invoice/draft`, CACAO + allow-list gated |
| Not covered | time-off, resource forecasting, project accounting beyond margin |

## Store backends

`MemStore` and `DatomicStore` (`langchain.db`) implement the same protocol and
pass the same contract test. The billed set is why that matters most here: a
firm that restarts its actor and loses which hours were already invoiced has
lost the only thing making `:double-billing` a hard hold. `DatomicStore` derives
it from the committed invoices themselves, so there is one source of truth and
no way for the two to drift.

The contract test caught a real divergence on the way in — `MemStore` was
`conj`ing rate cards, so re-registering a rate left two live cards for one
`[project role]` and an invoice total silently depended on insertion order.
Both stores now key by identity.

## HTTP surface

One route, permanently: `POST /api/invoice/draft`. Drafting is safe to expose
precisely because a draft is not a commitment — only `:issue-invoice` adds entry
keys to the billed set, so a caller hammering this route produces drafts and
never bills an hour. **Issuing has no HTTP representation at all**: an invoice
reaches a client because a person resumed the thread, and no request substitutes
for that. `:report-margin` is withheld separately — margin exposes cost rates,
which is the firm's own commercial position and one credential away from being a
competitor's.

Two gates: CACAO signature and temporal window (`cacao.edge.verify`, shared, not
reimplemented), then an allow-list mapping **DID → client id**, which is what
stops a signed caller drafting against another client's project before the
governor's `:project-wrong-client` hold ever runs. **An absent allow-list serves
503, never an open endpoint.**

A second refusal sits in front of it: with `TEHAI_STORE` unset the endpoint serves
**503 "no store configured"** without verifying anything. An empty in-process
store fails the governor's registration check, so the caller would get
`409 :no-worker` and go looking at their own registration while the actual fault
is a deployment with no store. `TEHAI_STORE=ephemeral` enables a non-persisting
smoke test, and every success response then carries `"ephemeral": true`. A
durable backend is not wired yet.

## Test

```bash
clojure -M:test
clojure -M:lint
```

## License

AGPL-3.0-or-later.
