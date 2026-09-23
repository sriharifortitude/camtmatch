package io.github.sriharifortitude.camtmatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The whole service over real HTTP against a real Postgres (Testcontainers).
 * The ledger and statement are the ones the matcher unit test uses, so the
 * outcomes here are the same hand-worked ones, now persisted and applied.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "camtmatch.api-token=" + ApiIntegrationTest.TOKEN)
class ApiIntegrationTest {

    static final String TOKEN = "test-token-0123456789abcdef0123456789";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.sql("truncate match_invoices, matches, transactions, statements, invoices cascade").update();
    }

    private HttpResponse<String> send(String method, String path, String contentType, byte[] body, boolean auth) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body));
        if (contentType != null) b.header("Content-Type", contentType);
        if (auth) b.header("Authorization", "Bearer " + TOKEN);
        try {
            return http.send(b.build(), BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private HttpResponse<String> json(String method, String path, String body) {
        return send(method, path, "application/json", body == null ? null : body.getBytes(StandardCharsets.UTF_8), true);
    }

    private HttpResponse<String> importXml(byte[] xml) {
        return send("POST", "/api/statements", "application/xml", xml, true);
    }

    static byte[] fixture() {
        try (InputStream in = ApiIntegrationTest.class.getResourceAsStream("/camt/statement-v08.xml")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private void loadLedger() {
        String[][] ledger = {
                {"i123", "INV-2026-000123", "1190.00", "RF98INV2026000123", "ACME Handels GmbH", "NL91ABNA0417164300"},
                {"i130", "INV-2026-000130", "750.00", "RF18 5390 0754 7034", "Beta SARL", null},
                {"i125", "INV-2026-000125", "238.00", null, "Gamma BV", null},
                {"i126", "INV-2026-000126", "119.00", null, "Gamma BV", null},
                {"i140", "INV-2026-000140", "89.25", null, "Delta Ltd", "GB82 WEST 1234 5698 7654 32"},
                {"i141", "INV-2026-000141", "89.25", null, "Other Ltd", null},
                {"i150", "INV-2026-000150", "100.00", "RF48INV2026000150", "Epsilon SpA", null},
                {"i151", "INV-2026-000151", "200.00", null, "Zeta AB", null},
                {"i012", "INV-2026-000012", "50.00", null, "Somebody Else", null}};
        for (String[] r : ledger) {
            String body = "{\"number\":\"%s\",\"amountDue\":%s,\"currency\":\"EUR\",\"creditorReference\":%s,\"customerName\":\"%s\",\"customerIban\":%s}"
                    .formatted(r[1], r[2], r[3] == null ? "null" : "\"" + r[3] + "\"", r[4], r[5] == null ? "null" : "\"" + r[5] + "\"");
            assertThat(json("PUT", "/api/invoices/" + r[0], body).statusCode()).isEqualTo(204);
        }
    }

    private List<String> paid() {
        return jdbc.sql("select id from invoices where status = 'paid' order by id").query(String.class).list();
    }

    private String statementId(String importResponse) {
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(importResponse);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private String matchId(String statement, String txKey) {
        return jdbc.sql("select m.id::text from matches m join transactions t on t.id = m.transaction where t.statement = :s::uuid and t.tx_key = :k")
                .param("s", statement).param("k", txKey).query(String.class).single();
    }

    @Test
    void rejectsRequestsWithoutTheToken() {
        assertThat(send("GET", "/api/invoices", null, null, false).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/actuator/health", null, null, false).statusCode()).as("health stays open for probes").isEqualTo(200);
    }

    @Test
    void importsMatchesAndAppliesOnlyWhatIsConclusive() {
        loadLedger();
        HttpResponse<String> r = importXml(fixture());
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(r.body()).contains("\"MATCHED\":4", "\"SUGGESTED\":2", "\"UNMATCHED\":2", "\"NOT_APPLICABLE\":1", "\"applied\":4", "\"discrepancy\":0.00");
        assertThat(paid()).containsExactly("i123", "i125", "i126", "i150", "i151");

        String stmt = statementId(r.body());
        HttpResponse<String> list = json("GET", "/api/statements/" + stmt + "/matches", null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("partial payment: 500.00 of 750.00 due", "\"invoiceIds\":\"i125,i126\"");

        // A person confirms the partial payment against INV-2026-000130 and rejects the amount-only suggestion.
        String partial = matchId(stmt, "E2/0");
        HttpResponse<String> confirmed = json("POST", "/api/matches/" + partial + "/confirm", null);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(confirmed.body()).contains("\"state\":\"confirmed\"");
        assertThat(paid()).contains("i130");
        assertThat(json("POST", "/api/matches/" + partial + "/confirm", null).statusCode()).as("not twice").isEqualTo(409);

        String amountOnly = matchId(stmt, "E4/0");
        assertThat(json("POST", "/api/matches/" + amountOnly + "/reject", null).body()).contains("\"state\":\"rejected\"");
        assertThat(paid()).doesNotContain("i140");

        assertThat(importXml(fixture()).statusCode()).as("same statement again").isEqualTo(409);
        assertThat(json("PUT", "/api/invoices/i123", "{\"number\":\"X\",\"amountDue\":1,\"currency\":\"EUR\",\"customerName\":\"X\"}").statusCode())
                .as("a paid invoice cannot be edited back to open").isEqualTo(409);
    }

    @Test
    void aStatementThatDoesNotAddUpIsImportedButNothingIsAppliedFromIt() {
        loadLedger();
        byte[] tampered = new String(fixture(), StandardCharsets.UTF_8)
                .replace("<Amt Ccy=\"EUR\">50.00</Amt>", "<Amt Ccy=\"EUR\">5.00</Amt>").getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> r = importXml(tampered);
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(r.body()).contains("\"discrepancy\":-45.00", "\"applied\":0");
        assertThat(paid()).isEmpty();
        long proposed = jdbc.sql("select count(*) from matches where outcome = 'MATCHED' and state = 'proposed' and reasons like '%do not add up%'")
                .query(Long.class).single();
        assertThat(proposed).isEqualTo(4);
    }

    @Test
    void theSameStatementImportedConcurrentlyIsAppliedOnce() {
        loadLedger();
        CompletableFuture<HttpResponse<String>> a = CompletableFuture.supplyAsync(() -> importXml(fixture()));
        CompletableFuture<HttpResponse<String>> b = CompletableFuture.supplyAsync(() -> importXml(fixture()));
        List<Integer> codes = List.of(a.join().statusCode(), b.join().statusCode()).stream().sorted().toList();
        assertThat(codes).containsExactly(201, 409);
        assertThat(jdbc.sql("select count(*) from statements").query(Long.class).single()).isEqualTo(1);
        assertThat(paid()).hasSize(5);
    }

    @Test
    void refusesHostileAndInvalidInputWithProblemDetails() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\"><BkToCstmrStmt/></Document>";
        HttpResponse<String> r = importXml(xxe.getBytes(StandardCharsets.UTF_8));
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(r.body()).contains("Not a readable camt.053 statement").doesNotContain("root:");

        assertThat(importXml(new byte[20 * 1024 * 1024 + 1]).statusCode()).isEqualTo(413);

        HttpResponse<String> badRef = json("PUT", "/api/invoices/x",
                "{\"number\":\"N\",\"amountDue\":10,\"currency\":\"EUR\",\"creditorReference\":\"RF19539007547034\",\"customerName\":\"C\"}");
        assertThat(badRef.statusCode()).isEqualTo(400);
        assertThat(badRef.body()).contains("not a valid ISO 11649 reference");

        HttpResponse<String> badBody = json("PUT", "/api/invoices/x", "{\"number\":\"\",\"amountDue\":0,\"currency\":\"eur\",\"customerName\":\"C\"}");
        assertThat(badBody.statusCode()).isEqualTo(400);
        assertThat(badBody.body()).contains("amountDue:", "currency:", "number:");

        assertThat(json("PUT", "/api/invoices/" + "x".repeat(65), "{\"number\":\"N\",\"amountDue\":10,\"currency\":\"EUR\",\"customerName\":\"C\"}").body())
                .contains("id: must be 1-64 characters");
        assertThat(json("GET", "/api/statements/00000000-0000-0000-0000-000000000000/matches", null).statusCode()).isEqualTo(404);
    }
}
