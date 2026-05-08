package com.mohamedali.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.mohamedali.ledger.ledger.adapter.in.messaging.ExternalLedgerEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"ledger-events.v1", "ledger-events.v1.dlq"})
class KafkaLedgerEventConsumerIntegrationTest {

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
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add("ledger.kafka.topic.events", () -> "ledger-events.v1");
        registry.add("ledger.kafka.topic.dlq", () -> "ledger-events.v1.dlq");
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    private UUID customerId;
    private UUID settledCash;
    private UUID omnibus;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_issues, reconciliation_runs, settlement_batches, withdrawal_requests, account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");
        customerId = UUID.randomUUID();
        settledCash = UUID.randomUUID();
        omnibus = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, 'SETTLED_CASH', 'AED', 'ACTIVE')",
                settledCash, customerId);
        jdbcTemplate.update("INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, 'BROKERAGE_OMNIBUS', 'AED', 'ACTIVE')",
                omnibus, customerId);
    }

    @AfterEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_issues, reconciliation_runs, settlement_batches, withdrawal_requests, account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");
    }

    @Test
    void duplicateDepositEvent_isIdempotent_noDoublePosting() throws Exception {
        UUID eventId = UUID.randomUUID();
        ExternalLedgerEvent event = new ExternalLedgerEvent(
                eventId,
                "VA_CREDITED",
                eventId,
                customerId,
                settledCash,
                null,
                null,
                null,
                null,
                omnibus,
                null,
                null,
                null,
                new BigDecimal("100.00"),
                null,
                null,
                null,
                com.mohamedali.ledger.ledger.domain.model.Currency.AED,
                System.currentTimeMillis()
        );

        String payload = objectMapper.writeValueAsString(event);
        kafkaTemplate.send("ledger-events.v1", customerId.toString(), payload).get();
        kafkaTemplate.send("ledger-events.v1", customerId.toString(), payload).get();

        waitUntil(() -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_postings", Integer.class) == 1, 8000);

        Integer postings = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_postings", Integer.class);
        Integer journalRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class);
        BigDecimal balance = jdbcTemplate.queryForObject("SELECT balance FROM account_balances WHERE account_id = ?", BigDecimal.class, settledCash);

        assertThat(postings).isEqualTo(1);
        assertThat(journalRows).isEqualTo(2);
        assertThat(balance).isEqualByComparingTo("100.00");
    }

    @Test
    void unsupportedEvent_goesToDlqAfterRetries() throws Exception {
        ExternalLedgerEvent event = new ExternalLedgerEvent(
                UUID.randomUUID(),
                "UNKNOWN_EVENT",
                UUID.randomUUID(),
                customerId,
                settledCash,
                null,
                null,
                null,
                null,
                omnibus,
                null,
                null,
                null,
                new BigDecimal("1.00"),
                null,
                null,
                null,
                com.mohamedali.ledger.ledger.domain.model.Currency.AED,
                System.currentTimeMillis()
        );

        kafkaTemplate.send("ledger-events.v1", customerId.toString(), objectMapper.writeValueAsString(event)).get();

        Map<String, Object> props = KafkaTestUtils.consumerProps("dlq-test-group", "false", embeddedKafkaBroker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "ledger-events.v1.dlq");

        ConsumerRecord<String, String> matched = pollForEventRecord(
                consumer,
                event.eventId().toString(),
                Duration.ofSeconds(10)
        );
        consumer.close();

        assertThat(matched).isNotNull();
        assertThat(matched.value()).contains("UNKNOWN_EVENT");
        // Header may be absent on old retained records, but must be present for new DLQ records.
        assertThat(headerValue(matched, "x-dlq-stack-hash")).isNotBlank();
    }

    @Test
    void keyCustomerMismatch_goesToDlqAndDoesNotPost() throws Exception {
        UUID eventId = UUID.randomUUID();
        ExternalLedgerEvent event = new ExternalLedgerEvent(
                eventId,
                "VA_CREDITED",
                eventId,
                customerId,
                settledCash,
                null,
                null,
                null,
                null,
                omnibus,
                null,
                null,
                null,
                new BigDecimal("10.00"),
                null,
                null,
                null,
                com.mohamedali.ledger.ledger.domain.model.Currency.AED,
                System.currentTimeMillis()
        );

        // Wrong key on purpose: strict ordering requires key == customerId.
        kafkaTemplate.send("ledger-events.v1", UUID.randomUUID().toString(), objectMapper.writeValueAsString(event)).get();

        Map<String, Object> props = KafkaTestUtils.consumerProps("dlq-test-group-2", "false", embeddedKafkaBroker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "ledger-events.v1.dlq");

        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
        consumer.close();

        assertThat(records.count()).isGreaterThanOrEqualTo(1);
        Integer postings = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_postings", Integer.class);
        assertThat(postings).isZero();
    }

    private static void waitUntil(Check condition, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition.ok()) {
                return;
            }
            Thread.sleep(100);
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }

    private static String headerValue(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value());
    }

    private static ConsumerRecord<String, String> pollForEventRecord(Consumer<String, String> consumer,
                                                                     String eventId,
                                                                     Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null && record.value().contains(eventId)) {
                    return record;
                }
            }
        }
        return null;
    }
}
