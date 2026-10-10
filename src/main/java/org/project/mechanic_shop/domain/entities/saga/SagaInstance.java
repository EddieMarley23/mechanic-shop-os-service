package org.project.mechanic_shop.domain.entities.saga;

import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.project.mechanic_shop.domain.enums.SagaStatusEnum;
import org.project.mechanic_shop.domain.enums.SagaStepEnum;

/**
 * State of the distributed transaction of one service order. Persisted, so the orchestrator can
 * resume after a restart and anyone can see where an order stopped and why.
 */
@Getter
@Setter
@NoArgsConstructor
public class SagaInstance {
	private UUID id;
	private UUID serviceOrderId;
	private SagaStepEnum step;
	private SagaStatusEnum status;
	/** Whether the parts were withdrawn from stock and must be returned on compensation. */
	private boolean stockReserved;
	private String failureReason;
	/** When the current step is considered lost and the Saga is compensated. Null: no limit. */
	private Instant stepDeadline;
	private Instant startedAt;
	private Instant updatedAt;
	private Instant finishedAt;

	public static SagaInstance start(UUID serviceOrderId, Instant now) {
		SagaInstance saga = new SagaInstance();
		saga.id = UUID.randomUUID();
		saga.serviceOrderId = serviceOrderId;
		saga.step = SagaStepEnum.DIAGNOSIS;
		saga.status = SagaStatusEnum.RUNNING;
		saga.startedAt = now;
		saga.updatedAt = now;
		return saga;
	}

	public boolean isWaitingAt(SagaStepEnum expected) {
		return this.status == SagaStatusEnum.RUNNING && this.step == expected;
	}

	public void advanceTo(SagaStepEnum next, Instant deadline, Instant now) {
		this.step = next;
		this.stepDeadline = deadline;
		this.updatedAt = now;
	}

	public void finish(SagaStatusEnum finalStatus, String reason, Instant now) {
		this.status = finalStatus;
		this.step = SagaStepEnum.DONE;
		this.stepDeadline = null;
		this.failureReason = reason;
		this.updatedAt = now;
		this.finishedAt = now;
	}

	/** A compensation was started but a participant still has to confirm its part (the refund). */
	public void startCompensation(String reason, Instant now) {
		this.status = SagaStatusEnum.COMPENSATING;
		this.stepDeadline = null;
		this.failureReason = reason;
		this.updatedAt = now;
	}
}
