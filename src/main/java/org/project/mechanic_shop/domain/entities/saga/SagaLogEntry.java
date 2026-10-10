package org.project.mechanic_shop.domain.entities.saga;

import java.time.Instant;
import java.util.UUID;
import org.project.mechanic_shop.domain.enums.SagaStatusEnum;
import org.project.mechanic_shop.domain.enums.SagaStepEnum;

/** One line of a Saga's history: what happened and the state the Saga was left in. */
public record SagaLogEntry(
	UUID sagaId,
	Instant at,
	String trigger,
	SagaStepEnum step,
	SagaStatusEnum status,
	String note
) {}
