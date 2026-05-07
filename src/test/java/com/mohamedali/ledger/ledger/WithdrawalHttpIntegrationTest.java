package com.mohamedali.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WithdrawalHttpIntegrationTest {

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
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    private UUID customerId;
    private UUID settledCashAccountId;
    private UUID settlementPendingAccountId;
    private UUID brokerageOmnibusAccountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE withdrawal_requests, account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");

        customerId = UUID.randomUUID();
        settledCashAccountId = UUID.randomUUID();
        settlementPendingAccountId = UUID.randomUUID();
        brokerageOmnibusAccountId = UUID.randomUUID();

        insertAccount(settledCashAccountId, customerId, "SETTLED_CASH");
        insertAccount(settlementPendingAccountId, customerId, "SETTLEMENT_PENDING");
        insertAccount(brokerageOmnibusAccountId, customerId, "BROKERAGE_OMNIBUS");
    }

    @AfterEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE withdrawal_requests, account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");
    }

    @Test
    void missingCurrencyOnCashEvent_returns422() throws Exception {
        UUID withdrawalId = UUID.randomUUID();
        String body = """
                {
                  "eventType": "WITHDRAWAL_REQUESTED",
                  "withdrawalId": "%s",
                  "customerId": "%s",
                  "settledCashAccountId": "%s",
                  "settlementPendingAccountId": "%s",
                  "brokerageOmnibusAccountId": "%s",
                  "amount": "100.00"
                }
                """.formatted(
                withdrawalId,
                customerId,
                settledCashAccountId,
                settlementPendingAccountId,
                brokerageOmnibusAccountId
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/cash/events"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(422);
    }

    private void insertAccount(UUID accountId, UUID cId, String type) {
        jdbcTemplate.update(
                "INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, ?, ?, ?)",
                accountId, cId, type, "AED", "ACTIVE"
        );
    }
}
