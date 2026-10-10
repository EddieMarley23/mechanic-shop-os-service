package org.project.mechanic_shop.infrastructure.adapters;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.project.mechanic_shop.application.ports.ProcessedMessagePort;
import org.project.mechanic_shop.infrastructure.jpa.entities.ProcessedMessageJpaEntity;
import org.project.mechanic_shop.infrastructure.jpa.repositories.ProcessedMessageJpaRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProcessedMessageAdapter implements ProcessedMessagePort {

	private final ProcessedMessageJpaRepository repository;
	private final Clock clock;

	/**
	 * Two concurrent deliveries of the same message can both pass the {@code existsById} check; the
	 * primary key then makes the second transaction fail and roll back, and its redelivery is ignored.
	 */
	@Override
	public boolean registerIfNew(UUID messageId, String type) {
		if (repository.existsById(messageId)) return false;

		repository.saveAndFlush(new ProcessedMessageJpaEntity(messageId, type, Instant.now(clock)));
		return true;
	}
}
