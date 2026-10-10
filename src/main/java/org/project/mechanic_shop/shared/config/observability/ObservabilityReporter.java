package org.project.mechanic_shop.shared.config.observability;

import com.newrelic.api.agent.NewRelic;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.domain.entities.saga.SagaLogEntry;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Single entry point for reporting failures to New Relic.
 *
 * <p>Reporting telemetry must never change the outcome of the caller, so both methods swallow their
 * own exceptions and only log a warning when the agent is unavailable.
 */
@Component
@Slf4j
public class ObservabilityReporter {

	/**
	 * Reports a failure while processing a service order. Feeds the "service order processing
	 * failures" alert condition.
	 */
	public void recordServiceOrderFailure(String operation, Throwable throwable) {
		Map<String, Object> attributes = baseAttributes();
		attributes.put("operation", operation);
		attributes.put("errorClass", throwable.getClass().getName());
		attributes.put("errorMessage", String.valueOf(throwable.getMessage()));

		record(ObservabilityEvents.SERVICE_ORDER_FAILURE, attributes, throwable);
	}

	/**
	 * Reports a failure calling an external dependency (SMTP, HTTP, …). Feeds the "integration
	 * errors" dashboard widget.
	 */
	public void recordIntegrationFailure(String integration, String operation, Throwable throwable) {
		Map<String, Object> attributes = baseAttributes();
		attributes.put("integration", integration);
		attributes.put("operation", operation);
		attributes.put("errorClass", throwable.getClass().getName());
		attributes.put("errorMessage", String.valueOf(throwable.getMessage()));

		record(ObservabilityEvents.INTEGRATION_FAILURE, attributes, throwable);
	}

	/**
	 * Reports one step of a service order Saga: which reply moved it and the state it was left in.
	 * Lets a distributed flow be followed, and compensations be counted, in New Relic.
	 */
	public void recordSagaTransition(SagaLogEntry entry, UUID serviceOrderId) {
		try {
			Map<String, Object> attributes = baseAttributes();
			attributes.put("sagaId", String.valueOf(entry.sagaId()));
			attributes.put("serviceOrderId", String.valueOf(serviceOrderId));
			attributes.put("trigger", String.valueOf(entry.trigger()));
			attributes.put("step", entry.step().name());
			attributes.put("status", entry.status().name());
			if (entry.note() != null) attributes.put("note", entry.note());

			NewRelic.getAgent().getInsights().recordCustomEvent(ObservabilityEvents.SAGA_TRANSITION, attributes);
		} catch (Exception e) {
			log.warn("Failed to report saga transition telemetry: {}", e.getMessage());
		}
	}

	/** Reports a failure while handling a Saga reply. Feeds the Saga failure alert condition. */
	public void recordSagaFailure(String messageType, UUID sagaId, UUID serviceOrderId, Throwable throwable) {
		Map<String, Object> attributes = baseAttributes();
		attributes.put("service", "os");
		attributes.put("messageType", String.valueOf(messageType));
		attributes.put("sagaId", String.valueOf(sagaId));
		attributes.put("serviceOrderId", String.valueOf(serviceOrderId));
		attributes.put("errorClass", throwable.getClass().getName());
		attributes.put("errorMessage", String.valueOf(throwable.getMessage()));

		record(ObservabilityEvents.SAGA_FAILURE, attributes, throwable);
	}

	private void record(String eventType, Map<String, Object> attributes, Throwable throwable) {
		try {
			NewRelic.getAgent().getInsights().recordCustomEvent(eventType, attributes);
			NewRelic.noticeError(throwable, attributes);
		} catch (Exception e) {
			log.warn("Failed to report '{}' telemetry: {}", eventType, e.getMessage());
		}
	}

	private Map<String, Object> baseAttributes() {
		Map<String, Object> attributes = new HashMap<>();
		String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
		if (correlationId != null) attributes.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, correlationId);
		return attributes;
	}
}
