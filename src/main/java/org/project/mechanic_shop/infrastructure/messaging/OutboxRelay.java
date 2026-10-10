package org.project.mechanic_shop.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.infrastructure.jpa.entities.OutboxMessageJpaEntity;
import org.project.mechanic_shop.infrastructure.jpa.repositories.OutboxMessageJpaRepository;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relays the outbox to RabbitMQ. A message is marked as published in the same transaction that
 * sent it, so a crash in between causes a resend — never a loss. Consumers are idempotent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

	private final OutboxMessageJpaRepository outboxRepository;
	private final RabbitTemplate rabbitTemplate;
	private final Clock clock;

	@Value("${app.outbox.batch-size:50}")
	private int batchSize;

	@Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
	@Transactional
	public void relayPending() {
		List<OutboxMessageJpaEntity> batch = outboxRepository.lockPendingBatch(PageRequest.of(0, batchSize));

		for (OutboxMessageJpaEntity outbox : batch) {
			Message message = MessageBuilder
				.withBody(outbox.getPayload().getBytes(StandardCharsets.UTF_8))
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.setContentEncoding(StandardCharsets.UTF_8.name())
				.setMessageId(outbox.getId().toString())
				.setType(outbox.getMessageType())
				.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
				.build();

			try {
				rabbitTemplate.send(outbox.getExchange(), outbox.getRoutingKey(), message);
			} catch (AmqpException e) {
				log.warn("Broker unavailable. {} outbox message(s) will be retried: {}", batch.size(), e.getMessage());
				return;
			}

			outbox.setPublishedAt(Instant.now(clock));
		}
	}
}
