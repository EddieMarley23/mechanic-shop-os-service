package org.project.mechanic_shop.application.saga;

/** Every message exchanged by the service order Saga. */
public final class SagaMessageTypes {

	// Commands to the Execution Service
	public static final String EXECUTION_DIAGNOSIS_START = "execution.diagnosis.start";
	public static final String EXECUTION_DIAGNOSIS_REOPEN = "execution.diagnosis.reopen";
	public static final String EXECUTION_REPAIR_START = "execution.repair.start";
	public static final String EXECUTION_WORK_ORDER_CANCEL = "execution.workorder.cancel";

	// Commands to the Billing Service
	public static final String BILLING_BUDGET_GENERATE = "billing.budget.generate";
	public static final String BILLING_BUDGET_CANCEL = "billing.budget.cancel";
	public static final String BILLING_PAYMENT_CREATE = "billing.payment.create";
	public static final String BILLING_PAYMENT_REFUND = "billing.payment.refund";

	// Replies from the Execution Service
	public static final String EXECUTION_DIAGNOSIS_STARTED = "execution.diagnosis.started";
	public static final String EXECUTION_DIAGNOSIS_COMPLETED = "execution.diagnosis.completed";
	public static final String EXECUTION_REPAIR_STARTED = "execution.repair.started";
	public static final String EXECUTION_REPAIR_COMPLETED = "execution.repair.completed";
	public static final String EXECUTION_REPAIR_FAILED = "execution.repair.failed";

	// Replies from the Billing Service
	public static final String BILLING_BUDGET_SENT = "billing.budget.sent";
	public static final String BILLING_BUDGET_FAILED = "billing.budget.failed";
	public static final String BILLING_BUDGET_APPROVED = "billing.budget.approved";
	public static final String BILLING_BUDGET_REJECTED = "billing.budget.rejected";
	public static final String BILLING_PAYMENT_CREATED = "billing.payment.created";
	public static final String BILLING_PAYMENT_CONFIRMED = "billing.payment.confirmed";
	public static final String BILLING_PAYMENT_FAILED = "billing.payment.failed";
	public static final String BILLING_PAYMENT_REFUNDED = "billing.payment.refunded";

	private SagaMessageTypes() {}
}
