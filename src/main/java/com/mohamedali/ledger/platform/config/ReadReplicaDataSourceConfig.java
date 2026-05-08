package com.mohamedali.ledger.platform.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ReadReplicaDataSourceConfig {

    @Bean(name = "readReplicaPhysicalJdbcTemplate")
    public JdbcTemplate readReplicaPhysicalJdbcTemplate(Environment environment) {
        String url = environment.getProperty("ledger.datasource.read-replica.url");
        String username = environment.getProperty("ledger.datasource.read-replica.username");
        String password = environment.getProperty("ledger.datasource.read-replica.password");
        String driver = environment.getProperty("ledger.datasource.read-replica.driver-class-name", "org.postgresql.Driver");

        if (url == null || username == null) {
            throw new IllegalStateException("read-replica configured without url/username");
        }

        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName(driver);
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(environment.getProperty("ledger.datasource.read-replica.hikari.maximum-pool-size", Integer.class, 20));
        ds.setMinimumIdle(environment.getProperty("ledger.datasource.read-replica.hikari.minimum-idle", Integer.class, 5));
        ds.setConnectionTimeout(environment.getProperty("ledger.datasource.read-replica.hikari.connection-timeout", Long.class, 10_000L));
        ds.setMaxLifetime(environment.getProperty("ledger.datasource.read-replica.hikari.max-lifetime", Long.class, 1_800_000L));
        ds.setKeepaliveTime(environment.getProperty("ledger.datasource.read-replica.hikari.keepalive-time", Long.class, 60_000L));
        return new JdbcTemplate(ds);
    }

    @Bean(name = "readReplicaJdbcTemplate")
    public JdbcTemplate readReplicaJdbcTemplate(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate,
                                                ReadReplicaRoutingState routingState,
                                                @Qualifier("readReplicaPhysicalJdbcTemplate") JdbcTemplate readReplicaPhysicalJdbcTemplate,
                                                Environment environment) {
        boolean enabled = environment.getProperty("ledger.datasource.read-replica.enabled", Boolean.class, false);
        return new ReadReplicaRoutingJdbcTemplate(jdbcTemplate, readReplicaPhysicalJdbcTemplate, routingState, enabled);
    }
}
