package org.project.mechanic_shop.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.domain.entities.saga.SagaInstance;
import org.project.mechanic_shop.domain.enums.SagaStatusEnum;
import org.project.mechanic_shop.domain.enums.SagaStepEnum;

class SagaInstanceTest {

	private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");

	@Test
	void shouldStartRunningAtTheDiagnosisStep() {
		UUID serviceOrderId = UUID.randomUUID();

		SagaInstance saga = SagaInstance.start(serviceOrderId, NOW);

		assertThat(saga.getId()).isNotNull();
		assertThat(saga.getServiceOrderId()).isEqualTo(serviceOrderId);
		assertThat(saga.isWaitingAt(SagaStepEnum.DIAGNOSIS)).isTrue();
		assertThat(saga.isStockReserved()).isFalse();
		assertThat(saga.getStartedAt()).isEqualTo(NOW);
	}

	@Test
	void shouldAdvanceToTheNextStepWithItsDeadline() {
		SagaInstance saga = SagaInstance.start(UUID.randomUUID(), NOW);
		Instant deadline = NOW.plusSeconds(60);

		saga.advanceTo(SagaStepEnum.AWAITING_APPROVAL, deadline, NOW.plusSeconds(1));

		assertThat(saga.isWaitingAt(SagaStepEnum.AWAITING_APPROVAL)).isTrue();
		assertThat(saga.isWaitingAt(SagaStepEnum.DIAGNOSIS)).isFalse();
		assertThat(saga.getStepDeadline()).isEqualTo(deadline);
		assertThat(saga.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
	}

	@Test
	void shouldNotWaitAtAnyStepOnceFinished() {
		SagaInstance saga = SagaInstance.start(UUID.randomUUID(), NOW);
		saga.advanceTo(SagaStepEnum.EXECUTION, NOW.plusSeconds(60), NOW);

		saga.finish(SagaStatusEnum.COMPLETED, null, NOW.plusSeconds(5));

		assertThat(saga.getStep()).isEqualTo(SagaStepEnum.DONE);
		assertThat(saga.getStatus().isFinished()).isTrue();
		assertThat(saga.getStepDeadline()).isNull();
		assertThat(saga.getFinishedAt()).isEqualTo(NOW.plusSeconds(5));
		assertThat(saga.isWaitingAt(SagaStepEnum.EXECUTION)).isFalse();
	}

	@Test
	void shouldStayUnfinishedWhileAParticipantStillHasToCompensate() {
		SagaInstance saga = SagaInstance.start(UUID.randomUUID(), NOW);
		saga.advanceTo(SagaStepEnum.EXECUTION, NOW.plusSeconds(60), NOW);

		saga.startCompensation("Repair failed", NOW.plusSeconds(5));

		assertThat(saga.getStatus()).isEqualTo(SagaStatusEnum.COMPENSATING);
		assertThat(saga.getStatus().isFinished()).isFalse();
		assertThat(saga.getFailureReason()).isEqualTo("Repair failed");
		assertThat(saga.getStepDeadline()).isNull();
		assertThat(saga.getFinishedAt()).isNull();
	}

	@Test
	void shouldDescribeEveryStepAndStatus() {
		for (SagaStepEnum step : SagaStepEnum.values()) {
			assertThat(step.getLabel()).isNotBlank();
			assertThat(step.getDescription()).isNotBlank();
		}
		for (SagaStatusEnum status : SagaStatusEnum.values()) {
			assertThat(status.getLabel()).isNotBlank();
			assertThat(status.getDescription()).isNotBlank();
		}
	}
}
