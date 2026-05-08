package com.mohamedali.ledger.shared.infra;

import com.mohamedali.ledger.platform.kafka.KafkaConsumerControlService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CircuitBreakerLifecycleListener {

    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreakerLifecycleListener.class);
    public static final String LEDGER_CONSUMER_LISTENER_ID = "ledger-events-consumer";

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final KafkaConsumerControlService controlService;

    public CircuitBreakerLifecycleListener(CircuitBreakerRegistry circuitBreakerRegistry,
                                           KafkaConsumerControlService controlService) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.controlService = controlService;
    }

    @PostConstruct
    public void subscribe() {
        circuitBreakerRegistry.circuitBreaker("ledgerProcessing")
                .getEventPublisher()
                .onStateTransition(event -> {
                    var toState = event.getStateTransition().getToState();
                    switch (toState) {
                        case OPEN -> {
                            controlService.pause(LEDGER_CONSUMER_LISTENER_ID, "circuit breaker OPEN");
                            LOG.warn("Circuit breaker opened, kafka consumer paused");
                        }
                        case HALF_OPEN, CLOSED -> {
                            controlService.resume(LEDGER_CONSUMER_LISTENER_ID, "circuit breaker recovered");
                            LOG.info("Circuit breaker recovered, kafka consumer resumed");
                        }
                        default -> {
                        }
                    }
                });
    }
}
