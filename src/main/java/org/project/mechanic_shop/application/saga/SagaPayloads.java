package org.project.mechanic_shop.application.saga;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Payloads of the Saga messages. Commands carry a copy of every piece of data the participant
 * needs, so no service ever has to read another service's database or call it synchronously.
 */
public final class SagaPayloads {

	private SagaPayloads() {}

	/** {@link SagaMessageTypes#EXECUTION_DIAGNOSIS_START} */
	public record StartDiagnosis(
		String licensePlate,
		String brand,
		String model,
		Integer year,
		String customerName,
		String customerComplaint,
		Integer odometerReading,
		String mechanicEmail
	) {}

	/** {@link SagaMessageTypes#EXECUTION_DIAGNOSIS_COMPLETED} */
	public record DiagnosisCompleted(String diagnosis, List<Labor> labors, List<Part> parts) {
		public record Labor(UUID mechanicServiceExternalId, Integer quantity) {}

		public record Part(UUID partExternalId, Integer quantity) {}
	}

	/** {@link SagaMessageTypes#BILLING_BUDGET_GENERATE} */
	public record GenerateBudget(
		String customerName,
		String customerEmail,
		String licensePlate,
		List<BudgetItem> items,
		BigDecimal totalAmount,
		Integer estimatedCompletionDays
	) {
		public record BudgetItem(
			String type,
			UUID referenceId,
			String description,
			Integer quantity,
			BigDecimal unitPrice,
			BigDecimal totalPrice
		) {}
	}

	/** {@link SagaMessageTypes#EXECUTION_REPAIR_START} */
	public record StartRepair(Integer estimatedCompletionDays) {}

	/** Compensation commands and failure replies. */
	public record Reason(String reason) {}
}
