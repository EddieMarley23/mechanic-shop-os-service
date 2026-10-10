package org.project.mechanic_shop.infrastructure.adapters;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.project.mechanic_shop.application.ports.SagaCommandPublisher;
import org.project.mechanic_shop.application.saga.SagaMessage;
import org.project.mechanic_shop.application.saga.SagaParticipant;
import org.project.mechanic_shop.infrastructure.jpa.entities.OutboxMessageJpaEntity;
import org.project.mechanic_shop.infrastructure.jpa.repositories.OutboxMessageJpaRepository;
import org.project.mechanic_shop.infrastructure.messaging.RabbitTopologyConfig;
import org.project.mechanic_shop.shared.config.observability.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Transactional outbox: the command is stored in the caller's transaction and relayed to RabbitMQ
 * later by {@code OutboxRelay}.
 */
@Component
@RequiredArgsConstructor
public class OutboxSagaCommandPublisher implements SagaCommandPublisher {

	private final OutboxMessageJpaRepository outboxRepository;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void send(SagaParticipant participant, UUID sagaId, UUID serviceOrderId, String type, Object payload) {
		Instant now = Instant.now(clock);

		SagaMessage message = new SagaMessage(
			UUID.randomUUID(),
			sagaId,
			serviceOrderId,
			type,
			now,
			MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY),
			objectMapper.valueToTree(payload)
		);

		OutboxMessageJpaEntity outbox = new OutboxMessageJpaEntity();
		outbox.setId(message.messageId());
		outbox.setExchange(RabbitTopologyConfig.COMMANDS_EXCHANGE);
		outbox.setRoutingKey(routingKeyOf(participant));
		outbox.setMessageType(type);
		outbox.setPayload(objectMapper.writeValueAsString(message));
		outbox.setCreatedAt(now);

		outboxRepository.save(outbox);
	}

	private String routingKeyOf(SagaParticipant participant) {
		return switch (participant) {
			case BILLING -> RabbitTopologyConfig.BILLING_ROUTING_KEY;
			case EXECUTION -> RabbitTopologyConfig.EXECUTION_ROUTING_KEY;
		};
	}
}
