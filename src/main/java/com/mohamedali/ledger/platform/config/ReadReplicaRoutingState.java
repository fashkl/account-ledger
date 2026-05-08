package com.mohamedali.ledger.platform.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class ReadReplicaRoutingState {

    private final AtomicBoolean replicaHealthy = new AtomicBoolean(true);

    public boolean replicaHealthy() {
        return replicaHealthy.get();
    }

    public void setReplicaHealthy(boolean healthy) {
        replicaHealthy.set(healthy);
    }
}
