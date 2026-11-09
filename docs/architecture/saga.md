# Saga da Ordem de Serviço

A abertura, a aprovação, o pagamento e a execução de uma OS envolvem três serviços com bancos separados. Não existe transação distribuída: cada passo é uma transação local, e a consistência vem de uma **Saga orquestrada**. O orquestrador é o `ServiceOrderSagaOrchestrator`, no OS Service.

## Estratégia: orquestração

| Critério | Orquestração (escolhida) | Coreografia |
|---|---|---|
| Fluxo linear com ordem fixa | Um único lugar define a sequência | A sequência fica implícita na troca de eventos |
| Compensação em ordem inversa | Explícita no orquestrador | Espalhada pelos três serviços |
| Visibilidade | Estado persistido em `saga_instances`/`saga_log` e exposto em `GET /api/service-orders/{id}/saga` | Exige reconstruir o fluxo a partir de logs |
| Acoplamento | O orquestrador conhece os contratos dos participantes | Participantes conhecem os eventos uns dos outros |

O custo da orquestração é o OS Service conhecer os comandos do Billing e do Execution. Ele é aceitável porque a OS já é o agregado central do negócio. Os participantes não conhecem uns aos outros: só respondem ao orquestrador.

## Máquina de estados

```mermaid
stateDiagram-v2
    [*] --> DIAGNOSIS: OS aberta / execution.diagnosis.start
    DIAGNOSIS --> DIAGNOSIS: itens inexistentes / execution.diagnosis.reopen
    DIAGNOSIS --> BUDGET_GENERATION: execution.diagnosis.completed / billing.budget.generate
    BUDGET_GENERATION --> AWAITING_APPROVAL: billing.budget.sent
    AWAITING_APPROVAL --> AWAITING_PAYMENT: billing.budget.approved / reserva estoque + billing.payment.create
    AWAITING_PAYMENT --> EXECUTION: billing.payment.confirmed / execution.repair.start
    EXECUTION --> DONE: execution.repair.completed

    BUDGET_GENERATION --> DIAGNOSIS: billing.budget.failed / execution.diagnosis.reopen
    BUDGET_GENERATION --> DONE: prazo / compensa
    AWAITING_APPROVAL --> DONE: peça removida do estoque / compensa
    AWAITING_APPROVAL --> DONE: billing.budget.rejected / cancela trabalho
    AWAITING_APPROVAL --> DONE: prazo / compensa
    AWAITING_PAYMENT --> DONE: billing.payment.failed ou prazo / compensa
    EXECUTION --> COMPENSATING: execution.repair.failed / estorno + devolve estoque + cancela orçamento e trabalho
    COMPENSATING --> DONE: billing.payment.refunded
    DONE --> [*]
```

Status da Saga (`SagaStatusEnum`): `RUNNING` → `COMPLETED`, ou `CANCELED` (recusa do cliente), ou `COMPENSATING` → `COMPENSATED`.

Correspondência com os status de cada serviço:

| Passo da Saga | OS (OS Service) | Ordem de trabalho (Execution) | Orçamento / pagamento (Billing) |
|---|---|---|---|
| `DIAGNOSIS` | `RECEIVED` → `DIAGNOSIS` | `DIAGNOSIS_QUEUED` → `IN_DIAGNOSIS` | — |
| `BUDGET_GENERATION` | `DIAGNOSIS` | `AWAITING_APPROVAL` | — |
| `AWAITING_APPROVAL` | `PENDING_APPROVAL` | `AWAITING_APPROVAL` | orçamento `SENT` |
| `AWAITING_PAYMENT` | `PENDING_PAYMENT` (já no `budget.approved`) | `AWAITING_APPROVAL` | orçamento `APPROVED`, pagamento `PENDING` |
| `EXECUTION` | `IN_PROGRESS` | `REPAIR_QUEUED` → `IN_REPAIR` | pagamento `CONFIRMED` |
| `DONE` (sucesso) | `COMPLETED` → `DELIVERED` | `COMPLETED` | — |
| `DONE` (compensada) | `CANCELED` | `CANCELED` / `FAILED` | orçamento `CANCELED`/`REJECTED`, pagamento `CANCELED`/`FAILED`/`REFUNDED` |

## Fluxo completo

```mermaid
sequenceDiagram
    autonumber
    actor R as Recepção
    actor M as Mecânico
    actor C as Cliente
    participant OS as OS Service<br/>(orquestrador)
    participant MQ as RabbitMQ
    participant EX as Execution Service
    participant BI as Billing Service

    R->>OS: POST /api/service-orders/create
    OS->>MQ: execution.diagnosis.start
    MQ->>EX: ordem na fila (DIAGNOSIS_QUEUED)
    M->>EX: POST .../diagnosis/start
    EX-->>OS: execution.diagnosis.started (OS → DIAGNOSIS)
    M->>EX: POST .../diagnosis/complete (serviços e peças)
    EX-->>OS: execution.diagnosis.completed
    Note over OS: precifica com catálogo e estoque locais
    OS->>MQ: billing.budget.generate (itens e total)
    MQ->>BI: cria orçamento e envia e-mail
    BI-->>OS: billing.budget.sent (OS → PENDING_APPROVAL)
    C->>BI: aprova (link do e-mail ou POST .../response)
    BI-->>OS: billing.budget.approved
    Note over OS: reserva o estoque (OS → PENDING_PAYMENT)
    OS->>MQ: billing.payment.create
    MQ->>BI: cria cobrança no gateway e envia o link
    BI-->>OS: billing.payment.created (só registra no saga_log)
    C->>BI: paga (webhook do gateway / simulate)
    BI-->>OS: billing.payment.confirmed (OS → IN_PROGRESS)
    OS->>MQ: execution.repair.start
    MQ->>EX: ordem na fila de reparo (REPAIR_QUEUED)
    M->>EX: POST .../repair/start e .../repair/complete
    EX-->>OS: execution.repair.completed (OS → COMPLETED, Saga COMPLETED)
    R->>OS: POST /api/service-orders/{id}/deliver (DELIVERED)
```

As setas tracejadas são respostas publicadas em `saga.events` e consumidas pela fila `os.saga-replies`.

## Falhas e compensações

| Falha | Compensação (em ordem) | Resultado |
|---|---|---|
| Diagnóstico com serviço ou peça inexistente | `execution.diagnosis.reopen` (devolve ao mecânico com o motivo) | Saga continua em `DIAGNOSIS` |
| Billing recusa gerar o orçamento (`billing.budget.failed`: itens ou total inválidos) | `execution.diagnosis.reopen` com o motivo | Saga volta a `DIAGNOSIS` |
| Billing não responde no prazo | `billing.budget.cancel` → `execution.workorder.cancel` | `COMPENSATED`, OS `CANCELED` |
| Peça do orçamento removida do estoque antes da aprovação | `billing.budget.cancel` → `execution.workorder.cancel` | `COMPENSATED`, OS `CANCELED` |
| Cliente recusa o orçamento | `execution.workorder.cancel` | `CANCELED`, OS `CANCELED` |
| Cliente não responde no prazo | `billing.budget.cancel` → `execution.workorder.cancel` | `COMPENSATED`, OS `CANCELED` |
| Pagamento recusado ou não pago no prazo | devolve o estoque → `billing.budget.cancel` → `execution.workorder.cancel` | `COMPENSATED`, OS `CANCELED` |
| Reparo inviável | `billing.payment.refund` → devolve o estoque → `billing.budget.cancel` → `execution.workorder.cancel`, tudo na mesma transação; o `billing.payment.refunded` fecha a Saga | `COMPENSATING` → `COMPENSATED`, OS `CANCELED` |

### Pagamento recusado

```mermaid
sequenceDiagram
    autonumber
    participant OS as OS Service
    participant MQ as RabbitMQ
    participant BI as Billing Service
    participant EX as Execution Service

    BI-->>OS: billing.payment.failed
    Note over OS: Saga → COMPENSATED<br/>devolve o estoque reservado<br/>OS → CANCELED (mesma transação)
    OS->>MQ: billing.budget.cancel
    MQ->>BI: orçamento e cobrança → CANCELED
    OS->>MQ: execution.workorder.cancel
    MQ->>EX: ordem de trabalho → CANCELED
```

### Reparo inviável (depois de pago)

```mermaid
sequenceDiagram
    autonumber
    actor M as Mecânico
    participant EX as Execution Service
    participant OS as OS Service
    participant MQ as RabbitMQ
    participant BI as Billing Service

    M->>EX: POST .../repair/fail (motivo)
    EX-->>OS: execution.repair.failed (ordem → FAILED)
    Note over OS: Saga → COMPENSATING<br/>devolve o estoque, OS → CANCELED<br/>(mesma transação)
    OS->>MQ: billing.payment.refund
    OS->>MQ: billing.budget.cancel
    OS->>MQ: execution.workorder.cancel
    MQ->>BI: estorno no gateway (pagamento → REFUNDED)
    BI-->>OS: billing.payment.refunded
    Note over OS: Saga → COMPENSATED
```

### Prazos

O `SagaTimeoutScheduler` verifica a cada `SAGA_TIMEOUT_CHECK_INTERVAL_MS` (padrão 30s) as Sagas com `stepDeadline` vencido e chama `handleTimeout`, que aplica a mesma compensação de uma falha naquele passo.

| Passo | Variável | Padrão |
|---|---|---|
| Geração do orçamento | `SAGA_BUDGET_GENERATION_TIMEOUT` | 5 min |
| Aprovação do cliente | `SAGA_APPROVAL_TIMEOUT` | 72 h |
| Pagamento | `SAGA_PAYMENT_TIMEOUT` | 24 h |

## Catálogo de mensagens

Envelope comum (`SagaMessage`, idêntico nos três serviços): `messageId`, `sagaId`, `serviceOrderId`, `type`, `occurredAt`, `correlationId`, `payload`.

| Tipo | Direção | Payload |
|---|---|---|
| `execution.diagnosis.start` | OS → Execution | placa, marca, modelo, ano, cliente, reclamação, odômetro, e-mail do mecânico |
| `execution.diagnosis.started` | Execution → OS | — |
| `execution.diagnosis.completed` | Execution → OS | diagnóstico, serviços (`mechanicServiceExternalId`, quantidade), peças (`partExternalId`, quantidade) |
| `execution.diagnosis.reopen` | OS → Execution | motivo |
| `billing.budget.generate` | OS → Billing | cliente, e-mail, placa, itens (`LABOR`/`PART`, referência, descrição, quantidade, preço unitário e total), total, prazo |
| `billing.budget.sent` / `failed` | Billing → OS | — / motivo |
| `billing.budget.approved` / `rejected` | Billing → OS | — |
| `billing.payment.create` | OS → Billing | — |
| `billing.payment.created` / `confirmed` / `failed` | Billing → OS | — / — / motivo |
| `billing.budget.cancel` | OS → Billing | motivo (compensação) |
| `billing.payment.refund` / `refunded` | OS ↔ Billing | motivo (compensação) |
| `execution.repair.start` | OS → Execution | prazo estimado em dias |
| `execution.repair.started` / `completed` / `failed` | Execution → OS | — / — / motivo |
| `execution.workorder.cancel` | OS → Execution | motivo (compensação) |

## Topologia no RabbitMQ

```mermaid
flowchart LR
    os["OS Service<br/>OutboxRelay"] -->|"routing key billing"| cmd{{"saga.commands<br/>(direct)"}}
    os -->|"routing key execution"| cmd
    cmd --> bq["billing.commands"] --> bi["Billing Service"]
    cmd --> eq["execution.commands"] --> ex["Execution Service"]
    bi -->|"OutboxRelay"| ev{{"saga.events<br/>(topic, #)"}}
    ex -->|"OutboxRelay"| ev
    ev --> rq["os.saga-replies"] --> osl["OS Service<br/>SagaReplyListener"]
    bq -. rejeitada .-> dlx{{"saga.dlx"}}
    eq -. rejeitada .-> dlx
    rq -. rejeitada .-> dlx
    dlx --> dlq["billing.commands.dlq<br/>execution.commands.dlq<br/>os.saga-replies.dlq"]
```

Os três serviços declaram a mesma topologia na subida (`RabbitTopologyConfig`), então qualquer um pode iniciar primeiro.

## Garantias

- **Atomicidade local + outbox:** a mudança de estado e o comando seguinte são gravados na mesma transação (`outbox_messages`). O `OutboxRelay` publica a cada `OUTBOX_POLL_INTERVAL_MS` (padrão 1s). Se o RabbitMQ estiver fora, as mensagens esperam na outbox.
- **Idempotência:** cada consumidor registra o `messageId` em `processed_messages`; uma reentrega é reconhecida e ignorada.
- **Ordem e atraso:** o orquestrador trava a linha da Saga e só aceita a resposta que o passo atual espera; respostas atrasadas ou fora de ordem são descartadas.
- **Mensagens venenosas:** após as tentativas em memória, a mensagem é rejeitada sem reenfileirar e vai para a DLQ da fila.
- **Rastreabilidade:** o `correlationId` da requisição original viaja em todas as mensagens e entra no MDC e no New Relic dos três serviços. O histórico de cada Saga fica em `saga_log`.

## Como verificar

```bash
docker compose -f docker-compose.full.yaml up --build -d
./scripts/saga_e2e.sh happy            # RECEIVED → … → DELIVERED, Saga COMPLETED
./scripts/saga_e2e.sh budget_rejected  # Saga CANCELED
./scripts/saga_e2e.sh payment_refused  # Saga COMPENSATED, estoque devolvido
./scripts/saga_e2e.sh repair_failed    # pagamento REFUNDED, Saga COMPENSATED
```

Os testes BDD em `src/test/resources/features/saga_ordem_de_servico.feature` cobrem os mesmos cenários contra o orquestrador real com portas em memória.
