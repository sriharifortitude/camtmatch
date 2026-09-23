package io.github.sriharifortitude.camtmatch.core;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A syntactically valid IBAN: country code, check digits, and a BBAN of the
 * right shape, with a correct MOD 97-10 checksum. Per-country BBAN lengths
 * are not enforced -- the checksum catches every single-character error and
 * every adjacent transposition, which is the error class that matters when
 * matching a payer to a customer record.
 */
public record Iban(String value) {
    private static final Pattern SHAPE = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");

    public Iban {
        if (!SHAPE.matcher(value).matches() || !Mod97.valid(value)) {
            throw new IllegalArgumentException("invalid IBAN");
        }
    }

    /** Accepts spaces and lower case, as printed on invoices. */
    public static Optional<Iban> parse(String raw) {
        if (raw == null) return Optional.empty();
        String compact = raw.replace(" ", "").toUpperCase(Locale.ROOT);
        try {
            return Optional.of(new Iban(compact));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Groups of four, the print format. */
    public String formatted() {
        return value.replaceAll("(.{4})(?!$)", "$1 ");
    }

    @Override
    public String toString() {
        return formatted();
    }
}
