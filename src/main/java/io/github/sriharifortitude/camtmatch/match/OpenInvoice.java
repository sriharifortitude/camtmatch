package io.github.sriharifortitude.camtmatch.match;

import io.github.sriharifortitude.camtmatch.core.CreditorReference;
import io.github.sriharifortitude.camtmatch.core.Iban;
import java.math.BigDecimal;
import java.util.Optional;

/** What the ledger says is still owed. The matcher never mutates it. */
public record OpenInvoice(
        String id,
        String number,
        BigDecimal amountDue,
        String currency,
        Optional<CreditorReference> reference,
        String customerName,
        Optional<Iban> customerIban) {}
