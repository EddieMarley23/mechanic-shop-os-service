# k6 load tests

Suíte `k6` da Fase 4. Todo o tráfego passa pelo **Kong** (`http://localhost:8000`), que encaminha `/api` ao OS Service, `/api/billing` ao Billing Service e `/api/executions` ao Execution Service. Assim, o teste mede o sistema distribuído inteiro, incluindo a Saga via RabbitMQ.

Jornadas:

- **Leitura geral** (65% das iterações de carga): `search` em usuários, veículos, catálogo, estoque e ordens de serviço, além da fila de execução (`/api/executions?status=DIAGNOSIS_QUEUED`).
- **Saga da OS** (35%): cria cliente, veículo, serviço e peça e abre a OS. Depois, a cada passo assíncrono, espera o efeito no serviço seguinte:
  1. Execution recebe a OS na fila, e o mecânico inicia e conclui o diagnóstico;
  2. OS precifica, Billing envia o orçamento e o cliente aprova (`/api/billing/budgets/{id}/response`);
  3. OS reserva o estoque, Billing cria a cobrança e o cliente paga (`/api/billing/payments/{id}/simulate`);
  4. Execution recebe o reparo, e o mecânico inicia e conclui;
  5. OS fica `COMPLETED` e a recepção entrega o veículo.

Cenários: `smoke` (1 VU, só a Saga), `baseline`, `stress` e `spike`.

## Métricas da Saga

Além das métricas HTTP padrão:

| Métrica | O que mede | Limite |
|---|---|---|
| `saga_flow_duration` | Da abertura da OS até a entrega | p95 < 60s |
| `saga_step_latency` | Tempo para um passo assíncrono aparecer no serviço seguinte (tag `step`) | p95 < 5s |
| `saga_step_timeouts` | Passos que não chegaram em `SAGA_STEP_TIMEOUT` segundos | 0 |
| `business_errors` | Respostas com status inesperado | < 10% |

As requisições de espera (polling) levam a tag `polling:true` e não contam como falha HTTP.

## Estrutura

- `script.js`: entrypoint com `setup()` compartilhado e export dos cenários
- `src/config.js`: configuração, métricas, thresholds e cenários
- `src/auth.js`: autenticação e descoberta do mecânico seed
- `src/data-factory.js`: geração de payloads únicos e válidos
- `src/http.js`: wrapper HTTP com validação padrão e `peek` para polling
- `src/workflows.js`: jornadas de negócio reutilizáveis

## Pré-requisitos

1. Subir os três serviços com o Kong: `docker compose -f docker-compose.full.yaml up --build -d` na raiz deste repositório.
2. Instalar o `k6` (Fedora: `sudo dnf install https://dl.k6.io/rpm/repo.rpm && sudo dnf install k6`).

## Variáveis suportadas

```bash
BASE_URL=http://localhost:8000      # Kong
SEED_PASSWORD=...                   # SEED_DEFAULT_PASSWORD do .env
ADMIN_EMAIL=admin@shop.com
RECEPTIONIST_EMAIL=receptionist@shop.com
MECHANIC_EMAIL=mechanic@shop.com
WAREHOUSE_EMAIL=warehouse@shop.com
SCENARIO=all                        # smoke | baseline | stress | spike | all
SAGA_STEP_TIMEOUT=30                # segundos de espera por passo assíncrono
SAGA_POLL_INTERVAL=0.5              # segundos entre consultas de espera
SMOKE_ITERATIONS=3
```

Controle fino da carga:

```bash
BASELINE_TARGET_VUS=10
STRESS_TARGET_1=20
STRESS_TARGET_2=40
STRESS_TARGET_3=60
SPIKE_TARGET=80
```

## Execução

```bash
export SEED_PASSWORD=$(grep -oP '^SEED_DEFAULT_PASSWORD=\K.*' .env)

SCENARIO=smoke k6 run k6/script.js                 # valida o ambiente (3 OS pela Saga)
SCENARIO=baseline k6 run k6/script.js
SCENARIO=stress k6 run k6/script.js
SCENARIO=spike SPIKE_TARGET=120 k6 run k6/script.js
k6 run k6/script.js                                # baseline + stress + spike
```

Referência local (compose completo, 8 VUs por 60s): 131 iterações, 0% de erro, `http_req_duration` p95 de 69 ms, `saga_flow_duration` p95 de 9,4 s e `saga_step_latency` p95 de 2,5 s. A maior parte da latência por passo vem do intervalo de polling das outboxes (`OUTBOX_POLL_INTERVAL_MS`).

## Observações

- O teste cria clientes, veículos, serviços, itens de estoque e OS dinamicamente, para evitar colisão entre VUs.
- A aprovação do orçamento e o pagamento são feitos pelo cliente criado na própria iteração, com o JWT dele, exigido pelo Kong nessas rotas.
- O pagamento usa o gateway `simulated` do Billing (`PAYMENT_PROVIDER=simulated`).
- Como o teste grava bastante dado, execute-o em ambiente isolado ou limpe os volumes entre rodadas maiores (`docker compose -f docker-compose.full.yaml down -v`).
