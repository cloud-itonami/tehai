# CLAUDE.md — cloud-itonami/tehai 手配

Professional-services actor. itonami pattern: advisor ⊣ independent governor ⊣
append-only ledger. Money arithmetic is `kotoba-lang/psa`; this repo is the
governed shell.

## Rules that are not negotiable

**Nothing is billed at a price nobody set.** `:unpriced-time` has no escalation
path and no "default rate" setting. The fix for unpriced work is to add the rate
card, never to let an approver wave through an amount the client never agreed to.

**No margin without both sides.** `kotoba.psa/margin` returns `:unknown` when any
entry lacks a cost rate, and `:fabricated-margin` holds a proposal that states a
number anyway. Do not substitute revenue, impute a blended cost, or drop
uncosted entries from the denominator.

Deliberately *not* a hold: assigning against **undeclared capacity**. Absent
capacity is unknown, not exceeded. If that becomes a problem, require capacity at
registration — do not invent a limit.

## Costs, currency and revenue

**Recording a cost is never gated on convertibility.** A foreign-currency expense
records even with no FX rate on file — the cost was incurred either way, and
refusing to record it erases it. Do not move that gate onto `:record-expense`.

**`:incomplete-total` is a hold with no approval route.** If any billable
component cannot be converted into the project's billing currency, the total is
wrong by an unknown factor and approving it means signing a number nobody can
check. No approval makes an unconverted currency converted. Drafting is
deliberately not blocked — a draft is not a commitment.

## Drafting is not issuing

Only `:issue-invoice` touches the billed set, and it **always** escalates. A
draft that is never issued must not lock hours away from a later, correct
invoice. Once issued, the entry keys are permanent — that is what makes a second
attempt a hard hold rather than an awkward conversation with the client.

## What the model may do

Propose which entries to bill. Not what they are worth. The governor redrafts the
invoice from the store's own entries and rate cards and compares totals, so an
inflated figure is held rather than corrected.

## Store and edge

`MemStore` ≡ `DatomicStore` — same protocol, same contract test; write both
sides of any store change. The Datomic store **derives** the billed set from
committed invoices so there is one source of truth.

The HTTP surface is **one route**: `POST /api/invoice/draft`. Issuing and
`:report-margin` have no HTTP representation (margin exposes cost rates — the
firm's own commercial position). An absent allow-list serves **503**. An unset `TEHAI_STORE` serves **503** too — refusing beats
returning `:no-worker` and blaming the caller for a storeless deployment.

## Test

    clojure -M:test && clojure -M:lint
