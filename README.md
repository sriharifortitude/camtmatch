# camtmatch

[![CI](https://github.com/sriharifortitude/camtmatch/actions/workflows/ci.yml/badge.svg)](https://github.com/sriharifortitude/camtmatch/actions/workflows/ci.yml)

Bank-statement reconciliation for accounts receivable. Import the ISO
20022 **camt.053** statement your bank sends every morning; camtmatch
matches each incoming payment to the open invoice it pays, marks the
conclusive ones paid, and puts the rest in front of a person with the
evidence for and against.

It is the job an accountant does by eye every morning in every European
company that sends invoices, and the one most often done badly by
software that matches on amount alone.

## How it decides

Three passes over every credit, strongest evidence first, so a weak match
can never take an invoice a strong one would have claimed:

| pass | evidence | result |
| --- | --- | --- |
| 1 | the **structured creditor reference** (ISO 11649 `RF…`), checksum-valid, equals the invoice's | **matched** if the amount is equal; **suggested** as a partial or over-payment otherwise |
| 2 | the **invoice number or RF reference in the free text**, found after removing everything but letters and digits — banks break remittance lines at 35 characters, often mid-number | same as pass 1; several invoices named and summing to the payment is a **matched** combined payment |
| 3 | the **same amount, paid from the IBAN on file** for the customer | **suggested**, never matched |

An amount alone never matches, however unique: a customer paying the
wrong invoice of the same value is the common case, not the rare one. An
invoice consumed by a match is not offered to a later payment. A
reference with a wrong check digit is ignored and the reason recorded.

Every result carries its reasons in plain sentences:

```
E2/0  SUGGESTED  i130   creditor reference RF18 5390 0754 7034 is invoice INV-2026-000130
                        partial payment: 500.00 of 750.00 due
E3/0  MATCHED    i125,i126
                        invoices INV-2026-000125, INV-2026-000126 named in the remittance text
                        amounts sum to 357.00, the payment amount
E5/0  UNMATCHED         1 open invoice(s) for this amount and nothing else to tell them apart
E8/0  UNMATCHED         structured reference "RF19539007547034" fails the ISO 11649 checksum and was ignored
                        no open invoice for 60.00 EUR
```

(Output from the test fixture `src/test/resources/camt/statement-v08.xml`,
which has one entry per rule; the header comment lists what each should
produce, and the tests assert exactly that.)

## What else it gets right

- **Balances are checked.** Opening balance plus booked entries must equal
  the closing balance. A statement that does not add up is imported, but
  nothing from it is applied automatically — every match is left for a
  person, with the discrepancy in its reasons.
- **Statements are idempotent.** (account, statement id) is unique;
  importing the same file twice is a 409. Imports for one account are
  serialised with a Postgres advisory lock, and the integration test fires
  the same statement twice concurrently: one 201, one 409, invoices paid
  once.
- **camt.053 versions .001.02 to .001.13**, including batch bookings with
  several transactions per entry, `NOTPROVIDED` end-to-end ids, and the
  paths that moved between versions.
- **Hostile XML is refused.** DTDs are disallowed outright, so XXE and
  entity expansion never start; bodies over 20 MB are refused before
  parsing. Both are tested over HTTP.
- **Exact money.** `BigDecimal` in Java, `numeric(18,2)` in Postgres; no
  floating point anywhere near an amount.
- **Errors are RFC 9457 problem details**, with the field or the XML path
  that was wrong.

## API

    PUT  /api/invoices/{id}             upsert an open invoice (a paid one cannot be edited)
    GET  /api/invoices?status=open|paid
    POST /api/statements                body: camt.053 XML → per statement: counts by outcome, how many applied
    GET  /api/statements/{id}/matches   every transaction with outcome, state, reasons, invoice ids
    POST /api/matches/{id}/confirm      a person accepts a suggestion; invoices become paid
    POST /api/matches/{id}/reject

`Authorization: Bearer <CAMTMATCH_API_TOKEN>` on every `/api` call; the
service refuses to start without a token of at least 32 characters.
`/actuator/health` (with liveness and readiness groups) stays open for
probes.

## Running it

    docker compose up -d --wait                    # Postgres on 127.0.0.1:5437
    export CAMTMATCH_API_TOKEN=$(openssl rand -hex 24)
    ./gradlew bootRun

    curl -X PUT localhost:8080/api/invoices/i123 -H "authorization: Bearer $CAMTMATCH_API_TOKEN" \
      -H 'content-type: application/json' \
      -d '{"number":"INV-2026-000123","amountDue":1190.00,"currency":"EUR","creditorReference":"RF98INV2026000123","customerName":"ACME Handels GmbH"}'
    curl -X POST localhost:8080/api/statements -H "authorization: Bearer $CAMTMATCH_API_TOKEN" \
      -H 'content-type: application/xml' --data-binary @src/test/resources/camt/statement-v08.xml

Or `./gradlew bootTestRun`, which starts its own Postgres in a container.
The image: `docker build -t camtmatch .` (non-root, JRE only).

## Checks

    ./gradlew build        # javac -Xlint:all -Werror, 25 unit tests, 5 HTTP tests against Postgres via Testcontainers

The IBAN and RF checksums in the tests were computed independently before
being written in; the matcher's expected outcomes come from the fixture's
design, not from running the matcher.

## Design notes

1. [Evidence, not scores](docs/adr/0001-evidence-not-scores.md)
2. [Parse camt.053 by hand, refuse DTDs](docs/adr/0002-hand-parser-no-dtd.md)

## What it deliberately does not do

- **No ledger integration.** Invoices arrive through `PUT`; paid status
  goes back however your ERP takes it. A connector per ERP is the product
  work this deliberately stops before.
- **No fuzzy name matching.** Customer names are recorded and shown, not
  used as evidence; "ACME GmbH" paying for "Acme Handels GmbH" is exactly
  the kind of guess a person should make.
- **No outgoing payments.** camt.053 debits are recorded as
  `NOT_APPLICABLE`. Matching them to supplier invoices is the mirror image
  and would reuse everything here.
- **One currency per match.** A EUR payment never pays a CHF invoice; FX
  settlement is out of scope.
- **Partial payments are suggestions, not instalments.** Confirming one
  marks the invoice paid; tracking a remaining balance is a ledger's job.
- **No user accounts.** One token; who confirmed what is not recorded
  beyond the time. An SSO proxy in front, and its access log, is the
  intended deployment.

## Licence

Business Source License 1.1. Free for evaluation, development and
non-commercial use; production use needs a commercial licence. Converts to
Apache 2.0 on 2030-09-23. See [LICENSE](LICENSE).
