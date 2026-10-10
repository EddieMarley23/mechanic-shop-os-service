package org.project.mechanic_shop.domain.enums;

import lombok.Getter;

@Getter
public enum SagaStatusEnum {
	RUNNING("Running", "Steps are being executed."),
	COMPLETED("Completed", "Every step succeeded: the repair is finished."),
	CANCELED("Canceled", "The customer rejected the budget. Nothing had to be undone."),
	COMPENSATING("Compensating", "A step failed. Waiting for the participants to undo their work."),
	COMPENSATED("Compensated", "A step failed and everything done before it was undone.");

	private final String label;
	private final String description;

	SagaStatusEnum(String label, String description) {
		this.label = label;
		this.description = description;
	}

	public boolean isFinished() {
		return this == COMPLETED || this == CANCELED || this == COMPENSATED;
	}
}
