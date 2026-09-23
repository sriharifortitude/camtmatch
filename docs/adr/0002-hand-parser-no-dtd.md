# 2. Parse camt.053 by hand; refuse DTDs

Status: accepted — 2026-09-23

## Context

The usual Java route to ISO 20022 is JAXB classes generated from the XSD
for one message version. Banks send .001.02, .001.04, .001.08 and newer
today, often more than one version across a company's accounts; one
generated binding per version is a lot of code for the dozen fields a
reconciliation needs. Prowide ISO 20022 covers all versions but is a large
dependency whose model is far more than this needs.

Separately: statements come from outside the company. A general-purpose
XML parser with default settings resolves external entities.

## Decision

- A DOM parse and a small set of helpers that find elements by **local
  name**, ignoring the namespace version. The fields read are the ones
  reconciliation uses: statement id, account IBAN, balances, entries,
  per-transaction amounts (batch bookings), end-to-end id, counterparty
  name and IBAN, structured and unstructured remittance.
- Paths that moved between versions are tried both ways
  (`Dbtr/Pty/Nm` then `Dbtr/Nm`; `Sts/Cd` then `Sts`; `Dt/Dt`, `Dt`,
  `DtTm`).
- The document factory disallows DOCTYPE declarations entirely, disables
  external entities and XInclude, and enables secure processing. A
  document with a DTD is refused before any entity is seen. The service
  also refuses bodies over 20 MB before parsing.
- The root namespace must be a camt.053 namespace; a pain.001 or camt.054
  is refused with that reason rather than half-read.

## Consequences

- One parser, ~200 lines, reads every version in circulation. The tests
  cover .001.08 in full and .001.02's differing shapes.
- No schema validation. A document that is well-formed and has the fields
  is accepted even if it violates the XSD elsewhere; the balance check is
  the integrity check that matters for reconciliation.
- DOM holds the whole document in memory. At the 20 MB cap that is
  roughly 200 MB of heap in the worst case, acceptable for one import at a
  time per account; a StAX rewrite is the path if statements grow.
- The XXE refusal is tested at the parser and over HTTP, including that
  the response does not contain file content.
