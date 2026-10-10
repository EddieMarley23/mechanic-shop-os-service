package org.project.mechanic_shop.application.saga;

/** The services that take part in the Saga. Decides which command queue a message goes to. */
public enum SagaParticipant {
	BILLING,
	EXECUTION,
}
