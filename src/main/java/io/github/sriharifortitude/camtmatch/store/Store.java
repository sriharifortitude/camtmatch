package io.github.sriharifortitude.camtmatch.store;

import io.github.sriharifortitude.camtmatch.camt.Statement;
import io.github.sriharifortitude.camtmatch.camt.Statement.Transaction;
import io.github.sriharifortitude.camtmatch.core.CreditorReference;
import io.github.sriharifortitude.camtmatch.core.Iban;
import io.github.sriharifortitude.camtmatch.match.Match;
import io.github.sriharifortitude.camtmatch.match.OpenInvoice;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL, and nothing else. Transactions are the caller's. */
@Repository
public class Store {
    private final JdbcClient jdbc;

    public Store(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // -- invoices -------------------------------------------------------------

    public record InvoiceRow(String id, String number, BigDecimal amountDue, String currency, String creditorReference,
                             String customerName, String customerIban, String status) {}

    /** Insert or update an open invoice. A paid invoice is not reopened by an upsert. */
    public boolean upsertInvoice(OpenInvoice i) {
        return jdbc.sql("""
                insert into invoices (id, number, amount_due, currency, creditor_reference, customer_name, customer_iban)
                values (:id, :number, :due, :ccy, :rf, :name, :iban)
                on conflict (id) do update set number = excluded.number, amount_due = excluded.amount_due,
                    currency = excluded.currency, creditor_reference = excluded.creditor_reference,
                    customer_name = excluded.customer_name, customer_iban = excluded.customer_iban, updated_at = now()
                where invoices.status = 'open'
                """)
                .param("id", i.id()).param("number", i.number()).param("due", i.amountDue()).param("ccy", i.currency())
                .param("rf", i.reference().map(CreditorReference::value).orElse(null))
                .param("name", i.customerName()).param("iban", i.customerIban().map(Iban::value).orElse(null))
                .update() == 1;
    }

    public List<OpenInvoice> openInvoices() {
        return jdbc.sql("select id, number, amount_due, currency, creditor_reference, customer_name, customer_iban from invoices where status = 'open' order by number")
                .query((rs, n) -> new OpenInvoice(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                        Optional.ofNullable(rs.getString(5)).flatMap(CreditorReference::parse), rs.getString(6),
                        Optional.ofNullable(rs.getString(7)).flatMap(Iban::parse)))
                .list();
    }

    public List<InvoiceRow> invoices(Optional<String> status) {
        return jdbc.sql("select id, number, amount_due, currency, creditor_reference, customer_name, customer_iban, status from invoices "
                        + "where (:status::text is null or status = :status) order by number")
                .param("status", status.orElse(null))
                .query(InvoiceRow.class).list();
    }

    /** Marks paid only if still open; returns how many changed. */
    public int markPaid(List<String> invoiceIds, UUID match) {
        return jdbc.sql("update invoices set status = 'paid', paid_by_match = :m, updated_at = now() where id in (:ids) and status = 'open'")
                .param("m", match).param("ids", invoiceIds).update();
    }

    // -- statements -----------------------------------------------------------

    /** Serialises imports per account for the rest of the transaction. */
    public void lockAccount(Iban account) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:a))").param("a", account.value()).query().singleRow();
    }

    public boolean statementExists(Iban account, String statementId) {
        return jdbc.sql("select count(*) from statements where account_iban = :a and statement_id = :s")
                .param("a", account.value()).param("s", statementId).query(Long.class).single() > 0;
    }

    public UUID insertStatement(Statement s) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into statements (id, account_iban, statement_id, currency, opening, closing, discrepancy)
                values (:id, :a, :s, :ccy, :o, :c, :d)
                """)
                .param("id", id).param("a", s.account().value()).param("s", s.id()).param("ccy", s.currency())
                .param("o", s.opening().map(Statement.Balance::amount).orElse(null))
                .param("c", s.closing().map(Statement.Balance::amount).orElse(null))
                .param("d", s.balanceDiscrepancy().orElse(null))
                .update();
        return id;
    }

    public UUID insertTransaction(UUID statement, Transaction t, java.time.LocalDate bookingDate) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into transactions (id, statement, tx_key, amount, currency, booking_date, end_to_end_id,
                    counterparty_name, counterparty_iban, structured_ref, remittance)
                values (:id, :st, :k, :amt, :ccy, :bd, :e2e, :cn, :ci, :sr, :rm)
                """)
                .param("id", id).param("st", statement).param("k", t.key()).param("amt", t.amount()).param("ccy", t.currency())
                .param("bd", bookingDate == null ? null : Date.valueOf(bookingDate)).param("e2e", t.endToEndId())
                .param("cn", t.counterpartyName()).param("ci", t.counterpartyIban().map(Iban::value).orElse(null))
                .param("sr", t.structuredReference().orElse(null)).param("rm", String.join("\n", t.unstructured()))
                .update();
        return id;
    }

    public UUID insertMatch(UUID transaction, Match m, String state, List<String> reasons) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into matches (id, transaction, outcome, state, reasons, decided_at) values (:id, :t, :o, :st, :r, case when :st = 'confirmed' then now() end)")
                .param("id", id).param("t", transaction).param("o", m.outcome().name()).param("st", state)
                .param("r", String.join("\n", reasons)).update();
        for (String inv : m.invoiceIds()) {
            jdbc.sql("insert into match_invoices (match, invoice) values (:m, :i)").param("m", id).param("i", inv).update();
        }
        return id;
    }

    // -- matches --------------------------------------------------------------

    public record MatchRow(UUID id, String txKey, BigDecimal amount, String currency, String counterpartyName, String remittance,
                           String structuredRef, String outcome, String state, String reasons, String invoiceIds) {}

    public List<MatchRow> matchesFor(UUID statement) {
        return jdbc.sql("""
                select m.id, t.tx_key, t.amount, t.currency, t.counterparty_name, t.remittance, t.structured_ref, m.outcome, m.state, m.reasons,
                       coalesce((select string_agg(mi.invoice, ',' order by mi.invoice) from match_invoices mi where mi.match = m.id), '') as invoice_ids
                from matches m join transactions t on t.id = m.transaction
                where t.statement = :s order by t.tx_key
                """).param("s", statement).query(MatchRow.class).list();
    }

    public Optional<MatchRow> match(UUID id) {
        return jdbc.sql("""
                select m.id, t.tx_key, t.amount, t.currency, t.counterparty_name, t.remittance, t.structured_ref, m.outcome, m.state, m.reasons,
                       coalesce((select string_agg(mi.invoice, ',' order by mi.invoice) from match_invoices mi where mi.match = m.id), '') as invoice_ids
                from matches m join transactions t on t.id = m.transaction where m.id = :id for update of m
                """).param("id", id).query(MatchRow.class).optional();
    }

    public void setMatchState(UUID id, String state) {
        jdbc.sql("update matches set state = :s, decided_at = now() where id = :id").param("s", state).param("id", id).update();
    }

    public boolean statementPresent(UUID id) {
        return jdbc.sql("select count(*) from statements where id = :id").param("id", id).query(Long.class).single() > 0;
    }
}
