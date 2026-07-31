# Contributing

The domain arithmetic — rate lookup, proration, margin, utilization, invoice
drafting — lives in the capability library `kotoba-lang/psa`. This repo holds
the governed actor. Fixes to how money is *computed* belong upstream; fixes to
what may be *committed* belong here.

Keep drafting, issuing and staffing behind the governor. Nothing may write to
the store outside the `:commit` node, and only `:issue-invoice` may add to the
billed set.

Three invariants come from `kotoba-lang/psa` and are load-bearing here:

1. No rate, no line — unpriced time is never billed at zero or at a default.
2. Margin needs both sides — `:unknown` is the answer when cost data is missing.
3. Utilization needs a denominator — no capacity, no ratio.

Before opening a PR:

```bash
clojure -M:lint
clojure -M:test
```

`GOVERNANCE.md` lists the rules that are not up for discussion.
