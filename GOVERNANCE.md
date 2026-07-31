# Governance

Maintained by the cloud-itonami org. The actor pattern (advisor-LLM ⊣
independent governor, append-only audit ledger, ADR-2607011000) is
non-negotiable; external-send actions require human approval.

Two rules here are stronger than the fleet default and are not maintainer
discretion.

**Nothing is billed at a price nobody set.** `:unpriced-time` has no
escalation path and no "default rate" configuration. Time whose `[project role]`
has no rate card is held, and the fix is to add the card — not to let an approver
wave through an amount the client never agreed to.

**No margin without both sides.** `kotoba.psa/margin` returns `:unknown` when any
contributing entry lacks a cost rate, and `:fabricated-margin` holds any proposal
that states a number anyway. A partially costed project produces a figure that
looks like profit and is really missing data; a PR that substitutes revenue,
imputes a blended cost, or drops uncosted entries from the denominator will be
closed.

Deliberately *not* a hold: assigning against undeclared capacity. Absent capacity
is unknown, not exceeded. If that becomes a problem in practice, the fix is to
require capacity declarations at registration, not to invent a limit.
