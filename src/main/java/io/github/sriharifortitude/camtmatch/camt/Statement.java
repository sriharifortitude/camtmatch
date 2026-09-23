package io.github.sriharifortitude.camtmatch.camt;

import io.github.sriharifortitude.camtmatch.core.Iban;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * One account statement from a camt.053 message. Amounts are signed from
 * the account holder's point of view: credits positive, debits negative.
 */
public record Statement(
        String id,
        Iban account,
        String currency,
        Optional<Balance> opening,
        Optional<Balance> closing,
        List<Entry> entries) {

    public record Balance(BigDecimal amount, LocalDate date) {}

    /** A booking on the account. Batch bookings carry several transactions. */
    public record Entry(
            String reference,
            BigDecimal amount,
            String currency,
            String status,
            LocalDate bookingDate,
            LocalDate valueDate,
            List<Transaction> transactions) {

        public boolean booked() {
            return "BOOK".equals(status);
        }
    }

    /** The unit that gets matched: one payment with its remittance information. */
    public record Transaction(
            String entryReference,
            int index,
            String endToEndId,
            BigDecimal amount,
            String currency,
            String counterpartyName,
            Optional<Iban> counterpartyIban,
            Optional<String> structuredReference,
            List<String> unstructured) {

        public boolean credit() {
            return amount.signum() > 0;
        }

        /** Stable identifier within a statement, e.g. "2026091800042/0". */
        public String key() {
            return entryReference + "/" + index;
        }
    }

    /**
     * Opening balance plus booked entries, less the closing balance. Zero for
     * a consistent statement. Non-zero means entries are missing or the file
     * was altered, and matches drawn from it should not be trusted.
     */
    public Optional<BigDecimal> balanceDiscrepancy() {
        if (opening.isEmpty() || closing.isEmpty()) return Optional.empty();
        BigDecimal sum = entries.stream().filter(Entry::booked).map(Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(opening.get().amount().add(sum).subtract(closing.get().amount()));
    }
}
