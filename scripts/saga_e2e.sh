#!/usr/bin/env bash
# Teste E2E da Saga com os 3 microsservicos rodando (docker-compose.full.yaml ou cluster).
# Cada passo imprime o curl executado e confere o efeito nos tres servicos.
#
# Uso: ./scripts/saga_e2e.sh [happy|budget_rejected|payment_refused|repair_failed|stop_at_budget]
#   happy            abre a OS e leva ate DELIVERED (Saga COMPLETED)
#   budget_rejected  cliente recusa o orcamento          -> Saga CANCELED
#   payment_refused  gateway recusa o pagamento          -> Saga COMPENSATED (estoque devolvido)
#   repair_failed    mecanico declara reparo inviavel    -> Saga COMPENSATED (pagamento estornado)
#   stop_at_budget   para com o orcamento enviado (para testar o link do e-mail)
#
# Por padrao tudo passa pelo Kong (http://localhost:8000). Variaveis:
#   OS_URL, BILLING_URL, EXECUTION_URL  base de cada servico (ex.: http://localhost:8080 direto)
#   SEED_PASSWORD                       senha dos usuarios seed (padrao: SEED_DEFAULT_PASSWORD do .env)
# O pagamento usa /simulate: o Billing precisa estar com PAYMENT_PROVIDER=simulated (padrao).
# Requer curl, jq e python3.
set -uo pipefail
GW=${GATEWAY_URL:-http://localhost:8000}
OS=${OS_URL:-$GW}; BIL=${BILLING_URL:-$GW}; EXE=${EXECUTION_URL:-$GW}
ENV_FILE="$(dirname "$0")/../.env"
SEED_PASSWORD=${SEED_PASSWORD:-$(grep -oP '^SEED_DEFAULT_PASSWORD=\K.*' "$ENV_FILE" 2>/dev/null)}
SCEN=${1:-happy}; RUN=$RANDOM
PASS=0; FAIL=0

step(){ echo; echo "### $*"; }
ok(){ echo "   ✔ $*"; PASS=$((PASS+1)); }
ko(){ echo "   ✘ $*"; FAIL=$((FAIL+1)); }
# req METHOD URL TOKEN [JSON]  -> imprime o curl e devolve o corpo
req(){ local m=$1 u=$2 t=$3 b=${4:-}
  echo "   \$ curl -s -X $m $u -H 'Authorization: Bearer \$TOKEN' ${b:+-H 'Content-Type: application/json' -d '$b'}" >&2
  if [ -n "$b" ]; then curl -s -X "$m" "$u" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -d "$b"
  else curl -s -X "$m" "$u" -H "Authorization: Bearer $t"; fi; }
id_of(){ jq -r '.data | if type=="object" then (.externalId // .id) else . end'; }
os_status(){ req GET "$OS/api/service-orders/$SO/status" "$ADM" 2>/dev/null | jq -r '.data.status // .data'; }
saga(){ req GET "$OS/api/service-orders/$SO/saga" "$ADM" 2>/dev/null; }
# wait_status ESPERADO TIMEOUT
wait_status(){ local want=$1 t=${2:-30} s; for _ in $(seq 1 "$t"); do s=$(os_status); [ "$s" = "$want" ] && { ok "OS em $want"; return 0; }; sleep 1; done; ko "OS esperada $want, atual $s"; return 1; }
wait_saga(){ local want=$1 t=${2:-30} s; for _ in $(seq 1 "$t"); do s=$(saga | jq -r '.data.status'); [ "$s" = "$want" ] && { ok "Saga em $want"; return 0; }; sleep 1; done; ko "Saga esperada $want, atual $s"; return 1; }

step "Login (OS Service emite JWT; Billing/Execution validam o mesmo JWT)"
ADM=$(curl -s -X POST $OS/api/auth/login -H 'Content-Type: application/json' -d '{"email":"admin@shop.com","password":"'"$SEED_PASSWORD"'"}' | jq -r .data)
MEC=$(curl -s -X POST $OS/api/auth/login -H 'Content-Type: application/json' -d '{"email":"mechanic@shop.com","password":"'"$SEED_PASSWORD"'"}' | jq -r .data)
[ ${#ADM} -gt 20 ] && ok "token admin" || { ko "login admin falhou"; exit 1; }
[ ${#MEC} -gt 20 ] && ok "token mecânico" || ko "login mecânico falhou"

step "Cadastros no OS Service: cliente, veículo, serviço e peça"
CPF=$(python3 -c "
import random
d=[random.randint(0,9) for _ in range(9)]
for n in (10,11):
    s=sum(a*b for a,b in zip(d,range(n,1,-1)));r=s*10%11;d.append(0 if r==10 else r)
print(''.join(map(str,d)))")
CUST=$(req POST $OS/api/users/create "$ADM" "{\"document\":\"$CPF\",\"name\":\"Cliente E2E\",\"email\":\"cli$RUN@e2e.com\",\"role\":\"CUSTOMER\",\"active\":true,\"password\":\"cli123\",\"phone\":\"11999998888\"}" | id_of)
PLATE=$(printf 'E%s%dA%02d' "$(tr -dc 'A-Z' </dev/urandom | head -c2)" $((RUN%10)) $((RUN%100)))
VEH=$(req POST $OS/api/vehicles/create "$ADM" "{\"licensePlate\":\"$PLATE\",\"brand\":\"Fiat\",\"model\":\"Uno\",\"year\":2015,\"color\":\"Azul\",\"ownerId\":\"$CUST\"}" | id_of)
SRV=$(req POST $OS/api/mechanic-services/create "$ADM" '{"name":"Troca de oleo E2E '$RUN'","estimatedTimeMinutes":60,"price":150.00}' | id_of)
PART=$(req POST $OS/api/stock-items/create "$ADM" "{\"code\":\"FLT$RUN\",\"name\":\"Filtro E2E\",\"type\":\"PART\",\"quantity\":10,\"costPrice\":20.00,\"salePrice\":45.50}" | id_of)
echo "   cliente=$CUST veiculo=$VEH servico=$SRV peca=$PART"
for v in CUST VEH SRV PART; do [[ ${!v} =~ ^[0-9a-f-]{36}$ ]] && ok "$v criado" || ko "$v não criado (${!v})"; done

step "Abrir OS (OS Service) -> Saga envia execution.diagnosis.start via RabbitMQ"
SO=$(req POST $OS/api/service-orders/create "$ADM" "{\"vehicleExternalId\":\"$VEH\",\"customerComplaint\":\"Barulho no motor\",\"odometerReading\":50000}" | id_of)
echo "   OS=$SO"
wait_status RECEIVED 10   # vai para DIAGNOSIS quando o mecânico inicia

step "Execution Service recebeu o comando? (fila de execução - MongoDB)"
for _ in $(seq 1 20); do W=$(req GET $EXE/api/executions/$SO "$MEC" 2>/dev/null | jq -r '.data.status // empty'); [ -n "$W" ] && break; sleep 1; done
[ "$W" = "DIAGNOSIS_QUEUED" ] && ok "ordem de trabalho na fila ($W)" || ko "ordem de trabalho não chegou ao Execution ($W)"

step "Mecânico inicia e conclui o diagnóstico (Execution) -> OS precifica -> Billing gera orçamento"
req POST $EXE/api/executions/$SO/diagnosis/start "$MEC" >/dev/null
req POST $EXE/api/executions/$SO/diagnosis/complete "$MEC" "{\"mechanicDiagnosis\":\"Filtro entupido\",\"labors\":[{\"mechanicServiceExternalId\":\"$SRV\",\"quantity\":1}],\"parts\":[{\"partExternalId\":\"$PART\",\"quantity\":2}]}" | jq -c '{status,message}'
wait_status PENDING_APPROVAL 30
B=$(req GET $BIL/api/billing/budgets/$SO "$ADM" | jq -c '.data | {status, totalAmount}'); echo "   orçamento: $B"
[ "$(echo "$B" | jq -r .totalAmount)" = "241" ] || [ "$(echo "$B" | jq -r .totalAmount)" = "241.00" ] && ok "total do orçamento = 150 + 2x45,50 = 241,00" || ko "total inesperado"

[ "$SCEN" = stop_at_budget ] && { echo "SO=$SO"; exit 0; }
if [ "$SCEN" = budget_rejected ]; then
  step "Cliente RECUSA o orçamento -> compensação: cancelar ordem de trabalho"
  req POST $BIL/api/billing/budgets/$SO/response "$ADM" '{"approved":false}' | jq -c '{status,message}'
  wait_status CANCELED 30; wait_saga CANCELED 10
  for _ in $(seq 1 15); do W=$(req GET $EXE/api/executions/$SO "$MEC" 2>/dev/null | jq -r .data.status); [ "$W" = CANCELED ] && break; sleep 1; done; [ "$W" = CANCELED ] && ok "Execution cancelou a ordem de trabalho" || ko "Execution: $W"
else
  step "Cliente APROVA o orçamento (Billing) -> OS reserva estoque -> Billing cria pagamento"
  req POST $BIL/api/billing/budgets/$SO/response "$ADM" '{"approved":true}' | jq -c '{status,message}'
  wait_status PENDING_PAYMENT 30
  STK=$(req GET $OS/api/stock-items/$PART "$ADM" | jq -r .data.quantity); [ "$STK" = 8 ] && ok "estoque reservado (10 -> 8)" || ko "estoque=$STK"
  for _ in $(seq 1 15); do P=$(req GET $BIL/api/billing/payments/$SO "$ADM" 2>/dev/null | jq -r '.data.status // empty'); [ -n "$P" ] && break; sleep 1; done
  echo "   pagamento: $P"

  if [ "$SCEN" = payment_refused ]; then
    step "Gateway RECUSA o pagamento -> compensação: devolver estoque, cancelar orçamento, cancelar trabalho"
    req POST $BIL/api/billing/payments/$SO/simulate "$ADM" '{"approved":false}' | jq -c '{status,message}'
    wait_saga COMPENSATED 30; wait_status CANCELED 5
    STK=$(req GET $OS/api/stock-items/$PART "$ADM" | jq -r .data.quantity); [ "$STK" = 10 ] && ok "estoque devolvido (8 -> 10)" || ko "estoque=$STK"
    BS=$(req GET $BIL/api/billing/budgets/$SO "$ADM" | jq -r .data.status); echo "   orçamento=$BS"
    for _ in $(seq 1 15); do W=$(req GET $EXE/api/executions/$SO "$MEC" 2>/dev/null | jq -r .data.status); [ "$W" = CANCELED ] && break; sleep 1; done; [ "$W" = CANCELED ] && ok "Execution cancelou a ordem de trabalho" || ko "Execution: $W"
  else
    step "Gateway APROVA o pagamento (simulado) -> Saga libera o reparo no Execution"
    req POST $BIL/api/billing/payments/$SO/simulate "$ADM" '{"approved":true}' | jq -c '{status,message}'
    wait_status IN_PROGRESS 30
    for _ in $(seq 1 15); do W=$(req GET $EXE/api/executions/$SO "$MEC" 2>/dev/null | jq -r .data.status); [ "$W" = REPAIR_QUEUED ] && break; sleep 1; done
    [ "$W" = REPAIR_QUEUED ] && ok "reparo na fila do Execution" || ko "Execution: $W"
    req POST $EXE/api/executions/$SO/repair/start "$MEC" >/dev/null

    if [ "$SCEN" = repair_failed ]; then
      step "Mecânico declara reparo INVIÁVEL -> compensação: estorno, devolução de estoque, cancelar orçamento"
      req POST $EXE/api/executions/$SO/repair/fail "$MEC" '{"reason":"Motor sem conserto"}' | jq -c '{status,message}'
      wait_saga COMPENSATED 40; wait_status CANCELED 5
      P=$(req GET $BIL/api/billing/payments/$SO "$ADM" | jq -r .data.status); [ "$P" = REFUNDED ] && ok "pagamento estornado" || ko "pagamento=$P"
      STK=$(req GET $OS/api/stock-items/$PART "$ADM" | jq -r .data.quantity); [ "$STK" = 10 ] && ok "estoque devolvido" || ko "estoque=$STK"
    else
      step "Mecânico conclui o reparo (Execution) -> OS COMPLETED -> entrega"
      req POST $EXE/api/executions/$SO/repair/complete "$MEC" | jq -c '{status,message}'
      wait_status COMPLETED 30
      req POST $OS/api/service-orders/$SO/deliver "$ADM" | jq -c '{status,message}'
      wait_status DELIVERED 5; wait_saga COMPLETED 10
    fi
  fi
fi

step "Log da Saga (persistido no OS Service)"
saga | jq -r '.data.history[] | "   \(.trigger) -> \(.step)/\(.status) \(.note // "")"'
echo; echo "== Cenário $SCEN: $PASS ok, $FAIL falhas =="; [ $FAIL -eq 0 ]
