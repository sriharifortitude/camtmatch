# 1. Evidence, not scores

Status: accepted — 2026-09-23

## Context

Reconciliation tools commonly compute a confidence score per candidate
(amount 40 points, name similarity 25, date proximity 15 …) and
auto-apply above a threshold. The score is unexplainable to the person
who must accept or reverse it, the threshold is tuned on somebody else's
data, and the failure mode — a customer paying the wrong invoice of the
same amount — scores highly on every axis.

## Decision

- Three ordered passes with named evidence: structured creditor
  reference; invoice number or reference in the free text; amount plus
  the payer's IBAN on file. Each pass runs over every transaction before
  the next begins, so the strongest evidence claims invoices first.
- Two kinds of positive result. `MATCHED` requires identifying evidence
  (pass 1 or 2) **and** an exactly equal amount; it is applied
  automatically. `SUGGESTED` is everything plausible that falls short;
  it waits for a person.
- Amount-only evidence never matches, however few candidates there are.
- Every result carries its reasons as sentences that name the evidence
  ("creditor reference RF18 … is invoice INV-2026-000130", "partial
  payment: 500.00 of 750.00 due").
- An invalid RF check digit is noted and the reference ignored, not
  "fuzzily" matched: ISO 11649's checksum exists precisely so that a
  mistyped reference is detected rather than attributed.

## Consequences

- A person reviewing suggestions reads why, not a number.
- The unit test is the specification: the fixture's header lists the
  outcome for each entry, and the test asserts outcomes, invoice ids and
  the exact reason text.
- Some payments a scoring engine would have auto-applied are left as
  suggestions here. That is the intended cost: an unapplied payment is a
  minute of someone's time; a wrongly applied one is a dunning letter to
  a customer who paid.
- Adding evidence (e.g. the end-to-end id a customer's ERP echoes back)
  means adding a pass at the right strength, not re-tuning weights.
