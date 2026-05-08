package com.mohamedali.ledger.shared.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Account Ledger API")
                        .version("v1")
                        .description("Ledger, order lifecycle, withdrawal, and platform admin APIs.")
                        .contact(new Contact().name("Account Ledger Team")));
    }
}
