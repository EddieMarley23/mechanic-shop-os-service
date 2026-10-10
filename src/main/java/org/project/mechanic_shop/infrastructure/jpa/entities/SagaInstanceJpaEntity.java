package org.project.mechanic_shop.infrastructure.jpa.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.project.mechanic_shop.domain.enums.SagaStatusEnum;
import org.project.mechanic_shop.domain.enums.SagaStepEnum;

@Entity
@Table(name = "saga_instances", indexes = @Index(name = "idx_saga_deadline", columnList = "status, step_deadline"))
@Getter
@Setter
@NoArgsConstructor
public class SagaInstanceJpaEntity {

	@Id
	private UUID id;

	@Column(name = "service_order_id", nullable = false, unique = true, updatable = false)
	private UUID serviceOrderId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SagaStepEnum step;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SagaStatusEnum status;

	@Column(name = "stock_reserved", nullable = false)
	private boolean stockReserved;

	@Column(name = "failure_reason", length = 1000)
	private String failureReason;

	@Column(name = "step_deadline")
	private Instant stepDeadline;

	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;
}
