package org.project.mechanic_shop.application.ports;

import java.util.UUID;
import org.project.mechanic_shop.application.saga.SagaParticipant;

/**
 * Sends the orchestrator's commands to the Saga participants.
 *
 * <p>Implementations must take part in the caller's transaction (transactional outbox), so a
 * command is never sent for a state change that was rolled back, and never lost for one that was
 * committed.
 */
public interface SagaCommandPublisher {
	void send(SagaParticipant participant, UUID sagaId, UUID serviceOrderId, String type, Object payload);
}
