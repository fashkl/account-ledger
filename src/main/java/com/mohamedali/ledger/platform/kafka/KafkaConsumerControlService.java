package com.mohamedali.ledger.platform.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;

@Service
public class KafkaConsumerControlService {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerControlService.class);

    private final KafkaListenerEndpointRegistry registry;
    private final MeterRegistry meterRegistry;
    private final Map<String, Boolean> pausedState = new ConcurrentHashMap<>();

    public KafkaConsumerControlService(KafkaListenerEndpointRegistry registry, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
    }

    public void pause(String listenerId, String reason) {
        MessageListenerContainer container = registry.getListenerContainer(listenerId);
        if (container == null) {
            return;
        }
        container.pause();
        pausedState.put(listenerId, true);
        meterRegistry.gauge("kafka_consumer_paused", pausedState, s -> s.getOrDefault(listenerId, false) ? 1 : 0);
        LOG.warn("Kafka listener paused listenerId={} reason={}", listenerId, reason);
    }

    public void resume(String listenerId, String reason) {
        MessageListenerContainer container = registry.getListenerContainer(listenerId);
        if (container == null) {
            return;
        }
        container.resume();
        pausedState.put(listenerId, false);
        LOG.info("Kafka listener resumed listenerId={} reason={}", listenerId, reason);
    }

    public boolean isPaused(String listenerId) {
        return pausedState.getOrDefault(listenerId, false);
    }
}
