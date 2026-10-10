package org.project.mechanic_shop.infrastructure.adapters;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.project.mechanic_shop.application.ports.SagaInstanceRepositoryPort;
import org.project.mechanic_shop.domain.entities.saga.SagaInstance;
import org.project.mechanic_shop.domain.entities.saga.SagaLogEntry;
import org.project.mechanic_shop.domain.enums.SagaStatusEnum;
import org.project.mechanic_shop.infrastructure.jpa.entities.SagaInstanceJpaEntity;
import org.project.mechanic_shop.infrastructure.jpa.entities.SagaLogJpaEntity;
import org.project.mechanic_shop.infrastructure.jpa.repositories.SagaInstanceJpaRepository;
import org.project.mechanic_shop.infrastructure.jpa.repositories.SagaLogJpaRepository;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SagaInstanceRepositoryAdapter implements SagaInstanceRepositoryPort {

	private final SagaInstanceJpaRepository jpaRepository;
	private final SagaLogJpaRepository logRepository;

	@Override
	public SagaInstance save(SagaInstance saga) {
		return toDomain(jpaRepository.save(toJpa(saga)));
	}

	@Override
	public Optional<SagaInstance> findWithLockById(UUID id) {
		return jpaRepository.findWithLockById(id).map(this::toDomain);
	}

	@Override
	public Optional<SagaInstance> findByServiceOrderId(UUID serviceOrderId) {
		return jpaRepository.findByServiceOrderId(serviceOrderId).map(this::toDomain);
	}

	@Override
	public List<UUID> findExpiredIds(Instant now) {
		return jpaRepository.findExpiredIds(SagaStatusEnum.RUNNING, now);
	}

	@Override
	public void appendLog(SagaLogEntry entry) {
		SagaLogJpaEntity jpa = new SagaLogJpaEntity();
		jpa.setSagaId(entry.sagaId());
		jpa.setOccurredAt(entry.at());
		jpa.setTrigger(entry.trigger());
		jpa.setStep(entry.step());
		jpa.setStatus(entry.status());
		jpa.setNote(entry.note());
		logRepository.save(jpa);
	}

	@Override
	public List<SagaLogEntry> findLog(UUID sagaId) {
		return logRepository
			.findBySagaIdOrderByIdAsc(sagaId)
			.stream()
			.map(l -> new SagaLogEntry(l.getSagaId(), l.getOccurredAt(), l.getTrigger(), l.getStep(), l.getStatus(), l.getNote()))
			.toList();
	}

	private SagaInstance toDomain(SagaInstanceJpaEntity jpa) {
		SagaInstance saga = new SagaInstance();
		saga.setId(jpa.getId());
		saga.setServiceOrderId(jpa.getServiceOrderId());
		saga.setStep(jpa.getStep());
		saga.setStatus(jpa.getStatus());
		saga.setStockReserved(jpa.isStockReserved());
		saga.setFailureReason(jpa.getFailureReason());
		saga.setStepDeadline(jpa.getStepDeadline());
		saga.setStartedAt(jpa.getStartedAt());
		saga.setUpdatedAt(jpa.getUpdatedAt());
		saga.setFinishedAt(jpa.getFinishedAt());
		return saga;
	}

	private SagaInstanceJpaEntity toJpa(SagaInstance saga) {
		SagaInstanceJpaEntity jpa = new SagaInstanceJpaEntity();
		jpa.setId(saga.getId());
		jpa.setServiceOrderId(saga.getServiceOrderId());
		jpa.setStep(saga.getStep());
		jpa.setStatus(saga.getStatus());
		jpa.setStockReserved(saga.isStockReserved());
		jpa.setFailureReason(saga.getFailureReason());
		jpa.setStepDeadline(saga.getStepDeadline());
		jpa.setStartedAt(saga.getStartedAt());
		jpa.setUpdatedAt(saga.getUpdatedAt());
		jpa.setFinishedAt(saga.getFinishedAt());
		return jpa;
	}
}
