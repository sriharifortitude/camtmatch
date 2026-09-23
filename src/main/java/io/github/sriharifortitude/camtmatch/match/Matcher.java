package io.github.sriharifortitude.camtmatch.match;

import io.github.sriharifortitude.camtmatch.camt.Statement.Transaction;
import io.github.sriharifortitude.camtmatch.core.CreditorReference;
import io.github.sriharifortitude.camtmatch.match.Match.Outcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Matches incoming payments to open invoices in three passes, strongest
 * evidence first, so that a strong match is never pre-empted by a weak one:
 *
 * <ol>
 *   <li><b>Structured creditor reference</b> (ISO 11649), checksum-valid and
 *       equal to an invoice's reference.
 *   <li><b>Invoice numbers or references in the free text</b>, found after
 *       removing everything but letters and digits -- banks break remittance
 *       lines at 35 characters, often mid-number.
 *   <li><b>Amount and payer</b>: the same amount, paid from the IBAN on file
 *       for the customer.
 * </ol>
 *
 * Only passes 1 and 2 with an exactly equal amount produce {@code MATCHED};
 * everything else is a {@code SUGGESTED} match for a person to confirm. An
 * amount alone never matches, however unique it is: a customer paying the
 * wrong invoice of the same value is the common case, not the rare one.
 *
 * <p>An invoice consumed by a MATCHED result is not offered again in the same
 * run. Suggestions do not consume.
 */
public final class Matcher {

    public List<Match> match(List<Transaction> transactions, List<OpenInvoice> invoices) {
        Map<String, Match> results = new LinkedHashMap<>();
        Set<String> consumed = new LinkedHashSet<>();
        Map<String, List<String>> notes = new LinkedHashMap<>();

        for (Transaction tx : transactions) {
            if (!tx.credit()) {
                results.put(tx.key(), new Match(tx, Outcome.NOT_APPLICABLE, List.of(), List.of("debit")));
            }
        }

        // Pass 1: structured reference.
        for (Transaction tx : transactions) {
            if (results.containsKey(tx.key()) || tx.structuredReference().isEmpty()) continue;
            String raw = tx.structuredReference().get();
            Optional<CreditorReference> ref = CreditorReference.parse(raw);
            if (ref.isEmpty()) {
                note(notes, tx, "structured reference \"" + raw + "\" fails the ISO 11649 checksum and was ignored");
                continue;
            }
            Optional<OpenInvoice> inv = available(invoices, consumed, tx)
                    .filter(i -> i.reference().equals(ref))
                    .findFirst();
            if (inv.isEmpty()) {
                note(notes, tx, "structured reference " + ref.get() + " belongs to no open invoice");
                continue;
            }
            results.put(tx.key(), decide(tx, List.of(inv.get()), "creditor reference " + ref.get() + " is invoice " + inv.get().number(), consumed, notes));
        }

        // Pass 2: invoice numbers and references in the free text.
        for (Transaction tx : transactions) {
            if (results.containsKey(tx.key()) || tx.unstructured().isEmpty()) continue;
            String text = compact(String.join("", tx.unstructured()));
            List<OpenInvoice> found = available(invoices, consumed, tx)
                    .filter(i -> text.contains(compact(i.number())) || i.reference().map(r -> text.contains(r.value())).orElse(false))
                    .toList();
            found = withoutShadowed(found);
            if (found.isEmpty()) continue;
            String evidence = found.size() == 1
                    ? "invoice " + found.get(0).number() + " named in the remittance text"
                    : "invoices " + found.stream().map(OpenInvoice::number).collect(Collectors.joining(", ")) + " named in the remittance text";
            results.put(tx.key(), decide(tx, found, evidence, consumed, notes));
        }

        // Pass 3: amount and payer IBAN. Suggestions only.
        for (Transaction tx : transactions) {
            if (results.containsKey(tx.key())) continue;
            List<OpenInvoice> sameAmount = available(invoices, consumed, tx)
                    .filter(i -> i.amountDue().compareTo(tx.amount()) == 0)
                    .toList();
            List<OpenInvoice> samePayer = tx.counterpartyIban().isEmpty() ? List.of()
                    : sameAmount.stream().filter(i -> i.customerIban().equals(tx.counterpartyIban())).toList();
            List<String> reasons = new ArrayList<>(notes.getOrDefault(tx.key(), List.of()));
            if (samePayer.size() == 1) {
                reasons.add("no reference; amount " + tx.amount().toPlainString() + " equals invoice " + samePayer.get(0).number()
                        + " and was paid from the customer's IBAN on file");
                results.put(tx.key(), new Match(tx, Outcome.SUGGESTED, List.of(samePayer.get(0).id()), List.copyOf(reasons)));
            } else {
                reasons.add(sameAmount.isEmpty()
                        ? "no open invoice for " + tx.amount().toPlainString() + " " + tx.currency()
                        : sameAmount.size() + " open invoice(s) for this amount and nothing else to tell them apart");
                results.put(tx.key(), new Match(tx, Outcome.UNMATCHED, List.of(), List.copyOf(reasons)));
            }
        }

        return transactions.stream().map(tx -> results.get(tx.key())).toList();
    }

    /** Equal amount -> MATCHED and consumed; otherwise a SUGGESTED partial or over-payment. */
    private static Match decide(Transaction tx, List<OpenInvoice> invoices, String evidence, Set<String> consumed, Map<String, List<String>> notes) {
        BigDecimal due = invoices.stream().map(OpenInvoice::amountDue).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> ids = invoices.stream().map(OpenInvoice::id).toList();
        List<String> reasons = new ArrayList<>(notes.getOrDefault(tx.key(), List.of()));
        reasons.add(evidence);
        int cmp = tx.amount().compareTo(due);
        if (cmp == 0) {
            reasons.add(invoices.size() == 1 ? "amount equals the amount due" : "amounts sum to " + due.toPlainString() + ", the payment amount");
            consumed.addAll(ids);
            return new Match(tx, Outcome.MATCHED, ids, List.copyOf(reasons));
        }
        reasons.add(cmp < 0
                ? "partial payment: " + tx.amount().toPlainString() + " of " + due.toPlainString() + " due"
                : "overpayment by " + tx.amount().subtract(due).toPlainString());
        return new Match(tx, Outcome.SUGGESTED, ids, List.copyOf(reasons));
    }

    private static java.util.stream.Stream<OpenInvoice> available(List<OpenInvoice> invoices, Set<String> consumed, Transaction tx) {
        return invoices.stream()
                .filter(i -> !consumed.contains(i.id()))
                .filter(i -> i.currency().equals(tx.currency()));
    }

    /** INV-1 is "found" inside INV-12; drop any number that is a substring of another found number. */
    private static List<OpenInvoice> withoutShadowed(List<OpenInvoice> found) {
        return found.stream()
                .filter(a -> found.stream().noneMatch(b -> b != a && compact(b.number()).length() > compact(a.number()).length()
                        && compact(b.number()).contains(compact(a.number()))))
                .sorted(Comparator.comparing(OpenInvoice::number))
                .toList();
    }

    static String compact(String s) {
        return s.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static void note(Map<String, List<String>> notes, Transaction tx, String note) {
        notes.computeIfAbsent(tx.key(), k -> new ArrayList<>()).add(note);
    }
}
