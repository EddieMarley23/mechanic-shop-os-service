package org.project.mechanic_shop.infrastructure.jpa.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "saga_log", indexes = @Index(name = "idx_saga_log_saga", columnList = "saga_id, id"))
@Getter
@Setter
@NoArgsConstructor
public class SagaLogJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "saga_id", nullable = false, updatable = false)
	private UUID sagaId;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Column(name = "trigger_type", nullable = false)
	private String trigger;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SagaStepEnum step;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SagaStatusEnum status;

	@Column(length = 1000)
	private String note;
}
