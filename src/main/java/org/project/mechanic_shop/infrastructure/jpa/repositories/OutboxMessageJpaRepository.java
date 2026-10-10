package org.project.mechanic_shop.infrastructure.jpa.repositories;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.UUID;
import org.project.mechanic_shop.infrastructure.jpa.entities.OutboxMessageJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface OutboxMessageJpaRepository extends JpaRepository<OutboxMessageJpaEntity, UUID> {
	/**
	 * Locks a batch of pending messages, skipping rows another replica is already relaying
	 * ({@code -2} is Hibernate's SKIP LOCKED), so several pods can run the relay at once.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
	@Query("select o from OutboxMessageJpaEntity o where o.publishedAt is null order by o.createdAt")
	List<OutboxMessageJpaEntity> lockPendingBatch(Pageable pageable);
}
