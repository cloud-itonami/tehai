# Security Policy

This repo holds a governed actor and no running service: no endpoint, no
scheduler, no credentials, no client data. `MemStore` keeps everything in
process memory and persists nothing.

The data this actor would hold in production is commercially sensitive —
rate cards reveal margins, and an invoice reveals what one client pays
relative to another. Two properties are load-bearing:

- A client may only ever see its own projects. `:project-wrong-client` is a
  hard hold, and `llm-advisor` is passed one project's entry keys, never
  another client's projects and never contract terms.
- Committed invoice entry keys are permanent. That is what makes
  double-billing detectable, and it means an invoice cannot be silently
  un-issued to re-bill the same hours.

Report privately to root@junkawasaki.com.
