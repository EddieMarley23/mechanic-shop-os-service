package org.project.mechanic_shop.bdd;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.project.mechanic_shop.application.saga.SagaMessage;
import org.project.mechanic_shop.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.application.saga.SagaParticipant;
import org.project.mechanic_shop.application.saga.SagaPayloads;
import org.project.mechanic_shop.domain.entities.mechanic_service.MechanicService;
import org.project.mechanic_shop.domain.entities.service_order.ServiceOrder;
import org.project.mechanic_shop.domain.entities.stock_item.StockItem;
import org.project.mechanic_shop.domain.enums.SagaStatusEnum;
import org.project.mechanic_shop.domain.enums.ServiceOrderStatusEnum;
import org.project.mechanic_shop.saga.SagaTestHarness;

/**
 * Steps of the service order Saga scenarios. The orchestrator is the system under test; the steps
 * act as the Billing and Execution services by delivering the replies they would send.
 */
public class ServiceOrderSagaSteps {

	private final SagaTestHarness harness = new SagaTestHarness();
	private final Map<String, MechanicService> catalog = new HashMap<>();
	private final Map<String, StockItem> stock = new HashMap<>();
	private ServiceOrder order;

	// --------------------------------------------------------------------------------------- Dado

	@Dado("o serviço {string} no catálogo por {string} reais")
	public void servicoNoCatalogo(String name, String price) {
		catalog.put(name, harness.addCatalogService(name, price, 90));
	}

	@Dado("a peça {string} no estoque com {int} unidades a {string} reais")
	public void pecaNoEstoque(String name, int quantity, String price) {
		stock.put(name, harness.addStockItem(name, quantity, price));
	}

	@Dado("uma ordem de serviço aguardando a aprovação do orçamento")
	public void ordemAguardandoAprovacao() {
		abrirOrdem("ABC1D23");
		concluirDiagnostico(1, "Troca de pastilhas", 2, "Pastilha de freio");
		billingEnviaOrcamento();
	}

	@Dado("uma ordem de serviço aguardando o pagamento")
	public void ordemAguardandoPagamento() {
		ordemAguardandoAprovacao();
		clienteAprova();
	}

	@Dado("uma ordem de serviço em execução")
	public void ordemEmExecucao() {
		ordemAguardandoPagamento();
		billingConfirmaPagamento();
	}

	// ------------------------------------------------------------------------------------- Quando

	@Quando("a recepção abre uma ordem de serviço para o veículo {string}")
	public void abrirOrdem(String licensePlate) {
		order = harness.openOrder(licensePlate, "cliente@teste.com");
	}

	@Quando("a Execução conclui o diagnóstico com {int} {string} e {int} {string}")
	public void concluirDiagnostico(int laborQuantity, String laborName, int partQuantity, String partName) {
		harness.receive(order, SagaMessageTypes.EXECUTION_DIAGNOSIS_STARTED);
		harness.receive(
			order,
			SagaMessageTypes.EXECUTION_DIAGNOSIS_COMPLETED,
			new SagaPayloads.DiagnosisCompleted(
				"Pastilhas desgastadas",
				List.of(new SagaPayloads.DiagnosisCompleted.Labor(catalog.get(laborName).getExternalId(), laborQuantity)),
				List.of(new SagaPayloads.DiagnosisCompleted.Part(stock.get(partName).getExternalId(), partQuantity))
			)
		);
	}

	@Quando("o Billing envia o orçamento ao cliente")
	public void billingEnviaOrcamento() {
		harness.receive(order, SagaMessageTypes.BILLING_BUDGET_SENT);
	}

	@Quando("o cliente aprova o orçamento")
	public void clienteAprova() {
		harness.receive(order, SagaMessageTypes.BILLING_BUDGET_APPROVED);
	}

	@Quando("o cliente recusa o orçamento")
	public void clienteRecusa() {
		harness.receive(order, SagaMessageTypes.BILLING_BUDGET_REJECTED);
	}

	@Quando("a aprovação do cliente é entregue duas vezes")
	public void aprovacaoEntregueDuasVezes() {
		SagaMessage approval = harness.reply(order, SagaMessageTypes.BILLING_BUDGET_APPROVED, Map.of());
		harness.orchestrator.handleReply(approval);
		harness.orchestrator.handleReply(approval);
	}

	@Quando("o Billing confirma o pagamento")
	public void billingConfirmaPagamento() {
		harness.receive(order, SagaMessageTypes.BILLING_PAYMENT_CONFIRMED);
	}

	@Quando("o Billing informa que o pagamento falhou")
	public void billingInformaFalhaNoPagamento() {
		harness.receive(order, SagaMessageTypes.BILLING_PAYMENT_FAILED, new SagaPayloads.Reason("Cartão recusado"));
	}

	@Quando("o Billing confirma o estorno")
	public void billingConfirmaEstorno() {
		harness.receive(order, SagaMessageTypes.BILLING_PAYMENT_REFUNDED, Map.of("refunded", true));
	}

	@Quando("a Execução conclui o reparo")
	public void execucaoConcluiReparo() {
		harness.receive(order, SagaMessageTypes.EXECUTION_REPAIR_STARTED);
		harness.receive(order, SagaMessageTypes.EXECUTION_REPAIR_COMPLETED);
	}

	@Quando("a Execução informa que o reparo falhou")
	public void execucaoInformaFalha() {
		harness.receive(order, SagaMessageTypes.EXECUTION_REPAIR_FAILED, new SagaPayloads.Reason("Disco trincado"));
	}

	@Quando("o prazo de aprovação expira")
	public void prazoDeAprovacaoExpira() {
		harness.clock.advance(SagaTestHarness.APPROVAL_TIMEOUT.plus(Duration.ofMinutes(1)));
		harness.sagaRepository.findExpiredIds(harness.clock.instant()).forEach(harness.orchestrator::handleTimeout);
	}

	// -------------------------------------------------------------------------------------- Então

	@Entao("o diagnóstico é solicitado ao serviço de Execução")
	public void diagnosticoSolicitado() {
		var command = harness.lastCommand(SagaMessageTypes.EXECUTION_DIAGNOSIS_START);
		assertThat(command.participant()).isEqualTo(SagaParticipant.EXECUTION);
		assertThat(((SagaPayloads.StartDiagnosis) command.payload()).licensePlate()).isEqualTo("ABC1D23");
	}

	@Entao("um orçamento de {string} reais é solicitado ao serviço de Billing")
	public void orcamentoSolicitado(String total) {
		var command = harness.lastCommand(SagaMessageTypes.BILLING_BUDGET_GENERATE);
		var payload = (SagaPayloads.GenerateBudget) command.payload();

		assertThat(command.participant()).isEqualTo(SagaParticipant.BILLING);
		assertThat(payload.totalAmount()).isEqualByComparingTo(new BigDecimal(total));
		assertThat(payload.customerEmail()).isEqualTo("cliente@teste.com");
		assertThat(payload.items()).hasSize(2);
	}

	@Entao("o pagamento é solicitado ao serviço de Billing")
	public void pagamentoSolicitado() {
		assertThat(harness.lastCommand(SagaMessageTypes.BILLING_PAYMENT_CREATE).participant()).isEqualTo(
			SagaParticipant.BILLING
		);
	}

	@Entao("o pagamento é solicitado ao serviço de Billing uma única vez")
	public void pagamentoSolicitadoUmaVez() {
		assertThat(harness.sentCommandTypes()).containsOnlyOnce(SagaMessageTypes.BILLING_PAYMENT_CREATE);
	}

	@Entao("o reparo é solicitado ao serviço de Execução")
	public void reparoSolicitado() {
		assertThat(harness.lastCommand(SagaMessageTypes.EXECUTION_REPAIR_START).participant()).isEqualTo(
			SagaParticipant.EXECUTION
		);
	}

	@Entao("o estorno do pagamento é solicitado ao serviço de Billing")
	public void estornoSolicitado() {
		assertThat(harness.lastCommand(SagaMessageTypes.BILLING_PAYMENT_REFUND).participant()).isEqualTo(
			SagaParticipant.BILLING
		);
	}

	@Entao("o Billing é avisado para cancelar o orçamento")
	public void billingAvisadoParaCancelar() {
		assertThat(harness.sentCommandTypes()).contains(SagaMessageTypes.BILLING_BUDGET_CANCEL);
	}

	@Entao("a Execução é avisada para cancelar o trabalho")
	public void execucaoAvisadaParaCancelar() {
		assertThat(harness.sentCommandTypes()).contains(SagaMessageTypes.EXECUTION_WORK_ORDER_CANCEL);
	}

	@Entao("a ordem de serviço fica {string}")
	public void ordemFica(String status) {
		assertThat(harness.reload(order).getStatus()).isEqualTo(ServiceOrderStatusEnum.valueOf(status));
	}

	// Step keywords are interchangeable in Gherkin: this one is also used as a "Dado".
	@Entao("o estoque da peça {string} é {int}")
	public void estoqueDaPeca(String name, int quantity) {
		assertThat(harness.stockOf(stock.get(name))).isEqualTo(quantity);
	}

	@Entao("a saga termina como {string}")
	public void sagaTerminaComo(String status) {
		var saga = harness.sagaOf(order);
		assertThat(saga.getStatus()).isEqualTo(SagaStatusEnum.valueOf(status));
		assertThat(saga.getStatus().isFinished()).isTrue();
		assertThat(saga.getFinishedAt()).isNotNull();
	}

	@Entao("a saga fica {string}")
	public void sagaFica(String status) {
		assertThat(harness.sagaOf(order).getStatus()).isEqualTo(SagaStatusEnum.valueOf(status));
	}
}
