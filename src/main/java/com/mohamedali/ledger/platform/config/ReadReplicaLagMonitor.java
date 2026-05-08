package com.mohamedali.ledger.platform.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReadReplicaLagMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(ReadReplicaLagMonitor.class);

    private final JdbcTemplate readReplicaPhysicalJdbcTemplate;
    private final ReadReplicaRoutingState routingState;
    private final boolean enabled;
    private final long maxLagMs;
    private final AtomicLong lagMsGauge = new AtomicLong(0);

    public ReadReplicaLagMonitor(@Qualifier("readReplicaPhysicalJdbcTemplate") JdbcTemplate readReplicaPhysicalJdbcTemplate,
                                 ReadReplicaRoutingState routingState,
                                 MeterRegistry meterRegistry,
                                 @Value("${ledger.datasource.read-replica.enabled:false}") boolean enabled,
                                 @Value("${ledger.datasource.read-replica.max-lag-ms:500}") long maxLagMs) {
        this.readReplicaPhysicalJdbcTemplate = readReplicaPhysicalJdbcTemplate;
        this.routingState = routingState;
        this.enabled = enabled;
        this.maxLagMs = maxLagMs;
        meterRegistry.gauge("read_replica_lag_ms", lagMsGauge);
    }

    @Scheduled(fixedDelayString = "${ledger.datasource.read-replica.lag-check-interval-ms:5000}")
    public void checkLag() {
        if (!enabled) {
            routingState.setReplicaHealthy(false);
            lagMsGauge.set(0);
            return;
        }
        try {
            // On true replicas this returns replay lag; on primary it is NULL.
            BigDecimal lagSeconds = readReplicaPhysicalJdbcTemplate.queryForObject(
                    "SELECT EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp()))",
                    BigDecimal.class
            );
            long lagMs = lagSeconds == null ? 0L : lagSeconds.multiply(BigDecimal.valueOf(1000)).longValue();
            lagMsGauge.set(Math.max(0L, lagMs));
            boolean healthy = lagMs <= maxLagMs;
            routingState.setReplicaHealthy(healthy);
            if (!healthy) {
                LOG.warn("Read replica lag too high; falling back to primary lagMs={} thresholdMs={}", lagMs, maxLagMs);
            }
        } catch (Exception ex) {
            routingState.setReplicaHealthy(false);
            lagMsGauge.set(maxLagMs + 1);
            LOG.warn("Read replica lag check failed; falling back to primary error={}", ex.toString());
        }
    }
}
