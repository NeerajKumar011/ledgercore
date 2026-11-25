package com.ledgercore;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

	static {
		POSTGRES.start();
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// Pin to same major version as docker-compose.yml (postgres:18).
		// Shared singleton across all Spring test contexts avoids starting
		// multiple Docker containers, saving significant total test execution time.
		return POSTGRES;
	}

}
