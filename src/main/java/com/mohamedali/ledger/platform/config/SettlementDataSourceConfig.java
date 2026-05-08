package com.mohamedali.ledger.platform.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class SettlementDataSourceConfig {

    @Bean
    @ConfigurationProperties("ledger.datasource.settlement")
    public DataSourceProperties settlementDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("ledger.datasource.settlement.hikari")
    public DataSource settlementDataSource(@Qualifier("settlementDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "settlementJdbcTemplate")
    public JdbcTemplate settlementJdbcTemplate(@Qualifier("settlementDataSource") DataSource settlementDataSource) {
        return new JdbcTemplate(settlementDataSource);
    }
}
