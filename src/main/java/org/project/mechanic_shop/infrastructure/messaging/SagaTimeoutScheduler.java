package org.project.mechanic_shop.infrastructure.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.application.ports.SagaInstanceRepositoryPort;
import org.project.mechanic_shop.application.saga.ServiceOrderSagaOrchestrator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finds the Sagas stuck at a step past its deadline — a participant that never answered, a
 * customer that never approved or paid — and hands them to the orchestrator to be compensated.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaTimeoutScheduler {

	private final SagaInstanceRepositoryPort sagaRepository;
	private final ServiceOrderSagaOrchestrator orchestrator;
	private final Clock clock;

	@Scheduled(fixedDelayString = "${app.saga.timeout-check-interval-ms:30000}")
	public void compensateExpiredSagas() {
		for (UUID sagaId : sagaRepository.findExpiredIds(Instant.now(clock))) {
			try {
				// Each Saga in its own transaction: one failure must not hold back the others.
				orchestrator.handleTimeout(sagaId);
			} catch (RuntimeException e) {
				log.error("Failed to compensate expired Saga {}: {}", sagaId, e.getMessage(), e);
			}
		}
	}
}
