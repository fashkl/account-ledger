package com.mohamedali.ledger.platform.config;

import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaAdminConfig {

    @Bean(destroyMethod = "close")
    public AdminClient adminClient(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return AdminClient.create(Map.of("bootstrap.servers", bootstrapServers));
    }
}
