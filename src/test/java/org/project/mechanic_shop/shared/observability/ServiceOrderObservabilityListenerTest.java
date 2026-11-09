package org.project.mechanic_shop.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.domain.entities.service_order.ServiceOrder;
import org.project.mechanic_shop.domain.enums.ServiceOrderStatusEnum;
import org.project.mechanic_shop.domain.events.NewServiceOrderEvent;
import org.project.mechanic_shop.domain.events.ServiceOrderStatusChangedEvent;
import org.project.mechanic_shop.shared.config.observability.CorrelationIdFilter;
import org.project.mechanic_shop.shared.config.observability.ServiceOrderObservabilityListener;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Without the New Relic Java agent attached, {@code NewRelic.getAgent()} is a no-op, so the custom
 * events cannot be inspected here. The listener writes the same data to a structured log record,
 * which is what these tests assert on.
 */
class ServiceOrderObservabilityListenerTest {

	private static final String ORDER_ID = "00000000-0000-0000-0000-000000000001";

	private final ServiceOrderObservabilityListener listener = new ServiceOrderObservabilityListener();
	private final Logger logger = (Logger) LoggerFactory.getLogger(ServiceOrderObservabilityListener.class);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void attachAppender() {
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		logger.detachAppender(appender);
		MDC.clear();
	}

	private ILoggingEvent onlyLogRecord() {
		assertThat(appender.list).hasSize(1);
		return appender.list.get(0);
	}

	private static ServiceOrder order(LocalDateTime createdAt, LocalDateTime approvedAt, LocalDateTime completedAt) {
		var order = new ServiceOrder();
		order.setExternalId(UUID.fromString(ORDER_ID));
		order.setCreatedAt(createdAt);
		order.setApprovalDate(approvedAt);
		order.setActualCompletionDate(completedAt);
		return order;
	}

	@Nested
	class OnServiceOrderOpened {

		@Test
		void logsTheOpenedOrderWithItsStatus() {
			MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, "corr-1");
			var order = order(LocalDateTime.now().minusMinutes(1), null, null);
			order.setStatus(ServiceOrderStatusEnum.RECEIVED);

			listener.onServiceOrderOpened(new NewServiceOrderEvent(order));

			var record = onlyLogRecord();
			assertThat(record.getLevel()).isEqualTo(Level.INFO);
			assertThat(record.getFormattedMessage())
				.isEqualTo("service_order.opened serviceOrderId=" + ORDER_ID + " status=RECEIVED");
		}

		@Test
		void acceptsAnOrderWithoutStatusOrCreationDate() {
			var order = order(null, null, null);
			order.setStatus(null);

			listener.onServiceOrderOpened(new NewServiceOrderEvent(order));

			assertThat(onlyLogRecord().getFormattedMessage()).endsWith("status=null");
		}

		@Test
		void neverPropagatesTelemetryFailures() {
			assertThatCode(() -> listener.onServiceOrderOpened(new NewServiceOrderEvent(null))).doesNotThrowAnyException();

			var record = onlyLogRecord();
			assertThat(record.getLevel()).isEqualTo(Level.WARN);
			assertThat(record.getFormattedMessage()).startsWith("Failed to report service order opened telemetry");
		}
	}

	@Nested
	class OnStatusChanged {

		private final LocalDateTime createdAt = LocalDateTime.now().minusHours(3);
		private final LocalDateTime approvedAt = createdAt.plusHours(1);
		private final LocalDateTime completedAt = approvedAt.plusHours(1);

		private String changeTo(ServiceOrder order, ServiceOrderStatusEnum from, ServiceOrderStatusEnum to) {
			listener.onStatusChanged(new ServiceOrderStatusChangedEvent(order, from, to));
			var record = onlyLogRecord();
			assertThat(record.getLevel()).isEqualTo(Level.INFO);
			return record.getFormattedMessage();
		}

		@Test
		void inProgressClosesTheDiagnosisPhase() {
			var message = changeTo(
				order(createdAt, approvedAt, null),
				ServiceOrderStatusEnum.PENDING_PAYMENT,
				ServiceOrderStatusEnum.IN_PROGRESS
			);

			assertThat(message).isEqualTo(
				"service_order.status_changed serviceOrderId=" +
					ORDER_ID +
					" oldStatus=PENDING_PAYMENT newStatus=IN_PROGRESS phase=DIAGNOSIS phaseDurationSeconds=3600"
			);
		}

		@Test
		void completedClosesTheExecutionPhase() {
			var message = changeTo(
				order(createdAt, approvedAt, completedAt),
				ServiceOrderStatusEnum.IN_PROGRESS,
				ServiceOrderStatusEnum.COMPLETED
			);

			assertThat(message).endsWith("phase=EXECUTION phaseDurationSeconds=3600");
		}

		@Test
		void deliveredClosesTheFinalizationPhase() {
			var message = changeTo(
				order(createdAt, approvedAt, completedAt),
				ServiceOrderStatusEnum.COMPLETED,
				ServiceOrderStatusEnum.DELIVERED
			);

			assertThat(message).contains("phase=FINALIZATION phaseDurationSeconds=").doesNotEndWith("=null");
		}

		@Test
		void otherTransitionsHaveNoPhase() {
			var message = changeTo(
				order(createdAt, null, null),
				ServiceOrderStatusEnum.RECEIVED,
				ServiceOrderStatusEnum.DIAGNOSIS
			);

			assertThat(message).endsWith("newStatus=DIAGNOSIS phase=null phaseDurationSeconds=null");
		}

		@Test
		void missingStatusesAreReportedAsUnknown() {
			var message = changeTo(order(null, null, null), null, null);

			assertThat(message).endsWith("oldStatus=UNKNOWN newStatus=UNKNOWN phase=null phaseDurationSeconds=null");
		}

		@Test
		void phaseWithoutItsTimestampsHasNoDuration() {
			var message = changeTo(
				order(createdAt, null, null),
				ServiceOrderStatusEnum.PENDING_PAYMENT,
				ServiceOrderStatusEnum.IN_PROGRESS
			);

			assertThat(message).endsWith("phase=DIAGNOSIS phaseDurationSeconds=null");
		}

		@Test
		void negativeDurationIsDiscarded() {
			var message = changeTo(
				order(approvedAt, createdAt, null),
				ServiceOrderStatusEnum.PENDING_PAYMENT,
				ServiceOrderStatusEnum.IN_PROGRESS
			);

			assertThat(message).endsWith("phase=DIAGNOSIS phaseDurationSeconds=null");
		}

		@Test
		void neverPropagatesTelemetryFailures() {
			var event = new ServiceOrderStatusChangedEvent(
				null,
				ServiceOrderStatusEnum.PENDING_PAYMENT,
				ServiceOrderStatusEnum.IN_PROGRESS
			);

			assertThatCode(() -> listener.onStatusChanged(event)).doesNotThrowAnyException();

			var record = onlyLogRecord();
			assertThat(record.getLevel()).isEqualTo(Level.WARN);
			assertThat(record.getFormattedMessage()).startsWith("Failed to report service order status telemetry");
		}
	}
}
