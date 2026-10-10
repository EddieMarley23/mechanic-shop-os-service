package org.project.mechanic_shop.application.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.project.mechanic_shop.domain.entities.saga.SagaInstance;
import org.project.mechanic_shop.domain.entities.saga.SagaLogEntry;

public interface SagaInstanceRepositoryPort {
	SagaInstance save(SagaInstance saga);

	/** Loads the Saga locking its row, so two replies for the same Saga are processed one at a time. */
	Optional<SagaInstance> findWithLockById(UUID id);

	Optional<SagaInstance> findByServiceOrderId(UUID serviceOrderId);

	/** Ids of the running Sagas whose current step passed its deadline. */
	List<UUID> findExpiredIds(Instant now);

	void appendLog(SagaLogEntry entry);

	List<SagaLogEntry> findLog(UUID sagaId);
}
