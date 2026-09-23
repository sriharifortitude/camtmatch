package io.github.sriharifortitude.camtmatch.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checksums verified independently (Python int arithmetic) before being written here. */
class IdentifiersTest {

    @ParameterizedTest
    @ValueSource(strings = {"DE89370400440532013000", "GB82WEST12345698765432", "NL91ABNA0417164300", "de89 3704 0044 0532 0130 00"})
    void acceptsValidIbans(String raw) {
        assertThat(Iban.parse(raw)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DE89370400440532013001", "DE98370400440532013000", "DE8937040044", "XX00", ""})
    void rejectsSingleDigitErrorsTranspositionsAndJunk(String raw) {
        assertThat(Iban.parse(raw)).isEmpty();
    }

    @Test
    void formatsIbanInGroupsOfFour() {
        assertThat(Iban.parse("DE89370400440532013000").orElseThrow().formatted()).isEqualTo("DE89 3704 0044 0532 0130 00");
    }

    @Test
    void validatesTheIso11649Example() {
        assertThat(CreditorReference.parse("RF18 5390 0754 7034")).isPresent();
        assertThat(CreditorReference.parse("RF712348231")).isPresent();
        assertThat(CreditorReference.parse("RF19539007547034")).isEmpty();
    }

    @Test
    void derivesAReferenceFromAnInvoiceNumber() {
        CreditorReference rf = CreditorReference.of("INV-2026-000123");
        assertThat(rf.value()).isEqualTo("RF98INV2026000123");
        assertThat(CreditorReference.parse(rf.formatted())).contains(rf);
        assertThat(CreditorReference.of("539007547034").value()).isEqualTo("RF18539007547034");
    }

    @Test
    void refusesBasesThatCannotFit() {
        assertThatThrownBy(() -> CreditorReference.of("A".repeat(22))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditorReference.of("--")).isInstanceOf(IllegalArgumentException.class);
    }
}
