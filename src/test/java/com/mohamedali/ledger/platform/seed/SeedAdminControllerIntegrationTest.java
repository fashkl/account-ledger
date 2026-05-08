package com.mohamedali.ledger.platform.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ledger.seed.enabled=true")
@Testcontainers
class SeedAdminControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("account_ledger")
            .withUsername("ledger")
            .withPassword("ledger");

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("ledger.datasource.settlement.url", postgres::getJdbcUrl);
        registry.add("ledger.datasource.settlement.username", postgres::getUsername);
        registry.add("ledger.datasource.settlement.password", postgres::getPassword);
        registry.add("ledger.datasource.read-replica.enabled", () -> false);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    reconciliation_issues,
                    reconciliation_runs,
                    settlement_batches,
                    withdrawal_requests,
                    account_balances,
                    journal_entries,
                    ledger_postings,
                    order_states,
                    accounts
                CASCADE
                """);
    }

    @Test
    void runEndpoint_returns200WithSummaryPayload() throws Exception {
        HttpResponse<String> response = post("/api/v1/admin/seed/run?dataset=medium&reset=true");

        assertThat(response.statusCode()).isEqualTo(200);
        SeedRunResult body = objectMapper.readValue(response.body(), SeedRunResult.class);
        assertThat(body.dataset()).isEqualTo("medium");
        assertThat(body.recordsCreatedByTable().get("accounts")).isEqualTo(144);
    }

    @Test
    void resetEndpoint_clearsBusinessTables() throws Exception {
        post("/api/v1/admin/seed/run?dataset=medium&reset=true");

        HttpResponse<String> reset = post("/api/v1/admin/seed/reset");

        assertThat(reset.statusCode()).isEqualTo(200);
        SeedRunResult body = objectMapper.readValue(reset.body(), SeedRunResult.class);
        assertThat(body.resetApplied()).isTrue();

        Integer accounts = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class);
        assertThat(accounts).isZero();
    }

    @SuppressWarnings("unchecked")
    @Test
    void catalogEndpoint_returnsKnownIdsAndScenarios() throws Exception {
        HttpResponse<String> response = get("/api/v1/admin/seed/catalog");

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
        assertThat(body.get("defaultDataset")).isEqualTo("medium");

        Map<String, Object> knownIds = (Map<String, Object>) body.get("knownIds");
        assertThat(knownIds).containsKeys("firstCustomerId", "firstSettledCashAccountId");
    }

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
