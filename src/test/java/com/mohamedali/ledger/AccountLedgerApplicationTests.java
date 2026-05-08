package com.mohamedali.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class AccountLedgerApplicationTests {

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
    private DataSource dataSource;

    @Test
    void contextLoadsAndFlywayCreatesCoreTables() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT to_regclass('public.accounts'), to_regclass('public.journal_entries'), to_regclass('public.account_balances'), to_regclass('public.ledger_postings'), to_regclass('public.order_states')"
             )) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("accounts");
            assertThat(resultSet.getString(2)).isEqualTo("journal_entries");
            assertThat(resultSet.getString(3)).isEqualTo("account_balances");
            assertThat(resultSet.getString(4)).isEqualTo("ledger_postings");
            assertThat(resultSet.getString(5)).isEqualTo("order_states");
        }
    }

    @Test
    void pgcryptoOrBuiltinUuidGeneratorIsAvailable() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     """
                     SELECT
                       current_setting('server_version_num')::int AS version_num,
                       to_regprocedure('gen_random_uuid()') IS NOT NULL AS has_gen_random_uuid,
                       EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgcrypto') AS has_pgcrypto
                     """
             )) {
            assertThat(resultSet.next()).isTrue();
            int versionNum = resultSet.getInt("version_num");
            boolean hasGenRandomUuid = resultSet.getBoolean("has_gen_random_uuid");
            boolean hasPgcrypto = resultSet.getBoolean("has_pgcrypto");

            // Migration V1 relies on gen_random_uuid() defaults. We accept either:
            // - builtin function availability on modern PostgreSQL
            // - pgcrypto extension providing the function.
            assertThat(hasGenRandomUuid || hasPgcrypto)
                    .withFailMessage("gen_random_uuid() is unavailable on PostgreSQL version %s; install/allow pgcrypto or use PostgreSQL >= 13",
                            versionNum)
                    .isTrue();
        }
    }

    @Test
    void v2MidFlightFailure_rollsBackConstraintDropAndLeavesNoLedgerPostingsTable() throws Exception {
        String schema = "phase0_" + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            connection.setAutoCommit(false);

            try {
                statement.execute("SET LOCAL search_path TO " + schema + ", public");
                statement.execute("""
                        CREATE TABLE journal_entries (
                            id UUID PRIMARY KEY,
                            idempotency_key TEXT UNIQUE
                        )
                        """);

                Savepoint beforeV2 = connection.setSavepoint("before_v2");
                try {
                    statement.execute("""
                            DO $$
                            BEGIN
                                IF EXISTS (
                                    SELECT 1
                                    FROM pg_constraint
                                    WHERE conname = 'journal_entries_idempotency_key_key'
                                ) THEN
                                    ALTER TABLE journal_entries DROP CONSTRAINT journal_entries_idempotency_key_key;
                                END IF;
                            END $$;
                            """);

                    // Simulate failure after the constraint drop and before the table creation completes.
                    statement.execute("SELECT invalid_sql_to_force_v2_failure()");
                } catch (Exception expected) {
                    connection.rollback(beforeV2);
                }

                try (ResultSet constraintResult = statement.executeQuery("""
                        SELECT COUNT(*) AS cnt
                        FROM pg_constraint c
                        JOIN pg_class t ON t.oid = c.conrelid
                        JOIN pg_namespace n ON n.oid = t.relnamespace
                        WHERE n.nspname = '%s'
                          AND t.relname = 'journal_entries'
                          AND c.conname = 'journal_entries_idempotency_key_key'
                        """.formatted(schema))) {
                    assertThat(constraintResult.next()).isTrue();
                    assertThat(constraintResult.getInt("cnt")).isEqualTo(1);
                }

                try (ResultSet ledgerPostingsResult = statement.executeQuery(
                        "SELECT to_regclass('" + schema + ".ledger_postings')")) {
                    assertThat(ledgerPostingsResult.next()).isTrue();
                    assertThat(ledgerPostingsResult.getString(1)).isNull();
                }

                connection.commit();
            } finally {
                connection.setAutoCommit(true);
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
