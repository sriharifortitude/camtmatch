package io.github.sriharifortitude.camtmatch.api;

import io.github.sriharifortitude.camtmatch.camt.CamtException;
import io.github.sriharifortitude.camtmatch.core.CreditorReference;
import io.github.sriharifortitude.camtmatch.core.Iban;
import io.github.sriharifortitude.camtmatch.match.OpenInvoice;
import io.github.sriharifortitude.camtmatch.store.Store;
import io.github.sriharifortitude.camtmatch.store.Store.InvoiceRow;
import io.github.sriharifortitude.camtmatch.store.Store.MatchRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class Api {

    /** A camt.053 for a busy account over a day is a few MB; this refuses the absurd. */
    static final int MAX_STATEMENT_BYTES = 20 * 1024 * 1024;

    private final Store store;
    private final Reconciliation reconciliation;

    public Api(Store store, Reconciliation reconciliation) {
        this.store = store;
        this.reconciliation = reconciliation;
    }

    public record InvoiceBody(
            @NotBlank @Size(max = 64) String number,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 16, fraction = 2) BigDecimal amountDue,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            String creditorReference,
            @NotBlank @Size(max = 140) String customerName,
            String customerIban) {}

    @PutMapping("/invoices/{id}")
    public ResponseEntity<?> putInvoice(@PathVariable @Size(max = 64) String id, @Valid @RequestBody InvoiceBody body) {
        Optional<CreditorReference> rf = Optional.ofNullable(body.creditorReference()).filter(s -> !s.isBlank()).map(s ->
                CreditorReference.parse(s).orElseThrow(() -> new BadRequest("creditorReference: not a valid ISO 11649 reference")));
        Optional<Iban> iban = Optional.ofNullable(body.customerIban()).filter(s -> !s.isBlank()).map(s ->
                Iban.parse(s).orElseThrow(() -> new BadRequest("customerIban: not a valid IBAN")));
        boolean written = store.upsertInvoice(new OpenInvoice(id, body.number(), body.amountDue(), body.currency(), rf, body.customerName(), iban));
        if (!written) throw new Reconciliation.Conflict("invoice " + id + " is paid and cannot be changed");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/invoices")
    public List<InvoiceRow> invoices(@RequestParam Optional<String> status) {
        return store.invoices(status);
    }

    @PostMapping(value = "/statements", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public List<Reconciliation.Imported> importStatement(HttpServletRequest request) throws IOException {
        return reconciliation.importStatements(bounded(request.getInputStream()));
    }

    @GetMapping("/statements/{id}/matches")
    public List<MatchRow> matches(@PathVariable UUID id) {
        return reconciliation.matches(id).orElseThrow(() -> new Reconciliation.NotFound("statement " + id));
    }

    @PostMapping("/matches/{id}/confirm")
    public MatchRow confirm(@PathVariable UUID id) {
        return reconciliation.confirm(id);
    }

    @PostMapping("/matches/{id}/reject")
    public MatchRow reject(@PathVariable UUID id) {
        return reconciliation.reject(id);
    }

    private static InputStream bounded(InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_STATEMENT_BYTES + 1);
        if (body.length > MAX_STATEMENT_BYTES) throw new TooLarge();
        return new ByteArrayInputStream(body);
    }

    // -- errors as RFC 9457 problem details ------------------------------------

    static class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    static class TooLarge extends RuntimeException {}

    @ExceptionHandler(CamtException.class)
    ProblemDetail camt(CamtException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Not a readable camt.053 statement", e.getMessage());
    }

    @ExceptionHandler(BadRequest.class)
    ProblemDetail bad(BadRequest e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted().reduce((a, b) -> a + "; " + b).orElse("invalid");
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail);
    }

    @ExceptionHandler(Reconciliation.DuplicateStatement.class)
    ProblemDetail duplicate(Reconciliation.DuplicateStatement e) {
        return problem(HttpStatus.CONFLICT, "Statement already imported", e.getMessage());
    }

    @ExceptionHandler(Reconciliation.Conflict.class)
    ProblemDetail conflict(Reconciliation.Conflict e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(Reconciliation.NotFound.class)
    ProblemDetail notFound(Reconciliation.NotFound e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(TooLarge.class)
    ProblemDetail tooLarge() {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "Statement too large", "limit is " + MAX_STATEMENT_BYTES + " bytes");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return p;
    }
}
