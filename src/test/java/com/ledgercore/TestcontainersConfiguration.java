package com.ledgercore;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// Pin to same major version as docker-compose.yml (postgres:18).
		// Using a specific patch tag so the image is immutable in CI.
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
	}

}
