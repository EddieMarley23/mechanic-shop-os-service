package org.project.mechanic_shop.infrastructure.jpa.repositories;

import java.util.UUID;
import org.project.mechanic_shop.infrastructure.jpa.entities.ProcessedMessageJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedMessageJpaRepository extends JpaRepository<ProcessedMessageJpaEntity, UUID> {}
