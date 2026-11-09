import { fail, group, sleep } from 'k6';
import {
  buildCustomerPayload,
  buildDiagnosisPayload,
  buildMechanicServicePayload,
  buildServiceOrderPayload,
  buildStockItemPayload,
  buildVehiclePayload,
} from './data-factory.js';
import { loginCustomer } from './auth.js';
import { businessErrors, config, sagaFlowDuration, sagaStepLatency, sagaStepTimeouts } from './config.js';
import { getData, peek, request } from './http.js';
import { pickOne } from './utils.js';

function createCustomer(receptionistToken, scenarioName) {
  const customerPayload = buildCustomerPayload();
  const customerId = getData('POST', '/api/users/create', {
    token: receptionistToken,
    body: customerPayload,
    expectedStatus: 201,
    tags: { flow: 'customer_create', scenario: scenarioName },
  });

  return {
    customerId,
    customerPayload,
  };
}

function createVehicle(receptionistToken, ownerId, scenarioName) {
  const vehiclePayload = buildVehiclePayload(ownerId);
  return getData('POST', '/api/vehicles/create', {
    token: receptionistToken,
    body: vehiclePayload,
    expectedStatus: 201,
    tags: { flow: 'vehicle_create', scenario: scenarioName },
  });
}

function createMechanicService(mechanicToken, scenarioName) {
  const servicePayload = buildMechanicServicePayload();
  return getData('POST', '/api/mechanic-services/create', {
    token: mechanicToken,
    body: servicePayload,
    expectedStatus: 201,
    tags: { flow: 'mechanic_service_create', scenario: scenarioName },
  });
}

function createStockItem(warehouseToken, scenarioName) {
  const stockPayload = buildStockItemPayload();
  return getData('POST', '/api/stock-items/create', {
    token: warehouseToken,
    body: stockPayload,
    expectedStatus: 201,
    tags: { flow: 'stock_item_create', scenario: scenarioName },
  });
}

function createServiceOrder(receptionistToken, vehicleExternalId, mechanicExternalId, scenarioName) {
  const orderPayload = buildServiceOrderPayload(vehicleExternalId, mechanicExternalId);
  return getData('POST', '/api/service-orders/create', {
    token: receptionistToken,
    body: orderPayload,
    expectedStatus: 201,
    tags: { flow: 'service_order_create', scenario: scenarioName },
  });
}

/**
 * Aguarda um passo assincrono da Saga (mensagem via RabbitMQ) refletir em um servico.
 * Registra a latencia em saga_step_latency; se o prazo estourar, conta em saga_step_timeouts
 * e interrompe a iteracao.
 */
function waitFor(stepName, path, token, predicate, scenarioName) {
  const startedAt = Date.now();
  const deadline = startedAt + config.sagaStepTimeoutSeconds * 1000;
  let last;

  while (Date.now() < deadline) {
    last = peek(path, token, { flow: `wait_${stepName}`, scenario: scenarioName });
    if (last.status === 200 && predicate(last.data)) {
      sagaStepLatency.add(Date.now() - startedAt, { step: stepName });
      return last.data;
    }
    sleep(config.sagaPollIntervalSeconds);
  }

  sagaStepTimeouts.add(1, { step: stepName });
  businessErrors.add(1);
  fail(`Saga step '${stepName}' did not complete in ${config.sagaStepTimeoutSeconds}s (last: ${JSON.stringify(last)})`);
}

const statusOf = (data) => (data && typeof data === 'object' ? data.status : data);

function waitServiceOrderStatus(token, serviceOrderId, expected, scenarioName) {
  return waitFor(
    `os_${expected.toLowerCase()}`,
    `/api/service-orders/${serviceOrderId}/status`,
    token,
    (data) => statusOf(data) === expected,
    scenarioName
  );
}

function waitWorkOrderStatus(token, serviceOrderId, expected, scenarioName) {
  return waitFor(
    `execution_${expected.toLowerCase()}`,
    `/api/executions/${serviceOrderId}`,
    token,
    (data) => data?.status === expected,
    scenarioName
  );
}

function executionAction(mechanicToken, serviceOrderId, action, scenarioName, body) {
  return getData('POST', `/api/executions/${serviceOrderId}/${action}`, {
    token: mechanicToken,
    body,
    expectedStatus: 200,
    tags: { flow: `execution_${action.replace('/', '_')}`, scenario: scenarioName },
  });
}

function respondBudget(customerToken, serviceOrderId, scenarioName) {
  return getData('POST', `/api/billing/budgets/${serviceOrderId}/response`, {
    token: customerToken,
    body: { approved: true },
    expectedStatus: 200,
    tags: { flow: 'billing_budget_approve', scenario: scenarioName },
  });
}

function simulatePayment(customerToken, serviceOrderId, scenarioName) {
  return getData('POST', `/api/billing/payments/${serviceOrderId}/simulate`, {
    token: customerToken,
    body: { approved: true },
    expectedStatus: 200,
    tags: { flow: 'billing_payment_simulate', scenario: scenarioName },
  });
}

function deliverVehicle(receptionistToken, serviceOrderId, scenarioName) {
  return getData('POST', `/api/service-orders/${serviceOrderId}/deliver`, {
    token: receptionistToken,
    expectedStatus: 200,
    tags: { flow: 'service_order_deliver', scenario: scenarioName },
  });
}

function searchUsers(receptionistToken, scenarioName) {
  getData('GET', '/api/users/search?size=5', {
    token: receptionistToken,
    expectedStatus: 200,
    tags: { flow: 'users_search', scenario: scenarioName },
  });
}

function searchVehicles(token, scenarioName) {
  getData('GET', '/api/vehicles/search?size=5', {
    token,
    expectedStatus: 200,
    tags: { flow: 'vehicles_search', scenario: scenarioName },
  });
}

function searchMechanicServices(token, scenarioName) {
  getData('GET', '/api/mechanic-services/search?size=5', {
    token,
    expectedStatus: 200,
    tags: { flow: 'mechanic_services_search', scenario: scenarioName },
  });
}

function searchStockItems(token, scenarioName) {
  getData('GET', '/api/stock-items/search?size=5', {
    token,
    expectedStatus: 200,
    tags: { flow: 'stock_items_search', scenario: scenarioName },
  });
}

function searchServiceOrders(token, scenarioName) {
  getData('GET', '/api/service-orders/search?size=5', {
    token,
    expectedStatus: 200,
    tags: { flow: 'service_orders_search', scenario: scenarioName },
  });
}

export function runCatalogJourney(context, scenarioName) {
  group(`catalog-journey:${scenarioName}`, () => {
    searchUsers(context.receptionistToken, scenarioName);
    searchVehicles(pickOne([context.receptionistToken, context.mechanicToken]), scenarioName);
    searchMechanicServices(pickOne([context.receptionistToken, context.mechanicToken]), scenarioName);
    searchStockItems(pickOne([context.receptionistToken, context.warehouseToken, context.mechanicToken]), scenarioName);
    searchServiceOrders(pickOne([context.receptionistToken, context.mechanicToken]), scenarioName);
    searchExecutionQueue(context.mechanicToken, scenarioName);
  });
}

function searchExecutionQueue(mechanicToken, scenarioName) {
  getData('GET', '/api/executions?status=DIAGNOSIS_QUEUED', {
    token: mechanicToken,
    expectedStatus: 200,
    tags: { flow: 'execution_queue', scenario: scenarioName },
  });
}

/**
 * Jornada completa de uma OS pelos tres microsservicos, via Kong:
 * OS abre -> Execution diagnostica -> OS precifica -> Billing orca -> cliente aprova ->
 * OS reserva estoque -> Billing cobra -> cliente paga -> Execution repara -> OS entrega.
 */
export function runServiceOrderJourney(context, scenarioName) {
  group(`service-order-saga:${scenarioName}`, () => {
    const { customerId, customerPayload } = createCustomer(context.receptionistToken, scenarioName);
    const vehicleId = createVehicle(context.receptionistToken, customerId, scenarioName);
    const mechanicServiceId = createMechanicService(context.mechanicToken, scenarioName);
    const stockItemId = createStockItem(context.warehouseToken, scenarioName);

    const startedAt = Date.now();
    const serviceOrderId = createServiceOrder(
      context.receptionistToken,
      vehicleId,
      context.mechanicId,
      scenarioName
    );

    // Execution Service recebe execution.diagnosis.start pelo RabbitMQ.
    waitWorkOrderStatus(context.mechanicToken, serviceOrderId, 'DIAGNOSIS_QUEUED', scenarioName);
    executionAction(context.mechanicToken, serviceOrderId, 'diagnosis/start', scenarioName);
    executionAction(
      context.mechanicToken,
      serviceOrderId,
      'diagnosis/complete',
      scenarioName,
      buildDiagnosisPayload(stockItemId, mechanicServiceId)
    );

    // OS precifica e o Billing gera e envia o orcamento.
    waitServiceOrderStatus(context.receptionistToken, serviceOrderId, 'PENDING_APPROVAL', scenarioName);
    const customerToken = loginCustomer(customerPayload.email, customerPayload.password);
    respondBudget(customerToken, serviceOrderId, scenarioName);

    // OS reserva o estoque e o Billing cria a cobranca.
    waitServiceOrderStatus(context.receptionistToken, serviceOrderId, 'PENDING_PAYMENT', scenarioName);
    waitFor(
      'billing_payment_created',
      `/api/billing/payments/${serviceOrderId}`,
      customerToken,
      (data) => data?.status === 'PENDING',
      scenarioName
    );
    simulatePayment(customerToken, serviceOrderId, scenarioName);

    // Pagamento confirmado: a Saga libera o reparo no Execution Service.
    waitWorkOrderStatus(context.mechanicToken, serviceOrderId, 'REPAIR_QUEUED', scenarioName);
    executionAction(context.mechanicToken, serviceOrderId, 'repair/start', scenarioName);
    executionAction(context.mechanicToken, serviceOrderId, 'repair/complete', scenarioName);

    waitServiceOrderStatus(context.receptionistToken, serviceOrderId, 'COMPLETED', scenarioName);
    deliverVehicle(context.receptionistToken, serviceOrderId, scenarioName);
    sagaFlowDuration.add(Date.now() - startedAt);

    request('GET', `/api/service-orders/${serviceOrderId}`, {
      token: customerToken,
      expectedStatus: 200,
      tags: { flow: 'service_order_get_by_id', scenario: scenarioName },
    });
  });
}
