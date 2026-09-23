package io.github.sriharifortitude.camtmatch.api;

import io.github.sriharifortitude.camtmatch.camt.Camt053Parser;
import io.github.sriharifortitude.camtmatch.camt.Statement;
import io.github.sriharifortitude.camtmatch.camt.Statement.Entry;
import io.github.sriharifortitude.camtmatch.camt.Statement.Transaction;
import io.github.sriharifortitude.camtmatch.match.Match;
import io.github.sriharifortitude.camtmatch.match.Matcher;
import io.github.sriharifortitude.camtmatch.store.Store;
import io.github.sriharifortitude.camtmatch.store.Store.MatchRow;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Import a statement, match it, apply what is conclusive, keep the rest for
 * a person. One database transaction per import, so a failure leaves
 * nothing half-applied.
 */
@Service
public class Reconciliation {

    private final Store store;
    private final Camt053Parser parser = new Camt053Parser();
    private final Matcher matcher = new Matcher();

    public Reconciliation(Store store) {
        this.store = store;
    }

    public record Imported(UUID id, String statementId, String account, BigDecimal discrepancy, Map<Match.Outcome, Integer> outcomes, int applied) {}

    public static class DuplicateStatement extends RuntimeException {
        public DuplicateStatement(String message) {
            super(message);
        }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    @Transactional
    public List<Imported> importStatements(InputStream xml) {
        List<Imported> out = new ArrayList<>();
        for (Statement s : parser.parse(xml)) out.add(importOne(s));
        return out;
    }

    private Imported importOne(Statement s) {
        store.lockAccount(s.account());
        if (store.statementExists(s.account(), s.id())) {
            throw new DuplicateStatement("statement " + s.id() + " for " + s.account() + " was already imported");
        }
        UUID statementId = store.insertStatement(s);
        boolean consistent = s.balanceDiscrepancy().map(d -> d.signum() == 0).orElse(true);

        Map<String, UUID> txIds = new HashMap<>();
        List<Transaction> txs = new ArrayList<>();
        for (Entry e : s.entries()) {
            if (!e.booked()) continue; // pending and informational entries are not payments yet
            for (Transaction t : e.transactions()) {
                txIds.put(t.key(), store.insertTransaction(statementId, t, e.bookingDate()));
                txs.add(t);
            }
        }

        Map<Match.Outcome, Integer> counts = new EnumMap<>(Match.Outcome.class);
        int applied = 0;
        for (Match m : matcher.match(txs, store.openInvoices())) {
            counts.merge(m.outcome(), 1, Integer::sum);
            List<String> reasons = new ArrayList<>(m.reasons());
            String state = switch (m.outcome()) {
                case MATCHED -> consistent ? "confirmed" : "proposed";
                case SUGGESTED -> "proposed";
                case UNMATCHED, NOT_APPLICABLE -> "none";
            };
            if (m.outcome() == Match.Outcome.MATCHED && !consistent) {
                reasons.add("not applied automatically: the statement's balances do not add up (discrepancy "
                        + s.balanceDiscrepancy().orElseThrow().toPlainString() + ")");
            }
            UUID matchId = store.insertMatch(txIds.get(m.transaction().key()), m, state, reasons);
            if (state.equals("confirmed")) {
                if (store.markPaid(m.invoiceIds(), matchId) == m.invoiceIds().size()) {
                    applied++;
                } else {
                    // Only possible if another import paid one of them between our read and this write; the account lock makes that a different account.
                    throw new Conflict("invoice(s) " + m.invoiceIds() + " were paid concurrently; import rolled back, retry");
                }
            }
        }
        return new Imported(statementId, s.id(), s.account().value(), s.balanceDiscrepancy().orElse(null), counts, applied);
    }

    public Optional<List<MatchRow>> matches(UUID statement) {
        return store.statementPresent(statement) ? Optional.of(store.matchesFor(statement)) : Optional.empty();
    }

    /** A person confirms a suggestion: the invoices become paid, if they still are open. */
    @Transactional
    public MatchRow confirm(UUID id) {
        MatchRow m = store.match(id).orElseThrow(() -> new NotFound("match " + id));
        if (!m.state().equals("proposed")) throw new Conflict("match is " + m.state() + ", not proposed");
        List<String> invoices = Arrays.stream(m.invoiceIds().split(",")).filter(s -> !s.isEmpty()).toList();
        if (store.markPaid(invoices, id) != invoices.size()) {
            throw new Conflict("one of " + invoices + " is no longer open");
        }
        store.setMatchState(id, "confirmed");
        return store.match(id).orElseThrow();
    }

    @Transactional
    public MatchRow reject(UUID id) {
        MatchRow m = store.match(id).orElseThrow(() -> new NotFound("match " + id));
        if (!m.state().equals("proposed")) throw new Conflict("match is " + m.state() + ", not proposed");
        store.setMatchState(id, "rejected");
        return store.match(id).orElseThrow();
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }
}
