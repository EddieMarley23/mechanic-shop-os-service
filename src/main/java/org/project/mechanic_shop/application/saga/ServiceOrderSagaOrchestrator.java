package org.project.mechanic_shop.application.saga;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.application.ports.MechanicServiceRepositoryPort;
import org.project.mechanic_shop.application.ports.ProcessedMessagePort;
import org.project.mechanic_shop.application.ports.SagaCommandPublisher;
import org.project.mechanic_shop.application.ports.SagaInstanceRepositoryPort;
import org.project.mechanic_shop.application.ports.ServiceOrderRepositoryPort;
import org.project.mechanic_shop.application.ports.StockItemRepositoryPort;
import org.project.mechanic_shop.application.services.StockItemService;
import org.project.mechanic_shop.domain.entities.mechanic_service.MechanicService;
import org.project.mechanic_shop.domain.entities.saga.SagaInstance;
import org.project.mechanic_shop.domain.entities.saga.SagaLogEntry;
import org.project.mechanic_shop.domain.entities.service_order.ServiceOrder;
import org.project.mechanic_shop.domain.entities.service_order_labor.ServiceOrderLabor;
import org.project.mechanic_shop.domain.entities.service_order_stock_item.ServiceOrderStockItem;
import org.project.mechanic_shop.domain.entities.stock_item.StockItem;
import org.project.mechanic_shop.domain.entities.user.User;
import org.project.mechanic_shop.domain.entities.vehicle.Vehicle;
import org.project.mechanic_shop.domain.enums.SagaStatusEnum;
import org.project.mechanic_shop.domain.enums.SagaStepEnum;
import org.project.mechanic_shop.domain.enums.ServiceOrderStatusEnum;
import org.project.mechanic_shop.domain.events.ServiceOrderStatusChangedEvent;
import org.project.mechanic_shop.shared.config.observability.ObservabilityReporter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Orchestrator of the service order Saga.
 *
 * <p>Happy path: open order → diagnosis (Execution) → budget (Billing) → customer approval
 * (Billing) → stock reservation (local) → payment (Billing) → repair (Execution).
 *
 * <p>The orchestrator is the only place that knows this sequence. It reacts to one reply at a
 * time: it loads the Saga with a row lock, checks that the reply belongs to the step the Saga is
 * waiting at, changes the order, writes the next command to the outbox and commits — all in one
 * local transaction. Replies that arrive late, twice or out of order are recognized and ignored.
 *
 * <p>When a step fails, {@link #compensate} undoes what the previous steps did, in reverse order:
 * refund the payment, return the reserved stock, void the budget and cancel the shop-floor work.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceOrderSagaOrchestrator {

	private final SagaInstanceRepositoryPort sagaRepository;
	private final SagaCommandPublisher commandPublisher;
	private final ProcessedMessagePort processedMessages;
	private final ServiceOrderRepositoryPort serviceOrderRepository;
	private final MechanicServiceRepositoryPort mechanicServiceRepository;
	private final StockItemRepositoryPort stockItemRepository;
	private final StockItemService stockItemService;
	private final ApplicationEventPublisher eventPublisher;
	private final ObservabilityReporter observabilityReporter;
	private final SagaProperties properties;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	private static final String TYPE_LABOR = "LABOR";
	private static final String TYPE_PART = "PART";
	private static final int EXTRA_DAYS_ON_PART_SHORTAGE = 3;
	private static final double WORKING_MINUTES_PER_DAY = 480.0;

	/**
	 * Starts the Saga of a newly opened order. Must run inside the transaction that creates the
	 * order, so an order never exists without its Saga.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public SagaInstance start(ServiceOrder order) {
		SagaInstance saga = sagaRepository.save(SagaInstance.start(order.getExternalId(), Instant.now(clock)));

		Vehicle vehicle = order.getVehicle();
		User owner = vehicle.getOwner();
		User mechanic = order.getResponsibleMechanic();

		commandPublisher.send(
			SagaParticipant.EXECUTION,
			saga.getId(),
			order.getExternalId(),
			SagaMessageTypes.EXECUTION_DIAGNOSIS_START,
			new SagaPayloads.StartDiagnosis(
				vehicle.getLicensePlate(),
				vehicle.getBrand(),
				vehicle.getModel(),
				vehicle.getYear(),
				owner != null ? owner.getName() : null,
				order.getCustomerComplaint(),
				order.getOdometerReading(),
				mechanic != null ? mechanic.getEmail() : null
			)
		);

		record(saga, "service-order.opened", "Diagnosis requested to the Execution Service.");
		return saga;
	}

	/** Processes one reply from a participant. Idempotent: a redelivered reply changes nothing. */
	@Transactional
	public void handleReply(SagaMessage reply) {
		if (!processedMessages.registerIfNew(reply.messageId(), reply.type())) {
			log.info("Duplicated message {} ({}) ignored.", reply.messageId(), reply.type());
			return;
		}

		Optional<SagaInstance> found = sagaRepository.findWithLockById(reply.sagaId());
		if (found.isEmpty()) {
			log.warn("Reply {} refers to unknown Saga {}. Ignored.", reply.type(), reply.sagaId());
			return;
		}

		SagaInstance saga = found.get();
		ServiceOrder order = loadOrder(saga);

		log.info("Saga {} at {}/{} received {}", saga.getId(), saga.getStep(), saga.getStatus(), reply.type());

		switch (reply.type()) {
			case SagaMessageTypes.EXECUTION_DIAGNOSIS_STARTED -> onDiagnosisStarted(saga, order, reply);
			case SagaMessageTypes.EXECUTION_DIAGNOSIS_COMPLETED -> onDiagnosisCompleted(saga, order, reply);
			case SagaMessageTypes.BILLING_BUDGET_SENT -> onBudgetSent(saga, order, reply);
			case SagaMessageTypes.BILLING_BUDGET_FAILED -> onBudgetFailed(saga, order, reply);
			case SagaMessageTypes.BILLING_BUDGET_APPROVED -> onBudgetApproved(saga, order, reply);
			case SagaMessageTypes.BILLING_BUDGET_REJECTED -> onBudgetRejected(saga, order, reply);
			case SagaMessageTypes.BILLING_PAYMENT_CONFIRMED -> onPaymentConfirmed(saga, order, reply);
			case SagaMessageTypes.BILLING_PAYMENT_FAILED -> onPaymentFailed(saga, order, reply);
			case SagaMessageTypes.EXECUTION_REPAIR_COMPLETED -> onRepairCompleted(saga, order, reply);
			case SagaMessageTypes.EXECUTION_REPAIR_FAILED -> onRepairFailed(saga, order, reply);
			case SagaMessageTypes.BILLING_PAYMENT_REFUNDED -> onPaymentRefunded(saga, reply);
			case SagaMessageTypes.BILLING_PAYMENT_CREATED, SagaMessageTypes.EXECUTION_REPAIR_STARTED -> record(
				saga,
				reply.type(),
				null
			);
			default -> log.warn("Unknown reply type '{}' ignored.", reply.type());
		}
	}

	/** Compensates a Saga whose current step passed its deadline. Called by the timeout scheduler. */
	@Transactional
	public void handleTimeout(UUID sagaId) {
		Optional<SagaInstance> found = sagaRepository.findWithLockById(sagaId);
		if (found.isEmpty()) return;

		SagaInstance saga = found.get();
		Instant deadline = saga.getStepDeadline();
		boolean expired =
			saga.getStatus() == SagaStatusEnum.RUNNING && deadline != null && deadline.isBefore(Instant.now(clock));

		// The reply may have arrived between the scheduler's query and this lock.
		if (!expired) return;

		String reason = "Step " + saga.getStep() + " timed out.";
		log.warn("Saga {} of Service Order {}: {}", saga.getId(), saga.getServiceOrderId(), reason);

		compensate(saga, loadOrder(saga), "saga.timeout", reason);
	}

	// ---------------------------------------------------------------------------------------------
	// Step handlers
	// ---------------------------------------------------------------------------------------------

	private void onDiagnosisStarted(SagaInstance saga, ServiceOrder order, SagaMessage reply) {
		if (!saga.isWaitingAt(SagaStepEnum.DIAGNOSIS)) {
			ignoreStale(saga, reply);
			return;
		}

		if (order.getStatus() == ServiceOrderStatusEnum.RECEIVED) {
			changeStatus(order, ServiceOrderStatusEnum.DIAGNOSIS);
		}
		record(saga, reply.type(), null);
	}

	private void onDiagnosisCompleted(SagaInstance saga, ServiceOrder order, SagaMessage reply) {
		if (!saga.isWaitingAt(SagaStepEnum.DIAGNOSIS)) {
			ignoreStale(saga, reply);
			return;
		}

		SagaPayloads.DiagnosisCompleted diagnosis = objectMapper.treeToValue(
			reply.payload(),
			SagaPayloads.DiagnosisCompleted.class
		);

		List<ServiceOrderLabor> labors = new ArrayList<>();
		List<ServiceOrderStockItem> parts = new ArrayList<>();
		List<SagaPayloads.GenerateBudget.BudgetItem> budgetItems = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;

		for (SagaPayloads.DiagnosisCompleted.Labor requested : nullSafe(diagnosis.labors())) {
			Optional<MechanicService> service = mechanicServiceRepository.findByExternalId(
				requested.mechanicServiceExternalId()
			);
			if (service.isEmpty()) {
				reopenDiagnosis(saga, order, reply, "Service not found in the catalog: " + requested.mechanicServiceExternalId());
				return;
			}

			BigDecimal unitPrice = service.get().getPrice();
			BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(requested.quantity()));

			ServiceOrderLabor labor = new ServiceOrderLabor();
			labor.setMechanicService(service.get());
			labor.setQuantity(requested.quantity());
			labor.setUnitPrice(unitPrice);
			labor.setTotalPrice(totalPrice);
			labors.add(labor);

			budgetItems.add(
				new SagaPayloads.GenerateBudget.BudgetItem(
					TYPE_LABOR,
					service.get().getExternalId(),
					service.get().getName(),
					requested.quantity(),
					unitPrice,
					totalPrice
				)
			);
			total = total.add(totalPrice);
		}

		for (SagaPayloads.DiagnosisCompleted.Part requested : nullSafe(diagnosis.parts())) {
			Optional<StockItem> stockItem = stockItemRepository.findByExternalId(requested.partExternalId());
			if (stockItem.isEmpty()) {
				reopenDiagnosis(saga, order, reply, "Part not found in stock: " + requested.partExternalId());
				return;
			}

			BigDecimal unitPrice = stockItem.get().getSalePrice();
			BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(requested.quantity()));

			ServiceOrderStockItem part = new ServiceOrderStockItem();
			part.setStockItem(stockItem.get());
			part.setStockItemType(stockItem.get().getType());
			part.setQuantity(requested.quantity());
			part.setUnitPrice(unitPrice);
			part.setTotalPrice(totalPrice);
			parts.add(part);

			budgetItems.add(
				new SagaPayloads.GenerateBudget.BudgetItem(
					TYPE_PART,
					stockItem.get().getExternalId(),
					stockItem.get().getName(),
					requested.quantity(),
					unitPrice,
					totalPrice
				)
			);
			total = total.add(totalPrice);
		}

		if (budgetItems.isEmpty()) {
			reopenDiagnosis(saga, order, reply, "The diagnosis lists no service or part.");
			return;
		}

		order.setMechanicDiagnosis(diagnosis.diagnosis());
		order.getLabors().clear();
		order.getLabors().addAll(labors);
		order.getStockItems().clear();
		order.getStockItems().addAll(parts);
		order.setBudgetTotalAmount(total);
		setEstimatedDeadline(order, calculateBaseDays(order));

		// The "diagnosis started" reply normally comes first, but ordering is not guaranteed.
		ServiceOrder saved = order.getStatus() == ServiceOrderStatusEnum.RECEIVED
			? changeStatus(order, ServiceOrderStatusEnum.DIAGNOSIS)
			: serviceOrderRepository.save(order);

		Vehicle vehicle = saved.getVehicle();
		User owner = vehicle.getOwner();

		commandPublisher.send(
			SagaParticipant.BILLING,
			saga.getId(),
			saved.getExternalId(),
			SagaMessageTypes.BILLING_BUDGET_GENERATE,
			new SagaPayloads.GenerateBudget(
				owner != null ? owner.getName() : null,
				owner != null ? owner.getEmail() : null,
				vehicle.getLicensePlate(),
				budgetItems,
				total,
				saved.getEstimatedCompletionDays()
			)
		);

		advance(saga, SagaStepEnum.BUDGET_GENERATION, properties.budgetGenerationTimeout());
		record(saga, reply.type(), "Items priced. Budget of R$ " + total + " requested to the Billing Service.");
	}

	private void onBudgetSent(SagaInstance saga, ServiceOrder order, SagaMessage reply) {
		if (!saga.isWaitingAt(SagaStepEnum.BUDGET_GENERATION)) {
			ignoreStale(saga, reply);
			return;
		}

		changeStatus(order, ServiceOrderStatusEnum.PENDING_APPROVAL);
		advance(saga, SagaStepEnum.AWAITING_APPROVAL, properties.approvalTimeout());
		record(saga, reply.type(), "Budget sent to the customer.");
	}

	private void onBudgetFailed(SagaInstance saga, ServiceOrder order, SagaMessage reply) {
		if (!saga.isWaitingAt(SagaStepEnum.BUDGET_GENERATION)) {
			ignoreStale(saga, reply);
			return;
		}

		advance(saga, SagaStepEnum.DIAGNOSIS, null);
		reopenDiagnosis(saga, order, reply, "Budget refused by the Billing Service: " + reasonOf(reply));
	}

	private void onBudgetApproved(SagaInstance saga, ServiceOrder order, SagaMessage reply) {
		if (!saga.isWaitingAt(SagaStepEnum.AWAITING_APPROVAL)) {
			ignoreStale(saga, reply);
			return;
		}

		// Checked up front, through the repository port, so that a missing part leads to a clean
		// compensation instead of an exception that would poison this transaction.
		for (ServiceOrderStockItem item : order.getStockItems()) {
			UUID partId = item.getStockItem().getExternalId();
			if (stockItemRepository.findByExternalId(partId).isEmpty()) {
				compensate(saga, order, reply.type(), "Stock could not be reserved: part " + partId + " no longer exists.");
				return;
			}
		}

		boolean anyPartMissing = false;
		for (ServiceOrderStockItem item : order.getStockItems()) {
			boolean isShortage = stockItemService.withdrawStock(item.getStockItem().getExternalId(), item.getQuantity());
			anyPartMissing = anyPartMissing || isShortage;
		}
		saga.setStockReserved(true);

		if (anyPartMissing) {
			setEstimatedDeadline(order, calculateBaseDays(order) + EXTRA_DAYS_ON_PART_SHORTAGE);
			log.info("Estimated deadline extended by 3 days: a part shortage was found while withdrawing stock.");
		}

		order.setApprovalDate(LocalDateTime.now(ZoneId.systemDefault()));
		changeStatus(order, ServiceOrderStatusEnum.PENDING_PAYMENT);

		commandPublisher.send(
			SagaParticipant.BILLING,
			saga.getId(),
			order.getExternalId(),
			SagaMessageTypes.BILLING_PAYMENT_CREATE,
			Map.of()
		);

		advance(saga, SagaStepEnum.AWAITING_PAYMENT, properties.paymentTimeout());
		record(saga, reply.type(), "Budget approved. Stock reserved and payment requested to the Billing Service.");
	}

	private void onBudgetRejected(SagaInstance saga, ServiceOrder order, SagaMessage reply) {
		if (!saga.isWaitingAt(SagaStepEnum.AWAITING_APPROVAL)) {
			ignoreStale(saga, reply);
			return;
		}

		String reason = "Budget rejected by the customer.";

		// Nothing was reserved or charged yet: only the shop-floor work order has to be closed.
		commandPublisher.send(
			SagaParticipant.EXECUTION,
			saga.getId(),
			order.getExternalId(),
			SagaMessageTypes.EXECUTION_WORK_ORDER_CANCEL,
			new SagaPayloads.Reason(reason)
		);

		changeStatus(order, ServiceOrderStatusEnum.CANCELED);
		saga.finish(SagaStatusEnum.CANCELED, reason, Instant.now(clock));
		sagaRepository.save(saga);
		record(saga, reply.type(), reason);
	}

	private void onPaymentConfirmed(SagaInstance saga, ServiceOrder order, SagaMessage reply) {
		if (!saga.isWaitingAt(SagaStepEnum.AWAITING_PAYMENT)) {
			ignoreStale(saga, reply);
			return;
		}

		ServiceOrder saved = changeStatus(order, ServiceOrderStatusEnum.IN_PROGRESS);

		commandPublisher.send(
			SagaParticipant.EXECUTION,
			saga.getId(),
			saved.getExternalId(),
			SagaMessageTypes.EXECUTION_REPAIR_START,
			new SagaPayloads.StartRepair(saved.getEstimatedCompletionDays())
		);

		advance(saga, SagaStepEnum.EXECUTION, null);
		record(saga, reply.type(), "Payment confirmed. Repair requested to the Execution Service.");
	}

	private void onPaymentFailed(SagaInstance saga, ServiceOrder order, SagaMessage reply) {
		if (!saga.isWaitingAt(SagaStepEnum.AWAITING_PAYMENT)) {
			ignoreStale(saga, reply);
			return;
		}

		compensate(saga, order, reply.type(), "Payment failed: " + reasonOf(reply));
	}

	private void onRepairCompleted(SagaInstance saga, ServiceOrder order, SagaMessage reply) {
		if (!saga.isWaitingAt(SagaStepEnum.EXECUTION)) {
			ignoreStale(saga, reply);
			return;
		}

		recordActualFinish(order);
		changeStatus(order, ServiceOrderStatusEnum.COMPLETED);

		saga.finish(SagaStatusEnum.COMPLETED, null, Instant.now(clock));
		sagaRepository.save(saga);
		record(saga, reply.type(), "Repair finished. Saga completed.");
	}

	private void onRepairFailed(SagaInstance saga, ServiceOrder order, SagaMessage reply) {
		if (!saga.isWaitingAt(SagaStepEnum.EXECUTION)) {
			ignoreStale(saga, reply);
			return;
		}

		compensate(saga, order, reply.type(), "Repair failed: " + reasonOf(reply));
	}

	private void onPaymentRefunded(SagaInstance saga, SagaMessage reply) {
		if (saga.getStatus() != SagaStatusEnum.COMPENSATING) {
			ignoreStale(saga, reply);
			return;
		}

		saga.finish(SagaStatusEnum.COMPENSATED, saga.getFailureReason(), Instant.now(clock));
		sagaRepository.save(saga);
		record(saga, reply.type(), "Payment refunded. Compensation finished.");
	}

	// ---------------------------------------------------------------------------------------------
	// Compensation
	// ---------------------------------------------------------------------------------------------

	/**
	 * Undoes, in reverse order, everything the Saga did up to the step it is waiting at, and cancels
	 * the order. Every compensation is idempotent on the participant's side.
	 */
	private void compensate(SagaInstance saga, ServiceOrder order, String trigger, String reason) {
		SagaStepEnum failedAt = saga.getStep();
		boolean paid = failedAt == SagaStepEnum.EXECUTION;
		boolean budgetExists = failedAt.ordinal() >= SagaStepEnum.BUDGET_GENERATION.ordinal();
		SagaPayloads.Reason payload = new SagaPayloads.Reason(reason);

		if (paid) {
			commandPublisher.send(
				SagaParticipant.BILLING,
				saga.getId(),
				order.getExternalId(),
				SagaMessageTypes.BILLING_PAYMENT_REFUND,
				payload
			);
		}

		if (saga.isStockReserved()) {
			for (ServiceOrderStockItem item : order.getStockItems()) {
				stockItemService.restoreStock(item.getStockItem().getExternalId(), item.getQuantity());
			}
			saga.setStockReserved(false);
		}

		if (budgetExists) {
			commandPublisher.send(
				SagaParticipant.BILLING,
				saga.getId(),
				order.getExternalId(),
				SagaMessageTypes.BILLING_BUDGET_CANCEL,
				payload
			);
		}

		commandPublisher.send(
			SagaParticipant.EXECUTION,
			saga.getId(),
			order.getExternalId(),
			SagaMessageTypes.EXECUTION_WORK_ORDER_CANCEL,
			payload
		);

		changeStatus(order, ServiceOrderStatusEnum.CANCELED);

		Instant now = Instant.now(clock);
		if (paid) {
			// The refund is confirmed asynchronously by the Billing Service (billing.payment.refunded).
			saga.startCompensation(reason, now);
		} else {
			saga.finish(SagaStatusEnum.COMPENSATED, reason, now);
		}
		sagaRepository.save(saga);

		record(saga, trigger, "Compensated at step " + failedAt + ". " + reason);
		log.warn("Saga {} of Service Order {} compensated at {}: {}", saga.getId(), order.getExternalId(), failedAt, reason);
	}

	/** The diagnosis cannot be priced: sends it back to the mechanic instead of failing the Saga. */
	private void reopenDiagnosis(SagaInstance saga, ServiceOrder order, SagaMessage reply, String reason) {
		commandPublisher.send(
			SagaParticipant.EXECUTION,
			saga.getId(),
			order.getExternalId(),
			SagaMessageTypes.EXECUTION_DIAGNOSIS_REOPEN,
			new SagaPayloads.Reason(reason)
		);
		record(saga, reply.type(), "Diagnosis sent back to the mechanic. " + reason);
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	private ServiceOrder loadOrder(SagaInstance saga) {
		return serviceOrderRepository
			.findByExternalId(saga.getServiceOrderId())
			.orElseThrow(() ->
				new IllegalStateException("Saga " + saga.getId() + " refers to a missing Service Order.")
			);
	}

	private ServiceOrder changeStatus(ServiceOrder order, ServiceOrderStatusEnum newStatus) {
		ServiceOrderStatusEnum oldStatus = order.getStatus();
		order.setStatus(newStatus);

		ServiceOrder saved = serviceOrderRepository.save(order);
		eventPublisher.publishEvent(new ServiceOrderStatusChangedEvent(saved, oldStatus, newStatus));

		log.info("Service Order {} status: {} -> {}", saved.getExternalId(), oldStatus, newStatus);
		return saved;
	}

	private void advance(SagaInstance saga, SagaStepEnum next, Duration timeout) {
		Instant now = Instant.now(clock);
		saga.advanceTo(next, timeout != null ? now.plus(timeout) : null, now);
		sagaRepository.save(saga);
	}

	private void record(SagaInstance saga, String trigger, String note) {
		SagaLogEntry entry = new SagaLogEntry(
			saga.getId(),
			Instant.now(clock),
			trigger,
			saga.getStep(),
			saga.getStatus(),
			note
		);
		sagaRepository.appendLog(entry);
		observabilityReporter.recordSagaTransition(entry, saga.getServiceOrderId());
	}

	private void ignoreStale(SagaInstance saga, SagaMessage reply) {
		log.warn(
			"Saga {} is at {}/{} and does not expect {}. Reply ignored.",
			saga.getId(),
			saga.getStep(),
			saga.getStatus(),
			reply.type()
		);
	}

	private String reasonOf(SagaMessage reply) {
		JsonNode payload = reply.payload();
		if (payload == null || payload.isNull()) return "no reason given";

		String reason = objectMapper.treeToValue(payload, SagaPayloads.Reason.class).reason();
		return reason != null ? reason : "no reason given";
	}

	private <T> List<T> nullSafe(List<T> list) {
		return list != null ? list : List.of();
	}

	private void setEstimatedDeadline(ServiceOrder order, int days) {
		order.setEstimatedCompletionDays(days);
		order.setEstimatedCompletionDate(LocalDateTime.now(ZoneId.systemDefault()).plusDays(days));
	}

	private int calculateBaseDays(ServiceOrder order) {
		long totalServiceMinutes = order
			.getLabors()
			.stream()
			.mapToLong(l -> (long) l.getMechanicService().getEstimatedTimeMinutes() * l.getQuantity())
			.sum();

		return (int) Math.ceil(totalServiceMinutes / WORKING_MINUTES_PER_DAY);
	}

	private void recordActualFinish(ServiceOrder order) {
		LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
		order.setActualCompletionDate(now);

		if (order.getApprovalDate() != null) {
			long daysTaken = ChronoUnit.DAYS.between(
				order.getApprovalDate().atZone(ZoneId.systemDefault()),
				now.atZone(ZoneId.systemDefault())
			);
			order.setActualCompletionDays((int) daysTaken);
		}
	}
}
