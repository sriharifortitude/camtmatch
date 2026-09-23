package io.github.sriharifortitude.camtmatch.camt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sriharifortitude.camtmatch.camt.Statement.Transaction;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class Camt053ParserTest {

    private final Camt053Parser parser = new Camt053Parser();

    static InputStream fixture() {
        return Camt053ParserTest.class.getResourceAsStream("/camt/statement-v08.xml");
    }

    private static InputStream xml(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsTheStatementHeaderAndBalances() {
        Statement s = parser.parse(fixture()).get(0);
        assertThat(s.id()).isEqualTo("STMT-2026-09-18");
        assertThat(s.account().value()).isEqualTo("DE89370400440532013000");
        assertThat(s.currency()).isEqualTo("EUR");
        assertThat(s.opening().orElseThrow().amount()).isEqualByComparingTo("1000.00");
        assertThat(s.opening().orElseThrow().date()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(s.closing().orElseThrow().amount()).isEqualByComparingTo("3522.45");
        assertThat(s.balanceDiscrepancy()).hasValueSatisfying(d -> assertThat(d).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void readsEntriesTransactionsAndRemittance() {
        Statement s = parser.parse(fixture()).get(0);
        assertThat(s.entries()).hasSize(8);
        List<Transaction> txs = s.entries().stream().flatMap(e -> e.transactions().stream()).toList();
        assertThat(txs).extracting(Transaction::key)
                .containsExactly("E1/0", "E2/0", "E3/0", "E4/0", "E5/0", "E6/0", "E7/0", "E7/1", "E8/0");

        Transaction e1 = txs.get(0);
        assertThat(e1.amount()).isEqualByComparingTo("1190.00");
        assertThat(e1.endToEndId()).isEqualTo("ACME-PAY-7781");
        assertThat(e1.counterpartyName()).isEqualTo("ACME Handels GmbH");
        assertThat(e1.counterpartyIban().orElseThrow().value()).isEqualTo("NL91ABNA0417164300");
        assertThat(e1.structuredReference()).contains("RF98INV2026000123");

        assertThat(txs.get(1).endToEndId()).as("NOTPROVIDED is not an id").isNull();
        assertThat(txs.get(2).unstructured()).containsExactly("Rechnungen INV-2026-0001", "25, INV-2026-000126 danke");

        Transaction fee = txs.get(5);
        assertThat(fee.amount()).isEqualByComparingTo("-23.80");
        assertThat(fee.credit()).isFalse();
        assertThat(fee.counterpartyName()).as("creditor is the counterparty on a debit").isEqualTo("Hausbank AG");

        assertThat(txs.get(6).amount()).as("batch: each TxDtls has its own amount").isEqualByComparingTo("100.00");
        assertThat(txs.get(7).amount()).isEqualByComparingTo("200.00");
    }

    @Test
    void reportsAStatementThatDoesNotAddUp() {
        String tampered = new String(readAll(fixture()), StandardCharsets.UTF_8).replace("<Amt Ccy=\"EUR\">50.00</Amt>", "<Amt Ccy=\"EUR\">5.00</Amt>");
        Statement s = parser.parse(xml(tampered)).get(0);
        assertThat(s.balanceDiscrepancy()).hasValueSatisfying(d -> assertThat(d).isEqualByComparingTo("-45.00"));
    }

    @Test
    void readsTheOlderVersionShapes() {
        // camt.053.001.02: debtor name directly under Dbtr, Sts as text, DtTm dates.
        String v02 = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt>
                  <GrpHdr><MsgId>M</MsgId><CreDtTm>2026-09-18T06:00:00</CreDtTm></GrpHdr>
                  <Stmt><Id>S2</Id><Acct><Id><IBAN>GB82 WEST 1234 5698 7654 32</IBAN></Id></Acct>
                    <Ntry><Amt Ccy="GBP">12.50</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts>BOOK</Sts>
                      <BookgDt><DtTm>2026-09-18T10:15:00</DtTm></BookgDt>
                      <NtryDtls><TxDtls><RltdPties><Dbtr><Nm>Old Format Ltd</Nm></Dbtr></RltdPties>
                        <RmtInf><Ustrd>INV 42</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
                  </Stmt></BkToCstmrStmt></Document>""";
        Statement s = parser.parse(xml(v02)).get(0);
        Transaction t = s.entries().get(0).transactions().get(0);
        assertThat(s.entries().get(0).booked()).isTrue();
        assertThat(s.entries().get(0).bookingDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(t.counterpartyName()).isEqualTo("Old Format Ltd");
        assertThat(t.key()).isEqualTo("S2#0/0");
        assertThat(s.balanceDiscrepancy()).as("no balances, nothing to check").isEmpty();
    }

    @Test
    void refusesDoctypesSoXxeCannotHappen() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE d [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt>
                  <Stmt><Id>&secret;</Id></Stmt></BkToCstmrStmt></Document>""";
        assertThatThrownBy(() -> parser.parse(xml(xxe))).isInstanceOf(CamtException.class).hasMessageContaining("DOCTYPE");
    }

    @Test
    void refusesOtherDocumentsWithAReason() {
        assertThatThrownBy(() -> parser.parse(xml("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\"/>")))
                .isInstanceOf(CamtException.class).hasMessageContaining("not a camt.053 document");
        assertThatThrownBy(() -> parser.parse(xml("not xml"))).isInstanceOf(CamtException.class).hasMessageContaining("malformed");
        assertThatThrownBy(() -> parser.parse(xml("""
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt><Stmt><Id>S</Id>
                <Acct><Id><IBAN>DE00370400440532013000</IBAN></Id></Acct></Stmt></BkToCstmrStmt></Document>""")))
                .isInstanceOf(CamtException.class).hasMessageContaining("account IBAN missing or invalid");
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
