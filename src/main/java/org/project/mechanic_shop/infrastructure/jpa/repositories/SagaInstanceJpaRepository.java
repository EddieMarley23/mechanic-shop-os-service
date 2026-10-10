package org.project.mechanic_shop.infrastructure.jpa.repositories;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.project.mechanic_shop.domain.enums.SagaStatusEnum;
import org.project.mechanic_shop.infrastructure.jpa.entities.SagaInstanceJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SagaInstanceJpaRepository extends JpaRepository<SagaInstanceJpaEntity, UUID> {
	Optional<SagaInstanceJpaEntity> findByServiceOrderId(UUID serviceOrderId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from SagaInstanceJpaEntity s where s.id = :id")
	Optional<SagaInstanceJpaEntity> findWithLockById(@Param("id") UUID id);

	@Query("select s.id from SagaInstanceJpaEntity s where s.status = :status and s.stepDeadline < :now")
	List<UUID> findExpiredIds(@Param("status") SagaStatusEnum status, @Param("now") Instant now);
}
