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

## インボイス制度 — the issuing side

`cloud-itonami-isco-4311` checks the **receiving** side (may this journal
entry claim 仕入税額控除). tehai checks the other half: is the invoice we are
about to send something its recipient could credit at all? Both read
[`kotoba-lang/taxlaw`](https://github.com/kotoba-lang/taxlaw), which exists
because a second actor needed the same law rather than a second copy of it.

The jurisdiction that decides is the **client's**, because the client is who
would claim the credit — an issuer that could nominate one would nominate a
jurisdiction it satisfies.

| client declares | invoice carries | verdict |
|---|---|---|
| `:jp` | a valid `T`+13 registration number | proceeds |
| `:jp` | nothing, or a malformed number | HARD `:invoice-not-creditable` |
| `:eu` | a number with an ISO 3166 alpha-2 prefix | proceeds, **and says what it did not check** |
| `:eu` | nothing, or something without that prefix | HARD `:invoice-not-creditable` |
| `:us` | anything | HARD `:unchecked-invoice-jurisdiction`, **and says why** |
| a jurisdiction taxlaw does not cover | anything | HARD `:unchecked-invoice-jurisdiction` |
| **nothing** | anything | **proceeds, and says it was not checked** |

That last row is the one worth arguing about. A client that declares no
jurisdiction has asserted nothing, and holding every such invoice would stop
every existing caller — so it is not held. But it is **not silently passed
either**: the verdict carries `:tax {:taxlaw/coverage :not-declared}`, so a
console shows *this was not checked* rather than an unqualified approval.
Same device as kintai's `:unevaluated`, and the same reason — a question
nobody could answer belongs next to the answer, not inside it.

**Measured**: dropping that `:tax` key reddens three tests, including one
that exists solely to assert the not-checked case is visible.

### Issuing outside Japan (taxlaw pin `d2663b5`, 2026-08-18)

taxlaw gained `[:eu]` and `[:us]`, and with them coverage that is **per facet,
not per jurisdiction**. That distinction is the whole point: `credit-support`
used to gate on `covered?`, and `requires-qualified-invoice?` returns nil for a
facet the catalog lacks — so `(or (not needs?) …)` was `true`, and adding a
jurisdiction with no invoice rule would have turned a claim there from *held*
into *approved with no registration number*.

**`[:us]` is that case, and it did not happen.** There is no federal VAT, so
the input-tax-credit facet is `:out-of-scope`; taxlaw still answers
`:taxlaw/coverage :none` and this actor still HOLDS, byte-identically to the
way it holds an invoice into a jurisdiction nobody has read at all. Measured
against `:atlantis` as the control: same `:ok?`, same `:hard?`, same
`:escalate?`, same rule set. What is new is only that the violation carries
`:out-of-scope` and `:why`, so an operator reads *there is no federal VAT*
instead of *nobody has catalogued this* — which would have been false.

**`[:eu]` is a real widening, and a narrow one.** Article 226 is a closed list
of required details and (3) is the supplier's VAT identification number, so an
EU invoice without one is now `:invoice-not-creditable` rather than merely
unchecked. But Article 215 gives the number's format as an ISO 3166 alpha-2
prefix (with `EL` for Greece) **and nothing else** — the body is Member State
law taxlaw has not read. So `"XX1"` passes.

A verdict reporting only `:taxlaw/supported? true` would be read as *the VAT
number is valid*, which is three claims more than was measured. So a pass that
rests on a partial check carries `:tax-registration-unchecked`:

```clojure
#{:member-state-is-a-member :body-format :check-digit}
```

Absent — not empty — when there is nothing to qualify: on a refusal, on a
non-invoicing op, and for `[:jp]`, where the catalog declares no breakdown at
all. An empty set would read as *nothing was left out*, which is a claim
nobody made.

**Retention is nil for both, and that is the instrument's answer.** Article
247(1) hands the period to the Member State; 26 CFR § 1.6001-1(e) states a
condition (*so long as the contents thereof may become material*) and no
number — the widely-repeated "seven years" appears nowhere in it. This actor
surfaces **no** retention period in any jurisdiction: the jurisdiction it holds
is the client's, because the client claims the credit, while how long the
issuer keeps its own copy is the issuer's law. Answering one with the other is
worse than silence. The suite pins both halves — that the nils stay nil, and
that no verdict key claims a period.

## 仕訳 — an issued invoice becoming a journal entry

Deciding is not bookkeeping. **An invoice that was issued and never became a
journal entry is revenue nobody's books show.** `tehai.shiwake/entry-request`
turns a committed `:issue-invoice` run into the `:draft-entry` request
[`cloud-itonami-isco-4311`](https://github.com/cloud-itonami/cloud-itonami-isco-4311)
accepts at `POST /api/entry`.

**It produces a value; it does not make a call.** No HTTP, no client, no
reference to 4311 — and no reference to `tehai.store` or `tehai.actor` either.
The namespace requires exactly `clojure.string` and `kotoba.taxlaw`, asserted
by a test that reads its own source: an allow-list of pure value libraries,
plus an explicit prohibition on `tehai.store`, `tehai.actor`, `tehai.governor`
and `kotoba.psa`. It must not be able to look up the ledger, and must not
price anything. Two reasons, and the second carries more weight:

1. This actor's ceiling is that it proposes. `:issue-invoice` already always
   escalates to a human; reaching past that to write into another actor's
   ledger would be the actuation the whole design refuses.
2. **A call would make the accounts this actor's business, and they are not.**
   Which account a consulting fee credits is the client's chart, and
   `kotoba-lang/shohyo` refuses to guess what an account is precisely because a
   statement that guessed still balances. So the mapping is an argument.

**Direction is the other way round from an expense.** An issued invoice
recognises revenue: 売掛金 debit, 売上 credit — the mirror of `keihi.shiwake`,
where a claim debits a cost account and credits 未払金. The debit is a
*receivable* and not cash on purpose: issuing is not collecting.

### 消費税 — two ways to get a figure, and one that stays refused

`kotoba.psa/invoice` computes no tax at all, so the figure has to come from
somewhere else. `:invoice/tax`:

| `:invoice/tax` | entry |
|---|---|
| a number > 0 | three lines — dr 売掛金 total / cr 売上 (total − tax) / cr 仮受消費税 tax, `:tax-treatment :stated` |
| `0` or `:none` | two lines, `:tax-treatment :none` — the absence travels as an assertion |
| absent, and no basis | **`:tax-not-stated`.** No entry |

**Still refused, permanently: multiplying a total by a rate read off a
jurisdiction table.** That produces an entry that balances and is wrong, and
「we applied 10%」 and 「the issuer told us the tax was ¥24,000」 are not the same
claim. An unstated figure is unstated, not zero — the same rule the governor
applies to an uncatalogued jurisdiction.

**Now computed, because it is a different act.** 消費税法施行令 第七十条の十
leaves the issuer exactly three decisions: which of the two methods (第一号
税抜価額 / 第二号 税込価額), which way 「端数を処理する」 rounds, and how the
lines were grouped by rate (「税率の異なるごとに区分して合計した金額」). State
all three and nothing is left to guess:

```clojure
:invoice/tax-basis {:jurisdiction :jp          ; the ISSUER's, not the client's
                    :method       :tax-exclusive
                    :rounding     :floor
                    :subtotals    {:standard 240000}}
```

That goes to `kotoba.taxlaw/consumption-tax-amount` verbatim, which multiplies
**the per-rate subtotal** once and rounds **that one figure** once. Taxing each
line and summing is a *third* method and the article offers two — measured: two
¥1,005 lines give 201 on the subtotal and 200 per line.

Neither the method nor the rounding is defaulted here, because taxlaw refuses
an unstated one: 「いずれかとする」 and 「端数を処理するものとする」 are both
choices the article hands the issuer, and a library that picks one is wrong by
¥1 per rate on every invoice forever. And the grouping is never invented from
the lines — which rate a supply falls under is the entity's judgement, and
making it visible is what the 政令's shape is for.

The jurisdiction on the basis is the **issuer's**, and it is deliberately not
the one the governor used. The governor asks whether the recipient could claim
a credit, so it reads the *client's*. 施行令 第七十条の十 governs what a
適格請求書発行事業者 writes on the invoice it issues. Two questions about tax
that are not the same question. Outside Japan there is nothing here to compute:
`[:eu]` has no Union-level analogue (Article 226(10) requires the amount to
appear but fixes no rounding) and `[:us]` has no federal consumption tax — both
`:tax-not-derivable`, with the catalog's own reason attached.

#### A derived figure and a handed-in one are not the same claim

They produce **identical lines**, so the result says which it is:

| `:shiwake/tax-source` | |
|---|---|
| `:stated` | the issuer told us ¥24,000 |
| `:derived` | we computed ¥24,000 from stated subtotals under a stated method and rounding |
| `:stated-and-derived` | both, and they agree |
| `:none` | the issuer stated there is no tax component |

Only the middle two are reproducible, and they carry `:shiwake/tax-derivation`
— jurisdiction, provision, method, statute clause, rounding, the subtotals as
given, the per-category figures — which is enough to recompute the number
without this actor. The suite pins that: it hands the recorded inputs back to
taxlaw and gets the recorded figure.

**The provenance does not travel to 4311.** The emitted request is
byte-for-byte the shape it has always been (`#{:op :source-doc :tax-treatment
:lines}`). This actor cannot change what another actor accepts, and inventing a
key in a body whose acceptor is *copied* here rather than depended on is
exactly the failure this repo keeps naming: two green suites and an entry lost
between them.

The 適格請求書 question itself is already settled upstream: an invoice its
recipient could not credit is a HARD hold and never reaches `:commit`.

### Every way the hand-off could lose an invoice is a named status

| | |
|---|---|
| `:draft-only` | a committed `:draft-invoice` (or any non-issuing op). No receivable exists and nobody has to act |
| `:not-issued` | an issue that was held or is awaiting sign-off — **its own status**, because a caller treating "no entry" as "nothing to do" would skip exactly the ones somebody must look at. Merging it with `:draft-only` would either send an operator to an empty queue or leave a real one unwatched |
| `:no-mapping` | the client has no 売掛金 account, or the revenue category no 売上 account. No suspense-account fallback: 仮受金 would make the entry appear and the missing decision disappear. A half-filled mapping is no mapping — an entry missing one line balances by having lost it. Only accounts the emitted lines need are demanded, so a zero-tax invoice needs no 仮受消費税 account |
| `:tax-not-stated` | above. Separate from `:unusable-invoice` because it is a question for the issuer, not a bug in the producer |
| `:tax-not-derivable` | a `:invoice/tax-basis` 第七十条の十 could not be applied to — usually the method or the rounding is missing, sometimes the jurisdiction has no such article. The issuer must finish the declaration it started |
| `:tax-basis-unreconciled` | the per-rate subtotals and the invoice total describe different invoices. Under 税抜 the subtotals plus the tax must **be** the total; under 税込 they must be it. A basis that computes cleanly against a total it does not describe is the dangerous one, because the figure it produces looks right |
| `:tax-disagrees` | the stated figure and what its own basis computes differ. Two figures on one invoice, one wrong, and this actor does not get to pick — posting either would balance |
| `:unusable-invoice` | no positive total, no id, no currency, or a tax figure that is not a number below the total |

Four tax refusals and not one, because each is a different piece of work and
three of them are not the issuer's arithmetic. A basis that fails is refused
**even when a figure was also stated**: ignoring it would let an issuer believe
its own arithmetic had been checked when it had not.

The invoice id travels as `:source-doc`, so 4311 refuses an entry citing a
document its own registry does not know — the ledger's registry is the one that
counts. The emitted body **never carries `:client-id`**: 4311 rejects a body
naming a client (400) rather than ignoring it, because the caller's client is
derived there from the verified DID.

`entry-requests` returns `{:ok [...] :skipped [...]}` rather than filtering, and
each refusal carries the invoice it refused.

**Measured**, all 15 mutations red: emit for a held invoice (2 tests), emit for
a draft (2), suspense-account fallback (2), drop the tax line (3), credit the
whole total to 売上 (3), treat an unstated tax as zero (2), invent a 10% rate
(2), flip the direction (3), batch discards its skips (1), refusal drops the
invoice (1), drop the source document (2), name the client in the body (2),
accept a non-positive total (1), tolerate a half-filled mapping (2), drop the
currency requirement (1). The non-positive-total mutation was **green on the
first pass** — every taxed case was caught downstream by `tax >= total`, so a
zero-amount entry would have gone out balanced; the suite now states the total
check with the tax stated as `:none`.

## 受領確認 — what the ledger did with the entry

Converting is not posting. `tehai.shiwake` produces the request; **nothing
recorded what happened to it.** 4311 can commit the entry, find it already
there, hold it against a rule, park it for a human, or refuse the body — and
from this side all five arrived as a value nobody wrote down. An invoice that
was issued, converted, submitted and *refused* is revenue nobody's books show,
and it was indistinguishable from one that posted.

`tehai.handoff/fact` turns one reply into a ledger fact the actor appends with
the `tehai.store/append-ledger!` it already has — no new store call, no new
schema, beside the graph's own `:commit` and `:hold` facts.

| reply | `:handoff/outcome` |
|---|---|
| 200, `:duplicate? false` | `:posted` |
| 200, `:duplicate? true` | `:duplicate` — **not** `:posted`. One call wrote the posting; the other found it already there, and to whoever reconciles those are different events |
| 202 | `:awaiting-approval`, with the escalation reason |
| 409 | `:held`, with the violations |
| 400 | `:rejected` — the body this actor emitted |
| 403 | `:not-permitted` — who it authenticated as |
| 503 | `:unavailable` — a ledger deployment with no store or allow-list |
| anything else, a body that is not a map, or a 200 that does not say whether it duplicated | `:unreadable`, carrying the whole response |

400/403/503 stay three answers where 4311's own batch collapses them to
`:rejected`, because they send a reader to three different places — the same
argument that actor makes about its own 503. **The good outcome is recorded
too**: a ledger holding only refusals cannot answer 「これは計上されたか」,
which is the one question the hand-off exists to close.

Every fact names the invoice, the client and 4311's posting id, so it joins
back to what it reconciles. `:handoff/posting` is present even when nil.

**It produces a value; it does not make a call** — it is handed a reply
somebody else obtained. Requires exactly `clojure.string`, asserted by a test
that reads its own source.

`handoff/facts` does the 207 batch. Results are joined to entries **by
position only**, so a length mismatch is refused rather than zipped: one
missing result misattributes every outcome after it while the entries that
fall off the end get none at all. A result echoing a different `:source-doc`
is `:misattributed` for the same reason. **A refused batch still returns one
fact** — returning none would mean a caller looping over them wrote nothing,
and a failed hand-off would look exactly like one nobody attempted, which is
the defect this namespace exists to remove.

**Measured**, `nbb tools/mutate.cljs`: 14 mutations, 14 killed, 0 survivors, 0
unmeasured. One was a **survivor** on the first pass of 13: making the status
check on an unsubmitted conversion unconditional reddened nothing, because
every refusal `shiwake` itself emits also lacks a source document and was
caught by the second guard. The suite now states it with a refusal carrying a
leftover request — the status is what says whether something was submitted,
not the presence of a request-shaped map — and the batch side of the same
guard got its own mutation, which is the fourteenth. That table
covers `tehai.handoff` and nothing else, and says so in its own header.

## The shared governor layer

`:no-client`, `:no-actuation`, `:unknown-project` and `:project-wrong-client`
are not PSA rules — every actor in this fleet has them, and they were
hand-copied into 376 governors, one of which silently drifted into reporting
a HARD violation as escalatable. They now come from
[`kotoba-lang/governor`](https://github.com/kotoba-lang/governor), along
with the verdict assembly. `:project-wrong-client` uses that library's
`:scope-key`, because a psa project carries ownership as `:project/client`
while the request carries `:client-id`.

`test/tehai/conformance_test.clj` pins every disposition against
`gov/conformance-failures`. Unlike its sibling actors, tehai's existing
suite **already** caught the drift in two tests
(`a-hard-violation-outranks-escalation`,
`an-unconvertible-billable-expense-blocks-issuing`) — measured by
re-injecting it. The conformance suite widens that from two incidental
cases to every disposition, deliberately.

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

## Costs, currency and revenue

Seven ops, not four. `kotoba-lang/psa` grew expenses, subcontractors, revenue
recognition and multi-currency; these are the ones tehai now governs.

```clojure
(actor/run-request! g {:client-id "c-1" :op :record-expense :project "alpha"
                       :expense (psa/expense "e-1" "alpha" 50000 "JPY" :billable? true)} {} "t-1")
(actor/run-request! g {:client-id "c-1" :op :record-subcontract :project "alpha"
                       :subcontract sub} {} "t-2")
(actor/run-request! g {:client-id "c-1" :op :recognize-revenue :project "alpha"} {} "t-3")
```

**Recording a cost is never gated on convertibility.** A JPY expense records even
with no JPY→USD rate on file: the cost was incurred either way, and refusing to
record it would erase it. That is the same shape as kintai refusing to gate punch
recording on lawfulness — the gate belongs downstream, not on the evidence.

### An incomplete total may not be issued

`:incomplete-total` is a **hold with no approval route**. If a billable expense
or a subcontractor line is in a currency the store cannot convert into the
project's billing currency, the total is wrong by an unknown factor — and
approving it means signing a number nobody can check.

```clojure
;; billable JPY expense, no JPY→USD rate
;; => :hold, :incomplete-total, listing [[:expense "e-1"]]
(governor/total-completeness st "alpha")
;; => {:complete? false :unconvertible [[:expense "e-1"]] :currency "USD"}
```

No approval makes an unconverted currency converted, so unlike `:issue-invoice`'s
ordinary escalation this one does not reach a human at all. Drafting is *not*
blocked — a draft is not a commitment. Two rate cards in different currencies
also block issuing (`:ambiguous-invoice-currency`): there is no single answer to
what the invoice is denominated in.

`:report-margin` now uses `psa/project-margin`, so labour, expenses and
subcontractors are each visible instead of only the net. Invariant 2 is
unchanged — one uncosted labour entry and the whole figure is still `:unknown`.

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
| Tests | 58 tests, 171 assertions, all green |
| Store | `MemStore` + `DatomicStore` (langchain.db), proved interchangeable by a contract test |
| Deployment | Cloudflare Pages Functions — `POST /api/invoice/draft`, CACAO + allow-list gated |
| Not covered | time-off, resource forecasting, project accounting beyond margin and recognition |

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

The deploy artifact is **built and exercised**, not merely configured:

```bash
npm install && npx shadow-cljs release edge-api   # -> functions/edge/
```

The `:esm` release runs `:advanced` optimization, which `cljs.main -c` does not,
so it is the first thing that could rename `TEHAI_STORE`,
`TEHAI_CALLER_ALLOWLIST` or `authorization` out from under the handler. They
survive (`:infer-externs :auto`), the module loads under Node, and invoking
`draftOnRequestPost` against a mock Cloudflare context returns 503 unset, 503 on a typo'd
store mode, and 401 for a bad CACAO. Running it for real is also what surfaced a
multi-line string literal leaking source indentation into the JSON `hint`.

## Test

```bash
clojure -M:test
clojure -M:lint
```

## License

AGPL-3.0-or-later.
