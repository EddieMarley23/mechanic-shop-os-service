package org.project.mechanic_shop.domain.enums;

import lombok.Getter;

/** Where a service order Saga is waiting. Each step is closed by a reply from a participant. */
@Getter
public enum SagaStepEnum {
	DIAGNOSIS("Diagnosis", "Execution Service is diagnosing the vehicle."),
	BUDGET_GENERATION("Budget generation", "Billing Service is generating the budget."),
	AWAITING_APPROVAL("Awaiting approval", "Customer is deciding on the budget."),
	AWAITING_PAYMENT("Awaiting payment", "Stock reserved. Customer is paying."),
	EXECUTION("Execution", "Execution Service is repairing the vehicle."),
	DONE("Done", "No step left to wait for.");

	private final String label;
	private final String description;

	SagaStepEnum(String label, String description) {
		this.label = label;
		this.description = description;
	}
}
