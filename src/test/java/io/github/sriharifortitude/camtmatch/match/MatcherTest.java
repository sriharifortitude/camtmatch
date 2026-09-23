package io.github.sriharifortitude.camtmatch.match;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sriharifortitude.camtmatch.camt.Camt053Parser;
import io.github.sriharifortitude.camtmatch.camt.Statement.Transaction;
import io.github.sriharifortitude.camtmatch.core.CreditorReference;
import io.github.sriharifortitude.camtmatch.core.Iban;
import io.github.sriharifortitude.camtmatch.match.Match.Outcome;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Expected outcomes are the ones listed in the fixture's header comment, worked out by hand. */
class MatcherTest {

    static OpenInvoice inv(String id, String number, String due, String rf, String customer, String iban) {
        return new OpenInvoice(id, number, new BigDecimal(due), "EUR",
                Optional.ofNullable(rf).flatMap(CreditorReference::parse), customer, Optional.ofNullable(iban).flatMap(Iban::parse));
    }

    static final List<OpenInvoice> LEDGER = List.of(
            inv("i123", "INV-2026-000123", "1190.00", "RF98INV2026000123", "ACME Handels GmbH", "NL91ABNA0417164300"),
            inv("i130", "INV-2026-000130", "750.00", "RF18539007547034", "Beta SARL", null),
            inv("i125", "INV-2026-000125", "238.00", null, "Gamma BV", null),
            inv("i126", "INV-2026-000126", "119.00", null, "Gamma BV", null),
            inv("i140", "INV-2026-000140", "89.25", null, "Delta Ltd", "GB82WEST12345698765432"),
            inv("i141", "INV-2026-000141", "89.25", null, "Other Ltd", null),
            inv("i150", "INV-2026-000150", "100.00", "RF48INV2026000150", "Epsilon SpA", null),
            inv("i151", "INV-2026-000151", "200.00", null, "Zeta AB", null),
            inv("i012", "INV-2026-000012", "50.00", null, "Somebody Else", null));

    static Map<String, Match> runFixture() {
        List<Transaction> txs = new Camt053Parser().parse(
                MatcherTest.class.getResourceAsStream("/camt/statement-v08.xml")).get(0).entries().stream()
                .flatMap(e -> e.transactions().stream()).toList();
        return new Matcher().match(txs, LEDGER).stream().collect(Collectors.toMap(m -> m.transaction().key(), Function.identity()));
    }

    @Test
    void everyEntryInTheFixtureGetsTheOutcomeItWasBuiltFor() {
        Map<String, Match> r = runFixture();
        assertThat(r.get("E1/0").outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(r.get("E1/0").invoiceIds()).containsExactly("i123");
        assertThat(r.get("E1/0").reasons()).containsExactly("creditor reference RF98 INV2 0260 0012 3 is invoice INV-2026-000123", "amount equals the amount due");

        assertThat(r.get("E2/0").outcome()).isEqualTo(Outcome.SUGGESTED);
        assertThat(r.get("E2/0").invoiceIds()).containsExactly("i130");
        assertThat(r.get("E2/0").reasons()).contains("partial payment: 500.00 of 750.00 due");

        assertThat(r.get("E3/0").outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(r.get("E3/0").invoiceIds()).containsExactly("i125", "i126");
        assertThat(r.get("E3/0").reasons()).containsExactly(
                "invoices INV-2026-000125, INV-2026-000126 named in the remittance text", "amounts sum to 357.00, the payment amount");

        assertThat(r.get("E4/0").outcome()).as("two invoices of 89.25; the payer IBAN picks one").isEqualTo(Outcome.SUGGESTED);
        assertThat(r.get("E4/0").invoiceIds()).containsExactly("i140");

        assertThat(r.get("E5/0").outcome()).as("an amount alone never matches").isEqualTo(Outcome.UNMATCHED);
        assertThat(r.get("E5/0").reasons()).containsExactly("1 open invoice(s) for this amount and nothing else to tell them apart");

        assertThat(r.get("E6/0").outcome()).isEqualTo(Outcome.NOT_APPLICABLE);

        assertThat(r.get("E7/0").outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(r.get("E7/0").invoiceIds()).containsExactly("i150");
        assertThat(r.get("E7/1").outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(r.get("E7/1").invoiceIds()).containsExactly("i151");

        assertThat(r.get("E8/0").outcome()).isEqualTo(Outcome.UNMATCHED);
        assertThat(r.get("E8/0").reasons()).containsExactly(
                "structured reference \"RF19539007547034\" fails the ISO 11649 checksum and was ignored", "no open invoice for 60.00 EUR");
    }

    private static Transaction tx(String key, String amount, String structured, List<String> text, String iban) {
        return new Transaction(key, 0, null, new BigDecimal(amount), "EUR", null,
                Optional.ofNullable(iban).flatMap(Iban::parse), Optional.ofNullable(structured), text);
    }

    @Test
    void aMatchedInvoiceIsNotOfferedTwice() {
        OpenInvoice a = inv("a", "INV-7", "10.00", "RF712348231", "X", null);
        List<Match> r = new Matcher().match(List.of(
                tx("t1", "10.00", "RF712348231", List.of(), null),
                tx("t2", "10.00", null, List.of("INV-7 again"), null)), List.of(a));
        assertThat(r.get(0).outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(r.get(1).outcome()).isEqualTo(Outcome.UNMATCHED);
    }

    @Test
    void strongEvidenceWinsEvenWhenItComesLater() {
        // t1 names INV-7 in text; t2 carries its RF reference. Pass 1 runs first for every transaction, so t2 gets it.
        OpenInvoice a = inv("a", "INV-7", "10.00", "RF712348231", "X", null);
        List<Match> r = new Matcher().match(List.of(
                tx("t1", "10.00", null, List.of("INV-7"), null),
                tx("t2", "10.00", "RF71 2348 231", List.of(), null)), List.of(a));
        assertThat(r.get(1).outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(r.get(0).outcome()).isEqualTo(Outcome.UNMATCHED);
    }

    @Test
    void aShorterNumberInsideALongerOneIsNotAlsoFound() {
        List<OpenInvoice> ledger = List.of(inv("one", "INV-1", "5.00", null, "X", null), inv("twelve", "INV-12", "12.00", null, "X", null));
        Match m = new Matcher().match(List.of(tx("t", "12.00", null, List.of("payment for INV 12"), null)), ledger).get(0);
        assertThat(m.outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(m.invoiceIds()).containsExactly("twelve");
    }

    @Test
    void aReferenceTypedIntoTheFreeTextStillCounts() {
        OpenInvoice a = inv("a", "INV-7", "10.00", "RF712348231", "X", null);
        Match m = new Matcher().match(List.of(tx("t", "10.00", null, List.of("rf71 2348", "231"), null)), List.of(a)).get(0);
        assertThat(m.outcome()).isEqualTo(Outcome.MATCHED);
    }

    @Test
    void overpaymentAndCurrencyAreRespected() {
        OpenInvoice eur = inv("a", "INV-7", "10.00", null, "X", null);
        OpenInvoice chf = new OpenInvoice("b", "INV-8", new BigDecimal("10.00"), "CHF", Optional.empty(), "X", Optional.empty());
        List<Match> r = new Matcher().match(List.of(
                tx("t1", "12.00", null, List.of("INV-7"), null),
                tx("t2", "10.00", null, List.of("INV-8"), null)), List.of(eur, chf));
        assertThat(r.get(0).outcome()).isEqualTo(Outcome.SUGGESTED);
        assertThat(r.get(0).reasons()).contains("overpayment by 2.00");
        assertThat(r.get(1).outcome()).as("a CHF invoice is not paid by a EUR transaction").isEqualTo(Outcome.UNMATCHED);
    }
}
