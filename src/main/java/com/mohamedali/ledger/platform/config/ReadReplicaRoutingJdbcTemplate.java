package com.mohamedali.ledger.platform.config;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class ReadReplicaRoutingJdbcTemplate extends JdbcTemplate {

    private final JdbcTemplate primaryJdbcTemplate;
    private final JdbcTemplate replicaJdbcTemplate;
    private final ReadReplicaRoutingState routingState;
    private final boolean replicaEnabled;

    public ReadReplicaRoutingJdbcTemplate(JdbcTemplate primaryJdbcTemplate,
                                          JdbcTemplate replicaJdbcTemplate,
                                          ReadReplicaRoutingState routingState,
                                          boolean replicaEnabled) {
        setDataSource(primaryJdbcTemplate.getDataSource());
        this.primaryJdbcTemplate = primaryJdbcTemplate;
        this.replicaJdbcTemplate = replicaJdbcTemplate;
        this.routingState = routingState;
        this.replicaEnabled = replicaEnabled;
    }

    private JdbcTemplate current() {
        if (!replicaEnabled) {
            return primaryJdbcTemplate;
        }
        return routingState.replicaHealthy() ? replicaJdbcTemplate : primaryJdbcTemplate;
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        return current().queryForObject(sql, requiredType, args);
    }

    @Override
    public List<java.util.Map<String, Object>> queryForList(String sql, Object... args) {
        return current().queryForList(sql, args);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        return current().query(sql, rowMapper, args);
    }
}
