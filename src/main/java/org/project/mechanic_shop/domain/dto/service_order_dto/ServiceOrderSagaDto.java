package org.project.mechanic_shop.domain.dto.service_order_dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.project.mechanic_shop.domain.entities.saga.SagaInstance;
import org.project.mechanic_shop.domain.entities.saga.SagaLogEntry;
import org.project.mechanic_shop.domain.enums.SagaStatusEnum;
import org.project.mechanic_shop.domain.enums.SagaStepEnum;

@Schema(description = "State and history of the Saga that coordinates a service order across the microservices")
public record ServiceOrderSagaDto(
	UUID sagaId,
	UUID serviceOrderId,
	@Schema(description = "Step the Saga is waiting at", example = "AWAITING_PAYMENT") SagaStepEnum step,
	@Schema(description = "Overall outcome so far", example = "RUNNING") SagaStatusEnum status,
	@Schema(description = "Why the Saga was canceled or compensated, when it was") String failureReason,
	@Schema(description = "When the current step times out and the Saga is compensated") Instant stepDeadline,
	Instant startedAt,
	Instant finishedAt,
	List<Entry> history
) {
	public record Entry(Instant at, String trigger, SagaStepEnum step, SagaStatusEnum status, String note) {}

	public static ServiceOrderSagaDto from(SagaInstance saga, List<SagaLogEntry> log) {
		return new ServiceOrderSagaDto(
			saga.getId(),
			saga.getServiceOrderId(),
			saga.getStep(),
			saga.getStatus(),
			saga.getFailureReason(),
			saga.getStepDeadline(),
			saga.getStartedAt(),
			saga.getFinishedAt(),
			log.stream().map(e -> new Entry(e.at(), e.trigger(), e.step(), e.status(), e.note())).toList()
		);
	}
}
