package org.project.mechanic_shop.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.project.mechanic_shop.application.ports.SagaInstanceRepositoryPort;
import org.project.mechanic_shop.application.saga.SagaMessage;
import org.project.mechanic_shop.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.application.saga.SagaParticipant;
import org.project.mechanic_shop.application.saga.ServiceOrderSagaOrchestrator;
import org.project.mechanic_shop.infrastructure.adapters.OutboxSagaCommandPublisher;
import org.project.mechanic_shop.infrastructure.adapters.ProcessedMessageAdapter;
import org.project.mechanic_shop.infrastructure.jpa.entities.OutboxMessageJpaEntity;
import org.project.mechanic_shop.infrastructure.jpa.entities.ProcessedMessageJpaEntity;
import org.project.mechanic_shop.infrastructure.jpa.repositories.OutboxMessageJpaRepository;
import org.project.mechanic_shop.infrastructure.jpa.repositories.ProcessedMessageJpaRepository;
import org.project.mechanic_shop.infrastructure.messaging.OutboxRelay;
import org.project.mechanic_shop.infrastructure.messaging.RabbitTopologyConfig;
import org.project.mechanic_shop.infrastructure.messaging.SagaReplyListener;
import org.project.mechanic_shop.infrastructure.messaging.SagaTimeoutScheduler;
import org.project.mechanic_shop.shared.config.observability.ObservabilityReporter;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SagaMessagingTest {

	private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	@Test
	void shouldOwnSharedExchangesAndOnlyItsReplyQueue() {
		var declarables = new RabbitTopologyConfig().sagaTopology().getDeclarables();
		assertThat(declarables.stream().filter(Exchange.class::isInstance).map(d -> ((Exchange) d).getName()))
			.containsExactlyInAnyOrder("saga.commands", "saga.events", "saga.dlx");
		assertThat(declarables.stream().filter(Queue.class::isInstance).map(d -> ((Queue) d).getName()))
			.containsExactlyInAnyOrder("os.saga-replies", "os.saga-replies.dlq");
	}

	@Nested
	class CommandPublisher {

		private final OutboxMessageJpaRepository outboxRepository = mock(OutboxMessageJpaRepository.class);
		private final OutboxSagaCommandPublisher publisher = new OutboxSagaCommandPublisher(
			outboxRepository,
			objectMapper,
			clock
		);

		@Test
		void shouldStoreTheCommandInTheOutboxRoutedToItsParticipant() {
			UUID sagaId = UUID.randomUUID();
			UUID serviceOrderId = UUID.randomUUID();

			publisher.send(
				SagaParticipant.BILLING,
				sagaId,
				serviceOrderId,
				SagaMessageTypes.BILLING_PAYMENT_CREATE,
				Map.of("amount", 10)
			);
			publisher.send(
				SagaParticipant.EXECUTION,
				sagaId,
				serviceOrderId,
				SagaMessageTypes.EXECUTION_REPAIR_START,
				Map.of()
			);

			ArgumentCaptor<OutboxMessageJpaEntity> captor = ArgumentCaptor.forClass(OutboxMessageJpaEntity.class);
			verify(outboxRepository, times(2)).save(captor.capture());

			OutboxMessageJpaEntity billing = captor.getAllValues().get(0);
			assertThat(billing.getExchange()).isEqualTo(RabbitTopologyConfig.COMMANDS_EXCHANGE);
			assertThat(billing.getRoutingKey()).isEqualTo(RabbitTopologyConfig.BILLING_ROUTING_KEY);
			assertThat(billing.getMessageType()).isEqualTo(SagaMessageTypes.BILLING_PAYMENT_CREATE);
			assertThat(billing.getCreatedAt()).isEqualTo(NOW);
			assertThat(billing.getPublishedAt()).isNull();

			SagaMessage envelope = objectMapper.readValue(billing.getPayload(), SagaMessage.class);
			assertThat(envelope.messageId()).isEqualTo(billing.getId());
			assertThat(envelope.sagaId()).isEqualTo(sagaId);
			assertThat(envelope.serviceOrderId()).isEqualTo(serviceOrderId);
			assertThat(envelope.payload().get("amount").asInt()).isEqualTo(10);

			assertThat(captor.getAllValues().get(1).getRoutingKey()).isEqualTo(RabbitTopologyConfig.EXECUTION_ROUTING_KEY);
		}
	}

	@Nested
	class Relay {

		private final OutboxMessageJpaRepository outboxRepository = mock(OutboxMessageJpaRepository.class);
		private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		private final OutboxRelay relay = new OutboxRelay(outboxRepository, rabbitTemplate, clock);

		private OutboxMessageJpaEntity pending(String routingKey) {
			OutboxMessageJpaEntity outbox = new OutboxMessageJpaEntity();
			outbox.setId(UUID.randomUUID());
			outbox.setExchange(RabbitTopologyConfig.COMMANDS_EXCHANGE);
			outbox.setRoutingKey(routingKey);
			outbox.setMessageType("billing.budget.generate");
			outbox.setPayload("{\"type\":\"billing.budget.generate\"}");
			outbox.setCreatedAt(NOW);
			return outbox;
		}

		@Test
		void shouldSendPendingMessagesAsPersistentAndMarkThemPublished() {
			ReflectionTestUtils.setField(relay, "batchSize", 50);
			OutboxMessageJpaEntity outbox = pending("billing");
			when(outboxRepository.lockPendingBatch(any(Pageable.class))).thenReturn(List.of(outbox));

			relay.relayPending();

			ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
			verify(rabbitTemplate).send(eq(RabbitTopologyConfig.COMMANDS_EXCHANGE), eq("billing"), captor.capture());
			Message sent = captor.getValue();
			assertThat(new String(sent.getBody(), StandardCharsets.UTF_8)).isEqualTo(outbox.getPayload());
			assertThat(sent.getMessageProperties().getMessageId()).isEqualTo(outbox.getId().toString());
			assertThat(sent.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
			assertThat(outbox.getPublishedAt()).isEqualTo(NOW);
		}

		@Test
		void shouldKeepMessagesPendingWhenTheBrokerIsDown() {
			ReflectionTestUtils.setField(relay, "batchSize", 50);
			OutboxMessageJpaEntity first = pending("billing");
			OutboxMessageJpaEntity second = pending("execution");
			when(outboxRepository.lockPendingBatch(any(Pageable.class))).thenReturn(List.of(first, second));
			doThrow(new AmqpConnectException(new RuntimeException("down")))
				.when(rabbitTemplate)
				.send(any(String.class), any(String.class), any(Message.class));

			relay.relayPending();

			assertThat(first.getPublishedAt()).isNull();
			assertThat(second.getPublishedAt()).isNull();
			verify(rabbitTemplate, times(1)).send(any(String.class), any(String.class), any(Message.class));
		}
	}

	@Nested
	class ReplyListener {

		private final ServiceOrderSagaOrchestrator orchestrator = mock(ServiceOrderSagaOrchestrator.class);
		private final ObservabilityReporter reporter = mock(ObservabilityReporter.class);
		private final SagaReplyListener listener = new SagaReplyListener(orchestrator, objectMapper, reporter);

		private Message amqp(SagaMessage message) {
			return MessageBuilder.withBody(objectMapper.writeValueAsBytes(message)).build();
		}

		private SagaMessage reply(String correlationId) {
			return new SagaMessage(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				SagaMessageTypes.BILLING_BUDGET_SENT,
				NOW,
				correlationId,
				objectMapper.valueToTree(Map.of("budgetId", "b-1"))
			);
		}

		@Test
		void shouldDeserializeTheEnvelopeAndDelegateToTheOrchestrator() {
			SagaMessage reply = reply("corr-1");

			listener.onReply(amqp(reply));

			ArgumentCaptor<SagaMessage> captor = ArgumentCaptor.forClass(SagaMessage.class);
			verify(orchestrator).handleReply(captor.capture());
			assertThat(captor.getValue().messageId()).isEqualTo(reply.messageId());
			assertThat(captor.getValue().type()).isEqualTo(SagaMessageTypes.BILLING_BUDGET_SENT);
			assertThat(captor.getValue().payload().get("budgetId").asString()).isEqualTo("b-1");
			verify(reporter, never()).recordSagaFailure(any(), any(), any(), any());
		}

		@Test
		void shouldReportAndRethrowSoTheBrokerRetriesAndDeadLetters() {
			SagaMessage reply = reply(null);
			IllegalStateException failure = new IllegalStateException("boom");
			doThrow(failure).when(orchestrator).handleReply(any());

			assertThatThrownBy(() -> listener.onReply(amqp(reply))).isSameAs(failure);

			verify(reporter).recordSagaFailure(reply.type(), reply.sagaId(), reply.serviceOrderId(), failure);
		}
	}

	@Nested
	class TimeoutScheduler {

		private final SagaInstanceRepositoryPort sagaRepository = mock(SagaInstanceRepositoryPort.class);
		private final ServiceOrderSagaOrchestrator orchestrator = mock(ServiceOrderSagaOrchestrator.class);
		private final SagaTimeoutScheduler scheduler = new SagaTimeoutScheduler(sagaRepository, orchestrator, clock);

		@Test
		void shouldHandEveryExpiredSagaToTheOrchestratorEvenWhenOneFails() {
			UUID failing = UUID.randomUUID();
			UUID healthy = UUID.randomUUID();
			when(sagaRepository.findExpiredIds(NOW)).thenReturn(List.of(failing, healthy));
			doThrow(new IllegalStateException("boom")).when(orchestrator).handleTimeout(failing);

			scheduler.compensateExpiredSagas();

			verify(orchestrator).handleTimeout(failing);
			verify(orchestrator).handleTimeout(healthy);
		}
	}

	@Nested
	class ProcessedMessages {

		private final ProcessedMessageJpaRepository repository = mock(ProcessedMessageJpaRepository.class);
		private final ProcessedMessageAdapter adapter = new ProcessedMessageAdapter(repository, clock);

		@Test
		void shouldRegisterAMessageSeenForTheFirstTime() {
			UUID messageId = UUID.randomUUID();
			when(repository.existsById(messageId)).thenReturn(false);

			assertThat(adapter.registerIfNew(messageId, "billing.budget.sent")).isTrue();

			ArgumentCaptor<ProcessedMessageJpaEntity> captor = ArgumentCaptor.forClass(ProcessedMessageJpaEntity.class);
			verify(repository).saveAndFlush(captor.capture());
			assertThat(captor.getValue().getMessageId()).isEqualTo(messageId);
			assertThat(captor.getValue().getProcessedAt()).isEqualTo(NOW);
		}

		@Test
		void shouldRefuseARedeliveredMessage() {
			UUID messageId = UUID.randomUUID();
			when(repository.existsById(messageId)).thenReturn(true);

			assertThat(adapter.registerIfNew(messageId, "billing.budget.sent")).isFalse();

			verify(repository, never()).saveAndFlush(any());
		}
	}
}
