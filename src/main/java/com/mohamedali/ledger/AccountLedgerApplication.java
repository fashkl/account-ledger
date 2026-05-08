package com.mohamedali.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AccountLedgerApplication {

	public static void main(String[] args) {
		SpringApplication.run(AccountLedgerApplication.class, args);
	}

}
