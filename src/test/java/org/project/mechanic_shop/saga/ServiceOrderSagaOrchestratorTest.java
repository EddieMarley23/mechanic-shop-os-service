package org.project.mechanic_shop.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.application.saga.SagaMessage;
import org.project.mechanic_shop.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.application.saga.SagaPayloads;
import org.project.mechanic_shop.domain.entities.mechanic_service.MechanicService;
import org.project.mechanic_shop.domain.entities.saga.SagaInstance;
import org.project.mechanic_shop.domain.entities.service_order.ServiceOrder;
import org.project.mechanic_shop.domain.entities.stock_item.StockItem;
import org.project.mechanic_shop.domain.enums.SagaStatusEnum;
import org.project.mechanic_shop.domain.enums.SagaStepEnum;
import org.project.mechanic_shop.domain.enums.ServiceOrderStatusEnum;
import org.project.mechanic_shop.domain.events.ServiceOrderStatusChangedEvent;

/**
 * Edge cases of the orchestrator. The main flows are specified in Gherkin
 * ({@code features/saga_ordem_de_servico.feature}).
 */
class ServiceOrderSagaOrchestratorTest {

	private SagaTestHarness harness;
	private MechanicService labor;
	private StockItem part;
	private ServiceOrder order;

	@BeforeEach
	void setup() {
		harness = new SagaTestHarness();
		labor = harness.addCatalogService("Troca de pastilhas", "180.00", 90);
		part = harness.addStockItem("Pastilha de freio", 10, "120.00");
		order = harness.openOrder("ABC1D23", "cliente@teste.com");
	}

	private void completeDiagnosis(int laborQuantity, int partQuantity) {
		harness.receive(
			order,
			SagaMessageTypes.EXECUTION_DIAGNOSIS_COMPLETED,
			new SagaPayloads.DiagnosisCompleted(
				"Pastilhas desgastadas",
				List.of(new SagaPayloads.DiagnosisCompleted.Labor(labor.getExternalId(), laborQuantity)),
				List.of(new SagaPayloads.DiagnosisCompleted.Part(part.getExternalId(), partQuantity))
			)
		);
	}

	private void reachAwaitingApproval() {
		completeDiagnosis(1, 2);
		harness.receive(order, SagaMessageTypes.BILLING_BUDGET_SENT);
	}

	@Nested
	class Start {

		@Test
		void shouldSendTheOrderDataTheExecutionServiceNeeds() {
			var payload = (SagaPayloads.StartDiagnosis) harness
				.lastCommand(SagaMessageTypes.EXECUTION_DIAGNOSIS_START)
				.payload();

			assertThat(payload.licensePlate()).isEqualTo("ABC1D23");
			assertThat(payload.customerName()).isEqualTo("Cliente Teste");
			assertThat(payload.customerComplaint()).isEqualTo("Ruido ao frear");
			assertThat(payload.mechanicEmail()).isNull();
		}

		@Test
		void shouldStartWaitingForTheDiagnosisWithoutDeadline() {
			SagaInstance saga = harness.sagaOf(order);

			assertThat(saga.getStep()).isEqualTo(SagaStepEnum.DIAGNOSIS);
			assertThat(saga.getStatus()).isEqualTo(SagaStatusEnum.RUNNING);
			assertThat(saga.getStepDeadline()).isNull();
			assertThat(harness.sagaRepository.findLog(saga.getId())).hasSize(1);
		}
	}

	@Nested
	class Diagnosis {

		@Test
		void shouldMoveTheOrderToDiagnosisWhenTheMechanicStarts() {
			harness.receive(order, SagaMessageTypes.EXECUTION_DIAGNOSIS_STARTED);

			assertThat(harness.reload(order).getStatus()).isEqualTo(ServiceOrderStatusEnum.DIAGNOSIS);
			assertThat(harness.publishedEvents).hasAtLeastOneElementOfType(ServiceOrderStatusChangedEvent.class);
		}

		@Test
		void shouldPriceItemsAndEstimateTheDeadline() {
			completeDiagnosis(2, 3);

			ServiceOrder priced = harness.reload(order);
			assertThat(priced.getBudgetTotalAmount()).isEqualByComparingTo("720.00");
			assertThat(priced.getLabors()).hasSize(1);
			assertThat(priced.getStockItems()).hasSize(1);
			assertThat(priced.getMechanicDiagnosis()).isEqualTo("Pastilhas desgastadas");
			// 2 x 90 minutes of labor fit in one working day
			assertThat(priced.getEstimatedCompletionDays()).isEqualTo(1);
			assertThat(priced.getEstimatedCompletionDate()).isNotNull();
		}

		@Test
		void shouldMoveToDiagnosisEvenWhenTheStartedReplyNeverArrived() {
			completeDiagnosis(1, 1);

			assertThat(harness.reload(order).getStatus()).isEqualTo(ServiceOrderStatusEnum.DIAGNOSIS);
		}

		@Test
		void shouldGiveTheBillingServiceADeadlineToAnswer() {
			completeDiagnosis(1, 1);

			SagaInstance saga = harness.sagaOf(order);
			assertThat(saga.getStep()).isEqualTo(SagaStepEnum.BUDGET_GENERATION);
			assertThat(saga.getStepDeadline()).isEqualTo(
				harness.clock.instant().plus(SagaTestHarness.BUDGET_GENERATION_TIMEOUT)
			);
		}

		@Test
		void shouldSendTheDiagnosisBackWhenAServiceIsNotInTheCatalog() {
			harness.receive(
				order,
				SagaMessageTypes.EXECUTION_DIAGNOSIS_COMPLETED,
				new SagaPayloads.DiagnosisCompleted(
					"x",
					List.of(new SagaPayloads.DiagnosisCompleted.Labor(UUID.randomUUID(), 1)),
					List.of()
				)
			);

			var reopen = harness.lastCommand(SagaMessageTypes.EXECUTION_DIAGNOSIS_REOPEN);
			assertThat(((SagaPayloads.Reason) reopen.payload()).reason()).contains("Service not found in the catalog");
			assertThat(harness.sagaOf(order).isWaitingAt(SagaStepEnum.DIAGNOSIS)).isTrue();
			assertThat(harness.sentCommandTypes()).doesNotContain(SagaMessageTypes.BILLING_BUDGET_GENERATE);
		}

		@Test
		void shouldSendTheDiagnosisBackWhenAPartIsNotInStock() {
			harness.receive(
				order,
				SagaMessageTypes.EXECUTION_DIAGNOSIS_COMPLETED,
				new SagaPayloads.DiagnosisCompleted(
					"x",
					List.of(new SagaPayloads.DiagnosisCompleted.Labor(labor.getExternalId(), 1)),
					List.of(new SagaPayloads.DiagnosisCompleted.Part(UUID.randomUUID(), 1))
				)
			);

			var reopen = harness.lastCommand(SagaMessageTypes.EXECUTION_DIAGNOSIS_REOPEN);
			assertThat(((SagaPayloads.Reason) reopen.payload()).reason()).contains("Part not found in stock");
		}

		@Test
		void shouldSendTheDiagnosisBackWhenItListsNothing() {
			harness.receive(
				order,
				SagaMessageTypes.EXECUTION_DIAGNOSIS_COMPLETED,
				new SagaPayloads.DiagnosisCompleted("x", null, null)
			);

			assertThat(harness.sentCommandTypes()).contains(SagaMessageTypes.EXECUTION_DIAGNOSIS_REOPEN);
			assertThat(harness.sagaOf(order).isWaitingAt(SagaStepEnum.DIAGNOSIS)).isTrue();
		}

		@Test
		void shouldReturnToDiagnosisWhenTheBillingServiceRefusesTheBudget() {
			completeDiagnosis(1, 1);

			harness.receive(order, SagaMessageTypes.BILLING_BUDGET_FAILED, new SagaPayloads.Reason("Total mismatch"));

			var reopen = harness.lastCommand(SagaMessageTypes.EXECUTION_DIAGNOSIS_REOPEN);
			assertThat(((SagaPayloads.Reason) reopen.payload()).reason()).contains("Total mismatch");
			SagaInstance saga = harness.sagaOf(order);
			assertThat(saga.isWaitingAt(SagaStepEnum.DIAGNOSIS)).isTrue();
			assertThat(saga.getStepDeadline()).isNull();
		}
	}

	@Nested
	class Approval {

		@Test
		void shouldGiveTheCustomerADeadlineToAnswer() {
			reachAwaitingApproval();

			assertThat(harness.sagaOf(order).getStepDeadline()).isEqualTo(
				harness.clock.instant().plus(SagaTestHarness.APPROVAL_TIMEOUT)
			);
		}

		@Test
		void shouldRecordTheApprovalDateAndGiveAPaymentDeadline() {
			reachAwaitingApproval();

			harness.receive(order, SagaMessageTypes.BILLING_BUDGET_APPROVED);

			assertThat(harness.reload(order).getApprovalDate()).isNotNull();
			SagaInstance saga = harness.sagaOf(order);
			assertThat(saga.isStockReserved()).isTrue();
			assertThat(saga.getStepDeadline()).isEqualTo(harness.clock.instant().plus(SagaTestHarness.PAYMENT_TIMEOUT));
		}

		@Test
		void shouldExtendTheDeadlineWhenTheReservationRevealsAShortage() {
			completeDiagnosis(1, 15);
			harness.receive(order, SagaMessageTypes.BILLING_BUDGET_SENT);

			harness.receive(order, SagaMessageTypes.BILLING_BUDGET_APPROVED);

			// 1 day of labor + 3 days waiting for the missing parts
			assertThat(harness.reload(order).getEstimatedCompletionDays()).isEqualTo(4);
			StockItem stock = harness.stockItemRepository.findByExternalId(part.getExternalId()).orElseThrow();
			assertThat(stock.getQuantity()).isZero();
			assertThat(stock.getPendingDemand()).isEqualTo(5);
		}

		@Test
		void shouldCompensateWithoutTouchingStockWhenAPartNoLongerExists() {
			reachAwaitingApproval();
			harness.stockItemRepository.delete(part.getExternalId());

			harness.receive(order, SagaMessageTypes.BILLING_BUDGET_APPROVED);

			SagaInstance saga = harness.sagaOf(order);
			assertThat(saga.getStatus()).isEqualTo(SagaStatusEnum.COMPENSATED);
			assertThat(saga.getFailureReason()).contains("Stock could not be reserved");
			assertThat(saga.isStockReserved()).isFalse();
			assertThat(harness.reload(order).getStatus()).isEqualTo(ServiceOrderStatusEnum.CANCELED);
			assertThat(harness.sentCommandTypes())
				.contains(SagaMessageTypes.BILLING_BUDGET_CANCEL, SagaMessageTypes.EXECUTION_WORK_ORDER_CANCEL)
				.doesNotContain(SagaMessageTypes.BILLING_PAYMENT_CREATE, SagaMessageTypes.BILLING_PAYMENT_REFUND);
		}
	}

	@Nested
	class Compensation {

		@Test
		void shouldCancelThePendingDemandCreatedByAShortageWhenThePaymentFails() {
			completeDiagnosis(1, 15);
			harness.receive(order, SagaMessageTypes.BILLING_BUDGET_SENT);
			harness.receive(order, SagaMessageTypes.BILLING_BUDGET_APPROVED);

			harness.receive(order, SagaMessageTypes.BILLING_PAYMENT_FAILED, new SagaPayloads.Reason("Recusado"));

			StockItem stock = harness.stockItemRepository.findByExternalId(part.getExternalId()).orElseThrow();
			assertThat(stock.getQuantity()).isEqualTo(10);
			assertThat(stock.getPendingDemand()).isZero();
		}

		@Test
		void shouldNotRefundWhenNothingWasPaid() {
			reachAwaitingApproval();
			harness.receive(order, SagaMessageTypes.BILLING_BUDGET_APPROVED);

			harness.receive(order, SagaMessageTypes.BILLING_PAYMENT_FAILED);

			assertThat(harness.sentCommandTypes()).doesNotContain(SagaMessageTypes.BILLING_PAYMENT_REFUND);
			assertThat(harness.sagaOf(order).getFailureReason()).isEqualTo("Payment failed: no reason given");
		}

		@Test
		void shouldCompensateWhenTheBillingServiceNeverAnswersTheBudgetRequest() {
			completeDiagnosis(1, 1);
			harness.clock.advance(SagaTestHarness.BUDGET_GENERATION_TIMEOUT.plus(Duration.ofSeconds(1)));

			harness.orchestrator.handleTimeout(harness.sagaOf(order).getId());

			SagaInstance saga = harness.sagaOf(order);
			assertThat(saga.getStatus()).isEqualTo(SagaStatusEnum.COMPENSATED);
			assertThat(saga.getFailureReason()).isEqualTo("Step BUDGET_GENERATION timed out.");
			assertThat(harness.reload(order).getStatus()).isEqualTo(ServiceOrderStatusEnum.CANCELED);
		}

		@Test
		void shouldNotBudgetCancelWhenTheSagaFailsBeforeTheBudgetExists() {
			// A deadline on the diagnosis step is not configured today; forcing one proves the rule.
			SagaInstance saga = harness.sagaOf(order);
			saga.setStepDeadline(harness.clock.instant().minusSeconds(1));

			harness.orchestrator.handleTimeout(saga.getId());

			assertThat(harness.sentCommandTypes())
				.contains(SagaMessageTypes.EXECUTION_WORK_ORDER_CANCEL)
				.doesNotContain(SagaMessageTypes.BILLING_BUDGET_CANCEL);
		}
	}

	@Nested
	class Timeout {

		@Test
		void shouldDoNothingWhenTheDeadlineHasNotPassed() {
			reachAwaitingApproval();

			harness.orchestrator.handleTimeout(harness.sagaOf(order).getId());

			assertThat(harness.sagaOf(order).isWaitingAt(SagaStepEnum.AWAITING_APPROVAL)).isTrue();
		}

		@Test
		void shouldDoNothingWhenTheReplyArrivedBeforeTheTimeoutWasHandled() {
			reachAwaitingApproval();
			harness.clock.advance(SagaTestHarness.APPROVAL_TIMEOUT.plus(Duration.ofSeconds(1)));
			List<UUID> expired = harness.sagaRepository.findExpiredIds(harness.clock.instant());
			harness.receive(order, SagaMessageTypes.BILLING_BUDGET_REJECTED);

			expired.forEach(harness.orchestrator::handleTimeout);

			assertThat(harness.sagaOf(order).getStatus()).isEqualTo(SagaStatusEnum.CANCELED);
		}

		@Test
		void shouldIgnoreUnknownSaga() {
			harness.orchestrator.handleTimeout(UUID.randomUUID());

			assertThat(harness.sagaOf(order).isWaitingAt(SagaStepEnum.DIAGNOSIS)).isTrue();
		}
	}

	@Nested
	class UnexpectedReplies {

		@Test
		void shouldIgnoreARepliesForAStepTheSagaIsNotWaitingAt() {
			int commandsBefore = harness.sentCommands.size();

			for (String type : List.of(
				SagaMessageTypes.BILLING_BUDGET_SENT,
				SagaMessageTypes.BILLING_BUDGET_FAILED,
				SagaMessageTypes.BILLING_BUDGET_APPROVED,
				SagaMessageTypes.BILLING_BUDGET_REJECTED,
				SagaMessageTypes.BILLING_PAYMENT_CONFIRMED,
				SagaMessageTypes.BILLING_PAYMENT_FAILED,
				SagaMessageTypes.EXECUTION_REPAIR_COMPLETED,
				SagaMessageTypes.EXECUTION_REPAIR_FAILED,
				SagaMessageTypes.BILLING_PAYMENT_REFUNDED
			)) {
				harness.receive(order, type);
			}

			assertThat(harness.sagaOf(order).isWaitingAt(SagaStepEnum.DIAGNOSIS)).isTrue();
			assertThat(harness.reload(order).getStatus()).isEqualTo(ServiceOrderStatusEnum.RECEIVED);
			assertThat(harness.sentCommands).hasSize(commandsBefore);
		}

		@Test
		void shouldIgnoreLateDiagnosisRepliesAfterTheSagaMovedOn() {
			reachAwaitingApproval();

			harness.receive(order, SagaMessageTypes.EXECUTION_DIAGNOSIS_STARTED);
			completeDiagnosis(5, 5);

			assertThat(harness.sagaOf(order).isWaitingAt(SagaStepEnum.AWAITING_APPROVAL)).isTrue();
			assertThat(harness.reload(order).getBudgetTotalAmount()).isEqualByComparingTo("420.00");
		}

		@Test
		void shouldIgnoreRepliesOfAnUnknownSaga() {
			SagaMessage orphan = new SagaMessage(
				UUID.randomUUID(),
				UUID.randomUUID(),
				order.getExternalId(),
				SagaMessageTypes.BILLING_BUDGET_SENT,
				harness.clock.instant(),
				null,
				harness.objectMapper.valueToTree(Map.of())
			);

			harness.orchestrator.handleReply(orphan);

			assertThat(harness.sagaOf(order).isWaitingAt(SagaStepEnum.DIAGNOSIS)).isTrue();
		}

		@Test
		void shouldIgnoreUnknownReplyTypes() {
			harness.receive(order, "billing.something.new");

			assertThat(harness.sagaOf(order).isWaitingAt(SagaStepEnum.DIAGNOSIS)).isTrue();
		}

		@Test
		void shouldOnlyLogInformationalReplies() {
			int logBefore = harness.sagaRepository.log.size();

			harness.receive(order, SagaMessageTypes.BILLING_PAYMENT_CREATED);
			harness.receive(order, SagaMessageTypes.EXECUTION_REPAIR_STARTED);

			assertThat(harness.sagaRepository.log).hasSize(logBefore + 2);
			assertThat(harness.sagaOf(order).isWaitingAt(SagaStepEnum.DIAGNOSIS)).isTrue();
		}
	}

	@Nested
	class Completion {

		@Test
		void shouldRecordTheActualCompletionWhenTheRepairFinishes() {
			reachAwaitingApproval();
			harness.receive(order, SagaMessageTypes.BILLING_BUDGET_APPROVED);
			harness.receive(order, SagaMessageTypes.BILLING_PAYMENT_CONFIRMED);

			harness.receive(order, SagaMessageTypes.EXECUTION_REPAIR_COMPLETED);

			ServiceOrder completed = harness.reload(order);
			assertThat(completed.getActualCompletionDate()).isNotNull();
			assertThat(completed.getActualCompletionDays()).isZero();
			assertThat(harness.sagaOf(order).getStepDeadline()).isNull();
		}

		@Test
		void shouldTellTheExecutionServiceHowLongTheRepairIsExpectedToTake() {
			reachAwaitingApproval();
			harness.receive(order, SagaMessageTypes.BILLING_BUDGET_APPROVED);

			harness.receive(order, SagaMessageTypes.BILLING_PAYMENT_CONFIRMED);

			var payload = (SagaPayloads.StartRepair) harness
				.lastCommand(SagaMessageTypes.EXECUTION_REPAIR_START)
				.payload();
			assertThat(payload.estimatedCompletionDays()).isEqualTo(1);
		}
	}
}
