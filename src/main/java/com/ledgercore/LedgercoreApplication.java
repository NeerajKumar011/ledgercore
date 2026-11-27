package com.ledgercore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LedgercoreApplication {

	public static void main(String[] args) {
		SpringApplication.run(LedgercoreApplication.class, args);
	}

}
