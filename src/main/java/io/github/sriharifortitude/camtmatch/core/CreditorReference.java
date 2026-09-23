package io.github.sriharifortitude.camtmatch.core;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * ISO 11649 structured creditor reference: "RF", two check digits, and up
 * to 21 alphanumeric characters chosen by the creditor. Printed on an
 * invoice and copied into the payment by the payer's bank, it survives the
 * journey through SEPA intact -- unlike free-text remittance information,
 * which banks truncate, reflow and upper-case.
 */
public record CreditorReference(String value) {
    private static final Pattern SHAPE = Pattern.compile("RF[0-9]{2}[A-Z0-9]{1,21}");

    public CreditorReference {
        if (!SHAPE.matcher(value).matches() || !Mod97.valid(value)) {
            throw new IllegalArgumentException("invalid creditor reference");
        }
    }

    public static Optional<CreditorReference> parse(String raw) {
        if (raw == null) return Optional.empty();
        String compact = raw.replace(" ", "").toUpperCase(Locale.ROOT);
        try {
            return Optional.of(new CreditorReference(compact));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Derive a reference from the creditor's own identifier, e.g. an invoice number. */
    public static CreditorReference of(String base) {
        String b = base.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (b.isEmpty() || b.length() > 21) throw new IllegalArgumentException("base must be 1-21 alphanumerics");
        int check = 98 - Mod97.remainder(b + "RF00");
        return new CreditorReference("RF%02d%s".formatted(check, b));
    }

    public String formatted() {
        return value.replaceAll("(.{4})(?!$)", "$1 ");
    }

    @Override
    public String toString() {
        return formatted();
    }
}
