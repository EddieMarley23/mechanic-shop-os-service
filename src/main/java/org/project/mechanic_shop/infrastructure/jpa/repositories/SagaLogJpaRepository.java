package org.project.mechanic_shop.infrastructure.jpa.repositories;

import java.util.List;
import java.util.UUID;
import org.project.mechanic_shop.infrastructure.jpa.entities.SagaLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaLogJpaRepository extends JpaRepository<SagaLogJpaEntity, Long> {
	List<SagaLogJpaEntity> findBySagaIdOrderByIdAsc(UUID sagaId);
}
