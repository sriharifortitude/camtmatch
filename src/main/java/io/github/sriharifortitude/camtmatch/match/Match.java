package io.github.sriharifortitude.camtmatch.match;

import io.github.sriharifortitude.camtmatch.camt.Statement.Transaction;
import java.util.List;

/**
 * The matcher's answer for one transaction, with its reasons. Reasons are
 * sentences for the person who confirms or rejects the match; they name
 * the evidence, not a score.
 */
public record Match(Transaction transaction, Outcome outcome, List<String> invoiceIds, List<String> reasons) {

    public enum Outcome {
        /** The evidence is conclusive; the invoices can be marked paid. */
        MATCHED,
        /** Plausible, needs a person: partial payment, amount-only evidence. */
        SUGGESTED,
        /** No usable evidence. */
        UNMATCHED,
        /** A debit; not an incoming payment. */
        NOT_APPLICABLE
    }
}
