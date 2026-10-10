package org.project.mechanic_shop.shared.config.system;

import java.time.Clock;
import org.project.mechanic_shop.application.saga.SagaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling drives the outbox relay and the Saga timeout check. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SagaProperties.class)
public class SagaConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
